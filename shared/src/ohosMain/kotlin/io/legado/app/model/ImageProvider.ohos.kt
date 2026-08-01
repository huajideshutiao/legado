package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.File

/**
 * [ImageProvider] 的 ohos stub。
 *
 * 鸿蒙端图片管线由 [io.legado.app.help.image.ReaderImageResolver] / [ReaderImageCache]
 * (compose ImageBitmap) 接管, 不调用本 object。留 stub 仅为满足 KMP expect/actual 约束,
 * 首次访问抛 [IllegalStateException] 防止误用。
 */
actual class ImageProviderBitmap

actual class ImageProviderSize actual constructor(width: Int, height: Int) {
    val width: Int = width
    val height: Int = height
}

actual class ImageProviderLruCache actual constructor() {
    actual fun remove(key: String): ImageProviderBitmap? = null
    actual fun evictAll() {}
}

actual object ImageProvider {

    actual val errorBitmap: ImageProviderBitmap
        get() = error("ImageProvider not implemented on ohos; use ReaderImageResolver")

    actual val cacheSize: Int = 0

    actual val bitmapLruCache: ImageProviderLruCache = ImageProviderLruCache()

    actual fun put(key: String, bitmap: ImageProviderBitmap) {}

    actual fun get(key: String): ImageProviderBitmap? = null

    actual fun remove(key: String): ImageProviderBitmap? = null

    actual suspend fun cacheImage(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): File = error("ImageProvider not implemented on ohos; use ReaderImageResolver")

    actual suspend fun getImageSize(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): ImageProviderSize = error("ImageProvider not implemented on ohos; use ReaderImageResolver")

    actual fun getImage(
        book: Book,
        src: String,
        width: Int,
        height: Int?
    ): ImageProviderBitmap = error("ImageProvider not implemented on ohos; use ReaderImageResolver")

    actual fun clear() {}
}
