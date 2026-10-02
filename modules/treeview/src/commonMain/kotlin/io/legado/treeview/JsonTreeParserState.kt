package io.legado.treeview

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonElement

@Immutable
internal sealed interface JsonTreeParserState {
    data object Loading : JsonTreeParserState

    /**
     * 按引用相等而非结构相等: 本类作为 Composable 参数参与比较 (`JsonTreeList`), 而 [list]
     * 是元素为 data class 的 List, 结构相等是 O(n) 逐元素比较 —— 每次展开/折叠/聚焦都会
     * 产生新 list, 每次都付这笔开销。两个字段同源变化 (不会出现同 list + 不同 jsonElement),
     * 故引用相等语义足够。
     */
    class Ready(
        val list: List<JsonTreeElement>,
        /**
         * 解析产物根节点: 渲染树由它转换而来, 保留引用使宿主可按节点 path 下钻取子树
         * (如"查看子级"), 免重建与重解析。
         */
        val jsonElement: JsonElement,
    ) : JsonTreeParserState

    sealed interface Parsing : JsonTreeParserState {
        data class Error(val throwable: Throwable) : Parsing
    }
}
