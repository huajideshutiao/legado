package io.legado.app.ui.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 路由页给页内锚点的参考系: 页身份 + 页自身坐标。
 *
 * 坐标必须取**页图层内侧**的 (由 [LegadoApp] 把 onGloballyPositioned 挂在 graphicsLayer 之后),
 * 卡片矩形才与页级裁剪窗口同坐标系: 页转场靠 graphicsLayer 平移页面, 若按 compose root 记矩形,
 * 非栈顶页被平移到 -width 后卡片矩形全是屏外值 —— 返回那一段恰好要用它, 就取不到正确起手位。
 * 换算到页图层内侧坐标即彻底不含页自身变换, 页在哪都不影响。
 */
class RoutePageAnchorScope internal constructor(internal val pageKey: Any) {
    internal var coords: LayoutCoordinates? = null
}

/** 由 [LegadoApp] 逐路由页提供; null = 不在路由页内 (预览等), 锚点直接不登记。 */
val LocalRoutePageAnchor = staticCompositionLocalOf<RoutePageAnchorScope?> { null }

/** 由 [LegadoApp] 逐路由页提供: 本页正处于页面级转场 (容器变换或普通淡入) 中。
 * 平台互操作层 (视频 Surface 等原生渲染面) 录不进容器快照、也不吃页面 graphicsLayer alpha,
 * 转场中留着会全屏突兀显示, 消费方应据此在转场期间移出组合, 动画结束自动恢复。
 * 注意消费是整体移出组合而非仅隐藏: 会连带卸载 RenderSurface 内的加载副作用
 * (videoUrl.collect), 恢复后由 StateFlow 补发 + URL 守卫接续; 代价是 push 进入视频页的
 * 起播/缓冲推迟一个转场时长, 属已知取舍。 */
val LocalPageTransitionActive = staticCompositionLocalOf { false }

/**
 * [BookRef] 书源 origin 扩展: Stored/Search 分别代理其内部 Book / SearchBook 的 origin。
 */
val BookRef.origin: String
    get() = when (this) {
        is BookRef.Stored -> value.origin
        is BookRef.Search -> value.origin
    }

/**
 * 获取书籍类路由的书源 origin, 非书籍路由返回 null。
 */
internal fun AppRoute.containerBookOrigin(): String? = when (this) {
    is AppRoute.BookInfo -> book.origin
    is AppRoute.Reader -> book.origin
    is AppRoute.AudioPlay -> book.origin
    is AppRoute.VideoPlay -> book.origin
    is AppRoute.MangaReader -> book.origin
    is AppRoute.ReadRss -> book.origin
    else -> null
}

/**
 * 构造容器变换卡片复合键: 同页允许同 URL 的不同书源并存 (如搜索结果多源), 按 `${origin}|${bookUrl}` 区分;
 * origin 为空时回退纯 bookUrl。
 */
fun containerAnchorKey(origin: String?, bookUrl: String): String =
    if (origin.isNullOrEmpty()) bookUrl else "$origin|$bookUrl"

/** 路由可传递的卡片身份；复合 origin 后，同页同 URL 的不同书源不会互相覆盖。 */
data class ContainerTransformIdentity(
    val bookUrl: String,
    val origin: String? = null,
) {
    internal val registryKey: String = containerAnchorKey(origin, bookUrl)
}

/**
 * 锚点在登记时所处的完整坐标系。窗口尺寸在转场中变化时，[containerTransformDraw] 会先按
 * [viewportSize] 换算 [rect] 和圆角，再向新页面尺寸插值，避免把旧视口坐标直接用于新视口。
 */
internal data class ContainerAnchorBounds(
    val rect: Rect,
    val viewportSize: IntSize,
    val cornerRadiusPx: Float,
)

/**
 * 列表卡片矩形登记表: 容器变换的起手矩形来源。卡片侧在布局阶段写, [LegadoApp] 在导航事件那次
 * 组合里读一次。
 *
 * 按 (路由页, 复合身份, 实例 token) 登记。同页同身份有多个已组合实例时各自保留记录，回收任一
 * 实例只删除自己的 token，不会误删仍可见实例；读取采用最近布局的存活实例。
 *
 * **有意用普通 map 而非 mutableStateMapOf**: 写入发生在布局阶段 (列表滚动时每帧都有), 若是快照
 * 状态, [LegadoApp] 组合期读它就会注册依赖 —— 滚一下列表整个页栈重组。普通 map 的写不产生快照
 * 通知; 而读只发生在"导航事件到来的那次组合", 必然晚于上一帧的布局写入, 取到的恒是最新值。
 */
class BookCardRectRegistry {
    private val byPage = mutableMapOf<
        Any,
        MutableMap<String, LinkedHashMap<Any, ContainerAnchorBounds>>,
        >()

    internal fun record(
        pageKey: Any,
        identity: ContainerTransformIdentity,
        token: Any,
        bounds: ContainerAnchorBounds,
    ) {
        val instances = byPage
            .getOrPut(pageKey) { mutableMapOf() }
            .getOrPut(identity.registryKey) { linkedMapOf() }
        // 重插到末尾，让读取顺序与最近一次实际布局顺序一致。
        instances.remove(token)
        instances[token] = bounds
    }

