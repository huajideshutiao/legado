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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.encodeURI
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.browser_search
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.content_edit_copy_all
import legado.shared.generated.resources.content_edit_copy_success
import legado.shared.generated.resources.copy_chapter_title
import legado.shared.generated.resources.lookup_word
import legado.shared.generated.resources.select_and_copy_first_hint
import legado.shared.generated.resources.text_action
import legado.shared.generated.resources.translate
import org.jetbrains.compose.resources.stringResource

/**
 * 跨端"文字选择"对话框 (desktop / iOS / 鸿蒙共享, 下沉自三份平行实现)。
 *
 * 阅读页正文为自绘 Canvas (PageContentCanvas), 不能套 [SelectionContainer]
 * (对 Canvas 自绘文字无效), 故改为弹窗形式: 用 [SelectionContainer] 包 [Text] 渲染整章正文,
 * 用户拖选文字后由 sharedUiMain 已下沉的 ComposeTextToolbar (AppTheme 注入 LocalTextToolbar)
 * 自动弹出"复制/全选"菜单, 复刻 app 端 ActionMode 文字选择 + TextActionMenu。
 *
 * # 操作菜单
 *
 * - 复制 / 全选: 由 ComposeTextToolbar 自动提供 (用户拖选文字后弹出)
 * - 复制全部 / 复制章节标题: 标题栏 OverflowMenu (经 [clipTextSink] 写系统剪贴板)
 * - 查词: 底部按钮 (经 [clipTextProvider] 读剪贴板取关键字, 调 [onDict] 回调由调用方弹 DictDialog)
 * - 浏览器搜索 / 翻译: 底部按钮 (读剪贴板取关键字, 经 [openUrl] 打开系统浏览器)
 * - 关闭: 底部按钮
 *
 * 浏览器搜索 / 翻译按钮读取剪贴板的设计原因: [SelectionContainer] 选区信息经
 * SelectionRegistrar internal API 暴露, 外部不易拿到; 简化为"用户选中 → 点
 * ComposeTextToolbar 的'复制' → 剪贴板有内容 → 点底部按钮执行后续操作"。
 *
 * @param chapterName 章节名 (标题 + 复制章节标题用)
 * @param content 章节正文 (整章文字, 用户可拖选)
 * @param onDismiss 关闭回调
 * @param clipTextProvider 读剪贴板文本 (desktop AWT / iOS UIPasteboard / 鸿蒙 pasteboard 桥)
 * @param clipTextSink 写剪贴板文本 (供"复制全部 / 复制章节标题"写入)
 * @param openUrl 打开外链 (desktop browseUrl / iOS openURL / 鸿蒙 OpenUrlProviders)
 * @param onDict 查词回调 (读剪贴板内容为关键字后触发, 由调用方弹出 DictDialog;
 *   默认空实现不影响现有调用, 避免本文件直接依赖 DictDialog 造成耦合)
 * @param surfaceModifier 对话框 Surface 尺寸约束 (默认统一钳制: 宽 0.9 屏宽上限 800dp, 高不超 0.8 屏高)
 */
@Composable
fun TextSelectionDialog(
    chapterName: String,
    content: String,
    onDismiss: () -> Unit,
    clipTextProvider: () -> String?,
    clipTextSink: (String) -> Unit,
    openUrl: (String) -> Unit,
    onDict: (String) -> Unit = {},
    surfaceModifier: Modifier = Modifier.appDialogSize(),
) {
    val colors = AppTheme.colors
    val cancelText = stringResource(Res.string.cancel)
    val copyAllText = stringResource(Res.string.content_edit_copy_all)
    val copySuccessText = stringResource(Res.string.content_edit_copy_success)
    val copyChapterTitleText = stringResource(Res.string.copy_chapter_title)
    val dictText = stringResource(Res.string.lookup_word)
    val browserSearchText = stringResource(Res.string.browser_search)
    val translateText = stringResource(Res.string.translate)
    val titleText = stringResource(Res.string.text_action)
    val noSelectionHintText = stringResource(Res.string.select_and_copy_first_hint)

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = surfaceModifier,
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = chapterName.ifBlank { titleText },
                    onBack = onDismiss,
                    actions = {
                        OverflowMenu { dismissMenu ->
                            DropdownMenuItem(
                                onClick = {
                                    dismissMenu()
                                    // 与 app 端 menu_copy 语义一致: 复制整章 (前缀章节名 + 换行)
                                    clipTextSink("$chapterName\n$content")
                                    Toasters.get().toast(copySuccessText)
                                },
                            ) {
                                Text(copyAllText, color = colors.primaryText)
                            }
                            DropdownMenuItem(
                                onClick = {
                                    dismissMenu()
                                    clipTextSink(chapterName)
                                    Toasters.get().toast(copySuccessText)
                                },
                            ) {
                                Text(copyChapterTitleText, color = colors.primaryText)
                            }
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
                    // 查词: 读剪贴板取词 → 空则 toast 提示, 非空则触发 onDict 回调 (由调用方弹 DictDialog)
                    AppTextButton(text = dictText, onClick = {
                        val query = clipTextProvider()?.takeIf { it.isNotBlank() }
                        if (query == null) {
                            Toasters.get().toast(noSelectionHintText)
                        } else {
                            onDict(query)
                        }
                    })
                    AppTextButton(text = browserSearchText, onClick = {
                        openInBrowser(
                            urlPrefix = "https://www.bing.com/search?q=",
                            clipTextProvider = clipTextProvider,
                            openUrl = openUrl,
                            noSelectionHint = noSelectionHintText,
                        )
                    })
                    AppTextButton(text = translateText, onClick = {
                        openInBrowser(
                            urlPrefix = "https://translate.google.com/?text=",
                            clipTextProvider = clipTextProvider,
                            openUrl = openUrl,
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
 * 读剪贴板 → [encodeURI] 拼接搜索引擎 / 翻译网站 URL → [openUrl] 打开系统浏览器。
 *
 * 剪贴板为空时弹 toast 提示 (SelectionContainer 选中后需点 ComposeTextToolbar 的"复制"
 * 按钮才会写入剪贴板)。
 */
private fun openInBrowser(
    urlPrefix: String,
    clipTextProvider: () -> String?,
    openUrl: (String) -> Unit,
    noSelectionHint: String,
) {
    val query = clipTextProvider()?.takeIf { it.isNotBlank() }
    if (query == null) {
        Toasters.get().toast(noSelectionHint)
        return
    }
    openUrl(urlPrefix + query.encodeURI())
}
