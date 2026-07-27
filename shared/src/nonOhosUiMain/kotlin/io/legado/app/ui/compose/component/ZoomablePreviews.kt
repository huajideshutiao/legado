package io.legado.app.ui.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.legado.app.ui.preview.AppPreview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [Zoomable.kt] 中 [Modifier.zoomable] 的 @Preview。
 *
 * zoomable 是 Modifier 扩展, Preview 中可渲染但手势交互受限;
 * 主要预览被缩放内容的初始视觉 (1x 状态)。
 */

@AppPreview
@Composable
fun ZoomablePreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        Box(
            Modifier
                .size(240.dp)
                .background(Color(0x33165DFF))
                .zoomable(),
            contentAlignment = Alignment.Center,
        ) {
            Text("双指缩放/平移\n双击切换 1x/2x")
        }
    }
}

@AppPreview
@Composable
fun ZoomableDarkPreview() = LegadoThemePreview(dark = true) {
    Box(Modifier.padding(16.dp)) {
        Box(
            Modifier
                .size(240.dp)
                .background(Color(0x55165DFF))
                .zoomable(),
            contentAlignment = Alignment.Center,
        ) {
            Text("深色缩放区")
        }
    }
}
