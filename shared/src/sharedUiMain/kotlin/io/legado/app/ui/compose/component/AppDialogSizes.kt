package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.legado.app.utils.ScreenInfoProviders

/**
 * 对话框尺寸锚点 (px), 由各平台宿主注入对话框应参照的容器尺寸:
 * - 桌面端: 主窗口尺寸 (Main.kt 在 Window 组合内注入, 跟随 resize)
 * - 移动端: 不注入, 回落屏幕尺寸 (对齐原版 displayMetrics)
 *
 * 不能用对话框内的 [androidx.compose.ui.platform.LocalWindowInfo]:
 * 独立窗口/独立视图的对话框宿主 (Android/iOS/鸿蒙) 里它返回的是**对话框自身窗口**的
 * 尺寸, 与内容尺寸互为反馈 (内容高 = f(0.8×窗高), 窗高 = 内容高), 帧间持续漂移,
 * 表现为滚动时对话框高度不断变高。
 */
val LocalDialogAnchorSize = compositionLocalOf<IntSize?> { null }

/**
 * 对话框尺寸, 对齐 app 端 BaseComposeDialogFragment.onStart 的窗口尺寸规则:
 * 宽 = 0.9 倍且上限 800dp, 全高模式高 = 0.8 倍。
 *
 * 基准取 [LocalDialogAnchorSize] (桌面 = 主窗口, 移动端 = 屏幕)。
 * 不 remember: 两次乘法 + coerce 极轻, 每次重组重算才能跟随窗口 resize。
 */
object AppDialogSizes {

    /** 宽度: 锚点宽 * 0.9, 上限 800dp。 */
    @Composable
    fun width(): Dp {
        val anchor = LocalDialogAnchorSize.current
        val wPx = anchor?.width ?: ScreenInfoProviders.get().screenWidthPx
        return with(LocalDensity.current) { (wPx * 0.9f).toDp().coerceAtMost(800.dp) }
    }

    /** 全高模式高度: 锚点高 * 0.8。 */
    @Composable
    fun fullHeight(): Dp {
        val anchor = LocalDialogAnchorSize.current
        val hPx = anchor?.height ?: ScreenInfoProviders.get().screenHeightPx
        return with(LocalDensity.current) { (hPx * 0.8f).toDp() }
    }

    /**
     * M2 AlertDialog 正文滚动区高度上限: 全高 0.8 锚点高 - 标题/按钮/间距 (约 180dp),
     * 保证按钮行不被裁切; 下限 120dp 防极矮窗口下 heightIn 取负。
     */
    @Composable
    fun textAreaMaxHeight(): Dp = (fullHeight() - 180.dp).coerceAtLeast(120.dp)

    /**
     * 对话框窗口属性: 必须关掉平台默认宽度, 否则 [appDialogSize] 的钳制被平台宽度覆盖。
     *
     * 背景暗化不在这里做: common expect 的 DialogProperties 只有 3 个参数, 传不了
     * decorFitsSystemWindows (Android 设 false 虽会切到带 dim 的 FloatingDialogWindowTheme,
     * 但窗口变 edge-to-edge 侵入系统栏, 内容需自行处理 insets) / scrimColor (仅 skiko 有);
     * 由 [io.legado.app.ui.compose.platform.PlatformDialogDim] 在 Dialog 内容内按平台补齐
     * (Android 窗口 FLAG_DIM_BEHIND 0.6, 桌面/iOS 自带 0.6 scrim)。
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
