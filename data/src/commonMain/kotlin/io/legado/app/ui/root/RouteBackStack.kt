package io.legado.app.ui.root

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次入栈的结果。
 *
 * @param entryId 复用或新建的页面身份
 * @param removed 本次被移除的页面 (单例页复用会销毁它上面的页), 调用方负责释放其结果通道与页面数据
 */
data class RoutePushResult(
    val entryId: RouteEntryId,
    val removed: List<RouteEntry> = emptyList(),
)

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

    fun peek(): RouteEntry? = _backStack.value.lastOrNull()

    fun push(
        route: AppRoute,
        resultKey: String? = null,
        resultTargetEntryId: RouteEntryId? = null,
        sharedToken: String? = null,
    ): RouteEntryId {
        val current = _backStack.value.lastOrNull()
        if (
            current?.route == route &&
            current.resultKey == resultKey &&
            current.resultTargetEntryId == resultTargetEntryId
        ) {
            return current.id
        }
        val entry = newEntry(route, resultKey, resultTargetEntryId, sharedToken)
        _backStack.value += entry
        return entry.id
    }

    /**
     * 按 [AppRoute.launchMode] 入栈。
     *
     * 单例模式命中时保留原页面身份 (id 不变, 页面数据与界面状态都不重建), 只替换路由参数;
     * 命中的是 [RouteLaunchMode.SingleTask] 时, 它上面的页面一并移除。
     */
    fun pushWithLaunchMode(
        route: AppRoute,
        resultKey: String? = null,
        sharedToken: String? = null,
    ): RoutePushResult {
        val entries = _backStack.value
        val top = entries.lastOrNull()
        val reusedIndex = when (route.launchMode) {
            RouteLaunchMode.Standard -> -1
            // 栈顶同类即复用 (对照原版 singleTop: 按 Activity 类判栈顶)
            RouteLaunchMode.SingleTop ->
                if (top != null && top.route.samePage(route) && top.resultKey == resultKey) {
                    entries.lastIndex
                } else {
                    -1
                }
            // 全栈查找 (对照原版 singleTask: 复用已存在实例并销毁它上面的页)
            RouteLaunchMode.SingleTask -> entries.indexOfLast { it.route.samePage(route) }
        }
        if (reusedIndex < 0) {
            return RoutePushResult(push(route, resultKey, resultKey?.let { top?.id }, sharedToken))
        }
        val reused = entries[reusedIndex].copy(
            route = route,
            resultKey = resultKey,
            // 结果回传目标是复用页下面那页: 它上面的页即将被移除, 不能再当回传目标
            resultTargetEntryId = resultKey?.let { entries.getOrNull(reusedIndex - 1)?.id },
            sharedToken = sharedToken,
        )
        val removed = entries.subList(reusedIndex + 1, entries.size).toList()
        _backStack.value = entries.subList(0, reusedIndex) + reused
        return RoutePushResult(reused.id, removed)
    }

    fun replace(route: AppRoute): RouteEntryId {
        val old = _backStack.value.last()
        val entry = newEntry(route, old.resultKey, old.resultTargetEntryId)
        _backStack.value = _backStack.value.dropLast(1) + entry
        return entry.id
    }

    fun pop(): Boolean {
        val entries = _backStack.value
        if (entries.size <= 1) return false
        _backStack.value = entries.dropLast(1)
        return true
    }

    fun snapshot(): NavigationSnapshot = NavigationSnapshot(
        entries = _backStack.value,
        overlays = emptyList(),
        nextEntryId = nextEntryId,
    )

    private fun newEntry(
        route: AppRoute,
        resultKey: String?,
        resultTargetEntryId: RouteEntryId? = null,
        sharedToken: String? = null,
    ): RouteEntry = RouteEntry(
        id = RouteEntryId(nextEntryId++),
        route = route,
        resultKey = resultKey,
        resultTargetEntryId = resultTargetEntryId,
        sharedToken = sharedToken,
    )
}
