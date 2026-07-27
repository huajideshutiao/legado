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
import androidx.compose.ui.unit.IntOffset
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/**
 * 滑动式翻页 delegate（sharedUiMain，Compose Multiplatform 版）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.delegate.SlidePageDelegate` 对应，
 * 用 Compose 跨平台 API 替代 Android `Scroller` + `Canvas` + `CanvasRecorder`。
 *
 * # 与 CoverPageDelegate 的核心差异
 *
 * | 维度              | Cover（覆盖）                          | Slide（滑动）                          |
 * | ---               | ---                                    | ---                                    |
 * | curPage 行为      | 静止不动，被覆盖页遮盖                  | 跟随手指滑出（与 nextPage 同步移动）    |
 * | prev/nextPage 偏移| 单侧（`-viewWidth + offset` 或 `viewWidth + offset`） | 反向（`offset - viewWidth` 或 `offset + viewWidth`） |
 * | 阴影位置          | 在覆盖页靠近视口的一侧                  | 在 curPage 滑出后的边缘（页缝处）        |
 * | currentOffset 含义| 覆盖页位置（绝对值表示覆盖进度）        | curPage 偏移（直接表示 curPage 位移）   |
 *
 * # 翻页方向语义
 *
 * - **NEXT**（手指向左滑，currentOffset < 0）：
 *   - curPage 向左滑出：偏移从 0 → -viewWidth
 *   - nextPage 从右侧滑入：偏移从 +viewWidth → 0（即 `currentOffset + viewWidth`）
 * - **PREV**（手指向右滑，currentOffset > 0）：
 *   - curPage 向右滑出：偏移从 0 → +viewWidth
 *   - prevPage 从左侧滑入：偏移从 -viewWidth → 0（即 `currentOffset - viewWidth`）
 *
 * # 与 app 端原版的算法等价性
 *
 * - app 端 `canvas.withTranslation(distanceX + viewWidth) { curRecorder.draw }` (PREV) →
 *   KMP 版 `Modifier.offset { IntOffset(currentOffset, 0) }`（curPage 跟随 currentOffset 滑出）
 * - app 端 `canvas.withTranslation(distanceX) { prevRecorder.draw }` (PREV) →
 *   KMP 版 `Modifier.offset { IntOffset(currentOffset - viewWidth, 0) }`
 *
 * @param viewModel 阅读 ViewModel
 * @param scope 协程作用域
 * @param animationSpeed 默认动画速度（ms/页宽）
 */
class SlidePageDelegateCompose(
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
        // Slide 模式：curPage 与 prev/nextPage 同向联动
        // 与 app 端 SlidePageDelegate.onDraw 中两 recorder 同时 withTranslation 对应
        when (direction) {
            PageDirectionShared.PREV -> {
                // prevPage 从左侧滑入：偏移 = currentOffset - viewWidth
                // currentOffset 从 0 → viewWidth 时，prevPage 偏移从 -viewWidth → 0
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (currentOffset - pageWidthPx).roundToInt(),
                                y = 0,
                            )
                        }
                        .fillMaxSize(),
                ) {
                    prevContent()
                }
                // curPage 向右滑出：偏移 = currentOffset
                // currentOffset 从 0 → viewWidth 时，curPage 偏移从 0 → viewWidth（完全滑出右侧）
                // 与 app 端 canvas.withTranslation(distanceX + viewWidth) { curRecorder.draw } 对应
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = currentOffset.roundToInt(),
                                y = 0,
                            )
                        }
                        .fillMaxSize(),
                ) {
                    curContent()
                }
            }
            PageDirectionShared.NEXT -> {
                // nextPage 从右侧滑入：偏移 = currentOffset + viewWidth
                // currentOffset 从 0 → -viewWidth 时，nextPage 偏移从 viewWidth → 0
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (currentOffset + pageWidthPx).roundToInt(),
                                y = 0,
                            )
                        }
                        .fillMaxSize(),
                ) {
                    nextContent()
                }
                // curPage 向左滑出：偏移 = currentOffset
                // currentOffset 从 0 → -viewWidth 时，curPage 偏移从 0 → -viewWidth（完全滑出左侧）
                // 与 app 端 canvas.withTranslation(distanceX) { nextRecorder.draw } +
                //      canvas.withTranslation(distanceX - viewWidth) { curRecorder.draw } 对应
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = currentOffset.roundToInt(),
                                y = 0,
                            )
                        }
                        .fillMaxSize(),
                ) {
                    curContent()
                }
            }
            else -> {
                // 静止状态：仅渲染当前页（与 Cover 一致）
                curContent()
            }
        }
    }

    /**
     * 绘制滑动翻页阴影：在 curPage 边缘与对侧页面交界处。
     *
     * **与 [CoverPageDelegate.drawShadow] 的差异**：
     * - Cover 阴影跟随覆盖页边缘（覆盖页在视口内的一侧）
     * - Slide 阴影跟随 curPage 滑出后的边缘（页缝位置）
     *
     * - PREV：curPage 向右滑出，curPage 左边缘在 currentOffset 位置
     *   - 阴影在 curPage 左边缘左侧（prevPage 右边缘）展开 shadowWidth
     *   - 渐变方向：左浅右深（让 prevPage 右边缘有阴影投在 curPage 上）
     * - NEXT：curPage 向左滑出，curPage 右边缘在 currentOffset + viewWidth 位置
     *   - 阴影在 curPage 右边缘右侧（nextPage 左边缘）展开 shadowWidth
     *   - 渐变方向：左深右浅（让 nextPage 左边缘有阴影投在 curPage 上）
     */
    override fun DrawScope.drawShadow(currentOffset: Float, viewWidth: Int) {
        val shadowWidth = SHADOW_WIDTH_PX.toFloat()
        // 用解构声明集中表达 (startColor, endColor, shadowLeft) 三元组
        val (startColor, endColor, shadowLeft) = when (mDirection) {
            PageDirectionShared.PREV -> {
                // curPage 左边缘在 currentOffset 位置，阴影在左侧展开 shadowWidth
                val left = currentOffset - shadowWidth
                // 左浅右深
                Triple(Color(0x00000000), Color(0x66111111), left)
            }
            PageDirectionShared.NEXT -> {
                // curPage 右边缘在 currentOffset + viewWidth 位置，阴影在右侧展开 shadowWidth
                val left = viewWidth + currentOffset
                // 左深右浅
                Triple(Color(0x66111111), Color(0x00000000), left)
            }
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
