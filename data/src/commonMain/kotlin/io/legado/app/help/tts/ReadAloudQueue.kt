package io.legado.app.help.tts

import io.legado.app.constant.AppPattern
import kotlinx.atomicfu.atomic

/**
 * 朗读播放队列的纯状态机: 段落列表 + 位置推进计算,从 ReadAloud 服务类平移。
 * 页跟踪(pageIndex/TextChapter)与章节切换仍由 Service 驱动,这里只做段级纯计算。
 *
 * 线程: 引擎回调线程(系统 TTS 的 binder 线程 / 播放器回调线程)与调用方线程都会读推进状态。
 * 四个字段互相定义 (readAloudNumber 按前一段长度 + 1 个换行累加), 分字段读写会取到跨版本的
 * 混合值, 故整份存为不可变 [Snapshot] 并原子发布: 读方一次 [snapshot] 取值 (无锁、无分配),
 * 写方在 CAS 环内由当次读到的快照生成新快照。每个推进方法返回自己发布的那份快照, 调用方
 * 无需二次读队列 (二次读会跨版本)。
 */
class ReadAloudQueue {

    /**
     * 队列快照(不可变)。
     *
     * @param contentList 待朗读段落列表(按 \n 分段并过滤空段,非句读切分)
     * @param nowSpeak 当前朗读段落下标
     * @param readAloudNumber 已朗读进度(章节内字符位置)
     * @param paragraphStartPos 当前段落内的起始偏移
     */
    data class Snapshot(
        val contentList: List<String> = emptyList(),
        val nowSpeak: Int = 0,
        val readAloudNumber: Int = 0,
        val paragraphStartPos: Int = 0,
    ) {

        /** 当前段文本; 空表或下标越出段表时为 null (此刻章内已无可朗读段)。 */
        fun currentParagraph(): String? = contentList.getOrNull(nowSpeak)
    }

    private val state = atomic(Snapshot())

    /** 零锁读: 一次取值拿到自洽的段落表与游标, 不参与任何锁或 CAS。 */
    val snapshot: Snapshot get() = state.value

    /**
     * 整份发布新队列。
     *
     * 下标钳制在此收口: nowSpeak 落在段表范围内 (空表为 0), paragraphStartPos 落在当前段
     * 长度内 —— 起点可能落在章末, 由各调用点分别补判据会漏。
     */
    fun reset(
        contentList: List<String>,
        nowSpeak: Int = 0,
        readAloudNumber: Int = 0,
        paragraphStartPos: Int = 0,
    ) {
        val index = nowSpeak.coerceIn(0, contentList.lastIndex.coerceAtLeast(0))
        state.value = Snapshot(
            contentList = contentList,
            nowSpeak = index,
            readAloudNumber = readAloudNumber.coerceAtLeast(0),
            paragraphStartPos = paragraphStartPos.coerceIn(
                0,
                contentList.getOrNull(index)?.length ?: 0,
            ),
        )
    }

    /**
     * 推进一段;已是最后一段时不动 nowSpeak,返回 false 由调用方切章。
     */
    fun stepNextOrEnd(): Boolean = publishResult { cur ->
        val next = cur.advance()
        next to (next.nowSpeak != cur.nowSpeak)
    }

    /**
     * 找当前段起的第一个可朗读段 (跳过空白/纯标点段) 并整份发布, 返回发布后的快照。
     *
     * 章内已无可朗读段时返回快照的 nowSpeak 越过段表末尾 (currentParagraph 为 null),
     * 由调用方切章。
     */
    fun seekSpeakable(): Snapshot = publish { cur ->
        var next = cur
        while (next.currentParagraph()?.matches(AppPattern.notReadAloudRegex) == true) {
            next = next.skipOne()
        }
        next
    }

    /**
     * 回退到上一个非空白段并整份发布。
     *
     * @return 是否发生回退; false = 已在首段 (调用方按跨章处理)
     */
    fun retreatToPrevSpeakable(): Boolean = publishResult { cur ->
        val next = cur.retreat()
        next to (next !== cur)
    }

    // region 纯快照变换: 入参出参都是快照, CAS 失败重跑结果一致

    /**
     * 推进一段: 累加本段已读长度后指针前进; 已在末段只累加长度、指针不动 (由调用方切章)。
     * 下标越出段表时不动 (空表/已越过末尾)。
     */
    private fun Snapshot.advance(): Snapshot {
        if (nowSpeak !in contentList.indices) return this
        return copy(
            nowSpeak = if (nowSpeak < contentList.lastIndex) nowSpeak + 1 else nowSpeak,
            readAloudNumber = readAloudNumber + paragraphStep(),
            paragraphStartPos = 0,
        )
    }

    /** 跳过当前段 (可越过段表末尾), 只用于扫描可朗读段。 */
    private fun Snapshot.skipOne(): Snapshot = copy(
        nowSpeak = nowSpeak + 1,
        readAloudNumber = readAloudNumber + paragraphStep(),
        paragraphStartPos = 0,
    )

    /** 回退一格, 连续跳过非空白段; 停在首段或第一个可朗读段。 */
    private fun Snapshot.retreat(): Snapshot {
        if (nowSpeak !in contentList.indices || nowSpeak <= 0) return this
        var next = this
        do {
            val target = next.nowSpeak - 1
            next = next.copy(
                nowSpeak = target,
                readAloudNumber = next.readAloudNumber -
                    next.contentList[target].length - 1 - next.paragraphStartPos,
                paragraphStartPos = 0,
            )
        } while (next.nowSpeak > 0 && next.contentList[next.nowSpeak].matches(AppPattern.notReadAloudRegex))
        return next
    }

    /** 本段推进要累加的字符数 (段长 + 1 个换行 - 段内已读偏移)。 */
    private fun Snapshot.paragraphStep(): Int =
        (currentParagraph()?.length ?: 0) + 1 - paragraphStartPos

    // endregion

    /**
     * CAS 环: 由当次读到的快照生成新快照并原子发布, 返回真正发布的那份。
     * [transform] 可能因 CAS 失败被重跑, 必须是纯函数; 返回同一实例表示本次无需变更。
     */
    private inline fun publish(transform: (Snapshot) -> Snapshot): Snapshot {
        while (true) {
            val cur = state.value
            val next = transform(cur)
            if (next === cur) return cur
            if (state.compareAndSet(cur, next)) return next
        }
    }

    /** 同 [publish], 额外返回一个取自当次计算的结果。 */
    private inline fun publishResult(transform: (Snapshot) -> Pair<Snapshot, Boolean>): Boolean {
        while (true) {
            val cur = state.value
            val (next, result) = transform(cur)
            if (next === cur) return result
            if (state.compareAndSet(cur, next)) return result
        }
    }

    companion object {

        /** 按段落(\n)切分朗读文本并过滤空段 */
        fun splitParagraphs(text: String): List<String> {
            return text.split("\n").filter { it.isNotEmpty() }
        }

        /** [splitParagraphs] 的惰性版本,预下载 take(n) 场景用 */
        fun splitParagraphsSequence(text: String): Sequence<String> {
            return text.splitToSequence("\n").filter { it.isNotEmpty() }
        }
    }
}
