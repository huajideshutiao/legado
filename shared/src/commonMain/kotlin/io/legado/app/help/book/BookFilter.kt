package io.legado.app.help.book

import io.legado.app.data.entities.BaseBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object BookFilter {

    fun splitQuery(query: String?): List<String> {
        return query?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun matches(book: BaseBook, word: String): Boolean {
        return book.name.contains(word, true)
            || book.author.contains(word, true)
            || book.originName.contains(word, true)
            || book.kind?.contains(word, true) == true
            || book.intro?.contains(word, true) == true
    }

    fun matches(book: BaseBook, words: List<String>): Boolean {
        if (words.isEmpty()) return true
        return words.all { matches(book, it) }
    }

    /**
     * 增量过滤器：仅维护到倒数第二个词的结果（N-1 级缓存）。
     */
    class IncrementalFilter<T : BaseBook>(private val skipFirst: Boolean = false) {
        private var lastBaseList: List<T>? = null
        private var cachedPrefix: List<String> = emptyList()
        private var cachedResult: List<T> = emptyList()

        fun filter(baseList: List<T>, query: String?): List<T> {
            return filter(baseList, splitQuery(query))
        }

        fun filter(baseList: List<T>, words: List<String>): List<T> {
            val filterWords = if (skipFirst && words.isNotEmpty()) words.drop(1) else words

            // 1. 如果原始列表引用变了，清空缓存
            if (baseList !== lastBaseList) {
                lastBaseList = baseList
                cachedPrefix = emptyList()
                cachedResult = baseList
            }

            // 无过滤词，直接返回缓存结果（等于 baseList 或 DB 粗筛结果）
            if (filterWords.isEmpty()) {
                return cachedResult
            }

            val prefixWords = filterWords.dropLast(1)

            // 2. 维护 N-1 缓存
            if (prefixWords != cachedPrefix) {
                cachedResult = if (prefixWords.size > cachedPrefix.size &&
                    prefixWords.subList(0, cachedPrefix.size) == cachedPrefix
                ) {
                    // 情况 B：在原有基础上追加了词，增量更新缓存
                    var r: List<T> = cachedResult
                    prefixWords.drop(cachedPrefix.size).forEach { word ->
                        r = r.filter { matches(it, word) }
                    }
                    r
                } else {
                    // 情况 C：前缀变了（删除、乱序或完全重写），重新从 base 计算 N-1
                    var r: List<T> = baseList
                    prefixWords.forEach { word ->
                        r = r.filter { matches(it, word) }
                    }
                    r
                }
                cachedPrefix = prefixWords
            }

            // 3. 应用最后一个词的过滤（不入缓存）
            return cachedResult.filter { matches(it, filterWords.last()) }
        }
    }
}

/**
 * 响应式增量过滤：在 Flow 闭包中维护 N-1 结果缓存。
 */
fun <T : BaseBook> Flow<Pair<List<T>, List<String>>>.incrementalFilter(skipFirst: Boolean = false): Flow<List<T>> {
    val helper = BookFilter.IncrementalFilter<T>(skipFirst)
    return map { (newList, newKeys) ->
        helper.filter(newList, newKeys)
    }
}
