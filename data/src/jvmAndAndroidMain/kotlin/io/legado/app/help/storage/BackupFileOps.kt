package io.legado.app.help.storage

import io.legado.app.help.storage.BackupFileOps.copyFile
import io.legado.app.help.storage.BackupFileOps.createFileIfNotExist
import io.legado.app.help.storage.BackupFileOps.createFolderIfNotExist
import io.legado.app.help.storage.BackupFileOps.delete
import io.legado.app.help.storage.BackupFileOps.exists
import io.legado.app.help.storage.BackupFileOps.listFiles
import io.legado.app.help.storage.BackupFileOps.readText
import io.legado.app.help.storage.BackupFileOps.unZipToPath
import io.legado.app.help.storage.BackupFileOps.writeText
import io.legado.app.help.storage.BackupFileOps.zipFiles
import io.legado.app.help.writeTextAtomically
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.copyFileAtomically
import io.legado.app.utils.compress.ZipUtils
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

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
 * - [writeText] -> `FileUtilsBase.writeBytes` 写同目录临时件 + [FileUtilsBase.replaceFileAtomically]
 *   就位 (整份落盘), 失败保持目标原内容并抛 [IOException]
 * - [readText] -> `File(path).readText()`
 * - [listFiles] -> `File(path).listFiles()?.map { it.absolutePath }`
 * - [copyFile] -> [copyFileAtomically] (同目录临时件 + 整份改名就位; 与 nativeMain actual 同语义)
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
        // 整份落盘 (同目录临时件 + 改名就位): 直接覆盖写会在写完之前把目标截断, 与此同时按同一
        // 路径整读该文件的一方 (备份/恢复/配置读取) 会拿到半截内容; 失败保持目标原内容并如实上抛
        if (!writeTextAtomically(path, text)) {
            throw IOException("写入文件失败: $path")
        }
    }

    actual fun readText(path: String): String {
        return File(path).readText()
    }

    actual fun readBytes(path: String): ByteArray {
        return File(path).readBytes()
    }

    actual fun openInputStream(path: String): InputStream = FileInputStream(File(path))

    actual fun fileSize(path: String): Long = File(path).length()

    actual fun listFiles(path: String): List<String>? {
        // 与 java.io.File.listFiles() 语义一致: 目录不存在返回 null
        return File(path).listFiles()?.map { it.absolutePath }
    }

    actual fun copyFile(srcPath: String, destPath: String) {
        // 整份复制替换目标 (共享实现): 直接往目标覆盖写, 复制中途被打断会留下半截目标
        // (备份 zip 被截断即读不出的坏包), 失败时目标原内容照旧并抛错
        copyFileAtomically(File(srcPath), File(destPath))
    }

    actual fun zipFiles(srcPaths: List<String>, zipPath: String): Boolean {
        val files = srcPaths.map { File(it) }
        return ZipUtils.zipFiles(files, File(zipPath))
    }

    actual fun unZipToPath(zipPath: String, destDir: String) {
        ZipUtils.unZipToPath(File(zipPath), destDir)
    }
}
