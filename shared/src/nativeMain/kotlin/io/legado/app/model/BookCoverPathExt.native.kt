package io.legado.app.model

/**
 * [resolveChildAbsolutePath] 的 native 实现 (iOS/鸿蒙共用)。
 *
 * parent 已为绝对路径, child 为纯文件名 (无分隔符), 直接用 "/" 拼接即可。
 * 不使用 kotlin.io.File.absolutePath -- 该属性在不同 Kotlin/Native 版本下归一化有差异
 * (见 NativeBookImageStorage.saveImage 注释); 此实现与 JVM `File(parent, child).absolutePath`
 * 对 "绝对 parent + 简单 child" 的结果等价。
 */
actual fun resolveChildAbsolutePath(parent: String, child: String): String {
    val trimmed = parent.trimEnd('/')
    return "$trimmed/$child"
}
