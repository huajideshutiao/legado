package io.legado.app.help.storage

import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.compress.ZipUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * [BackupFileOps] 的 jvmAndAndroidMain actual 实现。
 *
 * 委托已下沉到 jvmAndAndroidMain 的 `FileUtilsBase` + `ZipUtils` (纯 JDK 实现),
 * 行为与 app 端原 [io.legado.app.help.storage.Backup] 完全一致, 仅 API 表面换成
 * 跨平台 String 路径签名。
 *
 * # 行为映射
 * - [delete] -> `FileUtilsBase.delete(File(path), deleteRootDir = true)`
 * - [exists] -> `File(path).exists()`
 * - [createFolderIfNotExist] -> `FileUtilsBase.createFolderIfNotExist(path)`
 * - [createFileIfNotExist] -> `FileUtilsBase.createFileIfNotExist(path).absolutePath`
 * - [writeText] -> `FileUtilsBase.createFileIfNotExist(path).writeText(text)`
 * - [readText] -> `File(path).readText()`
 * - [listFiles] -> `File(path).listFiles()?.map { it.absolutePath }`
 * - [copyFile] -> `FileUtilsBase.createFileIfNotExist(dest)` + FileInputStream/FileOutputStream/copyTo (与 app 端 copyBackup(File) 等价)
 * - [zipFiles] -> `ZipUtils.zipFiles(paths.map { File(it) }, zipPath)`
 * - [unZipToPath] -> `ZipUtils.unZipToPath(File(zipPath), destDir)`
 *
 * # 模式参考
 * `io.legado.app.utils.MimeBase64Decoder` (expect object, JVM-only 包装)。
 */
actual object BackupFileOps {

    actual val separator: String = File.separator

    actual fun delete(path: String): Boolean {
        return FileUtilsBase.delete(File(path), deleteRootDir = true)
    }

    actual fun exists(path: String): Boolean {
        return File(path).exists()
    }

    actual fun createFolderIfNotExist(path: String) {
        FileUtilsBase.createFolderIfNotExist(path)
    }

    actual fun createFileIfNotExist(path: String): String {
        return FileUtilsBase.createFileIfNotExist(path).absolutePath
    }

    actual fun writeText(path: String, text: String) {
        FileUtilsBase.createFileIfNotExist(path).writeText(text)
    }

    actual fun readText(path: String): String {
        return File(path).readText()
    }

    actual fun listFiles(path: String): List<String>? {
        // 与 java.io.File.listFiles() 语义一致: 目录不存在返回 null
        return File(path).listFiles()?.map { it.absolutePath }
    }

    actual fun copyFile(srcPath: String, destPath: String) {
        // 与 app 端 copyBackup(File) 等价: createFileIfNotExist + 流式 copyTo
        val destFile = FileUtilsBase.createFileIfNotExist(destPath)
        FileInputStream(File(srcPath)).use { inputS ->
            FileOutputStream(destFile).use { outputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    actual fun zipFiles(srcPaths: List<String>, zipPath: String): Boolean {
        val files = srcPaths.map { File(it) }
        return ZipUtils.zipFiles(files, File(zipPath))
    }

    actual fun unZipToPath(zipPath: String, destDir: String) {
        ZipUtils.unZipToPath(File(zipPath), destDir)
    }
}
