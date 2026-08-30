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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 快捷键描述。[command] 是跨平台主修饰键: macOS/iOS 走 Cmd, 其余平台走 Ctrl。
 *
 * [preemptive] 显式指定是否在捕获阶段抢占消费；null = 按默认规则推断（见 [AppShortcut.resolvedPreemptive]）。
 * [repeatPolicy] 指定系统按键 repeat（按住连发）的处理策略，默认 [KeyRepeatPolicy.FILTER]。
 */
data class AppShortcut(
    val key: Key,
    val command: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val preemptive: Boolean? = null,
    val repeatPolicy: KeyRepeatPolicy = KeyRepeatPolicy.FILTER,
)

/**
 * 系统按键 repeat（按住连发）的处理策略，见 [AppShortcut.repeatPolicy]。
 */
enum class KeyRepeatPolicy {
    /** 消费但不重复触发（默认）：按住只触发一次（方向键/翻页键等），冒泡阶段不再执行 */
    FILTER,

    /** 消费且每次 repeat 都触发：小说/漫画音量键长按连续翻页（用户拍板 2026-08，
     *  小说与漫画一致；连翻速率由调用方节流，对照原版 ReadMangaActivity onKeyDown 的
     *  repeat 连翻） */
    TRIGGER,
}

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
/**
 * 解析后的抢占式标志: 显式设置取显式值, 否则按默认规则推断。
 *
 * 注意: 本扩展曾与成员属性 [AppShortcut.preemptive] 同名, 而 Kotlin 解析规则为成员优先, 调用点
 * `it.preemptive` 会解析到成员 (Boolean?, 未显式设置时为 null), 导致 `null == preemptive` 恒 false,
 * 所有未显式传 preemptive 的快捷键 (音量键/物理 Menu 键/桌面全局 Ctrl 组合键) KeyDown 永不匹配
 * (2026-08 真机 logcat 实证: 音量键 KeyDown 未消费而 KeyUp 消费)。改名根治, 调用点必须用本扩展。
 */
internal val AppShortcut.resolvedPreemptive: Boolean
    get() = preemptive ?: (command || alt || key in functionKeys)

private val functionKeys = setOf(
    Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6,
    Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
)

private class ShortcutEntry(
    val shortcuts: () -> List<AppShortcut>,
    val enabled: () -> Boolean,
    val onTriggered: (AppShortcut) -> Unit,
    /** KeyUp 回调 (媒体键长短按区分用), null = 该注册项不消费 KeyUp。 */
    val onKeyUp: ((AppShortcut) -> Unit)? = null,
)

/** 快捷键注册栈, 按注册顺序存放, 分发时栈顶 (最后注册的页面) 优先, 语义对齐 AppBackHandler。 */
private val shortcutStack = mutableListOf<ShortcutEntry>()

/**
 * 按住态判定窗口 (ms): 键在 [pressedSince] 中且距上次 KeyDown 未超过本窗口 = 该键仍按住,
 * 期间 KeyDown 一律视为系统自动重复, 不再用短时间窗猜 repeat。
 *
 * 旧实现用 100ms 窗口区分"自动重复"与"新按键", 但 OS 自动重复的首次延迟约 400~500ms
 * (Windows 默认 500ms / Android 约 400ms), 首个 repeat 到达时距上次按下已远超 100ms,
 * 被误判成新按键 → 重入 onPress 重置长按定时器, 长按变短按、松手误触发 seek
 * (2026-08 用户实测音频页)。本窗口只用于 KeyUp 丢失 (按住期间切走窗口焦点) 的自愈:
 * 长按期间每次 KeyDown 都会刷新时间戳, 不会误过期; 超过窗口的再次按下视为新按键重新接纳。
 */
private const val KEY_HOLD_STALE_MS = 1500L

/** 已命中快捷键且未抬起 (KeyDown → KeyUp) 的按键 → 最近一次 KeyDown 时间戳, 用于自动重复判定。 */
private val pressedSince = mutableMapOf<Key, Long>()

/** 分发中标记: 回调里再触发按键时直接放行, 避免递归分发。 */
private var dispatching = false

