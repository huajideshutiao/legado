package io.legado.app.ui.compose.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.platform.PlatformDialogDim

/**
 * 对话框统一窗口, 带 app 版 Animation.Dialog 动画: 进入 200ms decelerate 中心缩放
 * 0.96→1 + 淡入, 退出 150ms accelerate 淡出; E-Ink 模式跳过动画 (对齐 app 版
 * windowAnimations = 0)。
 *
 * onDismissRequest 先播退出动画再真正关闭: 平台 Dialog 一旦组合移除立即消失,
 * 直接回调会把退出动画砍掉。
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = AppDialogSizes.properties(),
    content: @Composable () -> Unit,
) {
    if (AppConfigProviders.get().isEInkMode) {
        Dialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
        return
    }
    var dismissing by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { dismissing = true }, properties = properties) {
        // Android 补平台 dim 0.6 (对齐桌面/iOS 0.6 scrim); E-Ink 分支在上方已跳过 (对齐原版 E-Ink 清 dim)
        PlatformDialogDim()
        val progress = remember { Animatable(0f) }
        // 进入: 缩放 0.96→1 + 淡入 (对齐 dialog_enter.xml)
        LaunchedEffect(Unit) {
            progress.animateTo(1f, tween(durationMillis = 200, easing = DecelerateEasing))
        }
        // 退出: 淡出播完再关闭 (对齐 dialog_exit.xml)
        LaunchedEffect(dismissing) {
            if (dismissing) {
                progress.animateTo(0f, tween(durationMillis = 150, easing = AccelerateEasing))
                onDismissRequest()
            }
        }
        val p = progress.value
        // 不套 fillMaxSize: 对话框窗口是 wrap_content, 撑满会占掉整个可用空间;
        // Box 尺寸跟随内容, 缩放/淡入只作用于内容框本身
        Box(
            Modifier.graphicsLayer {
                scaleX = 0.96f + 0.04f * p
                scaleY = 0.96f + 0.04f * p
                alpha = p
            },
        ) {
            content()
        }
    }
}

// CMP 1.9 animation-core 把 AccelerateEasing/DecelerateEasing 收为 internal,
// 这里按官方原实现本地定义 (同 MangaRenderState 做法)
private val AccelerateEasing = Easing { it * it }
private val DecelerateEasing = Easing { 1f - (1f - it) * (1f - it) }

/**
 * 底部弹层对话框 (对照原版 BaseBottomDialogFragment gravity=Bottom 语义):
 * 内容对齐容器底部, 进入从下方滑入 200ms + 淡入, 退出 150ms 下滑淡出;
 * E-Ink 模式跳过动画。
 *
 * # 跨平台贴底实现
 *
 * CMP 的 [Dialog] 在桌面端是主窗口内的 ComposeSceneLayer 并默认居中放置, 直接
 * `fillMaxWidth + BottomCenter` 在 wrap 内容里没有视觉效果, 表现为居中卡片。
 * 这里把内容铺满整个容器 ([fillMaxSize]) —— 层随内容铺满后坐标归 (0,0) ——
 * 再靠 `align(BottomCenter)` 把 sheet 贴到底部。铺满后点击都在层内,
 * `dismissOnClickOutside` 不再触发, 用透明点击层手动关闭 (scrim 由 Dialog 层自带)。
 */
@Composable
fun AppBottomSheetDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = AppDialogSizes.properties(),
    maxHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    if (AppConfigProviders.get().isEInkMode) {
        Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            BottomSheetScaffold(
                onDismissRequest = onDismissRequest,
                maxHeight = maxHeight
            ) { content() }
        }
        return
    }
    var dismissing by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { dismissing = true }, properties = properties) {
        // Android 补平台 dim 0.6; E-Ink 分支在上方已跳过
        PlatformDialogDim()
        val progress = remember { Animatable(0f) }
        // 进入: 从底部滑入 + 淡入 (对齐原版底部弹层动画)
        LaunchedEffect(Unit) {
            progress.animateTo(1f, tween(durationMillis = 200, easing = DecelerateEasing))
        }
        // 退出: 下滑淡出播完再关闭
        LaunchedEffect(dismissing) {
            if (dismissing) {
                progress.animateTo(0f, tween(durationMillis = 150, easing = AccelerateEasing))
                onDismissRequest()
            }
        }
        val p = progress.value
        val slideHeightPx = with(LocalDensity.current) { AppDialogSizes.fullHeight().toPx() }
        BottomSheetScaffold(
            // 外部点击与返回键一致走 dismissing 退出动画路径
            onDismissRequest = { dismissing = true },
            maxHeight = maxHeight,
            modifier = Modifier.graphicsLayer {
                translationY = slideHeightPx * (1f - p)
                alpha = p
            },
        ) { content() }
    }
}

/**
 * 底部弹层骨架: 透明点击层铺满 (点击关闭) + sheet 内容贴底。
 * 需作为 Dialog 内容的根, 内容 fillMaxSize 让弹层覆盖整个容器。
 */
@Composable
private fun BottomSheetScaffold(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 透明点击层: 铺满全窗, 点击关闭 sheet (铺满后 dismissOnClickOutside 不触发)
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismissRequest() }
        )
        Box(
            modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight ?: AppDialogSizes.fullHeight()),
            contentAlignment = Alignment.BottomCenter,
        ) { content() }
    }
}
