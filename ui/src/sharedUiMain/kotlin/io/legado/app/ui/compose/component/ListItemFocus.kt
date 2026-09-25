package io.legado.app.ui.compose.component

import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 列表条目的键盘/遥控器焦点资格: 让条目能被焦点搜索命中。
 *
 * 原版各条目布局写的是 `android:focusable="true"` + `android:background="@drawable/bg_item_focused_on_tv"`
 * (`item_bookshelf_list.xml` / `item_bookshelf_grid.xml` / `item_explore_video.xml` /
 * `item_explore_source.xml` / `item_home_cover_card.xml` / `item_home_rank_book.xml`)。
 * 那个 selector 只有一条 `state_focused` 分支取 `@color/btn_bg`, 是 View 系统没有内建焦点
 * 状态层时的替代品; Compose 的 ripple 自带焦点状态层 (`Ripple.kt` 的 `handleInteraction`
 * 处理 `FocusInteraction.Focus`, 取 `rippleAlpha.focusedAlpha`), 且 `clickable` 的
 * `focusableNode` 与 `indicationNode` 共用同一个 `interactionSource` (`Clickable.kt` 的
 * `initializeIndicationAndInteractionSourceIfNeeded`), 焦点变化直接驱动该状态层,
 * 故此处不再自绘底色。
 *
 * [focusProperties] `canFocus = true` 是必需的: `clickable`/`combinedClickable` 内部的
 * `FocusTarget` 用 `Focusability.SystemDefined`, 触摸输入模式下不参与焦点搜索, 而原版 View 系统的
 * `android:focusable="true"` 无此限制。被压栈页由页面根的 `focusProperties { canFocus = false }`
 * 覆盖 (外层覆盖内层), 本条无需自己判可见性。
 *
 * **Enter 激活**: 复用 `combinedClickable` 内建语义 (Key.DirectionCenter / Key.Enter /
 * Key.NumPadEnter / Key.Spacebar 触发 press + click), 不另建按键处理。
 */
fun Modifier.listItemFocus(): Modifier = this.focusProperties { canFocus = true }

/**
 * pager 页内容根的焦点门: 仅当前页可参与焦点搜索。
 *
 * pager 的非当前页仍在组合树里 (beyondViewportPageCount 驻留)，页内条目的
 * [listItemFocus] 不感知自己是否可见，方向键会跳进看不见的页。本条挂在每页
 * 内容根 (外层覆盖内层, 见 FocusPropertiesModifierNode 契约:
 * 层级更高的 focusProperties 覆盖更低的)，把非当前页整页挡在焦点搜索外。
 *
 * 判定在 lambda 内读 [PagerState.currentPage]: fetchFocusProperties 在每次焦点
 * 操作时重新执行各层 lambda (非组合期快照)，页切换后无需重组即生效。
 */
fun Modifier.pagerPageFocus(pagerState: PagerState, page: Int): Modifier =
    this.focusProperties { canFocus = pagerState.currentPage == page }

/**
 * 方向键焦点导航 (桌面端方向键 / TV 遥控): 桌面焦点系统的按键处理只认 Tab/Center/Back,
 * 本补丁在容器的 onKeyEvent (冒泡阶段) 补四方向 moveFocus。
 *
 * 挂点要求: 处在焦点节点的 KeyInput 祖先链上 (窗口根 / 对话框内容根)。焦点链冒泡顺序是
 * 焦点节点 → 逐级祖先 → 容器; 输入框的上下键不会冒泡到本节点 (单行由输入框侧消费,
 * 多行移光标被 TextField 消费), 到达时只剩"焦点不在输入框"的情形。
 *
 * 无可聚焦条目 (空白页 / 列表未渲染) 时 moveFocus 返回 false, 事件照旧放行。
 */
fun Modifier.directionKeyFocusNavigation(focusManager: FocusManager): Modifier =
    onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val direction = when (event.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            else -> return@onKeyEvent false
        }
        focusManager.moveFocus(direction)
    }
