package io.legado.app.utils

/**
 * [ensureParentDirectory] 的 jvmAndAndroidMain actual (Android + 桌面 JVM 共用)。
 *
 * 建目录委托 [FileUtilsBase.createFolderIfNotExist] (mkdirs, 已存在不报错)。
 */
actual fun File.ensureParentDirectory(): Boolean {
    val dir = parentFile ?: return false
    FileUtilsBase.createFolderIfNotExist(dir.path)
    return dir.isDirectory
}
