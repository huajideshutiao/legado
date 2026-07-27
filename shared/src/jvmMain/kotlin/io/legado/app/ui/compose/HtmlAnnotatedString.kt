package io.legado.app.ui.compose

import androidx.compose.ui.text.AnnotatedString

// Desktop/JVM: Compose 1.8.2 ui-text desktop 变体缺 fromHtml, 降级纯文本 (HTML 标签原样显示)
actual fun String.toHtmlAnnotatedString(): AnnotatedString = AnnotatedString(this)
