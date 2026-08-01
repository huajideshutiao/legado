package io.legado.app.ui.book.read.page.entities

import kotlin.math.min

/**
 * TextChapterShared 扩展: 复刻 app 端 TextChapter 的朗读相关方法。
 * app 端 TextChapter 依赖 Book/BookChapter/TextChapterLayout 无法整体下沉,
 * 朗读服务 (BaseReadAloudService/HttpReadAloudService) 通过本扩展取段落与朗读文本。
 */

/** 章节标题 (取首页标题, 对照 app 端 TextChapter.title) */
val TextChapterShared.title: String
    get() = pages.firstOrNull()?.title ?: ""

/** 章节段落列表 (对照 app 端 TextChapter.paragraphs) */
val TextChapterShared.paragraphs: ArrayList<TextParagraph>
    get() {
        val paragraphList = arrayListOf<TextParagraph>()
        for (i in pages.indices) {
            val lines = pages[i].lines
            for (a in lines.indices) {
                val line = lines[a]
                if (line.paragraphNum <= 0) continue
                if (paragraphList.lastIndex < line.paragraphNum - 1) {
                    paragraphList.add(TextParagraph(line.paragraphNum))
                }
                paragraphList[line.paragraphNum - 1].textLines.add(line)
            }
        }
        return paragraphList
    }

/** 分页段落列表 (对照 app 端 TextChapter.pageParagraphs) */
val TextChapterShared.pageParagraphs: ArrayList<TextParagraph>
    get() {
        val paragraphList = arrayListOf<TextParagraph>()
        for (i in pages.indices) {
            paragraphList.addAll(pages[i].paragraphs)
        }
        for (i in paragraphList.indices) {
            paragraphList[i].num = i + 1
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
