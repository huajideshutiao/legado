package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.MangaPluginImageFetcherProviders
import io.legado.app.help.image.PluginImageUrl
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.book.read.page.provider.ChapterContentParserShared
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.concurrent.newConcurrentMap
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.readAllAndClose
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 章节图片下载编排 (四端统一, 对照 app 端 `BookHelp.saveImages / saveImage`)。
 *
 * 编排: 存在性短路 → per-src Mutex 去重 → 插件源图片分流 → 下载 → 解密脚本 →
 * 有效性校验 (失败仍落盘, 只记日志) → 原始字节写入缓存。文件命名与路径派生
 * (`MD5(url)16位.后缀`) 由各端 [BookImageStorage] 实现完成。
 *
 * 入口: 只落盘用 [saveImage] / [saveImages]; 还要拿到字节解码 (阅读页取图) 用
 * [loadImageBytes], 缓存命中与下载双向都由本对象编排, 同图不会出现一份解密一份没解密;
 * 把已看过的图导给用户 (阅读页/漫画页长按保存) 用 [imageBytesToSave], 只读缓存不下载。
 *
 * 依赖注入:
 * - [BookImageStorageProviders]: getImagePath / isImageExist / checkImageBytes / writeImageBytes 原语
 * - [AppConfigProviders]: 并发数语义 (`threadCount`)
 * - [AnalyzeUrlFactories]: 带书源防盗链头/代理/限流的下载
 */
object BookImageDownloadShared {

    /** 进行中的图片下载互斥 (key: 图片 src), 同 src 并发请求只下载一次。 */
    private val downloadImages = newConcurrentMap<String, Mutex>()

    /** [downloadImages] 登记互斥的锁 (getOrPut 非原子, 与原版 synchronized 包裹语义一致)。 */
    private val downloadImagesLock = SynchronizedObject()

    /**
     * 从章节正文提取图片 src 流 (对照 app 端 `BookHelp.flowImages`), 空白 src 跳过。
     */
    fun flowImages(bookChapter: BookChapter, content: String): Flow<String> {
        return flow {
            val imgList = ChapterContentParserShared.extractImages(content)
            for (i in imgList) {
                if (i.src.isBlank()) continue
                emit(i.src)
            }
        }
    }

    /**
     * 提取正文图片并并发下载 (对照 app 端 `BookHelp.saveImages`)。
     *
     * [concurrency] 取各端 `AppConfig.threadCount` 语义。
     */
    suspend fun saveImages(
        bookSource: BookSource?,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        concurrency: Int = AppConfigProviders.get().threadCount
    ) = coroutineScope {
        flowImages(bookChapter, content).onEachParallel(concurrency) { mSrc ->
            saveImage(bookSource, book, mSrc, bookChapter)
        }.collect()
    }

