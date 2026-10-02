package io.legado.treeview

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Icon
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** 缩进/参考线的层步长 (上游 iconSize 语义, 两种树共用) */
public val TreeRowIndentStep: Dp = 20.dp

/** 层级缩进/参考线的封顶层数: 超深层级不再加深, 避免窄屏上剩余文本宽度变负 */
public const val TreeRowMaxIndentLevel: Int = 12

/**
 * 行文本显示字符上限。
 *
 * 行内容来自不受控的外部数据 (HTML 正文/内联脚本、JSON 长字符串值), 一条超长文本会成为
 * LazyColumn 内不可虚拟化的巨型行: 每次滚动/重组都要全量测量与绘制该行。
 * 超限时截断并加省略号, 完整内容由行菜单复制获取。
 */
public const val TreeRowMaxTextLength: Int = 4000

/**
 * 通用树行容器 (JSON 树与宿主 HTML 树共用的唯一行实现): 缩进 + 层级参考线 +
 * 折叠图标 (静态旋转角) + 行文本 + 长按/右键行内锚定菜单。
 *
 * [icon] 为 null 且 [iconSpace] 为 true 时用图标位占位 (叶子行与折叠行对齐);
 * [menu] 为 null 时不挂菜单。
 *
 * @param indent 行缩进, 调用方按 [TreeRowIndentStep] × 层级计算, 同时是菜单锚点与参考线间距基准
 * @param guides 参考线条数 (= 该行层级深度): 每层一条竖线, 对齐该层内容起点; 0 不画
 * @param guideColor 参考线颜色
 * @param icon 折叠图标; 叶子行传 null
 * @param iconTint 图标着色
 * @param iconRotationDegrees 图标旋转角 (折叠 0 / 展开 90, 静态设置, 与上游一致不加过渡)
 * @param onClick 行主键点击; 无主键动作的行传空实现
 * @param menu 行内锚定菜单内容, 长按与右键共用; **仅在菜单展开时组合**, 重负载构造
 * (如按下钻取子树) 可安全放在其中保持惰性
 * @param iconSpace icon 为 null 时是否仍留图标占位 (默认留, 叶子行与折叠行对齐;
 * 上游原始值行不留占位, 传 false)
 * @param maxTextLength 行文本显示上限 (字符数), 超限截断并加省略号; 传 [Int.MAX_VALUE] 不限制。
 * 默认 [TreeRowMaxTextLength], 调用方无需各自设限
 */
@Composable
public fun TreeRow(
    indent: Dp,
    guides: Int,
    guideColor: Color,
    icon: ImageVector?,
    iconSize: Dp,
    iconTint: Color,
    iconRotationDegrees: Float,
    text: AnnotatedString,
    showRowIndication: Boolean,
    onClick: () -> Unit,
    menu: (@Composable ColumnScope.((() -> Unit)) -> Unit)?,
    modifier: Modifier = Modifier,
    iconSpace: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    maxTextLength: Int = TreeRowMaxTextLength,
) {
    val menuExpanded = remember { mutableStateOf(false) }
    val indication = if (showRowIndication) LocalIndication.current else null
    val displayText = remember(text, maxTextLength) {
        if (text.length > maxTextLength) {
            buildAnnotatedString {
                append(text, 0, maxTextLength)
                append("…")
            }
        } else {
            text
        }
    }
    Box(
        modifier = modifier.drawBehind {
            // 层级参考线 (DevTools guides): 每层一条, 对齐该层内容起点
            val step = TreeRowIndentStep.toPx()
            for (i in 0 until guides) {
                val x = i * step + step / 2
                drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 桌面右键开菜单; 主键长按由 combinedClickable 上报
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Press) continue
                            if (event.buttons.isSecondaryPressed) menuExpanded.value = true
                        }
                    }
                }
                .padding(start = indent)
                .combinedClickable(
                    interactionSource = null,
                    indication = indication,
                    onLongClick = { menuExpanded.value = true },
                    onClick = onClick,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    modifier = Modifier
                        .size(iconSize)
                        .graphicsLayer(rotationZ = iconRotationDegrees),
                    imageVector = icon,
                    tint = iconTint,
                    contentDescription = null,
                )
            } else if (iconSpace) {
                Spacer(Modifier.size(iconSize))
            }
            // 剩余宽度内软换行 (DevTools Word wrap 同款): 长属性/长文本折行而非横向溢出
            Text(
                text = displayText,
                style = textStyle,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (menuExpanded.value && menu != null) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menuExpanded.value = false },
                // 菜单左缘对齐行内容起点 (缩进之后); 越出窗口右缘由官方定位器内收
                offset = DpOffset(indent, 0.dp),
            ) {
                menu { menuExpanded.value = false }
            }
        }
    }
}
