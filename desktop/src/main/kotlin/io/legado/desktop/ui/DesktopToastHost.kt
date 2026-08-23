package io.legado.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面端 Toast 消息 (单槽: 后到的消息替换未消失的前一条, 对齐 app 端 Toast 语义)。
 */
data class DesktopToastMsg(
    val text: String,
    val long: Boolean,
    val time: Long,
)

/**
 * 桌面端 Toast 请求队列 (单槽)。
 *
 * 供 [io.legado.desktop.help.ui.DesktopToastProviderImpl] (JS `java.toast` /
 * ToastProviders 链路, 已合并入 Toasters) 与 shared jvmMain `DesktopTrayNotifier.uiSender`
 * (Toaster 链路, 登录对话框"没有请求头！"等) 统一收口。
 *
 * 呈现 = 窗口内底部 toast (app 端 Toast 语义): 主题底栏色底主文本色圆角, 自动消失。
 * 不依赖托盘气泡 (托盘图标空闲期不驻留, 且受 Windows 通知设置影响, 曾表现为
 * "JS 里调用 toast 没反应")。
 */
object DesktopToasts {

    private val _current = MutableStateFlow<DesktopToastMsg?>(null)
    val current: StateFlow<DesktopToastMsg?> = _current.asStateFlow()

    fun show(msg: String, long: Boolean) {
        _current.value = DesktopToastMsg(msg, long, System.currentTimeMillis())
    }

    fun dismiss() {
        _current.value = null
    }
}

/** 退场淡出时长, 与宿主等待移除 Popup 的时间一致, 否则动画会被中途掐断。 */
private const val TOAST_EXIT_MILLIS = 200

/**
 * Toast 宿主, 由 desktop Main.kt 挂在 Compose 根 (与 DesktopDialogHost 平级)。
 *
 * 用 [Popup] 呈现: Popup 是独立合成层 (不被 LegadoApp/路由内容覆盖),
 * 且相对主窗口底部定位 (app 端 Toast 语义)。Popup 无窗口背景, 仅文本可见。
 * 主窗口最小化时 Popup 随窗口隐藏, toast 不显示 (窗口内提示的固有语义)。
 */
@Composable
fun DesktopToastHost() {
    val msg by DesktopToasts.current.collectAsState()
    // 委托属性无法 smart cast, 局部捕获
    val current = msg

    if (current != null) {
        val bottomPadding = with(LocalDensity.current) { 48.dp.toPx() }.toInt()
        // 首帧 false→true 即触发 enter 动画, 不必靠延迟补帧
        val visibleState = remember(current) { MutableTransitionState(false) }
        visibleState.targetState = true
        LaunchedEffect(current) {
            delay(if (current.long) 3500L else 2500L)
            visibleState.targetState = false
            delay(TOAST_EXIT_MILLIS.toLong())
            DesktopToasts.dismiss()
        }
        Popup(
            alignment = Alignment.BottomCenter,
            offset = IntOffset(0, -bottomPadding),
            onDismissRequest = { /* toast 由计时器关闭, 不响应点击/外部 */ },
            properties = PopupProperties(
                dismissOnClickOutside = false,
                dismissOnBackPress = false,
            ),
        ) {
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(),
                exit = fadeOut(tween(TOAST_EXIT_MILLIS)),
            ) {
                val corner = 8.dp
                val shadowOffset = 2.dp
                Text(
                    text = current.text,
                    color = AppTheme.colors.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier
                        // 阴影只能画在节点 bounds 内: fade 期间 alpha<1 把内容提升到离屏缓冲,
                        // 缓冲按 bounds 裁剪, 界外像素要等 alpha 回到 1 才出现(即阴影慢半拍)。
                        // 故底部先 padding 留出阴影带, 阴影画进这条留白。
                        .drawBehind {
                            val dy = shadowOffset.toPx()
                            drawRoundRect(
                                color = Color.Black.copy(alpha = 0.18f),
                                topLeft = Offset(0f, dy),
                                size = size.copy(height = size.height - dy),
                                cornerRadius = CornerRadius(corner.toPx()),
                            )
                        }
                        .padding(bottom = shadowOffset)
                        .background(AppTheme.colors.bottomBackground, RoundedCornerShape(corner))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
