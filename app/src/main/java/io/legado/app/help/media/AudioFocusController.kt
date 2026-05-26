package io.legado.app.help.media

import android.media.AudioManager
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.constant.AppLog
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import splitties.systemservices.audioManager

/**
 * 音频焦点状态机:
 * - LOSS:永久丢失 -> 暂停并放弃焦点
 * - LOSS_TRANSIENT:短暂丢失 -> 暂停但不放弃,焦点恢复时自动 resume
 * - LOSS_TRANSIENT_CAN_DUCK:仅压低音量场景,不处理
 * - GAIN:恢复焦点,若先前是 transient 暂停则触发 resume
 *
 * [AppConfig.ignoreAudioFocus] 开启时 request 直接返回 true 且回调全部跳过。
 */
class AudioFocusController(
    private val logTag: String,
    private val isPaused: () -> Boolean,
    private val onPause: (abandonFocus: Boolean) -> Unit,
    private val onResume: () -> Unit
) : AudioManager.OnAudioFocusChangeListener {

    private val focusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }

    private var needResumeOnGain = false

    fun request(): Boolean {
        if (AppConfig.ignoreAudioFocus) return true
        needResumeOnGain = false
        return MediaHelp.requestFocus(focusRequest)
    }

    fun abandon() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
    }

    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理($logTag)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnGain) {
                    AppLog.put("音频焦点获得,继续($logTag)")
                    needResumeOnGain = false
                    onResume()
                } else {
                    AppLog.put("音频焦点获得($logTag)")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停($logTag)")
                onPause(true)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失,暂停($logTag)")
                if (!isPaused()) {
                    needResumeOnGain = true
                    onPause(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                AppLog.put("音频焦点短暂丢失,不做处理($logTag)")
            }
        }
    }
}
