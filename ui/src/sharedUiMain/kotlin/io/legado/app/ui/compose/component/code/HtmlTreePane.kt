package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.treeview.TreeColors
import io.legado.treeview.defaultLightColors
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk

/**
 * HTML 树视图 + 面包屑: 条目菜单的"查看"把树**就地聚焦**到被点元素 (不新开窗口, 故无叠层),
 * 面包屑逐级退回。形态对齐 [JsonTreePane]。
 *
 * 聚焦栈由调用方持有 ([focusStack]/[onFocusStackChange]): 宿主需要知道当前聚焦到哪一级
 * (复制跟随聚焦节点取 outerHtml), 且聚焦栈的生命周期应与宿主内容绑定。
 *
 * @param html 原始 HTML 文本, 树只解析一次 (展开/聚焦只重压平)
 * @param focusStack 聚焦栈, 空 = 显示整份文档
 * @param rootLabel 面包屑首项文案 (通常是对话框标题/响应标题, 即整份文档)
 * @param onError 树解析失败回调, 透传给 [HtmlTree]
 */
@Composable
fun HtmlTreePane(
    html: String,
    focusStack: List<HtmlNode.Element>,
    onFocusStackChange: (List<HtmlNode.Element>) -> Unit,
    rootLabel: String,
    modifier: Modifier = Modifier,
    treeModifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: TreeColors = defaultLightColors,
    onError: (Throwable) -> Unit = {},
) {
    val focusRoot = focusStack.lastOrNull()
    // 每个聚焦层级各持一个列表状态: 换层即从顶部开始 (同 JsonTreePane)
    val lazyListState = remember(focusRoot?.id) { LazyListState() }
    Column(modifier) {
        if (focusStack.isNotEmpty()) {
            TreeBreadcrumb(
                rootLabel = rootLabel,
                labels = focusStack.map { it.tagName },
                onRootClick = { onFocusStackChange(emptyList()) },
                onLabelClick = { index -> onFocusStackChange(focusStack.take(index + 1)) },
            )
        }
        HtmlTree(
            html = html,
            displayRoot = focusRoot,
            contentPadding = contentPadding,
            colors = colors,
            lazyListState = lazyListState,
            modifier = treeModifier,
            // E-Ink 无灰阶过渡, 涟漪留下残影 (本仓其它动画在 E-Ink 下一律关闭)
            showRowIndication = !LocalEInk.current,
            itemMenu = { node, onDismiss ->
                HtmlNodeMenu(node, onDismiss) { target ->
                    // 目标就是当前根时不压栈: 树的根未变 (看起来"点了没反应"),
                    // 但面包屑会多一段同名标签且返回键要多按一次。
                    // 聚焦栈存目标的完整真实路径 (含未点过的中间祖先), 面包屑即真实路径
                    if (target.id != focusRoot?.id) {
                        onFocusStackChange(target.chain())
                    }
                }
            },
            // 聚焦切换会重压平, 解析/压平期给个进度占位 (否则整块空白)
            onLoading = {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = AppTheme.colors.accent,
                )
            },
            onError = onError,
        )
    }
}
