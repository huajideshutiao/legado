package io.legado.app.ui.reader

import androidx.compose.runtime.Composable

/**
 * [rememberReaderTextPlatformActions] 的桌面/iOS/鸿蒙 actual: 三端无 ACTION_PROCESS_TEXT
 * 等价机制, 恒空表。阅读页文本菜单只列原版那 8 项。
 */
@Composable
internal actual fun rememberReaderTextPlatformActions(): List<ReaderTextPlatformAction> =
    emptyList()
