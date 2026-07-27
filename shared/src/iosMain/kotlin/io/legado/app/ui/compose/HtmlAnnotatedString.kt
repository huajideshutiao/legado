package io.legado.app.ui.compose

import androidx.compose.ui.text.AnnotatedString

// iOS: Compose ui-text iOS 变体缺 fromHtml, 降级纯文本 (HTML 标签原样显示)
actual fun String.toHtmlAnnotatedString(): AnnotatedString = AnnotatedString(this)
