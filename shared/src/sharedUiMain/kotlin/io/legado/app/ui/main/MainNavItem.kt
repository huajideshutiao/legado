package io.legado.app.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.BottomNavTag
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.LocalEInk

/**
 * 主界面导航配色 (底栏 [MainBottomBar] 与侧栏 [MainNavRail] 共用)。
 *
 * @param bar 栏背景 (E-Ink 白 / 有壁纸透明 / 否则 bottomBackground)
 * @param item 未选中项的图标与文字色
 * @param accent 选中或按压时的图标与文字色
 */
internal data class MainNavColors(val bar: Color, val item: Color, val accent: Color)

@Composable
internal fun rememberMainNavColors(): MainNavColors {
    val themeStore = LocalThemeStoreProvider.current
    val eInk = LocalEInk.current
    val bg = themeStore.bgImagePath
    val barColor = when {
        eInk -> Color.White
        bg.isNullOrBlank() -> themeStore.bottomBackground
        else -> Color.Transparent
    }
    // 原代码: 无壁纸用 bottomBackground 判亮度, 有壁纸用 backgroundColor
    val bgForTextCalc =
        if (bg.isNullOrBlank()) themeStore.bottomBackground else themeStore.backgroundColor
    // 对齐 ColorUtils.isColorLight: luminance >= 0.5 视为浅色背景
    val textIsDark = bgForTextCalc.luminance() >= 0.5f
    // 对齐 getSecondaryTextColor(isDark): isDark=true→md_light_secondary, false→md_dark_primary_text
    val itemColor = rememberColor(
        if (textIsDark) "md_light_secondary" else "md_dark_primary_text"
    )
    return MainNavColors(barColor, itemColor, themeStore.accentColor)
}

/** labelMode: 0 无标签 / 1 恒显 / 2 仅选中 / 3 自动(≤3 项恒显否则仅选中) */
internal fun showNavLabel(labelMode: Int, selected: Boolean, tagCount: Int): Boolean =
    when (labelMode) {
        1 -> true
        2 -> selected
        3 -> tagCount <= 3 || selected
        else -> false
    }

/**
 * 单个导航项 (图标在上、标签在下), 底栏与侧栏共用同一份渲染。
 *
 * 占位方向差异全部由 [modifier] 决定: 底栏传 weight+fillMaxSize, 侧栏传 fillMaxWidth+height。
 */
@Composable
internal fun MainNavItem(
    tag: String,
    selected: Boolean,
    showLabel: Boolean,
    iconSize: Int,
    colors: MainNavColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    // 原版无 ripple 溅射, 按压反馈走 Selector.setPressedColor(accentColor) 的图标/文字变色
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (selected || pressed) colors.accent else colors.item
    val label = rememberString(tag.labelKey())
    Column(
        modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = rememberPainter(tag.iconKey(selected)),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(iconSize.dp),
        )
        if (showLabel) {
            Text(
                text = label,
                color = tint,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

// selector 不被 painterResource 支持, 按选中态直取 _s(实心)/_e(空心) vector
private fun String.iconKey(selected: Boolean) = when (this) {
    BottomNavTag.HOME -> if (selected) "ic_bottom_home_s" else "ic_bottom_home_e"
    BottomNavTag.BOOKSHELF -> if (selected) "ic_bottom_books_s" else "ic_bottom_books_e"
    BottomNavTag.DISCOVERY -> if (selected) "ic_bottom_explore_s" else "ic_bottom_explore_e"
    else -> if (selected) "ic_bottom_person_s" else "ic_bottom_person_e"
}

private fun String.labelKey() = when (this) {
    BottomNavTag.HOME -> "home"
    BottomNavTag.BOOKSHELF -> "bookshelf"
    BottomNavTag.DISCOVERY -> "discovery"
    else -> "my"
}
