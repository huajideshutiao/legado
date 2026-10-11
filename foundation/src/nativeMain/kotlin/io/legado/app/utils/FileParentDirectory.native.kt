package io.legado.app.utils

/**
 * [ensureParentDirectory] 的 nativeMain actual (iOS / 鸿蒙 共用)。
 *
 * 建目录走 [File.mkdirs] (okio createDirectories, 已存在视为成功)。
 */
actual fun File.ensureParentDirectory(): Boolean {
    val dir = parentFile ?: return false
    dir.mkdirs()
    return dir.isDirectory
}
