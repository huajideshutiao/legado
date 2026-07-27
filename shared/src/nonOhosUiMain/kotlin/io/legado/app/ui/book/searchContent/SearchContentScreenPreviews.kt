package io.legado.app.ui.book.searchContent

import androidx.compose.runtime.Composable
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/** [SearchContentScreen] 的 @Preview (结果列表/搜索中/空态)。 */

private val noOpSearchContentActions = object : SearchContentUiActions {
    override fun onBack() {}
    override fun onQueryChange(text: String) {}
    override fun onSubmitSearch(query: String) {}
    override fun onToggleReplaceEnabled() {}
    override fun onStopSearch() {}
    override fun onOpenResult(item: SearchResult, index: Int) {}
    override fun onRequestFocusSearch() {}
    override fun setClearFocusHandler(handler: (() -> Unit)?) {}
    override fun clearFocus() {}
    override fun onConsumePendingScrollIndex() {}
}

private val previewSearchResults = listOf(
    SearchResult(
        resultCount = 3,
        resultCountWithinChapter = 0,
        resultText = "宇宙就是一座黑暗森林, 每个文明都是带枪的猎人, 像幽灵般潜行于林间。",
        chapterTitle = "第十二章 黑暗森林",
        query = "黑暗森林",
        chapterIndex = 11,
        queryIndexInResult = 8,
    ),
    SearchResult(
        resultCount = 3,
        resultCountWithinChapter = 1,
        resultText = "黑暗森林法则是罗辑对宇宙社会学两条公理的推演结果。",
        chapterTitle = "第十二章 黑暗森林",
        query = "黑暗森林",
        chapterIndex = 11,
        queryIndexInResult = 0,
    ),
    SearchResult(
        resultCount = 3,
        resultCountWithinChapter = 0,
        resultText = "他终于理解了黑暗森林打击的真正含义。",
        chapterTitle = "第二十八章 威慑纪元",
        query = "黑暗森林",
        chapterIndex = 27,
        queryIndexInResult = 7,
    ),
)

private fun previewState(
    results: List<SearchResult> = previewSearchResults,
    searching: Boolean = false,
    query: String = "黑暗森林",
) = SearchContentUiState(
    query = query,
    results = results,
    resultCount = results.size,
    searching = searching,
    durChapterIndex = 11,
    replaceEnabled = true,
    focusEpoch = 0,
    pendingScrollIndex = null,
)

@AppPreview
@Composable
fun SearchContentScreenPreview() = LegadoThemePreview {
    SearchContentScreen(
        state = previewState(),
        actions = noOpSearchContentActions,
    )
}

@AppPreview
@Composable
fun SearchContentScreenSearchingPreview() = LegadoThemePreview {
    SearchContentScreen(
        state = previewState(searching = true),
        actions = noOpSearchContentActions,
    )
}

@AppPreview
@Composable
fun SearchContentScreenEmptyPreview() = LegadoThemePreview {
    SearchContentScreen(
        state = previewState(results = emptyList(), query = ""),
        actions = noOpSearchContentActions,
    )
}

@AppPreview
@Composable
fun SearchContentScreenDarkPreview() = LegadoThemePreview(dark = true) {
    SearchContentScreen(
        state = previewState(),
        actions = noOpSearchContentActions,
    )
}
