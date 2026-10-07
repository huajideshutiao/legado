package io.legado.app.ui.book.read

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.book.read.page.overlay.HighlightStyleRow
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppPopup
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.platform.OverlayInsetGap
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.delete
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.sure_del
import legado.ui.generated.resources.underline_note_hint
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.roundToInt

/** 气泡宽度上限; 边距/锚点间隙与浮动文本菜单同量级 */
private val BubbleMaxWidth = 300.dp
private val BubbleScreenMargin = 12.dp
private val BubbleAnchorGap = 12.dp

/** 卡片外的阴影留白: 弹层内容比卡片大一圈, 卡片阴影才不会被弹层窗口裁掉 */
private val BubbleShadowPadding = 6.dp

/**
 * 批注气泡状态快照。
 *
 * @param bookmark 命中的批注实体 (type=1); 批注内容即 [Bookmark.content], 不渲染原文
 * @param anchor 气泡锚点矩形 (全窗坐标): 轻点命中 = 命中批注的投影并集, 长按菜单创建 =
 *   选区起点方区, 与浮动菜单同源
 */
data class UnderlineBubbleState(
    val bookmark: Bookmark,
    val anchor: Rect,
)

/**
 * 批注气泡宿主: 挂在阅读路由组合根, state 为 null 时零组合。
 *
 * 形态:
 * - 内容区恒为输入框: 有批注显示 [Bookmark.content], 无内容显示占位提示; 不展示原文;
 *   弹出不自动聚焦也不弹输入法, 输入法由用户点击输入框时才起来;
 * - 操作区 = 取色圆点 + 上色开关 + 线型下拉 + 删除 (删除走 [AppAlertDialog] 二次确认);
 * - IME 完成键/桌面 Enter 提交 (桌面 Enter 提交、Shift+Enter 换行),
 *   点空白/返回键关闭时也把已输入内容落库;
 * - 换色/换线型/编辑落库后气泡保持 (改动即时在正文可见), 点空白/返回键/删除才关;
 * - 弹层可聚焦 (收按键与输入法), 点空白/返回键关闭由平台按 [PopupProperties] 处理;
 * - 落位: 纵向优先锚下方, 下方放不下且上方放得下则锚上方, 再按可用区底边 (窗口底 - 软键盘)
 *   clamp; 水平锚左缘后 clamp。
 *
 * @param state null = 不显示
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
    // 输入框状态提到宿主: 点空白/返回键关闭气泡时, 已输入内容也要落库
    val noteState = remember(state.bookmark.time) { TextFieldState(state.bookmark.content) }
    var showDeleteConfirm by remember(state.bookmark.time) { mutableStateOf(false) }
    val commitNote: () -> Unit = {
        val text = noteState.text.toString().trim()
        if (text != state.bookmark.content) onNoteConfirm(text)
    }

    // 键盘高度只在气泡存在时订阅 (键盘动画期逐帧变化): 输入态卡片底边顶在键盘上方
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val marginPx = with(density) { BubbleScreenMargin.roundToPx() }
    val gapPx = with(density) { BubbleAnchorGap.roundToPx() }
    val shadowPx = with(density) { BubbleShadowPadding.roundToPx() }
    val topInset = LocalOverlayTopInset.current
    val topBoundPx = max(marginPx, with(density) { (topInset + OverlayInsetGap).roundToPx() })
    val anchor = state.anchor

    // 关闭清理桥: 弹层是独立窗口, 收键盘/清焦点只能从弹层内容侧取管理器执行
    // (同 ReviewPostDialog 的清理桥; 输入框仍持焦时直接销毁弹层窗口会把 IME 留在下层页面)
    val cleanup = remember(state.bookmark.time) { mutableStateOf<(() -> Unit)?>(null) }
    val dismiss: () -> Unit = {
        cleanup.value?.invoke()
        commitNote()
        onDismiss()
    }

    AppPopup(
        popupPositionProvider = remember(anchor, marginPx, gapPx, shadowPx, topBoundPx, imeBottomPx) {
            UnderlineBubblePositionProvider(anchor, marginPx, gapPx, shadowPx, topBoundPx, imeBottomPx)
        },
        onDismissRequest = dismiss,
        // focusable: 弹层要收按键与输入法 —— 不给显式 properties 时默认 focusable=false,
        // Android 弹层窗口带 FLAG_NOT_FOCUSABLE, 输入框收不到按键且输入法不挂;
        // 点空白/返回键关闭由平台按下面两项处理, 不需要自建全屏捕获层
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        DisposableEffect(Unit) {
            cleanup.value = {
                keyboard?.hide()
                focusManager.clearFocus()
            }
            onDispose { cleanup.value = null }
        }

        Box(Modifier.padding(BubbleShadowPadding)) {
            UnderlineBubbleCard(
                bookmark = state.bookmark,
                noteState = noteState,
                onNoteConfirm = commitNote,
                onColorChange = onColorChange,
                onLineStyleChange = onLineStyleChange,
                onDelete = { showDeleteConfirm = true },
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

/**
 * 气泡落位: 纵向优先锚下方, 下方放不下且上方放得下则锚上方, 都放不下则贴可用区底边;
 * 可用区下界 = 窗口底 - 边距 - 软键盘高度 (输入态卡片底边顶在键盘之上)。
 * 弹层内容比卡片大一圈阴影留白, 故按卡片本体算位后整体外移留白。
 */
