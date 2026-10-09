package io.legado.app.help.media

import io.legado.app.constant.AppLog

/**
 * 电话状态 (平台无关口径, 与 Android `TelephonyManager.CALL_STATE_*` 三态一一对应)。
 *
 * iOS 由 `CXCallObserver` 的呼叫列表翻译, 鸿蒙由 `@ohos.telephony.observer` 的
 * `callStateChange` (`CallState` 枚举) 翻译。
 */
enum class PhoneCallState {
    Idle,
    Ringing,
    OffHook,
}

/**
 * 来电暂停朗读的三态状态机 (原 app 端 `BaseReadAloudService.ReadAloudPhoneStateListener` 下沉)。
 *
 * - [PhoneCallState.Ringing]: 正在朗读才暂停, 并记下"结束后恢复"标记
 * - [PhoneCallState.Idle]: 有标记则恢复朗读
 * - [PhoneCallState.OffHook]: 不处理 (接听后用户自己决定)
 *
 * 日志文案与 Android 原实现逐字一致。
 *
 * # 保真说明 (标记不重置)
 * 原实现在 Idle 分支恢复朗读后**并不清除** "待恢复" 标记 —— 若系统连发两次 Idle,
 * 第二次仍会再调一次恢复。此处逐字搬运该行为 (Android 上行为零变化的硬约束),
 * 不顺手"修正"。要不要改成恢复后清标记属行为变更, 见交付报告。
 */
class PhoneCallPauseHandler(
    private val isPaused: () -> Boolean,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {

    private var needResumeOnCallStateIdle = false

    fun onCallStateChanged(state: PhoneCallState) {
        when (state) {
            PhoneCallState.Idle -> {
                if (needResumeOnCallStateIdle) {
                    AppLog.put("来电结束,继续朗读")
                    onResume()
                } else {
                    AppLog.put("来电结束")
                }
            }

            PhoneCallState.Ringing -> {
                if (!isPaused()) {
                    AppLog.put("来电响铃,暂停朗读")
                    needResumeOnCallStateIdle = true
                    onPause()
                } else {
                    AppLog.put("来电响铃")
                }
            }

            PhoneCallState.OffHook -> AppLog.put("来电接听,不做处理")
        }
    }
}
