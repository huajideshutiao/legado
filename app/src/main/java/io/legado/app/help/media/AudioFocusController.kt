@file:Suppress("DEPRECATION") // androidx.media 的 AudioFocus*Compat 整体弃用, 同 MediaHelp

package io.legado.app.help.media

import android.media.AudioManager
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.audioManager
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig

/**
 * Android 音频焦点壳: 状态机在 commonMain [AudioFocusHandler], 本类只做平台两件事 ——
 * 申请/归还 `AudioFocusRequestCompat`, 以及把 `AudioManager.AUDIOFOCUS_*` 常量翻译成
 * [AudioFocusChange]。
 *
 * [AppConfig.ignoreAudioFocus] 开启时 request 直接返回 true (不申请系统焦点), 事件回调也由
 * 状态机自行跳过 —— 与下沉前逐字一致。
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

    private val handler = AudioFocusHandler(
        logTag = logTag,
        isPaused = isPaused,
        onPause = onPause,
        onResume = onResume,
    )

    fun request(): Boolean {
        if (AppConfig.ignoreAudioFocus) return true
        handler.onRequest()
        return MediaHelp.requestFocus(focusRequest)
    }

    fun abandon() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
    }

    override fun onAudioFocusChange(focusChange: Int) {
        handler.onFocusChange(
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> AudioFocusChange.Gain
                AudioManager.AUDIOFOCUS_LOSS -> AudioFocusChange.Loss
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusChange.LossTransient
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    AudioFocusChange.LossTransientCanDuck

                // 未知常量: 不下沉件, 保持"无动作" (原 when 无 else 分支, 同样无动作)
                else -> return
            }
        )
    }
}
