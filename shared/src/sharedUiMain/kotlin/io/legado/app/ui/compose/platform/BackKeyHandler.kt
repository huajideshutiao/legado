package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.legado.app.constant.AppLog
import io.legado.app.ui.root.AppNavigator

/**
 * 桌面端无系统返回键, ESC 等价于返回按钮; 同时作为全应用快捷键的分发入口。
 * (Backspace 刻意不映射: 根节点 preview 阶段拦截会吞掉全应用输入框的删字键)
 *
 * 两阶段分发: 捕获阶段只放带修饰键/功能键的组合 (不可能是文本输入),
 * 无修饰的方向键/翻页键/空格走冒泡阶段, 聚焦的输入框先消费后才轮到快捷键。
 */
fun Modifier.handleBackKey(
    onBack: () -> Unit,
    onRefresh: () -> Boolean = { false },
): Modifier = this
    .onPreviewKeyEvent { event ->
        // KeyUp 只清理快捷键按住状态 (repeat 过滤用, 见 dispatchShortcut), 无动作、不消费
        if (event.type == KeyEventType.KeyUp) {
            dispatchShortcut(event, preemptive = true)
            return@onPreviewKeyEvent false
        }
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Escape -> {
                onBack()
                true
            }

            Key.F5 -> onRefresh()
            else -> dispatchShortcut(event, preemptive = true)
        }
    }
    .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        dispatchShortcut(event, preemptive = false)
    }

/**
 * 统一返回动作: 先关顶层覆盖物 (CMP Dialog/Popup/自绘菜单层), 再关顶层 Overlay,
 * 然后让页面级 [AppBackHandler] 拦截, 最后才出栈。
 *
 * 层级语义对齐原版 Android Back: 系统 Dialog/Popup 最先消费返回键 (原版 Fragment 对话框/
 * PopupMenu 的 BACK 拦截), 其次 Activity 的 onBackPressedDispatcher (菜单/面板收起、
 * 朗读暂停等页面级拦截), 最后才 finish 退出页面。桌面端无系统弹层返回机制, 由
 * [BackLayerHandler] 注册栈模拟第一层; Overlay 栈对应原版 Fragment 对话框层。
 *
 * runCatching: 返回链上任何异常都会连坐 Recomposer (桌面端表现为窗口能重排但键鼠全失灵)。
 */
fun performBack(navigator: AppNavigator) {
    runCatching {
        if (!dismissTopLayer() &&
            !navigator.dismissTopOverlaySkipSuspended() &&
            !dispatchBackKey()
        ) navigator.pop()
    }.onFailure { AppLog.put("返回键处理异常", it) }
}

/** 页面级返回拦截器栈, 按注册顺序存放, 分发时栈顶 (最后注册的页面) 优先。 */
private val backInterceptors = mutableListOf<() -> Boolean>()

/**
 * 顶层覆盖物 (CMP Dialog/Popup/自绘菜单层) 返回拦截栈, 语义对齐原版系统 Dialog/Popup
 * 消费 BACK: 任何对话框/弹出菜单/阅读菜单打开时, 返回键先关闭它们, 不落到页面/出栈。
 *
 * 桌面端 CMP Dialog 是主窗口内 SceneLayer (键事件仍经窗口 Scene 分发, 无独立窗口),
 * Popup 同理, 系统不存在"弹层先吃返回键"机制, 故由各弹层组件在组合期注册本栈,
 * 返回键分发时栈顶 (最后打开的覆盖物) 优先。
 */
private val backLayers = mutableListOf<() -> Boolean>()

/**
 * 顶层覆盖物返回拦截注册: 弹层打开期间注册, 关闭/销毁时自动注销。
 *
 * 用于 [AppDialog]/[AppBottomSheetDialog]/[AppDropdownMenu]/[ReadMenuOverlay] 等
 * 覆盖物组件 (桌面端 ESC、移动端返回键若到达统一链时同样生效); 栈顶优先, 语义对齐
 * [dispatchBackKey] (后注册的覆盖物先关)。
 */
@Composable
fun BackLayerHandler(enabled: Boolean, onBack: () -> Unit) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        // 恒注册 (不随 enabled 增删), 保证栈内顺序始终等于组合顺序
        val layer: () -> Boolean = {
            if (currentEnabled) {
                currentOnBack()
                true
            } else false
        }
        backLayers += layer
        onDispose { backLayers -= layer }
    }
}

/**
 * 把返回键分发给顶层覆盖物栈, 返回 true 表示已消费 (调用方不再继续 overlay/页面/出栈)。
 * 栈顶 (最后打开的覆盖物) 优先; 逐个 catch, 理由同 [dispatchBackKey]。
 */
fun dismissTopLayer(): Boolean {
    if (dispatching) return false
    dispatching = true
    try {
        val snapshot = backLayers.toList()
        for (index in snapshot.indices.reversed()) {
            val layer = snapshot[index]
            // 已随覆盖物关闭注销的注册项不再调用 (回调可能持有已销毁弹层的状态)
            if (layer !in backLayers) continue
            val consumed = runCatching { layer() }
                .onFailure { AppLog.put("顶层覆盖物返回拦截异常", it) }
                .getOrDefault(false)
            if (consumed) return true
        }
        return false
    } finally {
        dispatching = false
    }
}

/** 分发中标记: 拦截器 onBack 里再触发返回时直接放行, 避免递归分发。 */
private var dispatching = false

/**
 * 页面级返回拦截: 同时覆盖 Android 系统返回键与桌面 ESC/Backspace。
 *
 * [PlatformBackHandler] 在 Android 与鸿蒙生效 (鸿蒙经 ArkUIViewController 的
 * OnBackPressedDispatcher), 桌面/iOS 的返回键走 [handleBackKey] → [dispatchBackKey],
 * 故这里额外注册到拦截器栈。
 */
@Composable
fun AppBackHandler(enabled: Boolean, onBack: () -> Unit) {
    PlatformBackHandler(enabled = enabled, onBack = onBack)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        // 恒注册 (不随 enabled 增删), 保证栈内顺序始终等于页面组合顺序
        val interceptor: () -> Boolean = {
            if (currentEnabled) {
                currentOnBack()
                true
            } else false
        }
        backInterceptors += interceptor
        onDispose { backInterceptors -= interceptor }
    }
}

/**
 * 把返回键分发给拦截器栈, 返回 true 表示已消费 (调用方不再出栈)。
 *
 * 拦截器 onBack 抛异常会连坐 Recomposer (桌面端表现为窗口能重排但键鼠全失灵), 故逐个 catch;
 * 遍历前先取快照: onBack 里 pop 会触发 onDispose 改栈, 直接迭代原表会 ConcurrentModificationException。
 */
fun dispatchBackKey(): Boolean {
    if (dispatching) return false
    dispatching = true
    try {
        val snapshot = backInterceptors.toList()
        for (index in snapshot.indices.reversed()) {
            val interceptor = snapshot[index]
            // 已随页面出栈注销的拦截器不再调用 (拦截器可能持有已销毁页面的回调)
            if (interceptor !in backInterceptors) continue
            val consumed = runCatching { interceptor() }
                .onFailure { AppLog.put("返回键拦截器异常", it) }
                .getOrDefault(false)
            if (consumed) return true
        }
        return false
    } finally {
        dispatching = false
    }
}
