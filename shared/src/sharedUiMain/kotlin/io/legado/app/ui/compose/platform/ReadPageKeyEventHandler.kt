package io.legado.app.ui.compose.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 阅读翻页键统一处理 (小说/漫画)
 * 对齐 App 端 ReadBookActivity.KeyHandler 语义
 * 菜单可见时不拦截翻页键; Esc/Backspace 恒走 onBack (调用方可在 onBack 内先收菜单再退出)
 */
fun Modifier.handleReadPageKeys(
    onPrevPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onBack: () -> Unit = {},
    onPrevChapter: (() -> Unit)? = null,   // 占位: 独立上下章节按键, 暂不绑定
    onNextChapter: (() -> Unit)? = null,   // 占位: 独立上下章节按键, 暂不绑定
    menuVisible: () -> Boolean = { false },
): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> {
            if (menuVisible()) return@onPreviewKeyEvent false
            onPrevPage()
            true
        }
        Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar -> {
            if (menuVisible()) return@onPreviewKeyEvent false
            onNextPage()
            true
        }
        Key.Escape, Key.Backspace -> {
            onBack()
            true
        }
        else -> false
    }
}
