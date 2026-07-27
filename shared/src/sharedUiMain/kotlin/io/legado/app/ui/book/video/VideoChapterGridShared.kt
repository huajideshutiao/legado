package io.legado.app.ui.book.video

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.compose.theme.AppTheme

@Composable
fun VideoChapterGrid(
    chapters: List<BookChapter>,
    displayTitles: List<String>,
    durIndex: Int,
    onClick: (Int) -> Unit,
    onLongClick: ((Int) -> Unit)? = null,
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
            )
        }
    }
}

@Composable
fun VideoChapterItem(
    title: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .then(if (isCurrent) Modifier.background(colors.accent) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = if (isCurrent) Color.White else colors.primaryText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
