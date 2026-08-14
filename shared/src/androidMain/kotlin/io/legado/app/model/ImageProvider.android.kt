@file:Suppress("unused")

package io.legado.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Size
import androidx.collection.LruCache
import com.caverock.androidsvg.SVG
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.isEpub
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.platform.sharedAppContext
import io.legado.app.utils.File
import io.legado.app.utils.FileUtilsBase
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

// 平台类型别名 (与 commonMain expect 对齐)
actual typealias ImageProviderBitmap = Bitmap
actual typealias ImageProviderSize = Size

/**
 * 缓存bitmap LruCache实现 (原版 BitmapLruCache, 继承 androidx.collection.LruCache)。
 * 1:1 复刻 app 端原版: sizeOf 按字节估算, entryRemoved 累计手动 remove 次数。
 */
actual class ImageProviderLruCache actual constructor() :
    LruCache<String, Bitmap>(ImageProvider.cacheSize) {

    private var removeCount = 0

    val count: Int
        get() = putCount() + createCount() - evictionCount() - removeCount

    override fun sizeOf(key: String, value: Bitmap): Int {
        return value.byteCount
    }

    override fun entryRemoved(
        evicted: Boolean,
        key: String,
        oldValue: Bitmap,
        newValue: Bitmap?
    ) {
        if (!evicted) {
            synchronized(this) {
                removeCount++
            }
        }
        // 记录渲染导致recycle后不能再绘制,交给gc回收,错误图片不能释放
        /* if (oldValue != errorBitmap) {
            oldValue.recycle()
        } */
    }

}

/**
 * [ImageProvider] 的 Android 实现 (1:1 复刻 app 端原版)。
 *
 * 下沉自 `app/src/main/java/io/legado/app/model/ImageProvider.kt`,
 * 保持参数 / 边界条件 / 异常处理 / 线程模型与原版完全一致。
 *
 * 依赖替换:
 * - `appCtx` → [sharedAppContext] (shared androidMain 不依赖 splitties)
 * - `R.drawable.image_loading_error` → `resources.getIdentifier` 查找 (shared 不能引用 app 的 R)
 * - `AppConfig.bitmapCacheSize` → [AppConfigProviders.get] + `setBitmapCacheSize`
 * - `BookHelp.getImage/isImageExist/saveImage` → [BookHelpProviders.get]
 * - `FileUtils.createFileIfNotExist` → [FileUtilsBase.createFileIfNotExist]
 * - `appCtx.toastOnUi` → [Toasters.get].toast
 * - `withContext(IO)` → [withContext]([IoDispatcher])
 * - `BitmapUtils` / `SvgUtils` → 内联 [BitmapDecode] / [SvgDecode] (需 androidsvg 依赖)
 */
