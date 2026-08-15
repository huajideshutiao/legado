package io.legado.app.ui.root

import kotlin.jvm.JvmInline
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
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
        // 允许与其他对话框叠放: 弹新 Overlay 时不被自动关闭, 也不关闭已有对话框
        // (对照原版 Fragment 对话框叠放场景, 如段评列表上再弹图片查看器, 关闭查看器后列表仍在)
        val stacked: Boolean = false,
        // push 路由/弹新 Overlay 时不自动关闭, 由对话框内容自管挂起/恢复
        // (对照原版 DialogFragment 被新 Activity 全屏盖住仍存活; 书源登录对话框:
        // 登录 JS startBrowser → push WebView 时挂起, pop 回原栈时恢复)
        val keepOnPush: Boolean = false,
        // 书源身份 (书源 URL, 可空): 供 photo 等需要防盗链 header/封面解密规则的 overlay
        // 按书源加载网络资源 (与全局当前阅读书解耦); 本地书/无书源场景不传, 保持裸 GET。
        // 默认值 + routeJson(ignoreUnknownKeys) 保证旧快照双向兼容 (与 stacked 字段同方案)。
        val sourceOrigin: String? = null,
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

    // 挂起中的 Overlay key 集合: 窗口已隐藏 (被路由盖住) 但状态保留。
    // 挂起期间返回键/ESC 不关闭该 Overlay, 落到路由层 pop (见 pop/dismissTopOverlaySkipSuspended)。
    private val _suspendedOverlayKeys = MutableStateFlow<Set<String>>(emptySet())
    val suspendedOverlayKeys: StateFlow<Set<String>> = _suspendedOverlayKeys.asStateFlow()

    /**
     * 标记 Overlay 挂起/恢复 (key 必须已在 [overlays] 栈中)。
     *
     * 对照原版 "DialogFragment 被新 Activity 全屏盖住但存活": 单页导航下 Overlay 恒渲染在
     * 路由之上, 无法字面"被盖住", 由对话框内容在路由栈被 push 盖住时调用本方法隐藏窗口
     * (组合保留状态), pop 回原栈时恢复。
     */
    fun setOverlaySuspended(key: String, suspended: Boolean) {
        _suspendedOverlayKeys.update { keys ->
            if (suspended) keys + key else keys - key
        }
    }

    /** 栈顶 Overlay 是否处于挂起状态 (窗口已隐藏, 返回键应 pop 路由而非关闭 Overlay)。 */
    fun isTopOverlaySuspended(): Boolean {
        val top = overlayBackStack.peek() ?: return false
        return top.key in _suspendedOverlayKeys.value
    }

    /** 栈顶 Overlay 是否可由返回键关闭 (dismissOnBack)。返回键拦截器据此决定是否拦截:
     * 不可关闭时拦截会"吃键但界面零变化", 应放行落到路由层。 */
    fun isTopOverlayDismissibleOnBack(): Boolean =
        overlayBackStack.isTopDismissibleOnBack()

    /** 关闭顶层 Overlay; 栈顶挂起 (窗口已隐藏) 时跳过并返回 false, 返回链继续落到路由层。 */
    fun dismissTopOverlaySkipSuspended(): Boolean {
        if (isTopOverlaySuspended()) return false
        return dismissTopOverlay()
    }
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
        // 方案 C: 任何新导航动作 (push 路由) 先自动关闭对话框类 overlay。对照原版:
        // 新 Activity/Fragment 全屏盖住后, 旧对话框随导航消失 (如登录对话框内
        // java.startBrowser → WebViewActivity, 对话框不再与 WebView 叠放); 单页导航下
        // 路由渲染在 Overlay 之下, 不关闭会被对话框遮住。Sheet 属半屏界面, 保留不关。
        // 例外: keepOnPush 对话框 (书源登录) 保留, 由内容自管挂起/恢复 (原版被盖住仍存活)。
        dismissDialogOverlays()
        val entryId = routeBackStack.push(
            route = route,
            resultKey = resultKey,
            resultTargetEntryId = resultKey?.let { currentEntry.id },
        )
        targetedResults.getOrPut(entryId) { Channel(Channel.UNLIMITED) }
        return entryId
    }

    /**
     * 接收只属于指定调用页面的导航结果。每个页面应只收集一次。
     *
     * 页面已出栈时返回空流而不是抛异常: 本函数在各 Route 的 LaunchedEffect 里调用,
     * 抛出会连坐整个 Recomposer (桌面端表现为窗口还能重排但键鼠全失灵)。
     */
    fun resultsFor(entryId: RouteEntryId): Flow<RouteResult> {
        if (backStack.value.none { it.id == entryId }) return emptyFlow()
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
        // 方案 C: replace 同样是新导航动作 (当前无调用点, 预留防止未来遗漏), 先关对话框类 overlay
        dismissDialogOverlays()
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
        // 栈顶 Overlay 挂起 (窗口已隐藏) 时跳过关闭, 继续 pop 路由: 返回键应作用于可见的路由层
        val topOverlay = overlayBackStack.peek()
        if (topOverlay != null && !isTopOverlaySuspended() && dismissTopOverlay()) {
            if (payload !is RouteResultPayload.None) {
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
        // 方案 C: 弹新 Overlay 时先自动关闭已有的对话框类 overlay (同上); 新 overlay 为
        // 可叠放 Dialog (stacked=true) 时保留已有对话框, 关闭后回到原对话框 (原版语义)。
        dismissDialogOverlays(
            keepStacked = overlay is AppOverlay.Dialog && overlay.stacked
        )
        overlayBackStack.show(overlay)
    }

    /**
     * 关闭所有对话框类 (Dialog) overlay, Sheet (半屏界面) 保留不关。
     *
     * @param keepStacked 为 true 时保留已有对话框 (仅当新 overlay 为可叠放 Dialog 时传,
     * 如段评列表上弹图片查看器, 查看器关闭后列表仍在); dismiss 按 key 过滤, 对不存在
     * 的 key 返回 false, 幂等无副作用, 与 pop() 的 dismissTopOverlay 复用同一底层机制。
     */
    private fun dismissDialogOverlays(keepStacked: Boolean = false) {
        overlayBackStack.overlays.value.forEach { overlay ->
            if (overlay is AppOverlay.Dialog
                && !(keepStacked && overlay.stacked)
                && !overlay.keepOnPush
            ) {
                overlayBackStack.dismiss(overlay.key)
            }
        }
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
