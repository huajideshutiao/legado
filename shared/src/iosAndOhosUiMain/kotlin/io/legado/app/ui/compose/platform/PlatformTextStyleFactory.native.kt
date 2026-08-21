package io.legado.app.ui.compose.platform

import androidx.compose.ui.text.PlatformTextStyle

/**
 * iOS / 鸿蒙 actual: 返回 null, 与 Android 的 `includeFontPadding = false` 行高等价。
 *
 * 两端 CMP 都坐在 skiko 上, [PlatformTextStyle] 只有 spanStyle/paragraphStyle 两项,
 * 不存在 includeFontPadding 等价参数 (Skia 按字体 ascent/descent 排版, jvm actual 同理)。
 */
actual fun platformTextStyleNoFontPadding(): PlatformTextStyle? = null
