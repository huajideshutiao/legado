package io.legado.app.data

import io.legado.app.constant.AppPattern
import io.legado.app.utils.cnCompare
import io.legado.app.utils.splitNotBlank

internal fun dealGroups(list: List<String>): List<String> {
    val groups = linkedSetOf<String>()
    list.forEach {
        it.splitNotBlank(AppPattern.splitGroupRegex).forEach { group ->
            groups.add(group)
        }
    }
    return groups.sortedWith { o1, o2 ->
        o1.cnCompare(o2)
    }
}
