package io.legado.app.ui.book.read

// I18N KEYS (need to register in ResourceProvider.jvm.kt):
//   "content_edit_reset" to "重置",
//   "content_edit_copy_all" to "复制全部",
//   "content_edit_copy_success" to "已复制"

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_save
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.content_edit_copy_all
import legado.shared.generated.resources.content_edit_copy_success
import legado.shared.generated.resources.content_edit_reset
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.ic_save
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 章节正文编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.read.ContentEditDialog` (BaseComposeDialogFragment),
 * 但去掉对 Android Fragment / Bundle / ContentEditViewModel / ReadBook 模型 / BookHelp /
 * appDb / AndroidView + AppCompatEditText / alert DSL 的依赖, 改为纯 @Composable + 回调形式:
 * - 调用方传入 [chapterName] + [content], [onSubmit] / [onDismiss] 回调;
 * - [onReset] 可选注入"重置"业务 (从源重新获取正文覆盖本地修改), null 时不渲染重置菜单项;
 * - [clipTextSink] 可选注入剪贴板写入能力, null 时不渲染复制全部菜单项。
 *
 * # 业务对齐 (对照 app 端原版)
 *
 * - 标题栏: 标题 = 章节名, 返回 (dismiss) + 保存 (ic_save) + OverflowMenu (重置 / 复制全部);
 * - 正文区: 多行 OutlinedTextField, maxLines = 10 (替代 app 端 AndroidView + AppCompatEditText,
 *   原版用 View EditText 是为了 layout.getLineForOffset 按阅读进度滚动定位, KMP 版用 Compose
 *   原生 OutlinedTextField, 滚动定位由 Compose 自动处理, 不再需要 View 互操作);
 * - 保存: 校验非空 (与 app 端 save() 中 `contentView?.text?.toString() ?: return` 等价,
 *   空内容直接 return 不回调), 通过则回调 [onSubmit] + [onDismiss];
 * - 重置: 委托 [onReset] (调用方负责从源重新拉取正文并更新 [content] 参数触发重组);
 * - 复制全部: "$chapterName\n$content" + [clipTextSink] 写剪贴板 + toast "已复制"
 *   (与 app 端 `requireContext().sendToClip("$title\n${contentView?.text}")` 等价)。
 *
 * # 与 app 端的差异
 *
 * - 标题栏点击编辑章节名: 由 [onRenameChapter] 回调承载 (调用方负责 appDb 更新 + 重载),
 *   null 时标题不可点击, 与原版标题栏点击改标题对齐 (原版直接依赖 appDb.bookChapterDao);
 * - 返回键 (标题栏返回) 自动保存: 对齐原版 onCancel(dialog) { save() } 语义;
 * - 底部显式"取消"按钮保留丢弃语义 (与返回键保存区分);
 * - 不实现 applyContent 按阅读进度滚动定位 (依赖 AppCompatEditText.layout.getLineForOffset,
 *   Compose OutlinedTextField 不暴露此 API, 滚动定位由用户手动操作)。
 *
 * @param chapterName 章节名 (用于标题 + 复制全部前缀)
 * @param content 章节正文 (用户可编辑)
 * @param onSubmit 用户点击保存且内容非空, 参数为编辑后的正文
 * @param onDismiss 用户取消 (点击"取消"按钮 / 对话框外部)
 * @param onReset 重置回调 (从源重新获取正文), null 时不显示重置菜单项
 * @param clipTextSink 剪贴板文本写入器 (替代 `context.sendToClip(text)`), null 时不显示复制全部菜单项
 * @param onRenameChapter 章节重命名回调 (参数为新标题, 调用方负责落库 + 刷新),
 *   null 时标题栏不可点击编辑 (对齐原版标题栏点击改章节标题)
 */
@Composable
fun ContentEditDialog(
    chapterName: String,
    content: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
    clipTextSink: ((String) -> Unit)? = null,
    onRenameChapter: ((String) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    // 所有字符串一次性在 @Composable 主体内 rememberString, 避免 onClick 中误用 @Composable
    val saveDescText = stringResource(Res.string.action_save)
    val resetText = stringResource(Res.string.content_edit_reset)
    val copyAllText = stringResource(Res.string.content_edit_copy_all)
    val copySuccessText = stringResource(Res.string.content_edit_copy_success)
    val cancelText = stringResource(Res.string.cancel)
    val okText = stringResource(Res.string.ok)
    val editText = stringResource(Res.string.edit)

    // 标题本地 state: 重命名成功后由回调更新, chapterName 参数变化 (外部重载) 时重新同步
    var titleState by remember(chapterName) { mutableStateOf(chapterName) }
    LaunchedEffect(chapterName) {
        titleState = chapterName
    }
    // 标题编辑子对话框开关 (原版 titleBar.toolbar 点击 → alert 编辑标题)
    var showTitleEdit by remember { mutableStateOf(false) }
    var titleEditState by remember { mutableStateOf(titleState) }

    // 本地编辑 state: content 参数变化时 (如 reset 后调用方更新 content) 重新初始化
    var contentState by remember(content) { mutableStateOf(content) }
    // content 参数变化时同步 state (用于 onReset 后调用方更新 content 触发重组)
    LaunchedEffect(content) {
        contentState = content
    }

    /**
     * 保存, 与 app 端 save() 完全等价:
     * - 内容为空时直接 return (与 app 端 `contentView?.text?.toString() ?: return` 等价);
     * - 通过则回调 [onSubmit] + [onDismiss]。
     */
    fun save() {
        val text = contentState
        if (text.isEmpty()) return
        onSubmit(text)
        onDismiss()
    }

    Surface(
        shape = DesignTokens.dialogShape,
        color = colors.fillet,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = titleState,
                // 返回键自动保存 (对照原版 onCancel(dialog) { save() })
                onBack = { save() },
                titleClickable = onRenameChapter != null,
                onTitleClick = {
                    titleEditState = titleState
                    showTitleEdit = true
                },
                actions = {
                    IconButton(onClick = { save() }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_save),
                            contentDescription = saveDescText,
                            tint = DesignTokens.arcoBlue6,
                        )
                    }
                    OverflowMenu { dismissMenu ->
                        // 重置: 仅当调用方提供 onReset 时渲染 (依赖 ContentEditViewModel.reset, 调用方注入)
                        if (onReset != null) {
                            DropdownMenuItem(
                                onClick = {
                                    dismissMenu()
                                    onReset()
                                },
                            ) {
                                Text(resetText, color = colors.primaryText)
                            }
                        }
                        // 复制全部: 仅当调用方提供 clipTextSink 时渲染 (与 app 端 sendToClip 等价)
                        if (clipTextSink != null) {
                            DropdownMenuItem(
                                onClick = {
                                    dismissMenu()
                                    // 与 app 端 sendToClip("$title\n${contentView?.text}") 等价
                                    clipTextSink("$titleState\n$contentState")
                                    Toasters.get().toast(copySuccessText)
                                },
                            ) {
                                Text(copyAllText, color = colors.primaryText)
                            }
                        }
                    }
                },
            )
            // 正文区: Box 包裹留作未来扩展 (原 app 端有 CircularProgressIndicator loading 指示器);
            // 不用 weight (Column 无固定高度时 weight 不生效), 让 TextField 按 maxLines=10 自然计算高度
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                // 多行输入, maxLines = 10 (任务硬要求); 走 AppTextField 统一 MD2 视觉
                // maxLines=10 时 TextField 内部自动滚动, 无需外层 verticalScroll
                AppTextField(
                    value = contentState,
                    onValueChange = { contentState = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 10,
                    textStyle = LocalTextStyle.current.copy(
                        color = colors.primaryText,
                        fontSize = 16.sp,
                    ),
                )
            }
            // 底部按钮栏 (与 SourceFilterEditDialog 风格对齐: 取消左 + 确定右)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(cancelText, color = colors.secondaryText)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { save() }) {
                    Text(okText, color = DesignTokens.arcoBlue6)
                }
            }
        }
    }
    // 标题编辑子对话框 (对照原版 editTitle: alert + 单行输入, 确认后回调调用方落库 + 刷新)
    if (showTitleEdit && onRenameChapter != null) {
        AppDialog(
            onDismissRequest = { showTitleEdit = false },
            properties = AppDialogSizes.properties(),
        ) {
            Surface(
                shape = DesignTokens.dialogShape,
                color = colors.fillet,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    DialogTitleBar(title = editText, onBack = { showTitleEdit = false })
                    AppTextField(
                        value = titleEditState,
                        onValueChange = { titleEditState = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { showTitleEdit = false }) {
                            Text(cancelText, color = colors.secondaryText)
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = {
                            showTitleEdit = false
                            titleState = titleEditState
                            onRenameChapter(titleEditState)
                        }) {
                            Text(okText, color = DesignTokens.arcoBlue6)
                        }
                    }
                }
            }
        }
    }
}

// ===== @Preview 合并自 androidMain 的 book/read/ReadDialogsPreviews.kt (ContentEditDialog) =====

// ===== ContentEditDialog =====

@Preview
@Composable
fun ContentEditDialogPreview() = LegadoThemePreview {
    ContentEditDialog(
        chapterName = "第一章 科学边界",
        content = buildString {
            appendLine("物理学在这一切之中扮演了什么角色?")
            appendLine("杨冬在心中默默问自己。")
            appendLine("她看着窗外, 那颗恒星的影像已经在屏幕上消散,")
            appendLine("只剩下空荡荡的宇宙, 像一个无声的嘲弄。")
        },
        onSubmit = {},
        onDismiss = {},
        onReset = {},
        clipTextSink = {},
    )
}

@Preview
@Composable
fun ContentEditDialogLongContentPreview() = LegadoThemePreview {
    ContentEditDialog(
        chapterName = "第二章 疯狂年代",
        content = buildString {
            repeat(30) { i ->
                appendLine("第 ${i + 1} 段: 这是一段用于测试长正文滚动展示效果的占位内容, ")
                appendLine("用于验证 OutlinedTextField 在 maxLines=10 时的滚动行为。")
            }
        },
        onSubmit = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ContentEditDialogEmptyContentPreview() = LegadoThemePreview {
    ContentEditDialog(
        chapterName = "空章节",
        content = "",
        onSubmit = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ContentEditDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ContentEditDialog(
        chapterName = "第一章 科学边界",
        content = "物理学在这一切之中扮演了什么角色?",
        onSubmit = {},
        onDismiss = {},
        onReset = {},
        clipTextSink = {},
    )
}
