package io.legado.app.service

import io.legado.app.constant.AppPattern
import io.legado.app.help.tts.ReadAloudQueue
import kotlinx.atomicfu.atomic

/**
 * 朗读段落计划构建器 (对照原版 newReadAloud 的段落表建立): 从章节文本切段, 按起始位置
 * 裁掉已读部分; 段起点累加方式 (段长 + 1 个换行) 与 [ReadAloudQueue.Snapshot.readAloudNumber] 一致。
 *
 * shared 朗读宿主 ([io.legado.app.help.tts.ReadAloudHostShared]) 与 Android 朗读服务
 * 共用同一算法; 宿主用 [snapshot] 把控制器的段下标换算回章内字符位置。
 *
 * 线程: [buildPlan] 在控制器线程执行 (start 的调用线程 / 引擎回调线程), 换算在宿主协程执行。
 * 几何量互相定义 (段下标按裁剪偏移换算、段起点按段下标取), 逐字段读会取到跨版本的混合值,
 * 故整份存为不可变 [Geometry] 并原子发布 —— 读方一次 [snapshot] 取值即自洽。
 */
class ReadAloudPlanBuilder {

    /**
     * 一次构建出来的段落几何 (不可变)。
     *
     * @param paragraphs 完整段落表 (未按 startPos 裁剪); 空表 = 章节无内容
     * @param paragraphOffsets 各段在章内的起始字符位置; 构建后不再改动
     * @param paragraphBase 控制器段下标 0 对应的真实段下标 (startPos 裁剪产生的偏移)
     * @param firstParagraphSkip 首段被 startPos 裁掉的段内字符数
     */
    class Geometry(
        val paragraphs: List<String>,
        val paragraphOffsets: IntArray,
        val paragraphBase: Int,
        val firstParagraphSkip: Int,
    ) {
        /** 该段在章内的起始字符位置; 段下标越界返回 null。 */
        fun paragraphStart(realIndex: Int): Int? = paragraphOffsets.getOrNull(realIndex)
    }

    /** 空 geometry: 无内容与尚未构建同一份取值。 */
    private val emptyGeometry = Geometry(emptyList(), IntArray(0), 0, 0)

    private val geometry = atomic(emptyGeometry)

    /** 当前几何: 读方一次取值, 不能逐字段读。 */
    val snapshot: Geometry get() = geometry.value

    /**
     * 建段落表并按 [chapterPosition] 裁掉已读部分 (对照原版 getParagraphNum +
     * paragraphStartPos: 从包含起始位置的那一段开始朗读, 段内已读字符直接截掉)。
     * 起始位置恰在段界时落到下一段, 不在段尾残留零长度朗读。
     *
     * "无内容"只有一种表示: 段表为空 (起点在末段末尾或更后、整章无段落、fromLastSpeakable
     * 且无最后可读段), 调用方按同一条无内容路径跳过 —— 不产出空串段让引擎去读空文本。
     *
     * 计划里的 [ReadAloudChapterPlan.chapterPosition] 取**截断后的真实起点**
     * (段起点 + 段内偏移), 与 [ReadAloudQueue.Snapshot.readAloudNumber] 的累加空间一致 ——
     * 请求位置超出段落表末尾或恰落在某段末尾 (换行符位置) 时, 两者不相等。
     *
     * @param text 章节朗读文本 (已排版章节取页文本拼接, 按页朗读时页间补换行)
     * @return 待朗读段表; 段表为空表示起点之后无内容
     */
    fun buildPlan(
        text: String,
        chapterPosition: Int,
        fromLastSpeakable: Boolean,
    ): ReadAloudChapterPlan {
        val all = ReadAloudQueue.splitParagraphs(text)
        val offsets = IntArray(all.size)
        var acc = 0
        for (i in all.indices) {
            offsets[i] = acc
            acc += all[i].length + 1
        }
        if (all.isEmpty()) {
            publishGeometry(all, offsets, paragraphBase = 0, firstParagraphSkip = 0)
            return ReadAloudChapterPlan(emptyList())
        }

        val startPos = chapterPosition.coerceAtLeast(0)
        var base = 0
        while (base + 1 < all.size && offsets[base + 1] <= startPos) base++
        var skip = (startPos - offsets[base]).coerceIn(0, all[base].length)
        if (skip >= all[base].length && base + 1 < all.size) {
            base++
            skip = 0
        }
        // 几何与计划由同一批局部量产出: 发布一次, 读方拿到的几何与本次计划同源
        publishGeometry(all, offsets, base, skip)

        val realStartPos = offsets[base] + skip
        val sub = if (skip >= all[base].length) {
            // 起点在末段末尾或更后: 余下段表为空 (不再产出首段为空的计划)
            emptyList()
        } else {
            all.subList(base, all.size).toMutableList()
                .also { if (skip > 0) it[0] = it[0].substring(skip) }
        }
        if (!fromLastSpeakable || sub.isEmpty()) {
            return ReadAloudChapterPlan(sub, chapterPosition = realStartPos)
        }
        val index = sub.indexOfLast { !it.matches(AppPattern.notReadAloudRegex) }
        if (index < 0) {
            return ReadAloudChapterPlan(emptyList(), chapterPosition = realStartPos)
        }
        val chapterPos = offsets.getOrNull(base + index)
            ?: sub.take(index).sumOf { it.length + 1 }
        return ReadAloudChapterPlan(
            paragraphs = sub,
            paragraphIndex = index,
            paragraphOffset = 0,
            chapterPosition = chapterPos,
        )
    }

    private fun publishGeometry(
        paragraphs: List<String>,
        offsets: IntArray,
        paragraphBase: Int,
        firstParagraphSkip: Int,
    ) {
        geometry.value = Geometry(paragraphs, offsets, paragraphBase, firstParagraphSkip)
    }
}
