package com.script.quickjs

import androidx.collection.LruCache
import kotlin.math.min

/**
 * 类名前缀二分匹配器。
 *
 * 从 modules/rhino 复制,引擎无关。用于安全名单的类名精确/前缀匹配。
 */
class ClassNameMatcher(classNames: List<String>) {

    private val sortedClassNames = classNames.sorted()
    private val matchCache = LruCache<String, Boolean>(64)

    fun match(className: String): Boolean {
        matchCache[className]?.let {
            return it
        }
        val match = matchInternal(className)
        matchCache.put(className, match)
        return match
    }

    private fun matchInternal(className: String): Boolean {
        val index = sortedClassNames.fastBinarySearch { prefix ->
            comparePrefix(className, prefix)
        }
        if (index >= 0) {
            return true
        }
        val prefix = sortedClassNames.getOrNull(-index - 2) ?: return false
        return className.getOrNull(prefix.length) == '.' && className.startsWith(prefix)
    }

    private fun comparePrefix(className: String, prefix: String): Int {
        val len = min(className.length, prefix.length)
        for (i in 0..<len) {
            val c1 = className[i]
            val c2 = prefix[i]
            if (c1 != c2) return c2 - c1
        }
        return prefix.length - className.length
    }

    private fun <T> List<T>.fastBinarySearch(comparator: (T) -> Int): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val cmp = comparator(get(mid))
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -(low + 1)
    }
}
