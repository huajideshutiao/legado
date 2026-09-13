package io.legado.app.ui.compose.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.platform.OverlayInsetGap
import kotlin.math.max

/**
 * 全局统一 Popup 宿主组件：
 * 对齐 [AppDialog] 的收敛架构，统一对所有 Popup 注入 [LocalOverlayTopInset] 顶部禁区钳制。
 *
 * 桌面端 Windows 原生控制条 (40dp) 的 Win32 Layered 子窗口物理覆盖在窗口顶部，
 * CMP 的 Popup 挂在最顶层画布会直接侵入该区域被遮挡。
 * 本组件统一截获 [PopupPositionProvider] 返回的 y 坐标，强制 y >= topInset，
 * 彻底消除全应用所有下拉菜单、自动补全框、划词浮动条被控制条遮挡的问题。
 * 移动端 [LocalOverlayTopInset] 恒为 0.dp，零开销零副作用。
 */
@Composable
fun AppPopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val topInset = LocalOverlayTopInset.current
    val density = LocalDensity.current

    val clampedProvider = if (topInset > 0.dp) {
        val topInsetPx = with(density) { (topInset + OverlayInsetGap).roundToPx() }
        remember(popupPositionProvider, topInsetPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val pos = popupPositionProvider.calculatePosition(
                        anchorBounds,
                        windowSize,
                        layoutDirection,
                        popupContentSize,
                    )
                    // 与第二重载同口径: 上抬到顶部禁区之下, 同时按内容高度夹住底边 ——
                    // 只抬上界时, 若 provider 自身不夹底 (自定义定位) 弹层会溢出窗口下沿
                    val maxY = max(topInsetPx, windowSize.height - popupContentSize.height)
                    return IntOffset(pos.x, pos.y.coerceIn(topInsetPx, maxY))
                }
            }
        }
    } else {
        popupPositionProvider
    }

    Popup(
        popupPositionProvider = clampedProvider,
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content,
    )
}

/**
 * [AppPopup] 的对齐+偏移重载版本 (对应官方 Popup(alignment, offset))。
 */
@Composable
fun AppPopup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val topInset = LocalOverlayTopInset.current
    val density = LocalDensity.current

    val positionProvider = remember(alignment, offset, topInset, density) {
        val topInsetPx = if (topInset > 0.dp) {
            with(density) { (topInset + OverlayInsetGap).roundToPx() }
        } else {
            0
        }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val anchorAlignmentOffset = alignment.align(
                    size = popupContentSize,
                    space = anchorBounds.size,
                    layoutDirection = layoutDirection,
                )
                val rawX = anchorBounds.left + anchorAlignmentOffset.x + offset.x
                val rawY = anchorBounds.top + anchorAlignmentOffset.y + offset.y
                val x = rawX.coerceIn(0, max(0, windowSize.width - popupContentSize.width))
                val y = if (topInsetPx > 0) {
                    rawY.coerceIn(topInsetPx, max(topInsetPx, windowSize.height - popupContentSize.height))
                } else {
                    rawY.coerceIn(0, max(0, windowSize.height - popupContentSize.height))
                }
                return IntOffset(x, y)
            }
        }
    }

    AppPopup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content,
    )
}
