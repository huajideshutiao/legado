package io.legado.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import androidx.collection.LruCache
import io.legado.app.R
import io.legado.app.constant.AppLog.putDebug
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isEpub
import io.legado.app.help.config.AppConfig
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

object ImageProvider {

    val errorBitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.image_loading_error)
    }

    /**
     * 缓存bitmap LruCache实现
     * filePath bitmap
     */
    private const val M = 1024 * 1024
    val cacheSize: Int
        get() {
            if (AppConfig.bitmapCacheSize !in 1..1024) {
                AppConfig.bitmapCacheSize = 50
            }
            return AppConfig.bitmapCacheSize * M
        }

    val bitmapLruCache = BitmapLruCache()

    class BitmapLruCache : LruCache<String, Bitmap>(cacheSize) {

        private var removeCount = 0

        val count get() = putCount() + createCount() - evictionCount() - removeCount

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

    fun put(key: String, bitmap: Bitmap) {
        ensureLruCacheSize(bitmap)
        bitmapLruCache.put(key, bitmap)
    }

    fun get(key: String): Bitmap? {
        return bitmapLruCache[key]
    }

    fun remove(key: String): Bitmap? {
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
    suspend fun cacheImage(
        book: Book,
        src: String,
        bookSource: BookSource?
    ): File {
        return withContext(IO) {
            val vFile = BookHelp.getImage(book, src)
            if (!BookHelp.isImageExist(book, src)) {
                // 本地EPUB不缓存，只有远程EPUB或网络书源才缓存
                val isLocalEpub = book.isEpub && !book.origin.startsWith(BookType.webDavTag)
                if (!isLocalEpub) {
                    val inputStream = FileBook.getImage(book, src)
                        ?: let {
                            BookHelp.saveImage(bookSource, book, src)
                            null
                        }
                    inputStream?.use { input ->
                        val newFile = FileUtils.createFileIfNotExist(vFile.absolutePath)
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
    suspend fun getImageSize(
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
            putDebug("ImageProvider: $src Unsupported image type or not found")
            return Size(errorBitmap.width, errorBitmap.height)
        }

        // 远程EPUB或网络书源：缓存到磁盘后读取
        val file = cacheImage(book, src, bookSource)
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, op)
        if (op.outWidth < 1 && op.outHeight < 1) {
            //svg size
            val size = SvgUtils.getSize(file.absolutePath)
            if (size != null) return size
            putDebug("ImageProvider: $src Unsupported image type")
            return Size(errorBitmap.width, errorBitmap.height)
        }
        return Size(op.outWidth, op.outHeight)
    }

    /**
     *获取bitmap 使用LruCache缓存
     */
    fun getImage(
        book: Book,
        src: String,
        width: Int,
        height: Int? = null
    ): Bitmap {
        //src为空白时 可能被净化替换掉了 或者规则失效
        if (book.getUseReplaceRule() && src.isBlank()) {
            book.config.useReplaceRule = false
            appCtx.toastOnUi(R.string.error_image_url_empty)
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
                    val bitmap = BitmapUtils.decodeBitmap(bytes, width, height ?: width)
                        ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
                    put(cacheKey, bitmap)
                    bitmap
                } ?: errorBitmap
            }.onFailure {
                put(cacheKey, errorBitmap)
            }.getOrDefault(errorBitmap)
        }

        // 远程EPUB或网络书源：使用磁盘缓存
        val vFile = BookHelp.getImage(book, src)
        if (!vFile.exists()) return errorBitmap

        val cacheBitmap = getNotRecycled(vFile.absolutePath)
        if (cacheBitmap != null) return cacheBitmap

        return kotlin.runCatching {
            val bitmap = BitmapUtils.decodeBitmap(vFile.absolutePath, width, height)
                ?: SvgUtils.createBitmap(vFile.absolutePath, width, height)
                ?: throw NoStackTraceException(appCtx.getString(R.string.error_decode_bitmap))
            put(vFile.absolutePath, bitmap)
            bitmap
        }.onFailure {
            //错误图片占位,防止重复获取
            put(vFile.absolutePath, errorBitmap)
        }.getOrDefault(errorBitmap)
    }

    fun clear() {
        bitmapLruCache.evictAll()
    }

}