    internal fun forget(pageKey: Any, identity: ContainerTransformIdentity, token: Any) {
        val page = byPage[pageKey] ?: return
        val instances = page[identity.registryKey] ?: return
        instances.remove(token)
        if (instances.isEmpty()) page.remove(identity.registryKey)
        if (page.isEmpty()) byPage.remove(pageKey)
    }

    internal fun boundsOf(
        pageKey: Any?,
        origin: String?,
        bookUrl: String,
    ): ContainerAnchorBounds? {
        val page = byPage[pageKey ?: return null] ?: return null
        if (!origin.isNullOrEmpty()) {
            page[containerAnchorKey(origin, bookUrl)]?.values?.lastOrNull()?.let { return it }
        }
        return page[bookUrl]?.values?.lastOrNull()
    }
}

/** 卡片矩形登记表: 由 [LegadoApp] 按应用实例提供 (默认值仅为不崩, 取不到矩形即不做容器变换)。 */
val LocalBookCardRects = staticCompositionLocalOf { BookCardRectRegistry() }

/**
 * 本列表所在页当前是否可见。
 *
 * 单个路由 entry 内可以同时组合多份书籍列表, 且它们共享同一个 [RoutePageAnchorScope]:
 * MainScreen 的 tab pager (beyondViewportPageCount=3, 四个 tab 全驻留)、BookshelfScreen 的分组
 * pager (相邻分组页已组合), 加上 BookGroupDao.flowByUserGroup 是位掩码查询, 同一本书还会同时
 * 出现在"全部"页与用户分组页。pager 把离屏页摆在屏幕外 (真实布局位移, 不是图层变换), 其卡片
 * 矩形是屏外值 —— 若一并登记, 会覆盖掉可见页那份正确矩形, 形变从屏外起手。
 *
 * 故只有可见页允许登记。默认 true: 不在 pager 内的列表 (搜索页/发现show 各自是独立路由页)
 * 无需接线。
 */
val LocalBookListActive = compositionLocalOf { true }

/**
 * 列表条目容器变换锚点: 铺满条目已测尺寸, 只上报自身矩形, 不绘制、不参与父布局测量。
 *
 * 常驻挂载 (不按"是否正在转场"开关): 容器变换要的是**导航发生之前**卡片就已摆放过的位置,
 * 段开始那一刻才挂就永远拿不到起手矩形。
 *
 * 支持传入 [origin] 构造复合标识 `${origin}|${bookUrl}` (见 [containerAnchorKey]),
 * 区分同页多书源同 URL 卡片, 避免互相覆盖与回收时误注销。
 */
private val DefaultContainerTransformCornerRadius = 12.dp

/**
 * 统一卡片锚点容器。内容先测量，锚点后声明并以 `matchParentSize` 读取既有尺寸；调用点无需再手写
 * `Box + Anchor`，也不会意外颠倒声明顺序。
 */
@Composable
fun ContainerTransformCard(
    identity: ContainerTransformIdentity,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = DefaultContainerTransformCornerRadius,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        content()
        ContainerTransformAnchor(identity, cornerRadius)
    }
}

/** 兼容独立锚点场景；新卡片优先使用 [ContainerTransformCard]。 */
@Composable
fun BoxScope.ContainerTransformAnchor(
    bookUrl: String,
    origin: String? = null,
    cornerRadius: Dp = DefaultContainerTransformCornerRadius,
) {
    ContainerTransformAnchor(ContainerTransformIdentity(bookUrl, origin), cornerRadius)
}

@Composable
private fun BoxScope.ContainerTransformAnchor(
    identity: ContainerTransformIdentity,
    cornerRadius: Dp,
) {
    if (!LocalBookListActive.current) return
    val page = LocalRoutePageAnchor.current ?: return
    val registry = LocalBookCardRects.current
    val token = remember { Any() }
    val cornerRadiusPx = with(LocalDensity.current) { cornerRadius.toPx() }
    // 条目被 LazyGrid 回收 / 所在 pager 页离屏 / 整页出栈时只抹掉本实例登记。
    DisposableEffect(registry, page, identity, token) {
        onDispose { registry.forget(page.pageKey, identity, token) }
    }
    Box(
        Modifier
            // matchParentSize 匹配由其他子节点决定的已测尺寸, 对父布局零影响
            // (fillMaxSize 会让 Box 撑开)
            .matchParentSize()
            .onGloballyPositioned { coords ->
                val pageCoords = page.coords ?: return@onGloballyPositioned
                registry.record(
                    pageKey = page.pageKey,
                    identity = identity,
                    token = token,
                    bounds = ContainerAnchorBounds(
                        // 取 localPositionOf + size 而非 boundsInRoot: 后者按各级父布局裁剪,
                        // 半滚出视口的卡片会拿到残缺矩形, 形变起手尺寸就错
                        rect = Rect(
                            pageCoords.localPositionOf(coords, Offset.Zero),
                            coords.size.toSize(),
                        ),
                        viewportSize = pageCoords.size,
                        cornerRadiusPx = cornerRadiusPx,
                    ),
                )
            }
    )
}

