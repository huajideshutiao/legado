package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.File

/**
 * 图片缓存管线 (commonMain expect, 各平台 actual 复刻 app 端原版逻辑)。
 *
 * 下沉自 `app/src/main/java/io/legado/app/model/ImageProvider.kt`,
 * 保持参数 / 边界条件 / 异常处理 / 线程模型与原版完全一致。
 *
 * 平台类型经 expect class 别名:
 * - [ImageProviderBitmap]: android=android.graphics.Bitmap; jvm/native/ohos 暂占位
 * - [ImageProviderSize]: android=android.util.Size
 * - [ImageProviderLruCache]: android 继承 androidx.collection.LruCache (原版 BitmapLruCache)
 *
# 平台实现
 * - androidMain: 1:1 复刻原版 (BitmapFactory + SvgUtils(androidsvg) + BookHelpProviders)
 * - jvmMain/nativeMain/ohosMain: 暂留 stub (无调用方, 由 ReaderImageResolver 接管)
 */
expect class ImageProviderBitmap

expect class ImageProviderSize(width: Int, height: Int)

expect class ImageProviderLruCache() {
    fun remove(key: String): ImageProviderBitmap?
    fun evictAll()
}

expect object ImageProvider {

    val errorBitmap: ImageProviderBitmap

    /**
     * 缓存bitmap LruCache实现
     * filePath bitmap
     */
    val cacheSize: Int

    val bitmapLruCache: ImageProviderLruCache

    fun put(key: String, bitmap: ImageProviderBitmap)

    fun get(key: String): ImageProviderBitmap?

    fun remove(key: String): ImageProviderBitmap?

    /**
     *缓存网络图片和远程epub图片
     *本地epub图片直接从ZIP读取，不缓存到磁盘
     */
    suspend fun cacheImage(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): File

    /**
     *获取图片宽度高度信息
     */
    suspend fun getImageSize(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): ImageProviderSize

    /**
     *获取bitmap 使用LruCache缓存
     */
    fun getImage(
        book: Book,
        src: String,
        width: Int,
        height: Int? = null
    ): ImageProviderBitmap

    fun clear()
}
