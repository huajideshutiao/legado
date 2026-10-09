package io.legado.app.model.fileBook

/**
 * iOS/鸿蒙 actual: 本地书保存引用只会是文件路径或 `file://` 引用, 不可能出现 `content://`
 * (无 SAF), 故原样返回 (与 Android 分支走 "else" 的结果一致)。
 */
actual fun androidSaveRef(savedRef: String): String = savedRef
