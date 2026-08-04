package io.legado.app.ui.book.read.page.delegate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.ReaderBackgroundImageCache
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 仿真翻页 delegate（sharedUiMain，Compose Multiplatform 版）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate` 对应，
 * 绘制流程逐项对齐原版 `onDraw`：
 *
 * | app 端（Android Canvas）                        | KMP 版（Compose / Skia）                              |
 * | ---                                            | ---                                                   |
 * | `CanvasRecorder.screenshot` 三页截 Bitmap        | [rememberGraphicsLayer] + `record` 录一次当前页        |
 * | `drawCurrentPageArea`（clipOutPath(mPath0)）    | `clipPath(mPath0, ClipOp.Difference)` + `drawLayer`   |
 * | `drawNextPageAreaAndShadow`（clip 0∩1 + 阴影）   | 嵌套 `clipPath` + `drawContent` + 背面阴影 Brush        |
 * | `drawCurrentBackArea`（镜像矩阵 + 折痕阴影）      | `withTransform { transform(matrix) }` + `drawLayer`   |
 * | `drawCurrentPageShadow`（翻起页前面两道阴影）      | [drawShadow] 内两次 clip + rotate + Brush              |
 * | `GradientDrawable` + `setBounds`               | `Brush.horizontalGradient/verticalGradient` + drawRect |
 *
 * 背面颜色：原版 `mColorMatrixFilter` 在 origin/quickjs 是**单位矩阵**（不变暗），
 * 背面的暗部完全由折痕阴影渐变（0x00333333 → 0xB0333333）提供，此处照搬，不额外加 alpha。
 *
 * 性能：4 条 [Path] 与镜像 [Matrix] 均为字段复用，每帧只 `reset` 不新建。
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

    // 拖拽点对应的页脚
    private var mCornerX = 1
    private var mCornerY = 1

    // 复用的路径对象（对应原版 mPath0/mPath1，原版 mPath1 被三处轮流 reset 复用，
    // Compose 侧三块绘制在不同的 draw lambda 中，故拆成三条独立路径避免时序耦合）
    private val mPath0: Path = Path()      // 当前页翻起区域边界（原版 clipOutPath 用）
    private val mPathNext: Path = Path()   // 翻起后露出的下一页区域
    private val mPathBack: Path = Path()   // 翻起页背面区域
    private val mPathShadow: Path = Path() // 翻起页前面阴影区域（每帧两次 reset 复用）

    // 镜像矩阵（对应原版 mMatrix + mMatrixArray）
    private val mMirrorMatrix: Matrix = Matrix()

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

    // 交点求解可能除零，产出 NaN/Inf 时跳过曲面绘制，退化为整页直出
    private var pointsValid = false

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

        // 与 app 端 drawNextPageAreaAndShadow 内 mDegrees 计算对应（Math.toDegrees 手动换算）
        mDegrees = (
            atan2(
                (mBezierControl1.x - mCornerX).toDouble(),
                (mBezierControl2.y - mCornerY).toDouble(),
            ) * 180.0 / PI
            ).toFloat()

        pointsValid = mBezierEnd1.isFinite && mBezierEnd2.isFinite &&
            mBezierVertex1.isFinite && mBezierVertex2.isFinite &&
            mBezierStart1.isFinite && mBezierStart2.isFinite &&
            mBezierControl1.isFinite && mBezierControl2.isFinite
        if (pointsValid) buildPaths()
    }

    /**
     * 构建三条区域路径，与 app 端各绘制函数内的 mPath0/mPath1 构建逐行对应。
     */
    private fun buildPaths() {
        // 原版 drawCurrentPageArea：翻起区域边界（当前页用 clipOutPath 挖掉这块）
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

        // 原版 drawNextPageAreaAndShadow：露出的下一页区域
        mPathNext.reset()
        mPathNext.moveTo(mBezierStart1.x, mBezierStart1.y)
        mPathNext.lineTo(mBezierVertex1.x, mBezierVertex1.y)
        mPathNext.lineTo(mBezierVertex2.x, mBezierVertex2.y)
        mPathNext.lineTo(mBezierStart2.x, mBezierStart2.y)
        mPathNext.lineTo(mCornerX.toFloat(), mCornerY.toFloat())
        mPathNext.close()

        // 原版 drawCurrentBackArea：翻起页背面区域
        mPathBack.reset()
        mPathBack.moveTo(mBezierVertex2.x, mBezierVertex2.y)
        mPathBack.lineTo(mBezierVertex1.x, mBezierVertex1.y)
        mPathBack.lineTo(mBezierEnd1.x, mBezierEnd1.y)
        mPathBack.lineTo(mTouchX, mTouchY)
        mPathBack.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mPathBack.close()
    }

    /**
     * 背面镜像矩阵：与 app 端 `drawCurrentBackArea` 内 mMatrixArray + preTranslate/postTranslate 等价。
     *
     * 原版是绕 mBezierControl1 的反射变换 `T(c1)·R·T(-c1)`，此处直接把平移量算进
     * Compose 4x4 矩阵的 TranslateX/Y，省掉两次矩阵连乘。
     */
    private fun updateMirrorMatrix() {
        val dis = hypot(
            (mCornerX - mBezierControl1.x).toDouble(),
            (mBezierControl2.y - mCornerY).toDouble(),
        ).toFloat()
        if (dis == 0f) {
            mMirrorMatrix.reset()
            return
        }
        val f8 = (mCornerX - mBezierControl1.x) / dis
        val f9 = (mBezierControl2.y - mCornerY) / dis
        val scaleX = 1 - 2 * f9 * f9
        val skew = 2 * f8 * f9
        val scaleY = 1 - 2 * f8 * f8
        val cx = mBezierControl1.x
        val cy = mBezierControl1.y
        mMirrorMatrix.reset()
        mMirrorMatrix.values[Matrix.ScaleX] = scaleX
        mMirrorMatrix.values[Matrix.SkewX] = skew
        mMirrorMatrix.values[Matrix.SkewY] = skew
        mMirrorMatrix.values[Matrix.ScaleY] = scaleY
        mMirrorMatrix.values[Matrix.TranslateX] = cx - (scaleX * cx + skew * cy)
        mMirrorMatrix.values[Matrix.TranslateY] = cy - (skew * cx + scaleY * cy)
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

    /**
     * 与 app 端 `SimulationPageDelegate.onAnimStart` 对应。
     *
     * 仿真翻页的曲面由触摸点 [touchX]/[touchY] 决定（[calcPoints]），所以动画驱动的是
     * **触摸点本身**（原版 `startScroll(touchX, touchY, dx, dy)` + `computeScroll` 里
     * `setTouchPoint(scroller.currX, currY)`），而不是基类的单轴页面偏移。
     * `_currentOffset` 仍同步推进：它是唯一的 Compose state，负责触发每帧重组。
     */
    override fun onAnimStart(animationSpeed: Int) {
        if (!isMoved || mDirection == PageDirectionShared.NONE) {
            // 未移动或方向未定，不启动动画（与基类 onAnimStart 守卫一致）。
            // 手势未成形同样恢复自动翻页，避免 abortAnim 的 pause 悬挂
            autoPager?.resume()
            return
        }
        isStarted = true
        isRunning = true
        // dx/dy 逐行照搬原版
        var dx: Float
        val dy: Float
        if (isCancel) {
            dx = if (mCornerX > 0 && mDirection == PageDirectionShared.NEXT) {
                viewWidth - touchX
            } else {
                -touchX
            }
            if (mDirection != PageDirectionShared.NEXT) {
                dx = -(viewWidth + touchX)
            }
            dy = if (mCornerY > 0) viewHeight - touchY else -touchY
        } else {
            dx = if (mCornerX > 0 && mDirection == PageDirectionShared.NEXT) {
                -(viewWidth + touchX)
            } else {
                viewWidth - touchX
            }
            // 防止 touchY 最终变为 0
            dy = if (mCornerY > 0) viewHeight - touchY else 1 - touchY
        }
        val fromX = touchX
        val fromY = touchY
        val fromOffset = _currentOffset
        val toOffset = when {
            isCancel -> 0f
            mDirection == PageDirectionShared.NEXT -> -viewWidth.toFloat()
            else -> viewWidth.toFloat()
        }
        val duration = if (animationSpeed <= 0) this.animationSpeed else animationSpeed
        animJob?.cancel()
        animJob = scope.launch {
            Animatable(0f).animateTo(1f, tween(duration, easing = FastOutSlowInEasing)) {
                touchX = fromX + dx * value
                touchY = fromY + dy * value
                _currentOffset = fromOffset + (toOffset - fromOffset) * value
            }
            onAnimStop()
        }
    }

    // endregion

    // region Compose 渲染（曲面剪裁 + 背面镜像 + 阴影）

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

        // 与 app 端 onDraw 内 calcPoints 调用对应
        calcPoints()
        updateMirrorMatrix()
        if (!pointsValid) {
            curContent()
            return
        }

        // 与 app 端 onDraw 的两个分支一致：翻动的是「底页」，露出的是「另一页」
        // NEXT: 底页=当前页、露出=下一页；PREV: 底页=上一页、露出=当前页
        val baseContent = if (direction == PageDirectionShared.NEXT) curContent else prevContent
        val revealContent = if (direction == PageDirectionShared.NEXT) nextContent else curContent
        // 翻起页背面必须是不透明的阅读底色，否则向后翻页时背面会透出底层页面。
        // 正面 PageView 也会把背景 alpha 归一为 1，保持两种渲染路径一致。
        val bgColor = Color(
            LocalReadConfigProviders.current.readBookConfig.config.curBgColor()
        ).copy(alpha = 1f)
        // 背景图由 PageView 的 Canvas 异步加载；这里也读取版本，确保已录制的
        // baseLayer 在图片就绪后重新记录，仿真翻页背面不会继续保留加载前的空底色。
        val backgroundVersion = ReaderBackgroundImageCache.version

        // 替代原版 CanvasRecorder.screenshot：底页只渲染一次，正面 / 背面镜像共用这份 layer
        val baseLayer = rememberGraphicsLayer()
        // draw lambda 捕获 currentOffset：每帧值变化才会重建 lambda 触发重绘，
        // 不能只依赖 delegate 字段（字段不是 Compose state，改了不失效）
        val frame = currentOffset

        // 1. 当前页未翻起部分（原版 drawCurrentPageArea：clipOutPath(mPath0) + drawBitmap）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (backgroundVersion < 0) return@drawWithContent
                    baseLayer.record { this@drawWithContent.drawContent() }
                    if (frame == 0f) {
                        drawLayer(baseLayer)
                        return@drawWithContent
                    }
                    clipPath(mPath0, ClipOp.Difference) { drawLayer(baseLayer) }
                },
        ) {
            baseContent()
        }

        // 2. 翻起后露出的下一页 + 背面阴影（原版 drawNextPageAreaAndShadow）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (frame == 0f) return@drawWithContent
                    clipPath(mPath0) {
                        clipPath(mPathNext) {
                            this@drawWithContent.drawContent()
                            drawBackShadow()
                        }
                    }
                },
        ) {
            revealContent()
        }

        // 3. 翻起页背面：底色 + 镜像页面 + 折痕阴影（原版 drawCurrentBackArea）
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (frame == 0f) return@Canvas
            clipPath(mPath0) {
                clipPath(mPathBack) {
                    drawRect(color = bgColor)
                    withTransform({ transform(mMirrorMatrix) }) { drawLayer(baseLayer) }
                    drawFolderShadow()
                }
            }
        }
    }

    /**
     * 背面阴影：原版 `drawNextPageAreaAndShadow` 内 mBackShadowDrawableLR/RL。
     * 在露出页的剪裁区内、绕 mBezierStart1 旋转 mDegrees 后画一条渐变带。
     */
    private fun DrawScope.drawBackShadow() {
        val left: Float
        val right: Float
        val colors: List<Color>
        if (mIsRtOrLb) {
            left = mBezierStart1.x
            right = mBezierStart1.x + mTouchToCornerDis / 4
            colors = backShadowLR
        } else {
            left = mBezierStart1.x - mTouchToCornerDis / 4
            right = mBezierStart1.x
            colors = backShadowRL
        }
        if (right <= left) return
        withTransform({ rotate(mDegrees, Offset(mBezierStart1.x, mBezierStart1.y)) }) {
            drawRect(
                brush = Brush.horizontalGradient(colors, startX = left, endX = right),
                topLeft = Offset(left, mBezierStart1.y),
                size = Size(right - left, mMaxLength),
            )
        }
    }

    /**
     * 折痕阴影：原版 `drawCurrentBackArea` 内 mFolderShadowDrawableLR/RL，
     * 宽度取两条贝塞尔弦长的较小值（原版 f3 = min(f1, f2)）。
     */
    private fun DrawScope.drawFolderShadow() {
        val f1 = abs((mBezierStart1.x + mBezierControl1.x) / 2 - mBezierControl1.x)
        val f2 = abs((mBezierStart2.y + mBezierControl2.y) / 2 - mBezierControl2.y)
        val f3 = min(f1, f2)
        val left: Float
        val right: Float
        val colors: List<Color>
        if (mIsRtOrLb) {
            left = mBezierStart1.x - 1
            right = mBezierStart1.x + f3 + 1
            colors = folderShadowLR
        } else {
            left = mBezierStart1.x - f3 - 1
            right = mBezierStart1.x + 1
            colors = folderShadowRL
        }
        withTransform({ rotate(mDegrees, Offset(mBezierStart1.x, mBezierStart1.y)) }) {
            drawRect(
                brush = Brush.horizontalGradient(colors, startX = left, endX = right),
                topLeft = Offset(left, mBezierStart1.y),
                size = Size(right - left, mMaxLength),
            )
        }
    }

    /**
     * 翻起页正面的两道阴影，与 app 端 `drawCurrentPageShadow` 逐行对应：
     * 都在「当前页未翻起区域」内（clipOut(mPath0)）再与阴影三角形取交集，
     * 分别绕 mBezierControl1 / mBezierControl2 旋转后画渐变带。
     */
    override fun DrawScope.drawShadow(currentOffset: Float, viewWidth: Int) {
        if (mDirection == PageDirectionShared.NONE || !pointsValid) return

        // 阴影顶点与 touch 点的距离（原版 25 * 1.414 * cos/sin）
        val degree = if (mIsRtOrLb) {
            PI / 4 - atan2(
                (mBezierControl1.y - mTouchY).toDouble(),
                (mTouchX - mBezierControl1.x).toDouble()
            )
        } else {
            PI / 4 - atan2(
                (mTouchY - mBezierControl1.y).toDouble(),
                (mTouchX - mBezierControl1.x).toDouble()
            )
        }
        val d1 = SHADOW_SIZE * 1.414 * cos(degree)
        val d2 = SHADOW_SIZE * 1.414 * sin(degree)
        val x = (mTouchX + d1).toFloat()
        val y = if (mIsRtOrLb) (mTouchY + d2).toFloat() else (mTouchY - d2).toFloat()

        // 第一道：绕 mBezierControl1 旋转的竖向渐变带
        mPathShadow.reset()
        mPathShadow.moveTo(x, y)
        mPathShadow.lineTo(mTouchX, mTouchY)
        mPathShadow.lineTo(mBezierControl1.x, mBezierControl1.y)
        mPathShadow.lineTo(mBezierStart1.x, mBezierStart1.y)
        mPathShadow.close()
        clipPath(mPath0, ClipOp.Difference) {
            clipPath(mPathShadow) {
                val left: Float
                val right: Float
                val colors: List<Color>
                if (mIsRtOrLb) {
                    left = mBezierControl1.x
                    right = mBezierControl1.x + SHADOW_SIZE
                    colors = frontShadowLR
                } else {
                    left = mBezierControl1.x - SHADOW_SIZE
                    right = mBezierControl1.x + 1
                    colors = frontShadowRL
                }
                val rotateDegrees = (
                    atan2(
                        (mTouchX - mBezierControl1.x).toDouble(),
                        (mBezierControl1.y - mTouchY).toDouble(),
                    ) * 180.0 / PI
                    ).toFloat()
                withTransform({
                    rotate(rotateDegrees, Offset(mBezierControl1.x, mBezierControl1.y))
                }) {
                    drawRect(
                        brush = Brush.horizontalGradient(colors, startX = left, endX = right),
                        topLeft = Offset(left, mBezierControl1.y - mMaxLength),
                        size = Size(right - left, mMaxLength),
                    )
                }
            }
        }

        // 第二道：绕 mBezierControl2 旋转的横向渐变带
        mPathShadow.reset()
        mPathShadow.moveTo(x, y)
        mPathShadow.lineTo(mTouchX, mTouchY)
        mPathShadow.lineTo(mBezierControl2.x, mBezierControl2.y)
        mPathShadow.lineTo(mBezierStart2.x, mBezierStart2.y)
        mPathShadow.close()
        clipPath(mPath0, ClipOp.Difference) {
            clipPath(mPathShadow) {
                val top: Float
                val bottom: Float
                val colors: List<Color>
                if (mIsRtOrLb) {
                    top = mBezierControl2.y
                    bottom = mBezierControl2.y + SHADOW_SIZE
                    colors = frontShadowTB
                } else {
                    top = mBezierControl2.y - SHADOW_SIZE
                    bottom = mBezierControl2.y + 1
                    colors = frontShadowBT
                }
                val rotateDegrees = (
                    atan2(
                        (mBezierControl2.y - mTouchY).toDouble(),
                        (mBezierControl2.x - mTouchX).toDouble(),
                    ) * 180.0 / PI
                    ).toFloat()
                // 原版：control2.y < 0 时按 viewHeight 折算，hmg 超过对角线则整条带右移
                val temp = if (mBezierControl2.y < 0) {
                    (mBezierControl2.y - viewHeight).toDouble()
                } else {
                    mBezierControl2.y.toDouble()
                }
                val hmg = hypot(mBezierControl2.x.toDouble(), temp).toFloat()
                val left = if (hmg > mMaxLength) {
                    mBezierControl2.x - SHADOW_SIZE - hmg
                } else {
                    mBezierControl2.x - mMaxLength
                }
                val right = if (hmg > mMaxLength) {
                    mBezierControl2.x + mMaxLength - hmg
                } else {
                    mBezierControl2.x
                }
                withTransform({
                    rotate(rotateDegrees, Offset(mBezierControl2.x, mBezierControl2.y))
                }) {
                    drawRect(
                        brush = Brush.verticalGradient(colors, startY = top, endY = bottom),
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                    )
                }
            }
        }
    }

    // endregion

    private companion object {
        // 原版硬编码的阴影带宽度（px）
        const val SHADOW_SIZE = 25f

        // 原版 GradientDrawable 的颜色组：LR = colors[0] 在左，RL = colors[0] 在右（列表反转等价）
        // mBackShadowColors = intArrayOf(-0xeeeeef, 0x111111) → 0xFF111111 → 0x00111111
        val backShadowLR = listOf(Color(0xFF111111), Color(0x00111111))
        val backShadowRL = backShadowLR.asReversed()

        // color = intArrayOf(0x333333, -0x4fcccccd) → 0x00333333 → 0xB0333333
        val folderShadowLR = listOf(Color(0x00333333), Color(0xB0333333))
        val folderShadowRL = folderShadowLR.asReversed()

        // mFrontShadowColors = intArrayOf(-0x7feeeeef, 0x111111) → 0x80111111 → 0x00111111
        val frontShadowLR = listOf(Color(0x80111111), Color(0x00111111))
        val frontShadowRL = frontShadowLR.asReversed()
        val frontShadowTB = frontShadowLR
        val frontShadowBT = frontShadowRL
    }
}
