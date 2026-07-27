package io.legado.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.LocalEInk

/**
 * 桌面端左侧导航栏：竖版复刻 shared [io.legado.app.ui.main.MainBottomBar]，
 * 4 个一级入口 (HOME 主页 / BOOKSHELF 书架 / DISCOVERY 发现 / MY 我的)，
 * 顺序与 app 端 [BottomNavTag] 一致。
 *
 * # 设计
 *
 * [io.legado.app.ui.main.MainBottomBar] 本身是横向 Row (底栏样式)，桌面端改为竖向
 * [Column] 布局：宽度 = `appConfig.bottomBarHeight.dp` (底栏高度视为宽度，用户明确要求
 * "pc 侧边栏就是 app 端的底栏竖过来，包括底栏配置等都不能丢。底栏高度视为宽度")，
 * 高度 [fillMaxHeight]。
 *
 * 配色完全复用 shared [io.legado.app.ui.main.MainBottomBar] 逻辑：
 * - 背景: themeStore.bottomBackground (有壁纸时透明)
 * - 选中/按压: themeStore.accentColor
 * - 未选: rememberColor("md_light_secondary" / "md_dark_primary_text") 按 luminance 判断
 * - 图标: rememberPainter("ic_bottom_xxx_s/e") 选中实心 / 未选空心
 * - 标签: rememberString("home" / "bookshelf" / "discovery" / "my")
 *
 * E-Ink 模式: 白底 + 顶部分割线 (与 [io.legado.app.ui.main.MainBottomBar] 一致)。
 *
 * 配置全部对齐 app 端 [io.legado.app.ui.main.MainBottomBar] / MainActivity:
 * - 宽度 = [io.legado.app.help.config.AppConfigAccessor.bottomBarHeight]
 *   (底栏高度作为侧栏宽度, 用户明确要求)
 * - 图标尺寸 = [io.legado.app.help.config.AppConfigAccessor.bottomBarIconSize]
 * - 标签模式 = [io.legado.app.help.config.AppConfigAccessor.bottomBarLabelMode]
 *   (0 无 / 1 恒显 / 2 仅选中 / 3 自动 ≤3 项恒显)
 * - 入口顺序与显隐 = appConfig.bottomNavItemOrder + showHome/showDiscovery
 *   (对齐 app 端 MainActivity.computeVisibleTags: 4 项且集合一致才采用自定义顺序,
 *    否则回落默认顺序; HOME 看 showHome, DISCOVERY 看 showDiscovery, 其余始终保留;
 *    过滤后为空回落 BOOKSHELF)
 *
 * @param currentRoute 当前选中的路由
 * @param onRouteChange 路由切换回调
 */
@Composable
fun DesktopSideBar(
    currentRoute: DesktopRoute,
    onRouteChange: (DesktopRoute) -> Unit,
) {
    val themeStore = LocalThemeStoreProvider.current
    val eInk = LocalEInk.current
    // 读 app 端 AppConfig (与 MainBottomBar 共用同一份配置, 桌面端竖版复刻)
    val appConfig = AppConfigProviders.get()
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

    // 底栏高度作为侧栏宽度 (用户明确要求"底栏高度视为宽度")
    val barWidth = appConfig.bottomBarHeight.dp
    val iconSize = appConfig.bottomBarIconSize.dp
    val labelMode = appConfig.bottomBarLabelMode

    // 默认顺序 HOME, BOOKSHELF, DISCOVERY, MY (对齐 BottomNavTag + MainActivity.computeVisibleTags)
    val defaultOrder = remember {
        listOf(
            BottomNavTag.HOME to DesktopRoute.HOME,
            BottomNavTag.BOOKSHELF to DesktopRoute.BOOKSHELF,
            BottomNavTag.DISCOVERY to DesktopRoute.DISCOVERY,
            BottomNavTag.MY to DesktopRoute.MY,
        )
    }
    // 读取用户自定义顺序 (对齐 MainActivity.computeVisibleTags: 4 项且集合一致才采用)
    val savedOrder = appConfig.bottomNavItemOrder.split(",").filter { it.isNotBlank() }
    val orderedTags = savedOrder
        .takeIf { it.size == 4 && it.toSet() == defaultOrder.map { it.first }.toSet() }
        ?: defaultOrder.map { it.first }
    // 过滤显隐: HOME 看 showHome, DISCOVERY 看 showDiscovery, BOOKSHELF/MY 始终保留
    val visibleItems = orderedTags.mapNotNull { tag ->
        val pair = defaultOrder.firstOrNull { it.first == tag } ?: return@mapNotNull null
        when (tag) {
            BottomNavTag.HOME -> if (appConfig.showHome) pair else null
            BottomNavTag.DISCOVERY -> if (appConfig.showDiscovery) pair else null
            else -> pair
        }
    }.ifEmpty { listOf(BottomNavTag.BOOKSHELF to DesktopRoute.BOOKSHELF) }

    Column(
        modifier = Modifier
            .width(barWidth)
            .fillMaxHeight()
            .background(barColor),
    ) {
        if (eInk) {
            // E-Ink 顶部分割线 (与 MainBottomBar 一致)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFCCCCCC)))
        }
        visibleItems.forEach { (tag, route) ->
            val selected = route == currentRoute
            val interaction = remember { MutableInteractionSource() }
            // 原版无 ripple 溅射, 按压反馈走 Selector.setPressedColor(accentColor) 的图标/文字变色
            val pressed by interaction.collectIsPressedAsState()
            val tint = if (selected || pressed) accent else itemColor
            val iconKey = tag.iconKey(selected)
            val labelKey = tag.labelKey()
            // labelMode: 0 无标签 / 1 恒显 / 2 仅选中 / 3 自动(≤3 项恒显否则仅选中)
            // (对齐 MainBottomBar.labelMode 分支)
            val showLabel = when (labelMode) {
                1 -> true
                2 -> selected
                3 -> if (visibleItems.size <= 3) true else selected
                else -> false
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { onRouteChange(route) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = rememberPainter(iconKey),
                    contentDescription = rememberString(labelKey),
                    tint = tint,
                    modifier = Modifier.size(iconSize),
                )
                if (showLabel) {
                    Text(
                        text = rememberString(labelKey),
                        color = tint,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// selector 不被 painterResource 支持, 按选中态直取 _s(实心)/_e(空心) vector
// (与 MainBottomBar.iconKey 一致, 复用相同图标 key 映射)
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
