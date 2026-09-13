package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 图片查看器源图片几何信息 (屏幕物理窗口坐标 + 圆角)。
 */
data class PhotoSourceBounds(
    val rect: Rect,
    val cornerRadiusPx: Float,
)

/**
 * 键控图片锚点注册表:
 * 封面等图片组件在布局阶段按图片 key (URL 或标识) 登记即时屏幕物理矩形与圆角;
 * 大图查看器打开时按 key 查询, 实现无缝"图片到图片"共享元素容器转场。
 */
class PhotoBoundsRegistry {
    private val map = mutableMapOf<String, PhotoSourceBounds>()

    fun record(key: String, bounds: PhotoSourceBounds) {
        map[key] = bounds
    }

    fun forget(key: String) {
        map.remove(key)
    }

    fun get(key: String): PhotoSourceBounds? = map[key]
}

val LocalPhotoBoundsRegistry = staticCompositionLocalOf { PhotoBoundsRegistry() }

/**
 * 声明本组件为图片查看器 (PhotoOverlay) 的源图片锚点。
 *
 * @param key 图片唯一标识 (通常为封面 URL 或图片路径, 与大图查看器的 src 一致)
 * @param cornerRadius 图片圆角
 */
@Composable
fun Modifier.photoSourceAnchor(
    key: String?,
    cornerRadius: Dp = 0.dp,
): Modifier {
    if (key.isNullOrBlank()) return this
    val registry = LocalPhotoBoundsRegistry.current
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }

    DisposableEffect(key, registry) {
        onDispose { registry.forget(key) }
    }

    return this.onGloballyPositioned { coords ->
        if (coords.isAttached) {
            val bounds = coords.boundsInWindow()
            if (bounds.width > 0f && bounds.height > 0f) {
                registry.record(key, PhotoSourceBounds(bounds, cornerRadiusPx))
            }
        }
    }
}
