package io.legado.app.ui.root

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.book.searchContent.SearchResult
import kotlinx.serialization.Serializable

/**
 * 跨平台路由结果 key 常量 (替代各 Activity companion object 内的 RESULT_* 常量)。
 *
 * 使用方式:
 * - 调用方: `navigator.push(route, resultKey = RouteResults.BOOK_SOURCE_EDIT)`
 * - 监听方: `navigator.resultsFor(entry.id).filter { it.key == RouteResults.BOOK_SOURCE_EDIT }.collect { result -> val payload = result.payload as RouteResultPayload.BookSourceEdit }`
 * - 返回方: `navigator.pop(payload = RouteResultPayload.BookSourceEdit(origin = url))`
 */
object RouteResults {
    // 通用结果码 (对齐 Activity RESULT_OK / RESULT_DELETED)
    const val OK = "ok"
    const val DELETED = "deleted" // 对齐 ReadBookActivity.RESULT_DELETED = 100

    // 书源编辑 (对齐 BookSourceEditActivity 回传 origin)
    const val BOOK_SOURCE_EDIT = "book_source_edit"

    // 书籍信息 (对齐 BookInfoActivity 回传 RESULT_DELETED)
    const val BOOK_INFO = "book_info"

    // 目录页 (对齐 TocActivityResult 回传 chapterIndex + chapterPos)
    const val TOC = "toc"

    // 正文搜索 (对齐 SearchContentActivity 回传 key + index + SearchResult)
    const val SEARCH_CONTENT = "search_content"

    // 替换规则 (对齐 ReplaceRuleActivity 回传 RESULT_OK)
    const val REPLACE_RULE = "replace_rule"

    // 替换规则编辑 (对齐 ReplaceEditActivity 回传 RESULT_OK)
    const val REPLACE_EDIT = "replace_edit"

    // 搜索结果屏蔽规则 (对齐 SourceFilterRuleActivity 回传 RESULT_OK)
    const val SOURCE_FILTER_RULE = "source_filter_rule"

    // 书籍信息编辑 (对齐 BookInfoEditActivity 回传 RESULT_OK)
    const val BOOK_INFO_EDIT = "book_info_edit"

    // 整书换源 (对齐 ChangeBookSourceDialog.CallBack.changeTo 回传 source + book + toc)
    const val CHANGE_SOURCE = "change_source"

    // 章节换源 (对齐 ChangeChapterSourceDialog.CallBack.replaceContent 回传 content)
    const val CHANGE_CHAPTER_SOURCE = "change_chapter_source"

    // 阅读器返回 (对齐 ReadBookActivity/AudioPlay/VideoPlay/MangaReader/ReadRss 返回)
    const val READER = "reader"

    // Overlay 结果 key: 分组选择对话框 (key="group_select" 的 AppOverlay.Dialog 关闭时回传 groupId)
    const val OVERLAY_GROUP_SELECT = "group_select"

    // Overlay 结果 key: 换封面对话框 (key="change_cover" 的 AppOverlay.Dialog 关闭时回传 coverUrl)
    const val OVERLAY_CHANGE_COVER = "change_cover"

    // Overlay 结果 key: 书源选择对话框 (key="source_picker" 的 AppOverlay.Dialog 关闭时回传选中的 BookSource)
    const val OVERLAY_SOURCE_PICKER = "source_picker"
}

/**
 * 类型化路由结果负载 (替代纯 String 结果)。
 *
 * 每种 Activity Result 对应一个 sealed 子类型, 携带结构化数据。
 * 消费方通过 `result.payload as? RouteResultPayload.Toc` 安全转型取值。
 */
@Serializable
sealed interface RouteResultPayload {

    /** 无结果 (默认 pop) */
    @Serializable
    data object None : RouteResultPayload

    /** 通用 OK (对齐 Activity RESULT_OK, 无附加数据) */
    @Serializable
    data object Ok : RouteResultPayload

    /** 删除 (对齐 ReadBookActivity.RESULT_DELETED) */
    @Serializable
    data object Deleted : RouteResultPayload

    /** 书源编辑回传 origin (对齐 BookSourceEditActivity) */
    @Serializable
    data class BookSourceEdit(val origin: String) : RouteResultPayload

    /** 目录页回传章节定位 (对齐 TocActivityResult Triple<Int, Int, Boolean>) */
    @Serializable
    data class Toc(
        val chapterIndex: Int,
        val chapterPos: Int,
        val chapterChanged: Boolean,
    ) : RouteResultPayload

    /** 正文搜索回传 (对齐 SearchContentActivity) */
    @Serializable
    data class SearchContent(
        val searchWord: String?,
        val searchResultIndex: Int,
        val searchResults: List<SearchResult>,
    ) : RouteResultPayload

    /** 整书换源回传 (对齐 ChangeBookSourceDialog.CallBack.changeTo(source, book, toc)) */
    @Serializable
    data class ChangeSource(
        val source: BookSource,
        val book: Book,
        val toc: List<BookChapter>,
    ) : RouteResultPayload

    /** 书籍信息编辑回传编辑后的 Book (对齐 BookInfoEditActivity.saveData 末尾 IntentData.book + finish) */
    @Serializable
    data class BookEdited(
        val book: Book,
    ) : RouteResultPayload

    /** 章节换源回传 (对齐 ChangeChapterSourceDialog.CallBack.replaceContent(content)) */
    @Serializable
    data class ChangeChapterContent(
        val content: String,
    ) : RouteResultPayload

    /** 分组选择对话框回传 (对齐 GroupSelectDialog.CallBack.upGroup, groupId 为位掩码) */
    @Serializable
    data class GroupSelect(
        val groupId: Long,
    ) : RouteResultPayload

    /** 换封面对话框回传 (对齐 ChangeCoverDialog.CallBack.coverChangeTo, coverUrl 可能为 "use_default_cover") */
    @Serializable
    data class ChangeCover(
        val coverUrl: String,
    ) : RouteResultPayload

    /** 书源选择对话框回传 (对齐 SourcePickerDialog.Callback.sourceOnClick) */
    @Serializable
    data class SourcePicker(
        val source: BookSource,
    ) : RouteResultPayload
}
