package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import io.legado.app.constant.DirUsage
import io.legado.app.help.FileUtilsCommon
import io.legado.app.ui.withLocalDirAccess

/**
 * iOS / 鸿蒙 actual: 两端文本栈同为 skiko, 只有「字节数组」入口,
 * 先经 [FileUtilsCommon] 读盘再建 Font。
 *
 * 字体文件可能是用户在应用容器外选定的字体目录里的文件, 读盘包在平台授权内
 * (用途 [DirUsage.Font]); 读不到 (未授权/授权失效) 返回 null, 由 [resolveReaderFontFamily]
 * 回落默认字体。
 */
actual fun loadReaderFontFamily(path: String): FontFamily? = runCatching {
    val bytes = withLocalDirAccess(path, DirUsage.Font) { FileUtilsCommon.readBytes(path) }
    if (bytes == null || bytes.isEmpty()) null else FontFamily(Font(identity = path, data = bytes))
}.getOrNull()
