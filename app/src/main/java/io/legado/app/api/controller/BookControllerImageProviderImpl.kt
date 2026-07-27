package io.legado.app.api.controller

import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

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
 * 内部持有原 BookController 的实例状态 (book/bookSource/bookUrl/defaultCoverBitmap),
 * 行为与原 app 端 getImg 的 `this.book`/`this.bookSource`/`this.bookUrl` 缓存一致,
 * 避免 bookUrl 未变时重复查库。
 *
 * # 行为等价
 * - [getCover]: ImageLoader.loadBitmap(84x112, centerCrop) → 3s 超时 → 失败回退默认封面
 *   (BookCover.newDefaultDrawable 缓存为 defaultCoverBitmap), 与原 app 端逐字等价。
 * - [getImg]: 按 bookUrl 缓存 book/bookSource, ImageProvider.cacheImage + getImage,
 *   返回 PNG 字节流, 与原 app 端逐字等价。
 *
 * # 错误处理差异
 * 原 app 端 getImg 在 bookUrl 不对时返回 "bookUrl不对" 错误消息;
 * shared 端 [BookController.getImg] 在本实现返回 null 时统一返回 "getImg error"。
 * 接口约束下无法区分错误类型, 行为差异可接受 (均为获取失败语义)。
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

    /** 默认封面 Bitmap 缓存 (getCover 失败回退用, 避免每次重新解码 BookCover.newDefaultDrawable)。 */
    private var defaultCoverBitmap: Bitmap? = null

    /**
     * 获取封面图片字节流 (PNG), 对应原 app 端 BookController.getCover。
     *
     * 流程: ImageLoader.loadBitmap(84x112, centerCrop) → 3s 超时获取 →
     * 失败时回退默认封面 (BookCover.newDefaultDrawable, 缓存为 defaultCoverBitmap) →
     * 仍失败返回 null (调用方 setErrorMsg("getCover error"))。
     */
    override fun getCover(coverPath: String?): ByteArray? {
        val ftBitmap = ImageLoader.loadBitmap(appCtx, coverPath)
            .override(84, 112)
            .centerCrop()
            .submit()
        return try {
            ftBitmap.get(3, TimeUnit.SECONDS).encodeToBytes()
        } catch (e: Exception) {
            try {
                val cached = defaultCoverBitmap
                val defaultBitmap = if (cached != null && !cached.isRecycled) {
                    cached
                } else {
                    ImageLoader.with(appCtx)
                        .asBitmap()
                        .load(BookCover.newDefaultDrawable().toBitmap())
                        .override(84, 112)
                        .centerCrop()
                        .submit()
                        .get()
                        .also { defaultCoverBitmap = it }
                }
                defaultBitmap.encodeToBytes()
            } catch (e: Exception) {
                null
            }
        }
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
