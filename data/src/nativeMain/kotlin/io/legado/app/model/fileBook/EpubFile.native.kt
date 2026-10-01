package io.legado.app.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.format.epub.EpubBook
import io.legado.app.format.epub.EpubContentReader
import io.legado.app.format.epub.chapterList
import io.legado.app.format.epub.EpubParser
import io.legado.app.format.epub.EpubResource
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.lib.webdav.WebDav
import io.legado.app.utils.File
import io.legado.app.utils.InputStream
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isXml
import io.legado.app.utils.toInputStream
import com.fleeksoft.ksoup.Ksoup
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * EpubFile epub 解析 (nativeMain, iOS/鸿蒙共用, 纯 Kotlin 实现)。
 *
 * # 背景
 * jvmAndAndroidMain 端 [EpubFile] 依赖 `io.legado.app.lib.epublib.*` (JVM-only,
 * 内部用 java.xml.parsers / java.util.zip), iOS/鸿蒙 (Kotlin/Native) 不可见。
 * 本文件用 commonMain 下沉的 [EpubParser] (纯 Kotlin + Ksoup + [unzipEpubEntries] expect/actual)
 * 实现 EPUB 2.0 / 3.0 解析 (原 ohosMain EpubFile.ohos.kt 上移到 nativeMain, iOS 端同时受益)。
 *
 * # 设计
 * - 本地文件: [LocalBookLocators] 解析 bookUrl → 本地路径 → [kotlin.io.File.readBytes]
 *   → [EpubParser.parse] → [EpubBook] (内存模型)
 * - 远程文件 (webDav/http): 复用 nativeMain WebDav actual (iOS 用 Darwin、鸿蒙用 CIO) 下载到本地缓存文件,
 *   再走 [EpubParser.parse] 解析 (与 jvm 端 RemoteZipWrapper 按需加载不同, native 端全量下载)
 * - 封面图片: [BitmapProviders] (iOS/鸿蒙已注册各自实现) 解码压缩为 JPEG 写入文件;
 *   封面路径统一走 [FileBook.getCoverPath] 门面 (md5Encode16, 与全端一致)
 * - 章节/正文: 逻辑与 jvmAndAndroidMain [EpubFile] 对齐 (parseFirstPage / parseMenu /
 *   getContent / getBody), 数据模型从 epublib EpubBook/Resource/TOCReference 替换为
 *   [EpubBook] / [EpubResource] / [EpubChapter]
 *
 * # 与 jvmAndAndroidMain [EpubFile] 的差异
 * - 无 epublib 依赖 (改用 [EpubParser] commonMain 纯 Kotlin 解析器)
 * - 无 RemoteZipWrapper 按需加载 (native 端用 WebDav 全量下载到缓存文件, 详见 [readEpubRemote])
 * - 图片 src 路径解析用 [EpubParser.resolvePath] (纯路径规范化) 替代 java.net.URI.resolve
 * - charset 固定 UTF-8 (EPUB 标准要求 XHTML 为 UTF-8; jvm 端 mCharset 仅为兼容异常文件)
 *
 * # 接口契约
 * 实现 [BaseFileBook] 五方法: [upBookInfo] / [getChapterList] / [getContent] / [getImage] / [clear]。
 * 与 jvm 端 companion object 单例缓存模式一致 (按 bookUrl 复用 [EpubFile] 实例)。
 */
class EpubFile(var book: Book) {

