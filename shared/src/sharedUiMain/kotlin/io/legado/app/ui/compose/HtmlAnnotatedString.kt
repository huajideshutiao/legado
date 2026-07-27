package io.legado.app.ui.compose

import androidx.compose.ui.text.AnnotatedString

// Compose 1.8.2 ui-text jar 缺 AnnotatedString.Companion.fromHtml (Android 专属, 桌面/iOS/ohos 无);
// 跨端 HTML→AnnotatedString 抽象, Android actual 复用 Compose fromHtml, 其余端降级纯文本
expect fun String.toHtmlAnnotatedString(): AnnotatedString
