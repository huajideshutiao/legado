package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.sebastianneubauer.jsontree.JsonTreeItem
import com.sebastianneubauer.jsontree.defaultDarkColors
import com.sebastianneubauer.jsontree.defaultLightColors
import io.legado.app.ui.compose.SelectableText
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.code.JsonTreePane
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.utils.isJson
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.copy
import legado.ui.generated.resources.json_tree
import legado.ui.generated.resources.json_tree_parse_failed
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.source_text
import legado.ui.generated.resources.text_too_large
import org.jetbrains.compose.resources.stringResource

private const val MAX_TEXT_LENGTH = 32 * 1024

/**
 * 通用文本展示对话框 (四端唯一入口, 日志堆栈详情 / 崩溃日志内容 / 书源源码 / 分类错误详情共用)。
 *
 * 布局 = AppDialog + Surface(fullHeight) + Column(DialogTitleBar 带返回 / 正文 weight(1f) / 按钮行钉底)。
 * 对齐原版 `BaseDialogFragment(R.layout.dialog_text_view)` + `isFullHeight = true` + `setupTitleBar`:
 * 原版日志堆栈详情与崩溃日志内容查看本就是同一个 TextDialog, 故此处只保留一份实现。
 * 按钮行: 复制靠左, 取消/确定靠右 (同 AppAlertDialogContent 的 contextual 槽位布局)。
 *
 * 不用 M2 AlertDialog: 其 BaselineLayout 在 CMP 桌面按"未钳制的标题+正文高"汇报, 长文本时
 * 对话框超 Surface 封顶, 滚动视口 > 可视区, 滚动错位 (内容下移/顶部空白/按钮被推出屏幕外,
 * 用户多轮实测复现)。weight+内部滚动方案视口恒定 (正文区 = 对话框剩余空间)。
 * 正文选择用 [SelectableText] (readOnly BasicTextField, 拖选/拖手柄越界自动滚动,
 * 对齐原版原生 TextView)。
 *
 * @param title 标题栏文案 (原版取文件名 / "Log" / "html" / "ERROR")
 * @param content 正文, 超过 [MAX_TEXT_LENGTH] 截断并追加提示 (对齐原版"数据太大"分支)
 * @param onDismiss 关闭 (返回箭头 / 点击对话框外部 / 取消 / 确定 四处同一回调, 原版无按钮语义区分)
 * @param allowJsonTree 正文形如 JSON (首尾字符配对预判) 时是否提供树形视图切换 (书源调试的
 * 源码就是原始响应体, 可能是 JSON 也可能是 HTML; 默认关, 仅源码查看处开启)
 */
@Composable
fun TextDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
    allowJsonTree: Boolean = false,
) {
    val colors = AppTheme.colors
    val okText = stringResource(Res.string.ok)
    val cancelText = stringResource(Res.string.cancel)
    val copyText = stringResource(Res.string.copy)
    val tooLargeText = stringResource(Res.string.text_too_large)
    val treeText = stringResource(Res.string.json_tree)
    val sourceText = stringResource(Res.string.source_text)
    // 树按钮显隐按首尾字符预判 (isJson 原位判断, 大响应体零解析成本): 首尾配对即出
    // 按钮, 不做合法性解析; 首尾配对但内容非法的误判可接受, 误判切树由树区错误文本
    // 兜底
    val isJson = remember(allowJsonTree, content) {
        allowJsonTree && content.isJson()
    }
    var treeMode by remember { mutableStateOf(false) }
    // 聚焦栈: 条目菜单"查看"把树就地聚焦到该节点, 面包屑逐级退回 (不新开窗口, 故无叠层)
    var focusStack by remember { mutableStateOf<List<JsonTreeItem>>(emptyList()) }
    // 换正文后聚焦栈失效 (旧聚焦指向上一份文档的节点), 就地复位
    LaunchedEffect(content) {
        focusStack = emptyList()
    }

    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        Surface(
            modifier = Modifier.appDialogSize(fullHeight = true),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = title,
                    onBack = onDismiss,
                )
                // 正文区: weight 占标题栏与按钮行之间的剩余空间 (视口恒定), 超长内部滚动
                val displayText = if (content.length >= MAX_TEXT_LENGTH) {
                    content.take(MAX_TEXT_LENGTH) + "\n\n" + tooLargeText
                } else {
                    content
                }
                if (treeMode) {
                    // 首尾配对预判可能误判非法 JSON: 树解析失败时树区改显错误文本, 不留空白
                    var treeError by remember(content) { mutableStateOf<Throwable?>(null) }
                    if (treeError == null) {
                        JsonTreePane(
                            json = content,
                            focusStack = focusStack,
                            onFocusStackChange = { focusStack = it },
                            rootLabel = title,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            treeModifier = Modifier.fillMaxWidth(),
                            colors = if (colors.isDark) defaultDarkColors else defaultLightColors,
                            contentPadding = PaddingValues(horizontal = DesignTokens.spacingDefault),
                            onError = { treeError = it },
                        )
                    }
                    treeError?.let { error ->
                        SelectableText(
                            text = stringResource(
                                Res.string.json_tree_parse_failed,
                                error.message ?: error.toString(),
                            ),
                            color = colors.secondaryText,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = DesignTokens.spacingDefault),
                        )
                    }
                } else {
                    SelectableText(
                        text = displayText,
                        color = colors.secondaryText,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = DesignTokens.spacingDefault),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spacingDefault),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    AppTextButton(text = copyText) {
                        // 复制当前所见: 原始值/聚焦子树/整份正文
                        val focused = focusStack.lastOrNull()
                        val toCopy = focused
                            ?.let { it.subtreeElement?.toString() ?: it.value.orEmpty() }
                            ?: content
                        PlatformCapabilityProviders.get().copyToClipboard(toCopy)
                    }
                    if (isJson) {
                        AppTextButton(
                            text = if (treeMode) sourceText else treeText,
                            color = colors.accent,
                        ) {
                            // 切回文本视图时同时退出聚焦: 文本区显示整份正文, 复制也按整份正文
                            // 取值, 否则会出现"屏幕是全文、复制到的却是聚焦子树"
                            focusStack = emptyList()
                            treeMode = !treeMode
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = cancelText, color = colors.secondaryText) { onDismiss() }
                    AppTextButton(text = okText) { onDismiss() }
                }
            }
        }
    }
}
