package io.legado.desktop.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import io.legado.app.ui.compose.theme.AppTheme
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
    var visible by mutableStateOf(false)
    // 委托属性无法 smart cast, 局部捕获
    val current = msg

    if (current != null) {
        val bottomPadding = with(LocalDensity.current) { 48.dp.toPx() }.toInt()
        LaunchedEffect(current) {
            visible = false
            // 下一帧再显示, 保证 AnimatedVisibility 的 enter 动画触发
            delay(16)
            visible = true
            delay(if (current.long) 3500L else 2500L)
            visible = false
            delay(200)
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
                visible = visible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = current.text,
                    color = AppTheme.colors.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .shadow(4.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppTheme.colors.bottomBackground)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
