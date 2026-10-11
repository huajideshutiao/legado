package io.legado.app.service

import io.legado.app.constant.AppPattern
import io.legado.app.help.tts.ReadAloudQueue

/**
 * 朗读段落计划构建器 (对照原版 newReadAloud 的段落表建立): 从章节文本切段, 按起始位置
 * 裁掉已读部分; 段起点累加方式 (段长 + 1 个换行) 与 [ReadAloudQueue.readAloudNumber] 一致。
 *
 * shared 朗读宿主 ([io.legado.app.help.tts.ReadAloudHostShared]) 与 Android 朗读服务
 * 共用同一算法; 宿主用 [paragraphOffsets]/[paragraphBase]/[firstParagraphSkip] 把控制器的
 * 段下标换算回章内字符位置。
 */
class ReadAloudPlanBuilder {

    /** 最近一次构建的完整段落表 (未按 startPos 裁剪)。 */
    var paragraphs: List<String> = emptyList()
        private set

    /** 各段在章内的起始字符位置。 */
    var paragraphOffsets: IntArray = IntArray(0)
        private set

    /** 控制器段下标 0 对应的真实段下标 (startPos 裁剪产生的偏移)。 */
    var paragraphBase: Int = 0
        private set

    /** 首段被 startPos 裁掉的段内字符数。 */
    var firstParagraphSkip: Int = 0
        private set

    /**
     * 建段落表并按 [chapterPosition] 裁掉已读部分 (对照原版 getParagraphNum +
     * paragraphStartPos: 从包含起始位置的那一段开始朗读, 段内已读字符直接截掉)。
     * 起始位置恰在段界时落到下一段, 不在段尾残留零长度朗读。
     *
     * 计划里的 [ReadAloudChapterPlan.chapterPosition] 取**截断后的真实起点**
     * (段起点 + 段内偏移), 与 [ReadAloudQueue.readAloudNumber] 的累加空间一致 ——
     * 请求位置超出段落表末尾或恰落在段界时, 两者不相等。
     *
     * @param text 章节朗读文本 (已排版章节取页文本拼接, 按页朗读时页间补换行)
     * @return null = 无可朗读段落 / fromLastSpeakable 且无最后可读段
     */
    fun buildPlan(
        text: String,
        chapterPosition: Int,
        fromLastSpeakable: Boolean,
    ): ReadAloudChapterPlan? {
        val all = ReadAloudQueue.splitParagraphs(text)
        if (all.isEmpty()) return null
        val offsets = IntArray(all.size)
        var acc = 0
        for (i in all.indices) {
            offsets[i] = acc
            acc += all[i].length + 1
        }
        paragraphs = all
        paragraphOffsets = offsets

        val startPos = chapterPosition.coerceAtLeast(0)
        var base = 0
        while (base + 1 < all.size && offsets[base + 1] <= startPos) base++
        var skip = (startPos - offsets[base]).coerceIn(0, all[base].length)
        if (skip >= all[base].length && base + 1 < all.size) {
            base++
            skip = 0
        }
        paragraphBase = base
        firstParagraphSkip = skip
        val realStartPos = offsets[base] + skip
        val sub = all.subList(base, all.size).toMutableList()
        if (skip > 0) sub[0] = sub[0].substring(skip)
        if (!fromLastSpeakable) {
            return ReadAloudChapterPlan(sub, chapterPosition = realStartPos)
        }
        val index = sub.indexOfLast { !it.matches(AppPattern.notReadAloudRegex) }
        if (index < 0) return null
        val chapterPos = paragraphOffsets.getOrNull(paragraphBase + index)
            ?: sub.take(index).sumOf { it.length + 1 }
        return ReadAloudChapterPlan(
            paragraphs = sub,
            paragraphIndex = index,
            paragraphOffset = 0,
            chapterPosition = chapterPos,
        )
    }
}
