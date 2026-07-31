package io.legado.app.ui.compose.platform

import androidx.compose.ui.text.PlatformTextStyle

/**
 * iOS actual: 返回 null, 与 Android 的 `includeFontPadding = false` 行高等价。
 *
 * CMP 在 iOS/skiko 上的 [PlatformTextStyle] 只有 `spanStyle`(TextDecorationLineStyle) 与
 * `paragraphStyle`(FontRasterizationSettings) 两项, 不存在 includeFontPadding 等价参数 —
 * Skia 文本本就按字体 ascent/descent 排版, 不加 Android 的额外 font padding,
 * 即 null 已是 Android 关闭 padding 后的行高 (jvm actual 同理)。
 */
actual fun platformTextStyleNoFontPadding(): PlatformTextStyle? = null
