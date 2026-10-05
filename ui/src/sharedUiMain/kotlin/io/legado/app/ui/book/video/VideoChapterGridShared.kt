package io.legado.app.ui.book.video

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.compose.component.FastScrollLazyVerticalGrid
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 选集网格 (对照 app 端 VideoPlayScreen.VideoChapterGrid: ChapterListAdapter + GridLayoutManager(3))。
 */
@Composable
fun VideoChapterGrid(
    chapters: List<BookChapter>,
    displayTitles: List<String>,
    durIndex: Int,
    onClick: (Int) -> Unit,
    onLongClick: ((Int) -> Unit)? = null,
    countWords: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    // 卷头 (TVBox 线路分组头) 点击收合/展开; 显示列表与目录页 TocScreen 同语义。
    // 默认只展开当前章所在分组 (目录页同语义; 无卷头时全展开), 目录刷新重置
    var collapsed by remember(chapters) { mutableStateOf(defaultCollapsedVolumes(chapters, durIndex)) }
    val display = remember(chapters, collapsed) { buildChapterDisplayList(chapters, collapsed) }
    LaunchedEffect(display, durIndex) {
        if (display.isNotEmpty()) {
            val position = display.indexOfFirst { it.index == durIndex }
            gridState.scrollToItem(position.coerceAtLeast(0))
        }
    }
    FastScrollLazyVerticalGrid(
        columns = rememberResponsiveColumns(3),
        state = gridState,
        modifier = modifier,
    ) {
        itemsIndexed(display, key = { _, chapter -> "${chapter.bookUrl}#${chapter.index}" }) { _, chapter ->
            VideoChapterItem(
                title = displayTitles.getOrElse(chapter.index) { chapter.title },
                isCurrent = chapter.index == durIndex,
                onClick = { onClick(chapter.index) },
                onLongClick = onLongClick?.let { cb -> { cb(chapter.index) } },
                onToggleVolume = if (chapter.isVolume) {
                    {
                        collapsed = if (chapter.index in collapsed) {
                            collapsed - chapter.index
                        } else {
                            collapsed + chapter.index
                        }
                    }
                } else {
                    null
                },
                volumeCollapsed = chapter.index in collapsed,
                chapter = chapter,
                countWords = countWords,
            )
        }
    }
}

/**
 * 默认收合集合: 只展开当前章所在分组, 其余卷头收起; 无当前章 (durIndex 无效) 时展开
 * 第一个分组; 无卷头返回空集 (全展开)。目录页 TocScreen 与选集网格共用。
 */
internal fun defaultCollapsedVolumes(chapters: List<BookChapter>, durIndex: Int): Set<Int> {
    val volumes = chapters.mapNotNull { chapter -> chapter.index.takeIf { chapter.isVolume } }
    if (volumes.isEmpty()) return emptySet()
    val keep = volumes.lastOrNull { it <= durIndex } ?: volumes.first()
    return (volumes - keep).toSet()
}

/**
 * 卷收合显示列表: 卷头 (isVolume) 按 [collapsed] 收起其后同级章节, 卷头本身恒显示
 * (目录页 TocScreen 同语义, 抽取共享)。
 */
internal fun buildChapterDisplayList(all: List<BookChapter>, collapsed: Set<Int>): List<BookChapter> {
    if (collapsed.isEmpty()) return all
    val out = ArrayList<BookChapter>(all.size)
    var hide = false
    for (item in all) {
        if (item.isVolume) {
            hide = item.index in collapsed
            out.add(item)
        } else if (!hide) {
            out.add(item)
        }
    }
    return out
}

/** 对照 item_chapter_list: 卷名 btn_bg 底、当前集 accent 字色 + 勾选、未缓存云图标、vip 锁。 */
@Composable
fun VideoChapterItem(
    title: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onToggleVolume: (() -> Unit)? = null,
    volumeCollapsed: Boolean = false,
    chapter: BookChapter? = null,
    countWords: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val isVolume = chapter?.isVolume == true
    Row(
        modifier
            .fillMaxWidth()
            // 行高基准与目录页 ChapterItem 一致: 单行不矮于带字数/tag 的章节行, 超高自动增高不裁剪
            .heightIn(min = DesignTokens.viewHeightMax)
            .then(if (isVolume) Modifier.background(rememberColor("btn_bg")) else Modifier)
            .combinedClickable(
                onClick = {
                    when {
                        isVolume && onToggleVolume != null -> onToggleVolume()
                        !isVolume -> onClick()
                    }
                },
                onLongClick = onLongClick,
            )
            .padding(DesignTokens.spacingDefault),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chapter != null && chapter.isVip && !chapter.isPay) {
            Icon(
                painter = rememberPainter("ic_lock_outline"),
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier
                    .size(24.dp)
                    .padding(end = DesignTokens.spacingDefault),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isCurrent) colors.accent else colors.primaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val showWordCount =
                countWords && chapter != null && !chapter.wordCount.isNullOrEmpty() && !isVolume
            val showTag = chapter != null && !chapter.tag.isNullOrEmpty() && !isVolume
            if (showWordCount || showTag) {
                Row {
                    if (showWordCount) {
                        Text(
                            text = chapter.wordCount.orEmpty(),
                            color = colors.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(end = DesignTokens.spacingLg),
                        )
                    }
                    if (showTag) {
                        Text(
                            text = chapter.tag.orEmpty(),
                            color = colors.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        val rightIcon = when {
            isVolume && volumeCollapsed -> "ic_expand_more"
            isVolume -> "ic_expand_less"
            isCurrent -> "ic_check"
            else -> "ic_outline_cloud_24"
        }
        Icon(
            painter = rememberPainter(rightIcon),
            contentDescription = null,
            tint = colors.secondaryText,
            modifier = Modifier
                .size(24.dp)
                .padding(DesignTokens.spacingXs),
        )
    }
}
