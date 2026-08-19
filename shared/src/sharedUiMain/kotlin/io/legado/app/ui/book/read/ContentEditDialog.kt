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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
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
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.coroutines.launch
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
 * - 正文区: 多行输入框不限制行数与最大高度 (对齐原版 EditText 无 maxLines): 输入框 wrap content
 *   随内容自然增高, 正文区 verticalScroll 承载超高内容 (替代 app 端 AndroidView + AppCompatEditText,
 *   原版用 View EditText 是为了 layout.getLineForOffset 按阅读进度滚动定位, KMP 版用 Compose
 *   原生 OutlinedTextField, 滚动定位由 Compose 自动处理, 不再需要 View 互操作);
 * - 保存: 校验非空 (与 app 端 save() 中 `contentView?.text?.toString() ?: return` 等价,
 *   空内容直接 return 不回调), 通过则回调 [onSubmit] + [onDismiss];
 * - 关闭: 返回键/外部点击/系统返回直接关闭不保存 (2026-08 变更: 顶部已有保存按钮,
 *   关闭即放弃未保存的修改; 原版 onCancel 自动保存的语义不再沿用);
 * - 重置: 委托 [onReset] (调用方负责从源重新拉取正文并更新 [content] 参数触发重组);
 * - 复制全部: "$chapterName\n$content" + [clipTextSink] 写剪贴板 + toast "已复制"
 *   (与 app 端 `requireContext().sendToClip("$title\n${contentView?.text}")` 等价)。
 *
 * # 与 app 端的差异
 *
 * - 标题栏点击编辑章节名: 由 [onRenameChapter] 回调承载 (调用方负责 appDb 更新 + 重载),
 *   null 时标题不可点击, 与原版标题栏点击改标题对齐 (原版直接依赖 appDb.bookChapterDao);
 * - 返回键 (标题栏返回) / 外部点击 / 系统返回直接关闭不保存: 顶部已有保存按钮 (ic_save),
 *   关闭即放弃未保存修改 (原版 onCancel(dialog) { save() } 自动保存语义已废弃);
 * - 无底部按钮栏 (2026-08 删除迁移期添加的"取消/确定"栏): 外部点击/系统返回直接 onDismiss()
 *   关闭不保存, 空内容时保存按钮同样直接 return 不关闭 (与 save() 空内容语义一致);
 * - 不实现 applyContent 按阅读进度滚动定位 (依赖 AppCompatEditText.layout.getLineForOffset,
 *   Compose OutlinedTextField 不暴露此 API, 滚动定位由用户手动操作)。
 *
 * @param chapterName 章节名 (用于标题 + 复制全部前缀)
 * @param content 章节正文 (用户可编辑); 传入 [contentLoader] 时仅作初始占位 (加载中由转圈覆盖,
 *   加载完成后以 loader 结果为准), 不传 loader 时直接展示本参数 (同步路径 / Preview)
 * @param onSubmit 用户保存且内容非空, 参数为编辑后的正文
 * @param onDismiss 关闭对话框 (返回键/外部点击/系统返回时直接调用, 不保存)
 * @param onReset 重置回调: 内容重载完成后刷新阅读器 (对照原版 menu_reset 回调里的
 *   ReadBook.loadContent), null 时不显示重置菜单项
 * @param contentLoader 章节全文加载器 (对照原版 ContentEditViewModel.initContent): 返回当前章节
 *   完整正文 (已处理, 不含标题), null 表示读不到内容。传入时对话框自动异步加载并显示转圈
 *   (对齐原版 rlLoading); 参数 reset=true 时先删缓存并重新拉取再读取 (对照原版 menu_reset)。
 *   null 时走同步 [content] 路径
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
    contentLoader: (suspend (reset: Boolean) -> String?)? = null,
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

    // 异步加载态 (对齐原版 loadStateLiveData + rlLoading): 传入 contentLoader 时进入加载态,
    // 加载中正文区显示转圈覆盖输入框, 完成前不展示可编辑内容。
    // 注意: 不能以 contentLoader 实例作 remember key —— 调用方每轮重组都会新建 lambda,
    // 会导致 loading 反复重置为 true 卡在转圈; 只以「是否传入 loader」决定初始态
    var loading by remember { mutableStateOf(contentLoader != null) }
    val scope = rememberCoroutineScope()

    /**
     * 加载章节全文 (对照原版 ContentEditViewModel.initContent):
     * 初次打开 [reset]=false 读取缓存处理; 重置 [reset]=true 先删缓存重拉再读取。
     */
    fun loadContent(reset: Boolean) {
        val loader = contentLoader ?: return
        loading = true
        scope.launch {
            contentState = loader(reset) ?: ""
            loading = false
        }
    }

    // 初次打开: 异步加载章节全文 (对照原版 onFragmentCreated → viewModel.initContent(chapter)),
    // 加载完成前转圈覆盖 (对齐原版 rlLoading); 同步路径 (无 loader) 直接展示 content 参数
    LaunchedEffect(Unit) {
        loadContent(reset = false)
    }

    /**
     * 重置: 重新拉取正文并更新输入框, 完成后刷新阅读器
     * (对照原版 menu_reset → initContent(reset=true) { setText + ReadBook.loadContent })。
     */
    fun resetContent() {
        if (contentLoader == null) {
            // 同步路径 (Preview): 无 loader 时仅触发调用方刷新, 输入框内容不变
            onReset?.invoke()
            return
        }
        loading = true
        scope.launch {
            contentState = contentLoader(true) ?: ""
            loading = false
            // 正文重拉完成后刷新阅读器 (对照原版 initContent 回调里 ReadBook.loadContent,
            // 此时缓存已就绪, 阅读器从缓存装载不重复下载)
            onReset?.invoke()
        }
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

    // 原版 ContentEditDialog: BaseDialogFragment + isFullHeight=true (窗口 0.9 宽 × 0.7 屏高居中,
    // filletBackground 8dp 圆角, 带 dim); dialog_content_edit.xml 根 match_parent 全高, 标题栏 + 正文 weight 撑满;
    // 无底部按钮栏, 外部取消/返回仅关闭不保存 (2026-08 变更: 顶部保存按钮负责落库)
    AppDialog(
        onDismissRequest = { onDismiss() },
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(fullHeight = true),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = titleState,
                    // 返回键直接关闭不保存 (2026-08 变更: 原 onCancel 自动保存改为关闭不保存)
                    onBack = { onDismiss() },
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
                                // 普通按钮色 (与返回箭头一致), 不主题色着色 (2026-08 变更)
                                tint = colors.primaryText,
                            )
                        }
                        OverflowMenu { dismissMenu ->
                            // 重置: 仅当调用方提供 onReset 时渲染 (依赖 ContentEditViewModel.reset, 调用方注入);
                            // 点击后重拉正文更新输入框 + 刷新阅读器 (对照原版 menu_reset → initContent(reset=true))
                            if (onReset != null) {
                                DropdownMenuItem(
                                    onClick = {
                                        dismissMenu()
                                        resetContent()
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
                // 正文区: weight(1f) 撑满剩余空间 (原版 FrameLayout weight=1, 空内容时整区可点击聚焦),
                // verticalScroll 承载超高内容 (2026-08 变更: 输入框随内容增高, 超高时正文区滚动);
                // 内边距 arco_spacing_md=12dp (对齐 XML content_view padding)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    // 输入框不限制行数与最大高度 (对齐原版 EditText 无 maxLines): wrap content
                    // 随内容自然增高, 不再固定高度内部滚动 (2026-08 变更: 正文区滚动替代输入框内部滚动)
                    AppTextField(
                        value = contentState,
                        onValueChange = { contentState = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            color = colors.primaryText,
                            fontSize = 16.sp,
                        ),
                    )
                    // 加载中居中转圈覆盖输入框 (对齐原版 rlLoading CircularProgressIndicator)
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
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
                        modifier = Modifier.fillMaxWidth(),
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
