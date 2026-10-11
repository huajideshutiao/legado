package io.legado.app.help.tts

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigConstants
import io.legado.app.help.config.PreferenceProviders

/**
 * 当前生效语速档位 0..45。
 *
 * 对照原版 `AppConfig.speechRatePlay`: 跟随系统时用 [AppConfigConstants.defaultSpeechRate],
 * 否则用用户设定值。
 */
fun configuredTtsSpeechRate(): Int {
    val prefs = PreferenceProviders.get()
    return if (prefs.getBoolean(PreferKey.ttsFollowSys, true)) {
        AppConfigConstants.defaultSpeechRate
    } else {
        prefs.getInt(PreferKey.ttsSpeechRate, AppConfigConstants.defaultSpeechRate)
    }
}

/** 语速档位 → 引擎倍率 (对照原版 `TTSReadAloudService.upSpeechRate` 的 `(ttsSpeechRate + 5) / 10f`)。 */
fun ttsSpeechRateScale(rate: Int): Float = (rate + 5) / 10f
