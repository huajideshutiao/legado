package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Comment
import com.fleeksoft.ksoup.nodes.DataNode
import com.fleeksoft.ksoup.nodes.DocumentType
import com.fleeksoft.ksoup.nodes.Element as KsoupElement
import com.fleeksoft.ksoup.nodes.TextNode
import com.sebastianneubauer.jsontree.TreeColors
import com.sebastianneubauer.jsontree.TreeRow
import com.sebastianneubauer.jsontree.generated.resources.Res as JsontreeRes
import com.sebastianneubauer.jsontree.generated.resources.jsontree_arrow_right
import org.jetbrains.compose.resources.vectorResource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos

/**
 * HTML 树行模型: 与 JSON 树 (vendored jsontree) 同一交互形态, 数据源是 ksoup 解析的 DOM。
 *
 * [Element] 是唯一的可折叠行 (可聚焦子树), 其余是叶子行。构建期已跳过纯空白文本节点
 * (DevTools 同口径); [Element.ksoupElement] 持 ksoup 原节点引用, 供菜单直取
 * outerHtml/innerHtml/文本, CSS 选择器也沿 ksoup 父链生成。
 */
sealed class HtmlNode {
    abstract val id: Long
    abstract val level: Int

    class Element(
        override val id: Long,
        override val level: Int,
        val ksoupElement: KsoupElement,
        val tagName: String,
        val idAttr: String?,
        val classNames: List<String>,
        /** HTML 规范 void 元素 (br/img/... 无闭合标签) */
        val isVoid: Boolean,
        /** 唯一子节点是文本时的内联紧凑显示 (DevTools 同款: <p>text</p> 单行, 不可折叠) */
        val inlineText: String?,
        /** 同名兄弟中的 1-based 序号 (nth-of-type 用), 构建期预存 */
        val position: Int,
        /** 同名兄弟总数 */
        val sameNameSiblings: Int,
        val children: List<HtmlNode>,
    ) : HtmlNode() {
        /** 父包装节点 (构建期由父回填, 之后只读); 顶层 (html) 为 null */
        var parent: HtmlNode.Element? = null
            internal set
    }

    /**
     * 闭合标签行 (展开元素的末尾, DevTools 同款 </tag> 独立行)。负 id 与元素行互斥
     * (元素 id 恒为正), LazyColumn key 唯一。
     */
    class EndTag(
        val element: Element,
        override val level: Int,
    ) : HtmlNode() {
        override val id: Long get() = -element.id
    }

    class Text(
        override val id: Long,
        override val level: Int,
        /** 折叠连续空白并截断的预览 */
        val preview: String,
        /** 原样全文 (菜单"复制文本"取值) */
        val fullText: String,
    ) : HtmlNode()

    class Comment(
        override val id: Long,
        override val level: Int,
        val preview: String,
        val full: String,
    ) : HtmlNode()

    class Doctype(
        override val id: Long,
        override val level: Int,
        val text: String,
    ) : HtmlNode()
}

/** 沿 parent 链从当前节点到文档根 (含自身) 的真实路径, 面包屑与选择器共用这一条链。 */
fun HtmlNode.Element.chain(): List<HtmlNode.Element> =
    generateSequence(this) { it.parent }.toList().asReversed()

/** 可折叠行 (有箭头/点击切换): 非 void、非内联、有子节点。 */
val HtmlNode.Element.isCollapsible: Boolean
    get() = !isVoid && inlineText == null && children.isNotEmpty()

/**
 * 整路径 CSS 选择器 (书源 @CSS: 规则可直接粘贴), 沿 [chain] 拼接。
 * 有 id 的层级不补 nth-of-type (id 唯一); 同名兄弟多于一个时补 :nth-of-type 保证定位唯一。
 */
fun HtmlNode.Element.cssSelector(): String =
    chain()
        .map { e ->
            val cls = e.classNames.firstOrNull()
                ?.let { ".${it}" }
                ?: ""
            when {
                e.idAttr != null -> "${e.tagName}#${e.idAttr}$cls"
                else -> {
                    val base = "${e.tagName}$cls"
                    if (e.sameNameSiblings > 1) "$base:nth-of-type(${e.position})" else base
                }
            }
        }
        .joinToString(" > ")

