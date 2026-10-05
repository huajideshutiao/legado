package io.legado.app.ui.main.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.bookshelf.ShelfVideoItem
import io.legado.app.ui.bookshelf.toCoverBook
import io.legado.app.ui.compose.component.horizontalMouseWheel
import io.legado.app.ui.compose.component.listItemFocus
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.root.LocalSharedCoverBinding
import io.legado.app.ui.root.rememberSharedCoverSourceBinding
import org.jetbrains.compose.resources.stringResource
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.home_more
import androidx.compose.material.Icon
import androidx.compose.foundation.layout.size
import org.jetbrains.compose.resources.painterResource
import legado.ui.generated.resources.ic_arrow_right

/**
 * 展示项标题行 (对照 view_home_section_title.xml: 高 36dp, paddingStart 16 / paddingEnd 8,
 * 标题 16sp 加粗 + "更多" 13sp 摘要色 + 16dp 右箭头, 整行可点)。
 *
 * 主页展示项区块与搜索"按源分类"源区块共用 (整行点击进下一级)。
 */
@Composable
fun SectionTitleRow(title: String, onMoreClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClick = onMoreClick)
            .padding(start = DesignTokens.spacingLg, end = DesignTokens.spacingDefault),
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
        // 对照 view_home_section_title.xml 的 tv_more + iv_arrow (13sp 摘要色 + 16dp 箭头)
        Text(
            text = stringResource(Res.string.home_more),
            color = colors.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = DesignTokens.spacingDefault, vertical = DesignTokens.spacingXs),
        )
        Icon(
            painter = painterResource(Res.drawable.ic_arrow_right),
            contentDescription = stringResource(Res.string.home_more),
            tint = colors.secondaryText,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 横向封面行 (对照 CoverCardAdapter): 封面 + 书名, 横向滚动。
 *
 * 主页展示项 (COVER_ROW 样式) 与搜索"按源分类"源区块共用。
 *
 * isVideoStyle=true 时对照原版 CoverCardAdapter 的
 * VideoCoverCardVH: 复用 item_explore_video 视频卡 (shared [ShelfVideoItem]),
 * 卡片宽 220dp (原版把 match_parent 根布局改为固定 220dp 才能在横向滚动里排布),
 * 封面按 VIDEO(16:9) 比例由宽度反推高度, 加粗标题 + 分类 + 作者, 无徽标。
 */
@Composable
fun SectionCoverRow(
    books: List<SearchBook>,
    onBookClick: (SearchBook, String?) -> Unit,
    onBookLongClick: (SearchBook, String?) -> Unit,
    isVideoStyle: Boolean,
    blockId: String,
    coverSlot: (@Composable (SearchBook, Modifier, isVideoCover: Boolean) -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .horizontalMouseWheel(scrollState)
            .padding(horizontal = DesignTokens.spacingDefault),
    ) {
        if (isVideoStyle) {
            // 对照原 VideoCoverCardVH.bind: bindVideoCard(coverRatio=VIDEO, isInBookshelf=false,
            // showBookshelfBadge=false); 封面走 LocalBookCoverSlot (与书架/探索页一致)
            books.forEach { book ->
                // 共享配对身份按条目下发 (被点的封面 = 出发端, token 由页面+区块+条目派生)
                val binding = rememberSharedCoverSourceBinding(book.bookUrl, blockId)
                CompositionLocalProvider(LocalSharedCoverBinding provides binding) {
                    ShelfVideoItem(
                        book = book.toCoverBook(),
                        coverReloadTick = 0,
                        onClick = { onBookClick(book, binding.pageToken) },
                        onLongClick = { onBookLongClick(book, binding.pageToken) },
                        modifier = Modifier.width(220.dp),
                        coverSlot = { b, m, isVideoCover, tick ->
                            coverSlot?.invoke(book, m, isVideoCover)
                                ?: LocalBookCoverSlot.current(b, m, isVideoCover, tick)
                        },
                    )
                }
            }
        } else {
            val colors = AppTheme.colors
            // 对照 item_home_cover_card.xml + CoverCardVH.bind: 封面 120×160dp (高 160dp 由
            // 封面组件按 NOVEL 3:4 反推宽 120dp), item 总宽 128 = 120 + 两侧 4dp padding
            books.forEach { book ->
                // 共享配对身份按条目下发 (同上)
                val binding = rememberSharedCoverSourceBinding(book.bookUrl, blockId)
                CompositionLocalProvider(LocalSharedCoverBinding provides binding) {
                    Column(
                        Modifier
                            .listItemFocus()
                            .width(128.dp)
                            .padding(DesignTokens.spacingXs)
                            .combinedClickable(
                                onClick = { onBookClick(book, binding.pageToken) },
                                onLongClick = { onBookLongClick(book, binding.pageToken) },
                            ),
                    ) {
                        // 封面: 外部 slot 优先 (搜索端按书架命中分流缓存区), 否则默认
                        // toCoverBook() 补 notShelf 标记 (与书架/探索页一致)
                        if (coverSlot != null) {
                            coverSlot(
                                book,
                                Modifier
                                    .width(120.dp)
                                    .height(160.dp),
                                false,
                            )
                        } else {
                            LocalBookCoverSlot.current(
                                book.toCoverBook(),
                                Modifier
                                    .width(120.dp)
                                    .height(160.dp),
                                false,
                                0,
                            )
                        }
                        // 对照 XML tv_name: 12sp 最多 2 行 (minLines=2 保持卡片等高)
                        Text(
                            text = book.name,
                            color = colors.primaryText,
                            fontSize = 12.sp,
                            maxLines = 2,
                            minLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(top = DesignTokens.spacingXs),
                        )
                        // 对照 XML tv_author: 10sp 摘要色, 最多 1 行, marginTop 2dp
                        Text(
                            text = book.getRealAuthor(),
                            color = colors.secondaryText,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
