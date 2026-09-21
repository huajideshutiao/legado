package io.legado.app.model

import io.legado.app.data.entities.Book

/**
 * ReadBook.initData/loadBook 共用的纯状态判定。
 *
 * 从 Android `ReadBook.initData` 原条件原样抽取，避免 app 与 shared 分别维护切书、进度归一
 * 和章节列表失效规则。这里只计算，不执行回调、数据库、TextFile 或平台资源副作用。
 */
data class ReadBookInitState(
    val isDifferentBook: Boolean,
    val shouldDropChapterList: Boolean,
    val shouldResetProgress: Boolean,
    val chapterIndex: Int,
    val chapterPosition: Int,
)

fun calculateReadBookInitState(
    previousBookUrl: String?,
    firstChapterBookUrl: String?,
    currentChapterIndex: Int,
    incomingBook: Book,
): ReadBookInitState {
    val isDifferentBook = previousBookUrl != incomingBook.bookUrl
    return ReadBookInitState(
        isDifferentBook = isDifferentBook,
        shouldDropChapterList = firstChapterBookUrl != incomingBook.bookUrl,
        shouldResetProgress = isDifferentBook || currentChapterIndex != incomingBook.durChapterIndex,
        chapterIndex = incomingBook.durChapterIndex,
        chapterPosition = incomingBook.durChapterPos * if (incomingBook.durChapterPos < 0) -1 else 1,
    )
}
