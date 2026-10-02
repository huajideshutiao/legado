package com.sebastianneubauer.jsontree

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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * 通用树行容器 (折叠行与叶子行共用): 缩进 + 折叠图标 (带旋转动画) + 行文本 +
 * 长按/右键行内锚定菜单。本项目在 vendored 基础上提取的公开组件, 供本库的
 * JSON 行与宿主的 HTML 树行共用, 避免交互骨架重复实现。
 *
 * [icon] 为 null 且 [iconSpace] 为 true 时用图标位占位 (叶子行与折叠行对齐); [menu] 为 null 时不挂菜单。
 *
 * @param indent 行缩进 (已乘层级), 同时是菜单锚点的水平偏移
 * @param icon 折叠图标; 叶子行传 null
 * @param iconTint 图标着色
 * @param iconRotationDegrees 图标旋转角 (折叠 0 / 展开 90, 静态设置, 与上游一致不加过渡)
 * @param onClick 行主键点击; 无主键动作的行传空实现
 * @param iconSpace icon 为 null 时是否仍留图标占位 (默认留, 叶子行与折叠行对齐;
 * 上游原始值行不留占位, 传 false)
 * @param menu 行内锚定菜单内容, 长按与右键共用; **仅在菜单展开时组合**, 重负载构造
 * (如按下钻取子树) 可安全放在其中保持惰性
 */
@Composable
public fun TreeRow(
    indent: Dp,
    icon: ImageVector?,
    iconSize: Dp,
    iconTint: Color,
    iconRotationDegrees: Float,
    text: AnnotatedString,
    textStyle: TextStyle,
    showRowIndication: Boolean,
    onClick: () -> Unit,
    menu: (@Composable ColumnScope.((() -> Unit)) -> Unit)?,
    modifier: Modifier = Modifier,
    iconSpace: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val menuExpanded = remember { mutableStateOf(false) }
    val indication = if (showRowIndication) LocalIndication.current else null
    Box(modifier) {
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
            Text(
                text = text,
                style = textStyle,
                maxLines = maxLines,
                overflow = overflow,
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