/**
 * HTML 树解析器: 后台解析 + 按展开集合压平可见行列表 (与 JsonTreeParser 同一调度模型)。
 *
 * 解析产物 [HtmlTreeParserState.Ready.treeRoots] 是全量不可变树, 展开/聚焦只重压平
 * 不重解析。默认展开第一层 (顶层元素可见、其子层折叠, 对齐 JSON 树 FIRST_ITEM_EXPANDED)。
 */
internal class HtmlTreeParser(
    private val html: String,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private var parserState = mutableStateOf<HtmlTreeParserState>(HtmlTreeParserState.Loading)
    val state: State<HtmlTreeParserState> = parserState

    suspend fun init() = withContext(defaultDispatcher) {
        val next = runCatching { parseAndFlatten() }
            .getOrElse { HtmlTreeParserState.Error(it) }
        withContext(mainDispatcher) { parserState.value = next }
    }

    /** 点击折叠行: 增删展开标记并重压平 (O(可见行), 主线程同步——读-改-写单线程串行,
     *  消除跨 dispatcher 竞态: 连点丢切换、在途 toggle 回滚聚焦)。 */
    fun toggle(item: HtmlNode.Element) {
        val current = parserState.value
        check(current is HtmlTreeParserState.Ready)
        val expanded = current.expanded.toMutableSet()
        if (!expanded.add(item.id)) {
            expanded.remove(item.id)
        }
        parserState.value = current.copy(
            visibleRows = flatten(current.displayRoots, expanded),
            expanded = expanded,
        )
    }

    /** 聚焦子树: 以 [root] 为唯一显示根重压平; null = 回到整份文档。展开状态沿用全树集合。 */
    fun focus(root: HtmlNode.Element?) {
        val current = parserState.value
        check(current is HtmlTreeParserState.Ready)
        val displayRoots = if (root == null) current.treeRoots else listOf(root)
        // 聚焦一个处于折叠态的元素时顺带展开它 (对齐 JSON 树聚焦后首层可见), 退回不还原
        val expanded = if (root != null) current.expanded + root.id else current.expanded
        parserState.value = current.copy(
            displayRoots = displayRoots,
            displayRootLevel = root?.level,
            visibleRows = flatten(displayRoots, expanded),
            expanded = expanded,
        )
    }

    private fun parseAndFlatten(): HtmlTreeParserState.Ready {
        val document = Ksoup.parse(html)
        val treeRoots = buildTreeRoots(document)
        val expanded = treeRoots.filterIsInstance<HtmlNode.Element>().mapTo(mutableSetOf()) { it.id }
        return HtmlTreeParserState.Ready(
            treeRoots = treeRoots,
            displayRoots = treeRoots,
            displayRootLevel = null,
            visibleRows = flatten(treeRoots, expanded),
            expanded = expanded,
        )
    }
}

internal sealed class HtmlTreeParserState {
    data object Loading : HtmlTreeParserState()
    data class Ready(
        /** 全量树根 (整份文档的顶层节点), 聚焦恢复用 */
        val treeRoots: List<HtmlNode>,
        /** 当前显示根 (聚焦后是聚焦节点自身) */
        val displayRoots: List<HtmlNode>,
        /** 显示根的层级, 与 [visibleRows] 同快照更新 (缩进归零基准, 杜绝参数/状态跨帧错位) */
        val displayRootLevel: Int?,
        val visibleRows: List<HtmlNode>,
        val expanded: Set<Long>,
    ) : HtmlTreeParserState()
    data class Error(val throwable: Throwable) : HtmlTreeParserState()
}

/** 按 [expanded] 集合把树压平成 LazyColumn 行列表; 展开元素的末尾追加闭合标签行。 */
internal fun flatten(roots: List<HtmlNode>, expanded: Set<Long>): List<HtmlNode> {
    val out = ArrayList<HtmlNode>()
    fun walk(node: HtmlNode) {
        out.add(node)
        if (node is HtmlNode.Element && node.id in expanded && node.isCollapsible) {
            node.children.forEach(::walk)
            out.add(HtmlNode.EndTag(node, node.level))
        }
    }
    roots.forEach(::walk)
    return out
}

private const val PREVIEW_LIMIT = 120

/** HTML 规范 void 元素 (无闭合标签) */
private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
)