private class UnderlineBubblePositionProvider(
    private val anchor: Rect,
    private val marginPx: Int,
    private val gapPx: Int,
    private val shadowPx: Int,
    private val topBoundPx: Int,
    private val imeBottomPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width - shadowPx * 2
        val height = popupContentSize.height - shadowPx * 2
        val bottomBoundPx = (windowSize.height - marginPx - imeBottomPx).coerceAtLeast(topBoundPx)
        var y = (anchor.bottom + gapPx).roundToInt()
        if (y + height > bottomBoundPx) {
            val upward = (anchor.top - gapPx - height).roundToInt()
            y = if (upward >= topBoundPx) {
                upward
            } else {
                (bottomBoundPx - height).coerceAtLeast(topBoundPx)
            }
        }
        val x = anchor.left.roundToInt().coerceIn(
            marginPx,
            (windowSize.width - marginPx - width).coerceAtLeast(marginPx),
        )
        return IntOffset(x - shadowPx, y - shadowPx)
    }
}

/** 气泡卡片: 批注输入框 + 分隔线 + 样式行 (色点/开关/线型下拉/删除) */
@Composable
private fun UnderlineBubbleCard(
    bookmark: Bookmark,
    noteState: TextFieldState,
    onNoteConfirm: () -> Unit,
    onColorChange: (Int?) -> Unit,
    onLineStyleChange: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    Surface(
        shape = DesignTokens.shapeDefault,
        color = colors.fillet,
        elevation = 6.dp,
        modifier = Modifier.widthIn(max = BubbleMaxWidth),
    ) {
        Column(Modifier.padding(DesignTokens.spacingDefault)) {
            UnderlineNoteEditor(state = noteState, onConfirm = onNoteConfirm)

            Spacer(Modifier.size(DesignTokens.spacingDefault))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(DesignTokens.strokeHairline)
                    .background(colors.secondaryText.copy(alpha = 0.3f)),
            )
            Spacer(Modifier.size(DesignTokens.spacingDefault))

            HighlightStyleRow(
                color = bookmark.color,
                onColorChange = onColorChange,
                lineStyle = bookmark.lineStyle,
                onLineStyleChange = onLineStyleChange,
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    AppTextButton(
                        text = stringResource(Res.string.delete),
                        color = DesignTokens.arcoBlue6,
                    ) { onDelete() }
                },
            )
        }
    }
}

/**
 * 批注输入框: 常显 (不在"文本/输入框"之间切换), 空时显示 [Res.string.underline_note_hint]
 * 占位; 纯文本形态无下划线 (与 NumberPickerDialog 同款 foundation BasicTextField state 版)。
 * 弹出不自动聚焦也不弹输入法, 输入法由用户点输入框触发; IME 完成键/桌面 Enter 提交
 * (多行输入框的 Enter 只换行, IME 完成键桌面端触发不到), 清空提交即清空批注。
 */
@Composable
private fun UnderlineNoteEditor(
    state: TextFieldState,
    onConfirm: () -> Unit,
) {
    val colors = AppTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // 输入框仍持焦时收键盘: 只 hide() 在部分机型收不起 IME
    val commit: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus()
        onConfirm()
    }
    Box(Modifier.fillMaxWidth()) {
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState())
                .onPreviewKeyEvent { event ->
                    // 桌面/硬件键盘: Enter 提交, Shift+Enter 换行
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.Enter && !event.isShiftPressed
                    ) {
                        commit()
                        true
                    } else {
                        false
                    }
                },
            lineLimits = TextFieldLineLimits.MultiLine(1, 5),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { commit() },
            textStyle = TextStyle(
                // 16sp: 与下方样式行 (Material 按钮 14sp Medium) 的字观感持平,
                // 14sp 时上面的批注文字比下面那行显小
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = colors.primaryText,
            ),
            cursorBrush = SolidColor(colors.accent),
        )
        if (state.text.isEmpty()) {
            Text(
                text = stringResource(Res.string.underline_note_hint),
                color = colors.secondaryText,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            )
        }
    }
}
