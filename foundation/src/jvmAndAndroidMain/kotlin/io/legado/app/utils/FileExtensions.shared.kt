package io.legado.app.utils

import java.io.File
import java.io.FileOutputStream

// File 扩展的纯 JDK 部分下沉 (shared jvmAndAndroidMain)。
// 依赖 android.net.Uri 的 listFileDocs 仍留 app 端 FileExtensions.kt。

fun File.getFile(vararg subDirFiles: String): File {
    val path = FileUtilsBase.getPath(this, *subDirFiles)
    return File(path)
}

fun File.exists(vararg subDirFiles: String): Boolean {
    return getFile(*subDirFiles).exists()
}

fun File.createFileIfNotExist(): File {
    if (!exists()) {
        parentFile?.createFolderIfNotExist()
        createNewFile()
    }
    return this
}

fun File.createFileReplace(): File {
    if (!exists()) {
        parent?.let {
            File(it).mkdirs()
        }
        createNewFile()
    } else {
        delete()
        createNewFile()
    }
    return this
}

fun File.createFolderIfNotExist(): File {
    if (!exists()) {
        mkdirs()
    }
    return this
}

fun File.createFolderReplace(): File {
    if (exists()) {
        FileUtilsBase.delete(this, true)
    }
    mkdirs()
    return this
}

fun File.checkWrite(): Boolean {
    var file: File? = null
    return try {
        val filename = System.currentTimeMillis().toString()
        file = FileUtilsBase.createFileIfNotExist(this, filename)
        file.outputStream().bufferedWriter().use { it.write(filename) }
        file.inputStream().bufferedReader().use { it.readText() == filename }
    } catch (_: Exception) {
        false
    } finally {
        file?.delete()
    }
}

fun File.outputStream(append: Boolean = false): FileOutputStream {
    return FileOutputStream(this, append)
}
