package io.legado.app.ui.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [GridPackLayout.kt] 中 [GridPackLayout] 的 @Preview。
 *
 * GridPackLayout: 占格打包布局, 模拟 GridLayout 横向自动放置。
 * Preview 用不同 colSpan/rowSpan 的子项展示布局效果。
 */

@Preview
@Composable
fun GridPackLayoutPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        GridPackLayout(
            specs = listOf(
                GridPackSpec(colSpan = 6, rowSpan = 1), // 半行宽
                GridPackSpec(colSpan = 6, rowSpan = 1), // 半行宽
                GridPackSpec(colSpan = 4, rowSpan = 1), // 1/3 宽
                GridPackSpec(colSpan = 4, rowSpan = 1),
                GridPackSpec(colSpan = 4, rowSpan = 1),
                GridPackSpec(colSpan = 12, rowSpan = 1), // 整行
            ),
            modifier = Modifier.fillMaxWidth(),
            rowUnitMinHeight = 48.dp,
        ) {
            // content lambda 被多次调用 (每个 spec 一次)
            // 此处用单一 placeholder, 实际项目里每个子项不同
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color(0x33165DFF)),
                contentAlignment = Alignment.Center,
            ) {
                Text("GridPack 子项")
            }
        }
    }
}

@Preview
@Composable
fun GridPackLayoutRowSpanPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        GridPackLayout(
            specs = listOf(
                GridPackSpec(colSpan = 4, rowSpan = 2), // 跨 2 行
                GridPackSpec(colSpan = 8, rowSpan = 1),
                GridPackSpec(colSpan = 8, rowSpan = 1),
            ),
            modifier = Modifier.fillMaxWidth(),
            rowUnitMinHeight = 60.dp,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x33165DFF)),
                contentAlignment = Alignment.Center,
            ) {
                Text("跨行子项", modifier = Modifier.padding(8.dp))
            }
        }
    }
}
