package io.legado.app.ui.root

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
@JvmInline
value class RouteEntryId(val value: Long)

@Serializable
data class RouteEntry(
    val id: RouteEntryId,
    val route: AppRoute,
    val resultKey: String? = null,
    val resultTargetEntryId: RouteEntryId? = null,
)

@Serializable
sealed interface AppOverlay {
    val key: String

    @Serializable
    data class Dialog(
        override val key: String,
        val payload: String? = null,
        val dismissOnBack: Boolean = true,
    ) : AppOverlay

    @Serializable
    data class Sheet(
        override val key: String,
        val payload: String? = null,
        val dismissOnBack: Boolean = true,
    ) : AppOverlay
}

@Serializable
data class NavigationSnapshot(
    val entries: List<RouteEntry>,
    val overlays: List<AppOverlay>,
    val nextEntryId: Long,
)

data class RouteResult(val key: String, val payload: RouteResultPayload = RouteResultPayload.None)

/**
 * Overlay 结果: 携带 overlay key 与回传 payload。
 *
 * Overlay 关闭时 (dismissOverlay(key, payload) 或 pop(payload) 关闭顶层 overlay) 通过
 * [AppNavigator.overlayResults] 推送, 调用方按 key 过滤消费。
 */
data class OverlayResult(val key: String, val payload: RouteResultPayload = RouteResultPayload.None)

/**
 * 应用级唯一导航状态源：页面栈、返回目标、结果回填和 Overlay 栈统一存放。
 */
