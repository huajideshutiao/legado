package io.legado.app.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.format.epub.EpubBook
import io.legado.app.format.epub.EpubChapter
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
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.InputStream
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.isXml
import io.legado.app.utils.toInputStream
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.select.Elements
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext

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
 * - 远程文件 (webDav/http): 复用 nativeMain WebDav actual (Ktor CIO) 下载到本地缓存文件,
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
        private var eFile: EpubFile? = null

        private fun getEFile(book: Book): EpubFile = synchronized(this) {
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

        override fun upBookInfo(book: Book) = synchronized(this) {
            getEFile(book).upBookInfo()
        }

        override fun clear() {
            eFile = null
        }
    }

    // EPUB 解析结果 (内存缓存, 构造期触发 readEpub)
    @Volatile
    private var epubBook: EpubBook? = null
        get() = field ?: synchronized(this) {
            field ?: readEpub().also { field = it }
        }

    // spine 阅读顺序资源 (对应 jvm 端 epubBookContents)
    @Volatile
    private var spineContents: List<EpubResource>? = null
        get() = field ?: synchronized(this) {
            field ?: epubBook?.spine?.also { field = it }
        }

    // parseFirstPage / parseMenu 共享的章节序号计数器
    private var durIndex = 0

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
            AppLog.put("读取Epub文件失败\n${it.localizedMessage}", it)
            it.printStackTraceOnDebug()
        }.getOrNull()
    }

    /**
     * 远程 epub (webDav/http) 加载。
     *
     * 复用 nativeMain WebDav actual (Ktor CIO) 下载远程 epub 到本地缓存文件,
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

    private fun getContent(chapter: BookChapter): String? {
        val contents = spineContents ?: return null
        val nextChapterFirstResourceHref = chapter.getVariable("nextUrl").substringBeforeLast("#")
        val currentChapterFirstResourceHref = chapter.url.substringBeforeLast("#")
        val isLastChapter = nextChapterFirstResourceHref.isBlank()
        val startFragmentId = chapter.startFragmentId
        val endFragmentId = chapter.endFragmentId
        val elements = Elements()
        var findChapterFirstSource = false
        val includeNextChapterResource = !endFragmentId.isNullOrBlank()
        // 一些书籍依靠href索引的resource会包含多个章节, 需依据fragmentId截取当前章节内容
        for (res in contents) {
            if (!findChapterFirstSource) {
                if (currentChapterFirstResourceHref != res.href) continue
                findChapterFirstSource = true
                // 第一个xhtml文件
                elements.add(getBody(res, startFragmentId, endFragmentId))
                // 不是最后章节 且 已经遍历到下一章节的内容时停止
                if (!isLastChapter && res.href == nextChapterFirstResourceHref) break
                continue
            }
            if (nextChapterFirstResourceHref != res.href) {
                // 其余部分
                elements.add(getBody(res, null, null))
            } else {
                // 下一章节的第一个xhtml
                if (includeNextChapterResource) {
                    // 有Fragment 则添加到上一章节
                    elements.add(getBody(res, null, endFragmentId))
                }
                break
            }
        }
        // title标签中的内容不需要显示在正文中, 去除
        elements.select("title").remove()
        elements.select("[style*=display:none]").remove()
        elements.select("img").forEach {
            if (it.attributesSize() <= 1) {
                return@forEach
            }
            val src = it.attr("src")
            it.clearAttributes()
            it.attr("src", src)
        }
        val tag = Book.rubyTag
        if (book.config.delTag and tag == tag) {
            elements.select("rp, rt").remove()
        }
        val html = elements.outerHtml()
        return HtmlFormatter.formatKeepImg(html)
    }

    private fun getBody(res: EpubResource, startFragmentId: String?, endFragmentId: String?): Element {
        // Ksoup 可能会修复不规范的 xhtml 文件, 解析处理后再获取
        var bodyElement = Ksoup.parse(res.data.decodeToString()).body()
        bodyElement.children().run {
            select("script").remove()
            select("style").remove()
        }
        var bodyString = bodyElement.outerHtml()
        val originBodyString = bodyString
        // 某些 xhtml 文件章节标题和内容不在一个节点或不是兄弟节点, 用 FragmentId 截取
        if (!startFragmentId.isNullOrBlank()) {
            bodyElement.getElementById(startFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = tagStart + bodyString.substringAfter(tagStart)
            }
        }
        if (!endFragmentId.isNullOrBlank() && endFragmentId != startFragmentId) {
            bodyElement.getElementById(endFragmentId)?.outerHtml()?.let {
                val tagStart = it.substringBefore("\n")
                bodyString = bodyString.substringBefore(tagStart)
            }
        }
        // 截取过再重新解析
        if (bodyString != originBodyString) {
            bodyElement = Ksoup.parse(bodyString).body()
        }
        // 去除正文中的 H 标签 (部分书籍标题与阅读标题重复)
        val tag = Book.hTag
        if (book.config.delTag and tag == tag) {
            bodyElement.run {
                select("h1, h2, h3, h4, h5, h6").remove()
            }
        }
        // SVG <image> 转 <img>
        bodyElement.select("image").forEach {
            it.tagName("img")
            it.attr("src", it.attr("xlink:href"))
        }
        // 解析 img src 为 zip 内绝对路径 (供 getImage 查找)
        bodyElement.select("img").forEach {
            val src = it.attr("src").trim()
            if (src.isDataUrl()) {
                it.attr("src", src)
            } else {
                // 用 EpubParser.resolvePath 规范化相对路径为 zip 内绝对路径
                val resolvedHref = EpubParser.resolvePath(res.href, src)
                it.attr("src", resolvedHref)
            }
        }
        return bodyElement
    }

    private fun getImage(href: String): InputStream? {
        val resources = epubBook?.resources ?: return null

        // 精确匹配原始 href
        resources[href]?.let { return it.data.toInputStream() }

        // 兜底: 按文件名模糊匹配 (处理路径前缀不匹配的情况)
        val fileName = href.substringAfterLast("/")
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
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
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

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        epubBook?.let { epub ->
            val toc = epub.toc
            if (toc.isEmpty()) {
                // 目录为空时退化为 spine 资源列表
                AppLog.putDebug("Epub: NCX/nav 目录为空, 退化为 spine. file: ${book.bookUrl}")
                val spine = epub.spine
                var i = 0
                while (i < spine.size) {
                    val resource = spine[i]
                    var title = extractTitleFromResource(resource)
                    val chapter = BookChapter()
                    chapter.index = i
                    chapter.bookUrl = book.bookUrl
                    chapter.url = resource.href
                    if (i == 0 && title.isEmpty()) {
                        chapter.title = "封面"
                    } else {
                        chapter.title = title
                    }
                    chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
                    chapterList.add(chapter)
                    i++
                }
            } else {
                parseFirstPage(chapterList, toc)
                parseMenu(chapterList, toc, 0)
                for (i in chapterList.indices) {
                    chapterList[i].index = i
                }
            }
        }
        return chapterList
    }

    /** 从 xhtml 资源的 <title> 标签提取标题。 */
    private fun extractTitleFromResource(resource: EpubResource): String {
        return runCatching {
            val doc = Ksoup.parse(resource.data.decodeToString())
            val elements = doc.getElementsByTag("title")
            if (elements.isNotEmpty()) elements[0].text() else ""
        }.getOrDefault("")
    }

    /**
     * 获取书籍起始页内容 (封面/引言/扉页等, 第一章之前的内容)。
     *
     * 遍历 spine 中在第一个 toc 条目之前的资源, 为每个创建"卷首"章节。
     */
    private fun parseFirstPage(
        chapterList: ArrayList<BookChapter>,
        toc: List<EpubChapter>
    ) {
        val contents = spineContents ?: return
        val firstRef = toc.firstOrNull { it.resource != null } ?: return
        val firstRefHref = firstRef.completeHref.substringBeforeLast("#")
        var i = 0
        durIndex = 0
        while (i < contents.size) {
            val content = contents[i]
            // 检索到第一章 href 停止
            if (firstRefHref == content.href) break
            val chapter = BookChapter()
            var title = extractTitleFromResource(content)
            if (title.isEmpty()) {
                title = "--卷首--"
            }
            chapter.bookUrl = book.bookUrl
            chapter.title = title
            chapter.url = content.href
            // 提取 fragmentId (# 后部分)
            val fragmentPart = content.href.substringAfter("#", "")
            chapter.startFragmentId = fragmentPart.takeIf { it.isNotEmpty() && it != content.href }

            chapterList.lastOrNull()?.endFragmentId = chapter.startFragmentId
            chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
            chapterList.add(chapter)
            durIndex++
            i++
        }
    }

    /** 递归解析 toc 层级目录为章节列表。 */
    private fun parseMenu(
        chapterList: ArrayList<BookChapter>,
        toc: List<EpubChapter>,
        level: Int
    ) {
        toc.forEach { ref ->
            if (ref.resource != null) {
                val chapter = BookChapter()
                chapter.bookUrl = book.bookUrl
                chapter.title = ref.title
                chapter.url = ref.completeHref
                chapter.startFragmentId = ref.fragmentId
                chapterList.lastOrNull()?.endFragmentId = chapter.startFragmentId
                chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
                chapterList.add(chapter)
                durIndex++
            }
            if (ref.children.isNotEmpty()) {
                chapterList.lastOrNull()?.isVolume = true
                parseMenu(chapterList, ref.children, level + 1)
            }
        }
    }

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
