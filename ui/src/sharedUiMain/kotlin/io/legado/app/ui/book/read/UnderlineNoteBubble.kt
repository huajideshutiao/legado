package io.legado.app.ui.book.read

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.book.read.page.overlay.HighlightPalette
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppPopup
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.platform.OverlayInsetGap
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.bookmark_note
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.delete
import legado.ui.generated.resources.edit
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.sure_del
import legado.ui.generated.resources.underline_note
import legado.ui.generated.resources.underline_note_add
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 划线批注气泡状态快照。
 *
 * @param bookmark 命中的划线实体 (type=1); 批注内容即 [Bookmark.content], 不渲染原文
 * @param anchor 气泡锚点矩形 (全窗坐标 = 命中划线的首个投影色块矩形, 与回显所见同源)
 */
data class UnderlineBubbleState(
    val bookmark: Bookmark,
    val anchor: Rect,
)

/**
 * 划线批注气泡宿主: 挂在阅读路由组合根, state 为 null 时零组合。
 *
 * 拍板形态 (用户明确要求回避正文原文摘要):
 * - 内容区只显示批注 ([Bookmark.content]); 为空显示"添加批注"空态, 不展示划线原文;
 * - 操作区 = 换色 (5 档色点, 与回显同源 HighlightPalette) + 编辑批注 + 删除划线;
 * - 锚定命中划线的投影色块矩形, 优先锚下方弹出, 下方放不下翻转上方, 出屏按边距 clamp;
 * - 轻量模态: 气泡存在期间点击任意空白即关闭 (捕获层), 操作完成由调用方关气泡。
 */
@Composable
fun UnderlineNoteBubbleHost(
    state: UnderlineBubbleState?,
    onDismiss: () -> Unit,
    onEditConfirm: (String) -> Unit,
    onColorChange: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    state ?: return
    var showEdit by remember(state) { mutableStateOf(false) }
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
                onEdit = { showEdit = true },
                onColorChange = onColorChange,
                onDelete = { showDeleteConfirm = true },
                modifier = Modifier
                    .offset { offset }
                    .onSizeChanged { cardSize = it }
                    .alpha(if (cardSize == IntSize.Zero) 0f else 1f),
            )
        }
    }

    if (showEdit) {
        UnderlineNoteEditDialog(
            bookmark = state.bookmark,
            onConfirm = { content ->
                showEdit = false
                onEditConfirm(content)
            },
            onDismiss = { showEdit = false },
        )
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

/** 气泡卡片: 内容区 (批注/空态) + 分隔线 + 操作区 (5 色点 + 编辑/删除) */
@Composable
private fun UnderlineBubbleCard(
    state: UnderlineBubbleState,
    onEdit: () -> Unit,
    onColorChange: (Int) -> Unit,
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
            val content = state.bookmark.content
            if (content.isBlank()) {
                // 空态: 只提示可添加批注, 不渲染划线原文 (拍板要求回避原文摘要)
                Text(
                    text = stringResource(Res.string.underline_note_add),
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
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
                        .verticalScroll(rememberScrollState()),
                )
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 换色: 5 档色点, 选中项 accent 描边 (色值与回显同源 HighlightPalette)
                repeat(HighlightPalette.SIZE) { index ->
                    val selected = index == state.bookmark.colorIndex
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(HighlightPalette.colorOf(index))
                            .border(
                                width = if (selected) 2.dp else 0.dp,
                                color = colors.accent,
                                shape = CircleShape,
                            )
                            .clickable { onColorChange(index) },
                    )
                }
                Spacer(Modifier.weight(1f))
                AppTextButton(
                    text = stringResource(Res.string.edit),
                    color = DesignTokens.arcoBlue6,
                ) { onEdit() }
                AppTextButton(
                    text = stringResource(Res.string.delete),
                    color = DesignTokens.arcoBlue6,
                ) { onDelete() }
            }
        }
    }
}

/**
 * 批注编辑弹层: 单字段编辑 [Bookmark.content] (轻量输入层, 对齐 BookmarkDialog 形态)。
 *
 * 不复用 [io.legado.app.ui.book.bookmark.BookmarkDialog]: 那是书签编辑框, 同时编辑
 * 原文 bookText 与备注双字段——批注场景只需 content, 且原文是重锚依据, 不应经气泡修改。
 */
@Composable
private fun UnderlineNoteEditDialog(
    bookmark: Bookmark,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val titleText = stringResource(Res.string.underline_note)
    val noteLabel = stringResource(Res.string.bookmark_note)
    val cancelText = stringResource(Res.string.cancel)
    val okText = stringResource(Res.string.ok)
    var content by remember { mutableStateOf(bookmark.content) }

    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(title = titleText, onBack = onDismiss)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spacingDefault),
                ) {
                    AppUnderlineTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = noteLabel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spacingDefault),
                ) {
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = cancelText, color = colors.secondaryText) { onDismiss() }
                    AppTextButton(text = okText, color = DesignTokens.arcoBlue6) {
                        onConfirm(content.trim())
                    }
                }
            }
        }
    }
}