class AppNavigator(
    initialRoute: AppRoute = AppRoute.Main(),
    restoredSnapshot: NavigationSnapshot? = null,
) {
    private val routeBackStack = RouteBackStack(initialRoute, restoredSnapshot)
    private val overlayBackStack = OverlayBackStack(restoredSnapshot?.overlays.orEmpty())

    val backStack: StateFlow<List<RouteEntry>> = routeBackStack.backStack
    val overlays: StateFlow<List<AppOverlay>> = overlayBackStack.overlays

    private val _results = MutableSharedFlow<RouteResult>(extraBufferCapacity = 16)
    val results: SharedFlow<RouteResult> = _results.asSharedFlow()
    private val targetedResults = backStack.value.associate { entry ->
        entry.id to Channel<RouteResult>(Channel.UNLIMITED)
    }.toMutableMap()

    // Overlay 结果流: Dialog/Sheet 关闭时回传 payload (如分组选择/换封面结果)
    private val _overlayResults = MutableSharedFlow<OverlayResult>(extraBufferCapacity = 16)
    val overlayResults: SharedFlow<OverlayResult> = _overlayResults.asSharedFlow()

    val currentEntry: RouteEntry get() = backStack.value.last()
    val currentRoute: AppRoute get() = currentEntry.route

    private val refreshHandlers = mutableMapOf<RouteEntryId, () -> Unit>()

    fun push(route: AppRoute, resultKey: String? = null): RouteEntryId {
        val current = currentEntry
        if (current.route == route && current.resultKey == resultKey) return current.id
        val entryId = routeBackStack.push(
            route = route,
            resultKey = resultKey,
            resultTargetEntryId = resultKey?.let { currentEntry.id },
        )
        targetedResults.getOrPut(entryId) { Channel(Channel.UNLIMITED) }
        return entryId
    }

    /** 接收只属于指定调用页面的导航结果。每个页面应只收集一次。 */
    fun resultsFor(entryId: RouteEntryId): Flow<RouteResult> {
        check(backStack.value.any { it.id == entryId }) {
            "RouteEntry $entryId is not active"
        }
        return targetedResults
            .getOrPut(entryId) { Channel(Channel.UNLIMITED) }
            .receiveAsFlow()
    }

    fun registerRefreshHandler(entryId: RouteEntryId, handler: () -> Unit) {
        refreshHandlers[entryId] = handler
    }

    fun unregisterRefreshHandler(entryId: RouteEntryId) {
        refreshHandlers.remove(entryId)
    }

    fun refreshCurrent(): Boolean {
        val handler = refreshHandlers[currentEntry.id] ?: return false
        handler()
        return true
    }

    fun replace(route: AppRoute): RouteEntryId {
        val replacedEntryId = currentEntry.id
        val entryId = routeBackStack.replace(route)
        targetedResults.remove(replacedEntryId)?.close()
        refreshHandlers.remove(replacedEntryId)
        targetedResults[entryId] = Channel(Channel.UNLIMITED)
        return entryId
    }

    /** 一级入口切换：清除子页和 Overlay，避免平台自行猜测返回目标。 */
    fun resetRoot(route: AppRoute.Main) {
        routeBackStack.resetRoot(route)
        targetedResults.values.forEach { it.close() }
        targetedResults.clear()
        refreshHandlers.clear()
        targetedResults[currentEntry.id] = Channel(Channel.UNLIMITED)
        overlayBackStack.clear()
    }

    fun pop(payload: RouteResultPayload = RouteResultPayload.None): Boolean {
        // 关闭顶层 Overlay 时, 若 payload 非空则通过 overlayResults 推送 (供调用方按 key 消费)
        val topOverlay = overlayBackStack.peek()
        if (dismissTopOverlay()) {
            if (topOverlay != null && payload !is RouteResultPayload.None) {
                _overlayResults.tryEmit(OverlayResult(topOverlay.key, payload))
            }
            return true
        }
        val removed = routeBackStack.peek()
        val popped = routeBackStack.pop()
        if (popped) {
            removed?.let { removedEntry ->
                targetedResults.remove(removedEntry.id)?.close()
                refreshHandlers.remove(removedEntry.id)
            }
            removed?.resultKey?.let { resultKey ->
                val result = RouteResult(resultKey, payload)
                removed.resultTargetEntryId?.let { targetEntryId ->
                    targetedResults
                        .getOrPut(targetEntryId) { Channel(Channel.UNLIMITED) }
                        .trySend(result)
                }
                _results.tryEmit(result)
            }
        }
        return popped
    }

    fun popTo(entryId: RouteEntryId, inclusive: Boolean = false): Boolean {
        val previousIds = backStack.value.mapTo(mutableSetOf()) { it.id }
        val popped = routeBackStack.popTo({ it.id == entryId }, inclusive)
        if (popped) {
            val activeIds = backStack.value.mapTo(mutableSetOf()) { it.id }
            (previousIds - activeIds).forEach { removedId ->
                targetedResults.remove(removedId)?.close()
            }
            overlayBackStack.clear()
        }
        return popped
    }

    fun showOverlay(overlay: AppOverlay) {
        overlayBackStack.show(overlay)
    }

    fun dismissOverlay(key: String): Boolean =
        overlayBackStack.dismiss(key)

    /** 关闭指定 key 的 Overlay 并回传 payload (通过 [overlayResults] 推送, 调用方按 key 消费)。 */
    fun dismissOverlay(key: String, payload: RouteResultPayload): Boolean {
        val dismissed = overlayBackStack.dismiss(key)
        if (dismissed && payload !is RouteResultPayload.None) {
            _overlayResults.tryEmit(OverlayResult(key, payload))
        }
        return dismissed
    }

    fun dismissTopOverlay(): Boolean =
        overlayBackStack.dismissTop()

    fun snapshot(): NavigationSnapshot =
        routeBackStack.snapshot().copy(overlays = overlayBackStack.overlays.value)

    fun encodeSnapshot(json: Json = routeJson): String = json.encodeToString(
        NavigationSnapshot.serializer(),
        snapshot(),
    )

    companion object {
        val routeJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }

        fun decodeSnapshot(value: String, json: Json = routeJson): NavigationSnapshot? =
            runCatching {
                json.decodeFromString(
                    NavigationSnapshot.serializer(),
                    value
                )
            }.getOrNull()
    }
}
