package io.legado.app.help

import io.legado.app.help.FileUtilsCommon.createFileReplace
import io.legado.app.help.FileUtilsCommon.delete
import io.legado.app.help.FileUtilsCommon.getCachePath
import io.legado.app.help.FileUtilsCommon.getPath
import io.legado.app.help.FileUtilsCommon.readBytes
import io.legado.app.help.FileUtilsCommon.resolveCachePath
import io.legado.app.help.FileUtilsCommon.writeBytes
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.InputStream
import java.io.File

/**
 * FileUtilsCommon 的 jvmAndAndroid actual (Android + 桌面 JVM 共用)。
 *
 * 详见 commonMain/help/FileUtilsCommon.kt expect 注释。
 * 委托 [FileUtilsBase] / [java.io.File], 行为与原 app 端 JsExtensions 内联实现一致。
 *
 * # 设计要点
 * - [getCachePath] 走 [AppFilesDirs] (Android: externalCacheDir; 桌面: cacheDir),
 *   替代原 `appCtx.externalCache.absolutePath` (AppCtx Android 专属)
 * - [resolveCachePath] 复刻原 app 端 JsExtensions.getFile 安全检查 (canonicalPath 必须以
 *   externalCache 父目录为前缀, 防 ../ 路径穿越)
 * - [delete] / [getPath] 委托 [FileUtilsBase], 与原 FileUtils 转发链等价
 * - [readBytes] / [writeBytes] / [createFileReplace] 直接走 java.io.File,
 *   与原 app 端 JsExtensions 内联实现一致 (File.readBytes / File.writeBytes / File.createFileReplace)
 */
internal actual object FileUtilsCommon {

    actual fun getCachePath(): String {
        // 优先 externalCacheDir (Android 有, 桌面 null), 退化到 cacheDir (与 AppFilesDirs 设计一致)
        val dirs = AppFilesDirs.get()
        return dirs.externalCacheDir ?: dirs.cacheDir
    }

    actual fun resolveCachePath(relativePath: String): String {
        // 复刻原 app 端 JsExtensions.getFile 路径解析 + 安全检查
        val cachePath = getCachePath()
        val aPath = if (relativePath.startsWith(File.separator)) {
            cachePath + relativePath
        } else {
            cachePath + File.separator + relativePath
        }
        val file = File(aPath)
        // 安全检查: canonicalPath 必须以 cachePath 的父目录为前缀 (防 ../ 穿越到上层)
        // app 端原检查 safePath = appCtx.externalCache.parent!! (cachePath 的父目录)
        val safePath = File(cachePath).parent ?: cachePath
        if (!file.canonicalPath.startsWith(safePath)) {
            throw SecurityException("非法路径")
        }
        return file.absolutePath
    }

    actual fun exist(path: String): Boolean {
        return FileUtilsBase.exist(path)
    }

    actual fun readBytes(path: String): ByteArray? {
        // 复用 FileUtilsBase.readBytes (FileInputStream + ByteArrayOutputStream, null on IOException)
        return FileUtilsBase.readBytes(path)
    }

    actual fun writeBytes(path: String, data: ByteArray): Boolean {
        // 委托 FileUtilsBase.writeBytes (自动创建父目录 + FileOutputStream, false on IOException)
        return FileUtilsBase.writeBytes(path, data)
    }

    actual fun copyToFile(path: String, input: InputStream) {
        // 对齐原 app 端 downloadFile 的 inputStream.copyTo(outputStream) 语义; IO 异常直接上抛
        val file = File(path)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        file.outputStream().use { out -> input.copyTo(out) }
    }

    actual fun delete(path: String, deleteRootDir: Boolean): Boolean {
        // 委托 FileUtilsBase.delete (递归删除 + EBUSY 兜底, 与原 FileUtils.delete 一致)
        return FileUtilsBase.delete(path, deleteRootDir)
    }

    actual fun listFiles(path: String): List<String> {
        // 与原 getTxtInFolder 内 folder.listFiles() 一致: 仅文件, 不含子目录
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile } ?: return emptyList()
        return files.map { it.absolutePath }
    }

    actual fun getPath(rootPath: String, vararg subDirFiles: String): String {
        // 委托 FileUtilsBase.getPath(String, vararg String) (用平台 separator 拼接)
        return FileUtilsBase.getPath(rootPath, *subDirFiles)
    }

    actual fun createFolderIfNotExist(path: String): Boolean {
        // 委托 FileUtilsBase.createFolderIfNotExist (mkdirs, 已存在不报错)
        FileUtilsBase.createFolderIfNotExist(path)
        return true
    }

    actual fun createFileReplace(path: String): Boolean {
        // 复刻原 File.createFileReplace 扩展 (jvmAndAndroidMain FileExtensions.shared.kt):
        // 不存在 → 创建父目录 + createNewFile; 已存在 → delete + createNewFile
        val file = File(path)
        return try {
            if (!file.exists()) {
                file.parent?.let { File(it).mkdirs() }
                file.createNewFile()
            } else {
                file.delete()
                file.createNewFile()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    actual fun listSubDirs(path: String): List<String> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory }?.map { it.absolutePath } ?: emptyList()
    }

    actual fun getDirSize(path: String): Long {
        val file = File(path)
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    actual fun lastModified(path: String): Long {
        val file = File(path)
        return if (file.exists()) file.lastModified() else 0L
    }

    actual fun getName(path: String): String {
        val idx = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (idx >= 0) path.substring(idx + 1) else path
    }
}
