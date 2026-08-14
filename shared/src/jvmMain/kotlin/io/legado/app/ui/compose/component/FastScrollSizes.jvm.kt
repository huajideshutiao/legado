package io.legado.app.ui.compose.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 快速滚动条尺寸的桌面 JVM actual。
 *
 * 桌面端鼠标命中面积小(低缩放下 15dp 仅十几像素), 容易点不到, 故把触摸区加宽到
 * 24dp、滑块加宽到 8dp; 移动端保持 15dp/5dp 不变。
 */
internal actual val fastScrollTouchWidth: Dp = 24.dp
internal actual val fastScrollThumbWidth: Dp = 8.dp
