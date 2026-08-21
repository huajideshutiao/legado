package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import io.legado.app.help.FileUtilsCommon

/**
 * iOS / 鸿蒙 actual: 两端文本栈同为 skiko, 只有「字节数组」入口,
 * 先经 [FileUtilsCommon] 读盘再建 Font。
 */
actual fun loadReaderFontFamily(path: String): FontFamily? = runCatching {
    val bytes = FileUtilsCommon.readBytes(path)
    if (bytes == null || bytes.isEmpty()) null else FontFamily(Font(identity = path, data = bytes))
}.getOrNull()
