package io.legado.app.ui.book.read.page

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import io.legado.app.ui.book.read.page.delegate.PageDelegateCompose
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlin.math.abs

/**
 * 让本 Layout 的指针输入与下层兄弟节点共享命中路径。
 *
 * CMP 默认行为: [PointerInputModifierNode.sharePointerInputWithSiblings] = false, 命中测试
 * 在顶层兄弟布局命中后即停止, 下层兄弟节点完全收不到事件。阅读页鼠标手势层叠在
 * selection 层/delegate 之上时, 下层 selection 层被阻断 —— 表现为长按能上色 (激活在鼠标层)
 * 但扩选与弹菜单 (selection 层职责) 全部失效 (2026-08-04 实测复现)。
 *
 * 开启共享后: 鼠标事件由本层在 Initial pass 统一消费, 下层手势层见 isConsumed 让位;
 * 触摸事件本层不消费, 下层 delegate 手势链正常接管 (触摸路径此前同样被阻断, 一并修复)。
 */
internal fun Modifier.sharePointerInputWithSiblings(): Modifier =
    this then SharePointerInputElement()

private class SharePointerInputNode : Modifier.Node(), PointerInputModifierNode {
    override fun sharePointerInputWithSiblings(): Boolean = true

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) = Unit

    override fun onCancelPointerInput() = Unit
}

private class SharePointerInputElement : ModifierNodeElement<SharePointerInputNode>() {
    override fun create(): SharePointerInputNode = SharePointerInputNode()
    override fun update(node: SharePointerInputNode) = Unit
    override fun equals(other: Any?): Boolean = other is SharePointerInputElement
    override fun hashCode(): Int = 0x5EEDF00D
}

/**
 * 鼠标手势层对翻页委托的最小依赖：只用到拖拽三件套 + 单击分发，
 * 与动画/渲染无关（便于独立复现与测试；[PageDelegateCompose] 已满足）。
 */
internal interface MouseDragDelegate {
    fun onDown(x: Float, y: Float)
    fun onScroll(x: Float, y: Float)
    fun onAnimStart(animationSpeed: Int)
    fun onTap(x: Float, y: Float): Boolean
}

/**
 * 桌面端鼠标手势接管层：鼠标左键的 单击 / 长按 / 拖拽 全部由本层处理并统一消费，
 * 使下层 delegate 的 detectTapGestures/detectDragGestures 对鼠标不再重复触发。
 *
 * # 背景 (2026-08-04)
 *
 * 用户实测桌面端阅读页鼠标"拖拽翻页 + 点击"全部无效。Compose Desktop 的指针事件
 * 与触摸同管道 (detectTapGestures/detectDragGestures 源码层面均不排斥 Mouse)，但阅读页
 * 手势挂在内层 Box，实测事件未到达。参照 F68 漫画页 [mangaMouseDragGestures] 的已验证
 * 模式：在最上层挂自定义鼠标手势，Initial pass 消费事件 —— 消费后 Main pass 的
 * 各手势层看到 isConsumed 即中止 (changedToDown/changedToUp/awaitPointerSlop 均检查
 * isConsumed)，本层成为鼠标事件的唯一处理者，与下层状态无关，确定性生效。
 *
 * # 语义 (与触摸行为对齐)
 *
 * - 单击 → [PageDelegateCompose.onTap]（九宫格动作/翻页/菜单，对照 delegate 的 detectTapGestures）
 * - 长按 → 页内文字选择起点（对照 detectTapGestures 的 onLongPress → 页内长按）
 * - 拖拽 → onDown → onScroll → 松手 onAnimStart（对照 delegate 的 detectDragGestures）
 * - 按下时已有文字选择 → 取消选择并吞掉本次点击（对照 selection 层 suppressedTap 语义）
 * - 选择激活期间拖拽 → 本层继续消费事件（Initial pass），selection 层 Main pass 同步扩选；
 *   长按后松手由本层弹选择菜单（2026-08-04 修复：原实现依赖 selection 层在抬起事件
 *   上弹菜单，桌面端实测不弹，改为长按路径自包含——抬起即弹，不依赖下层事件时序）
 *
 * # 四端安全
 *
 * 仅 [PointerType.Mouse] 生效且不消费触摸事件；菜单可见时让位 ReadMenuOverlay 的
 * bg clickable（点击收菜单）；非左键（中/右键）立即结束且不消费。
 */
