package io.legado.app.ui.main.home

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemExploreVideoBinding
import io.legado.app.model.CoverRatio
import io.legado.app.ui.book.explore.bindVideoCard
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.main.bookshelf.ShelfCover
import io.legado.app.ui.widget.setUpExploreOptions

private const val RANK_LIMIT = 5

/**
 * 非无限流展示项区块(对照 HomeSectionAdapter.SectionHolder)：
 * 标题行 + 参数 chip 行 + 按样式渲染内容(横向封面行 / 排行榜 / 四行)。空/加载/错误占位。
 */
@Composable
internal fun SectionBlock(state: HomeTabState, tabTitle: String, section: HomeSection) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        SectionTitleRow(section.title) { state.onMoreClick(section) }
        SectionOptions(state, tabTitle, section)
        val books = state.sectionBooks(tabTitle, section.id)
        when {
            books.isNotEmpty() -> when (section.style) {
                HomeSection.STYLE_RANK_LIST -> RankColumn(state, section, books.take(RANK_LIMIT))
                HomeSection.STYLE_FOUR_ROW -> FourRow(state, section, books)
                else -> CoverRow(state, section, books)
            }

            else -> SectionPlaceholder(
                loading = state.isSectionLoading(tabTitle, section.id),
                error = state.sectionHasError(tabTitle, section.id),
            )
        }
    }
}

/** 无限流的头部(对照 header)：标题行 + 参数 chip 行，占整行随网格滚动。 */
@Composable
internal fun InfiniteHeader(state: HomeTabState, tabTitle: String, section: HomeSection) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SectionTitleRow(section.title) { state.onMoreClick(section) }
        SectionOptions(state, tabTitle, section)
    }
}

/** 空/加载/错误占位(对照 LoadMoreView 三态)。 */
@Composable
private fun SectionPlaceholder(loading: Boolean, error: Boolean) {
    val colors = AppTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 96.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp),
            )

            error -> Text(
                text = stringResource(R.string.home_source_invalid),
                color = colors.secondaryText,
                fontSize = 12.sp,
            )

            else -> Text(
                text = stringResource(R.string.empty),
                color = colors.secondaryText,
                fontSize = 12.sp,
            )
        }
    }
}

/** 横向封面行(STYLE_COVER_ROW)：小说竖封面卡 / 视频复用发现页视频卡(桥接)。 */
@Composable
private fun CoverRow(state: HomeTabState, section: HomeSection, books: List<SearchBook>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(books, key = { it.bookUrl }) { book ->
            if (section.coverVideo) {
                VideoCardView(
                    book = book,
                    modifier = Modifier.width(220.dp).padding(4.dp),
                    onClick = { state.onBookClick(book, section, false) },
                    onLongClick = { state.onBookClick(book, section, true) },
                )
            } else {
                NovelCoverCard(state, section, book)
            }
        }
    }
}

