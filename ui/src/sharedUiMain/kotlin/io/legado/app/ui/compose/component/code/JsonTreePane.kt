package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebastianneubauer.jsontree.JsonTree
import com.sebastianneubauer.jsontree.JsonTreeItem
import com.sebastianneubauer.jsontree.TreeColors
import com.sebastianneubauer.jsontree.defaultLightColors
import io.legado.app.ui.compose.SelectableText
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk

/**
 * JSON 树视图 + 面包屑: 条目菜单的"查看"把树**就地聚焦**到被点节点 (不新开窗口, 故无叠层),
 * 面包屑逐级退回。
 *
 * 聚焦栈由调用方持有 ([focusStack]/[onFocusStackChange]): 宿主需要知道当前聚焦到哪一级
 * (复制/文本视图跟随聚焦节点), 且聚焦栈的生命周期应与宿主内容绑定。
 *
 * 聚焦后树的根换成该节点的解析产物 (经 [JsonTreeItem.subtreeElement] 直通), 不再解析文本。
 * 原始值条目没有子树, 同样进聚焦栈 (面包屑照常显示可退回), 正文区改为该值的可选文本。
 *
 * @param json 原始 JSON 文本, 仅作文本视图/复制与树的缓存键用 (聚焦后不再解析它)
 * @param focusStack 聚焦栈, 空 = 显示根; 末项无子树时表示聚焦到原始值
 * @param rootLabel 面包屑首项文案 (通常是对话框标题/响应标题, 即整份文档)
 * @param onError 树解析失败回调, 透传给 [JsonTree]
 */
@Composable
fun JsonTreePane(
    json: String,
    focusStack: List<JsonTreeItem>,
    onFocusStackChange: (List<JsonTreeItem>) -> Unit,
    rootLabel: String,
    modifier: Modifier = Modifier,
    treeModifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: TreeColors = defaultLightColors,
    onError: (Throwable) -> Unit = {},
) {
    // 末项无子树 = 聚焦到原始值: 正文区显示该值文本, 面包屑仍在 (可逐级退回)
    val focusedValue = focusStack.lastOrNull()?.takeIf { it.subtreeElement == null }
    // 聚焦到容器时以该节点的解析产物为树根; 未聚焦时从 [json] 文本解析
    val rootNode = focusStack.lastOrNull { it.subtreeElement != null }
    // 聚焦后条目路径补上聚焦节点前缀, 复制的 JSONPath 始终相对整份文档 (可直接粘回书源规则)
    val currentPathPrefix = rootNode?.path ?: "$"
    // 每个聚焦层级各持一个列表状态: 换层即从顶部开始。行 id 每次重建都由 IdGenerator
    // 从 1 重新编号, 沿用上一层的列表状态会按旧序号定位到内容不同的行 (子树更短时
    // 还会夹到最后一项)。
    val lazyListState = remember(currentPathPrefix) { LazyListState() }
    Column(modifier) {
        if (focusStack.isNotEmpty()) {
            Breadcrumb(rootLabel, focusStack, onFocusStackChange)
        }
        if (focusedValue != null) {
            SelectableText(
                text = focusedValue.value.orEmpty(),
                color = AppTheme.colors.secondaryText,
                fontSize = 15.sp,
                modifier = treeModifier,
            )
        } else {
            JsonTree(
                json = json,
                jsonElement = rootNode?.subtreeElement,
                pathPrefix = currentPathPrefix,
                lazyListState = lazyListState,
                // 聚焦切换会重建树, 解析期给个进度占位 (否则整块空白)
                onLoading = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.colors.accent,
                    )
                },
                onError = onError,
                modifier = treeModifier,
                colors = colors,
                contentPadding = contentPadding,
                // E-Ink 无灰阶过渡, 涟漪留下残影 (本仓其它动画在 E-Ink 下一律关闭)
                showRowIndication = !LocalEInk.current,
                itemMenu = { item, onDismiss ->
                    JsonPathMenu(item, onDismiss) { target ->
                        // 目标就是当前根时不压栈: 树的根未变 (看起来"点了没反应"),
                        // 但面包屑会多一段同名标签且返回键要多按一次
                        if (target.path != currentPathPrefix) {
                            onFocusStackChange(focusStack + target)
                        }
                    }
                },
            )
        }
    }
}

/** 面包屑: 首项为整份文档, 其后每级一个可点标签, 点任意一级即退回该级。 */
@Composable
private fun Breadcrumb(
    rootLabel: String,
    focusStack: List<JsonTreeItem>,
    onFocusStackChange: (List<JsonTreeItem>) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.spacingDefault, vertical = DesignTokens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreadcrumbLabel(rootLabel) { onFocusStackChange(emptyList()) }
        focusStack.forEachIndexed { index, item ->
            Text(
                text = "›",
                color = colors.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            BreadcrumbLabel(item.key ?: item.path) {
                onFocusStackChange(focusStack.take(index + 1))
            }
        }
    }
}

@Composable
private fun BreadcrumbLabel(text: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Text(
        text = text,
        color = colors.accent,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 160.dp)
            .clickable(onClick = onClick),
    )
}
