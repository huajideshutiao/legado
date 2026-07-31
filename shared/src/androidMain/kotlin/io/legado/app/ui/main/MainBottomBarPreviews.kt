package io.legado.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.constant.BottomNavTag
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [MainBottomBar] 的 @Preview。
 *
 * labelMode 三态 (0=常显 / 1=选中显示 / 2=不显示) 与 tag 数量 3/4 的组合;
 * iconSize/barHeight 取 AppConfig 默认值 (24dp / 50dp)。
 */

private val fourTags = listOf(
    BottomNavTag.HOME,
    BottomNavTag.BOOKSHELF,
    BottomNavTag.DISCOVERY,
    BottomNavTag.MY,
)

private val threeTags = listOf(
    BottomNavTag.BOOKSHELF,
    BottomNavTag.DISCOVERY,
    BottomNavTag.MY,
)

@Preview
@Composable
fun MainBottomBarPreview() = LegadoThemePreview {
    MainBottomBar(
        tags = fourTags,
        selectedIndex = 1,
        onSelect = {},
        onReselect = {},
        iconSize = 24,
        barHeight = 50,
        labelMode = 0,
    )
}

@Preview
@Composable
fun MainBottomBarThreeTabsPreview() = LegadoThemePreview {
    MainBottomBar(
        tags = threeTags,
        selectedIndex = 0,
        onSelect = {},
        onReselect = {},
        iconSize = 24,
        barHeight = 50,
        labelMode = 0,
    )
}

@Preview
@Composable
fun MainBottomBarLabelOnSelectedPreview() = LegadoThemePreview {
    MainBottomBar(
        tags = fourTags,
        selectedIndex = 2,
        onSelect = {},
        onReselect = {},
        iconSize = 24,
        barHeight = 50,
        labelMode = 1,
    )
}

@Preview
@Composable
fun MainBottomBarNoLabelPreview() = LegadoThemePreview {
    MainBottomBar(
        tags = fourTags,
        selectedIndex = 3,
        onSelect = {},
        onReselect = {},
        iconSize = 28,
        barHeight = 56,
        labelMode = 2,
    )
}

@Preview
@Composable
fun MainBottomBarDarkPreview() = LegadoThemePreview(dark = true) {
    MainBottomBar(
        tags = fourTags,
        selectedIndex = 1,
        onSelect = {},
        onReselect = {},
        iconSize = 24,
        barHeight = 50,
        labelMode = 0,
    )
}
