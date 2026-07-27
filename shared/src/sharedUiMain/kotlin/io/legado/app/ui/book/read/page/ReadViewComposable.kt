package io.legado.app.ui.book.read.page

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.delegate.PageDelegateCompose
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlin.math.roundToInt

/**
 * KMP 版阅读视图：用 Compose 替代 app 端 `ReadView` (FrameLayout + 3 个 PageView)。
 *
 * # 渲染路径
 *
 * ## A. 注入了 [PageDelegate]（如桌面端 [io.legado.app.ui.book.read.page.delegate.CoverPageDelegate]）
 *
 * 调 [PageDelegate.renderPageAnimation]，把 3 个 [PageViewComposable]
 * 作为 `@Composable () -> Unit` lambda 传入，delegate 完全接管：
 * - 三页位置 / 偏移 / 动画（用 `Modifier.offset` + `Animatable`）
 * - 手势检测（`detectDragGestures` / `detectTapGestures` 转发到 onDown/onScroll/onTap）
 * - 阴影等叠加层绘制（`Canvas` + `Brush.horizontalGradient`）
 *
 * ## B. 未注入 pageDelegate（pageDelegate=null）
 *
 * 走兜底路径：`BoxWithConstraints` 内直接放 3 个 [PageViewComposable]，
 * 用 `Modifier.offset` 把 prevPage/nextPage 移出视口外（仅 curPage 可见），
 * 用 `detectTapGestures` 检测单击 / 长按转发到 onClick / onLongClick。
 * 该路径不支持滑动翻页动画，仅支持点击切换。
 *
 * # 与 app 端差异
 *
 * - app 端用 View 的 onTouchEvent + PageDelegate 处理复杂手势（滑动/scroll/animation）；
 *   KMP 版路径 A 把所有手势下沉到 delegate，路径 B 仅支持 tap
 * - app 端 prevPage/nextPage 默认 invisible；KMP 版路径 B 用 offset 移出可视区域实现等价效果
 *
 * @param viewModel 阅读 ViewModel，提供 prevTextPage/curTextPage/nextTextPage 流 + pageDelegate
 * @param batteryLevel 电池电量 0-100，传 -1 表示不显示
 * @param onClick 单击回调（中心区域 → 菜单，由调用方处理；左右区域由 delegate 内部翻页不转发）
 * @param onLongClick 长按回调（用于文字选择）
 */
@Composable
fun ReadViewComposable(
    viewModel: ReadBookViewModelShared,
    modifier: Modifier = Modifier,
    batteryLevel: Int = -1,
    onClick: (TextColumn?) -> Unit = {},
    onLongClick: (TextColumn?) -> Unit = {},
) {
    val prevTextPage by viewModel.prevTextPage.collectAsState()
    val curTextPage by viewModel.curTextPage.collectAsState()
    val nextTextPage by viewModel.nextTextPage.collectAsState()
    // 取 pageDelegate（PageDelegateShared → 向下转型为 PageDelegateCompose 才能调 renderPageAnimation）
    // PageDelegateCompose 是 sharedUiMain 抽象基类，实现了 renderPageAnimation 抽象方法
    val composeDelegate = viewModel.pageDelegate as? PageDelegateCompose

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val pageWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val pageWidthInt = pageWidthPx.roundToInt()
        val pageHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val pageHeightInt = pageHeightPx.roundToInt()

        if (composeDelegate != null) {
            // 路径 A：注入了 Compose 翻页 delegate，完全接管三页位置 / 手势 / 动画
            composeDelegate.renderPageAnimation(
                pageWidthPx = pageWidthInt,
                pageHeightPx = pageHeightInt,
                prevContent = {
                    prevTextPage?.let { page ->
                        PageViewComposable(
                            textPage = page,
                            modifier = Modifier.fillMaxSize(),
                            batteryLevel = batteryLevel,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    }
                },
                curContent = {
                    curTextPage?.let { page ->
                        PageViewComposable(
                            textPage = page,
                            modifier = Modifier.fillMaxSize(),
                            batteryLevel = batteryLevel,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    }
                },
                nextContent = {
                    nextTextPage?.let { page ->
                        PageViewComposable(
                            textPage = page,
                            modifier = Modifier.fillMaxSize(),
                            batteryLevel = batteryLevel,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    }
                },
                onClick = onClick,
                onLongClick = onLongClick,
            )
        } else {
            // 路径 B：未注入 delegate，走兜底路径（仅 tap，无翻页动画）
            FallbackReadView(
                prevTextPage = prevTextPage,
                curTextPage = curTextPage,
                nextTextPage = nextTextPage,
                pageWidthInt = pageWidthInt,
                batteryLevel = batteryLevel,
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }
    }
}

/**
 * 兜底阅读视图（无 pageDelegate 时使用）。
 *
 * 与原 ReadViewComposable（KP2-D P0 之前版本）行为一致：
 * - 3 个 [PageViewComposable] 用 [Modifier.offset] 控制位置（prev 在 -width，cur 在 0，next 在 +width）
 * - 用 [detectTapGestures] 检测单击 / 长按
 *
 * 保留路径 B 是为了让未注入 delegate 的平台（如 iOS future / ohos）
 * 仍能显示阅读内容，仅不支持翻页动画。
 */
@Composable
private fun FallbackReadView(
    prevTextPage: io.legado.app.ui.book.read.page.entities.TextPage?,
    curTextPage: io.legado.app.ui.book.read.page.entities.TextPage?,
    nextTextPage: io.legado.app.ui.book.read.page.entities.TextPage?,
    pageWidthInt: Int,
    batteryLevel: Int,
    onClick: (TextColumn?) -> Unit,
    onLongClick: (TextColumn?) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        // 单击：转发到 viewModel 处理（中心 → 菜单，左右 → 翻页）
                        // 命中具体 TextColumn 的查找由 pageDelegate actual 补全
                        onClick(null)
                    },
                    onLongPress = { offset ->
                        onLongClick(null)
                    },
                )
            },
    ) {
        // 上一页：左移一个 pageWidth，默认不可见
        prevTextPage?.let { page ->
            PageViewComposable(
                textPage = page,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(-pageWidthInt, 0) },
                batteryLevel = batteryLevel,
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }

        // 当前页：位置 0
        curTextPage?.let { page ->
            PageViewComposable(
                textPage = page,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, 0) },
                batteryLevel = batteryLevel,
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }

        // 下一页：右移一个 pageWidth，默认不可见
        nextTextPage?.let { page ->
            PageViewComposable(
                textPage = page,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(pageWidthInt, 0) },
                batteryLevel = batteryLevel,
                onClick = onClick,
                onLongClick = onLongClick,
            )
        }
    }
}