/** raw text 元素 (内容是 DataNode 而非 TextNode, ksoup dataTags 同源): 内容不内联, 保持可展开 */
private val RAW_TEXT_TAGS = setOf("script", "style", "xmp", "iframe", "noembed", "noframes")

private fun previewOf(raw: String): String {
    val collapsed = raw.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length > PREVIEW_LIMIT) {
        collapsed.take(PREVIEW_LIMIT) + "…"
    } else {
        collapsed
    }
}

/**
 * ksoup DOM → [HtmlNode] 树。跳过纯空白文本节点; 文档顶层 (Doctype/html 等) 直接作树根,
 * 不引入 #document 包装层。
 */
internal fun buildTreeRoots(document: KsoupElement): List<HtmlNode> {
    // 从 1 起步: EndTag 行 id 取负 (-(element.id)), id 若含 0 则 -0L==0L 与元素行撞 LazyColumn key
    var nextId = 1L

    fun buildElement(
        element: KsoupElement,
        level: Int,
        position: Int,
        sameNameSiblings: Int,
    ): HtmlNode.Element {
        val id = nextId++
        // 同名兄弟定位一次预扫描 (每层一次): 若每个子元素各自重扫兄弟列表,
        // 宽节点 (单父下数千同标签) 会退化为 O(fanout²)
        val childElements = element.childNodes().filterIsInstance<KsoupElement>()
        val sameTotals = HashMap<String, Int>()
        for (child in childElements) {
            sameTotals.merge(child.tagName(), 1, Int::plus)
        }
        val sameSeen = HashMap<String, Int>()
        val positions = IntArray(childElements.size)
        for ((i, child) in childElements.withIndex()) {
            val tag = child.tagName()
            val seen = (sameSeen[tag] ?: 0) + 1
            sameSeen[tag] = seen
            positions[i] = seen
        }
        var elementCursor = 0
        val children = element.childNodes().mapNotNull { child ->
            when (child) {
                is KsoupElement -> {
                    val childElement = childElements[elementCursor++]
                    buildElement(
                        childElement,
                        level + 1,
                        positions[elementCursor - 1],
                        sameTotals[childElement.tagName()] ?: 1,
                    )
                }
                is TextNode -> {
                    if (child.isBlank()) {
                        null
                    } else {
                        HtmlNode.Text(
                            id = nextId++,
                            level = level + 1,
                            preview = previewOf(child.getWholeText()),
                            fullText = child.getWholeText(),
                        )
                    }
                }
                is DataNode -> {
                    // script/style/xmp 等 raw text 元素的内容由 ksoup 建为 DataNode (非 TextNode),
                    // 落到 else 会被静默丢成空元素 (书源页面藏在脚本里的数据不可见)。
                    // 原样显示不折叠空白 (代码缩进有意义), 与 DevTools 对 raw text 的处理一致
                    val raw = child.getWholeData()
                    if (raw.isBlank()) {
                        null
                    } else {
                        HtmlNode.Text(
                            id = nextId++,
                            level = level + 1,
                            preview = if (raw.length > PREVIEW_LIMIT) raw.take(PREVIEW_LIMIT) + "…" else raw,
                            fullText = raw,
                        )
                    }
                }
                is Comment -> HtmlNode.Comment(
                    id = nextId++,
                    level = level + 1,
                    preview = previewOf(child.getData()),
                    full = child.getData(),
                )
                else -> null
            }
        }
        // 唯一子节点是文本 → 内联紧凑行 (DevTools 同款 <p>text</p>), 不再单独占行;
        // raw text 元素 (script/style...) 的内容不内联, 保持可折叠展开看到全文 (DevTools 同款)
        val inlineText = (children.singleOrNull() as? HtmlNode.Text)
            ?.takeIf { element.tagName() !in RAW_TEXT_TAGS }
            ?.let { previewOf(it.fullText) }
        val effectiveChildren = if (inlineText != null) emptyList() else children
        val wrapper = HtmlNode.Element(
            id = id,
            level = level,
            ksoupElement = element,
            tagName = element.tagName(),
            idAttr = element.id().takeIf { it.isNotEmpty() },
            classNames = element.classNames().toList(),
            isVoid = element.tagName() in VOID_ELEMENTS,
            inlineText = inlineText,
            position = position,
            sameNameSiblings = sameNameSiblings,
            children = effectiveChildren,
        )
        effectiveChildren.forEach { if (it is HtmlNode.Element) it.parent = wrapper }
        return wrapper
    }

    return document.childNodes().mapNotNull { node ->
        when (node) {
            is DocumentType -> HtmlNode.Doctype(
                id = nextId++,
                level = 0,
                text = node.outerHtml(),
            )
            is KsoupElement -> buildElement(node, 0, 1, 1)
            is Comment -> HtmlNode.Comment(
                id = nextId++,
                level = 0,
                preview = previewOf(node.getData()),
                full = node.getData(),
            )
            else -> null
        }
    }
}

