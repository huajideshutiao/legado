package io.legado.app.ui.main

/*
 * 下沉自 app 端 `MainBottomBar.kt`。
 *
 * # 下沉改动
 *
 * - **包名**: 保持与 app 端原包名一致 `io.legado.app.ui.main`
 *   (app 端 MainActivity 调用方无需改 import)
 * - **资源访问**:
 *   - `stringResource(R.string.home/bookshelf/discovery/my)` → `rememberString("home" / "bookshelf" / "discovery" / "my")`
 *   - `painterResource(R.drawable.ic_bottom_*_s/e)` → `rememberPainter("ic_bottom_home_s" / "ic_bottom_home_e" / ...)`
 *   - `context.getSecondaryTextColor(isDark)` → `rememberColor("md_light_secondary" / "md_dark_primary_text")`
 * - **Android API 替换**:
 *   - `LocalContext.current` → 删除, 改用 `LocalThemeStoreProvider.current` 取主题色
 *   - `ThemeStore.accentColor` → `LocalThemeStoreProvider.current.accentColor`
 *   - `context.bottomBackground` / `context.backgroundColor` → `LocalThemeStoreProvider.current.bottomBackground` / `.backgroundColor`
 *   - `ThemeConfig.curBgImagePath` → `LocalThemeStoreProvider.current.bgImagePath`
 *   - `ColorUtils.isColorLight(int)` → `Color.luminance() >= 0.5f` (Compose Color 内置, 与 AppTheme.kt 一致)
 *   - `AppConfig.bottomBarIconSize/Height/LabelMode` → 改为函数参数 `iconSize/barHeight/labelMode`
 *     (AppConfigAccessor 接口当前未暴露这三项, 不在此下沉内扩展接口, 由 app 端调用方传入)
 *   - `LocalEInk.current` → 直接用 (shared 已有 LocalEInk, 由 AppTheme provides)
 * - **保留逻辑**: 视觉/布局/动画/手势/状态管理完全与 app 端原版一致 (宽高/边距/颜色/层级)
 *
 * # ResourceProvider key 需求清单
 *
 * ## Painter (drawable, 新增)
 * - `ic_bottom_home_s`     主页选中(实心)
 * - `ic_bottom_home_e`     主页未选(空心)
 * - `ic_bottom_books_s`    书架选中(实心)
 * - `ic_bottom_books_e`    书架未选(空心)
 * - `ic_bottom_explore_s`  发现选中(实心)
 * - `ic_bottom_explore_e`  发现未选(空心)
 * - `ic_bottom_person_s`   我的选中(实心)
 * - `ic_bottom_person_e`   我的未选(空心)
 *
 * ## String (strings.xml, 新增)
 * - `home`                 主页 (R.string.home)
 * - `bookshelf`            书架 (R.string.bookshelf, 已在 BookshelfComposablesShared 列出, 复用)
 * - `discovery`            发现 (R.string.discovery)
 * - `my`                   我的 (R.string.my)
 *
 * ## Color (colors.xml, 新增)
 * - `md_light_secondary`   浅底次文本色 #8A000000 (对应 R.color.md_light_secondary)
 * - `md_dark_primary_text` 深底主文本色 #FFFFFFFF (对应 R.color.md_dark_primary_text)
 *   (这两个颜色由 app 端 MaterialValueHelper.getSecondaryTextColor 使用,
 *    Android actual 通过 Resources.getIdentifier 动态查 id)
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
 * 复刻 BottomNavigationView 的主界面底栏(附录 H):
 * 背景=bottomBackground(有壁纸时透明)、选中 accent/未选 secondaryText、
 * 图标/标签/高度尺寸走 AppConfig 三项配置, E-Ink 白底+顶部分割线+无涟漪。
 *
 * @param tags 底栏可见 tag 列表(BottomNavTag 字符串)
 * @param selectedIndex 当前选中索引
 * @param onSelect 点击非选中项回调(传 index)
 * @param onReselect 重选当前项回调(传 tag)
 * @param iconSize 图标尺寸 dp (app 端 AppConfig.bottomBarIconSize)
 * @param barHeight 底栏高度 dp (app 端 AppConfig.bottomBarHeight)
 * @param labelMode 标签模式: 0 无/1 恒显/2 仅选中/3 自动(≤3 项恒显否则仅选中)
 */
@Composable
fun MainBottomBar(
    tags: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onReselect: (String) -> Unit,
    iconSize: Int,
    barHeight: Int,
    labelMode: Int,
) {
    val themeStore = LocalThemeStoreProvider.current
    val eInk = LocalEInk.current
    val bg = themeStore.bgImagePath
    val barColor = when {
        eInk -> Color.White
        bg.isNullOrBlank() -> themeStore.bottomBackground
        else -> Color.Transparent
    }
    // 原代码: 无壁纸用 bottomBackground 判亮度, 有壁纸用 backgroundColor
    val bgForTextCalc = if (bg.isNullOrBlank()) themeStore.bottomBackground else themeStore.backgroundColor
    // 对齐 ColorUtils.isColorLight: luminance >= 0.5 视为浅色背景
    val textIsDark = bgForTextCalc.luminance() >= 0.5f
    // 对齐 getSecondaryTextColor(isDark): isDark=true→md_light_secondary, false→md_dark_primary_text
    val itemColor = rememberColor(
        if (textIsDark) "md_light_secondary" else "md_dark_primary_text"
    )
    val accent = themeStore.accentColor
    val iconSizeDp = iconSize.dp
    val barHeightDp = barHeight.dp

    Column(Modifier.fillMaxWidth().background(barColor)) {
        if (eInk) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFCCCCCC)))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(barHeightDp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tags.forEachIndexed { index, tag ->
                val selected = index == selectedIndex
                // labelMode: 0 无标签 / 1 恒显 / 2 仅选中 / 3 自动(≤3 项恒显否则仅选中)
                val showLabel = when (labelMode) {
                    1 -> true
                    2 -> selected
                    3 -> if (tags.size <= 3) true else selected
                    else -> false
                }
                val interaction = remember { MutableInteractionSource() }
                // 原版无 ripple 溅射, 按压反馈走 Selector.setPressedColor(accentColor) 的图标/文字变色
                val pressed by interaction.collectIsPressedAsState()
                val tint = if (selected || pressed) accent else itemColor
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { if (selected) onReselect(tag) else onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = rememberPainter(tag.iconKey(selected)),
                        contentDescription = rememberString(tag.labelKey()),
                        tint = tint,
                        modifier = Modifier.size(iconSizeDp),
                    )
                    if (showLabel) {
                        Text(
                            text = rememberString(tag.labelKey()),
                            color = tint,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
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
