package io.legado.app.ui.book.searchContent

import kotlinx.serialization.Serializable

/**
 * 书内全文搜索结果数据类 (KMP 共享)。
 *
 * 原 app 端 `SearchResult` 下沉至 shared commonMain 供 SearchContentScreen
 * 及 app 端 Activity/ViewModel 共用。纯数据类, 无 Android 依赖。
 */
@Serializable
data class SearchResult(
    val resultCount: Int = 0,
    val resultCountWithinChapter: Int = 0,
    val resultText: String = "",
    val chapterTitle: String = "",
    val query: String = "",
    val pageSize: Int = 0,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val queryIndexInResult: Int = 0,
    val queryIndexInChapter: Int = 0
)
