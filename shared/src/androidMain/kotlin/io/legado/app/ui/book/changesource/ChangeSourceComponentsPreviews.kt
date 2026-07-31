package io.legado.app.ui.book.changesource

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 换源界面公开组件的 @Preview:
 * [ChangeSourceTitleBar] (标题/搜索两态) 与 [ChangeSourceRefreshBar]。
 * 整屏 ChangeSourceScreen 依赖 ViewModel+DB, 无法直接 Preview。
 */

@Preview
@Composable
fun ChangeSourceTitleBarPreview() = LegadoThemePreview {
    ChangeSourceTitleBar(
        title = "换源",
        subtitle = "三体 · 刘慈欣",
        searchMode = false,
        screenKey = "",
        searching = false,
        onBack = {},
        onSearchModeChange = {},
        onScreen = {},
        onStartStop = {},
        menuContent = { _ -> },
    )
}

@Preview
@Composable
fun ChangeSourceTitleBarSearchingPreview() = LegadoThemePreview {
    ChangeSourceTitleBar(
        title = "换源",
        subtitle = "三体 · 刘慈欣",
        searchMode = false,
        screenKey = "",
        searching = true,
        onBack = {},
        onSearchModeChange = {},
        onScreen = {},
        onStartStop = {},
        menuContent = { _ -> },
    )
}

@Preview
@Composable
fun ChangeSourceTitleBarSearchModePreview() = LegadoThemePreview {
    ChangeSourceTitleBar(
        title = "换源",
        subtitle = null,
        searchMode = true,
        screenKey = "书源名",
        searching = false,
        onBack = {},
        onSearchModeChange = {},
        onScreen = {},
        onStartStop = {},
        menuContent = { _ -> },
    )
}

@Preview
@Composable
fun ChangeSourceTitleBarDarkPreview() = LegadoThemePreview(dark = true) {
    ChangeSourceTitleBar(
        title = "换源",
        subtitle = "三体 · 刘慈欣",
        searchMode = false,
        screenKey = "",
        searching = true,
        onBack = {},
        onSearchModeChange = {},
        onScreen = {},
        onStartStop = {},
        menuContent = { _ -> },
    )
}

@Preview
@Composable
fun ChangeSourceRefreshBarPreview() = LegadoThemePreview {
    Column {
        ChangeSourceRefreshBar(visible = true)
    }
}
