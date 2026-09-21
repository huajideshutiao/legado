package io.legado.app.ui.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlin.random.Random

/**
 * 官方共享转场作用域 (androidx.compose.animation 的 SharedTransitionLayout 提供)。
 *
 * null = 不在作用域内 (独立预览、以及**跑在独立窗口里的 Dialog/Popup 内容**) → 端点 helper 直接
 * 返回原 modifier, 不挂共享、不登记在场。共享元素要求两端点在同一棵 layout 树里: 官方内部全靠
 * 本作用域根的 lookahead 坐标换算目标位, 跨树换算在 compose-ui 里直接抛
 * "layouts are not part of the same hierarchy" —— 这也是大图查看器必须留在主窗口内的原因。
 */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * 共享盒的缩放模式: 内容按终态尺寸布局一次再整体缩放, 不逐帧重排。
 *
 * 走 expect 而非直接调用: CPF 鸿蒙 fork CMP (1.9.2-0.5.0-25) 里该工厂叫 `ScaleToBounds`,
 * 官方 CMP 1.11.1 里叫 `scaleToBounds` (fork 落后于官方的重命名)。
 */
internal expect val photoSharedResizeMode: ResizeMode

/**
 * 共享元素转场总闸: 只由 eInk 与系统动画时长决定 (原先还有一个「容器变换动画」开关, 该机制
 * 已随容器变换一并删除、效果常开), 一个判定同时管住两处共享元素转场 (列表封面↔详情页封面、
 * 封面↔全屏大图查看器)。
 *
 * 关闭时端点完全不挂共享修饰符: 卡片进页走普通页转场, 大图直接全屏显示
 * (对齐原版 archive d0c42f3242 —— 原版两处都没有任何共享元素转场)。
 */
val LocalSharedTransitionEnabled = staticCompositionLocalOf { false }

/**
 * 共享元素配对 token 的签发器。
 *
 * 为什么不再拿封面 URL 当配对键: 官方 [SharedTransitionScope] 按 key 配对, 且 key 空间是**全局**的
 * (同一 key 的所有端点住在同一个 SharedElement 里, 官方只认「同一时刻恰好一个端点报称目标」)。
 * 封面 URL 是**被展示的数据**: 同一张封面同时出现在书架卡片/搜索列表/详情页/音频页是常态, 一旦同 key
 * 有多个端点在场, 官方只能取"组合顺序里最靠前的那个报目标端点", 起飞位与落位随之选错; 更根本的是
 * 配对正确性被绑到了数据一致性上 (详情页加载后回写 coverUrl、搜索书与书架书 URL 不同源等), 于是
 * "该配的配不上、不该配的配上了"。
 *
 * 改成"端点自签的配对身份": 谁的封面被点了, 谁就把自己的 token 交给这次导航, 目标页拿着同一个值当
 * 自己的 key —— 配对正确性由点击因果保证, 与被展示的数据完全解耦。这正是 Android 系统转场的形状
 * (`makeSceneTransitionAnimation(view, "name")`: 名字只是这一次转场的标识, 由发起方当场交出)。
 *
 * 进程盐 + 单调计数: token 只需在**本进程**唯一。导航快照会持久化 (进程被杀后恢复), 若一律从 0
 * 开始计数, 恢复出来的旧 token 会与本次新签发的撞号 → 两端误配。盐让两代 token 天然不撞。
 *
 * 只在主线程/组合期调用 (端点自签发生在组合期), 故不加锁。
 */
object SharedPairTokens {
    private var counter: Long = 0
    private val salt: String = Random.nextLong().toString(36)

    fun newToken(): String = "s$salt.${++counter}"
}

/** 页转场里的角色: [Source] = 被点的那张封面 (出发端), [Destination] = 目标页上的封面 (落位端)。 */
enum class SharedPageRole { Source, Destination }

/**
 * 本封面端点的共享身份 (由宿主条目/页面经 [LocalSharedCoverBinding] 提供; 没提供 = 这份封面完全不
 * 参与共享转场)。
 *
 * 两套端点各要一份 token:
 * - [pageToken] 页转场 (列表封面 ↔ 目标页封面): [SharedPageRole.Source] 侧组合期自签, 点击时交给
 *   导航 ([AppNavigator.push] 的 sharedToken 参数), 目标页从自己的 [RouteEntry.sharedToken] 取同一个值;
 * - [photoToken] 封面 ↔ 全屏大图查看器: 只有"本页封面能点开大图"的页面提供 (页面自签)。
 *
 * 为什么出发侧要在组合期就自签、而不是点击那一帧才签发: 官方要靠"对端上一次被放置的矩形"当飞行起点
 * (SharedTransitionStateMachine 的 currentBounds)。端点节点必须在本帧之前就已经在场并完成过布局;
 * 点击那一帧才新建节点, 起点会退化成目标自己的矩形 —— 飞行退化成原地淡入。
 */
