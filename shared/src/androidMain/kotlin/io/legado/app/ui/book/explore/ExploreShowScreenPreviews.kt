package io.legado.app.ui.book.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [ExploreShowScreen] 的 @Preview。
 *
 * optionsRowSlot/videoItemSlot/coverSlot 均用占位实现 (真实实现依赖 Coil 与 app 端组件)。
 */

private val previewSearchBooks = listOf(
    SearchBook(
        bookUrl = "preview://sb/1",
        origin = "preview://source",
        originName = "示例书源",
        name = "三体",
        author = "刘慈欣",
        kind = "科幻,连载",
        coverUrl = "https://preview.invalid/cover1.jpg",
        intro = "地球文明向宇宙发出的第一声啼鸣, 以及随之而来的一切。",
        wordCount = "88万字",
        latestChapterTitle = "第三十六章 末日之战",
    ),
    SearchBook(
        bookUrl = "preview://sb/2",
        origin = "preview://source",
        originName = "示例书源",
        name = "球状闪电",
        author = "刘慈欣",
        kind = "科幻,完结",
        intro = "那是一个雨夜, 球状闪电闯进了他的生日聚会。",
        wordCount = "32万字",
        latestChapterTitle = "尾声",
    ),
    SearchBook(
        bookUrl = "preview://sb/3",
        origin = "preview://source2",
        originName = "另一个书源",
        name = "白夜行",
        author = "东野圭吾",
        kind = "推理",
        intro = "只希望能手牵手在太阳下散步。",
        wordCount = "46万字",
        latestChapterTitle = "终章",
    ),
)

private val noOpExploreActions = object : ExploreShowUiActions {
    override fun onBack() {}
    override fun onTitleClick() {}
    override fun onToggleFavorite() {}
    override fun onRefresh() {}
    override fun onLogin() {}
    override fun onSwitchLayout() {}
    override fun onShowColumnPicker() {}
    override fun onShowSourceFilterRule() {}
    override fun onFooterClick() {}
    override fun onExploreOptionChanged() {}
    override fun onScrollToBottom() {}
    override fun onBookClick(book: SearchBook, longClick: Boolean) {}
    override fun isInBookshelf(book: SearchBook): Boolean = book.name == "三体"
}

private val previewExploreCoverSlot: @Composable (SearchBook, Boolean, Boolean, Modifier) -> Unit =
    { book, _, isVideoStyle, modifier ->
        Box(
            modifier
                .aspectRatio(if (isVideoStyle) 16f / 9f else 0.75f)
                .background(Color(0xFF888888), DesignTokens.shapeSm),
            contentAlignment = Alignment.Center,
        ) {
            Text(book.name.take(2), color = Color.White)
        }
    }

private fun previewExploreState(
    books: List<SearchBook> = previewSearchBooks,
    exploreStyle: Int = 0,
    footerLoading: Boolean = false,
) = ExploreShowUiState(
    title = "热门推荐",
    books = books,
    exploreStyle = exploreStyle,
    isFavorite = true,
    canLogin = true,
    bookshelfVersion = 0,
    optionsVersion = 0,
    scrollTopEpoch = 0,
    footerLoading = footerLoading,
    footerText = if (footerLoading) null else "点击加载更多",
)

@Preview
@Composable
fun ExploreShowScreenPreview() = LegadoThemePreview {
    ExploreShowScreen(
        state = previewExploreState(),
        actions = noOpExploreActions,
        optionsRowSlot = {},
        videoItemSlot = { _, _, _, _ -> },
        coverSlot = previewExploreCoverSlot,
    )
}

@Preview
@Composable
fun ExploreShowScreenLoadingPreview() = LegadoThemePreview {
    ExploreShowScreen(
        state = previewExploreState(footerLoading = true),
        actions = noOpExploreActions,
        optionsRowSlot = {},
        videoItemSlot = { _, _, _, _ -> },
        coverSlot = previewExploreCoverSlot,
    )
}

@Preview
@Composable
fun ExploreShowScreenEmptyPreview() = LegadoThemePreview {
    ExploreShowScreen(
        state = previewExploreState(books = emptyList()),
        actions = noOpExploreActions,
        optionsRowSlot = {},
        videoItemSlot = { _, _, _, _ -> },
        coverSlot = previewExploreCoverSlot,
    )
}

@Preview
@Composable
fun ExploreShowScreenDarkPreview() = LegadoThemePreview(dark = true) {
    ExploreShowScreen(
        state = previewExploreState(),
        actions = noOpExploreActions,
        optionsRowSlot = {},
        videoItemSlot = { _, _, _, _ -> },
        coverSlot = previewExploreCoverSlot,
    )
}
