package io.legado.app.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.SelectableText
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.encodeURI
import io.legado.app.utils.isAbsUrl
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookmark
import legado.shared.generated.resources.browser
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.content_edit_copy_all
import legado.shared.generated.resources.content_edit_copy_success
import legado.shared.generated.resources.copy
import legado.shared.generated.resources.copy_chapter_title
import legado.shared.generated.resources.lookup_word
import legado.shared.generated.resources.read_aloud
import legado.shared.generated.resources.replace
import legado.shared.generated.resources.search_content
import legado.shared.generated.resources.select_and_copy_first_hint
import legado.shared.generated.resources.share
import legado.shared.generated.resources.text_action
import org.jetbrains.compose.resources.stringResource

/**
 * 跨端"文字选择"对话框 (desktop / iOS / 鸿蒙共享, 下沉自三份平行实现)。
 *
 * 阅读页正文为自绘 Canvas (PageContentCanvas), 不能套 [SelectionContainer]
 * (对 Canvas 自绘文字无效), 故改为弹窗形式: 用 [SelectableText] (readOnly BasicTextField)
 * 渲染整章正文, 用户拖选文字后由 sharedUiMain 已下沉的 ComposeTextToolbar (AppTheme 注入
 * LocalTextToolbar) 自动弹出"复制/全选"菜单, 复刻 app 端 ActionMode 文字选择 + TextActionMenu。
 * (拖选/拖手柄越界自动滚动由 BasicTextField 原生提供, 对齐 master 分支原生 TextView)
 *
 * # 操作菜单 (对照原版 TextActionMenu 的 content_select_action.xml 菜单项,
 * 全部按钮集中底部一排, 顺序同原版: 替换/复制/书签/朗读/查词/全文搜索/浏览器/分享 + 关闭)
 *
 * - 复制 / 全选: 由 ComposeTextToolbar 自动提供 (用户拖选文字后弹出)
 * - 复制全部 / 复制章节标题: 标题栏 OverflowMenu (经 [clipTextSink] 写系统剪贴板)
 * - 替换 / 复制 / 书签 / 朗读 / 查词 / 全文搜索 / 浏览器 / 分享: 底部一排按钮,
 *   分别经 [onReplace] / [onBookmark] / [onReadAloud] / [onDict] / [onSearchContent] /
 *   [onShare] 回调由调用方处理 (原版 menu_replace/menu_copy/menu_bookmark/menu_aloud/
 *   menu_dict/menu_search_content/menu_browser/menu_share_str, 执行后关闭对话框);
 *   浏览器按原版 menu_browser 语义: 选中文本是 URL 直接打开, 否则经 [openUrl] 打开
 *   系统搜索引擎 (无独立"浏览器搜索"按钮, 原版只有一个浏览器项)
 * - 关闭: 同上排在末尾
 *
 * 浏览器搜索按钮读取剪贴板的设计原因: 文本选择组件的选区信息不对外暴露,
 * 外部不易拿到; 简化为"用户选中 → 点 ComposeTextToolbar 的'复制' → 剪贴板有内容 →
 * 点底部按钮执行后续操作"。
 *
 * @param chapterName 章节名 (标题 + 复制章节标题用)
 * @param content 章节正文 (整章文字, 用户可拖选)
 * @param onDismiss 关闭回调
 * @param clipTextProvider 读剪贴板文本 (desktop AWT / iOS UIPasteboard / 鸿蒙 pasteboard 桥)
 * @param clipTextSink 写剪贴板文本 (供"复制全部 / 复制章节标题"写入)
 * @param openUrl 打开外链 (desktop browseUrl / iOS openURL / 鸿蒙 OpenUrlProviders)
 * @param onDict 查词回调 (读剪贴板内容为关键字后触发, 由调用方弹出 DictDialog;
 *   默认空实现不影响现有调用, 避免本文件直接依赖 DictDialog 造成耦合)
 * @param onReplace 替换回调: 参数为选中文本, 由调用方打开替换规则编辑页预填 pattern+scope
 *   (对照原版 menu_replace → ReplaceEditActivity.startIntent(pattern=选中文本))
 * @param onBookmark 书签回调: 参数为选中文本, 由调用方用选中文本建书签并弹 BookmarkDialog
 *   (对照原版 menu_bookmark)
 * @param onReadAloud 朗读回调: 参数为选中文本, 由调用方朗读选中文字 (对照原版 menu_aloud)
 * @param onSearchContent 全文搜索回调: 参数为选中文本, 由调用方设置搜索词并打开全文搜索
 *   (对照原版 menu_search_content)
 * @param onShare 分享回调: 参数为选中文本, 由调用方调系统分享 (对照原版 menu_share_str)
 * @param selectedText 页内文字选择结果 (非 null 时本对话框切换为"选中文本菜单"形态:
 *   内容区展示选中文本, 底部动作直接以选中文本为参数, 并新增"复制"按钮;
 *   对照旧 TextActionMenu 的 menu_copy; null = 旧整章拖选形态)
 * @param surfaceModifier 对话框 Surface 尺寸约束 (默认统一钳制: 宽 0.9 屏宽上限 800dp, 高不超 0.7 屏高)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextSelectionDialog(
    chapterName: String,
    content: String,
    onDismiss: () -> Unit,
    clipTextProvider: () -> String?,
    clipTextSink: (String) -> Unit,
    openUrl: (String) -> Unit,
    onDict: (String) -> Unit = {},
    onReplace: (String) -> Unit = {},
    onBookmark: (String) -> Unit = {},
    onReadAloud: (String) -> Unit = {},
    onSearchContent: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    selectedText: String? = null,
    surfaceModifier: Modifier = Modifier.appDialogSize(),
) {
    val colors = AppTheme.colors
    val cancelText = stringResource(Res.string.cancel)
    val copyText = stringResource(Res.string.copy)
    val copyAllText = stringResource(Res.string.content_edit_copy_all)
    val copySuccessText = stringResource(Res.string.content_edit_copy_success)
    val copyChapterTitleText = stringResource(Res.string.copy_chapter_title)
    val dictText = stringResource(Res.string.lookup_word)
    val replaceText = stringResource(Res.string.replace)
    val bookmarkText = stringResource(Res.string.bookmark)
    val readAloudText = stringResource(Res.string.read_aloud)
    val searchContentText = stringResource(Res.string.search_content)
    val shareText = stringResource(Res.string.share)
    val browserText = stringResource(Res.string.browser)
    val titleText = stringResource(Res.string.text_action)
    val noSelectionHintText = stringResource(Res.string.select_and_copy_first_hint)

    // 选中文本菜单形态: 动作直接取选中文本, 不再依赖剪贴板 (对照旧 TextActionMenu 的
    // onActionItemClicked 用 SelectionInfo.text; 原整章形态因 SelectionContainer 选区
    // 无法外部读取才改走剪贴板)
    val useSelectedText = selectedText != null

    /** 取动作参数文本, 为空时 toast 提示并返回 null */
    fun selectedTextOrNull(): String? {
        val text = (selectedText ?: clipTextProvider())?.takeIf { it.isNotBlank() }
        if (text == null) {
            Toasters.get().toast(noSelectionHintText)
        }
        return text
    }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.fillet,
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
                // 内容区: SelectableText (readOnly BasicTextField) 渲染整章/选中文本,
                // 长按拖选后自动弹 ComposeTextToolbar; 拖选/拖手柄越界自动滚动 (对齐原生 TextView)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .padding(16.dp),
                ) {
                    SelectableText(
                        text = selectedText ?: content,
                        color = colors.primaryText,
                        fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 底部按钮栏: 全部动作集中一排 (对照原版 TextActionMenu 的 content_select_action.xml
                // 菜单项顺序: 替换/复制/书签/朗读/查词/全文搜索/浏览器/分享 + 关闭; 2026-08-05 用户
                // 反馈: 原实现把查词/浏览器搜索/关闭单独右对齐拆成一行, 且"浏览器搜索"与"浏览器"
                // 重复 —— 现合并为单排 FlowRow, 只保留原版菜单项)
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    // 替换 (原版 menu_replace)
                    AppTextButton(text = replaceText, onClick = {
                        selectedTextOrNull()?.let { text ->
                            onReplace(text)
                            onDismiss()
                        }
                    })
                    if (useSelectedText) {
                        // 复制选中文本 (原版 menu_copy) 并收菜单
                        AppTextButton(text = copyText, onClick = {
                            selectedTextOrNull()?.let { text ->
                                clipTextSink(text)
                                Toasters.get().toast(copySuccessText)
                                onDismiss()
                            }
                        })
                    }
                    // 书签 (原版 menu_bookmark)
                    AppTextButton(text = bookmarkText, onClick = {
                        selectedTextOrNull()?.let { text ->
                            onBookmark(text)
                            onDismiss()
                        }
                    })
                    // 朗读 (原版 menu_aloud)
                    AppTextButton(text = readAloudText, onClick = {
                        selectedTextOrNull()?.let { text ->
                            onReadAloud(text)
                            onDismiss()
                        }
                    })
                    // 查词 (原版 menu_dict): 由调用方弹 DictDialog
                    AppTextButton(text = dictText, onClick = {
                        val query = selectedTextOrNull()
                        if (query != null) {
                            onDict(query)
                            onDismiss()
                        }
                    })
                    // 全文搜索 (原版 menu_search_content)
                    AppTextButton(text = searchContentText, onClick = {
                        selectedTextOrNull()?.let { text ->
                            onSearchContent(text)
                            onDismiss()
                        }
                    })
                    // 浏览器 (原版 menu_browser): URL 直接打开, 非 URL 走系统搜索引擎
                    AppTextButton(text = browserText, onClick = {
                        val text = selectedTextOrNull() ?: return@AppTextButton
                        if (text.isAbsUrl()) {
                            openUrl(text)
                        } else {
                            openInBrowser(
                                urlPrefix = "https://www.bing.com/search?q=",
                                query = text,
                                openUrl = openUrl,
                            )
                        }
                        onDismiss()
                    })
                    // 分享 (原版 menu_share_str)
                    AppTextButton(text = shareText, onClick = {
                        selectedTextOrNull()?.let { text ->
                            onShare(text)
                            onDismiss()
                        }
                    })
                    // 关闭
                    AppTextButton(
                        text = cancelText,
                        color = colors.secondaryText,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

/**
 * [encodeURI] 拼接搜索引擎 URL → [openUrl] 打开系统浏览器。
 *
 * 文本源为空的提示已由调用方的 selectedTextOrNull 承担 (它取不到文本就 toast 并返回 null),
 * 这里只收非空关键字, 不再二次读取剪贴板。
 */
private fun openInBrowser(
    urlPrefix: String,
    query: String,
    openUrl: (String) -> Unit,
) {
    openUrl(urlPrefix + query.encodeURI())
}
