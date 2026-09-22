package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 页面级生命周期载体: 一个 [RouteEntry] 在导航栈内存在期间拥有一份 [Lifecycle], 供页面级副作用
 * (订阅/注册表/计时/落盘) 挂载, 而不必依赖"该页是否在组合中"。
 *
 * 为什么需要它: 单 Activity 架构下"是否在组合中"与"页面是否可见/存活"是两件事。副作用挂在
 * `DisposableEffect` 上时, 组合成员资格一旦变化 (被压栈、被 Lazy 回收、动画期间被移出渲染列表)
 * 副作用就跟着启停, 于是出现两类错误: 该停的没停 (不可见页仍在查库/计时), 该活的被杀死 (被压栈页
 * 的订阅被取消、状态被销毁)。原版一页一 Activity, 页面天然带 Lifecycle, 这类问题不存在;
 * Compose 化后必须显式补回这个载体。
 *
 * 状态映射 (对照原版 Activity 语义):
 * - 本页是栈顶且 app 在前台 → [Lifecycle.State.RESUMED] (页面可见, 用户正在交互);
 * - 本页在渲染中但被盖住, 或 app 退到后台 → [Lifecycle.State.STARTED] (在栈内存活, 但不可见);
 * - 本页已离开渲染列表 → [Lifecycle.State.DESTROYED]。
 *
 * 与 androidx.navigation 的 `NavBackStackEntry` 的差别: 官方把"栈内非栈顶"降到 CREATED, 因为它
 * 同时把该页的 UI 移出组合; 本端被压栈页保留组合 (见 [RouteTransitionSegment.displayEntries]),
 * 故其 Lifecycle 停在 STARTED。
 */
internal class RouteLifecycleOwner : LifecycleOwner {

    internal val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry
}

/** 本页的生命周期载体; null = 不在页面栈渲染上下文中 (独立预览、跨窗口内容)。 */
internal val LocalRouteLifecycleOwner = compositionLocalOf<RouteLifecycleOwner?> { null }

/**
 * 把页面级副作用挂在本页 Lifecycle 上: 本页至少处于 [minState] 且 [enabled] 时执行 [onEnter],
 * 否则执行 [onLeave]。
 *
 * 语义对照原版 Activity 的回调区间:
 * - [Lifecycle.State.STARTED] = 本页在渲染期间 (进页注册/退页释放, 对照 onCreate/onDestroy);
 * - [Lifecycle.State.RESUMED] = 本页可见期间 (对照 onResume/onPause, 被压栈或退后台即收尾)。
 *
 * 读 Lifecycle 自己的状态流驱动重组, 不另存布尔: 状态翻转是唯一事实来源。
 *
 * @param enabled 附加条件 (如"当前 tab 是本页"): 与状态相与, 任一不满足即离开
 */
@Composable
internal fun OnRouteLifecycle(
    minState: Lifecycle.State = Lifecycle.State.STARTED,
    enabled: Boolean = true,
    onEnter: () -> Unit = {},
    onLeave: () -> Unit,
) {
    val owner = LocalRouteLifecycleOwner.current ?: return
    val state by owner.lifecycle.currentStateFlow.collectAsState()
    // 回调用最新引用: 离开时执行的是本次组合的 onLeave, 不因闭包捕获旧值而漏掉新参数
    val currentOnLeave by rememberUpdatedState(onLeave)
    if (enabled && state.isAtLeast(minState)) {
        DisposableEffect(owner, minState) {
            onEnter()
            onDispose { currentOnLeave() }
        }
    }
}

/**
 * 把一个 [RouteLifecycleOwner] 提供给 [content], 并把它挂到 Compose 的 [LocalLifecycleOwner] 上。
 *
 * 覆盖 [LocalLifecycleOwner] 是必要的: `collectAsStateWithLifecycle()` 等按生命周期取值/暂停的
 * 组件都读它; 不覆盖时它们拿到宿主 Activity 的 Lifecycle, 对"本页被盖住"一无所知。
 */
@Composable
internal fun ProvideRouteLifecycle(
    owner: RouteLifecycleOwner,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalRouteLifecycleOwner provides owner,
        LocalLifecycleOwner provides owner,
        content = content,
    )
}

/**
 * 为一批参与渲染的页维护各自的 [RouteLifecycleOwner], 并按"栈顶 + 前台"驱动其状态。
 *
 * 状态只在栈事实与前后台变化时写入 (不是每帧), 且与 entry 同命: 离开渲染后载体先降到
 * DESTROYED 再移除, 保证挂在它上面的观察者收到终态事件。
 */
internal class RouteLifecycleStore {
    private val owners = mutableMapOf<RouteEntryId, RouteLifecycleOwner>()

    /**
     * 取本页的载体。
     *
     * 入参是**参与渲染的页** (displayEntries, 含退场动画中的出栈页) 而非栈内页: 出栈页在退场
     * 动画期间仍在组合, 其页面级副作用应当活到离开组合那一刻, 与原版 Activity 退出动画播完才
     * onDestroy 的时序一致。
     */
    fun ownerOf(composed: List<RouteEntry>, entry: RouteEntry): RouteLifecycleOwner? {
        if (composed.none { it.id == entry.id }) return null
        return owners.getOrPut(entry.id) {
            RouteLifecycleOwner().apply { registry.currentState = Lifecycle.State.STARTED }
        }
    }

    /**
     * 把每个载体的状态对齐到当前事实: 栈顶且前台 = RESUMED, 其余在渲染的页 = STARTED;
     * 已离开渲染的载体降到 DESTROYED 并移除。
     *
     * @param composed 参与渲染的页 (含退场动画中的出栈页)
     * @param stackTopId 当前页面栈的栈顶 (null = 空栈)
     */
    fun sync(
        composed: List<RouteEntry>,
        stackTopId: RouteEntryId?,
        foreground: Boolean,
    ) {
        val alive = composed.mapTo(mutableSetOf()) { it.id }
        owners.keys.filterNot { it in alive }.forEach { id ->
            owners.remove(id)?.registry?.currentState = Lifecycle.State.DESTROYED
        }
        composed.forEach { entry ->
            val target = if (foreground && entry.id == stackTopId) {
                Lifecycle.State.RESUMED
            } else {
                Lifecycle.State.STARTED
            }
            val owner = owners.getOrPut(entry.id) { RouteLifecycleOwner() }
            if (owner.registry.currentState != target) {
                owner.registry.currentState = target
            }
        }
    }
}
