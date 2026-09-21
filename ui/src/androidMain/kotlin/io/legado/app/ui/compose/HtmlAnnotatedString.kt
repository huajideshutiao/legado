package io.legado.app.ui.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml

// Android: Compose ui-text 提供 AnnotatedString.Companion.fromHtml (HTML 渲染 + 链接可点击)
actual fun String.toHtmlAnnotatedString(): AnnotatedString = AnnotatedString.fromHtml(this)
