package io.legado.app.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

fun Uri.isContentScheme() = this.scheme == "content"

fun Uri.isFileScheme() = this.scheme == "file"

/**
 * URI 的显示名 (外部投递判视频 / 起标题用)。
 *
 * `content://media/.../video/123` 这类 URI 末段是数字 id, 真正的文件名 (`x.mp4`) 只在 provider 的
 * `OpenableColumns.DISPLAY_NAME` 里; 查不到 (provider 不支持 / 无权限) 回落 URI 末段。
 * 非 content scheme 直接取末段, 不发查询。
 */
fun Uri.displayName(context: Context): String? =
    if (isContentScheme()) {
        runCatching {
            context.contentResolver.query(
                this,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: lastPathSegment
    } else {
        lastPathSegment
    }

@Throws(Exception::class)
fun Uri.readBytes(context: Context): ByteArray {
    return if (this.isContentScheme()) {
        context.contentResolver.openInputStream(this)?.let {
            val len: Int = it.available()
            val buffer = ByteArray(len)
            it.read(buffer)
            it.close()
            return buffer
        } ?: throw NoStackTraceException("打开文件失败\n${this}")
    } else {
        val path = RealPathUtil.getPath(context, this)
        if (path?.isNotEmpty() == true) {
            File(path).readBytes()
        } else {
            throw NoStackTraceException("获取文件真实地址失败\n${this.path}")
        }
    }
}

@Throws(Exception::class)
fun Uri.readText(context: Context): String {
    readBytes(context).let {
        return String(it)
    }
}

@Throws(Exception::class)
fun Uri.writeBytes(
    context: Context,
    byteArray: ByteArray
): Boolean {
    if (this.isContentScheme()) {
        context.contentResolver.openOutputStream(this)?.use {
            it.write(byteArray)
            return true
        }
        return false
    } else {
        val path = RealPathUtil.getPath(context, this)
        if (path?.isNotEmpty() == true) {
            File(path).writeBytes(byteArray)
            return true
        }
    }
    return false
}

@Throws(Exception::class)
fun Uri.writeText(context: Context, text: String, charset: Charset = Charsets.UTF_8): Boolean {
    return writeBytes(context, text.toByteArray(charset))
}

fun Uri.writeBytes(
    context: Context,
    fileName: String,
    byteArray: ByteArray
): Boolean {
    if (this.isContentScheme()) {
        DocumentFile.fromTreeUri(context, this)?.let { pDoc ->
            DocumentUtils.createFileIfNotExist(pDoc, fileName)?.let {
                return it.uri.writeBytes(context, byteArray)
            }
        }
    } else {
        FileUtils.createFileWithReplace(path + File.separatorChar + fileName)
            .writeBytes(byteArray)
        return true
    }
    return false
}

fun Uri.inputStream(context: Context): Result<InputStream> {
    val uri = this
    return kotlin.runCatching {
        try {
            if (isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)
                    ?: throw NoStackTraceException("未获取到文件")
                return@runCatching context.contentResolver.openInputStream(uri)!!
            } else {
                val path = RealPathUtil.getPath(context, uri)
                    ?: throw NoStackTraceException("未获取到文件")
                val file = File(path)
                if (file.exists()) {
                    return@runCatching FileInputStream(file)
                } else {
                    throw NoStackTraceException("文件不存在")
                }
            }
        } catch (e: Exception) {
            e.printOnDebug()
            AppLog.put("读取inputStream失败：${e.localizedMessage}", e)
            throw e
        }
    }
}

fun Uri.outputStream(context: Context): Result<OutputStream> {
    val uri = this
    return kotlin.runCatching {
        try {
            if (isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)
                    ?: throw NoStackTraceException("未获取到文件")
                return@runCatching context.contentResolver.openOutputStream(uri)!!
            } else {
                val path = RealPathUtil.getPath(context, uri)
                    ?: throw NoStackTraceException("未获取到文件")
                val file = File(path)
                if (file.exists()) {
                    return@runCatching FileOutputStream(file)
                } else {
                    throw NoStackTraceException("文件不存在")
                }
            }
        } catch (e: Exception) {
            e.printOnDebug()
            AppLog.put("读取inputStream失败：${e.localizedMessage}", e)
            throw e
        }
    }
}

fun Uri.toReadPfd(context: Context): Result<ParcelFileDescriptor> {
    val uri = this
    return kotlin.runCatching {
        try {
            if (isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)
                    ?: throw NoStackTraceException("未获取到文件")
                return@runCatching context.contentResolver.openFileDescriptor(uri, "r")!!
            } else {
                val path = RealPathUtil.getPath(context, uri)
                    ?: throw NoStackTraceException("未获取到文件")
                val file = File(path)
                if (file.exists()) {
                    return@runCatching ParcelFileDescriptor.open(
                        file,
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                } else {
                    throw NoStackTraceException("文件不存在")
                }
            }


        } catch (e: Exception) {
            e.printOnDebug()
            AppLog.put("读取inputStream失败：${e.localizedMessage}", e)
            throw e
        }
    }
}

fun Uri.toWritePfd(context: Context): Result<ParcelFileDescriptor> {
    val uri = this
    return kotlin.runCatching {
        try {
            if (isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)
                    ?: throw NoStackTraceException("未获取到文件")
                return@runCatching context.contentResolver.openFileDescriptor(uri, "w")!!
            } else {
                val path = RealPathUtil.getPath(context, uri)
                    ?: throw NoStackTraceException("未获取到文件")
                val file = File(path)
                if (file.exists()) {
                    return@runCatching ParcelFileDescriptor.open(
                        file,
                        ParcelFileDescriptor.MODE_WRITE_ONLY
                    )
                } else {
                    throw NoStackTraceException("文件不存在")
                }
            }


        } catch (e: Exception) {
            e.printOnDebug()
            AppLog.put("读取inputStream失败：${e.localizedMessage}", e)
            throw e
        }
    }
}

fun Uri.toRequestBody(contentType: MediaType? = null): RequestBody {
    val uri = this
    return object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength(): Long {
            val length = uri.inputStream(App.instance).getOrThrow().available().toLong()
            return if (length > 0) length else -1
        }

        override fun writeTo(sink: BufferedSink) {
            uri.inputStream(App.instance).getOrThrow().source().use { source ->
                sink.writeAll(source)
            }
        }
    }
}

fun Uri.canRead(): Boolean {
    return App.instance.checkSelfUriPermission(
        this,
        Intent.FLAG_GRANT_READ_URI_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED
}
