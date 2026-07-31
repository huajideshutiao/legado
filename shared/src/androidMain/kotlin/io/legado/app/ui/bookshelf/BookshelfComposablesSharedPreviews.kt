package io.legado.app.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [BookshelfComposablesShared.kt] 中各 Composable 的 @Preview。
 *
 * 假数据: Book/BookGroup 用纯内存对象构造, coverSlot 用灰色 Box + "封面" 文本占位,
 * 不依赖 DB/网络。AppConfigProviders 已由 [LegadoThemePreview] 注册 stub。
 */

// ---- 假数据 ----

private val previewBook = Book(
    name = "三体",
    author = "刘慈欣",
    bookUrl = "preview://1",
    tocUrl = "preview://toc",
    origin = BookType.localTag,
    kind = "科幻;小说;硬科幻",
    intro = "三体世界与地球文明的接触, 黑暗森林法则下的宇宙博弈...",
    coverUrl = "https://preview/cover.jpg",
    durChapterTitle = "黑暗森林",
    latestChapterTitle = "末日之战",
    durChapterIndex = 5,
    totalChapterNum = 10,
    latestChapterTime = 1_700_000_000_000,
    lastCheckCount = 3,
)

private val previewBookRefreshing = previewBook.copy(
    name = "刷新中书籍",
    bookUrl = "preview://refreshing",
)

private val previewGroup = BookGroup(
    groupId = 1,
    groupName = "我的分组",
    cover = "https://preview/group.jpg",
)

private val previewBookGroupEmpty = BookGroup(
    groupId = 2,
    groupName = "空分组",
)

/** 占位封面 slot (灰色 Box + "封面" 文本), 接受外部 modifier 控制尺寸 (对照真实 ShelfCover)。
 * isVideoCover 对照 CoverRatio: NOVEL=3:4 (0.75), VIDEO=16:9。 */
private val bookCoverSlot: @Composable (Book, Modifier, isVideoCover: Boolean) -> Unit = { book, modifier, isVideoCover ->
    val ratio = if (isVideoCover) 16f / 9f else 0.75f
    Box(
        modifier
            .aspectRatio(ratio)
            .background(Color(0xFF888888), DesignTokens.shapeSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(book.name.take(2), color = Color.White)
    }
}

/** 占位封面 slot (BookGroup 用), 接受外部 modifier 控制尺寸。
 * isVideoCover 对照 CoverRatio: NOVEL=3:4 (0.75), VIDEO=16:9。 */
private val groupCoverSlot: @Composable (BookGroup, Modifier, isVideoCover: Boolean) -> Unit = { group, modifier, isVideoCover ->
    val ratio = if (isVideoCover) 16f / 9f else 0.75f
    Box(
        modifier
            .aspectRatio(ratio)
            .background(Color(0xFF6B7280), DesignTokens.shapeSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(group.groupName.take(2), color = Color.White)
    }
}

// ---- 徽标 / 标签 ----

@Preview
@Composable
fun UnreadBadgePreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        UnreadBadge(count = 99, highlight = true)
    }
}

@Preview
@Composable
fun UnreadBadgeNormalPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        UnreadBadge(count = 5, highlight = false)
    }
}

@Preview
@Composable
fun KindLabelsPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        KindLabels(kinds = listOf("科幻", "小说", "硬科幻"))
    }
}

// ---- 列表条目 ----

@Preview
@Composable
fun ShelfListItemPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        ShelfListItem(
            book = previewBook,
            isVideoStyle = false,
            coverReloadTick = 0,
            refreshingUrls = emptySet(),
            showLastUpdateTime = true,
            showKindIntro = true,
            onClick = {},
            onLongClick = {},
            coverSlot = bookCoverSlot,
            lastUpdateTextSlot = { ShelfLastUpdateText(previewBook.latestChapterTime, remember { mutableIntStateOf(0) }) },
        )
    }
}

@Preview
@Composable
fun ShelfListItemRefreshingPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        ShelfListItem(
            book = previewBookRefreshing,
            isVideoStyle = false,
            coverReloadTick = 0,
            refreshingUrls = setOf(previewBookRefreshing.bookUrl),
            showLastUpdateTime = true,
            showKindIntro = true,
            onClick = {},
            onLongClick = {},
            coverSlot = bookCoverSlot,
            lastUpdateTextSlot = { ShelfLastUpdateText(previewBookRefreshing.latestChapterTime, remember { mutableIntStateOf(0) }) },
        )
    }
}

@Preview
@Composable
fun ShelfListItemDarkPreview() = LegadoThemePreview(dark = true) {
    Box(Modifier.padding(8.dp)) {
        ShelfListItem(
            book = previewBook,
            isVideoStyle = false,
            coverReloadTick = 0,
            refreshingUrls = emptySet(),
            showLastUpdateTime = true,
            showKindIntro = true,
            onClick = {},
            onLongClick = {},
            coverSlot = bookCoverSlot,
            lastUpdateTextSlot = { ShelfLastUpdateText(previewBook.latestChapterTime, remember { mutableIntStateOf(0) }) },
        )
    }
}

// ---- 网格条目 ----

@Preview
@Composable
fun ShelfGridItemPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp).width(120.dp)) {
        ShelfGridItem(
            book = previewBook,
            coverReloadTick = 0,
            refreshingUrls = emptySet(),
            onClick = {},
            onLongClick = {},
            coverSlot = bookCoverSlot,
        )
    }
}

@Preview
@Composable
fun ShelfGridItemRefreshingPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp).width(120.dp)) {
        ShelfGridItem(
            book = previewBookRefreshing,
            coverReloadTick = 0,
            refreshingUrls = setOf(previewBookRefreshing.bookUrl),
            onClick = {},
            onLongClick = {},
            coverSlot = bookCoverSlot,
        )
    }
}

// ---- 视频卡片条目 ----

@Preview
@Composable
fun ShelfVideoItemPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp).width(160.dp)) {
        ShelfVideoItem(
            book = previewBook,
            coverReloadTick = 0,
            onClick = {},
            onLongClick = {},
            coverSlot = bookCoverSlot,
        )
    }
}

// ---- 分组(文件夹)条目 ----

@Preview
@Composable
fun GroupListItemPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        GroupListItem(
            group = previewGroup,
            isVideoStyle = false,
            coverReloadTick = 0,
            onClick = {},
            onLongClick = {},
            coverSlot = groupCoverSlot,
        )
    }
}

@Preview
@Composable
fun GroupGridItemPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp).width(120.dp)) {
        GroupGridItem(
            group = previewGroup,
            coverReloadTick = 0,
            onClick = {},
            onLongClick = {},
            coverSlot = groupCoverSlot,
        )
    }
}

@Preview
@Composable
fun GroupVideoItemPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp).width(160.dp)) {
        GroupVideoItem(
            group = previewGroup,
            coverReloadTick = 0,
            onClick = {},
            onLongClick = {},
            coverSlot = groupCoverSlot,
        )
    }
}

@Preview
@Composable
fun GroupVideoItemEmptyCoverPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp).width(160.dp)) {
        GroupVideoItem(
            group = previewBookGroupEmpty,
            coverReloadTick = 0,
            onClick = {},
            onLongClick = {},
            coverSlot = groupCoverSlot,
        )
    }
}
