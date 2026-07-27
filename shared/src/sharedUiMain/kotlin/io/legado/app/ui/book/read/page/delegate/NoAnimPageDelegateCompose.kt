package io.legado.app.ui.book.read.page.delegate

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlinx.coroutines.CoroutineScope

/**
 * 无动画翻页 delegate（sharedUiMain，Compose Multiplatform 版）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate` 对应，
 * 翻页瞬间无过渡动画，松手即翻。
 *
 * # 与 app 端原版的等价性
 *
 * - app 端 `onAnimStart` 直接调 `readView.fillPage(mDirection)` + `stopScroll()` →
 *   KMP 版覆写为立即调基类 [HorizontalPageDelegate.onAnimStop]（同步翻页 + 章节联动）
 * - app 端 `onDraw` 空实现 → KMP 版 [renderPages] 仅渲染 curContent（不显示 prev/next 层）
 * - app 端 `setBitmap` 空实现 → KMP 版基类无 setBitmap 抽象方法，无需覆写
 *
 * # 章节边界联动
 *
 * 走基类 [HorizontalPageDelegate.onAnimStop] 逻辑：
 * - viewModel.nextPage() 返回 false → viewModel.moveToNextChapter()
 * - viewModel.prevPage() 返回 false → viewModel.moveToPrevChapter()
 *
 * @param viewModel 阅读 ViewModel
 * @param scope 协程作用域
 * @param animationSpeed 未使用（无动画），保留参数为构造一致性
 */
class NoAnimPageDelegateCompose(
    viewModel: ReadBookViewModelShared,
    scope: CoroutineScope,
    animationSpeed: Int = DEFAULT_ANIMATION_SPEED,
) : HorizontalPageDelegateCompose(viewModel, scope, animationSpeed) {

    /**
     * 无动画：松手立即翻页，不启动 Animatable 协程。
     *
     * 与 app 端 `NoAnimPageDelegate.onAnimStart` 直接调 `readView.fillPage(mDirection)` +
     * `stopScroll()` 对应。直接调用基类 [onAnimStop] 执行同步翻页 + 章节边界联动。
     */
    override fun onAnimStart(animationSpeed: Int) {
        if (!isMoved || mDirection == PageDirectionShared.NONE) {
            // 未移动或方向未定，不翻页（与基类 onAnimStart 守卫一致）
            return
        }
        // 立即翻页（不启动动画协程），与 app 端 stopScroll + fillPage 等价
        onAnimStop()
    }

    /**
     * 仅渲染当前页：无动画时 prev/next 层不显示。
     *
     * 与 app 端 `NoAnimPageDelegate.onDraw` 空实现对应——app 端 onDraw 空，
     * 但因为 `setBitmap` 也空，CanvasRecorder 无内容，所以 curPage 仍然显示。
     * KMP 版直接渲染 curContent() 等价。
     */
    @Composable
    override fun renderPages(
        pageWidthPx: Int,
        currentOffset: Float,
        direction: PageDirectionShared,
        prevContent: @Composable () -> Unit,
        curContent: @Composable () -> Unit,
        nextContent: @Composable () -> Unit,
    ) {
        // 无动画：始终只渲染当前页（忽略 currentOffset 和 direction）
        curContent()
    }

    /**
     * 无阴影：无动画翻页不需要阴影叠加层。
     * 与 app 端 `NoAnimPageDelegate.onDraw` 空实现对应。
     */
    override fun DrawScope.drawShadow(currentOffset: Float, viewWidth: Int) {
        // 无动画无阴影
    }
}
