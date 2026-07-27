package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.preview.AppPreview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [SelectActionBar.kt] 中 [SelectActionBar] 的 @Preview。
 *
 * SelectActionBar 内部用 rememberString 取 i18n 文案, jvm Preview 端
 * 未识别 key 时返回 key 本身作 fallback, 故渲染可见 (文案为 select_all_count 等)。
 */

@AppPreview
@Composable
fun SelectActionBarNoneSelectedPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        SelectActionBar(
            selectCount = 0,
            allCount = 10,
            onSelectAll = {},
            onRevertSelection = {},
            mainActionText = "删除",
            onMainAction = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@AppPreview
@Composable
fun SelectActionBarPartialSelectedPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        SelectActionBar(
            selectCount = 3,
            allCount = 10,
            onSelectAll = {},
            onRevertSelection = {},
            mainActionText = "删除",
            onMainAction = {},
            actions = listOf(
                SelectAction("全选") {},
                SelectAction("反选") {},
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@AppPreview
@Composable
fun SelectActionBarAllSelectedPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        SelectActionBar(
            selectCount = 10,
            allCount = 10,
            onSelectAll = {},
            onRevertSelection = {},
            mainActionText = "删除",
            onMainAction = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@AppPreview
@Composable
fun SelectActionBarDarkPreview() = LegadoThemePreview(dark = true) {
    Box(Modifier.padding(8.dp)) {
        SelectActionBar(
            selectCount = 5,
            allCount = 8,
            onSelectAll = {},
            onRevertSelection = {},
            mainActionText = "删除",
            onMainAction = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
