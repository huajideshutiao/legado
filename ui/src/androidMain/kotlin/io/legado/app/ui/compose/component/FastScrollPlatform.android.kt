package io.legado.app.ui.compose.component

import androidx.compose.ui.unit.dp

/**
 * 快速滚动条平台表现配置的 Android actual: 保持移动端默认
 * (15dp/5dp, 滚动时短暂显示, 仅按住高亮)。
 */
internal actual val fastScrollPlatform = FastScrollPlatformConfig(
    touchWidth = 15.dp,
    thumbWidth = 5.dp,
)