class SharedCoverBinding internal constructor(
    val pageRole: SharedPageRole,
    pageToken: String? = null,
    photoToken: String? = null,
) {
    var pageToken: String? by mutableStateOf(pageToken)
    var photoToken: String? by mutableStateOf(photoToken)
}

/** 本封面端点的共享身份 (缺省 null = 不参与任何共享转场)。 */
val LocalSharedCoverBinding = staticCompositionLocalOf<SharedCoverBinding?> { null }

/**
 * 建一份出发侧绑定 (列表卡片): 页转场 token 组合期签发, 点击时经 [SharedCoverBinding.pageToken] 交给导航。
 *
 * @param bookKey 条目身份键 (通常 bookUrl): 同一条目位置换成另一本书时必须换 token, 否则新书的封面
 *   会顶着一张刚离场的旧配对 (两端都还挂着同一个 token)。
 */
@Composable
fun rememberSharedCoverSourceBinding(bookKey: Any?): SharedCoverBinding =
    remember(bookKey) {
        SharedCoverBinding(SharedPageRole.Source, pageToken = SharedPairTokens.newToken())
    }

/**
 * 建一份落位侧绑定 (目标页: 详情页封面 / 音频页封面)。
 *
 * @param entryKey 本页 entry 身份 (entry.id): 同一位置换成另一页时必须换大图 token
 * @param pageToken 本页进入时由发起方交出的页转场 token (null = 本次不是从封面点进来的, 不参与页对)
 */
@Composable
fun rememberSharedCoverDestinationBinding(
    entryKey: Any?,
    pageToken: String?,
): SharedCoverBinding =
    remember(entryKey) {
        SharedCoverBinding(
            SharedPageRole.Destination,
            pageToken = pageToken,
            photoToken = SharedPairTokens.newToken(),
        )
    }

/**
 * 页转场共享对的飞行阶段: "哪些 token 的落位页此刻还在页面栈内"。
 *
 * 由 [LegadoApp] 从页面栈事实派生 (栈内 = 前进/落位; 被弹出但还在播退场动画的页已不在栈内 → 出发端
 * 恢复绘制)。端点只读**自己手上这个 token**:
 * - 出发端 (列表卡): token 在栈内 = 本次它是被点的那张 → 让位; 出栈后恢复绘制;
 * - 落位端 (目标页封面): 只有 token 在栈内 (前进飞行中) 才绘制。
 *
 * 为什么必须是可追踪的 compositionLocalOf 而不是 static: 封面住在 LazyGrid 的独立重组域里,
 * static 不下发读取方重组 → 读到过期值, 方向变成"看谁先组合"的赌博 (先组合的那一端被当成
 * 目标端, 终点矩形就落成它自己)。
 *
 * 结构相等 (data class + Set) 保证未变化时不下发重组。
 */
data class SharedPageFlights(val forward: Set<String>)

val LocalSharedPageFlights = compositionLocalOf { SharedPageFlights(emptySet()) }

/**
 * 页转场共享对的飞行参数: 与本次页面转场**同一份 spec** (前进=push 的时长/曲线, 返回=pop 的)。
 *
 * 为什么必须同源: 飞行期间共享内容画在 SharedTransitionLayout 的覆盖层里, 不受本页图层位移影响,
 * 而飞行的终点矩形是"本页不被位移时"的布局位置。两段时长不等时 (如飞行 280ms、页面转场 500ms),
 * 飞行先到位、页面还在位移, 飞行结束那一帧内容交还给本页图层后被剩余位移整体拖走一下,
 * 观感就是"飞完又被二次定位"。
 */
data class SharedPageFlightSpec(
    val durationMillis: Int,
    val easing: Easing,
)

val LocalSharedPageFlightSpec = compositionLocalOf {
    SharedPageFlightSpec(PhotoSharedBoundsDurationMillis, FastOutSlowInEasing)
}