    companion object : BaseFileBook {
        // Kotlin/Native 无 @Synchronized, 以 atomicfu SynchronizedObject 替代 (同 TextFile.native.kt)
        private val lock = SynchronizedObject()
        private var eFile: EpubFile? = null

        private fun getEFile(book: Book): EpubFile = synchronized(lock) {
            if (eFile == null || eFile?.book?.bookUrl != book.bookUrl) {
                eFile = EpubFile(book)
                return@synchronized eFile!!
            }
            eFile?.book = book
            eFile!!
        }

        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getEFile(book).getChapterList()
        }

        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getEFile(book).getContent(chapter)
        }

        override fun getImage(book: Book, href: String): InputStream? {
            return getEFile(book).getImage(href)
        }

        override fun upBookInfo(book: Book) = synchronized(lock) {
            getEFile(book).upBookInfo()
        }

        override fun clear() {
            eFile = null
        }
    }

    // 实例级懒加载锁 (Kotlin/Native 无 synchronized(this))
    private val instanceLock = SynchronizedObject()

    // EPUB 解析结果 (内存缓存, 构造期触发 readEpub)
    @Volatile
    private var epubBook: EpubBook? = null
        get() = field ?: synchronized(instanceLock) {
            field ?: readEpub().also { field = it }
        }

    // spine 阅读顺序资源 (对应 jvm 端 epubBookContents)
    @Volatile
    private var spineContents: List<EpubResource>? = null
        get() = field ?: synchronized(instanceLock) {
            field ?: epubBook?.spine?.also { field = it }
        }

    init {
        upBookCover(true)
    }

    /**
     * 读取并解析 epub 文件为 [EpubBook]。
     *
     * 本地路径走 [LocalBookLocators] → [kotlin.io.File.readBytes] → [EpubParser.parse];
     * 远程路径 (webDav/http) 走 [readEpubRemote] 下载缓存后解析。
     */
    private fun readEpub(): EpubBook? {
        return runCatching {
            when {
                book.bookUrl.startsWith(BookType.webDavTag) || book.bookUrl.startsWith("http") -> {
                    readEpubRemote()
                }
                else -> {
                    // 本地路径: LocalBookLocators 解析 → readBytes → EpubParser.parse
                    val path = LocalBookLocators.get().getLocalPath(book)
                        ?: book.bookUrl.removePrefix("file://")
                    val bytes = File(path).readBytes()
                    EpubParser.parse(bytes)
                }
            }
        }.onFailure {
            AppLog.put("读取Epub文件失败\n${it.message}", it)
            it.printStackTraceOnDebug()
        }.getOrNull()
    }

    /**
     * 远程 epub (webDav/http) 加载。
     *
     * 复用 nativeMain WebDav actual (iOS 用 Darwin、鸿蒙用 CIO) 下载远程 epub 到本地缓存文件,
     * 后续打开复用缓存避免重复下载; 解析仍走 commonMain [EpubParser]。
     *
     * 与 jvmAndAndroidMain 差异: jvm 端用 RemoteZipWrapper 按需 Range 读取 (不全量下载),
     * native 端未下沉 RemoteZipWrapper (依赖 epublib), 此处全量下载到缓存文件。
     *
     * 注: WebDav.downloadTo 为 suspend, readEpub 走同步路径 (epubBook 懒加载 getter),
     * 用 [runBlockingInScope] 阻塞调用 (与 nativeMain WebDav.readRange 同模式, 须在后台线程调用)。
     */
    private fun readEpubRemote(): EpubBook? {
        val url = book.getRemoteUrl() ?: book.bookUrl
        // webDav 协议从 URL 解析 serverID 构造; http 协议无 serverID, 回退到 AppWebDavShared.authorization
        val webDav = runCatching {
            WebDav.fromPath(url)
        }.getOrElse {
            AppWebDavShared.authorization?.let { auth -> WebDav(url, auth) } ?: run {
                AppLog.put("Epub: WebDav 未配置, 无法加载远程 epub. url: $url")
                return null
            }
        }
        val cachePath = getEpubCachePath(book.bookUrl)
        val cacheFile = File(cachePath)
        // 缓存文件不存在时下载 (downloadTo 内部先全量下载再写文件, 失败不产生残缺文件)
        if (!cacheFile.exists()) {
            cacheFile.parentFile?.mkdirs()
            runBlockingInScope(EmptyCoroutineContext) {
                webDav.downloadTo(cachePath, true)
            }
        }
        return EpubParser.parse(cacheFile.readBytes())
    }

    private fun getContent(chapter: BookChapter): String = EpubContentReader.read(
        spineContents ?: error("EPUB 解析失败，无法读取正文"),
        chapter,
        book.config.delTag,
    )

    private fun getImage(href: String): InputStream? {
        val resources = epubBook?.resources ?: return null

        // 精确匹配原始 href
        resources[href]?.let { return it.data.toInputStream() }
        val decodedHref = EpubParser.decodeHref(href)
        resources[decodedHref]?.let { return it.data.toInputStream() }

        // 兜底: 按文件名模糊匹配 (处理路径前缀不匹配的情况)
        val fileName = decodedHref.substringAfterLast("/")
        if (fileName.isNotEmpty()) {
            resources.values.find { resource ->
                val resourceHref = resource.href
                resourceHref.equals(fileName, ignoreCase = true)
                    || resourceHref.endsWith("/$fileName", ignoreCase = true)
            }?.let { return it.data.toInputStream() }
        }

        return null
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            epubBook?.let { epub ->
                if (book.coverUrl.isNullOrEmpty()) {
                    // 封面路径统一走 FileBook 门面 (md5Encode16, 与 app/desktop 端一致)
                    book.coverUrl = FileBook.getCoverPath(book.bookUrl)
                }
                if (fastCheck && File(book.coverUrl!!).exists()) {
                    return
                }
                // 封面图片资源 → BitmapProviders 解码压缩为 JPEG 写入文件
                epub.coverImage?.data?.let { bytes ->
                    if (bytes.isEmpty()) {
                        AppLog.putDebug("Epub: 封面字节为空. path: ${book.bookUrl}")
                        return
                    }
                    val input = bytes.toInputStream()
                    val coverFile = File(book.coverUrl!!)
                    coverFile.parentFile?.mkdirs()
                    val outFile = File(book.coverUrl!!)
                    BitmapProviders.get().decodeStreamAndCompressToJpeg(input, outFile, 90)
                } ?: AppLog.putDebug("Epub: 封面获取为空. path: ${book.bookUrl}")
            }
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.message}", e)
            e.printStackTraceOnDebug()
        }
    }

    private fun upBookInfo() {
        if (epubBook == null) {
            eFile = null
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            val metadata = epubBook!!.metadata
            book.name = metadata.firstTitle
            if (book.name.isEmpty()) {
                book.name = book.originName.replace(".epub", "")
            }

            if (metadata.authors.isNotEmpty()) {
                val author = metadata.authors[0].replace("^, |, $".toRegex(), "")
                book.author = author
            }
            if (metadata.descriptions.isNotEmpty()) {
                val desc = metadata.descriptions[0]
                book.intro = if (desc.isXml()) {
                    Ksoup.parse(desc).text()
                } else {
                    desc
                }
            }
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> =
        epubBook?.chapterList(book.bookUrl) ?: arrayListOf()

    /**
     * 构造远程 epub 缓存路径: `{BookStorage.rootPath}/epubCache/{md5(bookUrl)}.epub`。
     *
     * md5(bookUrl) 做文件名, 避免特殊字符。
     */
    private fun getEpubCachePath(bookUrl: String): String {
        val rootPath = BookStorageProviders.get().rootPath
        val cacheDir = if (rootPath.endsWith("/")) "${rootPath}epubCache" else "$rootPath/epubCache"
        return "$cacheDir/${MD5Utils.md5Encode(bookUrl)}.epub"
    }
}
