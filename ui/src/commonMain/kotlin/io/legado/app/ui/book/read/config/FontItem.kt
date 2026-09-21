package io.legado.app.ui.book.read.config

/**
 * 字体项：绝对路径 + 文件名（显示用）。
 *
 * 从 sharedUiMain 下沉到 commonMain：`PlatformCapabilities.scanFontItems` 需要以
 * 本类型作为跨平台返回类型，而 commonMain 不可见 sharedUiMain。
 */
data class FontItem(val path: String, val name: String)
