package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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

    /**
     * 对话框窗口属性: 必须关掉平台默认宽度, 否则 [appDialogSize] 的钳制被平台宽度覆盖。
     */
    fun properties(
        dismissOnBackPress: Boolean = true,
        dismissOnClickOutside: Boolean = true,
    ): DialogProperties = DialogProperties(
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        usePlatformDefaultWidth = false,
    )
}

/**
 * 对话框统一尺寸: 宽 0.9 屏宽 (上限 800dp), 高按 fullHeight 固定 0.8 屏高或自适应封顶 0.8。
 * 需与 [AppDialogSizes.properties] 搭配使用, 否则被平台默认宽度覆盖。
 */
@Composable
fun Modifier.appDialogSize(fullHeight: Boolean = false, widthFraction: Float = 1f): Modifier {
    val maxHeight = AppDialogSizes.fullHeight()
    return this
        .width(AppDialogSizes.width() * widthFraction)
        .then(if (fullHeight) Modifier.height(maxHeight) else Modifier.heightIn(max = maxHeight))
}
