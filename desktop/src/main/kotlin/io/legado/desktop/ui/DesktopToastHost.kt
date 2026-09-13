package io.legado.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 桌面端 Toast 宿主, 由 desktop Main.kt 挂在主窗口 Compose 根容器顶层 (与 LegadoApp 处于同一根 Box)。
 *
 * 采用 Compose 顶层 Overlay 呈现: 位于最外层 Box 顶层 (Z-Order 天然最高),
 * 锚定主窗口底部居中 (48dp safe padding), 点击事件天然穿透到底层页面,
 * 不依赖 AWT/Swing Popup 跨层图层, 避免图层边界与时序异常。
 * 外观对齐 Android 原版 Toast 标准语义 (高对比度深色半透明胶囊 + 白色文本)。
 */
@Composable
fun DesktopToastHost(
    modifier: Modifier = Modifier,
) {
    val msg by DesktopToasts.current.collectAsState()
    // 委托属性无法 smart cast, 局部捕获
    val current = msg

    if (current != null) {
        // 首帧 false→true 入场动画仅在消息切换时触发一次, 避免重组中反复篡改 targetState
        val visibleState = remember(current) {
            MutableTransitionState(false).apply { targetState = true }
        }
        LaunchedEffect(current) {
            delay(if (current.long) 3500L else 2500L)
            visibleState.targetState = false
            delay(TOAST_EXIT_MILLIS.toLong())
            DesktopToasts.dismiss()
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(bottom = 48.dp),
            contentAlignment = Alignment.BottomCenter,
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
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        // 阴影自绘在 bounds 内, 与淡入淡出动画同步
                        .drawBehind {
                            val dy = shadowOffset.toPx()
                            drawRoundRect(
                                color = Color.Black.copy(alpha = 0.36f),
                                topLeft = Offset(0f, dy),
                                size = size.copy(height = size.height - dy),
                                cornerRadius = CornerRadius(corner.toPx()),
                            )
                        }
                        .padding(bottom = shadowOffset)
                        .background(Color(0xE6212121), RoundedCornerShape(corner))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
