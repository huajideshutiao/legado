package io.legado.app.ui.book.info.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.legado.app.ui.preview.AppPreview
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.constant.BookType
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [BookInfoEditScreen] 的 @Preview。
 *
 * 假数据: [Book] 纯内存对象, [BookInfoEditUiState] 预设编辑态字段,
 * [coverSlot] 用灰色 Box + 书名占位 (替代 Glide 封面)。
 */

private val previewBook = Book(
    name = "三体",
    author = "刘慈欣",
    bookUrl = "preview://book",
    origin = BookType.localTag,
    coverUrl = "https://preview/cover.jpg",
    intro = "三体世界与地球文明的接触, 黑暗森林法则下的宇宙博弈。",
)

private val previewState = BookInfoEditUiState(
    book = previewBook,
    name = "三体",
    author = "刘慈欣",
    typeIndex = 0,
    coverUrl = "https://preview/cover.jpg",
    intro = "三体世界与地球文明的接触, 黑暗森林法则下的宇宙博弈。",
    bookUrl = "preview://book",
    coverTick = 0,
)

/** no-op actions */
private object NoOpEditActions : BookInfoEditUiActions {
    override fun onBack() {}
    override fun onSave() {}
    override fun onSelectCover() {}
    override fun onChangeCoverSource() {}
    override fun onRefreshCover() {}
    override fun onNameChange(value: String) {}
    override fun onAuthorChange(value: String) {}
    override fun onTypeChange(index: Int) {}
    override fun onCoverUrlChange(value: String) {}
    override fun onIntroChange(value: String) {}
    override fun onBookUrlChange(value: String) {}
}

/** 占位封面 slot: 灰色 Box + 书名前两字 */
private val coverSlot: @Composable (Book?, Modifier) -> Unit = { book, modifier ->
    Box(
        modifier
            .width(110.dp)
            .background(Color(0xFF888888), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(book?.name?.take(2).orEmpty(), color = Color.White)
    }
}

@AppPreview
@Composable
fun BookInfoEditScreenPreview() = LegadoThemePreview {
    BookInfoEditScreen(state = previewState, actions = NoOpEditActions, coverSlot = coverSlot)
}

@AppPreview
@Composable
fun BookInfoEditScreenNewBookPreview() = LegadoThemePreview {
    val newState = previewState.copy(book = null, name = "", author = "", intro = "", coverUrl = "")
    BookInfoEditScreen(state = newState, actions = NoOpEditActions, coverSlot = coverSlot)
}

@AppPreview
@Composable
fun BookInfoEditScreenDarkPreview() = LegadoThemePreview(dark = true) {
    BookInfoEditScreen(state = previewState, actions = NoOpEditActions, coverSlot = coverSlot)
}
