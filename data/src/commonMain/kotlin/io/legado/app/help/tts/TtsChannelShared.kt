package io.legado.app.help.tts

import io.legado.app.data.entities.HttpTTS

/** 在线朗读通道: 源配置 + 已创建的平台播放器。 */
class HttpTtsChannel(val config: HttpTTS, val player: HttpTtsPlayer)

/**
 * 配置串 → 朗读通道判定 (对照原版 `ReadAloud.getReadAloudClass`)。
 *
 * 空白 / 非数字 / 查不到源 / 平台未注册在线播放器 ⇒ null, 调用方走系统 TTS
 * (对照原版 `httpTTS == null` 时返回 `TTSReadAloudService`)。
 * 正式朗读与一次性朗读共用本判定, 两条通道的回落分支才能一致。
 */
suspend fun resolveHttpTtsChannel(
    ttsEngineConfig: String?,
    httpTtsConfigLoader: suspend (Long) -> HttpTTS?,
    httpTtsPlayerFactory: (HttpTTS) -> HttpTtsPlayer?,
): HttpTtsChannel? {
    val configStr = ttsEngineConfig?.takeIf { it.isNotBlank() } ?: return null
    if (configStr.any { !it.isDigit() }) return null
    val id = configStr.toLongOrNull() ?: return null
    val config = httpTtsConfigLoader(id) ?: return null
    return openHttpTtsChannel(config, httpTtsPlayerFactory)
}

/**
 * 已定源配置 → 在线通道 (朗读试听等指定引擎入口); 平台未注册在线播放器 ⇒ null, 走系统 TTS。
 */
fun openHttpTtsChannel(
    config: HttpTTS?,
    httpTtsPlayerFactory: (HttpTTS) -> HttpTtsPlayer?,
): HttpTtsChannel? {
    if (config == null) return null
    val player = httpTtsPlayerFactory(config) ?: return null
    return HttpTtsChannel(config, player)
}
