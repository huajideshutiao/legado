package io.legado.app.ui.book.read.page.delegate

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isImage
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.SplineFling
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.splineFlingDecaySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * 滚动翻页 delegate（sharedUiMain，Compose Multiplatform 版，行级滚动）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate` +
 * `ContentTextView.scroll/drawPage` 对应，语义逐项对照：
 *
 * # 行级滚动模型（对照旧 ContentTextView.pageOffset）
 *
 * 偏移 [_currentOffset] 恒 ≤ 0：可视区顶相对当前页内容顶的位置，范围 (-当前页高, 0]。
 * - 拖过页顶 (offset > 0) → 立即切上一页，offset -= 新当前页高（旧 scroll 的
 *   `pageOffset > 0 → moveToPrev(true) → pageOffset -= textPage.height`）
 * - 拖过页底 (offset < -页高) → 立即切下一页，offset += 旧页高（旧 `pageOffset <
 *   -textPage.height → moveToNext(true) → pageOffset += height`，保持内容连续）
 * - 末页底部钳制：无下一页时 `offset = min(0, visibleHeight - 页高)`（旧末页分支），
 *   书首/书末硬边界返回 false 并中止惯性
 *
 * # 渲染快照（2026-08 重构：跨页错帧闪烁根治）
 *
 * 滚动渲染由 [io.legado.app.ui.book.read.page.ScrollPageView] 单画布三页连排承担
 * （复刻旧 drawPage 的单 View 模型），本 delegate 持有渲染快照：
 * 三页数据（[renderPage]）+ 偏移（[contentOffset]）在手势折算的**同一同步块**内更新，
 * 绘制帧读取恒自洽——原三槽位结构中页数据经 StateFlow→重组（滞后一帧）与偏移
 * （绘制期当帧）跨帧分裂，跨页瞬间旧页错位一整页高闪一帧。
 * 页切换由 [renderVersion]（snapshot state）驱动同帧重绘（draw 阶段快照读）。
 * 外部路径（切章/重排/跳页/初始）经 [syncRenderPages] 从四页流同步（引用相等跳过）。
 *
 * # 保留一行翻页（对照旧 ScrollPageDelegate.calcNextPageOffset/calcPrevPageOffset）
 *
 * 点击/快捷键翻页（[nextPageByAnim]/[prevPageByAnim]，经 keyTurnPage 供九宫格点击复用）：
 * - 目标行取**三页可见行合成页**（对照旧 getCurVisiblePage，含下一页已露出的行）
 * - 增量驱动滚动动画（对照旧 Scroller.startScroll + computeScroll 的增量折算）：
 *   越过页边界时由 [applyScrollDelta] 自动折算切页，全程无瞬跳
 *
 * # 惯性滚动（对照旧 Scroller.fling + VelocityTracker）
 *
 * 手势用 [VelocityTracker] 追踪速度，松手后 [AnimationState.animateDecay] 衰减；
 * 衰减期间逐帧走同一套边界/翻页折算（旧 fling 由 computeScroll 驱动 scroll()）。
 * 惯性停止后对齐到最近行边界（对照旧 scrollToLine 的行对齐语义）。
 *
 * # 手势与点击共存（对照旧 ReadView.onTouchEvent）
 *
 * 触摸手势由 ReadViewComposable 的单一分发层驱动：越过 touchSlop 后才调 [onScroll]
 * （旧 isMove 判定）；未移动的抬手由分发层按单击处理（旧 !isMoved → click 分支）。
 * 长按检测同样在分发层（对照旧 postDelayed(longPressRunnable)）。
 * 动画中点击（旧 isAbortAnim）：abortAnim 打断动画时置位，nextPageByAnim/prevPageByAnim
 * 吞一次（对照旧吞语义），九宫格中心格点击由分发层按 isAbortAnim 忽略。
 *
 * @param viewModel 阅读 ViewModel
 * @param scope 协程作用域
 * @param animationSpeed 默认动画速度（ms/页高）
 */
class ScrollPageDelegateCompose(
    viewModel: ReadBookViewModelShared,
    scope: CoroutineScope,
    animationSpeed: Int = DEFAULT_ANIMATION_SPEED,
) : PageDelegateCompose(viewModel, scope, animationSpeed) {

    // region 渲染快照（单画布三页连排, 绘制期读取）

    private var renderPage0: TextPage? = null
    private var renderPage1: TextPage? = null
    private var renderPage2: TextPage? = null

    /**
     * 快照版本号：页切换时自增（snapshot state）。ScrollPageView 组合期读它驱动重组
     * （tip/缓存就绪），Canvas draw 期读它订阅同帧重绘（页数据绘制期直读恒自洽）。
     */
    var renderVersion by mutableStateOf(0)
        private set

    /** 绘制期读取第 [index] 页（0=当前页, 1=下一页, 2=下下页）。 */
    fun renderPage(index: Int): TextPage? = when (index) {
        0 -> renderPage0
        1 -> renderPage1
        else -> renderPage2
    }

    /**
     * 四页流 → 渲染快照同步（外部路径：切章/重排/跳页/初始装载）。
     * 手势路径跨页已在 [applyScrollDelta] 同块同步，此处引用相等跳过。
     */
    fun syncRenderPages() {
        val p0 = viewModel.curTextPage.value
        val p1 = viewModel.nextTextPage.value
        val p2 = viewModel.nextPlusTextPage.value
        if (renderPage0 === p0 && renderPage1 === p1 && renderPage2 === p2) return
        renderPage0 = p0
        renderPage1 = p1
        renderPage2 = p2
        renderVersion++
    }

    /** 外部滚动偏移重置同步（切章/重排归零；只消费外部写入, 不回灌自己的写入） */
    fun onExternalScrollOffset(offset: Int) {
        if (_currentOffset.toInt() != offset) {
            _currentOffset = offset.toFloat()
        }
    }

    // endregion

    // region 状态

    /** 屏幕密度（组合期注入，惯性物理计算用：ppi 与 50dp/s 门控换算） */
    private var density: Float = 1f

    /** 组合期注入屏幕密度（依赖 LocalDensity） */
    fun provideDensity(density: Float) {
        if (this.density != density) {
            this.density = density
        }
    }
    // endregion

    init {
        // 滚动 delegate 创建即新会话: 滚动偏移归零 (对照旧 upContent(resetPageOffset=true))
        viewModel.updateScrollOffset(0)
        syncRenderPages()
    }

    // 覆写为纯判定不弹 toast: 滚动模式的边界提示与原版 ScrollPageDelegate 一致
    // (原版 onScroll 钳制/nextPageByAnim 都不走带 toast 的 hasNext/hasPrev,
    // 仅横向翻页 delegate 的基类实现弹 "没有上一页/下一页"), 否则每次滚动钳制判定
    // (onScroll 末页底部) 都会刷 toast
    override fun hasPrev(): Boolean =
        viewModel.prevTextPage.value != null || viewModel.canMoveToPrevChapter()

    override fun hasNext(): Boolean =
        viewModel.nextTextPage.value != null || viewModel.canMoveToNextChapter()

    /**
     * 当前滚动偏移 (px, ≤ 0)。供 ScrollPageView 注入 graphicsLayer 的
     * translationY: 在 graphicsLayer 绘制阶段读取, 不触发重组。
     */
    val contentOffset: Float get() = _currentOffset

    /**
     * 自动翻页连续滚动驱动：按推进量走行级 scroll 折算（对照原版 AutoPager 每帧
     * `curPage.scroll(-scrollOffset)`）。正 deltaPx = 内容上移露出下一页；
     * 负值（上一页方向）同样折算，但自动翻页只推进正值。
     *
     * @return false = 命中硬边界（书首/书末），调用方应停止自动翻页
     */
    override fun onAutoScrollBy(deltaPx: Float): Boolean = applyScrollDelta(-deltaPx)

    // region 滚动核心算法 (对照旧 ContentTextView.scroll :138-170)

    /**
     * 应用滚动增量并折算页边界 (对照旧 scroll() 的四个分支, 顺序一致):
     * 1. offset > 0 → 切上一页 (本章无上一页时切章), offset -= 新当前页高
     * 2. 无下一页且页底已到视口底之上 → 钳制 offset = min(0, visibleHeight - 页高)
     * 3. offset < -页高 → 切下一页 (章末切章), offset += 旧当前页高
     *
     * 跨页/跨章时页切换（StateFlow）与偏移折算在**同一同步块**内完成，并立即同步
     * 渲染快照（对照原版 scroll() 内 moveToNext → setContent 与 pageOffset 折算
     * 同帧的原子性）——绘制帧读到的页+偏移恒自洽。
     *
     * @return false = 命中硬边界 (书首/书末), 调用方应停止惯性并复位手势
     */
    private fun applyScrollDelta(delta: Float): Boolean {
        var offset = _currentOffset + delta
        val cur = viewModel.curTextPage.value
        if (cur == null) {
            _currentOffset = 0f
            viewModel.updateScrollOffset(0)
            return true
        }
        val h = cur.height.toFloat()
        // 页高为 0 (空章空标题页/配置异常时的占位页): 不折算, 否则 offset < -h 恒成立且
        // offset += 0 不推进, 一次手势会连翻几十页; 同时丢弃增量不累积 (页高 0 期间无内容
        // 可滚, 累积会在页高恢复后折算成跳变/连翻)
        if (h <= 0f) {
            return true
        }
        when {
            // 拖过页顶: 上一页从上方滑入 (旧 moveToPrev 分支, 内容连续性由 offset 折算保证)
            offset > 0f -> {
                val oldOffset = offset
                if (!viewModel.prevPage()) {
                    if (!viewModel.moveToPrevChapter()) {
                        // 书首: 归零并中止 (旧 !hasPrev → pageOffset = 0 + abortAnim)
                        _currentOffset = 0f
                        viewModel.updateScrollOffset(0)
                        return false
                    }
                    viewModel.markScrollCrossingPending()
                }
                // 页切换与偏移折算同一同步块: 绘制帧读到的新页+新偏移恒自洽
                syncRenderPages()
                // 新当前页高折算 (旧 pageOffset -= textPage.height, 此时 textPage 已换为新页)
                offset = oldOffset - (viewModel.curTextPage.value?.height ?: 0f)
            }

            // 末页底部钳制 (旧 !hasNext 分支): 末行对齐视口底后不再上滚
            !hasNext() && offset < 0f && offset + h < cur.visibleHeight.toFloat() -> {
                offset = min(0f, cur.visibleHeight.toFloat() - h)
                _currentOffset = offset
                viewModel.updateScrollOffset(offset.toInt())
                return false
            }

            // 拖过页底: 下一页从下方滑入 (旧 moveToNext 分支)
            offset < -h -> {
                if (!viewModel.nextPage()) {
                    if (!viewModel.moveToNextChapter()) {
                        // 全书末: 钳制到页底 (旧 moveToNext 失败 → pageOffset = -height)
                        _currentOffset = -h
                        viewModel.updateScrollOffset((-h).toInt())
                        return false
                    }
                    viewModel.markScrollCrossingPending()
                }
                // 同上: 页切换与偏移折算同一同步块
                syncRenderPages()
                // 旧页高折算 (旧 pageOffset += textPage.height, height 在 moveToNext 前取值)
                offset += h
            }
        }
        _currentOffset = offset
        viewModel.updateScrollOffset(offset.toInt())
        return true
    }

    /**
     * 鼠标滚轮滚动入口 (ReaderRoute 滚轮翻页调用): 行级滚动 + 页边界折算,
     * 滚过页底自动切入下一页, 与拖拽滚动同一套折算 (互不干扰)。
     *
     * 用户主动滚动: 先打断惯性/行对齐动画 (abortAnim, 对照手势 onDown), 从当前偏移接管;
     * abortAnim 暂停了自动翻页 (pause), 滚轮是离散事件, 处理完立即配对恢复 (resume 重置时间基准,
     * 自动翻页在滚轮期间不推进、滚轮停止后恢复)。
     *
     * @return false = 命中硬边界 (书首/书末), 调用方按需处理
     */
    fun scrollBy(deltaPx: Float): Boolean {
        if (deltaPx == 0f) return true
        abortAnim()
        val ok = applyScrollDelta(deltaPx)
        autoPager?.resume()
        return ok
    }

    /**
     * 带动画小步滚动 (方向键滚动用, 用户拍板: 方向键滚动要有动画):
     * 页内部分动画 (与整页翻页同一 [animateScrollBy] 机制), 越界部分同步折算跨页
     * (与 nextPageByAnim 的跨页瞬切一致); abortAnim 打断后从当前偏移重算, 连按流畅。
     */
    fun scrollByAnimated(deltaPx: Float, animDurationMs: Int = 200): Boolean {
        if (deltaPx == 0f) return true
        abortAnim()
        val h = viewModel.curTextPage.value?.height?.toFloat() ?: return false
        // 页内可滚量 (offset 范围 (-h, 0]): 向下滚到页底 / 向上滚到页顶
        val remaining = if (deltaPx < 0f) (-h - _currentOffset) else -_currentOffset
        val inPage = if (deltaPx < 0f) maxOf(deltaPx, remaining) else minOf(deltaPx, remaining)
        if (inPage != 0f) {
            isStarted = true
            isRunning = true
            animJob?.cancel()
            animJob = scope.launch {
                animateScrollBy(inPage, animDurationMs)
                onAnimStop()
            }
        }
        // 越界部分同步折算 (跨页), 与 scrollBy 一致恢复自动翻页
        val rest = deltaPx - inPage
        if (rest != 0f) {
            val ok = applyScrollDelta(rest)
            autoPager?.resume()
            return ok
        }
        return true
    }

    // endregion

    // region 保留一行翻页 (对照旧 ScrollPageDelegate.calcNextPageOffset/calcPrevPageOffset :139-157)

    /**
     * 三页可见行合成页（复刻旧 ContentTextView.getCurVisiblePage）：遍历 relativePos
     * 0..2，把 [TextPage.isVisible] 的行平移相对偏移后收进合成页；下一页顶已到视口底
     * 之下即止（旧 `relativeOffset >= visibleHeight` break）。
     *
     * 保留一行翻页的目标行取**合成页**末行/首行（含下一页已露出的行），
     * 而非当前页末行/首行——页底已露出下一页部分行时点击翻页目标不同（旧语义）。
     */
    private fun curVisiblePage(): TextPage {
        val visiblePage = TextPage()
        val visibleHeight = viewModel.curTextPage.value?.visibleHeight ?: 0
        var rel = _currentOffset
        for (i in 0..2) {
            val page = renderPage(i) ?: continue
            if (i > 0 && rel >= visibleHeight) break
            for (line in page.lines) {
                if (line.isVisible(rel)) {
                    visiblePage.addLine(
                        line.copy().apply {
                            lineTop += rel
                            lineBottom += rel
                        }
                    )
                }
            }
            rel += page.height
        }
        return visiblePage
    }

    /** 点击下一页目标偏移: 可见合成页末行对齐视口顶 (图片页滚动整页可视高) */
    private fun calcNextPageOffset(): Float {
        val cur = viewModel.curTextPage.value ?: return 0f
        val book = viewModel.book.value
        val isTextStyle = book?.config?.imageStyle?.equals(
            Book.imgStyleText, true
        ) == true
        val visiblePage = curVisiblePage()
        if ((book == null || book.isImage) || (!isTextStyle && visiblePage.hasImageOrEmpty())) {
            return -cur.visibleHeight.toFloat()
        }
        if (visiblePage.lines.isEmpty()) return -cur.visibleHeight.toFloat()
        val lastLineTop = visiblePage.lines.last().lineTop
        return -(lastLineTop - cur.paddingTop)
    }

    /** 点击上一页目标偏移: 可见合成页首行底对齐视口底 (配合越界切页, 保留上一页末行) */
    private fun calcPrevPageOffset(): Float {
        val cur = viewModel.curTextPage.value ?: return 0f
        val book = viewModel.book.value
        val isTextStyle = book?.config?.imageStyle?.equals(
            Book.imgStyleText, true
        ) == true
        val visiblePage = curVisiblePage()
        if ((book == null || book.isImage) || (!isTextStyle && visiblePage.hasImageOrEmpty())) {
            return cur.visibleHeight.toFloat()
        }
        if (visiblePage.lines.isEmpty()) return cur.visibleHeight.toFloat()
        val firstLineBottom = visiblePage.lines.first().lineBottom
        return cur.visibleHeight.toFloat() - (firstLineBottom - cur.paddingTop)
    }

    // endregion

    // region PageDelegateShared 接口实现 (行级滚动)

    override fun onDown(x: Float, y: Float) {
        // 动画进行中按下立即中断 (对照旧 onTouch ACTION_DOWN 分支 abortAnim)
        abortAnim()
        isMoved = false
        noNext = false
        isRunning = false
        isCancel = false
        setDirection(PageDirectionShared.NONE)
        startX = x
        startY = y
        lastX = x
        lastY = y
        touchX = x
        touchY = y
        // 滚动偏移保持 (行级滚动位置不随按下重置, 对照旧 pageOffset 不被 onDown 清零)
    }

    override fun onScroll(x: Float, y: Float) {
        if (!isMoved) {
            val deltaY = y - startY
            if (deltaY == 0f) return
            isMoved = true
            // 滚动模式双向滚动, 无方向判定 (对照旧 ScrollPageDelegate.onScroll 只追踪移动)
            startY = y
            lastY = y
        }
        if (isMoved) {
            isRunning = true
            touchY = y
            val delta = y - lastY
            if (delta != 0f) {
                if (!applyScrollDelta(delta)) {
                    // 硬边界 (书首/书末): 复位手势, 后续移动重新起算 (对照旧 abortAnim 后重新追踪)
                    isMoved = false
                    isRunning = false
                }
            }
            lastY = y
        }
    }

    /**
     * 松手惯性滚动 (复刻 AOSP `Scroller.fling` 的 SplineOverScroller 样条曲线，
     * 对照旧 ScrollPageDelegate.onAnimStart 的 `fling(0, touchY, 0, mVelocity.yVelocity,
     * 0, 0, -10*viewHeight, 10*viewHeight)`): 以手势末速度逐帧推进，每帧走同一套
     * 边界/翻页折算（旧 fling 由 computeScroll 驱动 scroll()）。
     *
     * 曲线由 [SplineFling]（AOSP SplineOverScroller 逐行复刻）提供，经
     * [splineFlingDecaySpec] 适配为 Compose [androidx.compose.animation.core.DecayAnimationSpec]，
     * 复用 Compose animateDecay 动画框架的帧调度（对照原版 Scroller.computeScrollOffset
     * 的增量驱动）。|v| < 50dp/s 门控由 [SplineFling] 内置（duration=0 → animateDecay
     * 立即结束，对照 AOSP Scroller.fling 的 mMinimumVelocity 判定）。
     * 惯性停止即停，不做行对齐（2026-08 移除 snapToLine：原版 fling 停止处无行对齐）。
     */
    private fun onFling(velocityY: Float) {
        if (!isMoved) {
            // 无有效手势: 直接停（慢速拖放/边界复位后）。走 onAnimStop 而非只 resume:
            // 对照原版 computeScroll 的 else 分支 (onAnimStop + stopScroll) 把手势标志归零,
            // 否则 isStarted/isMoved/isRunning 残留 true 到下次按下
            onAnimStop()
            return
        }
        isStarted = true
        isRunning = true
        animJob?.cancel()
        animJob = scope.launch {
            // 惯性行程上限 ±10 屏 (对照原版 fling 的 minY/maxY)
            val travelLimit = if (viewHeight > 0) 10f * viewHeight else Float.MAX_VALUE
            // 增量必须取自动画自身轨迹: applyScrollDelta 折页时把 _currentOffset 折算 ±页高,
            // 而 animateDecay 的 value 沿原轨迹继续, 若用 value - _currentOffset 求增量, 首次
            // 折页后每帧都会得到约一页的增量 → 每帧翻一页 (一次惯性几十页)。
            // 对照原版 Scroller: 增量取 currY - lastY (scroller 自身坐标), 不受折算影响
            var lastValue = 0f
            AnimationState(0f, velocityY).animateDecay(splineFlingDecaySpec(velocityY, density)) {
                val bounded = value.coerceIn(-travelLimit, travelLimit)
                val delta = bounded - lastValue
                lastValue = bounded
                // 命中硬边界 (书首/书末) 或已到行程上限: 停止惯性 (对照原版 abortAnim)
                if (!applyScrollDelta(delta) || bounded != value) {
                    cancelAnimation()
                }
            }
            // 惯性结束: 复位手势标志并恢复自动翻页 (对照原版 computeScroll → onAnimStop + stopScroll)
            onAnimStop()
        }
    }

    /**
     * 动画每帧同步滚动偏移到 viewModel (对照原版动画期间每帧更新 pageOffset 的单一真相源):
     * animateScrollBy 只经 applyScrollDelta 双写, 增量驱动路径无独立局部偏移,
     * 本回调保留为空（横向 delegate 的 animateOffsetTo 覆写语义在此不适用）。
     */
    override fun onAnimOffsetChanged(offset: Float) = Unit

    override fun onTap(x: Float, y: Float): Boolean {
        // 优先走宿主注入的九宫格分发 (对照 app 端 ReadView.clickArea + click);
        // 滚动模式下动作 1/2 (翻页) 经 turnPage → keyTurnPage → nextPageByAnim 走保留一行滚动
        onTapAt?.let { dispatch ->
            dispatch(x, y)
            return true
        }
        // 未注入时按 y 位置区分：上 1/3 → 上一页，下 1/3 → 下一页，中间 → 交上层处理菜单
        if (viewHeight <= 0) return false
        val third = viewHeight / 3f
        return when {
            y < third -> {
                prevPageByAnim(animationSpeed)
                true
            }
            y > viewHeight - third -> {
                nextPageByAnim(animationSpeed)
                true
            }
            else -> false // 中心区域交给上层 onClick 处理（菜单显隐）
        }
    }

    override fun abortAnim() {
        // 取消正在执行的动画协程 (对照旧 abortAnim: 只取消 scroller, 不动滚动偏移)
        // 手动翻页手势开始：暂停自动翻页推进 (对照原版 onScrollAnimStart → autoPager.pause)
        autoPager?.pause()
        val running = animJob?.isActive == true
        animJob?.cancel()
        animJob = null
        isStarted = false
        isMoved = false
        isRunning = false
        // 对照旧 abortAnim: 动画被打断 (scroller 未完成) → isAbortAnim=true, 吞一次后续
        // nextPageByAnim/prevPageByAnim; 静止按下 → false 正常翻页
        isAbortAnim = running
    }

    override fun onAnimStart(animationSpeed: Int) {
        // 滚动模式动画由手势速度驱动 (onFling), 此入口无额外动作
    }

    override fun onTouchUp(x: Float, y: Float, velocityY: Float) {
        // 对照原版 ScrollPageDelegate.onTouch 的 UP/CANCEL → onAnimStart → fling：
        // 以手势末速度惯性滚动，无有效速度时直接停
        onFling(velocityY)
    }

    override fun onAnimStop() {
        // 滚动模式翻页在滚动过程中完成 (applyScrollDelta), 动画结束仅复位状态, 不清偏移
        isStarted = false
        isMoved = false
        isRunning = false
        isCancel = false
        mDirection = PageDirectionShared.NONE
        animJob = null
        // 动画结束恢复自动翻页推进 (对照原版 onScrollAnimStop → autoPager.resume)
        autoPager?.resume()
    }

    override fun nextPageByAnim(animDurationMs: Int) {
        // 吞一次 (对照旧 isAbortAnim): 动画中点击只打断不翻页; 吞后恢复自动翻页
        // (abortAnim 的 pause 配对)
        if (isAbortAnim) {
            isAbortAnim = false
            autoPager?.resume()
            return
        }
        if (!hasNext()) return
        abortAnim()
        // 目标 = 可见合成页末行对齐视口顶 (相对当前偏移的滚动量, ≤0)
        val target = calcNextPageOffset()
        isStarted = true
        isRunning = true
        animJob = scope.launch {
            // 增量驱动 (对照旧 startScroll + computeScroll 的 scroller 增量): 目标不越界时
            // 纯页内滚动; 合成页末行来自第 2 页时越过页底, applyScrollDelta 自动折算切页
            animateScrollBy(target, animDurationMs)
            onAnimStop()
        }
    }

    override fun prevPageByAnim(animDurationMs: Int) {
        // 吞一次 (对照旧 isAbortAnim, 同 nextPageByAnim)
        if (isAbortAnim) {
            isAbortAnim = false
            autoPager?.resume()
            return
        }
        if (!hasPrev()) return
        abortAnim()
        // 目标 = 可见合成页首行底对齐视口底 (相对当前偏移的滚动量, ≥0; 越过页顶时
        // applyScrollDelta 的 offset > 0 分支自动折算切上一页, 全程连续无瞬跳——
        // 对照旧 prevPageByAnim 的 startScroll 连续滚动 + scroll() 越界切页)
        val target = calcPrevPageOffset()
        isStarted = true
        isRunning = true
        animJob = scope.launch {
            animateScrollBy(target, animDurationMs)
            onAnimStop()
        }
    }

    /**
     * 增量滚动动画（对照旧 Scroller.startScroll + computeScroll 的增量驱动）：
     * 从 0 动画到 [delta]，每帧增量走 [applyScrollDelta]（越界自动折算切页/钳制），
     * 命中硬边界停止。时长按距离折算（对照旧 startScroll: duration =
     * animationSpeed * abs(dy) / viewHeight）。
     */
    private suspend fun animateScrollBy(delta: Float, animDurationMs: Int) {
        if (delta == 0f) return
        val duration = scrollDuration(delta, animDurationMs)
        var last = 0f
        // AnimationState.animateTo 的 block 是 AnimationScope（有 cancelAnimation），
        // 命中硬边界可中断；Animatable.animateTo 的 block 无取消入口不可用
        AnimationState(0f).animateTo(
            targetValue = delta,
            // 线性缓动：对照原版 PageDelegate.scroller = Scroller(context,
            // LinearInterpolator())，startScroll 翻页动画匀速（同 animateOffsetTo）
            animationSpec = tween(durationMillis = duration, easing = LinearEasing),
        ) {
            val step = value - last
            last = value
            if (!applyScrollDelta(step)) {
                cancelAnimation()
            }
        }
    }

    /** 动画时长折算 (对照旧 startScroll: duration = animationSpeed * abs(dy) / viewHeight) */
    private fun scrollDuration(delta: Float, animDurationMs: Int): Int {
        val distance = abs(delta)
        if (viewHeight <= 0) return animDurationMs
        return (animDurationMs * distance / viewHeight).toInt().coerceAtLeast(1)
    }

    // endregion

    // region Compose 渲染骨架 (行级滚动)

    /**
     * 滚动模式渲染已由 [io.legado.app.ui.book.read.page.ScrollPageView] 接管
     * （单画布三页连排，见该组件注释），本抽象覆写不再被调用。
     */
    @Composable
    override fun renderPageAnimation(
        pageWidthPx: Int,
        pageHeightPx: Int,
        prevContent: @Composable () -> Unit,
        curContent: @Composable () -> Unit,
        nextContent: @Composable () -> Unit,
        nextPlusContent: @Composable () -> Unit,
    ) = Unit

    /**
     * 滚动模式无阴影叠加 (对照旧 ScrollPageDelegate.onDraw: nothing)。
     */
    override fun DrawScope.drawShadow(currentOffset: Float, viewWidth: Int) = Unit

    // endregion
}
