package io.legado.app.ui.compose

import androidx.compose.ui.text.AnnotatedString

// Compose 1.8.2 ui-text jar 缺 AnnotatedString.Companion.fromHtml (Android 专属, 桌面/iOS/ohos 无);
// 跨端 HTML→AnnotatedString 抽象: Android actual 复用 Compose fromHtml,
// 桌面/iOS/鸿蒙委托 [ksoupHtmlToAnnotatedString] (Ksoup 解析)
expect fun String.toHtmlAnnotatedString(): AnnotatedString
