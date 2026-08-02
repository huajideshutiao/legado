package io.legado.app.ui.book.read.page.delegate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 滚动翻页 delegate（sharedUiMain，Compose Multiplatform 版）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate` 对应，
 * 用 Compose 跨平台 API 替代 Android `Scroller` + `VelocityTracker` + `Canvas`。
 *
 * # 与 app 端原版的语义差异（架构适配）
 *
 * app 端 ScrollPageDelegate 是 **单页内文字滚动**（保留一行）：
 * - 用 `VelocityTracker` 追踪速度 + `Scroller.fling` 惯性滚动
 * - `calcNextPageOffset` 保留最后一行行首在视口顶部
 *   （依赖 `ChapterProvider.visibleHeight` + `Book.config.imageStyle` + `visiblePage.lines`）
 * - `curPage.scroll(deltaY)` 内部调用 `ChapterProvider` 的行级排版 API
 *
 * KMP 版 [SimpleChapterLayout][io.legado.app.ui.book.read.page.provider.SimpleChapterLayout]
 * 尚未暴露行级 API（lines/lineTop/lineBottom），无法完整移植"保留一行"语义。
 * 改为 **整页垂直滑动切换**（与 [HorizontalPageDelegate] 对称，方向改垂直）：
 * - 手指上滑 → 下一页（nextContent 从下方滑入）
 * - 手指下滑 → 上一页（prevContent 从上方滑入）
 * - `Animatable` 驱动 _currentOffset 从 0 → ±viewHeight
 * - fling 惯性用 `Animatable.animateTo` + `tween` 近似（无需 VelocityTracker）
 *
 * 这是必要的架构适配，不是降级——app 端行级滚动依赖 ChapterProvider 的 TextPage.lines 行级排版，
 * KMP 版 SimpleChapterLayout 目前只产出整页 TextPage，不暴露行级 API。
 * 后续若 SimpleChapterLayout 补全行级 API，可在本 delegate 内恢复"保留一行"语义。
 *
 * # 翻页方向语义
 *
 * - **NEXT**（手指上滑，currentOffset < 0）：nextPage 从下方滑入，curPage 静止
 *   - currentOffset 从 0 → -viewHeight（nextPage 偏移 = viewHeight + currentOffset，从 viewHeight → 0）
 * - **PREV**（手指下滑，currentOffset > 0）：prevPage 从上方滑入，curPage 静止
 *   - currentOffset 从 0 → +viewHeight（prevPage 偏移 = -viewHeight + currentOffset，从 -viewHeight → 0）
 *
 * # 章节边界联动
 *
 * [onAnimStop] 内按 mDirection 调 viewModel.nextPage/prevPage；
 * 返回 false（章节末页/首页）时调 moveToNextChapter/moveToPrevChapter 切章。
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

    // region 触摸点状态（垂直方向，与 app 端 ScrollPageDelegate touchY/lastY 对应；startY 在基类）
    private var lastY: Float = 0f
    // endregion

    // region PageDelegateShared 接口实现（垂直翻页逻辑）

    override fun onDown(x: Float, y: Float) {
        // 与 app 端 PageDelegate.onTouch ACTION_DOWN 分支先 abortAnim() 对应:
        // 动画进行中按下立即中断, 否则旧动画协程会继续写 _currentOffset 与新手势冲突
        // (滚动委托原版 abortAnim 只取消不补页, 见 ScrollPageDelegate.abortAnim)
        abortAnim()
        // 与 app 端 PageDelegate.onDown 对应：重置状态字段
        isMoved = false
        noNext = false
        isRunning = false
        isCancel = false
        setDirection(PageDirectionShared.NONE)
        // 记录起始触碰点（垂直翻页关注 y 坐标）
        startX = x
        startY = y
        lastY = y
        touchX = x
        touchY = y
        // 重置偏移
        _currentOffset = 0f
    }

    override fun onScroll(x: Float, y: Float) {
        if (!isMoved) {
            val deltaY = y - startY
            // 与横向委托同一问题：onDragStart 与首个 onDrag 收到同一位置，首次 deltaY 恒为 0，
            // 据此定方向会恒判 PREV。跳过本次，等真实位移事件再定方向。
            if (deltaY == 0f) return
            // 原版两层判定共享按下点基准；Compose 已在内部消耗 touchSlop，不再叠加二次判定
            isMoved = true
            if (deltaY < 0) {
                // 向上滑 → NEXT，校验是否有下一页 / 下一章
                if (!hasNext()) {
                    noNext = true
                    return
                }
                setDirection(PageDirectionShared.NEXT)
            } else {
                // 向下滑 → PREV，校验是否有上一页 / 上一章
                if (!hasPrev()) {
                    noNext = true
                    return
                }
                setDirection(PageDirectionShared.PREV)
            }
            // 重设 startY 为当前点，避免初始 slop 偏移带入 currentOffset
            startY = y
            lastY = y
        }
        if (isMoved) {
            // 反向移动判定取消（与 app 端 ScrollPageDelegate 判定一致）
            isCancel = if (mDirection == PageDirectionShared.NEXT) y > lastY else y < lastY
            isRunning = true
            touchY = y
            // 计算 currentOffset 并 clamp 到合法范围（垂直方向）
            // 与 app 端 curPage.scroll((touchY - lastY).toInt()) 对应，但 KMP 版用绝对偏移
            val rawOffset = y - startY
            _currentOffset = when (mDirection) {
                PageDirectionShared.PREV -> rawOffset.coerceIn(0f, viewHeight.toFloat())
                PageDirectionShared.NEXT -> rawOffset.coerceIn(-viewHeight.toFloat(), 0f)
                else -> 0f
            }
            lastY = y
        }
    }

    override fun onTap(x: Float, y: Float): Boolean {
        // 优先走宿主注入的九宫格分发（对照 app 端 ReadView.clickArea + click）
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
        // 取消正在执行的动画协程
        animJob?.cancel()
        animJob = null
        isStarted = false
        isMoved = false
        isRunning = false
        _currentOffset = 0f
    }

    override fun onAnimStart(animationSpeed: Int) {
        if (!isMoved || mDirection == PageDirectionShared.NONE) {
            // 未移动或方向未定，不启动动画（与 app 端 onTouch ACTION_UP 后判定一致）
            return
        }
        isStarted = true
        isRunning = true
        // 计算目标偏移：取消 → 回弹 0；否则滑到 ±viewHeight（完全滑入/露出）
        // 与 app 端 ScrollPageDelegate.onAnimStart 的 fling 目标计算等价
        val target = when {
            isCancel -> 0f
            mDirection == PageDirectionShared.NEXT -> -viewHeight.toFloat()
            mDirection == PageDirectionShared.PREV -> viewHeight.toFloat()
            else -> 0f
        }
        // 启动动画协程（替代 Android Scroller.fling + invalidate 驱动）
        animJob?.cancel()
        animJob = scope.launch {
            animateOffsetTo(target, animationSpeed)
        }
    }

    override fun onAnimStop() {
        try {
            if (!isCancel) {
                // 非取消：实际翻页（与 app 端 readView.fillPage(mDirection) 对应）
                // 章节边界联动 - nextPage/prevPage 返回 false 时切章
                when (mDirection) {
                    PageDirectionShared.NEXT -> {
                        if (!viewModel.nextPage()) {
                            viewModel.moveToNextChapter()
                        }
                    }
                    PageDirectionShared.PREV -> {
                        if (!viewModel.prevPage()) {
                            viewModel.moveToPrevChapter()
                        }
                    }
                    else -> Unit
                }
            }
        } finally {
            // 重置状态（与 app 端 stopScroll 行为对应）
            resetState()
        }
    }

    override fun nextPageByAnim(animDurationMs: Int) {
        // 与 app 端 ScrollPageDelegate.nextPageByAnim 对应：末尾统一走 onAnimStart
        if (isRunning) return
        if (!hasNext()) return
        abortAnim()
        setDirection(PageDirectionShared.NEXT)
        // 模拟从底部按下，向上滑（与 app 端 setStartPoint(0, viewHeight, false) 对应）
        startY = viewHeight.toFloat()
        lastY = startY
        touchY = startY
        isMoved = true
        isCancel = false
        onAnimStart(animDurationMs)
    }

    override fun prevPageByAnim(animDurationMs: Int) {
        // 与 app 端 ScrollPageDelegate.prevPageByAnim 对应
        if (isRunning) return
        if (!hasPrev()) return
        abortAnim()
        setDirection(PageDirectionShared.PREV)
        // 模拟从顶部按下，向下滑（与 app 端 setStartPoint(0, 0, false) 对应）
        startY = 0f
        lastY = 0f
        touchY = 0f
        isMoved = true
        isCancel = false
        onAnimStart(animDurationMs)
    }

    // endregion

    // region Compose 渲染骨架（垂直翻页）

    @Composable
    override fun renderPageAnimation(
        pageWidthPx: Int,
        pageHeightPx: Int,
        prevContent: @Composable () -> Unit,
        curContent: @Composable () -> Unit,
        nextContent: @Composable () -> Unit,
        onClick: (TextColumn?) -> Unit,
        onLongClick: (TextColumn?) -> Unit,
    ) {
        // 尺寸变化时同步（与 app 端 setViewSize 调用时机对应）
        if (viewWidth != pageWidthPx || viewHeight != pageHeightPx) {
            setViewSize(pageWidthPx, pageHeightPx)
        }

        val currentOffsetValue = _currentOffset  // 读取状态触发重组
        val direction = mDirection

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 拖拽手势：转发到 onDown / onScroll / onAnimStart
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onDown(offset.x, offset.y)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            onScroll(change.position.x, change.position.y)
                        },
                        onDragEnd = {
                            // 松手启动动画（与 app 端 onTouch ACTION_UP → onAnimStart 对应）
                            onAnimStart(animationSpeed)
                        },
                        onDragCancel = {
                            // 取消手势：回弹到原位
                            isCancel = true
                            onAnimStart(animationSpeed)
                        },
                    )
                }
                // 单击/长按手势：转发到 onTap / onLongClick
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            // onTap 返回 false 表示中心区域未消费，转发给上层 onClick
                            if (!onTap(offset.x, offset.y)) {
                                onClick(null)
                            }
                        },
                        onLongPress = { _ ->
                            onLongClick(null)
                        },
                    )
                },
        ) {
            // 底层：当前页（位置始终为 0，被上层覆盖时不可见）
            // 与 app 端 ScrollPageDelegate.onDraw 中 curRecorder.draw(canvas) 在底层对应
            curContent()

            // 上层：根据方向显示 prev / next 页，用 offset 控制垂直滑入位置
            when (direction) {
                PageDirectionShared.PREV -> {
                    // prevPage 从上方滑入覆盖 curPage
                    // 偏移 = -viewHeight + currentOffset（currentOffset 从 0→viewHeight 时，偏移从 -viewHeight→0）
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (-pageHeightPx + currentOffsetValue).roundToInt(),
                                )
                            }
                            .fillMaxSize(),
                    ) {
                        prevContent()
                    }
                }
                PageDirectionShared.NEXT -> {
                    // nextPage 从下方滑入覆盖 curPage
                    // 偏移 = viewHeight + currentOffset（currentOffset 从 0→-viewHeight 时，偏移从 viewHeight→0）
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (pageHeightPx + currentOffsetValue).roundToInt(),
                                )
                            }
                            .fillMaxSize(),
                    ) {
                        nextContent()
                    }
                }
                else -> Unit
            }

            // 阴影叠加层：在滑入页面与底层页面的水平交界处绘制
            if (direction != PageDirectionShared.NONE && currentOffsetValue != 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawShadow(currentOffsetValue, pageWidthPx)
                }
            }
        }
    }

    /**
     * 绘制垂直翻页阴影：在滑入页面与底层页面的水平交界处。
     *
     * 阴影位置：在滑入页面与视口的交界处
     * - PREV：prevPage 下边缘（位置 = currentOffset）向上展开 shadowWidth
     *   - 渐变方向：上浅下深（让 prevPage 下边缘有阴影投在 curPage 上）
     * - NEXT：nextPage 上边缘（位置 = viewHeight + currentOffset）向下展开 shadowWidth
     *   - 渐变方向：上深下浅（让 nextPage 上边缘有阴影投在 curPage 上）
     */
    override fun DrawScope.drawShadow(currentOffset: Float, viewWidth: Int) {
        val shadowWidth = SHADOW_WIDTH_PX.toFloat()
        val height = size.height  // 从 DrawScope 取实际高度
        val shadowTop = when (mDirection) {
            PageDirectionShared.PREV -> {
                // prevPage 下边缘 = -viewHeight + currentOffset + viewHeight = currentOffset
                // 阴影在 prevPage 下边缘（currentOffset 位置）向上展开 shadowWidth
                currentOffset - shadowWidth
            }
            PageDirectionShared.NEXT -> {
                // nextPage 上边缘 = viewHeight + currentOffset
                // 阴影在 nextPage 上边缘向下展开 shadowWidth
                height + currentOffset
            }
            else -> return
        }
        // 与 app 端 shadowColors = intArrayOf(0x66111111, 0x00000000) 对应（垂直渐变）
        // PREV 方向：阴影在 prevPage 下边缘，上浅下深（让 prevPage 边缘有阴影投在 curPage 上）
        // NEXT 方向：阴影在 nextPage 上边缘，上深下浅（让 nextPage 边缘有阴影投在 curPage 上）
        val (startColor, endColor) = when (mDirection) {
            PageDirectionShared.PREV -> Color(0x00000000) to Color(0x66111111)
            PageDirectionShared.NEXT -> Color(0x66111111) to Color(0x00000000)
            else -> return
        }
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(startColor, endColor),
                startY = shadowTop,
                endY = shadowTop + shadowWidth,
            ),
            topLeft = Offset(0f, shadowTop),
            size = Size(viewWidth.toFloat(), shadowWidth),
        )
    }

    // endregion
}
