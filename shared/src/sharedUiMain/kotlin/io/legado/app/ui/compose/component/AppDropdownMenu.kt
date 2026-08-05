package io.legado.app.ui.compose.component

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LocalContentColor
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.legado.app.ui.compose.theme.AppTheme
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.layout.Box
import androidx.compose.material.Text
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 统一容器样式的下拉菜单，复刻 View PopupMenu 的 BottomBackgroundDrawable：
 * bottomBackground 填充 + 8dp 圆角，elevation 置 0。原版 popupMenuStyle 样式链
 * （Style.PopupMenu → Widget.AppCompat.PopupMenu）未设 android:popupElevation，
 * PopupWindow 无 elevation 即无阴影，四周是平的。
 *
 * 不复用 material DropdownMenu：其 DropdownMenuContent 内部 Card 硬编码
 * MenuElevation=8dp，阴影关不掉。此处按 CMP 1.9.2 DropdownMenu 行为
 * （定位/变换原点/缩放淡入淡出/方向键焦点）自建容器，仅换掉容器的 elevation。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expandedStates = remember { MutableTransitionState(false) }
    expandedStates.targetState = expanded
    if (expandedStates.currentState || expandedStates.targetState) {
        val transformOriginState = remember { mutableStateOf(TransformOrigin.Center) }
        val density = LocalDensity.current
        val popupPositionProvider = DropdownMenuPositionProvider(
            offset,
            density,
        ) { parentBounds, menuBounds ->
            transformOriginState.value = calculateTransformOrigin(parentBounds, menuBounds)
        }
        // Popup 的 onKeyEvent 仅 skiko 有, 跨端统一用 expect 签名, 方向键焦点移到容器 modifier
        Popup(
            onDismissRequest = onDismissRequest,
            popupPositionProvider = popupPositionProvider,
            properties = PopupProperties(focusable = true),
        ) {
            val focusManager = LocalFocusManager.current
            val inputModeManager = LocalInputModeManager.current
            MenuContainer(
                expandedStates = expandedStates,
                transformOriginState = transformOriginState,
                modifier = modifier,
                onKeyEvent = { handlePopupOnKeyEvent(it, focusManager, inputModeManager) },
                content = content,
            )
        }
    }
}

/** 菜单容器：bottomBackground 填充 + 8dp 圆角 + 无 elevation（对齐 BottomBackgroundDrawable）。 */
@Composable
private fun MenuContainer(
    expandedStates: MutableTransitionState<Boolean>,
    transformOriginState: MutableState<TransformOrigin>,
    modifier: Modifier = Modifier,
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable ColumnScope.() -> Unit,
) {
    // 打开/关闭动画, 与 material DropdownMenu 一致 (缩放 + 淡入淡出)
    val transition = rememberTransition(expandedStates, "DropDownMenu")
    val scale by transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) {
                // Dismissed to expanded
                tween(durationMillis = 120, easing = LinearOutSlowInEasing)
            } else {
                // Expanded to dismissed
                tween(durationMillis = 1, delayMillis = 74)
            }
        }
    ) { if (it) 1f else 0.8f }
    val alpha by transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) {
                tween(durationMillis = 30)
            } else {
                tween(durationMillis = 75)
            }
        }
    ) { if (it) 1f else 0f }
    Surface(
        modifier = modifier
            .onKeyEvent(onKeyEvent)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = transformOriginState.value
            }
            .shadow(
                // 阴影须在 graphicsLayer 内层随动画一起淡入/缩放, 否则弹出瞬间先闪出阴影框
                // 8dp: 4dp 时阴影仅边缘露出 1~2dp, 被不透明菜单本体盖住几乎不可见
                elevation = 8.dp,
                shape = AppTheme.DesignTokens.shapeDefault,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.5f),
            ),
        shape = AppTheme.DesignTokens.shapeDefault,
        color = AppTheme.colors.bottomBackground,
        // 阴影由 Modifier.shadow 单独控制 (8dp+半透明), Surface 自身不再叠加 elevation 阴影
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .width(IntrinsicSize.Max)
                .verticalScroll(rememberScrollState()),
        ) {
            CompositionLocalProvider(LocalContentColor provides AppTheme.colors.menuText) {
                content()
            }
        }
    }
}

/**
 * 菜单与锚点的相对定位，逐行复刻 CMP 1.9.2 material Menu.kt 的
 * DropdownMenuPositionProvider：水平先贴锚点左/右，垂直依次试锚点下方/上方/居中。
 */
