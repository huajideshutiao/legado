package io.legado.app.ui.book.read

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.book.read.page.ReadViewComposable
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlinx.coroutines.flow.StateFlow

/**
 * 阅读页 Composable 展示状态。
 *
 * 对照 app 端 [ReadBookActivity.Content] 的渲染输入：
 * - [viewModel] 驱动 [ReadViewComposable]（三页流 + pageDelegate）
 * - [menuState] 驱动 [ReadMenuOverlay]（顶/底栏菜单）
 * - [batteryLevel] 传给 [ReadViewComposable] 显示状态栏电量
 */
data class ReaderUiState(
    val viewModel: ReadBookViewModelShared,
    val menuState: ReadMenuState,
    val batteryLevel: StateFlow<Int>,
)

/**
 * 阅读页用户交互回调。
 *
 * 平台层（app/桌面）实现本接口，桥接平台专属行为（返回导航、文字选择等）。
 * 菜单项交互（目录/朗读/设置等）由 [ReadMenuState] 的回调方法直接桥接平台实现，
 * 不经过本接口。
 */
interface ReaderUiActions {
    /** 页面单击（中心区域转发，左右区域由 delegate 内部翻页不转发） */
    fun onPageClick(column: TextColumn?)

    /** 页面长按（用于文字选择） */
    fun onPageLongClick(column: TextColumn?)

    /** 返回 */
    fun onBack()
}

/**
 * 阅读页主体：组合 [ReadViewComposable] + [ReadMenuOverlay]。
 *
 * 对照 app 端 [ReadBookActivity.Content]：
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     AndroidView(factory = { renderLayer }, modifier = Modifier.fillMaxSize())
 *     ReadMenuOverlay(readMenu)
 *     ...
 * }
 * ```
 * shared 版用 [ReadViewComposable] 替代 AndroidView(renderLayer)，其余结构一致。
 * 菜单隐藏时 [ReadMenuOverlay] 内部 early return 零组合，仅 [ReadViewComposable] 接管手势。
 */
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    actions: ReaderUiActions,
    modifier: Modifier = Modifier,
) {
    val batteryLevel by state.batteryLevel.collectAsState()
    Box(modifier.fillMaxSize()) {
        ReadViewComposable(
            viewModel = state.viewModel,
            batteryLevel = batteryLevel,
            onClick = { column -> actions.onPageClick(column) },
            onLongClick = { column -> actions.onPageLongClick(column) },
        )
        ReadMenuOverlay(state = state.menuState)
    }
}
