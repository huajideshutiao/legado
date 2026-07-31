package io.legado.app.ui.book.read.page

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface

/**
 * Android actual: 走 [android.graphics.Typeface.createFromFile]，与 app 端
 * `TextStyleProvider.getTypeface` 的非 content scheme 分支一致。
 */
actual fun loadReaderFontFamily(path: String): FontFamily? = runCatching {
    FontFamily(Typeface(android.graphics.Typeface.createFromFile(path)))
}.getOrNull()