private data class DropdownMenuPositionProvider(
    val contentOffset: DpOffset,
    val density: Density,
    val onPositionCalculated: (IntRect, IntRect) -> Unit = { _, _ -> },
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // The min margin above and below the menu, relative to the screen.
        val verticalMargin = with(density) { 48.dp.roundToPx() }
        // The content offset specified using the dropdown offset parameter.
        val contentOffsetX =
            with(density) {
                contentOffset.x.roundToPx() *
                    (if (layoutDirection == LayoutDirection.Ltr) 1 else -1)
            }
        val contentOffsetY = with(density) { contentOffset.y.roundToPx() }

        // Compute horizontal position.
        val leftToAnchorLeft = anchorBounds.left + contentOffsetX
        val rightToAnchorRight = anchorBounds.right - popupContentSize.width + contentOffsetX
        val rightToWindowRight = windowSize.width - popupContentSize.width
        val leftToWindowLeft = 0
        val x =
            if (layoutDirection == LayoutDirection.Ltr) {
                sequenceOf(
                    leftToAnchorLeft,
                    rightToAnchorRight,
                    // If the anchor gets outside of the window on the left, we want to position
                    // toDisplayLeft for proximity to the anchor. Otherwise, toDisplayRight.
                    if (anchorBounds.left >= 0) rightToWindowRight else leftToWindowLeft,
                )
            } else {
                sequenceOf(
                    rightToAnchorRight,
                    leftToAnchorLeft,
                    // If the anchor gets outside of the window on the right, we want to
                    // position toDisplayRight for proximity to the anchor. Otherwise, toDisplayLeft.
                    if (anchorBounds.right <= windowSize.width) leftToWindowLeft
                    else rightToWindowRight,
                )
            }
                .firstOrNull { it >= 0 && it + popupContentSize.width <= windowSize.width }
                ?: rightToAnchorRight

        // Compute vertical position.
        val topToAnchorBottom = maxOf(anchorBounds.bottom + contentOffsetY, verticalMargin)
        val bottomToAnchorTop = anchorBounds.top - popupContentSize.height + contentOffsetY
        val centerToAnchorTop = anchorBounds.top - popupContentSize.height / 2 + contentOffsetY
        val bottomToWindowBottom = windowSize.height - popupContentSize.height - verticalMargin
        val y =
            sequenceOf(
                topToAnchorBottom,
                bottomToAnchorTop,
                centerToAnchorTop,
                bottomToWindowBottom,
            )
                .firstOrNull {
                    it >= verticalMargin &&
                        it + popupContentSize.height <= windowSize.height - verticalMargin
                } ?: bottomToAnchorTop

        onPositionCalculated(
            anchorBounds,
            IntRect(x, y, x + popupContentSize.width, y + popupContentSize.height),
        )
        return IntOffset(x, y)
    }
}

/** 展开动画的变换原点：贴哪条边就从哪条边缩放。复刻 material calculateTransformOrigin。 */
private fun calculateTransformOrigin(parentBounds: IntRect, menuBounds: IntRect): TransformOrigin {
    val pivotX =
        when {
            menuBounds.left >= parentBounds.right -> 0f
            menuBounds.right <= parentBounds.left -> 1f
            menuBounds.width == 0 -> 0f
            else -> {
                val intersectionCenter =
                    (max(parentBounds.left, menuBounds.left) +
                        min(parentBounds.right, menuBounds.right)) / 2
                (intersectionCenter - menuBounds.left).toFloat() / menuBounds.width
            }
        }
    val pivotY =
        when {
            menuBounds.top >= parentBounds.bottom -> 0f
            menuBounds.bottom <= parentBounds.top -> 1f
            menuBounds.height == 0 -> 0f
            else -> {
                val intersectionCenter =
                    (max(parentBounds.top, menuBounds.top) +
                        min(parentBounds.bottom, menuBounds.bottom)) / 2
                (intersectionCenter - menuBounds.top).toFloat() / menuBounds.height
            }
        }
    return TransformOrigin(pivotX, pivotY)
}

/** 方向键在菜单项间移动焦点，复刻 material handlePopupOnKeyEvent。 */
@OptIn(ExperimentalComposeUiApi::class)
private fun handlePopupOnKeyEvent(
    keyEvent: KeyEvent,
    focusManager: FocusManager?,
    inputModeManager: InputModeManager?,
): Boolean = if (keyEvent.type == KeyEventType.KeyDown) {
    when (keyEvent.key) {
        Key.DirectionDown -> {
            inputModeManager?.requestInputMode(InputMode.Keyboard)
            focusManager?.moveFocus(FocusDirection.Next)
            true
        }

        Key.DirectionUp -> {
            inputModeManager?.requestInputMode(InputMode.Keyboard)
            focusManager?.moveFocus(FocusDirection.Previous)
            true
        }

        else -> false
    }
} else {
    false
}

// ===== @Preview 合并自 androidMain 的 compose/component/SmallComponentsPreviews.kt (AppDropdownMenu) =====

// ---- AppDropdownMenu ----
// AppDropdownMenu 依赖外部 expanded 状态, expanded=false 时不显示内容;
// expanded=true 时在 IDE Preview 中可能无法正确弹出 Popup, 但可预览容器样式。

@Preview
@Composable
fun AppDropdownMenuExpandedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppDropdownMenu(
            expanded = true,
            onDismissRequest = {},
        ) {
            Text("菜单项1", modifier = Modifier.padding(16.dp))
            Text("菜单项2", modifier = Modifier.padding(16.dp))
        }
    }
}
