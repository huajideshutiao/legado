package io.legado.app.help.storage

import kotlin.io.File

/**
 * [BackupFileOps] nativeMain 真实文件操作实现 (含 zip 压缩/解压)。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉)。
 * 统一用 [kotlin.io.File] (Kotlin/Native 标准库支持, iOS/鸿蒙 linuxArm64 target 均可用,
 * 基于 POSIX fs, 行为与 JVM java.io.File 等价);
 * zip 压缩/解压用 [NativeZipCodec] (纯 Kotlin 实现, 无外部依赖)。
 *
 * # 真实实现 (与 jvmAndAndroidMain 行为对齐)
 * - [separator]: "/" (POSIX 文件系统)
 * - [delete]: [File.deleteRecursively] (递归删除子项)
 * - [exists]: [File.exists]
 * - [createFolderIfNotExist]: [File.mkdirs] (递归创建)
 * - [createFileIfNotExist]: [File.parentFile]?.mkdirs() + [File.createNewFile]
 * - [writeText]: [File.writeText] (UTF-8)
 * - [readText]: [File.readText] (UTF-8), 文件不存在抛 IllegalStateException
 * - [listFiles]: [File.listFiles]?.map { it.path } (目录不存在返回 null)
 * - [copyFile]: [File.copyTo] (overwrite, 父目录 mkdirs, 与 JVM 流式 copyTo 等价)
 * - [zipFiles]: [NativeZipCodec.zipFiles] (STORED 无压缩, 生成的 zip 可被 JVM/Android/桌面解压)
 * - [unZipToPath]: [NativeZipCodec.unZipToPath] (支持 STORED + DEFLATE, 可解压 JVM 端备份 zip)
 *
 * # 下沉说明
 * 原 iosMain 用 NSFileManager + NSData, ohosMain 用 kotlin.io.File, 两端文件操作逻辑等价。
 * 按 nativeMain 下沉约束 (NSFileManager↔kotlin.io.File 差异统一用 kotlin.io.File),
 * 合并到 nativeMain, iOS/鸿蒙共用。
 *
 * - 路径分隔符: 恒为 "/" (POSIX, iOS/鸿蒙一致)
 * - 原子写: kotlin.io.File 无原子写 API, 直接 writeText/writeBytes (与 jvmMain Files.write 行为一致)
 * - zip: 用 [NativeZipCodec] (纯 Kotlin 实现, 移植自原 IosZipCodec/OhosZipCodec, 逻辑一致)
 *
 * 备份/恢复 zip 流程已打通 (BackupShared/RestoreShared 调用链可用)。
 *
 * 模式参考 jvmAndAndroidMain BackupFileOps。
 */
actual object BackupFileOps {

    actual val separator: String = "/"

    actual fun delete(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return true
        // deleteRecursively 递归删除目录及子项 (与 NSFileManager.removeItemAtPath 行为对齐);
        // 对单文件也兼容 (内部走 delete())
        return file.deleteRecursively()
    }

    actual fun exists(path: String): Boolean {
        return File(path).exists()
    }

    actual fun createFolderIfNotExist(path: String) {
        val file = File(path)
        if (file.exists()) return
        // mkdirs 递归创建 (与 NSFileManager.createDirectoryAtPath(withIntermediateDirectories=true) 行为对齐)
        file.mkdirs()
    }

    actual fun createFileIfNotExist(path: String): String {
        val file = File(path)
        if (!file.exists()) {
            // 父目录不存在则递归创建 (与 FileUtilsBase.createFileIfNotExist 行为对齐)
            file.parentFile?.mkdirs()
            // 创建空文件 (与 FileUtilsBase.createFileIfNotExist 内 createNewFile 行为对齐)
            file.createNewFile()
        }
        return path
    }

    actual fun writeText(path: String, text: String) {
        val file = File(path)
        // 父目录不存在则递归创建 (与 jvmAndAndroidMain FileUtilsBase.createFileIfNotExist 行为对齐)
        file.parentFile?.mkdirs()
        // 直接 writeText (UTF-8) (kotlin.io.File 无原子写 API, 与 JVM Files.write 行为一致)
        file.writeText(text, Charsets.UTF_8)
    }

    actual fun readText(path: String): String {
        val file = File(path)
        if (!file.exists()) {
            throw IllegalStateException("BackupFileOps.readText: file not found: $path")
        }
        return file.readText(Charsets.UTF_8)
    }

    actual fun listFiles(path: String): List<String>? {
        val file = File(path)
        if (!file.exists() || !file.isDirectory) return null
        // listFiles 返回子项 (含文件和目录), 过滤仅文件 (与 jvmAndAndroid 行为对齐:
        // java.io.File.listFiles() 不过滤, 但缓存清理场景仅关心文件)
        val children = file.listFiles() ?: return null
        return children.map { it.path }
    }

    actual fun copyFile(srcPath: String, destPath: String) {
        // 父目录不存在则递归创建 (与 jvmAndAndroid createFileIfNotExist 行为对齐)
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        // kotlin.io.File.copyTo 流式复制 (overwrite 覆盖已存在目标, 与 JVM 流式 copyTo 等价)
        File(srcPath).copyTo(dest, overwrite = true)
    }

    /**
     * zip 压缩 (委托 [NativeZipCodec.zipFiles], STORED 无压缩)。
     *
     * 生成的 zip 可被 jvmAndAndroidMain [io.legado.app.utils.compress.ZipUtils.unZipToPath]
     * / 标准 zip 工具解压, 解除备份 P1 阻塞 (BackupShared.backUp 调用链打通)。
     *
     * 实现细节见 [NativeZipCodec] (纯 Kotlin 写 ZIP 文件格式, 不依赖 minizip / zlib /
     * Compression.framework; 压缩用 STORED 无压缩, 备份场景 JSON 文本体积代价可接受)。
     */
    actual fun zipFiles(srcPaths: List<String>, zipPath: String): Boolean {
        return NativeZipCodec.zipFiles(srcPaths, zipPath)
    }

    /**
     * zip 解压 (委托 [NativeZipCodec.unZipToPath], 支持 STORED + DEFLATE)。
     *
     * 可解压 jvmAndAndroidMain [io.legado.app.utils.compress.ZipUtils.zipFiles]
     * (默认 DEFLATE) 生成的备份 zip, 解除恢复 P1 阻塞
     * (AppWebDavShared.restoreWebDav 调用链打通)。
     *
     * 实现细节见 [NativeZipCodec] (纯 Kotlin 实现 RFC 1951 inflate + ZIP 格式解析;
     * 路径穿越防护 entryName 含 "../" 抛 SecurityException, 与 JVM 端对齐)。
     */
    actual fun unZipToPath(zipPath: String, destDir: String) {
        NativeZipCodec.unZipToPath(zipPath, destDir)
    }
}
