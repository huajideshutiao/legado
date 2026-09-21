package io.legado.app.ui.compose.platform

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.legado.app.ui.compose.platform.AppKeyRouter.dispatchBubble
import io.legado.app.ui.compose.platform.AppKeyRouter.dispatchCapture
import io.legado.app.ui.compose.platform.AppKeyRouter.dispatchPlatform

/**
 * 统一按键路由: 平台收键入口 (Android Activity / 桌面 Window / 根 Box handleBackKey)
 * 只转发原始按键, 所有业务判定 (全屏 Esc → 统一返回 → F5 刷新 → 快捷键栈捕获/冒泡两阶段)
 * 收拢到此处, 消除三端入口各自重复实现 Esc/F5/dispatchShortcut 的逻辑。
 *
 * KeyDown 分发顺序:
 * 1. 全屏 Esc 退全屏 (最高优先级, 平台注册的策略; 桌面注册, 其他端 null)
 * 2. Esc → 统一返回链 ([performBack]: 关顶层覆盖物 → 关 Overlay → 页面返回拦截 → 出栈),
 *    与 Android 系统返回键 (OnBackPressedDispatcher) 语义对齐
 * 3. F5 → 刷新 (未注册/未消费则放行)
 * 4. 快捷键栈捕获阶段 (preemptive=true: 带修饰键/功能键组合 + 阅读/漫画方向键)
 * 5. 快捷键栈冒泡阶段 (preemptive=false: 无修饰方向键/翻页键/空格, 让聚焦输入框先消费)
 *
 * KeyUp 只清快捷键按住状态 (repeat 过滤用) 并派发注册的 KeyUp 回调 (媒体键长短按区分);
 * 音量键 TRIGGER 策略 (小说/漫画) 抬起也消费, 该逻辑在 [dispatchShortcut] 内部。
 *
 * 不变式: 平台窗口层 (桌面 Window / Android Activity) 的 [dispatchPlatform] 无条件收键,
 * 不依赖 Compose 焦点链 (用户多次踩坑: 无控件持焦时按键无响应, 2026-08), 命中即消费;
 * 未命中放行给组合内 (输入框/焦点导航)。根 Box 的 [handleBackKey] 走同一路由的
 * [dispatchCapture]/[dispatchBubble], 覆盖 iOS/鸿蒙与焦点就绪场景。
 */
object AppKeyRouter {

    /** 全屏 Esc 退全屏策略: 返回 true 表示已消费 (桌面注册, 其他端 null)。 */
    private var fullscreenEscHandler: (() -> Boolean)? = null

    /** 统一返回动作 (handleBackKey 挂载期注册: performBack(navigator))。 */
    private var backAction: (() -> Unit)? = null

    /** F5 刷新动作 (handleBackKey 挂载期注册: navigator.refreshCurrent(), 返回是否消费)。 */
    private var refreshAction: (() -> Boolean)? = null

    fun registerFullscreenEsc(handler: (() -> Boolean)?) {
        fullscreenEscHandler = handler
    }

    fun registerBack(back: (() -> Unit)?) {
        backAction = back
    }

    fun registerRefresh(refresh: (() -> Boolean)?) {
        refreshAction = refresh
    }

    /**
     * 捕获阶段 (onPreviewKeyEvent 语义): KeyUp 清按住状态 + 派发 KeyUp 回调;
     * KeyDown 处理 全屏 Esc → 统一返回 → F5 → 抢占式快捷键 (preemptive=true)。
     */
    fun dispatchCapture(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp) {
            return dispatchShortcut(event, preemptive = true)
        }
        if (event.type != KeyEventType.KeyDown) return false
        when (event.key) {
            Key.Escape -> {
                if (fullscreenEscHandler?.invoke() == true) return true
                val back = backAction
                if (back != null) {
                    back()
                    return true
                }
                // 未注册返回动作 (非 LegadoApp 宿主, 如欢迎/导入页): 放行给后续阶段
                return false
            }

            Key.F5 -> return refreshAction?.invoke() == true
        }
        return dispatchShortcut(event, preemptive = true)
    }

    /**
     * 冒泡阶段 (onKeyEvent 语义): 只分发非抢占快捷键 (preemptive=false),
     * 聚焦的输入框先在捕获/自身处理中消费后才轮到此处。
     */
    fun dispatchBubble(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return dispatchShortcut(event, preemptive = false)
    }

    /**
     * 平台窗口层总入口 (桌面 Window onKeyEvent / Android Activity dispatchKeyEvent):
     * 先于 Compose 焦点链无条件收键, 捕获 + 冒泡两阶段都试, 命中即消费; 未命中放行。
     */
    fun dispatchPlatform(event: KeyEvent): Boolean =
        dispatchCapture(event) || dispatchBubble(event)
}
