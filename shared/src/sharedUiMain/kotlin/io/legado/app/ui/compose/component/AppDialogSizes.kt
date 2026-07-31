package io.legado.app.ui.compose.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.utils.ScreenInfoProviders

/**
 * 对话框尺寸, 对齐 app 端 BaseComposeDialogFragment.onStart 的窗口尺寸规则:
 * 宽 = 0.9 倍且上限 800dp, 全高模式高 = 0.8 倍。
 *
 * 基准取 [LocalWindowInfo] 的 containerSize (当前窗口), **不能用**
 * [ScreenInfoProviders] 的主屏物理像素 —— 桌面端窗口通常远小于主屏, 拿主屏算会让对话框
 * 超出窗口撑满屏幕。containerSize 未就绪 (挂到窗口前为 0) 时才回落主屏兜底。
 *
 * 不 remember: 两次乘法 + coerce 极轻, 每次重组重算才能跟随窗口 resize。
 */
object AppDialogSizes {

    /** 宽度: 窗口宽 * 0.9, 上限 800dp。 */
    @Composable
    fun width(): Dp {
        val containerWPx = LocalWindowInfo.current.containerSize.width
        val wPx = if (containerWPx > 0) containerWPx else ScreenInfoProviders.get().screenWidthPx
        return with(LocalDensity.current) { (wPx * 0.9f).toDp().coerceAtMost(800.dp) }
    }

    /** 全高模式高度: 窗口高 * 0.8。 */
    @Composable
    fun fullHeight(): Dp {
        val containerHPx = LocalWindowInfo.current.containerSize.height
        val hPx = if (containerHPx > 0) containerHPx else ScreenInfoProviders.get().screenHeightPx
        return with(LocalDensity.current) { (hPx * 0.8f).toDp() }
    }
}