actual object ImageProvider {

    actual val errorBitmap: Bitmap by lazy {
        // shared androidMain 不能引用 app 的 R.drawable.image_loading_error,
        // 用 getIdentifier 按名查找 (lazy 仅触发一次, 性能可接受)
        val ctx = sharedAppContext!!
        val resId = ctx.resources.getIdentifier(
            "image_loading_error", "drawable", ctx.packageName
        )
        BitmapFactory.decodeResource(ctx.resources, resId)
    }

    /**
     * 缓存bitmap LruCache实现
     * filePath bitmap
     */
    private const val M = 1024 * 1024

    actual val cacheSize: Int
        get() {
            val config = AppConfigProviders.get()
            if (config.bitmapCacheSize !in 1..1024) {
                config.setBitmapCacheSize(50)
            }
            return config.bitmapCacheSize * M
        }

    actual val bitmapLruCache: ImageProviderLruCache = ImageProviderLruCache()

    actual fun put(key: String, bitmap: Bitmap) {
        ensureLruCacheSize(bitmap)
        bitmapLruCache.put(key, bitmap)
    }

    actual fun get(key: String): Bitmap? {
        return bitmapLruCache[key]
    }

    actual fun remove(key: String): Bitmap? {
        return bitmapLruCache.remove(key)
    }

    private fun getNotRecycled(key: String): Bitmap? {
        val bitmap = bitmapLruCache[key] ?: return null
        if (bitmap.isRecycled) {
            bitmapLruCache.remove(key)
            return null
        }
        return bitmap
    }

    private fun ensureLruCacheSize(bitmap: Bitmap) {
        val lruMaxSize = bitmapLruCache.maxSize()
        val lruSize = bitmapLruCache.size()
        val byteCount = bitmap.byteCount
        val size = if (byteCount > lruMaxSize) {
            min(256 * M, (byteCount * 1.3).toInt())
        } else if (lruSize + byteCount > lruMaxSize && bitmapLruCache.count < 5) {
            min(256 * M, (lruSize + byteCount * 1.3).toInt())
        } else {
            lruMaxSize
        }
        if (size > lruMaxSize) {
            bitmapLruCache.resize(size)
        }
    }

    /**
     *缓存网络图片和远程epub图片
     *本地epub图片直接从ZIP读取，不缓存到磁盘
     */
    actual suspend fun cacheImage(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): File {
        return withContext(IoDispatcher) {
            val bookHelp = BookHelpProviders.get()
            val vFile = bookHelp.getImage(book, src)
            if (!bookHelp.isImageExist(book, src)) {
                // 本地EPUB不缓存，只有远程EPUB或网络书源才缓存
                val isLocalEpub = book.isEpub && !book.origin.startsWith(BookType.webDavTag)
                if (!isLocalEpub) {
                    val inputStream = FileBook.getImage(book, src)
                        ?: let {
                            bookHelp.saveImage(bookSource, book, src)
                            null
                        }
                    inputStream?.use { input ->
                        val newFile = FileUtilsBase.createFileIfNotExist(vFile.absolutePath)
                        FileOutputStream(newFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            return@withContext vFile
        }
    }

    /**
     *获取图片宽度高度信息
     */
    actual suspend fun getImageSize(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): Size {
        val isLocalEpub = book.isEpub && !book.origin.startsWith(BookType.webDavTag)

        if (isLocalEpub) {
            // 本地EPUB直接从ZIP读取，不缓存到磁盘
            FileBook.getImage(book, src)?.use { input ->
                val op = BitmapFactory.Options()
                op.inJustDecodeBounds = true
                BitmapFactory.decodeStream(input, null, op)
                if (op.outWidth > 0 && op.outHeight > 0) {
                    return Size(op.outWidth, op.outHeight)
                }
            }
            AppLog.putDebug("ImageProvider: $src Unsupported image type or not found")
            return Size(errorBitmap.width, errorBitmap.height)
        }

        // 远程EPUB或网络书源：缓存到磁盘后读取
        val file = cacheImage(book, src, bookSource)
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, op)
        if (op.outWidth < 1 && op.outHeight < 1) {
            //svg size
            val size = SvgDecode.getSize(file.absolutePath)
            if (size != null) return size
            AppLog.putDebug("ImageProvider: $src Unsupported image type")
            return Size(errorBitmap.width, errorBitmap.height)
        }
        return Size(op.outWidth, op.outHeight)
    }

    /**
     *获取bitmap 使用LruCache缓存
     */
    actual fun getImage(
        book: Book,
        src: String,
        width: Int,
        height: Int?
    ): Bitmap {
        //src为空白时 可能被净化替换掉了 或者规则失效
        if (book.getUseReplaceRule() && src.isBlank()) {
            book.config.useReplaceRule = false
            Toasters.get().toast(appString(AppStringKey.error_image_url_empty))
        }

        val isLocalEpub = book.isEpub && !book.origin.startsWith(BookType.webDavTag)

        if (isLocalEpub) {
            // 本地EPUB直接从ZIP读取并解码，只缓存到内存LruCache
            val cacheKey = "${book.bookUrl}#$src"
            val cacheBitmap = getNotRecycled(cacheKey)
            if (cacheBitmap != null) return cacheBitmap

            return kotlin.runCatching {
                FileBook.getImage(book, src)?.use { input ->
                    val bytes = input.readBytes()
                    val bitmap = BitmapDecode.decodeBitmap(bytes, width, height ?: width)
                        ?: throw NoStackTraceException(appString(AppStringKey.error_decode_bitmap))
                    put(cacheKey, bitmap)
                    bitmap
                } ?: errorBitmap
            }.onFailure {
                put(cacheKey, errorBitmap)
            }.getOrDefault(errorBitmap)
        }

        // 远程EPUB或网络书源：使用磁盘缓存
        val vFile = BookHelpProviders.get().getImage(book, src)
        if (!vFile.exists()) return errorBitmap

        val cacheBitmap = getNotRecycled(vFile.absolutePath)
        if (cacheBitmap != null) return cacheBitmap

        return kotlin.runCatching {
            val bitmap = BitmapDecode.decodeBitmap(vFile.absolutePath, width, height)
                ?: SvgDecode.createBitmap(vFile.absolutePath, width, height)
                ?: throw NoStackTraceException(appString(AppStringKey.error_decode_bitmap))
            put(vFile.absolutePath, bitmap)
            bitmap
        }.onFailure {
            //错误图片占位,防止重复获取
            put(vFile.absolutePath, errorBitmap)
        }.getOrDefault(errorBitmap)
    }

    actual fun clear() {
        bitmapLruCache.evictAll()
    }

}

/**
 * Bitmap 解码工具 (内联自 app 端 BitmapUtils, 仅供 [ImageProvider] 使用)。
 * calculateInSampleSize 降采样逻辑与原版一致。
 */
private object BitmapDecode {

    fun decodeBitmap(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
        op.inSampleSize = calculateInSampleSize(op, width, height)
        op.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
    }

    fun decodeBitmap(path: String, width: Int, height: Int?): Bitmap? {
        val fis = FileInputStream(path)
        return fis.use {
            val op = BitmapFactory.Options()
            op.inJustDecodeBounds = true
            BitmapFactory.decodeFileDescriptor(fis.fd, null, op)
            op.inSampleSize = calculateInSampleSize(op, width, height)
            op.inJustDecodeBounds = false
            BitmapFactory.decodeFileDescriptor(fis.fd, null, op)
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int? = null,
        reqHeight: Int? = null,
    ): Int {
        val h = options.outHeight
        val w = options.outWidth
        var inSampleSize = 1
        if (reqHeight != null && reqWidth != null) {
            if (h > reqHeight || w > reqWidth) {
                val halfHeight = h / 2
                val halfWidth = w / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
        } else if (reqWidth != null) {
            if (w > reqWidth) {
                val halfWidth = w / 2
                while (halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
        } else if (reqHeight != null && h > reqHeight) {
            val halfHeight = h / 2
            while (halfHeight / inSampleSize >= reqHeight) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

/**
 * SVG 解码工具 (内联自 app 端 SvgUtils, 仅供 [ImageProvider] 使用)。
 * 依赖 com.caverock:androidsvg-aar。
 */
private object SvgDecode {

    //获取svg图片大小
    fun getSize(filePath: String): Size? {
        return kotlin.runCatching {
            FileInputStream(filePath).use { input ->
                val svg = SVG.getFromInputStream(input)
                getSize(svg)
            }
        }.getOrNull()
    }

    fun createBitmap(filePath: String, width: Int, height: Int?): Bitmap? {
        return kotlin.runCatching {
            FileInputStream(filePath).use { input ->
                val svg = SVG.getFromInputStream(input)
                createBitmap(svg, width, height)
            }
        }.getOrNull()
    }

    private fun createBitmap(svg: SVG, width: Int?, height: Int?): Bitmap {
        val size = getSize(svg)
        val wRatio = width?.let { size.width / it } ?: -1
        val hRatio = height?.let { size.height / it } ?: -1
        //如果超出指定大小，则缩小相应的比例
        val ratio = when {
            wRatio > 1 && hRatio > 1 -> max(wRatio, hRatio)
            wRatio > 1 -> wRatio
            hRatio > 1 -> hRatio
            else -> 1
        }

        val viewBox: RectF? = svg.documentViewBox
        if (viewBox == null && size.width > 0 && size.height > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }

        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")

        val bitmapWidth = size.width / ratio
        val bitmapHeight = size.height / ratio
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)

        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    private fun getSize(svg: SVG): Size {
        val width = svg.documentWidth.toInt().takeIf { it > 0 }
            ?: (svg.documentViewBox.right - svg.documentViewBox.left).toInt()
        val height = svg.documentHeight.toInt().takeIf { it > 0 }
            ?: (svg.documentViewBox.bottom - svg.documentViewBox.top).toInt()
        return Size(width, height)
    }

}
