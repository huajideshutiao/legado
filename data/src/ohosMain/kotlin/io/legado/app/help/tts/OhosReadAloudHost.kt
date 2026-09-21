package io.legado.app.help.tts

/**
 * 鸿蒙端朗读宿主: 实现全在 [ReadAloudHostShared], 无鸿蒙专属部分。
 *
 * 鸿蒙 textToSpeech 无 pause/resume API, 暂停/恢复由 `OhosSystemTtsEngine` 的 paused 标志
 * 退化为重播当前段。
 */
object OhosReadAloudHost : ReadAloudHostShared()
