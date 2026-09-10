package io.legado.app.ui.book.read.page.entities

import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.provider.TextMeasurer
import kotlin.math.min

/**
 * TextChapterShared 扩展: 朗读取数用的段落表与朗读文本流。
 * 朗读服务 (BaseReadAloudService/HttpReadAloudService) 通过本扩展取段落与朗读文本,
 * 三者口径 (getNeedReadAloud 的文本流 / paragraphs 的段落集合 / getParagraphNum 的返回值)
 * 必须自洽: 段号 = 文本流按 `\n` 切分后的下标 + 1。
 */

/** 章节标题 (取首页标题, 对照 app 端 TextChapter.title) */
val TextChapterShared.title: String
    get() = pages.firstOrNull()?.title ?: ""

/**
 * 章节段落列表 (对照 app 端 TextChapter.paragraphs)。
 *
 * 按 [TextLine.isParagraphEnd] 断段并跨页续接。不能按 paragraphNum 分组: 排版器给标题行
 * 的 paragraphNum 是 0 (逻辑段号口径, 0=标题/1..N=正文, 段评计数按此对齐), 按 `> 0` 过滤
 * 会把标题从段落表里抹掉, 而 [getNeedReadAloud] 的文本流含标题, 两者段号就此错位。
 */
val TextChapterShared.paragraphs: ArrayList<TextParagraph>
    get() = groupParagraphs(pages)

/** 分页段落列表 (对照 app 端 TextChapter.pageParagraphs): 逐页断段, 段号按全章重排 */
val TextChapterShared.pageParagraphs: ArrayList<TextParagraph>
    get() {
        val paragraphList = arrayListOf<TextParagraph>()
        for (i in pages.indices) {
            paragraphList.addAll(pageParagraphsOf(i))
        }
        for (i in paragraphList.indices) {
            paragraphList[i].num = i + 1
        }
        return paragraphList
    }

/** 单页段落列表 (对照 app 端 TextPage.paragraphs, 区别是标题也算一段) */
fun TextChapterShared.pageParagraphsOf(pageIndex: Int): List<TextParagraph> {
    val page = pages.getOrNull(pageIndex) ?: return emptyList()
    return groupParagraphs(listOf(page))
}

/**
 * 按段末行断段, 段号 = 列表下标 + 1。
 *
 * 与排版期"每段文本后追加一个 `\n`"严格对偶: 段号即页文本流按 `\n` 切分后的下标 + 1,
 * 故 [getParagraphNum] 的返回值可直接当 contentList 下标用 (减 1)。
 */
private fun groupParagraphs(pages: List<TextPage>): ArrayList<TextParagraph> {
    val paragraphList = arrayListOf<TextParagraph>()
    var current: TextParagraph? = null
    for (page in pages) {
        for (line in page.lines) {
            var paragraph = current
            if (paragraph == null) {
                paragraph = TextParagraph(paragraphList.size + 1)
                paragraphList.add(paragraph)
                current = paragraph
            }
            paragraph.textLines.add(line)
            if (line.isParagraphEnd) current = null
        }
    }
    return paragraphList
}

/**
 * 获取需要朗读的文本 (对照 app 端 TextChapter.getNeedReadAloud)
 * @param pageIndex 起始页
 * @param pageSplit 是否分页
 * @param startPos 从当前页什么地方开始朗读
 * @param pageEndIndex 结束页
 */
fun TextChapterShared.getNeedReadAloud(
    pageIndex: Int,
    pageSplit: Boolean,
    startPos: Int,
    pageEndIndex: Int = pages.lastIndex
): String {
    val stringBuilder = StringBuilder()
    if (pages.isNotEmpty()) {
        for (index in pageIndex..min(pageEndIndex, pages.lastIndex)) {
            stringBuilder.append(pages[index].text)
            if (pageSplit && !stringBuilder.endsWith("\n")) {
                stringBuilder.append("\n")
            }
        }
    }
    return stringBuilder.substring(startPos).toString()
}

/**
 * 根据章节字符位置获取段落号 (对照 app 端 TextChapter.getParagraphNum)
 */
fun TextChapterShared.getParagraphNum(
    position: Int,
    pageSplit: Boolean,
): Int {
    val paragraphList = getParagraphs(pageSplit)
    paragraphList.forEach { paragraph ->
        if (position in paragraph.chapterIndices) {
            return paragraph.num
        }
    }
    return -1
}

/**
 * 获取段落列表 (对照 app 端 TextChapter.getParagraphs)
 * TextChapterShared 同步排版, isCompleted 恒为 true, 直接返回对应列表
 */
fun TextChapterShared.getParagraphs(pageSplit: Boolean): List<TextParagraph> {
    return if (pageSplit) pageParagraphs else paragraphs
}

/** 最后一个段落的章节位置 (对照 app 端 TextChapter.getLastParagraphPosition) */
fun TextChapterShared.getLastParagraphPosition(): Int {
    return pageParagraphs.last().chapterPosition
}

/**
 * 尝试就地为 [TextChapterContract] 打补丁添加/更新段评气泡（Patch 快速通道）。
 *
 * 逻辑：
 * 1. reviewCountMap 无 >0 计数时，直接标记 reviewCountApplied 并返回 true。
 * 2. 预检：拿不到可视区宽度（无可靠行宽上界）、末行已 exceed 回压、末行剩余空间装不下气泡、
 *    page.text 与行文本对不上，任一命中就返回 false，由调用方回退到后台重排。
 * 3. 落地：整章单次顺移。补丁行把占位符插进 page.text 并追加到 line.text，其后各行的
 *    chapterPosition / pagePosition 同步顺移，字符账本与「排版期就带占位符」等价
 *    （朗读定位 / containPos / 搜索选区 / 进度都按这套账本算）。
 *
 * @return true 表示成功就地 Patch，false 表示存在换行折行风险需触发后台重排。
 */
