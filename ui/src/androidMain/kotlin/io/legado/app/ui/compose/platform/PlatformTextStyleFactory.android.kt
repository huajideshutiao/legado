package io.legado.app.ui.compose.platform

import androidx.compose.ui.text.PlatformTextStyle

/**
 * Android actual: 用 `includeFontPadding = false` 对齐 View TextView 字体 padding 行为。
 *
 * 行为对齐 app 端原 defaultTextStyle (AppTheme.kt:108)。
 */
actual fun platformTextStyleNoFontPadding(): PlatformTextStyle? =
    PlatformTextStyle(includeFontPadding = false)
