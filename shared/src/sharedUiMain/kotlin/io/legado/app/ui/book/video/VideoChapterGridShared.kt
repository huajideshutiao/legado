package io.legado.app.ui.book.video

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme

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
    LaunchedEffect(chapters) {
        if (chapters.isNotEmpty()) {
            gridState.scrollToItem(durIndex.coerceIn(0, chapters.lastIndex))
        }
    }
    LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState, modifier = modifier) {
        itemsIndexed(chapters, key = { _, chapter -> chapter.url }) { index, chapter ->
            VideoChapterItem(
                title = displayTitles.getOrElse(index) { chapter.title },
                isCurrent = chapter.index == durIndex,
                onClick = { onClick(index) },
                onLongClick = onLongClick?.let { cb -> { cb(index) } },
                chapter = chapter,
                countWords = countWords,
            )
        }
    }
}

/** 对照 item_chapter_list: 卷名 btn_bg 底、当前集 accent 字色 + 勾选、未缓存云图标、vip 锁。 */
@Composable
fun VideoChapterItem(
    title: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    chapter: BookChapter? = null,
    countWords: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val isVolume = chapter?.isVolume == true
    Row(
        modifier
            .fillMaxWidth()
            .then(if (isVolume) Modifier.background(rememberColor("btn_bg")) else Modifier)
            .combinedClickable(
                onClick = { if (!isVolume) onClick() },
                onLongClick = onLongClick,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chapter != null && chapter.isVip && !chapter.isPay) {
            Icon(
                painter = rememberPainter("ic_lock_outline"),
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier
                    .size(24.dp)
                    .padding(end = 8.dp),
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
                            text = chapter?.wordCount.orEmpty(),
                            color = colors.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                    if (showTag) {
                        Text(
                            text = chapter?.tag.orEmpty(),
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
                .padding(4.dp),
        )
    }
}
