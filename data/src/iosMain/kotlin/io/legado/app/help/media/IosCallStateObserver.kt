@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.media

import io.legado.app.help.tts.IosReadAloudHost
import platform.CallKit.CXCall
import platform.CallKit.CXCallObserver
import platform.CallKit.CXCallObserverDelegateProtocol
import platform.darwin.NSObject

/**
 * iOS 来电事件源: `CXCallObserver` (对照 app 端 `PhoneStateListener.LISTEN_CALL_STATE`)。
 *
 * 把系统的呼叫列表翻译成 [PhoneCallState] (三态一一对应), 消费门槛与恢复语义都在
 * [io.legado.app.help.tts.ReadAloudHostShared.onPhoneCallState] 与
 * [PhoneCallPauseHandler] (app 端同一份)。
 *
 * # 翻译约定
 * `CXCallObserver` 没有 Android 那样的单一状态码, 只有呼叫列表; 按"当前是否有呼叫"归类:
 * - 列表为空 → [PhoneCallState.Idle]
 * - 有呼叫且已接通 (`hasConnected`, 含用户自己拨出的) → [PhoneCallState.OffHook]
 * - 否则 (正在响铃/拨打中) → [PhoneCallState.Ringing]
 *
 * `CXCallObserver` 无需额外权限 (与 Android 的 READ_PHONE_STATE 不同), 故 iOS 端没有
 * 权限申请环节。
 *
 * # 已知语义差异 (如实报告, 不得静默降级)
 * Android 的 `PhoneStateListener` 给的是"状态常量 + 跳变", iOS 只给"当前呼叫集合"快照。
 * 映射只能按集合归类, 拿不到 Android 那种独立的 Idle 跳变与结束原因。实际影响: 若系统把
 * "响铃→挂断/未接"压成单次回调 (集合已空), 本端就不会收到 Ringing, 也就不暂停
 * (相应地也不需要恢复), 行为自洽但少了一次暂停。已接通/正在响铃的情形一定能收到。
 *
 * # 写法约束 (Kotlin/Native 官方限制)
 * 必须是 **final class**: 官方文档明写 Kotlin 只能以 `final class` 继承 ObjC 类;
 * `object` 声明的实例是"类对象", 不走 ObjC 实例分配路径, 编译器会以
 * `Allocation of Obj-C class ... should have been lowered` 直接崩在代码生成阶段。
 */
private class IosCallObserverDelegate : NSObject(), CXCallObserverDelegateProtocol {

    private val observer = CXCallObserver()

    /** 挂上观察者 (宿主启动早期调用一次)。 */
    fun register() {
        observer.setDelegate(this, null)
    }

    override fun callObserver(callObserver: CXCallObserver, callChanged: CXCall) {
        IosReadAloudHost.onPhoneCallState(currentState())
    }

    /** 当前呼叫列表 → 三态 (只在有已接通呼叫时算接听)。 */
    private fun currentState(): PhoneCallState {
        val calls = observer.calls
        if (calls.isEmpty()) return PhoneCallState.Idle
        return if (calls.any { (it as? CXCall)?.hasConnected == true }) {
            PhoneCallState.OffHook
        } else {
            PhoneCallState.Ringing
        }
    }
}

// K/N 限制: ObjC 子类 (NSObject) 的 companion 不允许字段, 单例实例放文件顶层
// (ARC 持有 delegate, 故只建一次并常驻)
private val iosCallObserverDelegate = IosCallObserverDelegate()

/** 安装 iOS 来电观察者 (由 [registerIosMediaNotificationController] 调用)。 */
internal fun registerIosCallStateObserver() {
    iosCallObserverDelegate.register()
}