/**
 * 共享元素状态: "此刻哪张封面被全屏大图查看器举起来了" (整个应用一份)。
 *
 * 只存**正在被举起的那份 token**: 查看器端与封面端都读它推各自的 visible (两端各自再算一份必然错帧,
 * 错帧就会两端同时报称可见 → 官方按"缺 target"处理, 一个都不动画); 写只发生在查看器的接管/归还两处。
 * 没有"按封面 URL 计数在场端点"那层: 大图端点本就不由数据键配对, 源端点是否在场由 token 自己回答。
 */
class PhotoSharedState internal constructor() {

    /** 正被查看器举起的封面端点 token; null = 没有大图飞行, 封面端点照常绘制 */
    var viewerToken: String? by mutableStateOf(null)
        internal set
}

/** 共享元素状态: 由 [LegadoApp] 按应用实例提供 (整个应用一份) */
val LocalPhotoSharedState = staticCompositionLocalOf { PhotoSharedState() }

/**
 * 共享元素飞行时长: 两对端点的 bounds 动画、共享盒内进/出内容淡变与黑底蒙版渐变严格对齐此常量 (280ms)。
 */
internal const val PhotoSharedBoundsDurationMillis = 280

/**
 * 共享元素的 bounds 变换: 与蒙版渐变严格使用相同的时间 (280ms) 与插值曲线,
 * 确保形变与明暗 100% 同频, 杜绝末帧闪烁。
 */
private val PhotoBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = PhotoSharedBoundsDurationMillis, easing = FastOutSlowInEasing)
}

/**
 * 共享盒内进/出内容的淡变: 与 bounds 动画同时长同曲线。两处用到 —— `sharedBounds` 的 enter/exit
 * (飞行期间内容画在 SharedTransitionLayout 的覆盖层里, 父级的 alpha 到不了那里, 淡变必须写在共享节点
 * 之内), 以及包着共享节点的 `AnimatedVisibility` 的 enter/exit (它决定内容何时被卸载, 比 bounds 动画
 * 短就会把飞行中的那份提前拆掉; 不飞行时它才是实际生效的那一层淡变)。
 */
internal val PhotoSharedContentFade = tween<Float>(
    durationMillis = PhotoSharedBoundsDurationMillis,
    easing = FastOutSlowInEasing,
)

/**
 * "封面 ↔ 全屏大图查看器" 这一对的共享 key。
 *
 * 必须与页转场那一套分命名空间: 同一份封面上挂着两个端点 (外层管页转场, 内层管大图), 而官方按 key
 * 配对 —— 两个端点挤在同一个 key 下会互相抢正身 (页转场与大图转场互相打断、起飞位/落位选错)。
 */
internal fun photoSharedViewerKey(photoToken: String): String = "photo:$photoToken"

/**
 * 共享对的一层: AnimatedVisibility(方向) + 官方 sharedBounds (两端内容都绘制)。
 *
 * 为什么两对都用 sharedBounds 而不是 sharedElement: `sharedElement` 规定"同一时刻只有报称可见的那一端
 * 绘制"(官方 renderOnlyWhenVisible=true), 于是让位那一端整层停绘 —— 退出大图/退出详情页时屏幕上会先
 * 空一格, 而且飞行内容被逐帧用"动画尺寸"的固定约束重排 (封面链上的 `aspectRatio` 会算出超宽盒)。
 * `sharedBounds` 两端都绘制: 离场那份画完整段飞行, 两份内容在同一个动画盒里按 [PhotoSharedContentFade]
 * 交叉淡化; [photoSharedResizeMode] 让内容按**终态尺寸布局一次**再整体缩放, 不逐帧重排。
 *
 * [visible] 就是方向: 官方按"该端点自己的 AnimatedVisibilityScope 是否处于 Visible"决定谁提供目标
 * 矩形, 所以两端必须由同一份状态驱动、在同帧翻转。
 *
 * @param decoration 裁剪/手势等要出现在飞行副本上的东西 (官方要求裁剪写在共享修饰符之后)
 */
