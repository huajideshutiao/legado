package io.legado.app.api.controller

import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import coil3.toBitmap
import io.legado.app.App
import io.legado.app.api.controller.BookControllerImageProviderImpl.getCover
import io.legado.app.api.controller.BookControllerImageProviderImpl.getImg
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

/**
 * [ImageControllerProvider] 的 Android 实现。
 *
 * 原 app 端 `object BookController` 中 getCover/getImg 两个图片方法依赖
 * android.graphics.Bitmap + Glide (ImageLoader.loadBitmap) + ImageProvider,
 * 均为 Android-only, 无法下沉 commonMain。本实现把这些 Android 专属逻辑
 * 集中包装, 在 App.onCreate 经 [ImageControllerProviders.register] 注册,
 * 供 shared/commonMain 端 [BookController.getCover]/[getImg] 走 provider 间接调用。
 *
 * # 状态持有
 * 内部持有原 BookController 的实例状态 (book/bookSource/bookUrl),
 * 行为与原 app 端 getImg 的 `this.book`/`this.bookSource`/`this.bookUrl` 缓存一致,
 * 避免 bookUrl 未变时重复查库。
 *
 * # 行为等价
 * - [getCover]: Coil3 execute(84x112, FILL) → 3s 超时 → 失败回退默认封面,
 *   与原 app 端 (Glide loadBitmap+centerCrop) 语义等价。
 * - [getImg]: 按 bookUrl 缓存 book/bookSource, ImageProvider.cacheImage + getImage,
 *   返回 PNG 字节流, 与原 app 端逐字等价。
 *
 * # 错误处理
 * shared 端 [BookController.getImg] 已按原版口径先查库校验 bookUrl (查无返回 "bookUrl不对"),
 * 本实现返回 null 时对应取图失败 ("getImg error"), 与原 app 端行为一致。
 *
 * 注册时机: App.onCreate 经 [registerAndroidWebBookProviders]。
 * 模式参考 [io.legado.app.model.webBook.WebBookProvidersImpl]。
 */
object BookControllerImageProviderImpl : ImageControllerProvider {

    /** 当前 getImg 缓存的 book (bookUrl 未变时复用, 避免重复查库)。 */
    private lateinit var book: Book

    /** 当前 getImg 缓存的 bookSource (随 book 一起加载, 可能为 null)。 */
    private var bookSource: BookSource? = null

    /** 当前 getImg 缓存的 bookUrl (用于判断是否需要重新加载 book/bookSource)。 */
    private var bookUrl: String = ""

    /**
     * 获取封面图片字节流 (PNG), 对应原 app 端 BookController.getCover。
     *
     * 流程: Coil3 execute(84x112, FILL) → 3s 超时 →
     * useDefaultCover/空路径/失败时回退默认封面 → 仍失败返回 null。
     */
    override fun getCover(coverPath: String?): ByteArray? {
        return try {
            runBlocking { withTimeout(3.seconds) { loadCoverForWeb(coverPath) } }
                .encodeToBytes()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadCoverForWeb(path: String?): Bitmap {
        val loader = coil3.SingletonImageLoader.get(App.instance)
        val data: Any = if (AppConfig.useDefaultCover || path.isNullOrBlank()) {
            BookCover.newDefaultDrawable()
        } else {
            path
        }
        val request = ImageRequest.Builder(App.instance as PlatformContext)
            .data(data)
            .size(84, 112)
            .scale(Scale.FILL)
            .build()
        val result = loader.execute(request)
        return (result as? SuccessResult)?.image?.toBitmap()
            ?: BookCover.newDefaultDrawable().toBitmap()
    }

    /**
     * 获取正文图片字节流 (PNG), 对应原 app 端 BookController.getImg。
     *
     * 流程: 按 bookUrl 缓存 book/bookSource (bookUrl 变化时重新查库) →
     * ImageProvider.cacheImage 预缓存 → ImageProvider.getImage 取图 →
     * 编码为 PNG 字节流。bookUrl 不对或获取失败返回 null (调用方 setErrorMsg)。
     */
    override fun getImg(bookUrl: String, src: String, width: Int): ByteArray? {
        if (this.bookUrl != bookUrl) {
            this.book = runBlocking { appDb.bookDao.getBook(bookUrl) }
                ?: return null
            this.bookSource = runBlocking { appDb.bookSourceDao.getBookSource(book.origin) }
        }
        this.bookUrl = bookUrl
        val bitmap = runBlocking {
            ImageProvider.cacheImage(book, src, bookSource)
            ImageProvider.getImage(book, src, width)
        }
        return bitmap.encodeToBytes()
    }

    /**
     * Bitmap 编码为 PNG 字节流, 供 web 出图 (原 app 端 BookController 私有扩展)。
     */
    private fun Bitmap.encodeToBytes(): ByteArray {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return outputStream.toByteArray().also { outputStream.close() }
    }
}