/**
 * HTML 树视图: 折叠展开 + 行富文本 + 长按/右键菜单, 形态对齐 vendored jsontree 的 JsonTree。
 *
 * @param html 原始 HTML 文本 (解析一次, 展开/聚焦只重压平)
 * @param displayRoot 聚焦根; null = 整份文档
 * @param itemMenu 条目菜单 (长按/右键), null 关闭
 * @param showRowIndication E-Ink 传 false (涟漪留残影)
 */
@Composable
internal fun HtmlTree(
    html: String,
    displayRoot: HtmlNode.Element?,
    contentPadding: PaddingValues,
    colors: TreeColors,
    lazyListState: LazyListState,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    showRowIndication: Boolean = true,
    itemMenu: (@Composable ColumnScope.(HtmlNode, () -> Unit) -> Unit)? = null,
    onLoading: @Composable () -> Unit = {},
    onError: (Throwable) -> Unit = {},
) {
    val parser = remember(html) { HtmlTreeParser(html) }
    val toggleIcon = vectorResource(JsontreeRes.drawable.jsontree_arrow_right)

    LaunchedEffect(parser) {
        parser.init()
    }
    // 聚焦切换 (displayRoot 变化) 后重压平: 树只解析一次, 显示根在 parser 内切换。
    // displayRoot 为 null (退回整文档) 也必须调 focus(null): parser 里残留着上一次的显示根,
    // 跳过会让"点面包屑首项返回最外层"失效 (看似返回的仍是最后一层)。
    // 若宿主以非空 displayRoot 首次组合, init 可能在本 effect 之后才完成, 等 Ready 再压平;
    // 首次组合且未聚焦时 Ready 天然就是全文档, 无需 focus(null)
    LaunchedEffect(parser, displayRoot) {
        if (displayRoot != null) {
            while (parser.state.value !is HtmlTreeParserState.Ready) {
                if (parser.state.value is HtmlTreeParserState.Error) return@LaunchedEffect
                withFrameNanos { }
            }
        } else if (parser.state.value !is HtmlTreeParserState.Ready) {
            return@LaunchedEffect
        }
        parser.focus(displayRoot)
    }

    when (val state = parser.state.value) {
        is HtmlTreeParserState.Loading -> onLoading()
        // 组合提交后才写宿主错误态 (不在组合期改外部状态)
        is HtmlTreeParserState.Error -> SideEffect { onError(state.throwable) }
        is HtmlTreeParserState.Ready -> {
            LazyColumn(
                state = lazyListState,
                contentPadding = contentPadding,
                modifier = modifier,
            ) {
                items(state.visibleRows, key = { it.id }) { node ->
                    HtmlRow(
                        node = node,
                        displayRootLevel = state.displayRootLevel,
                        isExpanded = node.id in state.expanded,
                        colors = colors,
                        icon = toggleIcon,
                        iconSize = iconSize,
                        showRowIndication = showRowIndication,
                        itemMenu = itemMenu,
                        onClick = {
                            if ((node as? HtmlNode.Element)?.isCollapsible == true) {
                                parser.toggle(node)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlRow(
    node: HtmlNode,
    displayRootLevel: Int?,
    isExpanded: Boolean,
    colors: TreeColors,
    icon: ImageVector,
    iconSize: Dp,
    showRowIndication: Boolean,
    itemMenu: (@Composable ColumnScope.(HtmlNode, () -> Unit) -> Unit)?,
    onClick: () -> Unit,
) {
    val text = remember(node, colors, isExpanded) {
        buildHtmlRowText(node, colors, isExpanded)
    }
    // 聚焦后子树行 level 仍带全文档层级, 缩进按显示根的层级归零; coerce 防
    // 参数/状态跨帧错位时负 padding 崩溃 (displayRootLevel 已与行列表同快照, 双保险)
    val depth = (node.level - (displayRootLevel ?: 0)).coerceAtLeast(0)
    val indent = depth * iconSize
    TreeRow(
        indent = indent,
        icon = if ((node as? HtmlNode.Element)?.isCollapsible == true) icon else null,
        iconSize = iconSize,
        iconTint = colors.iconColor,
        iconRotationDegrees = if (isExpanded) 90f else 0f,
        text = text,
        // 与 JSON 树 (jsontree DefaultNodeTextStyle) 同字号同字重, 两种树视觉一致
        textStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
        showRowIndication = showRowIndication,
        onClick = onClick,
        // DevTools 语义: 闭合标签行无菜单
        menu = if (itemMenu != null && node !is HtmlNode.EndTag) {
            { dismiss -> itemMenu(node, dismiss) }
        } else {
            null
        },
        modifier = Modifier.drawBehind {
            // 缩进参考线 (DevTools guides): 每层一条, 对齐该层内容起点
            val step = iconSize.toPx()
            for (i in 0 until depth) {
                val x = i * step + step / 2
                drawLine(colors.indexColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
        },
        // 行内长路径截断, 完整内容靠菜单复制
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 行富文本, DevTools Elements 语义: 起始标签 `<tag key="value">` (属性全显),
 * 折叠头 `…</tag>`, 展开元素的闭合标签独立行, 内联/空/void 元素单行完成。
 * 配色映射 [TreeColors]: 标签=keyColor、属性名=numberValueColor、属性值/文本=stringValueColor、
 * 尖括号/省略号=symbolColor、注释与 Doctype=indexColor。
 */
private fun buildHtmlRowText(
    node: HtmlNode,
    colors: TreeColors,
    isExpanded: Boolean,
): AnnotatedString = buildAnnotatedString {
    when (node) {
        is HtmlNode.Element -> {
            appendStartTag(node, colors)
            when {
                node.isVoid -> withStyle(SpanStyle(color = colors.symbolColor)) { append(">") }
                node.inlineText != null -> {
                    withStyle(SpanStyle(color = colors.symbolColor)) { append(">") }
                    withStyle(SpanStyle(color = colors.stringValueColor)) { append(node.inlineText) }
                    appendEndTag(node, colors)
                }
                node.children.isEmpty() -> {
                    withStyle(SpanStyle(color = colors.symbolColor)) { append(">") }
                    appendEndTag(node, colors)
                }
                !isExpanded -> {
                    withStyle(SpanStyle(color = colors.symbolColor)) { append(">…") }
                    appendEndTag(node, colors)
                }
                else -> withStyle(SpanStyle(color = colors.symbolColor)) { append(">") }
            }
        }
        is HtmlNode.EndTag -> appendEndTag(node.element, colors)
        is HtmlNode.Text -> {
            withStyle(SpanStyle(color = colors.stringValueColor)) { append(node.preview) }
        }
        is HtmlNode.Comment -> {
            withStyle(SpanStyle(color = colors.indexColor)) {
                append("<!--${node.preview}-->")
            }
        }
        is HtmlNode.Doctype -> {
            withStyle(SpanStyle(color = colors.indexColor)) { append(node.text) }
        }
    }
}

private fun AnnotatedString.Builder.appendStartTag(node: HtmlNode.Element, colors: TreeColors) {
    withStyle(SpanStyle(color = colors.symbolColor)) { append("<") }
    withStyle(SpanStyle(color = colors.keyColor)) { append(node.tagName) }
    node.ksoupElement.attributes().asList().forEach { attr ->
        withStyle(SpanStyle(color = colors.symbolColor)) { append(" ${attr.key}=") }
        withStyle(SpanStyle(color = colors.stringValueColor)) { append("\"${attr.value}\"") }
    }
}

private fun AnnotatedString.Builder.appendEndTag(node: HtmlNode.Element, colors: TreeColors) {
    withStyle(SpanStyle(color = colors.symbolColor)) { append("</") }
    withStyle(SpanStyle(color = colors.keyColor)) { append(node.tagName) }
    withStyle(SpanStyle(color = colors.symbolColor)) { append(">") }
}
