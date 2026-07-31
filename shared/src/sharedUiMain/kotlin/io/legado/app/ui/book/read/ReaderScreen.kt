package io.legado.app.ui.book.read

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    /** 页面单击且动作为 0（菜单）时回调，其余动作在 [ReadViewComposable] 内消费或走 [onPageAction] */
    fun onPageClick(column: TextColumn?)

    /** 页面长按（用于文字选择） */
    fun onPageLongClick(column: TextColumn?)

    /**
     * 九宫格点击的非翻页动作（对照 app 端 ReadView.click 里走 callBack 的分支）：
     * 7=添加书签 / 9=替换状态 / 10=目录 / 11=全文搜索 / 13=朗读暂停继续。
     */
    fun onPageAction(action: Int) {}

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
    focusRequester: FocusRequester? = null,
) {
    val batteryLevel by state.batteryLevel.collectAsState()
    Box(
        modifier
            .fillMaxSize()
            // 键盘翻页前提: 全应用无焦点节点时 Compose 不会把按键派发进节点树
            // (FocusOwnerImpl.dispatchKeyEvent 找不到 KeyInput 节点直接 return false)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusable()
    ) {
        ReadViewComposable(
            viewModel = state.viewModel,
            batteryLevel = batteryLevel,
            onClick = { column -> actions.onPageClick(column) },
            onLongClick = { column -> actions.onPageLongClick(column) },
            onAction = { action -> actions.onPageAction(action) },
        )
        ReadMenuOverlay(state = state.menuState)
    }
}
