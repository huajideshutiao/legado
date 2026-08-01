package io.legado.app.ui.compose.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.config.AppConfigProviders

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
 * 内容对齐窗口底部, 进入从下方滑入 200ms + 淡入, 退出 150ms 下滑淡出;
 * E-Ink 模式跳过动画。尺寸由 [AppDialogSizes.properties] 控制 (窗口随内容包裹,
 * 内容侧用 fillMaxSize 撑满后 align BottomCenter)。
 */
@Composable
fun AppBottomSheetDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = AppDialogSizes.properties(),
    content: @Composable () -> Unit,
) {
    if (AppConfigProviders.get().isEInkMode) {
        Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { content() }
        }
        return
    }
    var dismissing by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { dismissing = true }, properties = properties) {
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
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = slideHeightPx * (1f - p)
                    alpha = p
                },
            contentAlignment = Alignment.BottomCenter,
        ) { content() }
    }
}
