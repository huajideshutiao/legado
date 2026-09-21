package io.legado.app.ui.root

import io.legado.app.constant.AppLog
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
// KMP 平台差异: JVM 要求 value class 必须 @JvmInline, 而 Kotlin/Native (iOS/鸿蒙)
// 不支持 @JvmInline (Unresolved), 改用 data class 保留值语义 (Map key/序列化/== 相等)。
data class RouteEntryId(val value: Long)

@Serializable
data class RouteEntry(
    val id: RouteEntryId,
    val route: AppRoute,
    val resultKey: String? = null,
    val resultTargetEntryId: RouteEntryId? = null,
    /**
     * 本次进入本页时, 发起方 (被点的那张封面) 交出的共享转场配对 token; null = 本次不是从封面点进来的。
     *
     * 只给共享元素配对用 (目标页封面据此认下配对身份), **不**参与路由相等/入栈幂等判据 —— 若把 token
     * 放进 AppRoute 里, 同一本书连点两次会因为 token 不同而绕过幂等, 真的叠出两个详情页。
     *
     * 带默认值的可空字段: 旧快照缺它按 null 解, 新快照被旧代码读时由 ignoreUnknownKeys 挡掉, 双向
     * 兼容 (同 resultTargetEntryId / AppOverlay.Dialog.sourceOrigin 方案)。
     */
    val sharedToken: String? = null,
)

@Serializable
sealed interface AppOverlay {
    val key: String

    @Serializable
    data class Dialog(
        override val key: String,
        val payload: String? = null,
        val dismissOnBack: Boolean = true,
        // push 路由时不自动关闭, 由对话框内容自管挂起/恢复
        // (对照原版 DialogFragment 被新 Activity 全屏盖住仍存活; 书源登录对话框:
        // 登录 JS startBrowser → push WebView 时挂起, pop 回原栈时恢复)
        val keepOnPush: Boolean = false,
        // 书源身份 (书源 URL, 可空): 供 photo 等需要防盗链 header/封面解密规则的 overlay
        // 按书源加载网络资源 (与全局当前阅读书解耦); 本地书/无书源场景不传, 保持裸 GET。
        // 默认值 + routeJson(ignoreUnknownKeys) 保证旧快照双向兼容 (同 keepOnPush 方案)。
        val sourceOrigin: String? = null,
        // 大图查看器本次配对的那份封面端点 token (由发起方页面自签并交给本 overlay);
        // null = 没有源封面端点 (阅读页内联图/验证码图等), 查看器按原行为立即显示、不做共享飞行。
        // 默认值 + ignoreUnknownKeys 保证旧快照双向兼容 (同 sourceOrigin 方案)。
        val photoToken: String? = null,
    ) : AppOverlay

