package io.legado.app.model.fileBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.lib.epublib.epub.EpubReader
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * [LocalEpubResource] 的 Android actual 实现。
 *
 * # 设计
 * 原 app 端 `EpubFile.readEpub` 本地分支用 `BookHelp.getBookPFD(book)` + `AndroidZipFile(pfd, name)`
 * 打开本地 epub (基于 ParcelFileDescriptor 的随机访问, 避免 content scheme 文件复制)。
 *
 * shared androidMain 不依赖 app 端 `BookHelp`/`AndroidZipFile`, 改用 `java.util.zip.ZipFile` +
 * [EpubReader.readEpubLazy] (接受 [ZipFile] 重载, 行为等价)。
 *
 * - **content scheme**: 走 [android.content.ContentResolver.openInputStream] 复制到缓存临时文件,
 *   再用 [ZipFile] 打开 (epub 通常几十 MB, 复制开销可接受; 原 app 端用 PFD 避免复制,
 *   但 PFD + AndroidZipFile 依赖 app 端 epublib 子代理未下沉的类, 这里用简化方案)
 * - **file scheme / 绝对路径**: 直接 [ZipFile](path) 打开
 * - **Context**: 由 [epubApplicationContext] (App.onCreate 经 [registerEpubApplicationContext] 注入)
 *
 * # 资源释放
 * [close] 关闭 [ZipFile] + 删除临时文件 (仅 content scheme 时存在), 幂等。
 * 由 `EpubFile.finalize()` 调用。
 *
 * 模式参考 `StringRes.android.kt` 的 `registerSharedAppContext`。
 */
actual class LocalEpubResource actual constructor(book: Book) {

    /** 已解析的 EpubBook (失败返回 null, 由 EpubFile 记录错误日志)。返回 Any? 对齐 commonMain expect。 */
    actual val epubBook: Any?

    /** 底层 ZipFile, close 时释放。 */
    private var zipFile: ZipFile? = null

    /** content scheme 复制出的临时文件, close 时删除 (null 表示非 content scheme)。 */
    private var tempFile: File? = null

    init {
        epubBook = runCatching {
            val (fileToOpen, temp) = resolveLocalFile(book)
            tempFile = temp
            val zf = ZipFile(fileToOpen, Charsets.ISO_8859_1)
            zipFile = zf
            // 与原 app 端 EpubReader().readEpubLazy(zipFile, "utf-8") 行为一致
            // (readEpubLazy 有 ZipFile 重载, 内部包成 ZipFileWrapper)
            EpubReader().readEpubLazy(zf, "utf-8")
        }.getOrElse {
            // 失败时立即释放已分配资源, 避免泄漏
            close()
            null
        }
    }

    /**
     * 解析 [book.bookUrl] 为可被 [ZipFile] 打开的 [File]。
     *
     * - **content scheme**: 复制到 `context.cacheDir/epub_tmp/` 下的临时文件
     * - **file scheme / 绝对路径**: 直接返回 [File]
     *
     * @return (可打开的 File, content scheme 时的临时 File 否则 null)
     */
    private fun resolveLocalFile(book: Book): Pair<File, File?> {
        val ctx = epubApplicationContext
        val url = book.bookUrl
        val uri = Uri.parse(url)
        return when (uri.scheme) {
            "content" -> {
                val context = ctx ?: error("epubApplicationContext not registered for content scheme: $url")
                val tempDir = File(context.cacheDir, "epub_tmp").apply { mkdirs() }
                // 用 originName 做临时文件名后缀, 避免多文件冲突
                val temp = File(tempDir, "epub_${System.currentTimeMillis()}_${book.originName}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output)
                    }
                } ?: error("openInputStream failed for content uri: $url")
                File(temp.path) to temp
            }
            "file" -> {
                File(uri.path ?: url) to null
            }
            else -> {
                // 无 scheme, 当作绝对路径 (与 app 端 Book.getLocalUri 的 Uri.fromFile 分支一致)
                File(url) to null
            }
        }
    }

    /** 释放底层 ZipFile + 删除临时文件, 幂等。 */
    actual fun close() {
        zipFile?.let { runCatching { it.close() } }
        zipFile = null
        tempFile?.let { runCatching { it.delete() } }
        tempFile = null
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
