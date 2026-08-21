package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [shouldConsumeImeInsets] 的鸿蒙 actual: 无软键盘/窗口收缩概念, 恒 false
 * (imeDismissPadding 天然 no-op)。
 */
actual fun shouldConsumeImeInsets(): Boolean = false


/**
 * [rememberImeVisible] 的鸿蒙 actual: 恒 true, 与桌面 JVM 一致常驻显示帮助栏。
 *
 * 有意偏离原版 (原版仅软键盘弹出时显示): CPF 1.9.2-0.5.0 移植版无 WindowInsets.ime
 * 实现 (foundation-layout-ohos 无键盘代码, platform.arkui 无窗口避让区/输入法绑定),
 * 真实检测需 tln 直链系统 .so, 暂不可行 —— 先与桌面端一致恒 true 常驻, 待 CPF
 * 支持 ime 或引入原生绑定后再改回真实检测。其余调用点影响同桌面端 (幂等 no-op)。
 */
@Composable
actual fun rememberImeVisible(): Boolean = true
