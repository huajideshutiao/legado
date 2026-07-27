package io.legado.app.ui.compose.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.legado.app.ui.compose.platform.rememberString
import kotlin.math.roundToInt

/**
 * Compose 版文本选择工具栏，复刻阅读页 [io.legado.app.ui.book.read.TextActionMenu] 视觉：
 * 动态背景色卡片/8dp 圆角/44dp 条高/无分割线/按压 0.12 alpha/E-Ink 描边降级。
 * 由 AppTheme 根注入 LocalTextToolbar，所有 SelectionContainer/TextField 自动接管。
 *
 * KMP 共享: 从 app/ui/compose/theme 下沉到 shared/commonMain。原 android.R.string.*
 * 系统字符串改用硬编码中文字面量（剪贴板通用语义，桌面/iOS/鸿蒙无 R.string 等价物）。
 */
class ComposeTextToolbar : TextToolbar {

    private var params by mutableStateOf<Params?>(null)

    override val status: TextToolbarStatus
        get() = if (params != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) {
        // 1.11 起 BasicTextField 调用 6 参数版本(含 onAutofillRequested)。
        // 显式 override 6 参数版本,避免依赖 interface default 转发链路在某些
        // Kotlin/JVM 编译配置下失效导致长按菜单不弹出。
        // onAutofillRequested 暂不暴露菜单项,直接忽略。
        params = Params(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        params = Params(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        params = null
    }

    private class Params(
        val rect: Rect,
        val onCopy: (() -> Unit)?,
        val onPaste: (() -> Unit)?,
        val onCut: (() -> Unit)?,
        val onSelectAll: (() -> Unit)?,
    )

    /** 挂在 AppTheme 内容树上：params 非空即弹出，跟随快照状态自动显隐。 */
    @Composable
    fun Host() {
        val p = params ?: return
        val eInk = LocalEInk.current
        val density = LocalDensity.current
        val gapPx = with(density) { 8.dp.roundToPx() }
        val marginPx = with(density) { 8.dp.roundToPx() }
        val positionProvider = remember(p.rect, gapPx, marginPx) {
            TextToolbarPositionProvider(p.rect, gapPx, marginPx)
        }
        // focusable=false：不抢文本框焦点/选区，显隐交由框架回调驱动
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = { params = null },
            properties = PopupProperties(focusable = false, clippingEnabled = false),
        ) {
            ToolbarCard(eInk) {
                // 顺序对齐系统：复制/剪切/粘贴/全选，仅列出回调非空项
                // 文案走 rememberString 翻译 (原 android.R.string.copy/cut/paste/selectAll)
                p.onCopy?.let { ToolbarItem(rememberString("copy"), it) }
                p.onCut?.let { ToolbarItem(rememberString("cut"), it) }
                p.onPaste?.let { ToolbarItem(rememberString("paste"), it) }
                p.onSelectAll?.let { ToolbarItem(rememberString("select_all"), it) }
            }
        }
    }
}

private val cardShape = RoundedCornerShape(8.dp)

@Composable
private fun ToolbarCard(eInk: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .then(if (eInk) Modifier else Modifier.shadow(6.dp, cardShape))
            .clip(cardShape)
            .background(AppTheme.colors.background)
            .then(if (eInk) Modifier.border(1.dp, AppTheme.colors.primaryText, cardShape) else Modifier)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun ToolbarItem(text: String, onClick: () -> Unit) {
    val color = AppTheme.colors.primaryText
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .height(44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .background(if (pressed) color.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = color, fontSize = 14.sp, maxLines = 1)
    }
}

/**
 * 复刻阅读页锚定：选区 rect（内容树根坐标）上方居中优先，放不下翻下方，左右 8dp clamp。
 * anchorBounds.topLeft 为内容根在窗口中的偏移，与 rect 坐标原点一致，相加得窗口坐标。
 */
private class TextToolbarPositionProvider(
    private val rect: Rect,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val originX = anchorBounds.left
        val originY = anchorBounds.top
        val centerX = originX + ((rect.left + rect.right) / 2f).roundToInt()
        val x = (centerX - popupContentSize.width / 2)
            .coerceIn(marginPx, (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx))
        var y = originY + rect.top.roundToInt() - popupContentSize.height - gapPx
        if (y < marginPx) {
            y = originY + rect.bottom.roundToInt() + gapPx
        }
        y = y.coerceAtMost((windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
        return IntOffset(x, y)
    }
}
