package io.legado.app.utils

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.App
import io.legado.app.exception.NoStackTraceException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger


data class FileDoc(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val uri: Uri
) {

    override fun toString(): String {
        return if (uri.isContentScheme()) uri.toString() else uri.path!!
    }

    val isContentScheme get() = uri.isContentScheme()

    fun readBytes(): ByteArray {
        return uri.readBytes(App.instance)
    }

    fun readText(): String {
        return uri.readText(App.instance)
    }

    fun asDocumentFile(): DocumentFile? {
        return FileDocIo.asDocumentFile(this)
    }

    fun asFile(): File? {
        if (isContentScheme) {
            return null
        }
        return File(uri.path!!)
    }

    companion object {

        fun fromDir(path: String): FileDoc {
            return fromUri(path.toUri(), true)
        }

        fun fromFile(path: String): FileDoc {
            return fromUri(path.toUri(), false)
        }

        fun fromDir(uri: Uri): FileDoc {
            return fromUri(uri, true)
        }

        fun fromUri(uri: Uri, isDir: Boolean): FileDoc {
            return FileDocIo.fromUri(uri, isDir)
        }

        fun fromDocumentFile(doc: DocumentFile): FileDoc {
            return FileDoc(
                name = doc.name ?: "",
                isDir = doc.isDirectory,
                size = doc.length(),
                lastModified = doc.lastModified(),
                uri = doc.uri
            )
        }

        fun fromFile(file: File): FileDoc {
            return FileDoc(
                name = file.name,
                isDir = file.isDirectory,
                size = file.length(),
                lastModified = file.lastModified(),
                uri = Uri.fromFile(file)
            )
        }

    }
}

/**
 * 过滤器
 */
typealias FileDocFilter = (file: FileDoc) -> Boolean

/**
 * 返回子文件列表,如果不是文件夹则返回null
 */
fun FileDoc.list(filter: FileDocFilter? = null): ArrayList<FileDoc>? {
    return FileDocIo.list(this, filter)
}

/**
 * 查找文档, 如果存在则返回文档,如果不存在返回空
 * @param name 文件名
 * @param depth 查找文件夹深度
 */
fun FileDoc.find(name: String, depth: Int = 0): FileDoc? {
    val list = list()
    list?.forEach {
        if (it.name == name) {
            return it
        }
    }
    if (depth > 0) {
        list?.forEach {
            if (it.isDir) {
                val fileDoc = it.find(name, depth - 1)
                if (fileDoc != null) {
                    return fileDoc
                }
            }
        }
    }
    return null
}

/**
 * 查找文档, 如果存在则返回文档,如果不存在返回空
 * @param name 文件名
 * @param depth 查找文件夹深度
 * @param maxFinds 最大查找文件夹数量
 */
fun FileDoc.find(name: String, depth: Int = 0, maxFinds: Int = Int.MAX_VALUE): FileDoc? {
    return find(name, depth, AtomicInteger(maxFinds))
}

private fun FileDoc.find(name: String, depth: Int, maxFinds: AtomicInteger): FileDoc? {
    if (maxFinds.getAndDecrement() <= 0) {
        return null
    }
    val list = list()
    list?.forEach {
        if (it.name == name) {
            return it
        }
    }
    if (depth > 0) {
        list?.forEach {
            if (it.isDir) {
                val fileDoc = it.find(name, depth - 1, maxFinds)
                if (fileDoc != null) {
                    return fileDoc
                }
            }
        }
    }
    return null
}

fun FileDoc.createFileIfNotExist(
    fileName: String,
    vararg subDirs: String,
    mimeType: String = ""
): FileDoc {
    return FileDocIo.createFile(this, fileName, *subDirs, mimeType = mimeType)
}

fun FileDoc.createFolderIfNotExist(
    vararg subDirs: String
): FileDoc {
    return FileDocIo.createFolder(this, *subDirs)
}

fun FileDoc.openInputStream(): Result<InputStream> {
    return FileDocIo.openInputStream(this)
}

fun FileDoc.openOutputStream(): Result<OutputStream> {
    return FileDocIo.openOutputStream(this)
}

fun FileDoc.openReadPfd(): Result<ParcelFileDescriptor> {
    return FileDocIo.openReadPfd(this)
}

fun FileDoc.openWritePfd(): Result<ParcelFileDescriptor> {
    return FileDocIo.openWritePfd(this)
}

fun FileDoc.exists(
    fileName: String,
    vararg subDirs: String
): Boolean {
    return FileDocIo.exists(this, fileName, *subDirs)
}

fun FileDoc.exists(): Boolean {
    return FileDocIo.exists(this)
}

fun FileDoc.writeText(text: String) {
    FileDocIo.writeText(this, text)
}

fun FileDoc.writeFile(file: File) {
    openOutputStream().getOrThrow().use { out ->
        file.inputStream().use {
            it.copyTo(out)
        }
    }
}

fun FileDoc.delete() {
    FileDocIo.delete(this)
}

fun FileDoc.checkWrite(): Boolean {
    if (!isDir) {
        throw NoStackTraceException("只能检查目录")
    }
    asFile()?.let {
        return it.checkWrite()
    }
    return asDocumentFile()!!.checkWrite()
}
