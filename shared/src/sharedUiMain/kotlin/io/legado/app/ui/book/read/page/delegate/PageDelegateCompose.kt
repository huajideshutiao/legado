package io.legado.app.ui.book.read.page.delegate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.AutoPagerCompose
import io.legado.app.ui.book.read.page.MouseDragDelegate
import io.legado.app.ui.book.read.page.PageDelegateShared
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 翻页动画委托基类（sharedUiMain，Compose Multiplatform 版）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.delegate.PageDelegate` 对应，
 * 用 Compose 跨平台 API 替代 Android `View` / `Canvas` / `Scroller` / `MotionEvent`：
 *
 * # 核心机制对照
 *
 * | app 端（Android）             | KMP 版（Compose）                                    |
 * | ---                          | ---                                                  |
 * | `Scroller.startScroll(dx,dy)`| `Animatable.animateTo(target, tween)`                |
 * | `View.invalidate()` 驱动重绘  | `mutableStateOf<Float>` 触发 Compose 重组            |
 * | `CanvasRecorder.screenshot()`| 直接渲染 3 个 `PageViewComposable` lambda（无需截图）|
 * | `Canvas.withTranslation`     | `Modifier.offset { IntOffset(x, 0) }`                |
 * | `Canvas.withClip`            | 上层 `Box` 用 offset 移出视口外（不可见即等价 clip）  |
 * | `GradientDrawable` 阴影      | `Brush.horizontalGradient` + `drawRect`              |
 * | `MotionEvent` 分发           | 统一触摸分发器（ReadViewComposable 一层 pointerInput）|
 * | `VelocityTracker`            | 分发器内 `VelocityTracker` + `Animatable`            |
 *
 * # 与 app 端的差异
 *
 * - app 端 `PageDelegate(readView: ReadView)` 持有 Android View 引用；
 *   KMP 版改为 `PageDelegate(viewModel, scope, animationSpeed)` 依赖注入，无 View 引用
 * - app 端 `onDraw(canvas: Canvas)` 抽象方法 → KMP 版改为 `@Composable renderPageAnimation(...)`
 * - app 端 `onTouch(event: MotionEvent)` → KMP 版拆为 `onDown` / `onScroll` / `onTouchUp`（命令式接口），
 *   由 [io.legado.app.ui.book.read.page.ReadViewComposable] 的单一触摸分发器调用，delegate 自身不挂手势
 * - app 端 `CanvasRecorder` 截图三页 → KMP 版直接渲染 3 个 `@Composable` lambda + `Modifier.offset`
 *
 * # 章节边界联动
 *
 * [onAnimStop] 内按 mDirection 调 [ReadBookViewModelShared.nextPage] / [prevPage]；
 * 返回 false（章节末页 / 首页）时由 delegate 调 [moveToNextChapter] / [moveToPrevChapter] 切章。
 *
 * @param viewModel 阅读 ViewModel，提供 prevTextPage/curTextPage/nextTextPage 流 + 翻页 API
 * @param scope 协程作用域，actual 平台注入（桌面=应用主作用域 / Android=viewModelScope）
 * @param animationSpeed 默认动画速度（ms/页宽，与 app 端 `defaultAnimationSpeed=300` 对应）
 */
abstract class PageDelegateCompose(
    protected val viewModel: ReadBookViewModelShared,
    protected val scope: CoroutineScope,
    protected val animationSpeed: Int = DEFAULT_ANIMATION_SPEED,
) : PageDelegateShared, MouseDragDelegate {

    companion object {
        // 与 app 端 ReadView.defaultAnimationSpeed=300 对应
        const val DEFAULT_ANIMATION_SPEED = 300

        // 按键翻页动画速度 (对照原版 PageDelegate.keyTurnPage → nextPageByAnim(100)):
        // 音量键/方向键等按键翻页用快速动画, 点击翻页走 DEFAULT_ANIMATION_SPEED (300ms)。
        // 与 200ms 翻页节流配合, 动画在下次按键前完成, 长按连翻不吞页。
        const val KEY_TURN_ANIMATION_SPEED = 100

        // 与 app 端 CoverPageDelegate shadowDrawableR setBounds(0,0,30,viewHeight) 对应
        const val SHADOW_WIDTH_PX = 30
    }

    // region 状态字段（实现 PageDelegateShared 接口）
    override var isMoved: Boolean = false
    override var noNext: Boolean = true

    /**
     * 当前翻页方向。
     *
     * 用 `mutableStateOf` 作为 backing field，让 [renderPageAnimation] 内 `when(mDirection)`
     * 在方向变化时自动重组（如 onDown 设 NONE → 隐藏 prev/next 层）。
     */
    private val _mDirection = mutableStateOf(PageDirectionShared.NONE)
    override var mDirection: PageDirectionShared
        get() = _mDirection.value
        set(value) { _mDirection.value = value }

    override var isCancel: Boolean = false
    override var isRunning: Boolean = false
    override var isStarted: Boolean = false

    /**
     * 动画被打断标志（对照旧 ReadView.isAbortAnim）：abortAnim 打断进行中的动画时置位，
     * nextPageByAnim/prevPageByAnim 首次调用吞一次（动画中点击只打断不翻页）；
     * 九宫格中心格点击由分发层按本标志忽略（对照旧 onSingleTapUp 的
     * clickArea.isCenter && isAbortAnim → return）。
     */
    var isAbortAnim: Boolean = false
    // endregion

    // 视图尺寸（由 setViewSize 注入）
    protected var viewWidth: Int = 0
    protected var viewHeight: Int = 0

    // 触摸点状态（与 app 端 PageDelegate.startX/startY/lastX/lastY/touchX/touchY 字段对应）
    protected var startX: Float = 0f
    protected var startY: Float = 0f
    protected var lastX: Float = 0f
    protected var lastY: Float = 0f
    protected var touchX: Float = 0f
    protected var touchY: Float = 0f

    /**
     * 翻页动画偏移：0 表示静止。
     *
     * 用 `mutableStateOf` 让 Compose 自动重组（替代 Android View.invalidate 驱动）。
     * 子类按翻页方向定义具体语义：
     * - 横向翻页（Cover/Slide/NoAnim/Simulation）：PREV >0 / NEXT <0
     * - 滚动翻页（Scroll）：垂直方向位移
     */
    protected var _currentOffset by mutableStateOf(0f)
    val currentOffset: Float get() = _currentOffset

    /**
     * 当前动画协程引用，用于 [abortAnim] 时取消正在执行的动画。
     */
    protected var animJob: Job? = null

    /**
     * 点击落点 → 动作分发，由 [io.legado.app.ui.book.read.page.ReadViewComposable] 注入。
     *
     * 对照 app 端 `ReadView.onSingleTapUp`：区域判定与动作执行属于 ReadView 而非 delegate。
     * 未注入时子类 [onTap] 退回内置的三等分左右/上下翻页。
     */
    var onTapAt: ((Float, Float) -> Unit)? = null

    // region PageDelegateShared 接口默认实现

    override fun setViewSize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    override fun computeScroll(): Boolean = isRunning

    override fun setDirection(direction: PageDirectionShared) {
        mDirection = direction
    }

    override fun keyTurnPage(direction: PageDirectionShared) {
        // 不做 isRunning 拦截: 动画进行中的按键由子类 nextPageByAnim/prevPageByAnim 内部的
        // abortAnim 打断重翻 (对照原版 keyTurnPage → nextPageByAnim → abortAnim 语义), 否则
        // 快速连按时动画未结束的合法按键会被静默丢弃 (表现为"只能按一下")。
        // 按键翻页用快速动画 (对照原版 keyTurnPage → nextPageByAnim(100)): 与 200ms
        // 翻页节流 (VolumeKeyPageTurnHandler 的 PageTurnThrottle) 配合, 动画在下次按键前
        // 完成, 长按连翻不掉拍; 若用常规 300ms, 动画未结束即被下一次按键 abortAnim 打断,
        // isAbortAnim 置位会让再下一次按键被吞, 连翻速度减半。
        when (direction) {
            PageDirectionShared.NEXT -> nextPageByAnim(KEY_TURN_ANIMATION_SPEED)
            PageDirectionShared.PREV -> prevPageByAnim(KEY_TURN_ANIMATION_SPEED)
            else -> Unit
        }
    }

    /**
     * 点击翻页 (对照原版 ReadView.click → nextPageByAnim(defaultAnimationSpeed)):
     * 用常规动画速度 [animationSpeed], 与按键翻页的快速动画区分。
     */
    override fun clickTurnPage(direction: PageDirectionShared) {
        when (direction) {
            PageDirectionShared.NEXT -> nextPageByAnim(animationSpeed)
            PageDirectionShared.PREV -> prevPageByAnim(animationSpeed)
            else -> Unit
        }
    }

    /**
     * 更新手势起点（对照旧 ReadView.setStartPoint：只更新 start/last/touch 坐标，
     * 不打断动画、不清偏移）。多指触控切换跟踪手指用（对照旧 ACTION_POINTER_DOWN/UP
     * 分支的 setStartPoint）。
     */
    open fun setStartPoint(x: Float, y: Float) {
        startX = x
        startY = y
        lastX = x
        lastY = y
        touchX = x
        touchY = y
    }

    override fun hasPrev(): Boolean {
        // 本章节有上一页 或 有上一章可切（由 viewModel.canMoveToPrevChapter 兜底）
        val hasPrev = viewModel.prevTextPage.value != null || viewModel.canMoveToPrevChapter()
        // 对照原版 PageDelegate.hasPrev: 无上一页时长 toast 提示
        if (!hasPrev) {
            Toasters.get().toastLong(appString(AppStringKey.no_prev_page))
        }
        return hasPrev
    }

    override fun hasNext(): Boolean {
        // 本章节有下一页 或 有下一章可切（由 viewModel.canMoveToNextChapter 兜底）
        val hasNext = viewModel.nextTextPage.value != null || viewModel.canMoveToNextChapter()
        // 对照原版 PageDelegate.hasNext: 无下一页时长 toast 提示 (原版顺带 autoPageStop,
        // KMP 版自动翻页走 AutoPagerCompose.turnPage 不经本方法, 无需重复停止)
        if (!hasNext) {
            Toasters.get().toastLong(appString(AppStringKey.no_next_page))
        }
        return hasNext
    }

    override fun onDestroy() {
        // 释放动画协程，避免内存泄漏（与 app 端 PageDelegate.onDestroy + recycle 行为对应）
        abortAnim()
    }

    /**
     * 用 [Animatable] 驱动 [_currentOffset] 从当前值动画到 [target]。
     *
     * 替代 app 端 `Scroller.startScroll` + `View.invalidate` 驱动循环：
     * - `Animatable.animateTo` 是 suspend，每帧自动调用 lambda 更新 [_currentOffset]
     * - Compose `mutableStateOf` 触发 recomposition，让 `Modifier.offset` 重新计算
     * - 动画结束自然继续执行 [onAnimStop]（无需外部 computeScroll poll）
     */
    protected suspend fun animateOffsetTo(target: Float, durationMs: Int) {
        val start = _currentOffset
        // 防止 start == target 时 animateTo 立即返回（仍需触发 onAnimStop）
        if (start == target) {
            onAnimStop()
            return
        }
        val duration = if (durationMs <= 0) animationSpeed else durationMs
        val anim = Animatable(start)
        anim.animateTo(
            targetValue = target,
            // 线性缓动：对照原版 PageDelegate.scroller = Scroller(context, LinearInterpolator()),
            // startScroll 的 SCROLL_MODE 用构造传入 interpolator（AOSP Scroller.java
            // computeScrollOffset 非 FLING 分支），翻页动画即匀速；fling 的样条曲线
            // 由 SplineFling 承担，与缓动无关
            animationSpec = tween(durationMillis = duration, easing = LinearEasing),
        ) {
            _currentOffset = value
            onAnimOffsetChanged(value)
        }
        onAnimStop()
    }

    /**
     * 动画每帧偏移回调。滚动模式覆写为同步 viewModel.scrollOffset —— 原版 pageOffset 是
     * 单一真相源, 动画期间每帧更新, 消费方 (朗读起点/选区/列命中) 才读得到真实偏移;
     * 横向翻页 delegate 的偏移不是滚动偏移, 默认空实现不消费。
     */
    protected open fun onAnimOffsetChanged(offset: Float) = Unit

    /**
     * 自动翻页控制器（由 [io.legado.app.ui.book.read.page.AutoPagerCompose] 挂载）。
     *
     * 手势暂停/恢复/翻页复位钩子：手势按下（[abortAnim]）→ pause，动画结束
     * （[resetState]）→ resume，实际换页（onAnimStop/abortAnim 补页分支）→ reset。
     * 用 `mutableStateOf` 承载，渲染侧（ReadViewComposable 揭示覆盖层）订阅变化。
     */
    var autoPager: AutoPagerCompose? by mutableStateOf(null)

    /**
     * 自动翻页连续滚动驱动（滚动模式）：按推进量滚动（px，正值 = 内容上移露出下一页）。
     *
     * 基类默认忽略（非滚动模式由 AutoPagerCompose 的揭示动画覆盖层承担）；
     * [ScrollPageDelegateCompose] 覆写为走行级 scroll 折算（页边界翻页/钳制，
     * 对照旧 AutoPager 每帧 curPage.scroll(-scrollOffset)）。
     *
     * @return false = 命中硬边界（书首/书末），调用方应停止自动翻页
     */
    open fun onAutoScrollBy(deltaPx: Float): Boolean = true

    /** 滚动视口高度（px），自动翻页按 height / autoReadSpeed 秒换算每拍推进量 */
    open val autoScrollHeight: Int get() = viewHeight

    /**
     * 自动翻页结束复位：清除滚动驱动留下的状态（由 [AutoPagerCompose.stop] 调用）。
     * 基类默认空实现；需要回滚偏移的子类覆写。
     */
    open fun onAutoScrollEnd() {}

    /**
     * 抬手/取消（触摸统一分发器在 UP/CANCEL 且 [isMoved] 时调用；对照原版
     * PageDelegate.onTouch 的 ACTION_UP/ACTION_CANCEL 分支 → onAnimStart）。
     *
     * @param velocityY 手势末速度 y 分量（px/s）：仅滚动模式惯性滚动使用，横向模式忽略
     */
    open fun onTouchUp(x: Float, y: Float, velocityY: Float) {
        onAnimStart(animationSpeed)
    }

    /**
     * 重置状态字段（与 app 端 stopScroll 行为对应）。
     *
     * 子类 [onAnimStop] 在 finally 块中调用，确保动画结束后状态归零。
     * 动画结束同时恢复自动翻页推进（对照原版 onScrollAnimStop → autoPager.resume）。
     */
    protected fun resetState() {
        isStarted = false
        isMoved = false
        isRunning = false
        isCancel = false
        mDirection = PageDirectionShared.NONE
        _currentOffset = 0f
        animJob = null
        autoPager?.resume()
    }

    // endregion

    // region Compose 渲染入口（替代 app 端 onDraw(canvas: Canvas)）

    /**
     * 翻页动画渲染入口：由 [io.legado.app.ui.book.read.page.ReadViewComposable] 调用。
     *
     * actual 子类内部：
     * 1. 用 `Box` 容器，三个子 `@Composable` lambda 通过 `Modifier.offset` 控制位置
     * 2. 用 `Animatable` 自动驱动动画（替代 Android invalidate 驱动）
     * 3. 在最上层用 `Canvas` 或 `drawBehind` 绘制阴影 / 边缘高光等叠加效果
     *
     * 本方法只做渲染、不挂任何手势：触摸手势统一由
     * [io.legado.app.ui.book.read.page.ReadViewComposable] 的分发层经 [onDown] / [onScroll] /
     * [onTouchUp] 驱动（对照原版 ReadView.onTouchEvent → PageDelegate.onTouch）。
     *
     * @param pageWidthPx 视图宽度（px，由 BoxWithConstraints.maxWidth 转 px）
     * @param pageHeightPx 视图高度（px，由 BoxWithConstraints.maxHeight 转 px）
     * @param prevContent 上一页内容（由调用方包装 `PageViewComposable(prevTextPage, ...)`）
     * @param curContent 当前页内容
     * @param nextContent 下一页内容
     * @param nextPlusContent 第 3 页内容（当前页之后的第 2 页）。仅滚动模式连排使用
     *   （对照原版 drawPage 的 relativePage(2)，章末短页 + 新章短页时视口下方需第 3 页
     *   补位，否则出现空白）；横向翻页模式各页自带完整背景，忽略本参数。
     */
    @Composable
    abstract fun renderPageAnimation(
        pageWidthPx: Int,
        pageHeightPx: Int,
        prevContent: @Composable () -> Unit,
        curContent: @Composable () -> Unit,
        nextContent: @Composable () -> Unit,
        nextPlusContent: @Composable () -> Unit = {},
    )

    /**
     * 阴影叠加层绘制（由子类在 [renderPageAnimation] 内的 `Canvas` 中调用）。
     *
     * 与 app 端 `CoverPageDelegate.addShadow` / `SimulationPageDelegate.drawCurrentPageShadow` 对应。
     * 子类按翻页方向定义阴影位置 / 渐变方向。
     */
    protected abstract fun DrawScope.drawShadow(currentOffset: Float, viewWidth: Int)

    // endregion
}