@Composable
private fun BoxScope.SharedCoverLayer(
    sharedKey: String,
    visible: Boolean,
    zIndexInOverlay: Float,
    decoration: Modifier,
    label: String,
    /**
     * 首次组合时的可见态: 决定这一层到底播不播进场。
     *
     * 官方 sharedBounds 的飞行只在"两端 bounds 真的在变"时成立 (isTransitionActive = 有端点的
     * boundsAnimation 在跑), 而 AnimatedVisibility 初始 currentState 等于它的 targetState 时
     * 不播任何动画。落位页封面是**首次组合**的那一端: 一上来就报可见时进场两端都不动 (出发端只
     * 是淡出、落位端没有 enter), 官方就判定没有飞行 → 封面原地出现 (2026-09 真机实测确认)。
     * 故落位端首次组合必须从 false 起跳 (官方列表→详情示例的形状), 出发端则从 true。
     */
    initiallyVisible: Boolean,
    /** bounds 动画与盒内进/出淡变共用的时长/曲线 (页转场对=本次页面转场 spec, 大图对=固定 280ms) */
    fade: FiniteAnimationSpec<Float>,
    boundsTransform: BoundsTransform,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = checkNotNull(LocalSharedTransitionScope.current)
    // key 带 sharedKey: 同一条目位置换成另一本书/另一页时必须重起一份状态, 否则新封面会继承旧的可见相位
    val visibleState = remember(sharedKey) { MutableTransitionState(initiallyVisible) }
    visibleState.targetState = visible
    AnimatedVisibility(
        visibleState = visibleState,
        // 铺满外层盒 (尺寸留在外层): 内容卸载后本格不塌, 端点起止盒恒等于封面格
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(fade),
        exit = fadeOut(fade),
        label = label,
    ) {
        val visibilityScope: AnimatedVisibilityScope = this
        val sharedContentState = with(scope) { rememberSharedContentState(sharedKey) }
        Box(
            modifier = with(scope) {
                Modifier
                    .fillMaxSize()
                    .sharedBounds(
                        sharedContentState = sharedContentState,
                        animatedVisibilityScope = visibilityScope,
                        enter = fadeIn(fade),
                        exit = fadeOut(fade),
                        boundsTransform = boundsTransform,
                        resizeMode = photoSharedResizeMode,
                        zIndexInOverlay = zIndexInOverlay,
                    )
            }.then(decoration),
            content = content,
        )
    }
}

/**
 * 封面端的共享端点宿主: 一次挂上两套端点 (页转场对 + 大图对)。
 *
 * 只有经 [LocalSharedCoverBinding] 拿到绑定、且开关打开、且真的有一张图时才挂共享节点; 否则退化成
 * 一个带全套装饰的 Box (与挂共享时视觉一致)。
 *
 * [outerModifier] 留在外层节点 (调用方给的尺寸/内边距 + `aspectRatio` 等尺寸约束 + onSizeChanged):
 * 本层里面的 `AnimatedVisibility` 在内容被卸载后自己会缩成 0, 尺寸必须由外层定住, 否则端点起止盒会塌。
 * [sharedModifier] 只挂在最内层共享节点上 (裁剪/描边/手势出现在飞行副本上), 外层共享节点会把它的效果
 * 一起录进自己的图层。
 *
 * @param cover 内容键: 只用来判"这份封面有没有可飞的图" (空串/裸默认封面不参与), **不再**当配对键
 */
