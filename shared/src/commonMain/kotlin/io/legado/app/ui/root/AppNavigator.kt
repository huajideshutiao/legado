package io.legado.app.ui.root

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    val currentEntry: RouteEntry get() = backStack.value.last()
    val currentRoute: AppRoute get() = currentEntry.route

    fun push(route: AppRoute, resultKey: String? = null): RouteEntryId =
        routeBackStack.push(route, resultKey)

    fun replace(route: AppRoute): RouteEntryId =
        routeBackStack.replace(route)

    /** 一级入口切换：清除子页和 Overlay，避免平台自行猜测返回目标。 */
    fun resetRoot(route: AppRoute.Main) {
        routeBackStack.resetRoot(route)
        overlayBackStack.clear()
    }

    fun pop(payload: RouteResultPayload = RouteResultPayload.None): Boolean {
        if (dismissTopOverlay()) return true
        val removed = routeBackStack.peek()
        val popped = routeBackStack.pop()
        if (popped) {
            removed?.resultKey?.let { _results.tryEmit(RouteResult(it, payload)) }
        }
        return popped
    }

    fun popTo(entryId: RouteEntryId, inclusive: Boolean = false): Boolean {
        val popped = routeBackStack.popTo({ it.id == entryId }, inclusive)
        if (popped) overlayBackStack.clear()
        return popped
    }

    fun showOverlay(overlay: AppOverlay) {
        overlayBackStack.show(overlay)
    }

    fun dismissOverlay(key: String): Boolean =
        overlayBackStack.dismiss(key)

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
