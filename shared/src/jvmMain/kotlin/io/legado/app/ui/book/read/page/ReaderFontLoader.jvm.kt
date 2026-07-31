package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import java.io.File

/**
 * 桌面 JVM actual: Skia FontMgr 直接按文件路径加载。
 */
actual fun loadReaderFontFamily(path: String): FontFamily? = runCatching {
    val file = File(path)
    if (file.isFile) FontFamily(Font(file)) else null
}.getOrNull()
