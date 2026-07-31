package io.legado.app.ui.book.toc

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [TocScreen] / [TocDrawerContent] 的 @Preview。
 *
 * 假数据: [Book] / [BookChapter] / [Bookmark] 用纯内存对象构造,
 * [TocUiActions] 用 no-op object。
 */

private val previewBook = Book(
    name = "三体",
    author = "刘慈欣",
    bookUrl = "preview://book",
    origin = BookType.localTag,
    durChapterTitle = "黑暗森林",
    durChapterIndex = 2,
    totalChapterNum = 6,
)

private val previewChapters = listOf(
    BookChapter(url = "ch://0", title = "第一卷 地球往事", isVolume = true, index = 0, bookUrl = "preview://book"),
    BookChapter(url = "ch://1", title = "第一章 科学边界", index = 1, bookUrl = "preview://book", wordCount = "3200"),
    BookChapter(url = "ch://2", title = "第二章 疯狂年代", index = 2, bookUrl = "preview://book", wordCount = "4500", isVip = true, isPay = false),
    BookChapter(url = "ch://3", title = "第三章 红岸之一", index = 3, bookUrl = "preview://book", wordCount = "3800"),
    BookChapter(url = "ch://4", title = "第二卷 黑暗森林", isVolume = true, index = 4, bookUrl = "preview://book"),
    BookChapter(url = "ch://5", title = "第四章 面壁者", index = 5, bookUrl = "preview://book", wordCount = "5100"),
)

private val previewBookmarks = listOf(
    Bookmark(
        time = 1_700_000_000_000,
        bookName = "三体",
        bookAuthor = "刘慈欣",
        chapterIndex = 1,
        chapterPos = 0,
        chapterName = "第一章 科学边界",
        bookText = "这是书签选中的原文内容片段...",
        content = "读者书签笔记内容",
    ),
    Bookmark(
        time = 1_700_000_100_000,
        bookName = "三体",
        bookAuthor = "刘慈欣",
        chapterIndex = 2,
        chapterPos = 100,
        chapterName = "第二章 疯狂年代",
        bookText = "另一个书签的原文片段...",
        content = "",
    ),
)

private val previewState = TocUiState(
    book = previewBook,
    durChapterIndex = 2,
    searching = false,
    searchKey = "",
    chapters = previewChapters,
    collapsedVolumes = emptySet(),
    displayTitleMap = emptyMap(),
    cacheFileNames = emptySet(),
    useReplace = false,
    countWords = true,
    chapterScroll = TocScrollCmd(),
    bookmarks = previewBookmarks,
    bookmarkScroll = TocScrollCmd(),
    isLocalBook = false,
)

private val previewStateSearching = previewState.copy(searching = true, searchKey = "科学")

private val previewStateCollapsed = previewState.copy(collapsedVolumes = setOf(4))

/** no-op actions */
private object NoOpTocActions : TocUiActions {
    override fun onBack() {}
    override fun setSearchMode(active: Boolean) {}
    override fun setQuery(query: String) {}
    override fun toggleVolume(volume: BookChapter) {}
    override fun openChapter(chapter: BookChapter) {}
    override fun reverseChapterList() {}
    override fun toggleUseReplace() {}
    override fun toggleCountWords() {}
    override fun toggleSplitLongChapter() {}
    override fun showTocRegexDialog() {}
    override fun exportBookmark() {}
    override fun exportBookmarkMd() {}
    override fun showLog() {}
    override fun openBookmark(bookmark: Bookmark) {}
    override fun editBookmark(bookmark: Bookmark, pos: Int) {}
    override fun onChapterLongClick(title: String) {}
}

@Preview
@Composable
fun TocScreenPreview() = LegadoThemePreview {
    TocScreen(state = previewState, actions = NoOpTocActions)
}

@Preview
@Composable
fun TocScreenSearchingPreview() = LegadoThemePreview {
    TocScreen(state = previewStateSearching, actions = NoOpTocActions)
}

@Preview
@Composable
fun TocScreenCollapsedPreview() = LegadoThemePreview {
    TocScreen(state = previewStateCollapsed, actions = NoOpTocActions)
}

@Preview
@Composable
fun TocScreenDarkPreview() = LegadoThemePreview(dark = true) {
    TocScreen(state = previewState, actions = NoOpTocActions)
}

// ---- TocDrawerContent ----

@Preview
@Composable
fun TocDrawerContentPreview() = LegadoThemePreview {
    TocDrawerContent(
        chapterList = previewChapters,
        currentIndex = 2,
        onChapterClick = {},
    )
}

@Preview
@Composable
fun TocDrawerContentDarkPreview() = LegadoThemePreview(dark = true) {
    TocDrawerContent(
        chapterList = previewChapters,
        currentIndex = 2,
        onChapterClick = {},
    )
}
