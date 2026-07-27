package io.legado.desktop.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.utils.ScreenInfoProviders

/**
 * 桌面端对话框尺寸工具，对齐 app 端 [BaseComposeDialogFragment] 的尺寸语义。
 *
 * 公式（与 app 端 displayMetrics 规则一致）：
 * - width = 窗口宽 * 0.9, coerceAtMost(800dp)
 * - height: 全高模式 = 窗口高 * 0.8；自适应模式 = WRAP_CONTENT
 *
 * 数据源：桌面多窗口环境按当前窗口尺寸（不是主屏物理像素）。
 * [LocalWindowInfo.current.containerSize] 由宿主 Window 注入，CMP Dialog 继承宿主 WindowInfo
 * （不另起独立窗口），窗口 resize 时 containerSize 变化触发重组自动跟随。
 * 初次未挂到窗口前 containerSize 可能为 0，此时回退 [ScreenInfoProviders] 主屏尺寸兜底。
 * 不用 remember：计算极轻（两次乘法+coerce），每次重组重算即可保证动态响应。
 */
object DialogSizes {

    /** 对话框最大宽度（Dp）：窗口宽 * 0.9 与 800dp 取小。 */
    @Composable
    fun dialogMaxWidth(): Dp {
        val density = LocalDensity.current
        val containerWPx = LocalWindowInfo.current.containerSize.width
        val wPx = if (containerWPx > 0) containerWPx else ScreenInfoProviders.get().screenWidthPx
        return with(density) { (wPx * 0.9f).toDp().coerceAtMost(800.dp) }
    }

    /** 全高模式最大高度（Dp）：窗口高 * 0.8。 */
    @Composable
    fun dialogFullHeight(): Dp {
        val density = LocalDensity.current
        val containerHPx = LocalWindowInfo.current.containerSize.height
        val hPx = if (containerHPx > 0) containerHPx else ScreenInfoProviders.get().screenHeightPx
        return with(density) { (hPx * 0.8f).toDp() }
    }
}
