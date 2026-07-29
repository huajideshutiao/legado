package io.legado.app.ui.root

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 页面栈抽象：管理 RouteEntry 顺序、id 分配与持久化快照。
 */
class RouteBackStack(
    initialRoute: AppRoute,
    restoredSnapshot: NavigationSnapshot? = null,
) {
    private var nextEntryId: Long = restoredSnapshot?.nextEntryId ?: 1L
    private val _backStack: MutableStateFlow<List<RouteEntry>> = MutableStateFlow(
        restoredSnapshot?.entries?.takeIf { it.isNotEmpty() }
            ?: listOf(newEntry(initialRoute, resultKey = null))
    )
    val backStack: StateFlow<List<RouteEntry>> = _backStack.asStateFlow()

    val size: Int get() = _backStack.value.size

    fun peek(): RouteEntry? = _backStack.value.lastOrNull()

    fun push(route: AppRoute, resultKey: String? = null): RouteEntryId {
        val current = _backStack.value.lastOrNull()
        if (current?.route == route && current.resultKey == resultKey) return current.id
        val entry = newEntry(route, resultKey)
        _backStack.value += entry
        return entry.id
    }

    fun replace(route: AppRoute): RouteEntryId {
        val old = _backStack.value.last()
        val entry = newEntry(route, old.resultKey)
        _backStack.value = _backStack.value.dropLast(1) + entry
        return entry.id
    }

    fun resetRoot(route: AppRoute.Main) {
        _backStack.value = listOf(newEntry(route, resultKey = null))
    }

    fun pop(): Boolean {
        val entries = _backStack.value
        if (entries.size <= 1) return false
        _backStack.value = entries.dropLast(1)
        return true
    }

    fun popTo(predicate: (RouteEntry) -> Boolean, inclusive: Boolean = false): Boolean {
        val entries = _backStack.value
        val index = entries.indexOfFirst(predicate)
        if (index < 0) return false
        val keepCount = if (inclusive) index else index + 1
        if (keepCount <= 0) return false
        _backStack.value = entries.take(keepCount)
        return true
    }

    fun snapshot(): NavigationSnapshot = NavigationSnapshot(
        entries = _backStack.value,
        overlays = emptyList(),
        nextEntryId = nextEntryId,
    )

    private fun newEntry(route: AppRoute, resultKey: String?): RouteEntry = RouteEntry(
        id = RouteEntryId(nextEntryId++),
        route = route,
        resultKey = resultKey,
    )
}
