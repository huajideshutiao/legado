package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.treeview.JsonTree
import io.legado.treeview.JsonTreeItem
import io.legado.treeview.TreeColors
import io.legado.treeview.defaultLightColors
import io.legado.app.ui.compose.SelectableText
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSONPath 切段: "$" 丢弃 (面包屑首项即文档根), 其余按 "." 切, 数组下标尾缀并回前段,
 * 引号键内的 "." 不切。
 * "$.data.list[0]" → ["data", "list[0]"]; "$['a.b'].c" → ["['a.b']", "c"]
 */
internal fun splitJsonPath(path: String): List<String> {
    var rest = path.removePrefix("$").removePrefix(".")
    if (rest.isEmpty()) return emptyList()
    val segments = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuote = false
    var quoteChar = ' '
    for (c in rest) {
        when {
            !inQuote && (c == '\'' || c == '"') -> {
                inQuote = true
                quoteChar = c
                sb.append(c)
            }
            inQuote && c == quoteChar -> {
                inQuote = false
                sb.append(c)
            }
            !inQuote && c == '.' -> {
                if (sb.isNotEmpty()) {
                    segments.add(sb.toString())
                    sb.clear()
                }
            }
            else -> sb.append(c)
        }
    }
    if (sb.isNotEmpty()) segments.add(sb.toString())
    return segments
}

/**
 * JSON 树视图 + 面包屑: 条目菜单的"查看"把树**就地聚焦**到被点节点 (不新开窗口, 故无叠层),
 * 面包屑逐级即真实 JSONPath 路径段 (含被跳过的中间层)。
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
    // 整份文档解析产物根 (JsonTree 首次从文本解析后经 onRootParsed 回传), 中间层跳转的下钻起点
    val jsonRoot = remember(json) { mutableStateOf<JsonElement?>(null) }
    Column(modifier) {
        if (focusStack.isNotEmpty()) {
            // 真实路径: 聚焦根的 JSONPath 切段补齐被跳过的中间层; 已聚焦层直接退回,
            // 跳过的中间层按下钻构造完整聚焦链 (对齐 HTML 树的 chain() 语义)
            val pathItem = focusStack.last()
            val segments = remember(pathItem.path) { splitJsonPath(pathItem.path) }
            val segmentPaths = remember(segments) {
                List(segments.size) { i -> joinJsonPathPrefix(segments.take(i + 1)) }
            }
            // jsonRoot 是整份解析产物, 不入 remember 键: JsonObject.equals 是整树深比较,
            // 入键会让每次重组都遍历整棵树。只读 jsonRoot.value 即建立订阅, 根本身到达后
            // 本行随重组重算; 单次成本是按路径逐层下钻 (O(段数)), 无需缓存。
            val root = jsonRoot.value
            val enabledLabels = segmentPaths.mapIndexed { index, prefix ->
                focusStack.any { it.path == prefix } ||
                        (root != null &&
                                buildJsonPathChain(root, segments.take(index + 1)) != null)
            }
            TreeBreadcrumb(
                rootLabel = rootLabel,
                labels = segments,
                enabledLabels = enabledLabels,
                onRootClick = { onFocusStackChange(emptyList()) },
                onLabelClick = { index ->
                    val stackIndex = focusStack.indexOfFirst { it.path == segmentPaths[index] }
                    if (stackIndex >= 0) {
                        onFocusStackChange(focusStack.take(stackIndex + 1))
                    } else {
                        buildJsonPathChain(jsonRoot.value, segments.take(index + 1))
                            ?.let { onFocusStackChange(it) }
                    }
                },
            )
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
                onRootParsed = { root ->
                    if (jsonRoot.value == null) jsonRoot.value = root
                },
            )
        }
    }
}

/**
 * 按切段拼接 JSONPath 前缀, 规则对齐 vendored `toJsonPath()`: 键段加点,
 * 以 [ 开头的下标/引号键段不加点 ("$.a.list[0]" / "$[0].x" / "$['a.b']")。
 * 前缀必须与树自产的 JsonTreeItem.path 逐字一致, 否则面包屑命中与复制的 JSONPath 会分叉。
 */
