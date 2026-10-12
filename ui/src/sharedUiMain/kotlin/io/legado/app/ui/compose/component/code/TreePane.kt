package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.treeview.JsonTreeItem
import io.legado.treeview.defaultDarkColors
import io.legado.treeview.defaultLightColors
import io.legado.app.ui.compose.SelectableText
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.isJson
import io.legado.app.utils.isXml
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.html_tree_parse_failed
import legado.ui.generated.resources.json_tree_parse_failed
import org.jetbrains.compose.resources.stringResource

/** 树视图类型: JSON 与 HTML 按正文首尾字符配对互斥判定, 同一正文只会出一种树 */
enum class TreeKind { JSON, HTML }

/**
 * 按正文首尾非空白字符配对预判树类型: `{}`/`[]` 判 JSON, `<>` 判 HTML, 其余无树。
 *
 * 只取首尾两个字符, 不做合法性解析 (超大响应体零解析成本)。非法 JSON 的误判由树区错误文本
 * 兜底; ksoup 对任意输入几乎不抛错, 误判的 HTML 会渲染成规范化后的树而非报错。
 *
 * 全部树视图入口共用此判定, 同一正文在任何入口的树按钮显隐一致。
 */
fun treeKindOf(content: String): TreeKind? = when {
    content.isJson() -> TreeKind.JSON
    content.isXml() -> TreeKind.HTML
    else -> null
}

/**
 * [TreePane] 的聚焦状态: 两类树各持一个聚焦栈 + 解析错误。
 *
 * 宿主需要读聚焦节点时提升 (全文对话框按聚焦节点复制); 不需要时用 [TreePane] 的默认值即可。
 * 由 [rememberTreePaneState] 创建, 键含正文: 换正文即换实例 (聚焦栈与解析错误都指向旧文档)。
 */
@Stable
class TreePaneState {
    internal var jsonFocus by mutableStateOf<List<JsonTreeItem>>(emptyList())
    internal var htmlFocus by mutableStateOf<List<HtmlNode.Element>>(emptyList())
    internal var error by mutableStateOf<Throwable?>(null)

    /** 聚焦节点对应的可复制文本 (JSON 取聚焦子树/原始值, HTML 取 outerHtml); null = 未聚焦 */
    val copyText: String?
        get() = when {
            jsonFocus.isNotEmpty() ->
                jsonFocus.last().let { it.subtreeElement?.toString() ?: it.value.orEmpty() }
            htmlFocus.isNotEmpty() -> htmlFocus.last().ksoupElement.outerHtml()
            else -> null
        }

    /** 回到未进树的状态: 清聚焦与解析错误。宿主切回文本视图时调用 (文本区显示整份正文,
     *  复制也按整份正文取值, 否则会出现"屏幕是全文、复制到的却是聚焦子树"; 解析错误一并
     *  清掉, 下次进树重新解析) */
    fun reset() {
        jsonFocus = emptyList()
        htmlFocus = emptyList()
        error = null
    }
}

@Composable
fun rememberTreePaneState(content: String): TreePaneState = remember(content) { TreePaneState() }

/**
 * 树视图统一入口: 按 [kind] 分派 JSON 树 / HTML 树, 两者交互形态一致 (折叠展开 + 条目菜单
 * "查看"就地聚焦 + 面包屑逐级退回), 解析失败时就地显示错误文本, 不留空白。
 *
 * 调用方先用 [treeKindOf] 判出类型 (为 null 即无树可出), 再按同一类型取值传入 [kind]。
 *
 * @param content 原始文本 (JSON 或 HTML), 树只解析一次 (展开/聚焦只重压平)
 * @param kind 树类型, 决定走哪棵树与错误文案
 * @param rootLabel 面包屑首项文案 (通常是对话框标题/响应标题, 即整份文档)
 * @param modifier 外层容器 (全文对话框带 weight 占满剩余高度, 行内不限高)
 * @param treeModifier 树区/错误文本区 (行内限高滚动, 全文占满)
 * @param state 聚焦状态, 默认内部持有
 */
@Composable
fun TreePane(
    content: String,
    kind: TreeKind,
    rootLabel: String,
    modifier: Modifier = Modifier,
    treeModifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: TreePaneState = rememberTreePaneState(content),
) {
    val colors = AppTheme.colors
    val treeColors = if (colors.isDark) defaultDarkColors else defaultLightColors
    val error = state.error
    Column(modifier) {
        if (error != null) {
            SelectableText(
                text = stringResource(
                    if (kind == TreeKind.JSON) {
                        Res.string.json_tree_parse_failed
                    } else {
                        Res.string.html_tree_parse_failed
                    },
                    error.message ?: error.toString(),
                ),
                color = colors.secondaryText,
                fontSize = 15.sp,
                modifier = treeModifier.padding(horizontal = DesignTokens.spacingDefault),
            )
        } else {
            when (kind) {
                TreeKind.JSON -> JsonTreePane(
                    json = content,
                    focusStack = state.jsonFocus,
                    onFocusStackChange = { state.jsonFocus = it },
                    rootLabel = rootLabel,
                    modifier = Modifier.fillMaxWidth(),
                    treeModifier = treeModifier,
                    contentPadding = contentPadding,
                    colors = treeColors,
                    onError = { state.error = it },
                )

                TreeKind.HTML -> HtmlTreePane(
                    html = content,
                    focusStack = state.htmlFocus,
                    onFocusStackChange = { state.htmlFocus = it },
                    rootLabel = rootLabel,
                    modifier = Modifier.fillMaxWidth(),
                    treeModifier = treeModifier,
                    contentPadding = contentPadding,
                    colors = treeColors,
                    onError = { state.error = it },
                )
            }
        }
    }
}