/** 小说竖封面卡(对照 item_home_cover_card)：封面(160dp,3:4) + 书名(2 行) + 作者。 */
@Composable
private fun NovelCoverCard(state: HomeTabState, section: HomeSection, book: SearchBook) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .width(120.dp) // 160dp * 3/4
            .padding(4.dp)
            .combinedClickable(
                onClick = { state.onBookClick(book, section, false) },
                onLongClick = { state.onBookClick(book, section, true) },
            ),
    ) {
        ShelfCover(
            path = book.coverUrl,
            name = book.name,
            author = book.author,
            origin = book.origin,
            ratio = CoverRatio.NOVEL,
            reloadKey = 0,
            modifier = Modifier.width(120.dp).height(160.dp),
        )
        Text(
            text = book.name,
            color = colors.primaryText,
            fontSize = 12.sp,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = book.getRealAuthor(),
            color = colors.secondaryText,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 排行榜(STYLE_RANK_LIST)：竖排前 N 名，带名次色标(前 3 名红/橙/黄)。 */
@Composable
private fun RankColumn(state: HomeTabState, section: HomeSection, books: List<SearchBook>) {
    Column(Modifier.fillMaxWidth()) {
        books.forEachIndexed { index, book ->
            RankItem(state, section, rank = index + 1, book = book, showRank = true)
        }
    }
}

/**
 * 四行(STYLE_FOUR_ROW)：横向翻页，每列 4 本(不显名次)。
 * flingBehavior 用 SnapPosition.Start 对齐原 StartSnapHelper 的起始吸附语义。
 */
@Composable
private fun FourRow(state: HomeTabState, section: HomeSection, books: List<SearchBook>) {
    val rowState = rememberLazyListState()
    val columns = remember(books) { books.chunked(4) }
    LazyRow(
        state = rowState,
        flingBehavior = rememberSnapFlingBehavior(rowState, SnapPosition.Start),
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(columns, key = { it.firstOrNull()?.bookUrl ?: it.hashCode() }) { column ->
            Column(Modifier.width(220.dp)) {
                column.forEach { book ->
                    RankItem(state, section, rank = 0, book = book, showRank = false)
                }
            }
        }
    }
}

/** 排行/四行的行项(对照 item_home_rank_book)：名次 + 小封面(70dp) + 名/作者。 */
@Composable
private fun RankItem(
    state: HomeTabState,
    section: HomeSection,
    rank: Int,
    book: SearchBook,
    showRank: Boolean,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { state.onBookClick(book, section, false) },
                onLongClick = { state.onBookClick(book, section, true) },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showRank) {
            val rankColor = when (rank) {
                1 -> colorResource(R.color.md_red_600)
                2 -> colorResource(R.color.md_orange_700)
                3 -> colorResource(R.color.md_yellow_700)
                else -> colorResource(R.color.tv_text_summary)
            }
            Text(
                text = rank.toString(),
                color = rankColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(28.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        ShelfCover(
            path = book.coverUrl,
            name = book.name,
            author = book.author,
            origin = book.origin,
            ratio = CoverRatio.NOVEL,
            reloadKey = 0,
            modifier = Modifier
                .padding(start = if (showRank) 8.dp else 0.dp)
                .height(70.dp)
                .width(52.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.getRealAuthor(),
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 无限流网格单元(对照 GridExploreShowAdapter/VideoExploreShowAdapter 的网格项)。 */
@Composable
internal fun InfiniteGridCard(
    state: HomeTabState,
    tabTitle: String,
    section: HomeSection,
    book: SearchBook,
) {
    if (section.coverVideo) {
        VideoCardView(
            book = book,
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            onClick = { state.onBookClick(book, section, false) },
            onLongClick = { state.onBookClick(book, section, true) },
        )
    } else {
        val colors = AppTheme.colors
        Column(
            Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .combinedClickable(
                    onClick = { state.onBookClick(book, section, false) },
                    onLongClick = { state.onBookClick(book, section, true) },
                ),
        ) {
            ShelfCover(
                path = book.coverUrl,
                name = book.name,
                author = book.author,
                origin = book.origin,
                ratio = CoverRatio.NOVEL,
                reloadKey = 0,
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 12.sp,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 视频卡桥接(附录 D 欠账)：复用发现页 item_explore_video(封面/标签/徽标)。
 * holder 缓存 binding 与 bind key，避免无关重组触发 Glide 重载。
 */
private class VideoCardHolder {
    var binding: ItemExploreVideoBinding? = null
    var key: String? = null
}

@Composable
private fun VideoCardView(
    book: SearchBook,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val holder = remember { VideoCardHolder() }
    AndroidView(
        factory = { ctx ->
            val b = ItemExploreVideoBinding.inflate(LayoutInflater.from(ctx))
            b.root.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            holder.binding = b
            b.root
        },
        modifier = modifier,
        update = { root ->
            val b = holder.binding ?: return@AndroidView
            val key = "${book.bookUrl}|${book.coverUrl}"
            if (holder.key != key) {
                holder.key = key
                b.bindVideoCard(book, book.coverUrl, isInBookshelf = false, showBookshelfBadge = false)
            }
            root.setOnClickListener { onClick() }
            root.setOnLongClickListener { onLongClick(); true }
        },
    )
}

/**
 * 参数 chip 行桥接：复用 [setUpExploreOptions](removeAllViews+按 options 渲染 chip+切 isVisible)。
 * 用户点 chip 直接改可变 ExploreOption 实例并回调重载；仅在 optionsVersion 变化时重建，
 * 避免书籍更新的重组触发整行重 inflate。
 */
@Composable
private fun SectionOptions(state: HomeTabState, tabTitle: String, section: HomeSection) {
    val version = state.optionsVersionOf(tabTitle, section.id)
    AndroidView(
        factory = { ctx ->
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        update = { container ->
            val key = version.toString()
            if (container.tag != key) {
                container.tag = key
                container.setUpExploreOptions(state.sectionOptions(tabTitle, section.id)) {
                    state.onOptionSelected(tabTitle, section)
                }
            }
        },
    )
}

/** 展示项标题行(对照 view_home_section_title)：标题 + 「更多」+ 右箭头，整行点击进详情。 */
@Composable
internal fun SectionTitleRow(title: String, onMore: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClick = onMore)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = colors.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.home_more),
            color = colors.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right),
            contentDescription = stringResource(R.string.home_more),
            tint = colors.secondaryText,
            modifier = Modifier.size(16.dp),
        )
    }
}
