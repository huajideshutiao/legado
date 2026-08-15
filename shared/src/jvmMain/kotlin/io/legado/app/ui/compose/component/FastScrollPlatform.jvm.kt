package io.legado.app.ui.compose.component

import androidx.compose.ui.unit.dp

/**
 * 快速滚动条平台表现配置的桌面 JVM actual。
 *
 * 桌面端鼠标命中面积小(低缩放下 15dp 仅十几像素), 容易点不到, 故触摸区加宽到 24dp、
 * 滑块加宽到 8dp; 且常显 (不随滚动静止隐藏) + 悬停即高亮加宽 (提前响应按压)。
 * 移动端保持 15dp/5dp + 滚动短暂显示 + 仅按住高亮不变。
 */
internal actual val fastScrollPlatform = FastScrollPlatformConfig(
    touchWidth = 24.dp,
    thumbWidth = 8.dp,
    alwaysVisible = true,
    hoverHighlight = true,
)
