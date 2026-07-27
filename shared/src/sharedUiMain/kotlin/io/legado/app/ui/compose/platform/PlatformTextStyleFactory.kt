package io.legado.app.ui.compose.platform

import androidx.compose.ui.text.PlatformTextStyle

/**
 * 跨平台 [PlatformTextStyle] 工厂。
 *
 * Android 端用 `PlatformTextStyle(includeFontPadding = false)` 对齐 View TextView
 * 字体 padding 行为；其他平台 (桌面/iOS/鸿蒙) 无 includeFontPadding 概念, 返回 null。
 *
 * commonMain 不能直接构造 [PlatformTextStyle] (它是 expect class, 各平台 actual
 * 签名不同), 故走 expect/actual 工厂。
 *
 * 使用方: [io.legado.app.ui.compose.theme.AppTheme] 的 defaultTextStyle。
 */
expect fun platformTextStyleNoFontPadding(): PlatformTextStyle?
