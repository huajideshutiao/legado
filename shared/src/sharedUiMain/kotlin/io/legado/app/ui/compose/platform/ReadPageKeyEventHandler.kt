package io.legado.app.ui.compose.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 阅读翻页键统一处理 (小说/漫画)
 * 对齐 App 端 ReadBookActivity.KeyHandler / ReadMangaActivity.onKeyDown 语义
 * 菜单可见时不拦截翻页键; Esc/Backspace 恒走 onBack (调用方可在 onBack 内先收菜单再退出)
 *
 * 用户拍板 (2026-08): 键盘只保留方向键, PageUp/PageDown/Space 不再绑定翻页。
 * 键位随翻页方向自适应 (当前模式用不到的那对方向键 = 章节切换):
 * - [horizontalPageMode]=true (左右翻页): ←/→=翻页, ↑/↓=上一章/下一章
 * - [horizontalPageMode]=false (上下滚动): ↑/↓=翻页, ←/→=上一章/下一章
 *
 * 对照原版 ReadMangaActivity 补齐: 音量键翻页 (Vol-/Vol+ = 下一页/上一页)、
 * 物理 Menu 键呼出菜单 ([onOpenMenu]); 音量键/Menu 键 down/up 均消费
 * (对照原版 onKeyDown + onKeyUp 双拦截, 消费 down 阻止系统调音量/触发系统菜单)。
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
    /** 物理 Menu 键回调 (对照原版 dispatchKeyEvent KEYCODE_MENU → runMenuIn) */
    onOpenMenu: () -> Unit = {},
): Modifier = this.onPreviewKeyEvent { event ->
    // 音量键/物理 Menu 键: down/up 均消费 (对照原版 onKeyDown + onKeyUp 双拦截)
    when (event.key) {
        Key.VolumeUp -> {
            if (event.type == KeyEventType.KeyDown) onPrevPage()
            return@onPreviewKeyEvent true
        }

        Key.VolumeDown -> {
            if (event.type == KeyEventType.KeyDown) onNextPage()
            return@onPreviewKeyEvent true
        }

        Key.Menu -> {
            if (event.type == KeyEventType.KeyDown) onOpenMenu()
            return@onPreviewKeyEvent true
        }
    }
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
