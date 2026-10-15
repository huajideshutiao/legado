package io.legado.app.ui.compose.component

/**
 * 生成列表项键: 内容键 + 该内容在列表中的出现序号 (首次出现不带序号)。
 *
 * 供内容可能重复的列表使用 (同一书源把同一本书返回两次): 只按内容作键会撞键闪退。
 * 内容不重复的项在列表增删/重排后键保持不变, 滚动锚点与项内状态不丢; 内容重复的项
 * 键里带出现序号, 在它之前增删同内容项会让序号位移。
 * 键类型为内容键本身或内容键与出现序号的二元组, 二者均可随 Android Bundle 保存。
 */
fun <T, K : Any> occurrenceKeys(items: List<T>, keyOf: (T) -> K): List<Any> {
    val seen = HashMap<K, Int>(items.size)
    return items.map { item ->
        val key = keyOf(item)
        val occurrence = seen.getOrDefault(key, 0)
        seen[key] = occurrence + 1
        if (occurrence == 0) key else key to occurrence
    }
}
