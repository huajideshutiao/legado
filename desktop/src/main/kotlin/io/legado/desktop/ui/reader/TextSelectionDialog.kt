package io.legado.desktop.ui.reader

import androidx.compose.foundation.layout.widthIn
import io.legado.desktop.ui.component.DialogSizes
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.browseUrl
import java.net.URLEncoder

/**
 * 桌面端"文字选择"对话框。
 *
 * 替代简化版"复制标题/内容" AlertDialog (原 [ReaderScreen.kt] 内 showLongClickMenu 分支),
 * 补齐 desktop 端文字选择功能, 对照 app 端 ActionMode 文字选择 + TextActionMenu
 * (自绘 PopupWindow, 见 app/.../ui/book/read/TextActionMenu.kt)。
 *
 * # 工作原理
 *
 * shared/sharedUiMain 已下沉 [io.legado.app.ui.compose.theme.ComposeTextToolbar]
 * (替代系统 ActionMode 文本菜单), 由 [AppTheme] 在根 Composable 注入到
 * [androidx.compose.ui.platform.LocalTextToolbar] (AppTheme.kt L146-L150 +
 * L160 调 `textToolbar.Host()`), 所有 [SelectionContainer] / TextField 自动接管
 * "复制/全选"弹层。
 *
 * desktop 端阅读页正文为自绘 Canvas
 * ([io.legado.app.ui.book.read.page.PageContentCanvas]), 不能套 [SelectionContainer]
 * (对 Canvas 自绘文字无效), 故改为弹窗形式:
 * 用 [SelectionContainer] 包 [Text] 渲染整章正文, 用户拖选文字后自动弹出"复制/全选"菜单
 * (ComposeTextToolbar 复刻 app 端 TextActionMenu 视觉: 8dp 圆角 / 44dp 条高 /
 * 按压 0.12 alpha / E-Ink 描边降级)。
 *
 * # 操作菜单
 *
 * - 复制 / 全选: 由 ComposeTextToolbar 自动提供 (用户拖选文字后弹出)
 * - 复制全部: 标题栏 OverflowMenu (整章写入系统剪贴板, 对齐 app 端 `menu_copy` 语义)
 * - 复制章节标题: 标题栏 OverflowMenu (保留原简化版 AlertDialog 的功能)
 * - 查词: 底部按钮 (读剪贴板内容为关键字, 调 [onDict] 回调弹出 [DictDialog], 对照 app 端 DictDialog)
 * - 浏览器搜索: 底部按钮 (读剪贴板内容为关键字, 调 [Desktop.browse] 打开搜索引擎)
 * - 翻译: 底部按钮 (读剪贴板内容为关键字, 调 [Desktop.browse] 打开翻译网站)
 * - 关闭: 底部按钮
 *
 * 浏览器搜索 / 翻译按钮读取剪贴板的设计原因:
 * [SelectionContainer] 选中文字通过 [androidx.compose.ui.platform.LocalTextToolbar]
 * 自动复制到系统剪贴板, 但选区信息通过 SelectionRegistrar internal API 暴露, 外部不易拿到。
 * 简化为"用户选中 → 点 ComposeTextToolbar 的'复制' → 剪贴板有内容 → 点底部按钮执行后续操作"。
 *
 * # 不实现项 (对照 app 端 TextActionMenu)
 *
 * - 朗读: 依赖 TTS 集成, 通过 TtsControlPanel 已有入口
 * - 替换: 依赖 ReplaceEditActivity + selectedText 作 pattern, 桌面端未对接参数路由
 * - 书签: 依赖 BookStorageProviders + Bookmark 数据模型, 暂不实现
 * - 分享: desktop 无原生 share Intent, 用"复制全部"替代 (剪贴板即可粘贴到任意应用)
 *
 * @param chapterName 章节名 (标题 + 复制章节标题用)
 * @param content 章节正文 (整章文字, 用户可拖选)
 * @param onDismiss 关闭回调
 * @param clipTextProvider 读剪贴板文本 (供"查词 / 浏览器搜索 / 翻译"按钮取关键字)
 * @param clipTextSink 写剪贴板文本 (供"复制全部 / 复制章节标题"写入)
 * @param onDict 查词回调 (读剪贴板内容为关键字后触发, 由调用方弹出 DictDialog;
 *   默认空实现不影响现有调用, 避免本文件直接依赖 DictDialog 造成耦合)
 */
@Composable
fun TextSelectionDialog(
    chapterName: String,
    content: String,
    onDismiss: () -> Unit,
    clipTextProvider: () -> String?,
    clipTextSink: (String) -> Unit,
    onDict: (String) -> Unit = {},
) {
    val colors = AppTheme.colors
    // i18n: 文案全部走 rememberString (cancel / content_edit_copy_all / content_edit_copy_success
    // / text_action 复用已注册 key; copy_chapter_title / lookup_word / browser_search /
    // translate / select_and_copy_first_hint 为桌面端 key, 由 sharedStringTable 统一补)
    val cancelText = rememberString("cancel")
    val copyAllText = rememberString("content_edit_copy_all")
    val copySuccessText = rememberString("content_edit_copy_success")
    val copyChapterTitleText = rememberString("copy_chapter_title")
    val dictText = rememberString("lookup_word")
    val browserSearchText = rememberString("browser_search")
    val translateText = rememberString("translate")
    val titleText = rememberString("text_action")
    val noSelectionHintText = rememberString("select_and_copy_first_hint")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.background,
            modifier = Modifier.widthIn(max = DialogSizes.dialogMaxWidth()),
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
                                    // 与 app 端 menu_copy 语义一致: 复制整章 (前缀章节名 + 换行)
                                    clipTextSink("$chapterName\n$content")
                                    Toasters.get().toast(copySuccessText)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(copyChapterTitleText, color = colors.primaryText) },
                                onClick = {
                                    dismissMenu()
                                    clipTextSink(chapterName)
                                    Toasters.get().toast(copySuccessText)
                                },
                            )
                        }
                    },
                )
                // 内容区: SelectionContainer 包 Text, 用户拖选文字后自动弹 ComposeTextToolbar
                // (8dp 圆角 / 44dp 条高, 复刻 app 端 TextActionMenu 视觉)
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
                    // 对照 app 端 TextActionMenu 查词: app 端直接弹 DictDialog, 桌面端通过回调解耦
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
                            noSelectionHint = noSelectionHintText,
                        )
                    })
                    AppTextButton(text = translateText, onClick = {
                        openInBrowser(
                            urlPrefix = "https://translate.google.com/?text=",
                            clipTextProvider = clipTextProvider,
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
 * 读剪贴板 → 拼接搜索引擎 / 翻译网站 URL → 调 [Desktop.browse] 打开系统默认浏览器。
 *
 * 剪贴板为空时弹 toast 提示 [noSelectionHint] (SelectionContainer 选中后需点
 * ComposeTextToolbar 的"复制"按钮才会写入剪贴板)。
 */
private fun openInBrowser(
    urlPrefix: String,
    clipTextProvider: () -> String?,
    noSelectionHint: String,
) {
    val query = clipTextProvider()?.takeIf { it.isNotBlank() }
    if (query == null) {
        Toasters.get().toast(noSelectionHint)
        return
    }
    val url = urlPrefix + URLEncoder.encode(query, "UTF-8")
    browseUrl(url)
}