    @Serializable
    data class Sheet(
        override val key: String,
        val payload: String? = null,
        val dismissOnBack: Boolean = true,
        // "web_view" 半屏浏览器 (startBrowser asBottomSheet=true) 的参数包: 与全屏路由
        // 共用同一个 [AppRoute.WebView] —— 半屏与全屏跑的是同一段实现
        // (io.legado.app.ui.browser.WebViewScreen), 参数只有一份就不会各自漂移。
        // 可空 + 默认 null 保证旧快照双向兼容 (同 Dialog.sourceOrigin 方案);
        // 为空时由消费方退回 payload 当裸 URL 用。
        val webView: AppRoute.WebView? = null,
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
 * Overlay 关闭时由 dismissOverlay(key, payload) 通过 [AppNavigator.overlayResults] 推送,
 * 调用方按 key 过滤消费。带 payload 的 [AppNavigator.pop] 一律是路由结果, 不走这里。
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

    /**
     * 入栈一页。
     *
     * @param sharedToken 共享元素配对 token: 由发起方 (被点的那张封面) 在点击那一刻交出, 落在新 entry 上
     *   供目标页封面认配对身份 (见 [RouteEntry.sharedToken])。**不**参与下面的幂等判据: 同一本书连点两次
     *   仍应只叠一页 (token 不同只是说明第二次点击没有真的发生导航, 自然也没有飞行)
     */
    fun push(
        route: AppRoute,
        resultKey: String? = null,
        sharedToken: String? = null,
    ): RouteEntryId {
        val current = currentEntry
        if (current.route == route && current.resultKey == resultKey) return current.id
        // 任何新导航动作 (push 路由) 先自动关闭对话框类 overlay。对照原版:
        // 新 Activity 全屏盖住后旧对话框不再与新页面叠放 (如原版屏蔽规则列表对话框跳
        // SourceFilterRuleActivity 前先 dismiss 自己); 单页导航下路由渲染在 Overlay 之下,
        // 不关闭会被对话框遮住。Sheet 属半屏界面, 保留不关。
        // 例外: keepOnPush 对话框 (书源登录) 保留, 由内容自管挂起/恢复 (原版被盖住仍存活)。
        // 时机就是立即: 曾试过延后到转场播完再关以消除"对话框先消失、新页才出现"的
        // 窗口期, 但 Overlay 恒渲染在路由之上, 对话框会整段转场悬在滑入的新页之上
        // 挡视线, 观感更差 (2026-09 实测回退)。
        // 注: 弹新 Overlay 不走本逻辑 —— 对话框之间默认叠放, 见 [showOverlay]。
        dismissDialogOverlays()
        val entryId = routeBackStack.push(
            route = route,
            resultKey = resultKey,
            resultTargetEntryId = resultKey?.let { currentEntry.id },
            sharedToken = sharedToken,
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
        // replace 同样是新导航动作 (当前无调用点, 预留防止未来遗漏), 先关对话框类 overlay
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
        // 无 payload = 返回键语义: 顶层 Overlay 优先关闭 (挂起中的除外, 窗口已隐藏时返回键
        // 应作用于可见的路由层)。带 payload = 路由结果语义, 必须 pop 路由并投递给 push 它的
        // entry; Overlay 回传结果走 dismissOverlay(key, payload), 不在此分流, 否则栈上留着
        // 未关的 Sheet 时结果会被误当成 OverlayResult 发出, 路由不 pop 且调用方永远收不到。
        if (payload is RouteResultPayload.None &&
            overlayBackStack.peek() != null &&
            !isTopOverlaySuspended() &&
            dismissTopOverlay()
        ) {
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

    /**
     * 弹出 Overlay。对话框之间默认叠放: 新对话框叠在已有对话框之上, 关闭后回到下面那个
     * (对照原版 DialogFragment: 父对话框弹子对话框时不 dismiss 自己, dismiss 只出现在
     * 取消/确定/结果回传路径)。
     *
     * 需要"新对话框顶掉旧对话框"时由调用方自行 dismissOverlay, 本方法不隐式关闭 ——
     * 否则"从对话框内部再弹对话框"的链路 (如段评列表点头像看大图) 会把发起方一起关掉。
     * 路由 push/replace 仍会关对话框, 见 [push]。
     */
    fun showOverlay(overlay: AppOverlay) {
        overlayBackStack.show(overlay)
    }

    /**
     * 关闭所有对话框类 (Dialog) overlay, Sheet (半屏界面) 保留不关。
     *
     * 仅路由导航 (push/replace) 调用: 单页导航下路由渲染在 Overlay 之下, 不关会被遮住。
     * keepOnPush 对话框 (书源登录) 例外保留, 由内容自管挂起/恢复。
     * dismiss 按 key 过滤, 幂等无副作用, 与 pop() 的 dismissTopOverlay 复用同一底层机制。
     */
    private fun dismissDialogOverlays() {
        overlayBackStack.overlays.value.forEach { overlay ->
            if (overlay is AppOverlay.Dialog && !overlay.keepOnPush) {
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
            }.onFailure {
                // 不静默吞: 快照解码失败会让 RouteBackStack 回落到 initialRoute (整个导航栈弹回
                // 书架), 属用户可感知行为, 必须留痕才能定位是哪个字段/版本不兼容
                AppLog.put("导航快照解码失败, 回落初始路由", it)
            }.getOrNull()
    }
}
