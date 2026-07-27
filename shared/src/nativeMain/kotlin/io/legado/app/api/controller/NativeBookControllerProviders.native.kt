package io.legado.app.api.controller

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.model.ReadBookShared
import kotlinx.coroutines.runBlocking
import io.legado.app.utils.File

/**
 * nativeMain: [ImageControllerProvider] 的 iOS/鸿蒙最小真实实现。
 *
 * 无平台位图缩放/默认封面绘制 (app 端走 Coil3+Bitmap), 直接返回本地缓存原始字节;
 * 任何失败返回 null → 调用方回 "getCover/getImg error", 不抛异常。
 */
object NativeImageControllerProvider : ImageControllerProvider {

    // 与 app 端 BookControllerImageProviderImpl 一致: 按 bookUrl 缓存 book, 避免重复查库
    private var book: Book? = null
    private var bookUrl: String = ""

    override fun getCover(coverPath: String?): ByteArray? = runCatching {
        if (coverPath.isNullOrBlank()) return@runCatching null
        if (coverPath.startsWith("http://", true) || coverPath.startsWith("https://", true)) {
            return@runCatching runBlocking { downloadBytes(coverPath) }
        }
        val file = File(coverPath.removePrefix("file://"))
        if (file.exists()) file.readBytes() else null
    }.getOrNull()

    /** width 参数忽略 (native 端无位图缩放); 缓存未命中时经 BookImageStorage 下载一次再读。 */
    override fun getImg(bookUrl: String, src: String, width: Int): ByteArray? = runCatching {
        val cached = if (this.bookUrl == bookUrl) this.book else null
        val book = cached
            ?: runBlocking { AppDbProviders.get().bookDao.getBook(bookUrl) }?.also {
                this.book = it
                this.bookUrl = bookUrl
            }
            ?: return@runCatching null
        val storage = BookImageStorageProviders.get()
        // NativeBookImageStorage 路径仅由 book+url 派生, chapter 只参与签名, 占位即可
        val chapter = BookChapter(url = src, bookUrl = bookUrl)
        var path = storage.getImagePath(book, chapter, src)
        if (path == null) {
            runBlocking { storage.saveImages(book, chapter, listOf(src)) }
            path = storage.getImagePath(book, chapter, src)
        }
        path?.let { File(it).takeIf(File::exists)?.readBytes() }
    }.getOrNull()

    private suspend fun downloadBytes(url: String): ByteArray? {
        val client = HttpClient(CIO)
        return try {
            val response = client.get(url)
            if (response.status.isSuccess()) response.bodyAsBytes() else null
        } finally {
            client.close()
        }
    }
}

/**
 * nativeMain: [ReadBookStateProvider] 的 iOS/鸿蒙桥接。
 *
 * [ReadBookShared] 是阅读页 Compose 内的实例 (无全局单例), 阅读页进入/退出时经
 * [attach]/[detach] 挂接; 未挂接时各字段为 null, BookController 跳过 ReadBook 同步
 * (与未注册行为一致, 但保留统一接入点)。
 */
object NativeReadBookStateProvider : ReadBookStateProvider {

    @Volatile
    private var readBook: ReadBookShared? = null

    /** 阅读页进入时挂接当前 [ReadBookShared] 实例。 */
    fun attach(readBook: ReadBookShared) {
        this.readBook = readBook
    }

    /** 阅读页退出时解除挂接。 */
    fun detach() {
        readBook = null
    }

    override val currentBookUrl: String? get() = readBook?.book?.value?.bookUrl
    override val currentBookName: String? get() = readBook?.book?.value?.name
    override val currentBookAuthor: String? get() = readBook?.book?.value?.author

    override fun clearCurrentBook() {
        // ReadBookShared 无置空 book 的公开 API, 解除挂接达成"无正在阅读的书"语义
        readBook = null
    }

    override fun setWebBookProgress(progress: BookProgress) {
        readBook?.updateWebBookProgress(progress)
    }
}

/**
 * 注册 [ImageControllerProviders] + [ReadBookStateProviders] (iOS/鸿蒙共用, Web 服务
 * /cover /image /deleteBook /saveBookProgress 依赖)。未注册时前两者抛 IllegalStateException。
 */
fun registerNativeBookControllerProviders() {
    ImageControllerProviders.register(NativeImageControllerProvider)
    ReadBookStateProviders.register(NativeReadBookStateProvider)
}
