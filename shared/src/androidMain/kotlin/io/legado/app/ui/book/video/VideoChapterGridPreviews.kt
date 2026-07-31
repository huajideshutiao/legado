package io.legado.app.ui.book.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.preview.LegadoThemePreview

/** [VideoChapterGrid] / [VideoChapterItem] 的 @Preview (选集网格, 含当前集高亮)。 */

private val previewVideoChapters = (1..24).map { index ->
    BookChapter(
        url = "preview://ep/$index",
        title = "第 $index 集",
        bookUrl = "preview://video/1",
        index = index - 1,
    )
}

@Preview
@Composable
fun VideoChapterGridPreview() = LegadoThemePreview {
    VideoChapterGrid(
        chapters = previewVideoChapters,
        displayTitles = previewVideoChapters.map { it.title },
        durIndex = 4,
        onClick = {},
    )
}

@Preview
@Composable
fun VideoChapterGridFewEpisodesPreview() = LegadoThemePreview {
    VideoChapterGrid(
        chapters = previewVideoChapters.take(3),
        displayTitles = previewVideoChapters.take(3).map { it.title },
        durIndex = 0,
        onClick = {},
    )
}

@Preview
@Composable
fun VideoChapterGridDarkPreview() = LegadoThemePreview(dark = true) {
    VideoChapterGrid(
        chapters = previewVideoChapters,
        displayTitles = previewVideoChapters.map { it.title },
        durIndex = 11,
        onClick = {},
        onLongClick = {},
    )
}

@Preview
@Composable
fun VideoChapterItemCurrentPreview() = LegadoThemePreview {
    VideoChapterItem(
        title = "第 5 集",
        isCurrent = true,
        onClick = {},
    )
}

@Preview
@Composable
fun VideoChapterItemNormalPreview() = LegadoThemePreview {
    VideoChapterItem(
        title = "第 6 集",
        isCurrent = false,
        onClick = {},
    )
}