fun TextChapterContract.tryPatchReviewCounts(
    reviewCountMap: Map<Int, Int>,
    reviewChar: String = "▨",
    measurer: TextMeasurer? = null,
    titleMeasurer: TextMeasurer? = null,
    visibleWidth: Int = 0,
    paddingLeft: Int = 0,
    paddingRight: Int = 0,
    viewWidth: Int = 0,
    doublePage: Boolean = false,
): Boolean {
    val validCounts = reviewCountMap.filter { it.value > 0 }
    if (validCounts.isEmpty()) {
        if (this is TextChapterShared) {
            this.reviewCountApplied = true
        }
        return true
    }
    // 没有可视区宽度就拿不到可靠的行宽上界，无从判断气泡会不会挤出可视区
    if (visibleWidth <= 0) return false

    val pageCount = this.pageSize
    if (pageCount == 0) return false
    val allPages = (0 until pageCount).mapNotNull { getPage(it) }
    if (allPages.isEmpty()) return false

    // 收集所有段落末行 (0=标题, 1..N=正文段落)
    val targetLines = mutableMapOf<Int, TextLine>()
    for (page in allPages) {
        for (line in page.lines) {
            if (line.isTitle && line.isParagraphEnd) {
                targetLines[0] = line
            } else if (line.paragraphNum > 0 && line.isParagraphEnd) {
                targetLines[line.paragraphNum] = line
            }
        }
    }
    // 标题兜底：若标题未标记 isParagraphEnd，取首页最后一行标题
    if (0 in validCounts && targetLines[0] == null) {
        allPages.firstOrNull()?.lines?.lastOrNull { it.isTitle }?.let {
            targetLines[0] = it
        }
    }

    // 第一遍扫描：空间与换行折行安全性预检，顺带算出气泡宽度留给落地阶段复用
    val pending = mutableMapOf<Int, PendingReviewBubble>()
    for ((pIndex, count) in validCounts) {
        val line = targetLines[pIndex] ?: continue
        val page = line.textPage

        // 已有 ReviewColumn 仅需更新 count，无需占用新空间
        val existing = line.columns.firstOrNull { it is ReviewColumn } as? ReviewColumn
        if (existing != null) {
            pending[pIndex] = PendingReviewBubble(line, count, 0f, existing)
            continue
        }

        // 若之前排版已越界压缩，则不能再塞气泡
        if (line.exceed) {
            return false
        }
        // page.text 里对不上本行文本时，插入占位符会打乱整页字符账本
        if (!page.text.regionMatches(line.pagePosition, line.text, 0, line.text.length)) {
            return false
        }

        val isTitle = line.isTitle
        val bubbleWidth = when {
            isTitle && titleMeasurer != null -> titleMeasurer.measureWidth(reviewChar)
            !isTitle && measurer != null -> measurer.measureWidth(reviewChar)
            page.contentPaintTextHeight > 0f -> page.contentPaintTextHeight
            else -> line.height.coerceAtLeast(20f)
        }

        val lineMaxEnd = if (doublePage && !line.isLeftLine) {
            if (viewWidth > 0) (viewWidth - paddingRight).toFloat()
            else (paddingLeft + visibleWidth * 2).toFloat()
        } else {
            (paddingLeft + visibleWidth).toFloat()
        }

        if (line.lineEnd + bubbleWidth > lineMaxEnd) {
            // 末行剩余空间不足以容纳气泡，添加会导致换行折行，放弃就地 Patch
            return false
        }
        pending[pIndex] = PendingReviewBubble(line, count, bubbleWidth, null)
    }

    // 第二遍扫描：按页序 / 行序单次遍历整章，先顺移位置账本再插入占位符
    var chapterDelta = 0
    for (page in allPages) {
        var pageDelta = 0
        for (line in page.lines) {
            line.chapterPosition += chapterDelta
            line.pagePosition += pageDelta
            val paragraphIndex = if (line.isTitle) 0 else line.paragraphNum
            val bubble = pending[paragraphIndex]
            if (bubble == null || bubble.line !== line) continue
            val existing = bubble.existing
            if (existing != null) {
                existing.count = bubble.count
                continue
            }
            val lineEnd = line.lineEnd
            line.addColumn(
                ReviewColumn(
                    start = lineEnd,
                    end = lineEnd + bubble.bubbleWidth,
                    paragraphIndex = paragraphIndex,
                    count = bubble.count,
                )
            )
            // 占位符插在本行文本末尾（段末换行符之前），与排版期拼进段落文本的位置一致
            val insertAt = line.pagePosition + line.text.length
            page.text = page.text.substring(0, insertAt) + reviewChar + page.text.substring(insertAt)
            line.text += reviewChar
            chapterDelta += reviewChar.length
            pageDelta += reviewChar.length
        }
    }

    if (this is TextChapterShared) {
        this.reviewCountApplied = true
    }
    return true
}

/** [tryPatchReviewCounts] 预检产物：待落地的气泡，[existing] 非空表示只改计数不占新空间。 */
private class PendingReviewBubble(
    val line: TextLine,
    val count: Int,
    val bubbleWidth: Float,
    val existing: ReviewColumn?,
)

