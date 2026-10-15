package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * 安装输入法编辑命令通道 (复制/剪切/粘贴/全选)。
 *
 * Android 上输入法这些键走输入框的编辑命令通道 (`InputConnection.performContextMenuAction`),
 * 而 Compose 的输入框没有实现它: 只把命令翻译成一个合成的按键事件丢回窗口并恒返回失败,
 * 该事件要穿过 窗口 → 活动页 → 视图树 → 输入框 整条按键链才可能落到输入框, 任一环断掉输入法
 * 这些键就完全没反应 (系统原生输入框直接执行命令, 不走这条链, 故原版 legado 无此问题)。
 * 本通道由各端自己接管: Android actual 见同目录 `.android.kt`。
 *
 * 桌面/iOS/鸿蒙的输入法在应用内, 编辑命令不经输入连接, 直接透传。
 */
@Composable
internal expect fun ProvidePlatformImeEditCommands(content: @Composable () -> Unit)
