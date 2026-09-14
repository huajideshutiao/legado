package io.legado.app.ui.root

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

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
 * 共享元素转场总闸: 「其他设置 → 容器变换动画」一个开关同时管住两处共享元素转场
 * (列表封面↔详情页封面、封面↔全屏查看器); eInk 与系统动画时长为 0 由 [LegadoApp] 一并算进来。
 *
 * 关闭时端点完全不挂共享修饰符: 卡片进页走普通页转场, 大图直接全屏显示
 * (对齐原版 archive d0c42f3242 —— 原版两处都没有任何共享元素转场)。
 */
val LocalSharedTransitionEnabled = staticCompositionLocalOf { false }

/**
 * 本页是否为当前栈顶路由页 (由 [LegadoApp] 逐路由页提供)。
 *
 * 端点消歧靠它: 同一本书的封面会同时存在于多个已组合页面 (书架卡片 + 详情页封面, 甚至音频页),
 * 而官方按 key 配对、从"上一个报称可见的端点"的矩形起飞。不止一个端点报称可见时, 起飞位与落位
 * 就会选错 (典型表现: 大图从被压在详情页下面的那张卡片位置飞出来)。只栈顶页那个端点算正身,
 * 其余端点既不登记也不挂修饰符。
 *
 * 必须用 compositionLocalOf 而不是 static: 栈顶与否在导航时会变, 而静态 local 不订阅读取方
 * (封面在 LazyGrid 的独立重组域里, 静态读法会一直读到过期值)。代价是该标志翻转时页内封面
 * 重组一次, 与官方用 AnimatedVisibility 整页进出场时的同量代价。
 */
val LocalPageIsTopPage = compositionLocalOf { false }

/**
 * 共享元素状态: "此刻哪张图被大图查看器举起来了"。
 *
 * 语义由官方实现: 同一 key 的各端点按 [visibleKey] 推导自己的 `visible`, 翻转那一帧官方把内容
 * **提升**到 SharedTransitionLayout 的覆盖层逐帧重排飞行, 并让让位的那一端**整层不再重放**
 * (SharedContentNode.kt:463-476 录进 layer 后按 shouldRenderInPlace 决定是否 drawLayer)。因此:
 * - 不需要上报源矩形 (旧轮子的矩形登记表就是为这个存在的);
 * - 不需要手工插值 offset/size (旧轮子按封面宽高比推终态盒, 与终点真实 Fit 布局不同源 → 末端跳);
 * - 不需要两端互补 alpha (旧轮子因此出现"空窗 + 重影");
 * - 飞行期间画的就是最终那份内容, 不会中途被换掉。
 */
class PhotoSharedState internal constructor() {

    /** 正在被查看器举起的图片 key; null = 无 (封面端点照常绘制) */
    var visibleKey by mutableStateOf<String?>(null)
        internal set

    /** 在场正身封面端点的 key → 引用计数: 同一 key 多处显示时, 任一处离场不误伤 */
    private val sources = mutableStateMapOf<String, Int>()

    /**
     * 该 key 是否有正身封面端点在场。查看器据此决定"等图片就绪再起飞"还是"立刻显示":
     * 有正身才有共享元素动画可等; 无源端点 (阅读页内联图、验证码图等) 本来就没有转场,
     * 保持原行为立即显示, 不能因为门控变成"点了没反应"。
     */
    fun hasSource(key: String): Boolean = (sources[key] ?: 0) > 0

    internal fun acquireSource(key: String) {
        sources[key] = (sources[key] ?: 0) + 1
    }

    internal fun releaseSource(key: String) {
        val left = (sources[key] ?: 0) - 1
        if (left > 0) {
            sources[key] = left
        } else {
            sources.remove(key)
        }
    }
}

/** 共享元素状态: 由 [LegadoApp] 按应用实例提供 (整个应用一份) */
val LocalPhotoSharedState = staticCompositionLocalOf { PhotoSharedState() }

/**
 * 共享元素飞行时长: 大图的 bounds 动画与黑底蒙版渐变严格对齐此常量 (280ms)。
 */
internal const val PhotoSharedBoundsDurationMillis = 280

/**
 * 大图查看器的 bounds 变换: 与蒙版渐变严格使用相同的时间 (280ms) 与插值曲线,
 * 确保形变与明暗 100% 同频, 杜绝末帧闪烁。
 */
private val PhotoBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = PhotoSharedBoundsDurationMillis, easing = FastOutSlowInEasing)
}

/**
 * 封面的共享元素**源**端点 (书架/搜索/发现卡片、详情页封面、音频页封面都经
 * [io.legado.app.ui.bookshelf.SharedBookCover] 挂它): 不自己算"隐藏", 只声明
 * "我是这个 key 的端点 + 我此刻是不是正身"。
 *
 * 端点必须**无条件挂上** (本页不再是栈顶页时也要挂): 共享元素要能飞回来, 靠的是退出端仍以 entry
 * 形式留在同一个 SharedElement 里; 早退不挂就等于 entry 消失, 既没有起飞位也没有落点。
 * 受栈顶页约束的只是"报称可见"与"正身在场登记":
 * visible = 本页是栈顶页 且 这张图没被查看器举起 (与查看器读同一份 [PhotoSharedState.visibleKey])。
 *
 * 必须挂在封面修饰符链的**最前**: 裁剪/圆角得是它的子级 (官方 KDoc 明确要求), 否则飞行副本会
 * 丢掉封面圆角。在场登记也在本函数里做, 与端点同一条件、同一处, 不会漂移。
 */
@Composable
fun Modifier.photoSharedSource(key: String?): Modifier {
    val scope = LocalSharedTransitionScope.current
    val state = LocalPhotoSharedState.current
    val enabled = LocalSharedTransitionEnabled.current
    val pageIsTop = LocalPageIsTopPage.current
    if (key.isNullOrBlank() || scope == null || !enabled) return this
    // 正身在场只登记栈顶页那一个: 查看器据此区分"有正身可等就绪起飞"与
    // "无源端点 (阅读页内联图、验证码图) → 按原行为立即显示"
    if (pageIsTop) {
        DisposableEffect(state, key) {
            state.acquireSource(key)
            onDispose { state.releaseSource(key) }
        }
    }
    val sharedContentState = with(scope) { rememberSharedContentState(key) }
    return this.then(
        with(scope) {
            Modifier.sharedElementWithCallerManagedVisibility(
                sharedContentState = sharedContentState,
                visible = pageIsTop && state.visibleKey != key,
            )
        }
    )
}

/**
 * 封面的共享元素**目标**端点 (主窗口内的全屏查看器): [visible] 由查看器按"图片已就绪"给值。
 *
 * 源端点在场时, 查看器先把图解好码再置 visible → 官方从那一刻才开始把内容从封面位举到全屏位;
 * 未就绪期间源封面继续显示, 屏幕上始终有一份, 不存在空窗。
 */
@Composable
fun Modifier.photoSharedTarget(key: String, visible: Boolean): Modifier {
    val scope = LocalSharedTransitionScope.current
    val enabled = LocalSharedTransitionEnabled.current
    if (scope == null || !enabled) return this
    val sharedContentState = with(scope) { rememberSharedContentState(key) }
    return this.then(
        with(scope) {
            Modifier.sharedElementWithCallerManagedVisibility(
                sharedContentState = sharedContentState,
                visible = visible,
                boundsTransform = PhotoBoundsTransform,
                // 大图浮在页栈与其它共享元素之上 (飞行途中要盖住页面内容)
                zIndexInOverlay = 1f,
            )
        }
    )
}