/**
 * 内容淡入淡出占容器开合度的比例: 容器张开到这个程度时内容已完全不透明。
 *
 * 不可直接用段进度当 alpha: 平台转场曲线普遍前重 (桌面 Fluent 的 0.1,0.9,0.2,1 在 20% 时间
 * 就走完 90% 进度), 返回时页面几十毫秒就透明了, 后面的收缩过程根本看不见 —— 观感就是
 * "返回没有动画"。改跟开合度后两个方向对称, 形变全程可见。
 *
 * 也不能整段不淡: 起手帧整页被非等比压成卡片形状, 全不透明会把那几帧的拉伸失真直接暴露出来。
 */
internal const val ContainerContentFadeOpenness = 0.25f

/**
 * 容器变换绘制: [startBounds] 非空时用段开始录下的页内容代替实时绘制, 从其中的卡片矩形逐帧长到
 * 全屏; 为空则原样绘制。录一次、逐帧只贴一次的理由与分端实现见 [ContainerSnapshot]。
 *
 * **必须接在 `.background(...)` 之前**: 修饰符链靠前的画在外层, `drawContent()` 只能录到
 * 自己之后的东西 —— 接在 background 之后会录不到页底色, 录制内容缺底。
 *
 * 仅书籍候选页面挂载录制层 (由 [LegadoApp] 按页面身份条件挂载): 未进入容器变换的普通
 * 路由避免无条件 rememberGraphicsLayer() 与 ContainerSnapshot 申请开销。
 *
 * @param openness 0=卡片位置与尺寸, 1=全屏; 只在 draw 阶段读, 逐帧不重组
 * @param contentAlpha 内容不透明度 (见 [ContainerContentFadeOpenness])
 */
@Composable
internal fun Modifier.containerTransformDraw(
    startBounds: ContainerAnchorBounds?,
    openness: () -> Float,
    contentAlpha: () -> Float,
): Modifier {
    // 按页存活的录制层: rememberGraphicsLayer 负责离开组合时归还给 GraphicsContext
    val layer = rememberGraphicsLayer()
    val snapshot = remember { ContainerSnapshot() }
    // 段结束即释放离屏资源并把图层属性归位 (全屏离屏约 8MB, 不常驻)
    if (startBounds == null) snapshot.release(layer)
    return this.drawWithContent {
        val pageSize = IntSize(size.width.toInt(), size.height.toInt())
        if (startBounds == null || pageSize.width <= 0 || pageSize.height <= 0) {
            drawContent()
            return@drawWithContent
        }
        // 页尺寸变了 (桌面拖窗) 就作废重录
        if (snapshot.capturedSize != pageSize) {
            // 录制入口交给各端在自己需要的时机调用 (见 ContainerSnapshot.capture)。
            // 不能直接把 drawContent() 引向自建 canvas —— 它只往 drawContext.canvas 画,
            // 而 record 正是官方提供的"临时换掉那个 canvas"入口
            snapshot.capture(this, layer, pageSize) {
                layer.record(pageSize) { this@drawWithContent.drawContent() }
            }
        }
        // 起点与登记时页面坐标系绑定。转场中窗口变化时，先把起点同步换算到当前视口，
        // 再向当前 pageSize 插值；快照重录与几何坐标更新因此使用同一尺寸版本。
        val sourceViewport = startBounds.viewportSize
        val scaleX = if (sourceViewport.width > 0) {
            pageSize.width.toFloat() / sourceViewport.width
        } else {
            1f
        }
        val scaleY = if (sourceViewport.height > 0) {
            pageSize.height.toFloat() / sourceViewport.height
        } else {
            1f
        }
        val sourceRect = startBounds.rect
        val startRect = Rect(
            left = sourceRect.left * scaleX,
            top = sourceRect.top * scaleY,
            right = sourceRect.right * scaleX,
            bottom = sourceRect.bottom * scaleY,
        )
        val startCornerRadiusPx = startBounds.cornerRadiusPx * min(scaleX, scaleY)
        val p = openness().coerceIn(0f, 1f)
        val width = startRect.width + (pageSize.width - startRect.width) * p
        val height = startRect.height + (pageSize.height - startRect.height) * p
        // 中心点插值: 中心从卡片中心线性走向视口中心, 缩放围绕中心进行 (不再左上角对齐)
        val centerX = startRect.center.x + (pageSize.width / 2f - startRect.center.x) * p
        val centerY = startRect.center.y + (pageSize.height / 2f - startRect.center.y) * p
        val left = centerX - width / 2f
        val top = centerY - height / 2f
        snapshot.draw(
            scope = this,
            layer = layer,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(
                width.roundToInt().coerceAtLeast(1),
                height.roundToInt().coerceAtLeast(1),
            ),
            alpha = contentAlpha().coerceIn(0f, 1f),
            cornerRadiusPx = startCornerRadiusPx * (1f - p),
        )
    }
}
