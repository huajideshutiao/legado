package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * 底部弹层窗口的系统栏图标明暗: 面板底色铺到系统栏区域后, 那一条的底色由面板接管,
 * 图标明暗必须跟着面板底色走, 否则浅色底上留浅色图标 (看不见)。
 *
 * 状态栏那一条只在面板顶进入后才归面板 (未进入时状态栏下面是页面内容), 故按进入量取值;
 * 导航栏那一条面板恒铺满 (面板贴底, 避让加在底色之内), 恒按面板底色取值。
 *
 * @param lightStatusBarIcons true = 深色图标 (浅色底), false = 浅色图标 (深色底),
 *   null = 面板顶尚未进入状态栏区域, 恢复窗口既有取值。
 * @param lightNavigationBarIcons 导航栏图标明暗, 同 true = 深色图标。
 *
 * Android: 弹层是独立对话框窗口, 窗口主题 (FloatingDialogWindowTheme 未声明
 * windowLightStatusBar / windowLightNavigationBar, 继承宿主 Activity 主题) 自带一套图标明暗,
 * 不随面板底色变化; 这里按需覆盖该窗口的系统栏图标外观, 收回时写回进入弹层时读到的窗口取值。
 * 需在 Dialog 内容内调用 (从内容 View 上溯找 DialogWindowProvider 取窗口)。
 * 桌面 JVM: 无系统栏, no-op。iOS / 鸿蒙: 弹层是主窗口内的层 (非独立系统窗口), 系统栏图标
 * 由宿主视图控制器决定, 无弹层窗口可设, no-op。
 */
@Composable
expect fun PlatformSheetSystemBarIcons(
    lightStatusBarIcons: Boolean?,
    lightNavigationBarIcons: Boolean,
)