    /**
     * 下载并缓存单张图片 (对照 app 端 `BookHelp.saveImage`)。
     *
     * - 存在性双检 (进入 Mutex 前后各一次), 同 src 并发只下载一次
     * - 插件源图片 (内部协议形态) 委托扩展自身 client (拦截器在官方通道内生效)
     * - 下载后执行书源解密脚本, 校验失败仍落盘 (避免每次进正文反复重新下载失效图)
     * - 失败只记日志不抛出 (与 app 端一致); [chapter] 仅用于日志
     */
    suspend fun saveImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter? = null
    ) {
        downloadImage(bookSource, book, src, chapter)
    }

    /**
     * 取单张图片字节 (对照 app 端 `ImageProvider.cacheImage` + `getImageSize` 的读取段)。
     *
     * 磁盘缓存命中直接读缓存文件; 未命中走 [saveImage] 同一条下载编排, 返回本次落盘的
     * (已解密) 字节; 同 src 并发时本次下载被去重, 返回先落盘那份的字节。
     * 缓存文件读取失败 / 下载失败返回 null, 调用方按取图失败处理。
     */
    suspend fun loadImageBytes(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter? = null
    ): ByteArray? {
        cachedImageBytes(book, chapter, src)?.let { return it }
        return downloadImage(bookSource, book, src, chapter)
            ?: cachedImageBytes(book, chapter, src)
    }

    /** 读图片缓存文件字节; 文件不存在 / 读不出 / 空文件返回 null。 */
    private fun cachedImageBytes(book: Book, chapter: BookChapter?, src: String): ByteArray? =
        BookImageStorageProviders.get()
            .getImagePath(book, chapter.forStorage(book), src)
            ?.let { FileUtilsCommon.readBytes(it) }
            ?.takeIf { it.isNotEmpty() }

    /**
     * 取"保存图片"要落盘的字节 (对照 app 端 `BaseReadViewModel.saveImage` 的读取段):
     * 图片缓存文件命中即返回缓存字节; 未命中且是本地书时返回书文件内嵌图字节; 两处都没有返回 null。
     *
     * 不回落下载 —— 保存只把已经拿到手的字节写到用户选定的位置, 不因导出触发新的网络请求;
     * 原版此时什么都不输出, 其固定提示"保存图片成功"是错报 (不复刻, 由调用方按 null 报失败)。
     *
     * 读取含文件 IO, 统一在 [IoDispatcher] 执行 (调用方可能在主线程回调里直接调)。
     */
    suspend fun imageBytesToSave(
        book: Book,
        src: String,
        chapter: BookChapter? = null
    ): ByteArray? = withContext(IoDispatcher) {
        cachedImageBytes(book, chapter, src) ?: localBookImageBytes(book, src)
    }

    /**
     * 本地书内嵌图 [src] 的字节: 这类 src 是书文件内路径 (无 scheme), 没有可下载的地址,
     * 阅读页取图对本地书就走书内嵌图分支, 从不把它落进图片缓存。
     *
     * 取不到 / 空流按无图处理 (空字节写出去只是一份 0 字节文件, 不当作保存成功)。
     */
    private fun localBookImageBytes(book: Book, src: String): ByteArray? {
        if (!book.isLocal) return null
        return runCatching {
            FileBook.getImage(book, src)?.readAllAndClose()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 下载单张图片并按 [BookImageStorage.writeImageBytes] 落盘, 返回本次写入的 (已解密)
     * 字节; 落盘前命中缓存 / 下载失败 / 解密脚本返回空返回 null。
     */
    private suspend fun downloadImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter?
    ): ByteArray? {
        val storage = BookImageStorageProviders.get()
        val ch = chapter.forStorage(book)
        if (storage.isImageExist(book, ch, src)) {
            return null
        }
        val mutex = synchronized(downloadImagesLock) {
            downloadImages.getOrPut(src) { Mutex() }
        }
        mutex.lock()
        try {
            if (storage.isImageExist(book, ch, src)) {
                return null
            }
            // 插件源图片: 内部协议形态 → 委托扩展自身 client (拦截器还原), 与
            // MangaImageBytesLoader 插件分支同构; 失败抛异常走下方统一 catch
            val bytes = PluginImageUrl.parse(src)?.let { (sourceId, encodedUrl) ->
                MangaPluginImageFetcherProviders.getOrNull()
                    ?.fetchImage(sourceId, encodedUrl)
                    ?: throw RuntimeException("插件源图片下载失败: $src")
            } ?: AnalyzeUrlFactories.create(
                rawUrl = src,
                source = bookSource,
                coroutineContext = currentCoroutineContext()
            ).getByteArrayAwait()
            //某些图片被加密，需要进一步解密
            val decoded = runScriptWithContext {
                ImageUtils.decode(
                    src, bytes, isCover = false, bookSource, book
                )
            } ?: return null
            if (!storage.checkImageBytes(decoded)) {
                // 如果部分图片失效，每次进入正文都会花很长时间再次获取图片数据
                // 所以无论如何都要将数据写入到文件里
                AppLog.put("${book.name} ${chapter?.title} 图片 $src 下载错误 数据异常")
            }
            storage.writeImageBytes(book, ch, src, decoded)
            return decoded
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("${book.name} ${chapter?.title} 图片 $src 下载失败\n${e.message}", e)
            return null
        } finally {
            downloadImages.remove(src)
            mutex.unlock()
        }
    }

    /**
     * 缓存 key 用的章节: [BookImageStorage] 的路径只由 book+url 派生, chapter 仅参与签名,
     * 调用方只拿来记日志 (可为 null) 时用同一本书的占位章节。
     */
    private fun BookChapter?.forStorage(book: Book): BookChapter =
        this ?: BookChapter(bookUrl = book.bookUrl)
}
