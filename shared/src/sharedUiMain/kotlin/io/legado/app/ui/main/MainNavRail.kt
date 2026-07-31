package io.legado.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.theme.LocalEInk

/** 窗口宽 ≥ 此值时导航栏由底部改到左侧 (桌面/平板横屏), 窄回底栏 */
val MainNavRailMinWindowWidth = 600.dp

/** 侧栏宽度: 容纳底栏同款 24dp 图标 + 12sp 单行标签, 与 Material NavigationRail 同档 */
private val MainNavRailWidth = 72.dp

/**
 * [MainBottomBar] 的竖排版本: 宽窗口下贴左侧显示。
 *
 * 图标/标签/配色/E-Ink 处理与底栏完全一致 (共用 [MainNavItem] + [rememberMainNavColors]),
 * 仅排列方向由横改竖、E-Ink 分割线由顶边改右边、insets 由底部改左侧。
 *
 * @param itemHeight 单项高度 dp (取底栏高度 AppConfig.bottomBarHeight, 保持项内比例一致)
 */
@Composable
fun MainNavRail(
    tags: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onReselect: (String) -> Unit,
    iconSize: Int,
    itemHeight: Int,
    labelMode: Int,
) {
    val colors = rememberMainNavColors()
    val eInk = LocalEInk.current

    Row(Modifier.fillMaxHeight().background(colors.bar)) {
        // 与底栏同序: 先 fillMax 再 insets 再定尺寸, 背景由外层铺满 inset 区
        Column(
            Modifier
                .fillMaxHeight()
                .navigationBarsPadding()
                .width(MainNavRailWidth),
        ) {
            tags.forEachIndexed { index, tag ->
                val selected = index == selectedIndex
                MainNavItem(
                    tag = tag,
                    selected = selected,
                    showLabel = showNavLabel(labelMode, selected, tags.size),
                    iconSize = iconSize,
                    colors = colors,
                    onClick = { if (selected) onReselect(tag) else onSelect(index) },
                    modifier = Modifier.fillMaxWidth().height(itemHeight.dp),
                )
            }
        }
        if (eInk) Box(Modifier.fillMaxHeight().width(1.dp).background(Color(0xFFCCCCCC)))
    }
}
