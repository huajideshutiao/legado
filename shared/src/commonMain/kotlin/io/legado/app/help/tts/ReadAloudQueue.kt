package io.legado.app.help.tts

import io.legado.app.constant.AppPattern

/**
 * 朗读播放队列的纯状态机: 段落列表 + 位置推进计算,从 ReadAloud 服务类平移。
 * 页跟踪(pageIndex/TextChapter)与章节切换仍由 Service 驱动,这里只做段级纯计算。
 */
class ReadAloudQueue {

    /** 待朗读段落列表(按 \n 分段并过滤空段,非句读切分) */
    var contentList: List<String> = emptyList()

    /** 当前朗读段落下标 */
    var nowSpeak: Int = 0

    /** 已朗读进度(章节内字符位置) */
    var readAloudNumber: Int = 0

    /** 当前段落内的起始偏移 */
    var paragraphStartPos: Int = 0

    /**
     * 推进到下一段。调用方保证 nowSpeak < contentList.lastIndex。
     */
    fun stepNext() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        nowSpeak++
    }

    /**
     * 推进一段;已是最后一段时不动 nowSpeak,返回 false 由调用方切章。
     */
    fun stepNextOrEnd(): Boolean {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        return if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            true
        } else {
            false
        }
    }

    /**
     * 连续推进,跳过空白/纯标点段;越过末尾返回 false 由调用方切章。
     */
    fun advanceToNextSpeakable(): Boolean {
        do {
            readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            if (nowSpeak >= contentList.size) {
                return false
            }
        } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        return true
    }

    /**
     * 回退到上一个非空白段。调用方保证 nowSpeak > 0。
     */
    fun retreatToPrevSpeakable() {
        do {
            nowSpeak--
            readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
            paragraphStartPos = 0
        } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
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
