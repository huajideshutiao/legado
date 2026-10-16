package io.legado.app.ui.root

import androidx.compose.animation.core.Easing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 消费一次跟手的系统返回手势 (平台通道见
 * [io.legado.app.ui.compose.platform.PlatformPredictiveBackHandler])。
 *
 * 事件口径与框架一致: 流每发一个值 = 一次跟手进度, 流正常结束 = 判定完成, 流以取消异常结束 = 判定取消。
 * - 首个进度值到达才接管本段 (页面开始跟手); 若那一刻上一段尚未落定 (接管被拒), 后续进度值会再试,
 *   接管成功后页面从手指当前位置开始跟手;
 * - 真的滑动过就一定要返回: 接管成功时调到 [onComplete] (调用方在那里走统一返回链),
 *   动画从当前进度续播到终位不回跳; 没能接管 (栈内没有上一页 / 上一段还在播) 时**也**调
 *   [onComplete] —— 边缘滑动不允许被吞掉 (Android 主界面就是从边缘滑动切回标签/退出的);
 * - 出栈后本段仍挂在原栈上 (返回被覆盖物层/页面级拦截消费) 时自己弹回落定, 不留半路停住的页面;
 * - 没滑动过的完成不是手势 (Android 按键/硬件返回、iOS 识别器失败), 本函数不做任何动作 ——
 *   平台的跟手通道自行决定要不要当成一次返回 (Android 走"点击式返回"出口, iOS 忽略);
 * - 取消时把进度弹回起点并落定, 页面栈不变; 取消异常**照常抛出**, 平台实现据此把"取消"与
 *   "无进度的完成"分开;
 * - 全程不做动作的情形只剩两种: 没滑动过的完成、以及被拒后又取消。
 *
 * @return true = 这次确实是在滑动 (收到过至少一个进度), 调用方据此区分"点击式返回"。
 * @param scope 取消收尾用的作用域 (调用方的组合期作用域): 取消必须交回它执行 —— Android 的框架在
 *   取消时会把本次手势的协程一并取消, 就地收尾会被直接跳过 (页面停在手指位置, 此后手势全失效);
 *   组合已销毁时该作用域同样已取消, 收尾自然丢弃 (状态机随组合消失)。
 */
suspend fun RouteTransitionState.driveInteractiveBack(
    gesture: Flow<Float>,
    entries: () -> List<RouteEntry>,
    scope: CoroutineScope,
    cancelDurationMillis: Int,
    cancelEasing: Easing,
    onComplete: () -> Unit,
): Boolean {
    var taken = false
    var sawProgress = false
    try {
        gesture.collect { value ->
            sawProgress = true
            if (taken) {
                dragInteractiveBack(entries(), value)
            } else {
                taken = beginInteractiveBack(entries(), value)
            }
        }
        // 流正常结束 = 框架判定完成。滑动过就一定要返回 (接管失败也不例外, 否则边缘滑动会被吞掉),
        // 没滑动过的完成交给平台的跟手通道自己判 (见其 onBackClick / 忽略)。
        if (sawProgress) {
            onComplete()
            // 出栈是否真的发生只能由状态机说话: 本段仍与真实页面栈匹配 = 这一次返回被别的层消费掉了
            if (taken && isInteractiveBackPending(entries())) {
                cancelInteractiveBack(entries(), cancelDurationMillis, cancelEasing)
            }
        }
        return sawProgress
    } catch (e: CancellationException) {
        if (taken) {
            scope.launch { cancelInteractiveBack(entries(), cancelDurationMillis, cancelEasing) }
        }
        throw e
    }
}
