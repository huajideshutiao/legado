package io.legado.app.help

import io.legado.app.exception.SecurityException
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.File

/**
 * FileUtilsCommon 的 nativeMain actual (iOS / 鸿蒙 共用)。
 *
 * 详见 commonMain/help/FileUtilsCommon.kt expect 注释。
 * 委托 [kotlin.io.File] (Kotlin/Native 标准库, 基于 POSIX fs),
 * 行为对齐 jvmAndAndroidMain actual。
 *
 * # 设计要点 (与 [NativeFileCacheProvider] / [NativeFileDownloader] 同源)
 * - [kotlin.io.File] 在 iOS/鸿蒙 target 标准库均支持 (POSIX fs), 与 JVM java.io.File 行为等价
 *   (exists/readBytes/writeBytes/delete/mkdirs/listFiles/renameTo 均可用)
 * - [getCachePath] 走 [AppFilesDirs] (iOS/鸿蒙端启动时注册 [io.legado.app.help.file.registerIosAppFilesDir]
 *   / registerOhosAppFilesDir, 取 cacheDir; 无 externalCacheDir 概念)
 * - [resolveCachePath] 安全检查走 absolutePath 比较 (无 canonicalPath; POSIX 路径解析由 fs 保证)
 * - [delete] 递归删除子项后删自身 (kotlin.io.File 无 File.deleteRecursively, 但有 listFiles)
 * - [createFileReplace] 用 delete + createNewFile (kotlin.io.File 无原子写, 与 jvm 行为对齐)
 *
 * # 行为差异说明
 * - canonicalPath: JVM 有, Native 无。安全检查退化用 absolutePath 比较, 业务可接受
 *   (iOS/鸿蒙 cacheDir 由 AppFilesDirs 注册的绝对路径, 无符号链接穿越风险)
 * - EBUSY 兜底: JVM 端 FileUtilsBase.deleteResolveEBUSY 在 Native 无等价 (POSIX fs 无 EBUSY);
 *   Native delete 直接 file.delete(), 失败返回 false (与 jvm 一致)
 *
 * 模式参考 [NativeFileCacheProvider] / [NativeFileDownloader]。
 */
internal actual object FileUtilsCommon {

    actual fun getCachePath(): String {
        // iOS/鸿蒙: 走 AppFilesDirs.cacheDir (无 externalCacheDir 概念)
        return AppFilesDirs.get().cacheDir
    }

    actual fun resolveCachePath(relativePath: String): String {
        // 与 jvmAndAndroid actual 一致: cachePath + sep + relativePath
        // POSIX 系统分隔符固定为 "/", 用字面量替代 JVM 的 File.separator (Native 无此属性)
        val cachePath = getCachePath()
        val aPath = if (relativePath.startsWith("/")) {
            cachePath + relativePath
        } else {
            "$cachePath/$relativePath"
        }
        val file = File(aPath)
        // 安全检查: absolutePath 必须以 cachePath 的父目录为前缀 (与 jvm 端 safePath 一致)
        // Native 无 canonicalPath, 用 absolutePath 替代 (POSIX 路径无符号链接穿越风险)
        val safePath = File(cachePath).parent ?: cachePath
        if (!file.absolutePath.startsWith(safePath)) {
            throw SecurityException("非法路径")
        }
        return file.absolutePath
    }

    actual fun exist(path: String): Boolean {
        return File(path).exists()
    }

    actual fun readBytes(path: String): ByteArray? {
        // kotlin.io.File.readBytes 失败抛异常, 这里 catch 返回 null (对齐 FileUtilsBase.readBytes)
        return try {
            File(path).readBytes()
        } catch (_: Exception) {
            null
        }
    }

    actual fun writeBytes(path: String, data: ByteArray): Boolean {
        // 自动创建父目录 (与 FileUtilsBase.writeBytes 一致); 失败返回 false
        return try {
            val file = File(path)
            if (!file.exists()) {
                file.parent?.let { File(it).mkdirs() }
                file.createNewFile()
            }
            file.writeBytes(data)
            true
        } catch (_: Exception) {
            false
        }
    }

    actual fun delete(path: String, deleteRootDir: Boolean): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return try {
            // 目录递归删除子项 (kotlin.io.File 无 deleteRecursively, 手写递归)
            if (file.isDirectory) {
                file.listFiles()?.forEach { child ->
                    delete(child.absolutePath, deleteRootDir = true)
                }
                if (!deleteRootDir) return true
            }
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    actual fun listFiles(path: String): List<String> {
        // 与 jvm actual 一致: 仅文件, 不含子目录
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile }.map { it.absolutePath }
    }

    actual fun getPath(rootPath: String, vararg subDirFiles: String): String {
        // 与 FileUtilsBase.getPath 一致: rootPath + sep + subDir (跳过空)
        // POSIX 分隔符固定为 "/", 用字面量替代 JVM 的 File.separator (Native 无此属性)
        val sb = StringBuilder(rootPath)
        subDirFiles.forEach {
            if (it.isNotEmpty()) {
                if (!sb.endsWith("/")) {
                    sb.append("/")
                }
                sb.append(it)
            }
        }
        return sb.toString()
    }

    actual fun createFolderIfNotExist(path: String): Boolean {
        // mkdirs 递归创建, 已存在不报错
        return try {
            File(path).mkdirs()
        } catch (_: Exception) {
            false
        }
    }

    actual fun createFileReplace(path: String): Boolean {
        // 复刻 jvm actual: 不存在 → 创建父目录 + createNewFile; 已存在 → delete + createNewFile
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
        return dir.listFiles()?.filter { it.isDirectory }?.map { it.absolutePath } ?: emptyList()
    }

    actual fun getDirSize(path: String): Long {
        val file = File(path)
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
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
