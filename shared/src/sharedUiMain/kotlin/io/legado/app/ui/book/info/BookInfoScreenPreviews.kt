package io.legado.app.ui.book.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.constant.BookType
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [BookInfoScreen] 的 @Preview。
 *
 * 假数据: [Book] 纯内存对象, [BookInfoUiState] 预设详情态字段,
 * 三个 slot 用灰色 Box 占位 (替代 Glide/AndroidView)。
 */

private val previewBook = Book(
    name = "三体",
    author = "刘慈欣",
    bookUrl = "preview://book",
    tocUrl = "preview://toc",
    origin = BookType.localTag,
    originName = "本地",
    kind = "科幻:硬科幻,小说",
    coverUrl = "https://preview/cover.jpg",
    intro = "三体世界与地球文明的接触, 黑暗森林法则下的宇宙博弈。",
    durChapterTitle = "黑暗森林",
    latestChapterTitle = "末日之战",
    durChapterIndex = 5,
    totalChapterNum = 120,
)

private val previewMenuState = BookInfoMenuState(
    isLocal = true,
    isWebDav = false,
    hasSource = false,
    sourceHasLogin = false,
    sourceHasReviewRule = false,
    canUpdate = true,
    isLocalTxt = false,
    splitLongChapter = false,
    bookUrl = "preview://book",
    tocUrl = "preview://toc",
)

private val previewState = BookInfoUiState(
    book = previewBook,
    bookTick = 0,
    coverTick = 0,
    inBookshelf = true,
    groupName = "我的分组",
    tocText = "120 章",
    lastedTitle = "末日之战",
    wordCountText = "100 万字",
    isLandscape = false,
    useDevFeat = false,
    isDarkTheme = false,
    menuState = previewMenuState,
)

private val previewStateLandscape = previewState.copy(isLandscape = true)

private val previewStateNotInShelf = previewState.copy(inBookshelf = false)

/** no-op actions */
private object NoOpInfoActions : BookInfoUiActions {
    override fun onBack() {}
    override fun onEdit() {}
    override fun onShare() {}
    override fun onRefresh() {}
    override fun onUploadBook() {}
    override fun onDownloadToLocal() {}
    override fun onTopBook() {}
    override fun onLogin() {}
    override fun onOpenCommentDialog() {}
    override fun onSetSourceVariable() {}
    override fun onSetBookVariable() {}
    override fun onCopyBookUrl() {}
    override fun onCopyTocUrl() {}
    override fun onToggleCanUpdate() {}
    override fun onToggleSplitLongChapter() {}
    override fun onClearCache() {}
    override fun onShowLog() {}
    override fun onNameClick() {}
    override fun onCoverClick() {}
    override fun onCoverLongClick() {}
    override fun onOriginClick() {}
    override fun onOriginLongClick() {}
    override fun onTocClick() {}
    override fun onGroupClick() {}
    override fun onShelfClick() {}
    override fun onReadClick() {}
    override fun onSearchAuthor(author: String, submit: Boolean) {}
    override fun onSearchKind(kind: String, submit: Boolean) {}
    override fun onDispatchIntroAction(action: String) {}
    override fun onShowPhoto(src: String) {}
}

/** 占位模糊背景 slot */
private val blurCoverBgSlot: @Composable (Modifier) -> Unit = { modifier ->
    Box(modifier.background(Color(0xFF4A6B8A)))
}

/** 占位封面 slot */
private val coverSlot: @Composable (Book?, Modifier) -> Unit = { book, modifier ->
    Box(modifier.background(Color(0xFF888888)), contentAlignment = Alignment.Center) {
        Text(book?.name?.take(2).orEmpty(), color = Color.White)
    }
}

/** 占位简介图片 slot */
private val introImageSlot: @Composable (String, () -> Unit) -> Unit = { src, onClick ->
    Box(
        Modifier
            .background(Color(0xFFCCCCCC))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("[图片] $src", color = Color.DarkGray)
    }
}

@Preview
@Composable
fun BookInfoScreenPreview() = LegadoThemePreview {
    BookInfoScreen(
        state = previewState,
        actions = NoOpInfoActions,
        blurCoverBgSlot = blurCoverBgSlot,
        coverSlot = coverSlot,
        introImageSlot = introImageSlot,
    )
}

@Preview
@Composable
fun BookInfoScreenLandscapePreview() = LegadoThemePreview {
    BookInfoScreen(
        state = previewStateLandscape,
        actions = NoOpInfoActions,
        blurCoverBgSlot = blurCoverBgSlot,
        coverSlot = coverSlot,
        introImageSlot = introImageSlot,
    )
}

@Preview
@Composable
fun BookInfoScreenNotInShelfPreview() = LegadoThemePreview {
    BookInfoScreen(
        state = previewStateNotInShelf,
        actions = NoOpInfoActions,
        blurCoverBgSlot = blurCoverBgSlot,
        coverSlot = coverSlot,
        introImageSlot = introImageSlot,
    )
}

@Preview
@Composable
fun BookInfoScreenDarkPreview() = LegadoThemePreview(dark = true) {
    BookInfoScreen(
        state = previewState,
        actions = NoOpInfoActions,
        blurCoverBgSlot = blurCoverBgSlot,
        coverSlot = coverSlot,
        introImageSlot = introImageSlot,
    )
}
