package io.legado.app.help.media

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders

/**
 * 音频焦点变更事件 (平台无关口径, 由各端事件源翻译而来)。
 *
 * 四档与 Android `AudioManager.AUDIOFOCUS_*` 一一对应, 也是各端能力上限:
 * - [Gain] / [Loss] / [LossTransient]: iOS `AVAudioSessionInterruptionNotification` (Began/Ended)
 *   与鸿蒙 `InterruptHint` 都能提供;
 * - [LossTransientCanDuck]: 仅 Android (AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) 与
 *   鸿蒙 (`INTERRUPT_HINT_DUCK`) 有; iOS 无等价枚举 (该档在本状态机里也只是"不处理")。
 */
enum class AudioFocusChange {
    Gain,
    Loss,
    LossTransient,
    LossTransientCanDuck,
}

/**
 * 音频焦点状态机 (Android / iOS / 鸿蒙 三端共用一份, 原 app 端 `AudioFocusController` 下沉)。
 *
 * - [AudioFocusChange.Loss]: 永久丢失 -> 暂停并放弃焦点
 * - [AudioFocusChange.LossTransient]: 短暂丢失 -> 暂停但不放弃, 焦点恢复时自动 resume
 * - [AudioFocusChange.LossTransientCanDuck]: 仅压低音量场景, 不处理
 * - [AudioFocusChange.Gain]: 恢复焦点, 若先前是 transient 暂停则触发 resume
 *
 * `ignoreAudioFocus` 开启时 [onFocusChange] 全部跳过 (与 app 端短路语义一致);
 * 各端"是否真的不申请/不归还平台焦点"由各端壳负责。
 *
 * # 谁负责申请/归还平台焦点
 * 各端不同, 故不进本类: Android 由 app 端 `AudioFocusController` 壳负责 (真正的
 * `requestAudioFocus` / `abandonAudioFocusRequest`); iOS / 鸿蒙 由平台播控写入端在
 * 卡片状态变化时顺带开关音频会话/焦点 (见 [NowPlayingSink.setAudioFocus])。
 */
class AudioFocusHandler(
    private val logTag: String,
    private val isPaused: () -> Boolean,
    private val onPause: (abandonFocus: Boolean) -> Unit,
    private val onResume: () -> Unit,
) {

    private var needResumeOnGain = false

    /**
     * 请求焦点/开始一次焦点周期: 清掉上一轮残留的 "待恢复" 标记。
     *
     * 对照 app 端 `AudioFocusController.request()` 的 `needResumeOnGain = false`。
     */
    fun onRequest() {
        needResumeOnGain = false
    }

    /** 处理一次焦点变更事件 (各端事件源翻译成 [AudioFocusChange] 后调用)。 */
    fun onFocusChange(change: AudioFocusChange) {
        if (ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理($logTag)")
            return
        }
        when (change) {
            AudioFocusChange.Gain -> {
                if (needResumeOnGain) {
                    AppLog.put("音频焦点获得,继续($logTag)")
                    needResumeOnGain = false
                    onResume()
                } else {
                    AppLog.put("音频焦点获得($logTag)")
                }
            }

            AudioFocusChange.Loss -> {
                AppLog.put("音频焦点丢失,暂停($logTag)")
                onPause(true)
            }

            AudioFocusChange.LossTransient -> {
                AppLog.put("音频焦点暂时丢失,暂停($logTag)")
                if (!isPaused()) {
                    needResumeOnGain = true
                    onPause(false)
                }
            }

            AudioFocusChange.LossTransientCanDuck -> {
                AppLog.put("音频焦点短暂丢失,不做处理($logTag)")
            }
        }
    }

    /** 开启后不处理任何焦点事件 (对照 app 端 `AppConfig.ignoreAudioFocus` 短路)。 */
    private val ignoreAudioFocus: Boolean
        get() = PreferenceProviders.get().getBoolean(PreferKey.ignoreAudioFocus, false)
}
