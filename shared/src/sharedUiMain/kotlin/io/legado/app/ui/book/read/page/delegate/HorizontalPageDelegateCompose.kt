package io.legado.app.ui.book.read.page.delegate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 横向翻页 delegate 基类（sharedUiMain，Compose Multiplatform 版）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.delegate.HorizontalPageDelegate` 对应，
 * 提供 Cover / Slide / NoAnim / Simulation 四种横向翻页 delegate 的共用逻辑：
 *
 * # 共用逻辑
 *
 * - **手势判定**：[onDown] / [onScroll] / [onTap]（与 app 端 `HorizontalPageDelegate.onScroll` 对应）
 *   - 首次超过 slop 阈值时按 deltaX 正负判定 [mDirection]（PREV / NEXT）
 *   - 后续滑动按 mDirection 反向移动判定 [isCancel]
 *   - 单击按 x 位置区分：左 1/3 → 上一页，右 1/3 → 下一页，中间 → 交上层处理菜单
 * - **动画启动 / 停止**：[onAnimStart] / [onAnimStop]（与 app 端 `Scroller.startScroll` + `computeScroll` 对应）
 *   - 目标偏移：取消 → 0；NEXT → -viewWidth；PREV → +viewWidth
 *   - 停止时调 viewModel.nextPage/prevPage，章节边界时调 moveToNextChapter/moveToPrevChapter
 * - **翻页 API**：[nextPageByAnim] / [prevPageByAnim]（与 app 端 `HorizontalPageDelegate.nextPageByAnim` 对应）
 * - **Compose 渲染骨架**：[renderPageAnimation]（与 app 端 `onDraw(canvas)` 对应）
 *   - `Box` + `Modifier.pointerInput` 检测拖拽 / 单击 / 长按
 *   - 子类 override [renderPages] 定义三页布局
 *   - 子类 override [drawShadow] 定义阴影叠加层
 *
 * # 子类差异
 *
 * - [CoverPageDelegate]：覆盖翻页，prev/nextPage 单侧偏移，curPage 静止
 * - [SlidePageDelegate]：滑动翻页，curPage 与 prev/nextPage 同向联动
 * - [NoAnimPageDelegate]：无动画，直接翻页
 * - [SimulationPageDelegate]：仿真翻页（贝塞尔曲线 / 3D 翻转）
 *
 * @param viewModel 阅读 ViewModel
 * @param scope 协程作用域
 * @param animationSpeed 默认动画速度（ms/页宽）
 */
abstract class HorizontalPageDelegateCompose(
    viewModel: ReadBookViewModelShared,
    scope: CoroutineScope,
    animationSpeed: Int = DEFAULT_ANIMATION_SPEED,
) : PageDelegateCompose(viewModel, scope, animationSpeed) {

    // region PageDelegateShared 接口实现（横向翻页共用逻辑）

    override fun onDown(x: Float, y: Float) {
        // 与 app 端 PageDelegate.onDown 对应：重置状态字段
        isMoved = false
        noNext = false
        isRunning = false
        isCancel = false
        setDirection(PageDirectionShared.NONE)
        // 记录起始触碰点
        startX = x
        lastX = x
        touchX = x
        touchY = y
        // 重置偏移
        _currentOffset = 0f
    }

    override fun onScroll(x: Float, y: Float) {
        if (!isMoved) {
            val deltaX = x - startX
            val deltaY = y - touchY
            // 判断是否超过 slop 阈值（与 app 端 pageSlopSquare2 判定对应）
            if (deltaX * deltaX + deltaY * deltaY > TOUCH_SLOP_PX * TOUCH_SLOP_PX) {
                isMoved = true
                if (deltaX > 0) {
                    // 向右滑 → PREV，校验是否有上一页 / 上一章
                    if (!hasPrev()) {
                        noNext = true
                        return
                    }
                    setDirection(PageDirectionShared.PREV)
                } else {
                    // 向左滑 → NEXT，校验是否有下一页 / 下一章
                    if (!hasNext()) {
                        noNext = true
                        return
                    }
                    setDirection(PageDirectionShared.NEXT)
                }
                // 重设 startX 为当前点，避免初始 slop 偏移带入 currentOffset
                // 与 app 端 readView.setStartPoint(event.x, event.y, false) 对应
                startX = x
                lastX = x
            }
        }
        if (isMoved) {
            // 反向移动判定取消（与 app 端 HorizontalPageDelegate.onScroll 判定一致）
            isCancel = if (mDirection == PageDirectionShared.NEXT) x > lastX else x < lastX
            isRunning = true
            touchX = x
            touchY = y
            // 计算 currentOffset 并 clamp 到合法范围
            // 与 app 端 touchX - startX → distanceX → scroller.startScroll 对应
            val rawOffset = x - startX
            _currentOffset = when (mDirection) {
                PageDirectionShared.PREV -> rawOffset.coerceIn(0f, viewWidth.toFloat())
                PageDirectionShared.NEXT -> rawOffset.coerceIn(-viewWidth.toFloat(), 0f)
                else -> 0f
            }
            lastX = x
        }
    }

    override fun onTap(x: Float, y: Float): Boolean {
        // 按 x 位置区分：左 1/3 → 上一页，右 1/3 → 下一页，中间 → 交上层处理菜单
        // 与 app 端 ReadView.onSingleTapUp → leftRightTap / centerTap 逻辑对应
        if (viewWidth <= 0) return false
        val third = viewWidth / 3f
        return when {
            x < third -> {
                prevPageByAnim(animationSpeed)
                true
            }
            x > viewWidth - third -> {
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
        // 计算目标偏移：取消 → 回弹 0；否则滑到 ±viewWidth（完全覆盖/露出）
        // 与 app 端 HorizontalPageDelegate.onAnimStart 的 distanceX 计算等价
        val target = when {
            isCancel -> 0f
            mDirection == PageDirectionShared.NEXT -> -viewWidth.toFloat()
            mDirection == PageDirectionShared.PREV -> viewWidth.toFloat()
            else -> 0f
        }
        // 启动动画协程（替代 Android Scroller.startScroll + invalidate 驱动）
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
                            // 已到本章节末页，切下一章
                            viewModel.moveToNextChapter()
                        }
                    }
                    PageDirectionShared.PREV -> {
                        if (!viewModel.prevPage()) {
                            // 已到本章节首页，切上一章
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
        // 与 app 端 HorizontalPageDelegate.nextPageByAnim 对应
        if (isRunning) return
        if (!hasNext()) return
        abortAnim()
        setDirection(PageDirectionShared.NEXT)
        // 模拟从右侧按下，向左滑（与 app 端 setStartPoint(viewWidth*0.9, ...) 对应）
        startX = viewWidth.toFloat()
        isMoved = true
        isStarted = true
        isRunning = true
        animJob?.cancel()
        animJob = scope.launch {
            animateOffsetTo(-viewWidth.toFloat(), animDurationMs)
        }
    }

    override fun prevPageByAnim(animDurationMs: Int) {
        // 与 app 端 HorizontalPageDelegate.prevPageByAnim 对应
        if (isRunning) return
        if (!hasPrev()) return
        abortAnim()
        setDirection(PageDirectionShared.PREV)
        // 模拟从左侧按下，向右滑（与 app 端 setStartPoint(0, ...) 对应）
        startX = 0f
        isMoved = true
        isStarted = true
        isRunning = true
        animJob?.cancel()
        animJob = scope.launch {
            animateOffsetTo(viewWidth.toFloat(), animDurationMs)
        }
    }

    // endregion

    // region Compose 渲染骨架（横向翻页共用）

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
            // 子类实现三页布局（Cover/Slide/NoAnim/Simulation 各有差异）
            renderPages(
                pageWidthPx = pageWidthPx,
                currentOffset = currentOffsetValue,
                direction = direction,
                prevContent = prevContent,
                curContent = curContent,
                nextContent = nextContent,
            )

            // 阴影叠加层：子类实现具体绘制
            if (direction != PageDirectionShared.NONE && currentOffsetValue != 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawShadow(currentOffsetValue, pageWidthPx)
                }
            }
        }
    }

    /**
     * 子类实现：根据方向 + offset 渲染三页布局。
     *
     * 与 app 端 `onDraw(canvas)` 内 `canvas.withTranslation/withClip { recorder.draw }` 对应，
     * KMP 版用 `Box` + `Modifier.offset { IntOffset(x, 0) }` 控制三页位置。
     */
    @Composable
    protected abstract fun renderPages(
        pageWidthPx: Int,
        currentOffset: Float,
        direction: PageDirectionShared,
        prevContent: @Composable () -> Unit,
        curContent: @Composable () -> Unit,
        nextContent: @Composable () -> Unit,
    )

    // endregion
}
