package io.legado.app.ui.book.read

/*
 * 下沉自 app 端 `SearchMenu.kt` 的 `SearchMenuOverlay` Composable + 私有辅助。
 * app 端 `SearchMenu` 状态持有类保留（依赖 Activity / ReadBook / R.string 等
 * Android 专属 API，属 L3 不可下沉），实现 shared 端 [SearchMenuState] 接口作为薄壳。
 *
 * # 资源访问替换
 * - `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - `stringResource(R.string.xxx)` → `rememberString("xxx")` (key-based, 跨平台)
 * - `LocalContext.current.getPrimaryTextColor(isLight)` → `ColorUtils.isColorLight` 判断
 *   0xDE000000 / White（等价 md_light/dark_primary_text，与 shared SearchContentScreen 一致）
 *
 * # 复用已下沉的 shared 组件
 * - [ReadMenuFab] / [BottomMenuItem] / [AccelerateDecelerateEasing] 均来自 shared ReadMenu.kt
 *
 * # 资源 key 需求清单（均已存在于 ResourceProvider.jvm/ios）
 * ## Painter
 * - ic_arrow_right (FAB 上下处导航, 已存在)
 * - ic_arrow_drop_up / ic_arrow_drop_down (回顶/到底, 已存在)
 * - ic_toc (结果, 已存在) / ic_auto_page_stop (退出, 已存在)
 * - ic_menu (主菜单, 已存在)
 * ## String
 * - go_to_top / go_to_bottom (箭头描述, 已存在)
 * ## 硬编码中文文案（原布局硬编码，保留以不改变实现逻辑）
 * - "结果" / "退出" / searchInfo 中的 "当前章节"
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ColorUtils

/**
 * SearchMenu 状态接口：暴露 shared Composable 所需的状态属性 + 动作回调。
 *
 * app 端 [SearchMenu] 类实现本接口，保留 Android 专属逻辑（ReadBook / R.string /
 * CallBack 桥接到 ReadBookActivity 等）。shared 端 [SearchMenuOverlay] 仅依赖本接口，
 * 达成 KMP 解耦；桌面端可同样实现本接口复用 [SearchMenuOverlay]。
 *
 * # 设计说明
 *
 * - 所有 `val` 属性均为只读视图（app 端用 `mutableStateOf` + `private set` 实现）
 * - `fun` 为动作回调，由 app 端 [SearchMenu] 内部桥接到 [SearchMenu.CallBack]
 *   （如 [clickResults] → `runMenuOut { callBack.openSearchActivity(...) }`）
 * - [bottomVisibleState] 为 `MutableTransitionState`，app 端写入 `targetState`
 *   驱动出入场，shared Composable 读取 `isIdle/currentState` 做过渡簿记
 */
interface SearchMenuState {
    /** 整体可见性(原 SearchMenu 根 View 的 visible/invisible) */
    val rootVisible: Boolean

    /** 底部菜单出入场状态(原 ll_bottom_menu 动画驱动) */
    val bottomVisibleState: MutableTransitionState<Boolean>

    /** 上/下一处 FAB(原 fabLeft/fabRight，菜单收起后仍驻留) */
    val fabsVisible: Boolean

    /** 原 vw_menu_bg 可见性(随底部菜单出入场) */
    val bgVisible: Boolean

    /** 搜索信息文本(原 ll_search_base_info) */
    val searchInfo: String

    /** 原 menuBottomIn/Out.onAnimationEnd 收尾 */
    fun onTransitionIdle(shown: Boolean)

    /** 原 vw_menu_bg 点击收起 */
    fun onBgClick()

    /** 上一处(delta=-1)/下一处(delta=1) */
    fun navigate(delta: Int)

    /** "结果"按钮：收起后打开 SearchContentActivity */
    fun clickResults()

    /** "主菜单"按钮：收起后回到主菜单 */
    fun clickMainMenu()

    /** "退出"按钮：收起后退出搜索菜单 */
    fun clickExit()
}

