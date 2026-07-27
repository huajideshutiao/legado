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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/** Arco Design arcoblue-6 主色 (#165DFF)。 */
private val ArcoBlue6 = Color(0xFF165DFF)

/** Arco Design arco_radius_lg = 16dp。 */
private val ArcoRadiusLg = 16.dp

/**
 * 书签编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.bookmark.BookmarkDialog`, 但去掉对
 * BaseComposeDialogFragment / Bundle / GSON / appDb 的依赖, 改为纯 @Composable + 回调形式:
 * - 展示章节名 (只读), 编辑书签原文 (bookText) 和备注内容 (content)
 * - 编辑态 (showDelete=true) 露出删除按钮, 调用 [onDelete]
 * - 确认时把修改后的 bookmark 通过 [onConfirm] 回传, 调用方负责入库
 *
 * @param bookmark 待编辑的书签 (bookText/content 会被修改后通过 onConfirm 回传)
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
    val titleText = rememberString("bookmark")
    val contentLabel = rememberString("bookmark_content")
    val noteLabel = rememberString("bookmark_note")
    val deleteText = rememberString("delete")
    val cancelText = rememberString("cancel")
    val okText = rememberString("ok")

    var bookText by remember { mutableStateOf(bookmark.bookText) }
    var content by remember { mutableStateOf(bookmark.content) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(ArcoRadiusLg),
            color = colors.background,
            modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                    )
                    AppOutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = noteLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                    )
                }

                // 底部按钮栏: 删除(可选) | 弹性间距 | 取消 | 确定
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    if (showDelete && onDelete != null) {
                        AppTextButton(text = deleteText, color = ArcoBlue6) { onDelete() }
                    }
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = cancelText, color = colors.secondaryText) { onDismiss() }
                    AppTextButton(text = okText, color = ArcoBlue6) {
                        // 对齐 app 端: 直接修改 bookmark 的 bookText/content 字段后回传
                        bookmark.bookText = bookText
                        bookmark.content = content
                        onConfirm(bookmark)
                    }
                }
            }
        }
    }
}
