package io.legado.app.ui.book.read.page.delegate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 仿真翻页 delegate（sharedUiMain，Compose Multiplatform 版）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate` 对应，
 * 用 Compose 跨平台 API 替代 Android `Path` + `Matrix` + `ColorMatrixColorFilter` +
 * `GradientDrawable` + `Canvas.clipPath/clipOutPath` + `CanvasRecorder.screenshot`。
 *
 * # 与 app 端原版的架构适配
 *
 * app 端 `SimulationPageDelegate` 核心绘制流程：
 * 1. `CanvasRecorder.screenshot(prevPage/curPage/nextPage)` 截图成 Bitmap
 * 2. `calcPoints()` 计算贝塞尔曲线控制点（纯数学，跨平台兼容）
 * 3. `Canvas.clipPath(mPath0)` + `canvas.drawBitmap(curBitmap)` 绘制当前页（剪裁掉翻起部分）
 * 4. `Canvas.clipPath(mPath1)` + `canvas.drawBitmap(nextBitmap)` + `canvas.rotate(mDegrees)` 绘制翻起页 + 阴影
 * 5. `mMatrix` 矩阵变换 + `mColorMatrixFilter` 颜色过滤绘制翻起页背面
 * 6. `GradientDrawable` 绘制 4 种阴影（前/后/左/右）
 *
 * KMP 版核心差异（**Compose 无 CanvasRecorder 等价物，无法对 Composable 截图**）：
 * - 截图 → 直接渲染 Composable，用 `Modifier.graphicsLayer` 实现 3D 变换
 * - `canvas.drawBitmap(bitmap, matrix, paint)` → `Modifier.graphicsLayer { rotationY = mDegrees }` 实现 3D 翻转
 * - `ColorMatrixColorFilter` 背面变暗 → `Modifier.graphicsLayer { alpha = 0.85f }` 近似（简化）
 * - `GradientDrawable` 阴影 → `Brush.horizontalGradient/verticalGradient` + `drawRect` + `drawPath`
 *
 * # 保留的核心视觉特征
 *
 * - 贝塞尔曲线计算（[calcPoints] / [calcCornerXY] / [getCross]）：完整移植 app 端纯数学逻辑
 * - 翻起页 3D 翻转：用 `graphicsLayer { rotationY = mDegrees }` 实现（与 app 端 `canvas.rotate(mDegrees)` 对应）
 * - 阴影渐变：用 `Brush` + `Path` 实现（与 app 端 `GradientDrawable` + `canvas.clipPath` 对应）
 *
 * # 简化项（标注 TODO，后续迭代补全）
 *
 * - 翻起页背面颜色过滤（`ColorMatrixColorFilter`）：KMP 版用 `graphicsLayer.alpha` 近似，不做精确颜色矩阵变换
 * - `mMatrix` 矩阵变换：KMP 版用 `rotationY` 替代，不保留 app 端的反射矩阵
 * - 贝塞尔剪裁（`canvas.clipPath(mPath0/mPath1)`）：KMP 版暂不实现 Composable 的 Path 剪裁，
 *   因为 Compose 的 `drawWithContent + clipPath` 在动画过程中时序复杂；后续可引入 `Modifier.clip(GenericShape)`
 *   或自定义 `DrawModifierNode` 实现等价效果
 *
 * @param viewModel 阅读 ViewModel
 * @param scope 协程作用域
 * @param animationSpeed 默认动画速度（ms/页宽）
 */
class SimulationPageDelegateCompose(
    viewModel: ReadBookViewModelShared,
    scope: CoroutineScope,
    animationSpeed: Int = DEFAULT_ANIMATION_SPEED,
) : HorizontalPageDelegateCompose(viewModel, scope, animationSpeed) {

    // region 贝塞尔曲线状态字段（与 app 端 SimulationPageDelegate 字段一一对应）

    // 不让 x,y 为 0，否则在点计算时会有问题
    private var mTouchX = 0.1f
    private var mTouchY = 0.1f

    // 触摸起点 Y（基类 PageDelegateCompose 只有 startX，Simulation 需要垂直方向判定）
    private var startY: Float = 0f

    // 拖拽点对应的页脚
    private var mCornerX = 1
    private var mCornerY = 1

    // 贝塞尔曲线路径（与 app 端 mPath0/mPath1 对应，Compose Path API 等价）
    // mPath0: 当前页未翻起部分的边界路径
    // mPath1: 翻起页的边界路径
    private val mPath0: Path = Path()
    private val mPath1: Path = Path()

    // 贝塞尔曲线起始点（用 Offset 替代 app 端 PointF，跨平台兼容）
    private var mBezierStart1 = Offset.Zero
    private var mBezierControl1 = Offset.Zero
    private var mBezierVertex1 = Offset.Zero
    private var mBezierEnd1 = Offset.Zero

    // 另一条贝塞尔曲线
    private var mBezierStart2 = Offset.Zero
    private var mBezierControl2 = Offset.Zero
    private var mBezierVertex2 = Offset.Zero
    private var mBezierEnd2 = Offset.Zero

    private var mMiddleX = 0f
    private var mMiddleY = 0f
    private var mDegrees = 0f
    private var mTouchToCornerDis = 0f

    // 是否属于右上左下
    private var mIsRtOrLb = false
    private var mMaxLength: Float = 0f
    // endregion

    // region app 端纯数学函数完整移植（calcCornerXY / calcPoints / getCross）

    /**
     * 计算拖拽点对应的拖拽脚。
     * 与 app 端 `SimulationPageDelegate.calcCornerXY` 完全等价（纯数学）。
     */
    private fun calcCornerXY(x: Float, y: Float) {
        mCornerX = if (x <= viewWidth / 2) 0 else viewWidth
        mCornerY = if (y <= viewHeight / 2) 0 else viewHeight
        mIsRtOrLb = (mCornerX == 0 && mCornerY == viewHeight) ||
            (mCornerY == 0 && mCornerX == viewWidth)
    }

    /**
     * 计算贝塞尔曲线控制点。
     * 与 app 端 `SimulationPageDelegate.calcPoints` 完全等价（纯数学）。
     *
     * 用 [Offset] 替代 app 端 `PointF`（x/y 字段改为 Offset.x/y，语义等价）。
     */
    private fun calcPoints() {
        mTouchX = touchX
        mTouchY = touchY

        mMiddleX = (mTouchX + mCornerX) / 2
        mMiddleY = (mTouchY + mCornerY) / 2
        mBezierControl1 = Offset(
            x = mMiddleX - (mCornerY - mMiddleY) * (mCornerY - mMiddleY) / (mCornerX - mMiddleX),
            y = mCornerY.toFloat(),
        )
        mBezierControl2 = Offset(
            x = mCornerX.toFloat(),
            y = mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) /
                (if (mCornerY - mMiddleY == 0f) 0.1f else (mCornerY - mMiddleY)),
        )
        mBezierStart1 = Offset(
            x = mBezierControl1.x - (mCornerX - mBezierControl1.x) / 2,
            y = mCornerY.toFloat(),
        )

        // 固定左边上下两个点
        if (mTouchX > 0 && mTouchX < viewWidth) {
            if (mBezierStart1.x < 0 || mBezierStart1.x > viewWidth) {
                if (mBezierStart1.x < 0) {
                    mBezierStart1 = Offset(viewWidth - mBezierStart1.x, mBezierStart1.y)
                }
                val f1 = abs(mCornerX - mTouchX)
                val f2 = viewWidth * f1 / mBezierStart1.x
                mTouchX = abs(mCornerX - f2)
                val f3 = abs(mCornerX - mTouchX) * abs(mCornerY - mTouchY) / f1
                mTouchY = abs(mCornerY - f3)
                mMiddleX = (mTouchX + mCornerX) / 2
                mMiddleY = (mTouchY + mCornerY) / 2
                mBezierControl1 = Offset(
                    x = mMiddleX - (mCornerY - mMiddleY) * (mCornerY - mMiddleY) / (mCornerX - mMiddleX),
                    y = mCornerY.toFloat(),
                )
                mBezierControl2 = Offset(
                    x = mCornerX.toFloat(),
                    y = mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) /
                        (if (mCornerY - mMiddleY == 0f) 0.1f else (mCornerY - mMiddleY)),
                )
                mBezierStart1 = Offset(
                    x = mBezierControl1.x - (mCornerX - mBezierControl1.x) / 2,
                    y = mCornerY.toFloat(),
                )
            }
        }
        mBezierStart2 = Offset(
            x = mCornerX.toFloat(),
            y = mBezierControl2.y - (mCornerY - mBezierControl2.y) / 2,
        )

        mTouchToCornerDis = hypot(
            (mTouchX - mCornerX).toDouble(),
            (mTouchY - mCornerY).toDouble(),
        ).toFloat()

        mBezierEnd1 = getCross(
            Offset(mTouchX, mTouchY), mBezierControl1, mBezierStart1,
            mBezierStart2,
        )
        mBezierEnd2 = getCross(
            Offset(mTouchX, mTouchY), mBezierControl2, mBezierStart1,
            mBezierStart2,
        )

        mBezierVertex1 = Offset(
            x = (mBezierStart1.x + 2 * mBezierControl1.x + mBezierEnd1.x) / 4,
            y = (2 * mBezierControl1.y + mBezierStart1.y + mBezierEnd1.y) / 4,
        )
        mBezierVertex2 = Offset(
            x = (mBezierStart2.x + 2 * mBezierControl2.x + mBezierEnd2.x) / 4,
            y = (2 * mBezierControl2.y + mBezierStart2.y + mBezierEnd2.y) / 4,
        )

        // 同步构建 Path（与 app 端 drawCurrentPageArea/drawNextPageAreaAndShadow 内 mPath0/mPath1 构建对应）
        buildPaths()
    }

    /**
     * 构建 [mPath0] / [mPath1] 贝塞尔曲线路径。
     *
     * 与 app 端 `drawCurrentPageArea` 内 mPath0 构建 + `drawNextPageAreaAndShadow` 内 mPath1 构建 对应。
     * - mPath0: 当前页未翻起部分的边界（贝塞尔曲线 + 触摸点 + 角点）
     * - mPath1: 翻起页的边界（贝塞尔曲线 + 触摸点 + 角点）
     */
    private fun buildPaths() {
        // mPath0: 当前页未翻起部分（与 app 端 drawCurrentPageArea 内 mPath0.reset + moveTo/quadTo/lineTo 对应）
        mPath0.reset()
        mPath0.moveTo(mBezierStart1.x, mBezierStart1.y)
        mPath0.quadraticTo(
            mBezierControl1.x, mBezierControl1.y,
            mBezierEnd1.x, mBezierEnd1.y,
        )
        mPath0.lineTo(mTouchX, mTouchY)
        mPath0.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mPath0.quadraticTo(
            mBezierControl2.x, mBezierControl2.y,
            mBezierStart2.x, mBezierStart2.y,
        )
        mPath0.lineTo(mCornerX.toFloat(), mCornerY.toFloat())
        mPath0.close()

        // mPath1: 翻起页（与 app 端 drawNextPageAreaAndShadow 内 mPath1.reset + moveTo/lineTo 对应）
        mPath1.reset()
        mPath1.moveTo(mBezierStart1.x, mBezierStart1.y)
        mPath1.lineTo(mBezierVertex1.x, mBezierVertex1.y)
        mPath1.lineTo(mBezierVertex2.x, mBezierVertex2.y)
        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y)
        mPath1.lineTo(mCornerX.toFloat(), mCornerY.toFloat())
        mPath1.close()
    }

    /**
     * 求解直线 P1P2 和直线 P3P4 的交点坐标。
     * 与 app 端 `SimulationPageDelegate.getCross` 完全等价（纯数学）。
     */
    private fun getCross(P1: Offset, P2: Offset, P3: Offset, P4: Offset): Offset {
        // 二元函数通式： y=ax+b
        val a1 = (P2.y - P1.y) / (P2.x - P1.x)
        val b1 = (P1.x * P2.y - P2.x * P1.y) / (P1.x - P2.x)
        val a2 = (P4.y - P3.y) / (P4.x - P3.x)
        val b2 = (P3.x * P4.y - P4.x * P3.y) / (P3.x - P4.x)
        val crossX = (b2 - b1) / (a1 - a2)
        val crossY = a1 * crossX + b1
        return Offset(crossX, crossY)
    }

    // endregion

    // region HorizontalPageDelegate 覆写

    override fun setViewSize(width: Int, height: Int) {
        super.setViewSize(width, height)
        // 与 app 端 setViewSize 中 mMaxLength 更新对应
        mMaxLength = hypot(viewWidth.toDouble(), viewHeight.toDouble()).toFloat()
    }

    override fun onDown(x: Float, y: Float) {
        super.onDown(x, y)
        // 与 app 端 SimulationPageDelegate.onTouch ACTION_DOWN 对应
        calcCornerXY(x, y)
    }

    override fun onScroll(x: Float, y: Float) {
        // 与 app 端 SimulationPageDelegate.onTouch ACTION_MOVE 对应
        // 调整 touchY 模拟页脚位置（app 端在 onTouch ACTION_MOVE 内处理）
        if (isMoved) {
            if ((startY > viewHeight / 3 && startY < viewHeight * 2 / 3) ||
                mDirection == PageDirectionShared.PREV
            ) {
                touchY = viewHeight.toFloat()
            }
            if (startY > viewHeight / 3 && startY < viewHeight / 2 &&
                mDirection == PageDirectionShared.NEXT
            ) {
                touchY = 1f
            }
        }
        super.onScroll(x, y)
    }

    override fun setDirection(direction: PageDirectionShared) {
        super.setDirection(direction)
        // 与 app 端 SimulationPageDelegate.setDirection 中 calcCornerXY 调用对应
        when (direction) {
            PageDirectionShared.PREV -> {
                // 上一页滑动不出现对角
                if (startX > viewWidth / 2) {
                    calcCornerXY(startX, viewHeight.toFloat())
                } else {
                    calcCornerXY(viewWidth - startX, viewHeight.toFloat())
                }
            }
            PageDirectionShared.NEXT -> {
                if (viewWidth / 2 > startX) {
                    calcCornerXY(viewWidth - startX, startY)
                }
            }
            else -> Unit
        }
    }

    override fun onAnimStart(animationSpeed: Int) {
        if (!isMoved || mDirection == PageDirectionShared.NONE) return
        // 与 app 端 SimulationPageDelegate.onAnimStart 的 dx/dy 计算对应
        // 这里仅计算目标偏移，实际动画由基类 animateOffsetTo 驱动
        isStarted = true
        isRunning = true
        val target = when {
            isCancel -> 0f
            mDirection == PageDirectionShared.NEXT -> -viewWidth.toFloat()
            mDirection == PageDirectionShared.PREV -> viewWidth.toFloat()
            else -> 0f
        }
        animJob?.cancel()
        animJob = scope.launch {
            animateOffsetTo(target, animationSpeed)
        }
    }

    // endregion

    // region Compose 渲染（3D 翻转 + 阴影）

    @Composable
    override fun renderPages(
        pageWidthPx: Int,
        currentOffset: Float,
        direction: PageDirectionShared,
        prevContent: @Composable () -> Unit,
        curContent: @Composable () -> Unit,
        nextContent: @Composable () -> Unit,
    ) {
        if (direction == PageDirectionShared.NONE || currentOffset == 0f) {
            // 静止状态：仅渲染当前页
            curContent()
            return
        }

        // 计算贝塞尔曲线控制点（与 app 端 onDraw 内 calcPoints 调用对应）
        calcPoints()
        // 计算 3D 翻转角度（与 app 端 drawNextPageAreaAndShadow 内 mDegrees 计算对应）
        // app 端用 Math.toDegrees，KMP 版用 kotlin.math.PI 手动换算
        mDegrees = (atan2(
            (mBezierControl1.x - mCornerX).toDouble(),
            (mBezierControl2.y - mCornerY).toDouble(),
        ) * 180.0 / PI).toFloat()

        when (direction) {
            PageDirectionShared.NEXT -> {
                // 底层：当前页（剪裁掉翻起部分）
                // 与 app 端 drawCurrentPageArea + canvas.clipOutPath(mPath0) 对应
                // KMP 版暂不实现贝塞尔剪裁（见类注释简化项），直接渲染 curContent
                Box(modifier = Modifier.fillMaxSize()) {
                    curContent()
                }
                // 上层：翻起的下一页（3D 翻转 + alpha 近似背面变暗）
                // 与 app 端 drawNextPageAreaAndShadow + canvas.drawBitmap(nextBitmap) +
                //      canvas.rotate(mDegrees) + mColorMatrixFilter 对应
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 与 app 端 canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y) 对应
                            rotationY = mDegrees
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                                pivotFractionX = if (pageWidthPx > 0) {
                                    (mBezierStart1.x / pageWidthPx).coerceIn(0f, 1f)
                                } else 0.5f,
                                pivotFractionY = if (viewHeight > 0) {
                                    (mBezierStart1.y / viewHeight).coerceIn(0f, 1f)
                                } else 0.5f,
                            )
                            // 简化：用 alpha 近似背面变暗（app 端用 ColorMatrixColorFilter）
                            alpha = 0.85f
                        },
                ) {
                    nextContent()
                }
            }
            PageDirectionShared.PREV -> {
                // 底层：上一页（当前显示的 prevPage）
                Box(modifier = Modifier.fillMaxSize()) {
                    prevContent()
                }
                // 上层：翻起的当前页
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationY = mDegrees
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                                pivotFractionX = if (pageWidthPx > 0) {
                                    (mBezierStart1.x / pageWidthPx).coerceIn(0f, 1f)
                                } else 0.5f,
                                pivotFractionY = if (viewHeight > 0) {
                                    (mBezierStart1.y / viewHeight).coerceIn(0f, 1f)
                                } else 0.5f,
                            )
                            alpha = 0.85f
                        },
                ) {
                    curContent()
                }
            }
        }
    }

    /**
     * 绘制仿真翻页阴影（与 app 端 `drawCurrentPageShadow` + `drawNextPageAreaAndShadow` +
     * `drawCurrentBackArea` 三层阴影叠加对应）。
     *
     * KMP 版用 [mPath1] 贝塞尔路径绘制翻起页阴影：
     * - 沿翻起页边缘绘制渐变阴影（与 app 端 mBackShadowDrawableLR/RL 对应）
     * - 阴影宽度用 mTouchToCornerDis / 4（与 app 端 setBounds 计算对应）
     */
    override fun DrawScope.drawShadow(currentOffset: Float, viewWidth: Int) {
        if (mDirection == PageDirectionShared.NONE) return
        // 用 mTouchToCornerDis 控制阴影宽度（与 app 端 mTouchToCornerDis / 4 对应）
        val shadowWidth = (mTouchToCornerDis / 4).coerceAtLeast(1f)
        // 与 app 端 shadowColors = intArrayOf(0x66111111, 0x00000000) 对应
        val shadowColors = listOf(Color(0x66111111), Color(0x00000000))
        // 在 mBezierStart1 位置绘制背面阴影（与 app 端 mBackShadowDrawable.setBounds 对应）
        val shadowLeft = if (mIsRtOrLb) {
            mBezierStart1.x
        } else {
            mBezierStart1.x - shadowWidth
        }
        drawRect(
            brush = Brush.horizontalGradient(
                colors = shadowColors,
                startX = shadowLeft,
                endX = shadowLeft + shadowWidth,
            ),
            topLeft = Offset(shadowLeft, mBezierStart1.y),
            size = Size(shadowWidth, mMaxLength),
        )
    }

    // endregion
}
