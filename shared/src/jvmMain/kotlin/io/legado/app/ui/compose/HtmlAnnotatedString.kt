package io.legado.app.ui.compose

import androidx.compose.ui.text.AnnotatedString

// 桌面/JVM: Compose ui-text desktop 变体缺 fromHtml (Android 专属), 委托 sharedUiMain 的 Ksoup 共享实现
actual fun String.toHtmlAnnotatedString(): AnnotatedString = ksoupHtmlToAnnotatedString()
