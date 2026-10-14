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
import io.legado.app.ui.compose.platform.platformDialogProperties
import io.legado.app.ui.compose.platform.platformSheetDialogProperties
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.ScreenInfoProviders

/**
 * 对话框尺寸锚点 (px) = 宿主窗口可用区, 由平台根经 [AppTheme] 的 anchor 参数注入
 * (各端都注入; 未注入时回落屏幕尺寸兜底)。
 *
 * 不能在对话框层读 [androidx.compose.ui.platform.LocalWindowInfo]:
 * 独立窗口/独立视图的对话框宿主 (Android/iOS/鸿蒙) 里它返回的是**对话框自身窗口**的
 * 尺寸, 与内容尺寸互为反馈 (内容高 = f(0.8×窗高), 窗高 = 内容高), 帧间持续漂移,
 * 表现为滚动时对话框高度不断变高。
 */
val LocalDialogAnchorSize = compositionLocalOf<IntSize?> { null }

/**
 * 对话框尺寸: 宽 = 0.9 倍且上限 800dp, 全高模式高 = 0.8 锚点高。
 *
 * 基准取 [LocalDialogAnchorSize] (由宿主根注入的窗口可用区; 未注入时回落屏幕尺寸)。
 * 不 remember: 两次乘法 + coerce 极轻, 每次重组重算才能跟随窗口 resize。
 */
object AppDialogSizes {

    /** 宽度: 锚点宽 * [DesignTokens.dialogWidthFraction], 上限 [DesignTokens.dialogMaxWidth]。 */
    @Composable
    fun width(): Dp {
        val anchor = LocalDialogAnchorSize.current
        val wPx = anchor?.width ?: ScreenInfoProviders.get().screenWidthPx
        return with(LocalDensity.current) {
            (wPx * DesignTokens.dialogWidthFraction).toDp().coerceAtMost(DesignTokens.dialogMaxWidth)
        }
    }

    /**
     * 全高模式高度: 锚点高 * [DesignTokens.dialogHeightFraction] (全局统一 0.8 锚点高)。
     */
    @Composable
    fun fullHeight(): Dp {
        val anchor = LocalDialogAnchorSize.current
        val hPx = anchor?.height ?: ScreenInfoProviders.get().screenHeightPx
        return with(LocalDensity.current) { (hPx * DesignTokens.dialogHeightFraction).toDp() }
    }

    /**
     * 对话框内列表/长正文滚动区高度上限: 全高 0.8 锚点高 - 标题/按钮/间距 (约 180dp),
     * 保证按钮行不被裁切; 下限 120dp 防极矮窗口下 heightIn 取负。
     */
    @Composable
    fun textAreaMaxHeight(): Dp = (fullHeight() - 180.dp).coerceAtLeast(120.dp)

    /**
     * 居中对话框与自管内容窗口的窗口属性: 必须关掉平台默认宽度, 否则 [appDialogSize] 的钳制
     * 被平台宽度覆盖。构造走 [io.legado.app.ui.compose.platform.platformDialogProperties] 平台桥。
     *
     * 窗口不铺满整窗 (按平台默认贴合系统栏): 面板画不到状态栏, 避让也不用自理。
     * 底部弹层不走这里, 见 [sheetProperties]。
     *
     * 背景暗化不在这里做: Android 端由
     * [io.legado.app.ui.compose.platform.PlatformDialogDim] 在 Dialog 内容内补
     * FLAG_DIM_BEHIND 0.6, 桌面/iOS 自带 0.6 scrim。
     */
    fun properties(
        dismissOnBackPress: Boolean = true,
        dismissOnClickOutside: Boolean = true,
    ): DialogProperties = platformDialogProperties(
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
    )

    /**
     * 底部弹层窗口属性 (走 [platformSheetDialogProperties]): 窗口内容铺满整窗 —— Android 贴边,
     * skiko 三端关掉平台安全区缩进并把平台 scrim 置透明。
     *
     * 铺满是弹层面板能画到窗口顶 (状态栏/安全区那一条) 的前提; 随之而来的顶部装饰带、
     * 底部系统栏避让由面板容器 ([AppSheetSurface]) 与弹层骨架按实际进入量补 (Android 侧另由
     * [io.legado.app.ui.compose.platform.PlatformSheetWindowStyle] 清窗口暗化标志),
     * 底层的压暗由对话框窗口/层自带 (见 [AppBottomSheetDialog]), 均不由调用方声明。
     */
    fun sheetProperties(): DialogProperties = platformSheetDialogProperties()
}

/**
 * 对话框尺寸语义 (单一来源, 由宿主 [io.legado.app.ui.compose.component.AppDialog] 施加):
 * - [Adaptive]: 非全高对话框语义 —— 宽 0.9 锚点 (上限 800dp), 高随内容收缩、封顶 0.8 锚点;
 * - [Fixed]: 全高对话框语义 —— 宽同上, 高固定 0.8 锚点;
 * - [Unconstrained]: 不做钳制 (满屏自管内容的窗口承载, 如点击区九宫格)。
 */
enum class AppDialogSizing { Adaptive, Fixed, Unconstrained }

/** [AppDialogSizing] 对应的尺寸修饰符; [AppDialogSizing.Unconstrained] 原样返回。 */
@Composable
fun Modifier.appDialogSize(sizing: AppDialogSizing): Modifier = when (sizing) {
    AppDialogSizing.Adaptive -> appDialogSize(fullHeight = false)
    AppDialogSizing.Fixed -> appDialogSize(fullHeight = true)
    AppDialogSizing.Unconstrained -> this
}

/**
 * 对话框统一尺寸: 宽 0.9 屏宽 (上限 800dp), 高按 [fullHeight] 取固定 0.8 锚点或自适应封顶 0.8。
 * 需与 [AppDialogSizes.properties] 搭配使用: 平台默认宽度已关闭, 尺寸只能来自宿主或内容。
 */
@Composable
fun Modifier.appDialogSize(fullHeight: Boolean = false, widthFraction: Float = 1f): Modifier {
    val maxHeight = AppDialogSizes.fullHeight()
    return this
        .width(AppDialogSizes.width() * widthFraction)
        .then(if (fullHeight) Modifier.height(maxHeight) else Modifier.heightIn(max = maxHeight))
}
