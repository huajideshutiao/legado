package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily

/**
 * 按字体文件绝对路径加载 [FontFamily]（对应 app 端 `TextStyleProvider.getTypeface`）。
 *
 * 加载失败或平台不支持返回 null，调用方回落系统默认字体（对齐原版 `Typeface.SANS_SERIF` 兜底）。
 */
expect fun loadReaderFontFamily(path: String): FontFamily?
