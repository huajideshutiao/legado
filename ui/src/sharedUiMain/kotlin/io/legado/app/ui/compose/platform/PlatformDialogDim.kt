package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * 对话框背景暗化, 跨端对齐原版 AppCompat 平台 dim (`backgroundDimAmount = 0.6`)。
 *
 * - Android: CMP 的 Dialog 窗口主题 (DialogWindowTheme) 不设 backgroundDimEnabled,
 *   平台 Dialog 无背景暗化; 这里给窗口补 `FLAG_DIM_BEHIND + dimAmount = 0.6`,
 *   与桌面/iOS 的 0.6 scrim、原版 AppCompat 0.6 dim 三端一致。
 *   需在 Dialog 内容内调用 (从内容 View 上溯找 DialogWindowProvider 取窗口)。
 * - 桌面 JVM / iOS / 鸿蒙: CMP Dialog 自带 0.6 scrim (DefaultScrimOpacity), no-op。
 *
 * E-Ink 模式由调用方跳过 (对齐原版 E-Ink 清 FLAG_DIM_BEHIND):
 * [io.legado.app.ui.compose.component.AppDialog] 的 E-Ink 分支不调用本函数。
 */
@Composable
expect fun PlatformDialogDim()
