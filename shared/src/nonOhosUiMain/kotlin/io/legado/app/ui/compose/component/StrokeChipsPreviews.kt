package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.preview.AppPreview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [StrokeChips.kt] 中各 Composable 的 @Preview。
 * - [StrokeTextChip]: 描边小按钮 (阅读页底部弹窗用)
 * - [RadioChip]: 分段单选钮 (无圆点)
 *
 * 注: 用 FlowRow 排列多 chip 以预览各种状态。FlowRow 在 compose-foundation 1.7+ 可用。
 */

@AppPreview
@Composable
fun StrokeTextChipPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        StrokeTextChip(text = "描边按钮", onClick = {})
    }
}

@AppPreview
@Composable
fun StrokeTextChipGroupPreview() = LegadoThemePreview {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    FlowRow(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StrokeTextChip(text = "chip1", onClick = {})
        StrokeTextChip(text = "chip2", onClick = {})
        StrokeTextChip(text = "chip3", onClick = {})
        StrokeTextChip(text = "长一点的 chip", onClick = {})
    }
}

@AppPreview
@Composable
fun RadioChipCheckedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        RadioChip(text = "选中", checked = true, onClick = {})
    }
}

@AppPreview
@Composable
fun RadioChipUncheckedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        RadioChip(text = "未选中", checked = false, onClick = {})
    }
}

@AppPreview
@Composable
fun RadioChipGroupPreview() = LegadoThemePreview {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioChip(text = "选项1", checked = true, onClick = {})
        RadioChip(text = "选项2", checked = false, onClick = {})
        RadioChip(text = "选项3", checked = false, onClick = {})
    }
}

@AppPreview
@Composable
fun RadioChipGroupDarkPreview() = LegadoThemePreview(dark = true) {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioChip(text = "选项1", checked = true, onClick = {})
        RadioChip(text = "选项2", checked = false, onClick = {})
    }
}
