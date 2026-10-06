package io.legado.app.ui.book.read

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.book.read.page.overlay.DefaultHighlightColor
import io.legado.app.ui.book.read.page.overlay.HighlightStyleButton
import io.legado.app.ui.book.read.page.overlay.HighlightLineStyleRow
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppPopup
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.platform.OverlayInsetGap
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.delete
import legado.ui.generated.resources.highlight_colored
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.sure_del
import legado.ui.generated.resources.underline_note_add
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 批注气泡状态快照。
 *
 * @param bookmark 命中的批注实体 (type=1); 批注内容即 [Bookmark.content], 不渲染原文
 * @param anchor 气泡锚点矩形 (全窗坐标; 轻点命中 = 命中批注的首个投影色块矩形,
 *   长按菜单创建 = 选区起点方区, 与浮动菜单同源)
 * @param startEditing true = 弹出即进入批注输入态 (长按菜单点"批注"创建后自动弹出)
 */
data class UnderlineBubbleState(
    val bookmark: Bookmark,
    val anchor: Rect,
    val startEditing: Boolean = false,
)

/**
 * 批注气泡宿主: 挂在阅读路由组合根, state 为 null 时零组合。
 *
 * 拍板形态 (用户明确要求回避正文原文摘要):
 * - 内容区只显示批注 ([Bookmark.content]); 为空显示"添加批注"空态, 点击内容区即原地
 *   进入输入 (IME 完成键提交, 不弹对话框), 不展示原文;
 * - 操作区 = 上色开关 + 选色 (取色盘, color 可空) + 线型 (与回显同源 HighlightLineStyle)
 *   + 删除;
 * - 锚定命中批注的投影色块矩形, 优先锚下方弹出, 下方放不下翻转上方, 出屏按边距 clamp;
 * - 轻量模态: 气泡存在期间点击任意空白即关闭 (捕获层), 操作完成由调用方关气泡。
 */
@Composable
fun UnderlineNoteBubbleHost(
    state: UnderlineBubbleState?,
    onDismiss: () -> Unit,
    onNoteConfirm: (String) -> Unit,
    onColorChange: (Int?) -> Unit,
    onLineStyleChange: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    state ?: return
    var editing by remember(state) { mutableStateOf(state.startEditing) }
    var showDeleteConfirm by remember(state) { mutableStateOf(false) }

    val density = LocalDensity.current
    val marginPx = with(density) { 12.dp.roundToPx() }
    val gapPx = with(density) { 12.dp.roundToPx() }
    val topInset = LocalOverlayTopInset.current
    val topBoundPx = max(marginPx, with(density) { (topInset + OverlayInsetGap).roundToPx() })
    val windowSize = LocalWindowInfo.current.containerSize
    // 气泡尺寸首帧测量为 0, 先隐藏再落位, 避免贴屏边气泡闪现在出屏位置
    var cardSize by remember(state) { mutableStateOf(IntSize.Zero) }
    val offset = remember(state.anchor, cardSize, windowSize, marginPx, gapPx, topBoundPx) {
        calculateBubbleOffset(state.anchor, cardSize, windowSize, marginPx, gapPx, topBoundPx)
    }

    AppPopup(alignment = Alignment.TopStart) {
        // 空白捕获层: 气泡存在期间点击任意空白即关闭 (卡片上的点击先被子项消费,
        // 与 AppTextMenuHost 的主传递口径一致)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(state) { detectTapGestures { onDismiss() } },
        ) {
            UnderlineBubbleCard(
                state = state,
                editing = editing,
                onStartEdit = { editing = true },
                onNoteConfirm = onNoteConfirm,
                onColorChange = onColorChange,
                onLineStyleChange = onLineStyleChange,
                onDelete = { showDeleteConfirm = true },
                modifier = Modifier
                    .offset { offset }
                    .onSizeChanged { cardSize = it }
                    .alpha(if (cardSize == IntSize.Zero) 0f else 1f),
            )
        }
    }

    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.sure_del),
            okButton = AlertButton(stringResource(Res.string.ok)) {
                showDeleteConfirm = false
                onDelete()
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {
                showDeleteConfirm = false
            },
        )
    }
}

