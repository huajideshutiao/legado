package io.legado.app.service

import io.legado.app.constant.AppPattern
import io.legado.app.help.tts.ReadAloudQueue

/**
 * TTS 朗读纯逻辑（KMP commonMain 版）。
 *
 * 从 app 端 `TTSReadAloudService.play()` 抽取的批量入队朗读计划计算:
 * 给定段落列表、起始段、段内偏移,计算真正需要交给引擎朗读的 (段下标, 文本) 序列。
 *
 * 平台相关（Android TextToSpeech.QUEUE_FLUSH/ADD、UtteranceProgressListener、
 * 引擎 init/retry、章节切换、音频焦点）留 app 端。
 *
 * 各端复用此纯逻辑时, 自行实现引擎调用 / 错误处理 / 章节联动。
 */
object TtsReadAloudShared {

    /**
     * 单条朗读计划项。
     *
     * @param index 段落在 contentList 中的下标（供调用方构造 utteranceId）
     * @param text 实际交给引擎朗读的文本（已应用 paragraphStartPos 偏移）
     */
    data class SpeakItem(val index: Int, val text: String)

    /**
     * 计算批量朗读计划。
     *
     * 行为对齐 app 端 `TTSReadAloudService.play()` 的循环体:
     * - 从 [ReadAloudQueue.nowSpeak] 遍历到 contentList 末尾
     * - 第一段应用 [ReadAloudQueue.paragraphStartPos] 偏移
     * - 跳过匹配 [AppPattern.notReadAloudRegex] 的段
     *
     * @param queue 朗读队列状态机
     * @return 待朗读的 [SpeakItem] 列表; 空列表表示当前章节无可朗读段（调用方应切下一章）
     */
    fun buildSpeakPlan(queue: ReadAloudQueue): List<SpeakItem> {
        val plan = mutableListOf<SpeakItem>()
        val list = queue.contentList
        for (i in queue.nowSpeak until list.size) {
            var text = list[i]
            if (queue.paragraphStartPos > 0 && i == queue.nowSpeak) {
                text = text.substring(queue.paragraphStartPos)
            }
            if (text.matches(AppPattern.notReadAloudRegex)) continue
            plan += SpeakItem(i, text)
        }
        return plan
    }
}
