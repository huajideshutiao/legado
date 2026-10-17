package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackEventCompat
import androidx.compose.ui.backhandler.PredictiveBackHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [PlatformBackHandler] 的 iOS actual: no-op。
 *
 * 本端没有物理返回键, 也没有导航容器承载侧滑返回 (宿主是 SwiftUI `WindowGroup { ContentView() }`
 * 直接包一层 `UIViewControllerRepresentable` 呈现 Compose 根 VC, 全仓无 `NavigationStack` /
 * `UINavigationController`, 页面栈完全由 shared [io.legado.app.ui.root.AppNavigator] 自管),
 * 但**框架自带**了 iOS 的系统级边缘手势: Compose 在窗口内容顶层视图上装
 * `UIScreenEdgePanGestureRecognizer` (起始边 = 左, RTL 互换), 只在存在启用中的返回处理器时
 * 点亮, 并给出手势进度; 该识别器优先于 Compose 自身的手势识别器 (不能被子树手势阻止),
 * "完成/取消"由框架判定 (位移 ≥ 屏宽 30% 或速度 > 100 算完成)。
 *
 * 本端**只由根层一个处理器**消费这条手势 (页面栈的跟手转场 + 松手后的统一返回链,
 * 见 [PlatformPredictiveBackHandler]), 页面级 [AppBackHandler] 不注册平台处理器: 它们经统一
 * 返回链在**松手时**判定 (先关覆盖物层/弹层, 再页面级拦截, 最后出栈), 与其它端系统返回的
 * 先后一致, 也保住了手势的跟手观感 (注册第二个处理器只会把该页面的手势降级成"抬手即返回")。
 *
 * 后果 (调用方必须自行兜住): 没有"抬手即返回"的瞬时返回通道 —— 外接键盘 ESC 走
 * `handleBackKey` 的键路径, 不经此处; 全屏 / 覆盖层等把顶栏一起隐掉的界面仍必须常驻一个
 * 可见退出口 (见 [io.legado.app.ui.root.PlatformCapabilities.supportsSystemBack])。
 *
 * 框架把识别器"失败"也当"完成"上报 (源码 `UIGestureRecognizerStateFailed -> dispatchOnEventCompleted`),
 * 左边缘单击会以"空手势"到达 —— 跟手通道要求真的收到过进度才动作, 单击边缘不返回; 因此
 * [PlatformPredictiveBackHandler] 的 onBackClick 出口在本端没有真实调用方。
 * 与 Android 共用同一条驱动规则"滑动过就一定要返回": 拖到最后还是没能接管本段 (上一段动画
 * 尚未落定) 时, 松手也会走一次统一返回链 —— 边缘滑动不允许被吞掉。
 * 弹层打开期间框架的对话框自身也注册了返回处理器 (更晚注册 = 优先级更高, 且只认无进度的瞬时
 * 返回), 边缘滑动不会关闭弹层 (待真机确认)。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op: 本端的返回手势由根层跟手通道统一消费
}

// 该通道的非废弃替代品 (NavigationEventHandler) 需 navigationevent-compose 依赖, 而 CMP 1.11.1
// 只把 navigationevent 核心件放进 classpath; 迁移随 CMP 升级一并做。
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onGesture: suspend (gesture: Flow<Float>) -> Boolean,
    onBackClick: () -> Unit,
) {
    val currentOnGesture by rememberUpdatedState(onGesture)
    // 处理器按 (组合键, onBack) 记忆: lambda 每次重组换新实例会注销重注册, 手势途中重组即被掐断,
    // 故用稳定 lambda + rememberUpdatedState 取最新回调; enabled 由框架在重组时就地更新, 不进记忆键。
    val gestureHandler: suspend (Flow<BackEventCompat>) -> Unit = remember {
        { progress ->
            currentOnGesture(progress.map { it.progress })
        }
    }
    PredictiveBackHandler(enabled = enabled, onBack = gestureHandler)
}