/**
 * 注册一组页面级/全局快捷键, 页面离开组合时自动注销。
 *
 * [enabled] 传 lambda 而非 Boolean: 分发时才求值, 避免为了刷新开关态把宿主页面
 * 绑进重组 (如阅读页读 menuState.isVisible 会让整页跟着菜单动画重组)。
 *
 * [onKeyUp] 非 null 时, KeyUp 命中本注册项的同键快捷键 (修饰键组合需一致) 会派发该回调
 * 并消费 KeyUp (媒体键短按/长按区分用, 见 [MediaKeyLongPressState]); null 则 KeyUp 只清
 * 按住状态不消费。注意 [onKeyUp] 必须放在 [onTriggered] 之前: 尾随 lambda 恒绑定到
 * 最后一个参数 (onTriggered), 否则现有调用点会把回调误绑到 onKeyUp 上 (2026-08 踩坑)。
 */
@Composable
fun AppShortcutHandler(
    shortcuts: List<AppShortcut>,
    enabled: () -> Boolean = { true },
    onKeyUp: ((AppShortcut) -> Unit)? = null,
    onTriggered: (AppShortcut) -> Unit,
) {
    val currentShortcuts by rememberUpdatedState(shortcuts)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnTriggered by rememberUpdatedState(onTriggered)
    val currentOnKeyUp by rememberUpdatedState(onKeyUp)
    DisposableEffect(Unit) {
        // 恒注册 (不随 enabled 增删), 保证栈内顺序始终等于页面组合顺序
        val entry = ShortcutEntry(
            shortcuts = { currentShortcuts },
            enabled = { currentEnabled() },
            onTriggered = { currentOnTriggered(it) },
            onKeyUp = { currentOnKeyUp?.invoke(it) },
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
 * [preemptive] 区分捕获/冒泡两阶段: 见 [AppShortcut.resolvedPreemptive]。
 * 逐个 catch + 遍历前取快照, 理由同 [dispatchBackKey]。
 *
 * KeyUp 只清理按住状态 (repeat 过滤用), 不触发 KeyDown 动作——例外:
 * 1. 音量键且有任一激活注册项对该键用 TRIGGER 策略时抬起也消费, 对照原版 ReadMangaActivity
 *    onKeyUp 对音量键返回 true (小说/漫画音量键均为 TRIGGER);
 * 2. 注册项提供 onKeyUp 回调 (媒体键长短按区分) 且 KeyUp 命中同键时派发回调并消费。
 *
 * KeyDown 命中后:
 * 同键未抬起 (按住态, 见 [KEY_HOLD_STALE_MS]) → 视为系统按键 repeat, 按
 * [AppShortcut.repeatPolicy] 处理 (默认 FILTER: 消费但不重复触发, 按住只触发一次;
 * 音量键 TRIGGER: 每次 repeat 触发连翻, 连翻速率由调用方 200ms 节流);
 * 快速连按 (KeyUp 已清按住态) 每次正常触发。
 */
fun dispatchShortcut(event: KeyEvent, preemptive: Boolean): Boolean {
    if (dispatching) return false
    dispatching = true
    try {
        if (event.type == KeyEventType.KeyUp) {
            pressedSince.remove(event.key)
            // 音量键抬起: 有激活的 TRIGGER 策略注册项 (小说/漫画翻页) 就消费, 不让系统弹音量条
            // (对照原版 ReadMangaActivity.onKeyUp / ReadBookKeyHandler.onKeyUp 恒消费音量键);
            // 只按栈顶那一项判定会被同页更靠上的 FILTER 注册项 (如把音量键设成自定义翻页键)
            // 挡掉, 故按"任一激活项"判定
            if (event.key == Key.VolumeUp || event.key == Key.VolumeDown) {
                return firstEnabledEntry {
                    it.key == event.key && it.repeatPolicy == KeyRepeatPolicy.TRIGGER
                } != null
            }
            // KeyUp 回调: 栈顶 enabled 且 shortcuts 命中同键 (含修饰键一致) 的注册项 →
            // 派发 onKeyUp 并消费 (媒体键短按/长按区分; 无回调的注册项不消费 KeyUp)
            val entry = firstEnabledEntry { it.matches(event) } ?: return false
            val onKeyUp = entry.onKeyUp ?: return false
            val hit = entry.shortcuts().first { it.matches(event) }
            runCatching { onKeyUp(hit) }
                .onFailure { AppLog.put("快捷键 KeyUp 处理异常", it) }
            return true
        }
        if (event.type != KeyEventType.KeyDown) return false
        val entry = firstEnabledEntry {
            it.resolvedPreemptive == preemptive && it.matches(event)
        } ?: return false
        val hit = entry.shortcuts().first { it.resolvedPreemptive == preemptive && it.matches(event) }
        val now = systemCurrentTimeMillis()
        val heldSince = pressedSince[event.key]
        if (heldSince != null && now - heldSince < KEY_HOLD_STALE_MS) {
            // 键仍按住: 本次 KeyDown 是系统自动重复。按住态判定与 OS 首次重复延迟长短无关,
            // 不再依赖时间窗猜测; 两种策略都刷新时间戳, 长按期间持续按键不会误过期。
            pressedSince[event.key] = now
            when (hit.repeatPolicy) {
                // 消费但不重复触发, 焦点导航等冒泡阶段不再执行
                KeyRepeatPolicy.FILTER -> return true
                // 每次 repeat 都触发 (长按连翻, 速率由调用方节流)
                KeyRepeatPolicy.TRIGGER -> {
                    trigger(entry, hit)
                    return true
                }
            }
        }
        pressedSince[event.key] = now
        trigger(entry, hit)
        return true
    } finally {
        dispatching = false
    }
}

/** 栈顶第一个 enabled 且 shortcuts 命中 predicate 的注册项 (栈顶优先, 语义对齐 [dispatchShortcut]) */
private fun firstEnabledEntry(predicate: (AppShortcut) -> Boolean): ShortcutEntry? {
    val snapshot = shortcutStack.toList()
    for (index in snapshot.indices.reversed()) {
        val entry = snapshot[index]
        if (entry !in shortcutStack) continue
        val enabled = runCatching { entry.enabled() }
            .onFailure { AppLog.put("快捷键开关求值异常", it) }
            .getOrDefault(false)
        if (!enabled) continue
        if (entry.shortcuts().any(predicate)) return entry
    }
    return null
}

/** 触发快捷键回调, 异常只记日志不中断分发链 */
private fun trigger(entry: ShortcutEntry, hit: AppShortcut) {
    runCatching { entry.onTriggered(hit) }
        .onFailure { AppLog.put("快捷键处理异常", it) }
}

/**
 * 阅读/漫画页方向键 (2026-08 用户拍板: 键盘只保留方向键, PageUp/PageDown/Space 不再绑定;
 * 键位随翻页方向自适应, 由调用方按 horizontalPageMode 映射)。
 *
 * 显式 preemptive = true 捕获阶段拦截:
 * 1) 避开 FocusTargetNode 焦点导航在冒泡阶段抢先消费方向键 (焦点在页面与根节点/其他
 *    路由页面的 focusable 间移动时, 按键会被吞掉不触发快捷键);
 * 2) 仅当页面是栈顶且菜单隐藏时才命中 (enabled 由调用方提供), 此时页面上无输入框,
 *    抢占不吞输入; 非顶层 (目录/换源等子页) 时 enabled=false, 捕获阶段放行。
 *
 * repeatPolicy = TRIGGER: 长按连翻, 由调用方 PageTurnThrottle (200ms) 安全节流。
 */
val readerDirectionalKeys = listOf(
    AppShortcut(Key.DirectionLeft, preemptive = true, repeatPolicy = KeyRepeatPolicy.TRIGGER),
    AppShortcut(Key.DirectionRight, preemptive = true, repeatPolicy = KeyRepeatPolicy.TRIGGER),
    AppShortcut(Key.DirectionUp, preemptive = true, repeatPolicy = KeyRepeatPolicy.TRIGGER),
    AppShortcut(Key.DirectionDown, preemptive = true, repeatPolicy = KeyRepeatPolicy.TRIGGER),
)

/**
 * 音频/视频播放页媒体键 (方向键/空格, 无修饰键)。
 *
 * 显式 preemptive = true (捕获阶段抢占): 先于 Compose 焦点主链消费, 避免与焦点系统
 * 抢键 —— 播放页控件 (标题栏 ⋯/播放按钮等) 点击后持焦, 若媒体键走冒泡阶段, 焦点控件
 * 与快捷键栈会对同一 Space/方向键各处理一半 (控件 KeyUp 激活菜单 + 快捷键触发播放,
 * 2026-08 用户实测: 点 ⋯ 后按空格先弹菜单再暂停/播放)。抢占后焦点系统拿不到这些键,
 * 单一所有者; 弹层打开时由调用方 enabled 让位 (菜单/对话框方向键导航优先)。
 * 播放页无输入框, 抢占不吞输入; 对齐阅读/漫画页方向键既有先例。
 *
 * ← 用 TRIGGER: 系统按键 repeat (长按) 每次触发, 连续后退 seek (对照原 handleMediaKeys
 * 左键不防抖语义); 其余 FILTER (按住只触发一次)。→ 的长短按判定 (短按 seek +10s /
 * 长按 当前倍速×2, 松手恢复) 由调用方配 [MediaKeyLongPressState] + [AppShortcutHandler.onKeyUp]
 * 实现 (原 handleMediaKeys 语义, 2026-08 收拢)。
 */
val mediaPlaybackKeys = listOf(
    AppShortcut(Key.Spacebar, preemptive = true),
    AppShortcut(Key.DirectionLeft, preemptive = true, repeatPolicy = KeyRepeatPolicy.TRIGGER),
    AppShortcut(Key.DirectionRight, preemptive = true),
    AppShortcut(Key.DirectionUp, preemptive = true),
    AppShortcut(Key.DirectionDown, preemptive = true),
)

/**
 * 媒体键长按状态 (对照原 handleMediaKeys 右方向键三段式: 死区 → seek +10s →
 * 再一档 → 当前倍速×2, 收拢为长短按两段):
 * - [onPress] (KeyDown): 按平台长按阈值 [longPressTimeoutMs] 计时 (Compose
 *   ViewConfiguration.longPressTimeoutMillis, 与全应用 clickable 键盘长按手感一致),
 *   窗口内松开 = 短按, 超过 = 长按; 系统按键 repeat 由快捷键栈 FILTER 策略消费, 不会重启计时
 * - [onRelease] (KeyUp): 长按松开 → onLongPressRelease (恢复倍速); 短按 → onShortPress (seek)
 *
 * 状态随页面组合存活, 计时协程挂在调用方 scope 上 (页面销毁自动取消)。
 */
class MediaKeyLongPressState {
    private var job: Job? = null
    private var longPressActive = false

    /** KeyDown: 启动长短按判定窗口 (重复 KeyDown 被快捷键栈 FILTER 消费, 不重启)。 */
    fun onPress(scope: CoroutineScope, longPressTimeoutMs: Long, onLongPress: () -> Unit) {
        // 防御: 定时器未决期间重复 onPress (repeat 漏过滤/双路径兜底) 不重启,
        // 否则长按窗口被反复后移、长按永远不触发; 定时器已结束视为新一次手势。
        // repeat 漏过滤已由 dispatchShortcut 按住态判定在源头拦截, 本判断是双保险。
        if (job?.isActive == true) return
        longPressActive = false
        job = scope.launch {
            delay(longPressTimeoutMs)
            longPressActive = true
            onLongPress()
        }
    }

    /** KeyUp: 长按松开恢复 / 窗口内松开执行短按。 */
    fun onRelease(onShortPress: () -> Unit, onLongPressRelease: () -> Unit) {
        job?.cancel()
        job = null
        if (longPressActive) {
            longPressActive = false
            onLongPressRelease()
        } else {
            onShortPress()
        }
    }
}
