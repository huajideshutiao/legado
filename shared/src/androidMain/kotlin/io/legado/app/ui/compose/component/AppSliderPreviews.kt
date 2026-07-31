package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [AppSlider.kt] 中各 Composable 的 @Preview。
 * - [AppSlider]: 自绘 SeekBar
 * - [AppDetailSeekBar]: 标题 + 减 + 滑条 + 加 + 值
 */

@Preview
@Composable
fun AppSliderPreview() = LegadoThemePreview {
    var value by remember { mutableIntStateOf(30) }
    Box(Modifier.padding(16.dp)) {
        AppSlider(
            value = value,
            max = 100,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview
@Composable
fun AppSliderDisabledPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppSlider(
            value = 50,
            max = 100,
            onValueChange = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview
@Composable
fun AppDetailSeekBarPreview() = LegadoThemePreview {
    var value by remember { mutableIntStateOf(18) }
    Box(Modifier.padding(16.dp)) {
        AppDetailSeekBar(
            title = "字号",
            value = value,
            min = 12,
            max = 36,
            onChanged = { value = it },
            valueFormat = { "${it}sp" },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview
@Composable
fun AppDetailSeekBarDarkPreview() = LegadoThemePreview(dark = true) {
    var value by remember { mutableIntStateOf(15) }
    Box(Modifier.padding(16.dp)) {
        AppDetailSeekBar(
            title = "行距",
            value = value,
            min = 10,
            max = 20,
            onChanged = { value = it },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
