package io.legado.app.ui.book.read.page

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.delegate.rememberPageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.formatTimeOfDay
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.math.roundToInt

/**
 * KMP 版阅读视图：用 Compose 替代 app 端 `ReadView` (FrameLayout + 3 个 PageView)。
 *
 * # 渲染路径
 *
 * [rememberPageDelegate] 按 `ReadBookConfig.pageAnim` 取翻页委托（对照原版 `ReadView.upPageAnim`），
 * 调 [io.legado.app.ui.book.read.page.delegate.PageDelegateCompose.renderPageAnimation]，
 * 把 3 个 [PageViewComposable] 作为 `@Composable () -> Unit` lambda 传入，delegate 完全接管：
 * - 三页位置 / 偏移 / 动画（用 `Modifier.offset` + `Animatable`）
 * - 手势检测（`detectDragGestures` / `detectTapGestures` 转发到 onDown/onScroll/onTap）
 * - 阴影等叠加层绘制（`Canvas` + `Brush.horizontalGradient`）
 *
 * 九宫格点击分区由本层判定后经 `onTapAt` 注入 delegate：拖动手势与点击动作互不干扰
 * （对照原版 ReadView 持有 `ClickArea`、delegate 只管动画的分工）。
 *
 * # 与 app 端差异
 *
 * - app 端用 View 的 onTouchEvent 分发到 PageDelegate；KMP 版把手势下沉到 delegate 自身的
 *   `pointerInput`，点击落点仍回调本层
 *
 * @param viewModel 阅读 ViewModel，提供 prevTextPage/curTextPage/nextTextPage 流
 * @param batteryLevel 电池电量 0-100，传 -1 表示不显示
 * @param clockText 当前系统时间 HH:mm，随 timeChanged 刷新
 * @param onClick 单击回调（动作 0=菜单，由调用方处理；翻页/切章在本 Composable 内消费）
 * @param onLongClick 长按回调（用于文字选择）
 * @param onAction 非翻页类点击动作（书签/目录/搜索等），对照 app 端 ReadView.click 的 callBack 分支
 */
@Composable
fun ReadViewComposable(
    viewModel: ReadBookViewModelShared,
    modifier: Modifier = Modifier,
    batteryLevel: Int = -1,
    clockText: String = formatTimeOfDay(systemCurrentTimeMillis()),
    onClick: (TextColumn?) -> Unit = {},
    onLongClick: (TextColumn?) -> Unit = {},
    onAction: (Int) -> Unit = {},
) {
    val prevTextPage by viewModel.prevTextPage.collectAsState()
    val curTextPage by viewModel.curTextPage.collectAsState()
    val nextTextPage by viewModel.nextTextPage.collectAsState()
    // 按 ReadBookConfig.pageAnim 取翻页委托，配置变更时重建（对照原版 ReadView.upPageAnim）
    val composeDelegate = rememberPageDelegate(viewModel)

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val pageWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val pageWidthInt = pageWidthPx.roundToInt()
        val pageHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val pageHeightInt = pageHeightPx.roundToInt()

        // 九宫格点击动作分发（对照 app 端 ReadView.onSingleTapUp → click(action)）
        val onTapAt: (Float, Float) -> Unit = { x, y ->
            when (val action = readClickActionConfig().actionAt(x, y, pageWidthInt, pageHeightInt)) {
                0 -> onClick(null)
                1 -> viewModel.turnPage(PageDirectionShared.NEXT)
                2 -> viewModel.turnPage(PageDirectionShared.PREV)
                3 -> viewModel.moveToNextChapter()
                // 原版 moveToPrevChapter(toLast = false)：切上一章后落到章首而非章末
                4 -> viewModel.moveToPrevChapter(toLast = false)
                else -> onAction(action)
            }
        }

        // 点击分区由本层决定，delegate 只负责动画（对照原版 ReadView 持有 ClickArea）
        composeDelegate.onTapAt = onTapAt
        composeDelegate.renderPageAnimation(
            pageWidthPx = pageWidthInt,
            pageHeightPx = pageHeightInt,
            prevContent = {
                prevTextPage?.let { page ->
                    PageViewComposable(
                        textPage = page,
                        modifier = Modifier.fillMaxSize(),
                        batteryLevel = batteryLevel,
                        clockText = clockText,
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
                        clockText = clockText,
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
                        clockText = clockText,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                }
            },
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}
