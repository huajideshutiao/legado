package io.legado.app.ui.book.bookmark

// I18N KEYS (need to register in ResourceProvider.jvm.kt):
//   "bookmark_content" to "内容",
//   "bookmark_note" to "备注内容"

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewBookmarks
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookmark
import legado.shared.generated.resources.bookmark_content
import legado.shared.generated.resources.bookmark_note
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * 书签编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.bookmark.BookmarkDialog`, 但去掉对
 * BaseComposeDialogFragment / Bundle / GSON / appDb 的依赖, 改为纯 @Composable + 回调形式:
 * - 展示章节名 (只读), 编辑书签原文 (bookText) 和备注内容 (content)
 * - 编辑态 (showDelete=true) 露出删除按钮, 调用 [onDelete]
 * - 确认时把修改后的 bookmark (新实例) 通过 [onConfirm] 回传, 调用方负责入库
 *
 * @param bookmark 待编辑的书签 (不被修改, 确认时以 copy 回传)
 * @param showDelete 是否显示删除按钮 (编辑态传 true, 新建态传 false)
 * @param onConfirm 用户点击确定, 参数为更新后的 bookmark
 * @param onDismiss 用户取消 (返回按钮 / 点击对话框外部 / 取消按钮)
 * @param onDelete 可选的删除回调 (showDelete=true 且非 null 时显示删除按钮)
 */
@Composable
fun BookmarkDialog(
    bookmark: Bookmark,
    showDelete: Boolean = false,
    onConfirm: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val titleText = stringResource(Res.string.bookmark)
    val contentLabel = stringResource(Res.string.bookmark_content)
    val noteLabel = stringResource(Res.string.bookmark_note)
    val deleteText = stringResource(Res.string.delete)
    val cancelText = stringResource(Res.string.cancel)
    val okText = stringResource(Res.string.ok)

    var bookText by remember { mutableStateOf(bookmark.bookText) }
    var content by remember { mutableStateOf(bookmark.content) }

    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = titleText,
                    onBack = onDismiss,
                )

                // 章节名 (只读展示, 对齐 app 端原版)
                Text(
                    text = bookmark.chapterName,
                    color = colors.primaryText,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                // 可滚动编辑区: 原文 + 备注
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    AppOutlinedTextField(
                        value = bookText,
                        onValueChange = { bookText = it },
                        label = contentLabel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppOutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = noteLabel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 底部按钮栏: 删除(可选) | 弹性间距 | 取消 | 确定
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    if (showDelete && onDelete != null) {
                        AppTextButton(text = deleteText, color = DesignTokens.arcoBlue6) { onDelete() }
                    }
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = cancelText, color = colors.secondaryText) { onDismiss() }
                    AppTextButton(text = okText, color = DesignTokens.arcoBlue6) {
                        // 回传新实例: 原地改字段后引用不变, 调用方 state 判定未变不重组
                        onConfirm(bookmark.copy(bookText = bookText, content = content))
                    }
                }
            }
        }
    }
}

// ===== @Preview 合并自 androidMain 的 book/bookmark/BookmarkPreviews.kt (BookmarkDialog) =====

// ---- BookmarkDialog ----

/**
 * [BookmarkDialog] 与 [AllBookmarkScreen] 的 @Preview。
 *
 * 假数据取 PreviewData.previewBookmarks (跨两本书, 验证按书分组吸顶)。
 */

private val previewBookmark = previewBookmarks.first()

@Preview
@Composable
fun BookmarkDialogPreview() = LegadoThemePreview {
    BookmarkDialog(
        bookmark = previewBookmark,
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
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

@Preview
@Composable
fun BookmarkDialogEmptyNotePreview() = LegadoThemePreview {
    BookmarkDialog(
        bookmark = previewBookmarks[1],
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
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

