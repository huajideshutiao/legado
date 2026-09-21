package io.legado.app.ui.compose.platform

import io.legado.app.utils.systemCurrentTimeMillis

/**
 * 翻页快捷键 200ms 去抖 (对照原版 ReadBookKeyHandler.keyPageDebounce 的 Throttle
 * wait=200/maxWait=200/leading=true/trailing=false: 首次按键立即触发, 200ms 内
 * 重复按键 (含系统按键 repeat) 丢弃, 长按不会连翻)。
 */
class PageTurnThrottle(private val intervalMs: Long = 200L) {
    private var lastTurnTime = 0L

    fun tryTurn(block: () -> Unit) {
        val now = systemCurrentTimeMillis()
        if (now - lastTurnTime < intervalMs) return
        lastTurnTime = now
        block()
    }
}
