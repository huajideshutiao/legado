package io.legado.app.ui.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [BookshelfScreen] 内部 Composable 的 @Preview (BookshelfTopBar / GroupTab /
 * DefaultBookshelfActions / DefaultBookCoverPlaceholder)。
 *
 * 假数据: 纯内存 Book/BookGroup, 不依赖 DB/网络;
 * AppConfigProviders 由 [LegadoThemePreview] 注册 stub。
 * BookshelfScreen 自身依赖 BookshelfViewModel (需 DB), 不 Preview, 仅 Preview 其内部组件。
 */

// ---- 假数据 ----

private val screenPreviewBook = Book(
    name = "三体",
    author = "刘慈欣",
    bookUrl = "screenPreview://1",
    tocUrl = "screenPreview://toc",
    origin = BookType.localTag,
    kind = "科幻;小说",
    intro = "三体世界与地球文明的接触, 黑暗森林法则下的宇宙博弈...",
    coverUrl = "https://preview/cover.jpg",
    durChapterTitle = "黑暗森林",
    latestChapterTitle = "末日之战",
    durChapterIndex = 5,
    totalChapterNum = 10,
    latestChapterTime = 1_700_000_000_000,
    lastCheckCount = 3,
)

private val screenPreviewGroups = listOf(
    BookGroup(groupId = 1, groupName = "全部"),
    BookGroup(groupId = 2, groupName = "科幻"),
    BookGroup(groupId = 3, groupName = "小说"),
)

@AppPreview
@Composable
fun DefaultBookCoverPlaceholderPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp).width(120.dp)) {
        DefaultBookCoverPlaceholder(screenPreviewBook)
    }
}

@AppPreview
@Composable
fun DefaultBookCoverPlaceholderDarkPreview() = LegadoThemePreview(dark = true) {
    Box(Modifier.padding(16.dp).width(120.dp)) {
        DefaultBookCoverPlaceholder(screenPreviewBook)
    }
}

@AppPreview
@Composable
fun BookshelfScreenTopBarPreview() = LegadoThemePreview {
    BookshelfTopBar(
        groups = screenPreviewGroups,
        currentGroupId = 1L,
        showGroupCount = true,
        bookCount = 3,
        onGroupClick = {},
        onGroupLongClick = {},
        actions = { DefaultBookshelfActions(onSearchClick = {}) },
    )
}

@AppPreview
@Composable
fun BookshelfScreenTopBarEmptyPreview() = LegadoThemePreview {
    BookshelfTopBar(
        groups = emptyList(),
        currentGroupId = BookGroup.IdAll,
        showGroupCount = false,
        bookCount = 0,
        onGroupClick = {},
        onGroupLongClick = {},
        actions = { DefaultBookshelfActions(onSearchClick = {}) },
    )
}

@AppPreview
@Composable
fun BookshelfScreenGroupTabPreview() = LegadoThemePreview {
    Row(Modifier.padding(16.dp)) {
        GroupTab(title = "选中", selected = true, eInk = false, onClick = {}, onLongClick = {})
        GroupTab(title = "未选中", selected = false, eInk = false, onClick = {}, onLongClick = {})
    }
}

@AppPreview
@Composable
fun BookshelfScreenActionsPreview() = LegadoThemePreview {
    Row(Modifier.padding(16.dp)) {
        DefaultBookshelfActions(onSearchClick = {})
    }
}
