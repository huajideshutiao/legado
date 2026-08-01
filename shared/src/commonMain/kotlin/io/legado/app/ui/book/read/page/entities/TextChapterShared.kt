package io.legado.app.ui.book.read.page.entities

import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.fastBinarySearchBy
import kotlin.math.abs
import kotlin.math.min

/**
 * commonMain 版章节分页能力载体（对照 app 端 TextChapter 的分页/定位方法子集）。
 * app 端 TextChapter 依赖 Book/BookChapter/TextChapterLayout 无法整体下沉，
 * 本类仅承载 SimpleChapterLayout 排版产物 [pages] 与翻页所需的定位算法，算法照抄原版。
 */
class TextChapterShared(
    val chapterIndex: Int,
    val pages: List<TextPage>,
    /** 排版本章时段评计数是否已就绪并应用；false 时计数迟到需要重排。 */
    override val reviewCountApplied: Boolean = false,
    // 本章起效的替换规则，对照 app 端 TextChapter.effectiveReplaceRules，供 EffectiveReplaces 对话框读取
    val effectiveReplaceRules: List<ReplaceRule>? = null,
    /** 本章正文是否已移除重复标题 (对照 app 端 TextChapter.sameTitleRemoved, 供"去重"菜单切换) */
    val sameTitleRemoved: Boolean = false,
) : TextChapterContract {

    override val pageSize: Int get() = pages.size

    /** SimpleChapterLayout 同步排版，构造即完成（app 端异步排版时才为 false） */
    override var isCompleted = true

    val lastIndex: Int get() = pages.lastIndex

    override val lastReadLength: Int get() = getReadLength(lastIndex)

    override fun getPage(index: Int): TextPage? = pages.getOrNull(index)

    /** 是否最后一页（对照 app 端 TextChapter.isLastIndex） */
    override fun isLastIndex(index: Int): Boolean = isCompleted && index >= pages.size - 1

    /** 已读长度 = 页首字符的章节内偏移（对照 app 端 TextChapter.getReadLength） */
    override fun getReadLength(pageIndex: Int): Int {
        if (pageIndex < 0 || pages.isEmpty()) return 0
        val page = pages[min(pageIndex, lastIndex)]
        // 占位消息页无行，chapterPosition 取 first 行会越界，兜底 0
        return if (page.lineSize == 0) 0 else page.chapterPosition
    }

    /** 下一页位置，无下一页返回 -1（对照 app 端 TextChapter.getNextPageLength） */
    override fun getNextPageLength(length: Int): Int {
        val pageIndex = getPageIndexByCharIndex(length)
        if (pageIndex + 1 >= pageSize) return -1
        return getReadLength(pageIndex + 1)
    }

    /** 上一页位置，无上一页返回 -1（对照 app 端 TextChapter.getPrevPageLength） */
    override fun getPrevPageLength(length: Int): Int {
        val pageIndex = getPageIndexByCharIndex(length)
        if (pageIndex - 1 < 0) return -1
        return getReadLength(pageIndex - 1)
    }

    /** 根据章节内字符偏移反算所在页（对照 app 端 TextChapter.getPageIndexByCharIndex 二分实现） */
    override fun getPageIndexByCharIndex(charIndex: Int): Int {
        val pageSize = pages.size
        if (pageSize == 0) return -1
        val bIndex = pages.fastBinarySearchBy(charIndex, 0, pageSize) {
            if (it.lineSize == 0) 0 else it.chapterPosition
        }
        val index = abs(bIndex + 1) - 1
        // 判断是否已经排版到 charIndex，没有则返回 -1
        if (!isCompleted && index == pageSize - 1) {
            val page = pages[index]
            val pageEndPos = (if (page.lineSize == 0) 0 else page.chapterPosition) + page.charSize
            if (charIndex > pageEndPos) return -1
        }
        return index
    }

    /** 清除本章搜索高亮（照搬 app 端 TextChapter.clearSearchResult） */
    override fun clearSearchResult() {
        for (i in pages.indices) {
            val page = pages[i]
            page.searchResult.forEach {
                it.selected = false
                it.isSearchResult = false
            }
            page.searchResult.clear()
        }
    }
}
