package io.legado.app.ui.book.read.page.entities

import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.fastBinarySearchBy
import kotlin.math.abs
import kotlin.math.min

/**
 * 章节排版产物载体：承载 [pages] 与翻页 / 阅读位置的定位算法。
 */
class TextChapterShared(
    val chapterIndex: Int,
    val pages: List<TextPage>,
    /** 排版本章时段评计数是否已就绪并应用；false 时计数迟到需要重排。 */
    var reviewCountApplied: Boolean = false,
    // 本章起效的替换规则，供 EffectiveReplaces 对话框读取
    val effectiveReplaceRules: List<ReplaceRule>? = null,
    /** 本章正文是否已移除重复标题 (供"去重"菜单切换) */
    val sameTitleRemoved: Boolean = false,
) {

    val pageSize: Int get() = pages.size

    val lastIndex: Int get() = pages.lastIndex

    /** 末页页首在章节内的字符偏移 */
    val lastReadLength: Int get() = getReadLength(lastIndex)

    fun getPage(index: Int): TextPage? = pages.getOrNull(index)

    /** 是否最后一页 */
    fun isLastIndex(index: Int): Boolean = index >= pages.size - 1

    /** 已读长度 = 页首字符的章节内偏移 */
    fun getReadLength(pageIndex: Int): Int {
        if (pageIndex < 0) return 0
        return pages[min(pageIndex, lastIndex)].chapterPosition
    }

    /** 下一页位置，无下一页返回 -1 */
    fun getNextPageLength(length: Int): Int {
        val pageIndex = getPageIndexByCharIndex(length)
        if (pageIndex + 1 >= pageSize) return -1
        return getReadLength(pageIndex + 1)
    }

    /** 上一页位置，无上一页返回 -1 */
    fun getPrevPageLength(length: Int): Int {
        val pageIndex = getPageIndexByCharIndex(length)
        if (pageIndex - 1 < 0) return -1
        return getReadLength(pageIndex - 1)
    }

    /** 根据章节内字符偏移反算所在页 */
    fun getPageIndexByCharIndex(charIndex: Int): Int {
        val pageSize = pages.size
        if (pageSize == 0) return -1
        val bIndex = pages.fastBinarySearchBy(charIndex, 0, pageSize) { it.chapterPosition }
        return abs(bIndex + 1) - 1
    }

}
