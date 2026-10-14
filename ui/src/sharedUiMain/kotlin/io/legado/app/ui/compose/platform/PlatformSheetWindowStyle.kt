package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * 底部弹层窗口的平台适配 (只管窗口自身的系统栏与暗化; 面板尺寸不在此列)。
 *
 * - Android: 弹层走贴边窗口 ([io.legado.app.ui.compose.component.AppDialogSizes.sheetProperties])
 *   —— 面板要铺到窗口顶的状态栏区域, 而该窗口的主题 (FloatingDialogWindowTheme) 自带
 *   `backgroundDimEnabled`、系统栏默认不透明。这里把两个系统栏设为透明 (面板底色透上来)
 *   并清掉窗口主题自带的暗化: 底层压暗由
 *   [io.legado.app.ui.compose.platform.PlatformDialogDim] 统一补 0.6, 清掉后暗度不再受主题
 *   默认值影响, E-Ink 不补时也就不会被压暗。
 *   状态栏图标明暗不在此改: 弹层窗口不设置该外观项, 保持平台/页面既有取值。
 *   需在 Dialog 内容内调用 (从内容 View 上溯找 DialogWindowProvider 取窗口)。
 * - 桌面 JVM / iOS / 鸿蒙: 窗口无系统栏可透明 (安全区避让已改由面板容器承担), scrim 已由弹层
 *   窗口属性置透明, no-op。
 */
@Composable
expect fun PlatformSheetWindowStyle()