@Composable
fun PhotoSharedCoverHost(
    cover: String?,
    outerModifier: Modifier = Modifier,
    sharedModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = LocalSharedTransitionScope.current
    val enabled = LocalSharedTransitionEnabled.current
    val binding = LocalSharedCoverBinding.current
    // 本端点在这一对里扮演的角色 (缺省出发端): 先取好, 不依赖"binding 已被智能转换为非空"
    val pageRole = binding?.pageRole ?: SharedPageRole.Source
    val flights = LocalSharedPageFlights.current
    val state = LocalPhotoSharedState.current
    val flightSpec = LocalSharedPageFlightSpec.current
    val hasImage = !cover.isNullOrBlank()
    val pageToken = binding?.pageToken?.takeIf { hasImage }
    val photoToken = binding?.photoToken?.takeIf { hasImage }
    if (scope == null || !enabled || pageToken == null && photoToken == null) {
        Box(modifier = outerModifier.then(sharedModifier), content = content)
        return
    }
    // 页转场那一对与本次页面转场同源 (见 [SharedPageFlightSpec])
    val pageFade: FiniteAnimationSpec<Float> = remember(flightSpec) {
        tween<Float>(durationMillis = flightSpec.durationMillis, easing = flightSpec.easing)
    }
    val pageBoundsTransform: BoundsTransform = remember(flightSpec) {
        BoundsTransform { _, _ ->
            tween(durationMillis = flightSpec.durationMillis, easing = flightSpec.easing)
        }
    }
    Box(modifier = outerModifier) {
        if (pageToken == null) {
            SharedCoverLayer(
                sharedKey = photoSharedViewerKey(photoToken!!),
                // 查看器举着这一份时本端让位 (整段飞行交给查看器那份); 其余时刻本端就是稳态可见端
                visible = state.viewerToken != photoToken,
                zIndexInOverlay = 0f,
                decoration = sharedModifier,
                label = "photoSharedCover",
                initiallyVisible = true,
                fade = PhotoSharedContentFade,
                boundsTransform = PhotoBoundsTransform,
                content = content,
            )
        } else {
            // 出发端: token 在栈内 = 本次它是被点的那张 → 让位 (前进飞行交给落位端);
            // 出栈后恢复绘制 → 它的 enter 就是回程飞行的驱动器 (官方靠"那一端在播"才认飞行)
            // 落位端: 只有 token 在栈内 (前进飞行中) 才绘制
            val isTargetEnd = when (pageRole) {
                SharedPageRole.Source -> pageToken !in flights.forward
                SharedPageRole.Destination -> pageToken in flights.forward
            }
            SharedCoverLayer(
                sharedKey = pageToken,
                visible = isTargetEnd,
                // 落位端是**首次组合**的那一端: 一上来就报可见时它自己的 AnimatedVisibility 不播
                // (currentState == targetState), 这一对就没有任何 bounds 变化 → 官方判定没在飞,
                // 封面原地出现。从不可见起跳才会真的播 enter, 飞行才有驱动器。
                // 出发端恒 true (它本来就在屏幕上)
                initiallyVisible = pageRole != SharedPageRole.Destination,
                zIndexInOverlay = 0f,
                // 有内层大图端点时装饰挂内层 (它整体录进外层图层), 否则挂本层
                decoration = if (photoToken == null) sharedModifier else Modifier,
                label = "pageSharedCover",
                fade = pageFade,
                boundsTransform = pageBoundsTransform,
            ) {
                if (photoToken == null) {
                    content()
                } else {
                    SharedCoverLayer(
                        sharedKey = photoSharedViewerKey(photoToken),
                        visible = state.viewerToken != photoToken,
                        initiallyVisible = true,
                        zIndexInOverlay = 0f,
                        decoration = sharedModifier,
                        label = "photoSharedCover",
                        fade = PhotoSharedContentFade,
                        boundsTransform = PhotoBoundsTransform,
                        content = content,
                    )
                }
            }
        }
    }
}

/**
 * 大图查看器端的共享端点 (主窗口内的全屏查看器)。
 *
 * 与封面端的 [PhotoSharedCoverHost] 共用同一套 key 与动画参数 (key 由 [photoSharedViewerKey] 统一推导,
 * 参数在本文件里只写一遍) —— 两端各写一份必然配不上对, 且不会有任何报错。
 *
 * [animatedVisibilityScope] 由调用方包在本节点外的 `AnimatedVisibility` 提供 (1.11.1 的公开 API 只有
 * 这一条路, caller-managed 的 `sharedBoundsWithCallerManagedVisibility` 是 internal); 该
 * AnimatedVisibility 的 visible 必须与"查看器是否已接管"同一口径, 且 enter/exit 不得比 bounds 动画短,
 * 否则离场那份会被提前卸载。
 *
 * @param photoToken 本查看器所配对的那份封面端点的 token ([PhotoSharedState.viewerToken] 同值)
 */
@Composable
fun Modifier.photoSharedTarget(
    photoToken: String,
    animatedVisibilityScope: AnimatedVisibilityScope,
    zIndexInOverlay: Float = 0f,
): Modifier {
    val scope = LocalSharedTransitionScope.current
    val enabled = LocalSharedTransitionEnabled.current
    if (scope == null || !enabled) return this
    val sharedContentState =
        with(scope) { rememberSharedContentState(photoSharedViewerKey(photoToken)) }
    return this.then(
        with(scope) {
            Modifier.sharedBounds(
                sharedContentState = sharedContentState,
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(PhotoSharedContentFade),
                exit = fadeOut(PhotoSharedContentFade),
                boundsTransform = PhotoBoundsTransform,
                resizeMode = photoSharedResizeMode,
                zIndexInOverlay = zIndexInOverlay,
            )
        }
    )
}
