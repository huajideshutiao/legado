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
 *
 * 用户拍板 (2026-08): 键盘只保留方向键, PageUp/PageDown/Space 不再绑定翻页。
 * 键位随翻页方向自适应 (当前模式用不到的那对方向键 = 章节切换):
 * - [horizontalPageMode]=true (左右翻页): ←/→=翻页, ↑/↓=上一章/下一章
 * - [horizontalPageMode]=false (上下滚动): ↑/↓=翻页, ←/→=上一章/下一章
 */
fun Modifier.handleReadPageKeys(
    onPrevPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onBack: () -> Unit = {},
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    /** 左右翻页模式 (true=←/→翻页); false=上下滚动模式 (↑/↓翻页) */
    horizontalPageMode: Boolean = true,
    menuVisible: () -> Boolean = { false },
): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionLeft, Key.DirectionRight -> {
            if (menuVisible()) return@onPreviewKeyEvent false
            val isLeft = event.key == Key.DirectionLeft
            if (horizontalPageMode) {
                if (isLeft) onPrevPage() else onNextPage()
            } else {
                // 上下滚动模式: ←/→ = 章节切换 (用户拍板: ↑↓ 翻页→章节切换的自适应版)
                if (isLeft) onPrevChapter?.invoke() else onNextChapter?.invoke()
            }
            true
        }

        Key.DirectionUp, Key.DirectionDown -> {
            if (menuVisible()) return@onPreviewKeyEvent false
            val isUp = event.key == Key.DirectionUp
            if (horizontalPageMode) {
                // 左右翻页模式: ↑/↓ = 章节切换 (用户拍板: 原版 ↑↓ 翻页改为章节切换)
                if (isUp) onPrevChapter?.invoke() else onNextChapter?.invoke()
            } else {
                if (isUp) onPrevPage() else onNextPage()
            }
            true
        }
        Key.Escape, Key.Backspace -> {
            onBack()
            true
        }
        else -> false
    }
}
