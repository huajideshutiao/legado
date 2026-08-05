package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.legado.app.constant.AppLog
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * 快捷键描述。[command] 是跨平台主修饰键: macOS/iOS 走 Cmd, 其余平台走 Ctrl。
 *
 * [preemptive] 显式指定是否在捕获阶段抢占消费；null = 按默认规则推断（见 [AppShortcut.preemptive]）。
 */
data class AppShortcut(
    val key: Key,
    val command: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val preemptive: Boolean? = null,
)

/** 主修饰键: macOS/iOS = Cmd (Meta), Windows/Linux/Android/鸿蒙 = Ctrl。 */
val KeyEvent.isCommandPressed: Boolean
    get() = if (usesMetaAsCommandKey) isMetaPressed else isCtrlPressed

/** 展示用主修饰键名, 快捷键说明列表按平台显示 Cmd 或 Ctrl。 */
val commandKeyLabel: String get() = if (usesMetaAsCommandKey) "Cmd" else "Ctrl"

/** 主修饰键是否用 Meta(Cmd)。 */
internal expect val usesMetaAsCommandKey: Boolean

internal fun AppShortcut.matches(event: KeyEvent): Boolean =
    event.key == key &&
        event.isCommandPressed == command &&
        event.isShiftPressed == shift &&
        event.isAltPressed == alt

/**
 * 抢占式 = 在捕获阶段消费。带修饰键或功能键的组合不可能是正常文本输入, 抢占安全;
 * 其余 (方向键/翻页键/空格) 只能走冒泡阶段, 让聚焦的输入框先消费, 否则会吞掉正常输入。
 *
 * 例外: 阅读页方向键显式置 true —— 仅当阅读页是栈顶且菜单隐藏时才命中 (页面上无输入框),
 * 抢占不会吞输入; 同时避开 FocusTargetNode 的焦点导航在冒泡阶段抢先消费方向键导致按键丢失。
 */
internal val AppShortcut.preemptive: Boolean
    get() = preemptive ?: (command || alt || key in functionKeys)

private val functionKeys = setOf(
    Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6,
    Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
)

private class ShortcutEntry(
    val shortcuts: () -> List<AppShortcut>,
    val enabled: () -> Boolean,
    val onTriggered: (AppShortcut) -> Unit,
)

/** 快捷键注册栈, 按注册顺序存放, 分发时栈顶 (最后注册的页面) 优先, 语义对齐 AppBackHandler。 */
private val shortcutStack = mutableListOf<ShortcutEntry>()

/**
 * 系统按键 repeat 间隔上限 (ms)。Windows/macOS/Linux 的按住连发间隔约 30-60ms,
 * 人类最快手速连按约 100ms+; 同键 KeyDown 未抬起且间隔在此窗口内视为 repeat。
 * 窗口同时兼作 KeyUp 丢失 (按键期间切走窗口焦点) 的自愈: 超过窗口的再次按下重新接纳。
 */
private const val KEY_REPEAT_WINDOW_MS = 100L

/** 已命中快捷键且未抬起 (KeyDown → KeyUp) 的按键 → 按下时间戳, 用于 repeat 过滤。 */
private val pressedSince = mutableMapOf<Key, Long>()

/** 分发中标记: 回调里再触发按键时直接放行, 避免递归分发。 */
private var dispatching = false

/**
 * 注册一组页面级/全局快捷键, 页面离开组合时自动注销。
 *
 * [enabled] 传 lambda 而非 Boolean: 分发时才求值, 避免为了刷新开关态把宿主页面
 * 绑进重组 (如阅读页读 menuState.isVisible 会让整页跟着菜单动画重组)。
 */
@Composable
fun AppShortcutHandler(
    shortcuts: List<AppShortcut>,
    enabled: () -> Boolean = { true },
    onTriggered: (AppShortcut) -> Unit,
) {
    val currentShortcuts by rememberUpdatedState(shortcuts)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnTriggered by rememberUpdatedState(onTriggered)
    DisposableEffect(Unit) {
        // 恒注册 (不随 enabled 增删), 保证栈内顺序始终等于页面组合顺序
        val entry = ShortcutEntry(
            shortcuts = { currentShortcuts },
            enabled = { currentEnabled() },
            onTriggered = { currentOnTriggered(it) },
        )
        shortcutStack += entry
        onDispose { shortcutStack -= entry }
    }
}

/** 单快捷键便捷重载。 */
@Composable
fun AppShortcutHandler(
    shortcut: AppShortcut,
    enabled: () -> Boolean = { true },
    onTriggered: () -> Unit,
) {
    val currentOnTriggered by rememberUpdatedState(onTriggered)
    val shortcuts = remember(shortcut) { listOf(shortcut) }
    AppShortcutHandler(shortcuts, enabled) { currentOnTriggered() }
}

/**
 * 把按键分发给快捷键栈, 返回 true 表示已消费。
 *
 * [preemptive] 区分捕获/冒泡两阶段: 见 [AppShortcut.preemptive]。
 * 逐个 catch + 遍历前取快照, 理由同 [dispatchBackKey]。
 *
 * KeyUp 只清理按住状态 (repeat 过滤用), 不触发任何动作。KeyDown 命中后:
 * 同键未抬起且间隔在 [KEY_REPEAT_WINDOW_MS] 内 → 视为系统按键 repeat, 消费但不重复触发
 * (对照原版 ReadBookKeyHandler 的 `repeatCount > 0 → return false` 语义, 按住只翻一页);
 * 快速连按 (间隔超过窗口) 每次正常触发。
 */
fun dispatchShortcut(event: KeyEvent, preemptive: Boolean): Boolean {
    if (dispatching) return false
    dispatching = true
    try {
        if (event.type == KeyEventType.KeyUp) {
            pressedSince.remove(event.key)
            return false
        }
        if (event.type != KeyEventType.KeyDown) return false
        val snapshot = shortcutStack.toList()
        for (index in snapshot.indices.reversed()) {
            val entry = snapshot[index]
            if (entry !in shortcutStack) continue
            val enabled = runCatching { entry.enabled() }
                .onFailure { AppLog.put("快捷键开关求值异常", it) }
                .getOrDefault(false)
            if (!enabled) continue
            val hit = entry.shortcuts()
                .firstOrNull { it.preemptive == preemptive && it.matches(event) }
                ?: continue
            val now = systemCurrentTimeMillis()
            val lastPress = pressedSince[event.key]
            if (lastPress != null && now - lastPress < KEY_REPEAT_WINDOW_MS) {
                // 按住连发 (系统 repeat): 消费但不重复触发, 焦点导航等冒泡阶段不再执行
                return true
            }
            pressedSince[event.key] = now
            runCatching { entry.onTriggered(hit) }
                .onFailure { AppLog.put("快捷键处理异常", it) }
            return true
        }
        return false
    } finally {
        dispatching = false
    }
}
