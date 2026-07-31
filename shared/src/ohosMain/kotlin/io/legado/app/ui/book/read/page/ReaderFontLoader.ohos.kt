package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import io.legado.app.help.FileUtilsCommon

/**
 * 鸿蒙 actual: CPF 文本栈同为 skiko, 与 iOS 走同一 skikoMain 入口 —— 只有「字节数组」重载,
 * 先经 [FileUtilsCommon] 读盘再建 Font。
 */
actual fun loadReaderFontFamily(path: String): FontFamily? = runCatching {
    val bytes = FileUtilsCommon.readBytes(path)
    if (bytes == null || bytes.isEmpty()) null else FontFamily(Font(identity = path, data = bytes))
}.getOrNull()