internal fun joinJsonPathPrefix(segments: List<String>): String =
    "$" + segments.joinToString("") { seg -> if (seg.startsWith("[")) seg else "." + seg }

/**
 * 按切段前缀从整份文档解析产物逐级下钻, 构造完整聚焦链 (每级一个 [JsonTreeItem],
 * 对齐 HTML 树的 chain()); 下钻失败 (路径与文档不符) 返回 null。
 * 原始值层对齐 vendored 原始值聚焦语义: subtreeElement=null + value 文本。
 */
internal fun buildJsonPathChain(root: JsonElement?, segments: List<String>): List<JsonTreeItem>? {
    if (root == null) return null
    val items = ArrayList<JsonTreeItem>(segments.size)
    var current: JsonElement = root
    segments.forEachIndexed { index, segment ->
        val (key, indices) = parsePathSegment(segment) ?: return null
        key?.let { name ->
            current = (current as? JsonObject)?.get(name) ?: return null
        }
        for (idx in indices) {
            current = (current as? JsonArray)?.getOrNull(idx) ?: return null
        }
        items += if (current is JsonPrimitive) {
            JsonTreeItem(
                path = joinJsonPathPrefix(segments.take(index + 1)),
                key = segment,
                value = current.content,
                quotedValue = current.toString(),
                subtreeElement = null,
            )
        } else {
            JsonTreeItem(
                path = joinJsonPathPrefix(segments.take(index + 1)),
                key = segment,
                value = null,
                quotedValue = null,
                subtreeElement = current,
            )
        }
    }
    return items
}

private val ARRAY_INDEX_REGEX = Regex("\\[(\\d+)\\]")

/** 段内下标提取 (如 "[0][1]"); 混入非数字段视为不可解析, 返回 null */
private fun extractArrayIndices(text: String): List<Int>? {
    val matches = ARRAY_INDEX_REGEX.findAll(text).toList()
    if (matches.isEmpty()) return null
    return matches.map { it.groupValues[1].toIntOrNull() ?: return null }
}

/**
 * 面包屑切段回解为 (键名, 下标序列): "list[0]" → ("list", [0]),
 * "['a.b']" → ("a.b", []), "[0]" → (null, [0]), "[0][1]" → (null, [0, 1]), "data" → ("data", []);
 * 不可解析段 (如非数字非引号的 [a], 或 toJsonPath 的无解键名字面量) 返回 null → 面包屑置灰,
 * 绝不产出"导航无进展"的空解 (根级下标段曾因此静默丢下标、错焦)。
 */
private fun parsePathSegment(segment: String): Pair<String?, List<Int>>? {
    if (segment.startsWith("[")) {
        val inner = segment.substring(1, segment.length - 1)
        // 引号键段 (toJsonPath 对特殊键名用方括号字面量): 键名还原仅用于"复制键",
        // 引号嵌套判定失败时落到正则分支返回 null (置灰), 不产出错焦导航
        if ((inner.startsWith("'") && inner.endsWith("'")) ||
            (inner.startsWith("\"") && inner.endsWith("\""))
        ) {
            return inner.substring(1, inner.length - 1) to emptyList()
        }
        // 纯下标段 (可多下标): 段必须被下标完整覆盖, 否则不可解析
        val matches = ARRAY_INDEX_REGEX.findAll(segment).toList()
        if (matches.isEmpty() || matches.sumOf { it.value.length } != segment.length) return null
        val indices = matches.map { it.groupValues[1].toIntOrNull() ?: return null }
        return null to indices
    }
    val bracketStart = segment.indexOf('[')
    if (bracketStart < 0) return segment to emptyList()
    val key = segment.take(bracketStart)
    if (key.isEmpty()) return null
    val indices = extractArrayIndices(segment.substring(bracketStart)) ?: return null
    return key to indices
}
