package io.legado.app.ui.book.bookmark

import androidx.compose.runtime.Composable
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.preview.AppPreview
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewBookmarks

/**
 * [BookmarkDialog] 与 [AllBookmarkScreen] 的 @Preview。
 *
 * 假数据取 PreviewData.previewBookmarks (跨两本书, 验证按书分组吸顶)。
 */

// ---- BookmarkDialog ----

private val previewBookmark = previewBookmarks.first()

@AppPreview
@Composable
fun BookmarkDialogPreview() = LegadoThemePreview {
    BookmarkDialog(
        bookmark = previewBookmark,
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun BookmarkDialogWithDeletePreview() = LegadoThemePreview {
    BookmarkDialog(
        bookmark = previewBookmark,
        showDelete = true,
        onConfirm = {},
        onDismiss = {},
        onDelete = {},
    )
}

@AppPreview
@Composable
fun BookmarkDialogEmptyNotePreview() = LegadoThemePreview {
    BookmarkDialog(
        bookmark = previewBookmarks[1],
        onConfirm = {},
        onDismiss = {},
    )
}

@AppPreview
@Composable
fun BookmarkDialogDarkPreview() = LegadoThemePreview(dark = true) {
    BookmarkDialog(
        bookmark = previewBookmark,
        showDelete = true,
        onConfirm = {},
        onDismiss = {},
        onDelete = {},
    )
}

// ---- AllBookmarkScreen ----

private val noOpBookmarkActions = object : AllBookmarkUiActions {
    override fun onBack() {}
    override fun export() {}
    override fun exportMd() {}
    override fun openBookmark(bookmark: Bookmark) {}
    override fun editBookmark(bookmark: Bookmark, pos: Int) {}
}

@AppPreview
@Composable
fun AllBookmarkScreenPreview() = LegadoThemePreview {
    AllBookmarkScreen(
        state = AllBookmarkUiState(bookmarks = previewBookmarks),
        actions = noOpBookmarkActions,
    )
}

@AppPreview
@Composable
fun AllBookmarkScreenEmptyPreview() = LegadoThemePreview {
    AllBookmarkScreen(
        state = AllBookmarkUiState(),
        actions = noOpBookmarkActions,
    )
}

@AppPreview
@Composable
fun AllBookmarkScreenDarkPreview() = LegadoThemePreview(dark = true) {
    AllBookmarkScreen(
        state = AllBookmarkUiState(bookmarks = previewBookmarks),
        actions = noOpBookmarkActions,
    )
}
