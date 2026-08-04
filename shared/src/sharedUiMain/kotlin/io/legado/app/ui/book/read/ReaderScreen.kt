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
 * - [batteryLevel] 传给 [ReadViewComposable] 显示页眉/页脚电量
 * - [clockText] 传给 [ReadViewComposable] 显示页眉/页脚时间
 */
data class ReaderUiState(
    val viewModel: ReadBookViewModelShared,
    val menuState: ReadMenuState,
    val batteryLevel: StateFlow<Int>,
    val clockText: StateFlow<String>,
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

    /** 页面长按（仅空白区域回落；文字长按走页内选择，图片长按走 [onImageLongPress]） */
    fun onPageLongClick(column: TextColumn?)

    /** 图片长按（命中图片列，携带 src 与长按点坐标；对照旧 onImageLongPress → 图片操作菜单） */
    fun onImageLongPress(src: String, x: Float, y: Float) {}

    /**
     * 页内文字选择完成（长按选中文字后抬起）：携带选中文本与选区起点锚点
     * （阅读页内坐标，含滚动折算），平台弹浮动文本操作菜单并跟随选区
     * （对照旧 ReadView.CallBack.showTextActionMenu；默认空实现，未接入的平台忽略）
     */
    fun onTextSelection(text: String, anchorX: Float, anchorY: Float) {}

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
    val clockText by state.clockText.collectAsState()
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
            clockText = clockText,
            onClick = { column -> actions.onPageClick(column) },
            onLongClick = { column -> actions.onPageLongClick(column) },
            onImageLongPress = { src, x, y -> actions.onImageLongPress(src, x, y) },
            onAction = { action -> actions.onPageAction(action) },
            onSelectionMenu = { text, anchor ->
                actions.onTextSelection(
                    text,
                    anchor?.x ?: 0f,
                    anchor?.y ?: 0f,
                )
            },
            menuVisible = { state.menuState.isVisible },
        )
        ReadMenuOverlay(state = state.menuState)
    }
}
