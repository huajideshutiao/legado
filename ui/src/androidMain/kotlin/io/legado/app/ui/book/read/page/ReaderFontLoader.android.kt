package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface

/**
 * Android actual: 走 [readerFontTypeface], content 地址 (SAF 字体目录里的文件) 与普通路径
 * 同一条路 —— 排版度量侧 ([io.legado.app.ui.book.read.page.provider.AndroidTextMeasurer])
 * 读的就是同一份文件。
 */
actual fun loadReaderFontFamily(path: String): FontFamily? =
    readerFontTypeface(path)?.let { FontFamily(Typeface(it)) }
