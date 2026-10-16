package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

/**
 * 跨平台系统返回键拦截 (对照 app 端 `androidx.activity.compose.BackHandler`)。
 *
 * - Android: 委托 `androidx.activity.compose.BackHandler`; 预测式返回开关打开时让位给
 *   [PlatformPredictiveBackHandler] 的根层接管 (见 Android actual)
 * - 鸿蒙: 注册到 ArkUI `OnBackPressedDispatcher` (见 Ohos actual)
 * - 桌面 JVM: 无系统返回键, actual 为 no-op; 该端页面级返回拦截要经 [AppBackHandler] 注册进
 *   统一返回链 (ESC → `performBack`), 本函数对其无效
 * - iOS: Compose 框架内置的左边缘拖动返回手势由**根层唯一处理器**消费 (见
 *   [PlatformPredictiveBackHandler]), 本函数同样为 no-op; 页面级拦截经统一返回链在松手时判定
 *
 * 页面内的子状态返回一律用 [AppBackHandler] (它同时注册平台通道与统一返回链), 只用本函数会在
 * 无系统返回键的端变成"进得去退不出"。
 *
 * shared/sharedUiMain 未引入 activity-compose 依赖, 故走 expect/actual 抽离。
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * 跨平台"系统返回手势"的跟手通道: [onGesture] 收到的流每发一个值就是一次跟手进度
 * (0f = 本段起点位, 1f = 终位), 流正常结束 = 框架判定手势完成, 流以取消异常结束 = 手势取消。
 *
 * 有该通道的端: iOS (框架内置左边缘拖动识别器) 与 Android (系统预测式返回, 进度自 API 34 起)。
 * 两端都只注册一个根层处理器: 手势跟手驱动页面转场, 松手后走统一返回链
 * ([io.legado.app.ui.root.performBack])。鸿蒙/桌面的系统返回只有"按下"没有进度, actual 为 no-op。
 *
 * "没有滑动过就完成"的到达形状两端不同, 由平台 actual 各自解释:
 * - Android 的按键/硬件返回与旧式返回手势不给进度事件, 那是真的一次返回 → [onBackClick];
 * - iOS 的空流来自"识别器失败被当成完成上报"(左边缘单击也会走到) → 忽略。
 *
 * @param onGesture 消费本次会话的进度流 (口径与流程见 [io.legado.app.ui.root.driveInteractiveBack]);
 *   返回 true = 这次确实是在滑动 (收到过至少一个进度) —— Android actual 据此区分"点击式返回"。
 */
@Composable
expect fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onGesture: suspend (gesture: Flow<Float>) -> Boolean,
    onBackClick: () -> Unit,
)