internal suspend fun PointerInputScope.readerMouseGestures(
    delegate: MouseDragDelegate,
    onClickFallback: (TextColumn?) -> Unit,
    onLongPressAt: (Float, Float) -> Unit,
    isSelectionActive: () -> Boolean,
    cancelSelection: () -> Unit,
    menuVisible: () -> Boolean,
    /** 长按选中后松手时弹选择菜单（携带选中文本；仅鼠标长按路径触发，触摸仍走 selection 层） */
    onLongPressMenu: (String) -> Unit = {},
    /** 当前选中文本（弹菜单时取，对照 selection.selectedText()） */
    selectionText: () -> String = { "" },
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // 仅鼠标；触摸仍走原手势链（delegate 的 detectDragGestures/detectTapGestures）
        if (down.type != PointerType.Mouse) return@awaitEachGesture
        // 菜单可见时 ReadMenuOverlay 的全屏 bg clickable 接管点击（收起菜单），本层不抢
        if (menuVisible()) return@awaitEachGesture
        // 本层接管后统一消费 down，使 delegate 的手势层对鼠标完全停摆，避免重复触发
        down.consume()
        val downId = down.id
        val startPos = down.position
        val slop = viewConfiguration.touchSlop
        // 按下时已有文字选择：取消选择（对照 selection 层 ACTION_DOWN → cancelSelect）
        val suppressedTap = if (isSelectionActive()) {
            cancelSelection()
            true
        } else {
            false
        }
        // 等待三选一：越 slop（拖拽开始）/ 抬起（单击）/ 长按超时（文字选择）
        var up: PointerInputChange? = null
        var dragStart: PointerInputChange? = null
        var primaryLost = false
        var isLongPress = false
        try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == downId } ?: break
                    if (!change.pressed) {
                        up = change
                        break
                    }
                    // 非左键（中/右键）按下：结束手势不消费（右键菜单等不受影响）
                    if (!event.buttons.isPrimaryPressed) {
                        primaryLost = true
                        up = change
                        break
                    }
                    if (change.isConsumed) {
                        up = change
                        break
                    }
                    if (abs(change.position.x - startPos.x) > slop ||
                        abs(change.position.y - startPos.y) > slop
                    ) {
                        dragStart = change
                        break
                    }
                }
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            isLongPress = true
        }
        // 局部 val 拷贝：被 withTimeout lambda 捕获的 var 无法智能转换
        val dragStartChange = dragStart
        val upChange = up
        when {
            dragStartChange != null -> {
                // 拖拽翻页（对照 detectDragGestures：onDragStart → onScroll → onDragEnd）
                delegate.onDown(dragStartChange.position.x, dragStartChange.position.y)
                dragStartChange.consume()
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == downId } ?: break
                    if (!change.pressed) {
                        change.consume()
                        // 松手启动翻页动画（滚动模式为 no-op，位置已随拖动滚动）
                        if (!isSelectionActive()) {
                            delegate.onAnimStart(PageDelegateCompose.DEFAULT_ANIMATION_SPEED)
                        }
                        break
                    }
                    if (!event.buttons.isPrimaryPressed) break
                    // 长按选中后拖拽扩选：让位 selection 层（其消费先于 delegate 手势层）
                    if (isSelectionActive()) break
                    delegate.onScroll(change.position.x, change.position.y)
                    change.consume()
                }
            }

            isLongPress -> {
                // 长按：文字选择起点；随后拖拽由 selection 层扩选（Main pass 同步收到事件）
                if (!suppressedTap) onLongPressAt(startPos.x, startPos.y)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == downId } ?: break
                    if (!change.pressed) {
                        change.consume()
                        // 长按后松手：本层直接弹选择菜单（不依赖 selection 层抬起事件处理，
                        // 触摸路径仍由 selection 层弹，两路不重复——selection 层对鼠标抬起跳过）
                        if (!suppressedTap && isSelectionActive()) {
                            onLongPressMenu(selectionText())
                        }
                        break
                    }
                    // 非左键（中/右键）：结束且不消费（右键菜单等不受影响）
                    if (!event.buttons.isPrimaryPressed) break
                    // 选择激活后继续消费移动（selection 层 Main pass 同步扩选，互不干扰）
                    change.consume()
                }
            }

            upChange != null -> {
                // 单击（对照 detectTapGestures onTap；suppressedTap = 点按取消选择吞掉本次点击）
                upChange.consume()
                if (!suppressedTap && !primaryLost) {
                    if (!delegate.onTap(upChange.position.x, upChange.position.y)) {
                        onClickFallback(null)
                    }
                }
            }
        }
    }
}
