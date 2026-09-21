package io.legado.app.ui.book.read.page.delegate

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.delegate.PageDelegateCompose.Companion.SHADOW_WIDTH_PX
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/**
 * 覆盖式翻页 delegate（sharedUiMain，Compose Multiplatform 版）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.delegate.CoverPageDelegate` 对应，
 * 用 Compose 跨平台 API 替代 Android `Scroller` + `Canvas` + `CanvasRecorder`。
 *
 * # 翻页方向语义 (对照 app 端原版 CoverPageDelegate.onDraw 的不对称实现)
 *
 * - **NEXT**（手指向左滑，offsetX < 0）：curPage 在上层向左拖出（0 → -viewWidth），
 *   nextPage 固定在下层逐渐露出
 *   （原版：withClip 原位裁剪 nextRecorder 在底层露出 + withTranslation 平移 curRecorder 在上层拖出）
 * - **PREV**（手指向右滑，offsetX > 0）：prevPage 从左侧滑入覆盖 curPage
 *   - currentOffset 从 0 → +viewWidth（prevPage 偏移 = -viewWidth + currentOffset，从 -viewWidth → 0）
 *   （原版：withTranslation(distanceX) { prevRecorder.draw }，当前页静止在底层）
 *
 * # 与 app 端原版的算法等价性
 *
 * - app 端 `distanceX = if (offsetX > 0) offsetX - viewWidth else offsetX + viewWidth` →
 *   KMP 版 `_currentOffset = offsetX - startX`（直接用偏移量，方向相反但语义等价）
 * - app 端 `shadowDrawableR setBounds(0, 0, 30, viewHeight)` + `GradientDrawable LEFT_RIGHT` →
 *   KMP 版 `SHADOW_WIDTH_PX = 30` + `Brush.horizontalGradient`
 * - app 端 `canvas.withTranslation(distanceX) { prevRecorder.draw }`（PREV）→
 *   KMP 版 `Modifier.offset { IntOffset(-pageWidthPx + currentOffset, 0) }`
 * - app 端 `canvas.withTranslation(distanceX - viewWidth) { curRecorder.draw }`（NEXT）→
 *   KMP 版 `Modifier.offset { IntOffset(currentOffset, 0) }`
 *
 * @param viewModel 阅读 ViewModel，提供 prevTextPage/curTextPage/nextTextPage 流 + 翻页 API
 * @param scope 协程作用域，actual 平台注入（桌面=应用主作用域 / Android=viewModelScope）
 * @param animationSpeed 默认动画速度（ms/页宽，与 app 端 `defaultAnimationSpeed=300` 对应）
 */
class CoverPageDelegateCompose(
    viewModel: ReadBookViewModelShared,
    scope: CoroutineScope,
    animationSpeed: Int = DEFAULT_ANIMATION_SPEED,
) : HorizontalPageDelegateCompose(viewModel, scope, animationSpeed) {

    @Composable
    override fun renderPages(
        pageWidthPx: Int,
        currentOffset: Float,
        direction: PageDirectionShared,
        prevContent: @Composable () -> Unit,
        curContent: @Composable () -> Unit,
        nextContent: @Composable () -> Unit,
    ) {
        when (direction) {
            PageDirectionShared.PREV -> {
                // 底层：当前页静止
                curContent()
                // 上层：prevPage 从左侧滑入覆盖 curPage
                // 偏移 = -viewWidth + currentOffset（currentOffset 从 0→viewWidth 时，偏移从 -viewWidth→0）
                // 与 app 端 canvas.withTranslation(distanceX) { prevRecorder.draw } 对应
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (-pageWidthPx + currentOffset).roundToInt(),
                                y = 0,
                            )
                        }
                        .fillMaxSize()
                        // 独立渲染层：内容不变时滑入/滑出只更新层 transform，不重绘整页
                        // （Skia 后端无显示列表缓存，无此层时 offset 每帧移动会整页重绘文字）
                        .graphicsLayer { },
                ) {
                    prevContent()
                }
            }
            PageDirectionShared.NEXT -> {
                // 底层：nextPage 原位固定，当前页拖走后逐渐露出
                // 与 app 端 canvas.withClip(width + offsetX, 0f, width, height) { nextRecorder.draw } 对应
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { },
                ) {
                    nextContent()
                }
                // 上层：curPage 跟随手势向左拖出
                // 与 app 端 canvas.withTranslation(distanceX - viewWidth) { curRecorder.draw } 对应
                Box(
                    modifier = Modifier
                        .offset { IntOffset(currentOffset.roundToInt(), 0) }
                        .fillMaxSize()
                        // 独立渲染层：移动只更新层 transform，不重绘整页
                        .graphicsLayer { },
                ) {
                    curContent()
                }
            }
            else -> {
                // 无手势无动画：仅当前页静止
                curContent()
            }
        }
    }

    /**
     * 绘制覆盖翻页阴影（与 app 端 `CoverPageDelegate.addShadow` + `GradientDrawable` 对应）。
     *
     * 阴影位置：贴在移动页的前缘
     * - PREV：prevPage 右边缘（位置 = currentOffset）
     * - NEXT：curPage 右边缘（位置 = viewWidth + currentOffset）
     *
     * 阴影样式：从透明到半透明黑色（0x00000000 → 0x66111111）的水平渐变，宽度 [SHADOW_WIDTH_PX]
     */
    override fun DrawScope.drawShadow(currentOffset: Float, viewWidth: Int) {
        val shadowWidth = SHADOW_WIDTH_PX.toFloat()
        val shadowLeft = when (mDirection) {
            PageDirectionShared.PREV -> {
                // prevPage 右边缘 = -viewWidth + currentOffset + viewWidth = currentOffset
                // 阴影在 prevPage 右边缘（currentOffset 位置）向左展开 shadowWidth
                currentOffset - shadowWidth
            }
            PageDirectionShared.NEXT -> {
                // curPage 右边缘 = viewWidth + currentOffset
                // 阴影在 curPage 右边缘向右展开 shadowWidth
                viewWidth + currentOffset
            }
            else -> return
        }
        // 与 app 端 shadowColors = intArrayOf(0x66111111, 0x00000000) 对应
        // GradientDrawable Orientation.LEFT_RIGHT 表示从左到右：左深右浅
        // PREV 方向：阴影画在 prevPage 自身右缘上方 (阴影 Canvas 为 Box 最后一个 child,
        // z 序在页面之上才可见), 左浅右深贴 prevPage 前缘; 与原版"画在 curPage 右缘左侧"
        // 的载体不同, 观感相近 (既有行为, 非本次引入)
        // NEXT 方向：阴影在 curPage 右边缘，左深右浅（贴 curPage 前缘深，投在 nextPage 上）
        val (startColor, endColor) = when (mDirection) {
            PageDirectionShared.PREV -> Color(0x00000000) to Color(0x66111111)
            PageDirectionShared.NEXT -> Color(0x66111111) to Color(0x00000000)
            else -> return
        }
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(startColor, endColor),
                startX = shadowLeft,
                endX = shadowLeft + shadowWidth,
            ),
            topLeft = Offset(shadowLeft, 0f),
            size = Size(shadowWidth, size.height),
        )
    }
}
