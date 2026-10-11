package io.legado.app.utils

/**
 * 确保 [this] 文件的父目录存在 (递归创建, 已存在视为成功)。
 *
 * 返回值按"父目录是目录"判定: java.io.File.mkdirs 对已存在的目录返回 false,
 * okio createDirectories 对已存在的目录返回 true, 两者都不能直接用 mkdirs 的返回值表示"目录可用"。
 *
 * @return false = 无父路径或父目录创建失败 (含父路径已被同名文件占用)
 */
expect fun File.ensureParentDirectory(): Boolean