/**
 * 搜索菜单 Overlay：底部导航条出入场(150ms/200ms，E-Ink snap)，
 * 收起后左右 FAB 驻留供结果导航，整体隐藏由 [SearchMenuState.rootVisible] 控制。
 *
 * 下沉自 app 端原 `SearchMenuOverlay(state: SearchMenu)`，将 `SearchMenu` 直接依赖
 * 拆为 [state] 接口，去除 Android `Context` / `getPrimaryTextColor` 依赖。
 * 视觉/布局/动画/手势/层级完全与 app 端原版一致(宽高/边距/颜色/层级)。
 */
@Composable
fun SearchMenuOverlay(state: SearchMenuState) {
    val vs = state.bottomVisibleState
    LaunchedEffect(vs.isIdle, vs.currentState) {
        if (vs.isIdle) state.onTransitionIdle(vs.currentState)
    }
    if (!state.rootVisible) {
        return
    }
    val eInk = LocalEInk.current
    val bg = AppTheme.colors.bottomBackground
    // 等价 app 端 getPrimaryTextColor(isColorLight(bg))：md_light/dark_primary_text
    val textColor = if (ColorUtils.isColorLight(bg.toArgb())) Color(0xDE000000) else Color.White
    val pressedBg = Color(ColorUtils.darkenColor(bg.toArgb()))
    fun spec(duration: Int): FiniteAnimationSpec<IntOffset> =
        if (eInk) snap() else tween(duration, easing = AccelerateDecelerateEasing)
    Box(Modifier.fillMaxSize()) {
        if (state.bgVisible) {
            // 原 vw_menu_bg：拦截触摸，点击收起
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { state.onBgClick() }
            )
        }
        if (state.fabsVisible) {
            ReadMenuFab(
                iconKey = "ic_arrow_right",
                contentDescription = "上个结果",
                bg = bg, pressedBg = pressedBg, tint = textColor,
                modifier = Modifier.align(Alignment.CenterStart),
                iconModifier = Modifier.rotate(180f),
            ) { state.navigate(-1) }
            ReadMenuFab(
                iconKey = "ic_arrow_right",
                contentDescription = "下个结果",
                bg = bg, pressedBg = pressedBg, tint = textColor,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) { state.navigate(1) }
        }
        AnimatedVisibility(
            visibleState = vs,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spec(150)) { it },
            exit = slideOutVertically(spec(200)) { it },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                    ),
            ) {
                // 搜索信息行(原 ll_search_base_info)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchInfoArrow(
                        iconKey = "ic_arrow_drop_up",
                        descKey = "go_to_top",
                        tint = textColor,
                    ) { state.navigate(-1) }
                    SearchInfoArrow(
                        iconKey = "ic_arrow_drop_down",
                        descKey = "go_to_bottom",
                        tint = textColor,
                    ) { state.navigate(1) }
                    Text(
                        text = state.searchInfo,
                        color = textColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                    )
                }
                // 结果/主菜单/退出(原 ll_bottom_bg)
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    BottomMenuItemText(iconKey = "ic_toc", label = "结果", tint = textColor) {
                        state.clickResults()
                    }
                    Spacer(Modifier.weight(2f))
                    BottomMenuItem("ic_menu", "main_menu", textColor) {
                        state.clickMainMenu()
                    }
                    Spacer(Modifier.weight(2f))
                    BottomMenuItemText(iconKey = "ic_auto_page_stop", label = "退出", tint = textColor) {
                        state.clickExit()
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SearchInfoArrow(
    iconKey: String,
    descKey: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(36.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = rememberString(descKey),
            tint = tint,
        )
    }
}

/** 同 [BottomMenuItem]，label 为原布局硬编码中文文案(保留以不改变实现逻辑) */
@Composable
private fun BottomMenuItemText(
    iconKey: String,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(60.dp)
            .clickable(onClick = onClick)
            .padding(top = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
