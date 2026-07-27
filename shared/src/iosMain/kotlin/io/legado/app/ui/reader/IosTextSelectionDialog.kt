package io.legado.app.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.help.copyToClipboard
import io.legado.app.help.openURL
import io.legado.app.help.readFromClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * iOS 端"文字选择"对话框 (对照 desktop TextSelectionDialog)。
 *
 * # 工作原理
 *
 * shared/sharedUiMain 已下沉 ComposeTextToolbar (替代系统 ActionMode 文本菜单),
 * 由 AppTheme 在根 Composable 注入到 LocalTextToolbar, 所有 [SelectionContainer] 自动接管
 * "复制/全选"弹层。
 *
 * iOS 端阅读页正文为自绘 Canvas (PageContentCanvas), 不能套 SelectionContainer
 * (对 Canvas 自绘文字无效), 故改为弹窗形式: 用 [SelectionContainer] 包 [Text] 渲染整章正文,
 * 用户拖选文字后自动弹出"复制/全选"菜单。
 *
 * # 操作菜单
 *
 * - 复制 / 全选: 由 ComposeTextToolbar 自动提供 (用户拖选文字后弹出)
 * - 复制全部 / 复制章节标题: 标题栏 OverflowMenu (用 [copyToClipboard] 写入 UIPasteboard)
 * - 浏览器搜索 / 翻译: 底部按钮 (读 [readFromClipboard] 取关键字, 调 [openURL] 打开系统浏览器)
 * - 关闭: 底部按钮
 *
 * # 与桌面端差异
 *
 * - 无查词按钮 (iOS 端 DictDialog 未下沉);
 * - 剪贴板用 [copyToClipboard] / [readFromClipboard] (对照桌面端 AWT Toolkit.systemClipboard);
 * - 浏览器打开用 [openURL] (对照桌面端 Desktop.browse)。
 *
 * @param chapterName 章节名 (标题 + 复制章节标题用)
 * @param content 章节正文 (整章文字, 用户可拖选)
 * @param onDismiss 关闭回调
 */
@Composable
fun IosTextSelectionDialog(
    chapterName: String,
    content: String,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // i18n: 复用已注册 key; 浏览器搜索 / 翻译 / 复制章节标题 / 文字操作 / 请先选中并复制文字 用硬编码中文字面量
    // (与桌面端 TextSelectionDialog 处理方式一致: 无 R.string 等价物的 key 用硬编码)
    val cancelText = rememberString("cancel")
    val copyAllText = rememberString("content_edit_copy_all")
    val copySuccessText = rememberString("content_edit_copy_success")
    val copyChapterTitleText = "复制章节标题"
    val browserSearchText = "浏览器搜索"
    val translateText = "翻译"
    val titleText = "文字操作"
    val noSelectionHintText = "请先选中并复制文字"

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.background,
            modifier = Modifier.fillMaxWidth(0.8f),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = chapterName.ifBlank { titleText },
                    onBack = onDismiss,
                    actions = {
                        OverflowMenu { dismissMenu ->
                            DropdownMenuItem(
                                text = { Text(copyAllText, color = colors.primaryText) },
                                onClick = {
                                    dismissMenu()
                                    copyToClipboard("$chapterName\n$content")
                                    Toasters.get().toast(copySuccessText)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(copyChapterTitleText, color = colors.primaryText) },
                                onClick = {
                                    dismissMenu()
                                    copyToClipboard(chapterName)
                                    Toasters.get().toast(copySuccessText)
                                },
                            )
                        }
                    },
                )
                // 内容区: SelectionContainer 包 Text, 用户拖选文字后自动弹 ComposeTextToolbar
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = content,
                            color = colors.primaryText,
                            fontSize = 16.sp,
                        )
                    }
                }
                // 底部按钮栏 (与 ContentEditDialog 风格对齐: 关闭右对齐, 其余左)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTextButton(text = browserSearchText, onClick = {
                        openInBrowser(
                            urlPrefix = "https://www.bing.com/search?q=",
                            noSelectionHint = noSelectionHintText,
                        )
                    })
                    AppTextButton(text = translateText, onClick = {
                        openInBrowser(
                            urlPrefix = "https://translate.google.com/?text=",
                            noSelectionHint = noSelectionHintText,
                        )
                    })
                    Spacer(Modifier.width(4.dp))
                    AppTextButton(text = cancelText, color = colors.secondaryText, onClick = onDismiss)
                }
            }
        }
    }
}

/**
 * 读剪贴板 → 拼接搜索引擎 / 翻译网站 URL → 调 [openURL] 打开系统浏览器。
 *
 * 剪贴板为空时弹 toast 提示 (SelectionContainer 选中后需点 ComposeTextToolbar 的"复制"
 * 按钮才会写入剪贴板)。
 */
private fun openInBrowser(
    urlPrefix: String,
    noSelectionHint: String,
) {
    val query = readFromClipboard()?.takeIf { it.isNotBlank() }
    if (query == null) {
        Toasters.get().toast(noSelectionHint)
        return
    }
    val url = urlPrefix + java.net.URLEncoder.encode(query, "UTF-8")
    openURL(url)
}
