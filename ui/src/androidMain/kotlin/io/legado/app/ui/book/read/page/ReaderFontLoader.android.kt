package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface

/**
 * Android actual: 走 [readerFontTypeface], content 地址 (SAF 字体目录里的文件) 与普通路径
 * 同一条路 —— 排版度量侧 ([io.legado.app.ui.book.read.page.provider.AndroidTextMeasurer])
 * 读的就是同一份文件。
 *
 * 读完把本次读到的文件标识 (大小 + 最后修改时刻) 登记下来, 度量侧据此复用已加载字形;
 * 读不到时本条已建字形仍在用就继续用它 (两侧同一个字形, 字体不因源暂时读不到而退回默认),
 * 从未建出字形才登记读不到, 由 [resolveReaderFontFamily] 回落默认字体。
 */
actual fun loadReaderFontFamily(path: String): FontFamily? =
    readerFontTypeface(path)?.let { FontFamily(Typeface(it)) }
