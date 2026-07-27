package io.legado.app.model.fileBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.lib.epublib.epub.EpubReader
import io.legado.app.lib.epublib.util.zip.AndroidZipFile
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * [LocalEpubResource] 的 Android actual 实现。
 *
 * 对齐原 app 端 `BookHelp.getBookPFD(book)` + `AndroidZipFile(pfd, name)`:
 * 基于 ParcelFileDescriptor 随机访问, content scheme 零拷贝, 不做整包复制。
 * - **webDav**: `StorageManager.openProxyFileDescriptor` + [WebDavPfdCallback] 流式按需读
 * - **content scheme**: `contentResolver.openFileDescriptor` (需 [epubApplicationContext] 已注册)
 * - **file scheme / 绝对路径**: `ParcelFileDescriptor.open`
 *
 * [close] 关闭 [AndroidZipFile] (内部关闭 pfd), 幂等, 由 `EpubFile.finalize()` 调用。
 */
actual class LocalEpubResource actual constructor(book: Book) {

    /** 已解析的 EpubBook (失败返回 null, 由 EpubFile 记录错误日志)。返回 Any? 对齐 commonMain expect。 */
    actual val epubBook: Any?

    /** 底层 AndroidZipFile (持有 pfd), close 时释放。 */
    private var zipFile: AndroidZipFile? = null

    init {
        epubBook = runCatching {
            val pfd = openBookPfd(book)
                ?: throw IOException("获取 ParcelFileDescriptor 失败: ${book.bookUrl}")
            val zf = AndroidZipFile(pfd, book.originName)
            zipFile = zf
            EpubReader().readEpubLazy(zf, "utf-8")
        }.getOrElse {
            // 失败时立即释放已分配资源, 避免泄漏
            close()
            null
        }
    }

    /**
     * 打开本地书籍文件的 [ParcelFileDescriptor], 行为对齐原 `BookHelp.getBookPFD`。
     */
    private fun openBookPfd(book: Book): ParcelFileDescriptor? {
        if (book.bookUrl.startsWith(BookType.webDavTag)) {
            val webDavUrl = book.getRemoteUrl()!!
            val webdav = runCatching {
                WebDav.fromPath(webDavUrl)
            }.getOrElse {
                AppWebDavShared.authorization?.let { auth ->
                    WebDav(webDavUrl, auth)
                } ?: throw WebDavException("Unexpected defaultBookWebDav")
            }
            val size = runBlocking { webdav.getWebDavFile()?.size } ?: 0L
            val context = epubApplicationContext
                ?: error("epubApplicationContext not registered for webDav book: ${book.bookUrl}")
            val storageManager = context.getSystemService(StorageManager::class.java)
            val handlerThread = HandlerThread("WebDavPfd")
            handlerThread.start()
            return storageManager?.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                WebDavPfdCallback(webdav, size, handlerThread),
                Handler(handlerThread.looper)
            )
        }
        val uri = Uri.parse(book.bookUrl)
        return when (uri.scheme) {
            "content" -> {
                val context = epubApplicationContext
                    ?: error("epubApplicationContext not registered for content scheme: ${book.bookUrl}")
                context.contentResolver.openFileDescriptor(uri, "r")
            }
            "file" -> {
                ParcelFileDescriptor.open(
                    File(uri.path ?: book.bookUrl), ParcelFileDescriptor.MODE_READ_ONLY
                )
            }
            else -> {
                // 无 scheme, 当作绝对路径 (与 app 端 Book.getLocalUri 的 Uri.fromFile 分支一致)
                ParcelFileDescriptor.open(
                    File(book.bookUrl), ParcelFileDescriptor.MODE_READ_ONLY
                )
            }
        }
    }

    /** 释放底层 AndroidZipFile (内部关闭 pfd), 幂等。 */
    actual fun close() {
        zipFile?.let { runCatching { it.close() } }
        zipFile = null
    }
}

/**
 * WebDav 代理文件描述符回调, 按 Range 请求流式读取远程文件 (对齐原 app 端同名类)。
 */
private class WebDavPfdCallback(
    private val webDav: WebDav,
    private val size: Long,
    private val handlerThread: HandlerThread
) : ProxyFileDescriptorCallback() {

    override fun onGetSize(): Long {
        return size
    }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (offset >= this.size) return 0

        try {
            val bytes = webDav.readRange(offset, size, this.size)
            if (bytes.isEmpty()) return 0
            System.arraycopy(bytes, 0, data, 0, minOf(bytes.size, size))
            return bytes.size
        } catch (e: IOException) {
            Log.w("WebDavPfdCallback", "Server does not support Range requests", e)
            throw ErrnoException("onRead: ${e.message}", OsConstants.EIO)
        } catch (e: ErrnoException) {
            throw e
        } catch (e: Exception) {
            Log.e("WebDavPfdCallback", "onRead error", e)
            throw ErrnoException("onRead", OsConstants.EIO)
        }
    }

    override fun onRelease() {
        handlerThread.quitSafely()
    }
}

/**
 * [decodeBitmap] 的 Android actual 实现。
 *
 * 用 [BitmapFactory.decodeByteArray], 行为对齐原 app 端
 * `BitmapFactory.decodeStream(input)` (EpubFile.upBookCover 内)。
 *
 * @return 解码失败的 Bitmap, 失败返回 null
 */
actual fun decodeBitmap(bytes: ByteArray): Any? {
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

/**
 * [compressBitmap] 的 Android actual 实现。
 *
 * 用 [Bitmap.compress], 行为对齐原 app 端
 * `cover.compress(Bitmap.CompressFormat.JPEG, 90, FileOutputStream(out))`。
 *
 * @param bitmap [decodeBitmap] 返回的 [Bitmap]
 * @param format "JPEG" / "PNG" (映射到 [Bitmap.CompressFormat])
 * @param quality 0..100
 * @param destPath 目标文件绝对路径 (内部自动创建父目录)
 * @return 成功 true
 */
actual fun compressBitmap(bitmap: Any?, format: String, quality: Int, destPath: String): Boolean {
    if (bitmap !is Bitmap) return false
    val compressFormat = when (format.uppercase()) {
        "PNG" -> Bitmap.CompressFormat.PNG
        "JPEG" -> Bitmap.CompressFormat.JPEG
        "WEBP" -> Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }
    return runCatching {
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        if (!dest.exists()) dest.createNewFile()
        FileOutputStream(dest).use { out ->
            bitmap.compress(compressFormat, quality, out)
        }
        true
    }.getOrElse { false }
}
