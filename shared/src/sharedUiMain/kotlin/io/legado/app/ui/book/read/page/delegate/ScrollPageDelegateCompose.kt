package io.legado.app.ui.book.read.page.delegate

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isImage
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.column.TextColumn
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
 * # 渲染（对照旧 drawPage 三页连排）
 *
 * 当前页 + 下一页连排：下一页绘制位置 = offset + 当前页高（旧 relativeOffset(1)）。
 * 平移经 [io.legado.app.ui.book.read.page.PageViewComposable] 的 contentTranslationY
 * 提供者应用（graphicsLayer 绘制阶段读取，不触发重组），视口裁剪对照旧
 * `canvas.clipRect(visibleRect)`。页眉/页脚 tip 固定不随内容滚动（旧 PageView 布局）。
 *
 * # 保留一行翻页（对照旧 ScrollPageDelegate.calcNextPageOffset/calcPrevPageOffset）
 *
 * 点击/快捷键翻页（[nextPageByAnim]/[prevPageByAnim]，经 keyTurnPage 供九宫格点击复用）：
 * - 下一页：滚动到当前页末行对齐视口顶（图片页滚动整页可视高），页索引不变
 * - 上一页：先切上一页，再滚动到上一页末行对齐视口底
 *
 * # 惯性滚动（对照旧 Scroller.fling + VelocityTracker）
 *
 * 手势用 [VelocityTracker] 追踪速度，松手后 [AnimationState.animateDecay] 衰减；
 * 衰减期间逐帧走同一套边界/翻页折算（旧 fling 由 computeScroll 驱动 scroll()）。
 * 惯性停止后对齐到最近行边界（对照旧 scrollToLine 的行对齐语义）。
 *
 * # 手势与点击共存（对照旧 ReadView.onTouchEvent）
 *
 * 自定义 drag 循环越过 touchSlop 才开始消费并滚动（旧 isMove 判定）；未越过 slop 的
 * 事件不消费，detectTapGestures 正常触发单击/长按（旧 !isMoved → click 分支）。
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

    companion object {
        /** 惯性停止后行对齐动画时长 (ms) */
        private const val LINE_SNAP_MS = 150

        /** 单帧内最大翻页折算次数 (防御性上限, 正常单帧只跨一页) */
        private const val MAX_PAGE_TRANSITIONS = 64
    }

    // region 状态
    /** 惯性衰减 (splineBasedDecay, 由 renderPageAnimation 组合期注入) */
    private var decaySpec: DecayAnimationSpec<Float>? = null

    private var lastY: Float = 0f
    // endregion

    init {
        // 滚动 delegate 创建即新会话: 滚动偏移归零 (对照旧 upContent(resetPageOffset=true))
        viewModel.updateScrollOffset(0)
    }

    /**
     * 当前滚动偏移 (px, ≤ 0)。供 ReadViewComposable 注入 PageViewComposable 的
     * contentTranslationY 提供者: 在 graphicsLayer 绘制阶段读取, 不触发重组。
     */
    val contentOffset: Float get() = _currentOffset

    /**
     * 下一页内容平移量 (px) = 当前偏移 + 当前页高 (对照旧 drawPage 的 relativeOffset(1))。
     * 供 ReadViewComposable 注入 nextContent 的 contentTranslationY 提供者。
     */
    val nextContentOffset: Float
        get() = _currentOffset + (viewModel.curTextPage.value?.height ?: 0f)

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
     * @return false = 命中硬边界 (书首/书末), 调用方应停止惯性并复位手势
     */
    private fun applyScrollDelta(delta: Float): Boolean {
        var offset = _currentOffset + delta
        var transitions = 0
        while (transitions++ < MAX_PAGE_TRANSITIONS) {
            val cur = viewModel.curTextPage.value
            if (cur == null) {
                offset = 0f
                break
            }
            val h = cur.height.toFloat()
            when {
                // 拖过页顶: 上一页从上方滑入 (旧 moveToPrev 分支, 内容连续性由 offset 折算保证)
                offset > 0f -> {
                    val oldOffset = offset
                    if (!viewModel.prevPage() && !viewModel.moveToPrevChapter()) {
                        // 书首: 归零并中止 (旧 !hasPrev → pageOffset = 0 + abortAnim)
                        offset = 0f
                        _currentOffset = offset
                        viewModel.updateScrollOffset(offset.toInt())
                        return false
                    }
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
                    val oldHeight = h
                    if (!viewModel.nextPage() && !viewModel.moveToNextChapter()) {
                        // 全书末: 钳制到页底 (旧 moveToNext 失败 → pageOffset = -height)
                        offset = -oldHeight
                        _currentOffset = offset
                        viewModel.updateScrollOffset(offset.toInt())
                        return false
                    }
                    // 旧页高折算 (旧 pageOffset += textPage.height, height 在 moveToNext 前取值)
                    offset += oldHeight
                }

                else -> break
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
     * 页内部分动画 (与整页翻页同一 [animateOffsetTo] 机制), 越界部分同步折算跨页
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
                animateOffsetTo(_currentOffset + inPage, animDurationMs)
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

    /**
     * 惯性停止后对齐到最近行边界 (对照旧 scrollToLine 的行对齐语义)。
     * 候选 = 页顶 (offset 0) + 各行行顶对齐视口顶, 取距当前视口顶最近者。
     */
    private fun snapToLine(): Boolean {
        val cur = viewModel.curTextPage.value ?: return false
        val lines = cur.lines
        if (lines.isEmpty()) return false
        val viewTop = -_currentOffset // 视口顶在页内容中的位置 (durY 坐标)
        var target = 0f // 候选: 页顶对齐
        var bestDist = abs(viewTop)
        for (i in lines.indices) {
            val lineTop = lines[i].lineTop - cur.paddingTop
            val dist = abs(lineTop - viewTop)
            if (dist < bestDist) {
                bestDist = dist
                target = -lineTop
            }
        }
        if (target == _currentOffset) return false
        // 末页底部钳制同样约束行对齐 (对照旧 scroll 末页分支: 页底不能高于视口底)
        if (!hasNext()) {
            val minOffset = min(0f, cur.visibleHeight.toFloat() - cur.height)
            if (target < minOffset) target = minOffset
            if (target == _currentOffset) return false
        }
        isStarted = true
        isRunning = true
        animJob?.cancel()
        animJob = scope.launch {
            animateOffsetTo(target, LINE_SNAP_MS)
        }
        return true
    }

    // endregion

    // region 保留一行翻页 (对照旧 ScrollPageDelegate.calcNextPageOffset/calcPrevPageOffset :139-157)

    /** 点击下一页目标偏移: 当前页末行对齐视口顶 (图片页滚动整页可视高) */
    private fun calcNextPageOffset(): Float {
        val cur = viewModel.curTextPage.value ?: return 0f
        val book = viewModel.book.value
        val isTextStyle = book?.config?.imageStyle?.equals(
            Book.imgStyleText, true
        ) == true
        if ((book == null || book.isImage) || (!isTextStyle && cur.hasImageOrEmpty())) {
            return -cur.visibleHeight.toFloat()
        }
        if (cur.lines.isEmpty()) return -cur.visibleHeight.toFloat()
        val lastLineTop = cur.lines.last().lineTop
        return -(lastLineTop - cur.paddingTop)
    }

    /** 点击上一页目标偏移: 当前页首行对齐视口底 (配合切页, 保留上一页末行) */
    private fun calcPrevPageOffset(): Float {
        val cur = viewModel.curTextPage.value ?: return 0f
        val book = viewModel.book.value
        val isTextStyle = book?.config?.imageStyle?.equals(
            Book.imgStyleText, true
        ) == true
        if ((book == null || book.isImage) || (!isTextStyle && cur.hasImageOrEmpty())) {
            return cur.visibleHeight.toFloat()
        }
        if (cur.lines.isEmpty()) return cur.visibleHeight.toFloat()
        val firstLineBottom = cur.lines.first().lineBottom
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
     * 松手惯性滚动 (对照旧 ScrollPageDelegate.onAnimStart 的
     * `fling(0, touchY, 0, mVelocity.yVelocity, ...)`): 以手势末速度衰减,
     * 逐帧走 [applyScrollDelta] (翻页折算/边界钳制), 停止后对齐行边界。
     */
    private fun onFling(velocityY: Float) {
        if (!isMoved || abs(velocityY) <= 0f) {
            // 无有效速度: 直接行对齐 (慢速拖放/边界复位后)
            snapToLine()
            // 手势结束恢复自动翻页推进 (无动画路径)
            autoPager?.resume()
            return
        }
        isStarted = true
        isRunning = true
        animJob?.cancel()
        animJob = scope.launch {
            val decay = decaySpec
            if (decay != null) {
                AnimationState(_currentOffset, velocityY).animateDecay(decay) {
                    if (!applyScrollDelta(value - _currentOffset)) {
                        cancelAnimation()
                    }
                }
            }
            // 惯性停止 → 对齐行边界
            snapToLine()
            // 惯性结束恢复自动翻页推进 (对照原版 computeScroll → onAnimStop → resume)
            autoPager?.resume()
        }
    }

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
        animJob?.cancel()
        animJob = null
        isStarted = false
        isMoved = false
        isRunning = false
    }

    override fun onAnimStart(animationSpeed: Int) {
        // 滚动模式动画由手势速度驱动 (onFling), 此入口无额外动作
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
        // 对照旧 ScrollPageDelegate.nextPageByAnim: 保留一行滚动 (页索引不变)。
        // 不做 isRunning 拦截: 动画中按键由 abortAnim 打断后从当前偏移重算目标 (对齐原版
        // nextPageByAnim → startScroll 语义), 快速连按的合法按键不静默丢弃。
        if (!hasNext()) return
        abortAnim()
        var target = calcNextPageOffset()
        if (target == _currentOffset) {
            // 偏移已对齐当前页末行 (上次保留一行翻页完成): 继续滚过页底切入下一页、
            // 再对齐新页末行 (对照 applyScrollDelta 的跨页折算分支), 保证连续按键每次都翻页
            val pageHeight = viewModel.curTextPage.value?.height?.toFloat() ?: 0f
            if (pageHeight <= 0f || !applyScrollDelta(-pageHeight)) {
                // 无动画路径：恢复自动翻页，避免 abortAnim 的 pause 悬挂
                autoPager?.resume()
                return
            }
            target = calcNextPageOffset()
            if (target == _currentOffset) {
                autoPager?.resume()
                return
            }
        }
        isStarted = true
        isRunning = true
        animJob = scope.launch {
            animateOffsetTo(target, scrollDuration(target, animDurationMs))
        }
    }

    override fun prevPageByAnim(animDurationMs: Int) {
        // 对照旧 ScrollPageDelegate.prevPageByAnim: 先切上一页, 再滚动保留上一页末行;
        // isRunning 拦截理由同 [nextPageByAnim]
        if (!hasPrev()) return
        abortAnim()
        val target = calcPrevPageOffset()
        if (!viewModel.prevPage() && !viewModel.moveToPrevChapter()) {
            // 无动画路径：恢复自动翻页，避免 abortAnim 的 pause 悬挂
            autoPager?.resume()
            return
        }
        // 新当前页 (旧 prev) 从视口顶上方滑入: 起点 = -新页高 (旧 scroller 首帧折算结果)
        val hNew = viewModel.curTextPage.value?.height ?: 0f
        _currentOffset = -hNew
        viewModel.updateScrollOffset(_currentOffset.toInt())
        isStarted = true
        isRunning = true
        animJob = scope.launch {
            animateOffsetTo(target - hNew, scrollDuration(target, animDurationMs))
        }
    }

    /** 动画时长折算 (对照旧 startScroll: duration = animationSpeed * abs(dy) / viewHeight) */
    private fun scrollDuration(target: Float, animDurationMs: Int): Int {
        val distance = abs(target - _currentOffset)
        if (viewHeight <= 0) return animDurationMs
        return (animDurationMs * distance / viewHeight).toInt().coerceAtLeast(1)
    }

    // endregion

    // region Compose 渲染骨架 (行级滚动)

    @Composable
    override fun renderPageAnimation(
        pageWidthPx: Int,
        pageHeightPx: Int,
        prevContent: @Composable () -> Unit,
        curContent: @Composable () -> Unit,
        nextContent: @Composable () -> Unit,
        onClick: (TextColumn?) -> Unit,
        onLongClick: (Float, Float) -> Unit,
    ) {
        // 尺寸变化时同步（与 app 端 setViewSize 调用时机对应）
        if (viewWidth != pageWidthPx || viewHeight != pageHeightPx) {
            setViewSize(pageWidthPx, pageHeightPx)
        }
        // 惯性衰减规格组合期注入 (splineBasedDecay 依赖 density)
        if (decaySpec == null) {
            decaySpec = splineBasedDecay(LocalDensity.current)
        }

        // 与 viewModel.scrollOffset 同步: 切章/重排重置归零时本 delegate 跟随
        // (applyScrollDelta 已双写, 此处只消费外部重置, 同值写入不触发重绘)
        LaunchedEffect(viewModel) {
            viewModel.scrollOffset.collect { offset ->
                if (_currentOffset != offset.toFloat()) {
                    _currentOffset = offset.toFloat()
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 滚动手势: 越过 touchSlop 才消费 (与 detectTapGestures 共存, 对照旧 isMove 判定)
                .pointerInput(Unit) {
                    scrollDragGesture()
                }
                // 单击手势: 转发到 onTap (携带落点坐标, 供九宫格动作分发)。
                // 长按已由顶层选择层统一接管 (2026-08-08 方案 A: 触摸长按在 ReadViewComposable
                // 顶层选择层检测并消费, 鼠标长按在 readerMouseGestures), 此处不再注册
                // onLongPress, 避免与顶层长按检测双触发 (onLongClick 参数保留, 见基类签名)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            // onTap 返回 false 表示中心区域未消费, 转发给上层 onClick
                            if (!onTap(offset.x, offset.y)) {
                                onClick(null)
                            }
                        },
                    )
                },
        ) {
            // 当前页: 行级平移在 PageViewComposable 内部 (contentTranslationY 提供者 +
            // 固定视口裁剪, 对照旧 drawPage 的 translate + clipRect(visibleRect))
            curContent()

            // 下一页: 连排在当前页内容之后, 绘制位置 = offset + 当前页高
            // (对照旧 drawPage 的 relativeOffset(1) = pageOffset + textPage.height;
            // 整页滑出视口时由内部裁剪自然不可见, 无每帧可见性判定)
            nextContent()
        }
    }

    /**
     * 滚动手势循环: 追踪速度, 越过 touchSlop 后开始滚动并消费事件。
     *
     * 对照旧 ReadView.onTouchEvent + ScrollPageDelegate.onTouch:
     * - ACTION_DOWN → [onDown] (中止动画 + 记录起点)
     * - ACTION_MOVE → 越过 slop 判定 isMove 后 [onScroll]
     * - ACTION_UP/CANCEL → [onFling] (带末速度)
     */
    private suspend fun PointerInputScope.scrollDragGesture() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onDown(down.position.x, down.position.y)
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            val slop = viewConfiguration.touchSlop
            var lastPos = down.position
            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // 事件已被其他手势消费 (文字选择扩选层) → 终止本次滚动, 避免与选择争抢
                // (对照 detectDragGestures 的 awaitTouchSlopOrCancellation 对消费事件的处理)
                if (change.isConsumed) break
                if (!dragging) {
                    val delta = change.position - down.position
                    if (abs(delta.x) > slop || abs(delta.y) > slop) {
                        dragging = true
                        // 越过 slop 后重置基准, 避免 slop 位移带入滚动 (对照旧 setStartPoint)
                        lastPos = change.position
                    }
                }
                if (dragging) {
                    tracker.addPointerInputChange(change)
                    if (change.position != lastPos) {
                        onScroll(change.position.x, change.position.y)
                    }
                    change.consume()
                }
                lastPos = change.position
            }
            if (dragging) {
                onFling(tracker.calculateVelocity().y)
            }
        }
    }

    /**
     * 滚动模式无阴影叠加 (对照旧 ScrollPageDelegate.onDraw: nothing)。
     */
    override fun DrawScope.drawShadow(currentOffset: Float, viewWidth: Int) = Unit

    // endregion
}