/** 气泡落位: 纵向优先锚下方, 放不下且上方放得下则锚上方, 最后按边距 clamp; 水平锚左缘后 clamp */
private fun calculateBubbleOffset(
    anchor: Rect,
    cardSize: IntSize,
    windowSize: IntSize,
    marginPx: Int,
    gapPx: Int,
    topBoundPx: Int,
): IntOffset {
    if (cardSize == IntSize.Zero || windowSize == IntSize.Zero) return IntOffset.Zero
    val width = cardSize.width
    val height = cardSize.height
    var y = (anchor.bottom + gapPx).roundToInt()
    if (y + height > windowSize.height - marginPx) {
        val up = (anchor.top - gapPx - height).roundToInt()
        y = if (up >= topBoundPx) {
            up
        } else {
            (windowSize.height - marginPx - height).coerceAtLeast(topBoundPx)
        }
    }
    val x = anchor.left.roundToInt().coerceIn(
        marginPx,
        (windowSize.width - marginPx - width).coerceAtLeast(marginPx),
    )
    return IntOffset(x, y)
}

/** 气泡卡片: 内容区 (批注/空态/内联输入) + 分隔线 + 操作区 (上色/选色 + 线型 + 删除) */
@Composable
private fun UnderlineBubbleCard(
    state: UnderlineBubbleState,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onNoteConfirm: (String) -> Unit,
    onColorChange: (Int?) -> Unit,
    onLineStyleChange: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Surface(
        shape = DesignTokens.shapeDefault,
        color = colors.fillet,
        elevation = 6.dp,
        modifier = modifier
            .widthIn(max = 300.dp)
            // 卡片内点击不冒泡到宿主的空白捕获层 (文本区等非按钮区域误点不关气泡)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.padding(DesignTokens.spacingDefault)) {
            if (editing) {
                UnderlineNoteEditor(
                    initial = state.bookmark.content,
                    onConfirm = onNoteConfirm,
                )
            } else {
                val content = state.bookmark.content
                if (content.isBlank()) {
                    // 空态: 只提示可添加批注, 点击即进入内联输入, 不展示原文 (拍板要求回避原文摘要)
                    Text(
                        text = stringResource(Res.string.underline_note_add),
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onStartEdit),
                    )
                } else {
                    Text(
                        text = content,
                        color = colors.primaryText,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                            .clickable(onClick = onStartEdit),
                    )
                }
            }

            Spacer(Modifier.size(DesignTokens.spacingDefault))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(DesignTokens.strokeHairline)
                    .background(colors.secondaryText.copy(alpha = 0.3f)),
            )
            Spacer(Modifier.size(DesignTokens.spacingDefault))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 上色开关: 关 = color 存 null (不画色块, 只剩线型); 开 = 无色时取默认黄
                Text(
                    text = stringResource(Res.string.highlight_colored),
                    color = colors.primaryText,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(DesignTokens.spacingXs))
                AppSwitch(
                    checked = state.bookmark.color != null,
                    onCheckedChange = { on ->
                        onColorChange(if (on) (state.bookmark.color ?: DefaultHighlightColor) else null)
                    },
                )
                Spacer(Modifier.weight(1f))
                // 选色: 当前色圆点 (无色空心), 点击弹取色盘 (色值与回显同源)
                HighlightStyleButton(color = state.bookmark.color, onColorPicked = onColorChange)
                Text(
                    text = stringResource(Res.string.delete),
                    color = DesignTokens.arcoBlue6,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(DesignTokens.shapeDefault)
                        .clickable { onDelete() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.size(DesignTokens.spacingXs))

            // 线型: 与回显同源 HighlightLineStyle, 改动即 PATCH 落库
            HighlightLineStyleRow(
                selected = state.bookmark.lineStyle,
                onLineStyleChange = onLineStyleChange,
            )
        }
    }
}

/**
 * 批注内联输入: 纯文本形态无下划线 (与 NumberPickerDialog 同款 foundation BasicTextField
 * state 版), IME 完成键提交, 清空提交即视为清空批注。
 */
@Composable
private fun UnderlineNoteEditor(
    initial: String,
    onConfirm: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val state = remember { TextFieldState(initial) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 进入即聚焦输入框 + 弹键盘 (对照 ReviewPostScreen: etInput.requestFocus() +
    // stateAlwaysVisible 语义)
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    BasicTextField(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        lineLimits = TextFieldLineLimits.MultiLine(1, 5),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onKeyboardAction = { onConfirm(state.text.toString().trim()) },
        textStyle = TextStyle(
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = colors.primaryText,
        ),
        cursorBrush = SolidColor(colors.accent),
    )
}
