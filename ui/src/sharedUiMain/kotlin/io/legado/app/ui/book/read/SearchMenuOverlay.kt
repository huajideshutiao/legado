package io.legado.app.ui.book.read

/*
 * 下沉自 app 端 `SearchMenu.kt` 的 `SearchMenuOverlay` Composable + 私有辅助。
 * 原版 `SearchMenu` 状态持有类（View 子类，依赖 Activity / ReadBook / R.string）已不存在，
 * 状态与动作由 shared 端 [SearchMenuStateImpl] 实现本接口提供（四端共用）。
 *
 * # 资源访问替换
 * - `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)` (key-based, 跨平台)
 * - `LocalContext.current.getPrimaryTextColor(isLight)` → 非沉浸式统一取动态主题文字色
 *   `AppTheme.colors.primaryText`（与阅读菜单顶/底栏同源，不再按底栏亮度反推黑白）
 *
 * # 配色同源
 *
 * 原版 ReadMenu 在纯色阅读背景（`curBgType() == 0`）下就用阅读背景色/阅读文字色
 * （archive ReadMenu.kt:57-62 + upColorConfig），而原版 SearchMenu 恒取
 * `context.bottomBackground`（archive SearchMenu.kt:38-39）—— 色差是原版自带的，
 * 米黄/羊皮纸这类阅读主题下一个跟阅读背景、一个跟应用主题。本处让搜索菜单与
 * 阅读菜单同源取色，统一通过 [rememberReadMenuPalette] 成对解析 surface 与 onSurface；
 * E-Ink 模式下成对固定白底与深色前景，避免白底浅色字。
 *
 * # 复用已下沉的 shared 组件
 * - [ReadMenuFab] / [BottomMenuItem] / [AccelerateDecelerateEasing] 均来自 shared ReadMenu.kt
 *
 * # 资源 key 需求清单（均已存在于 ResourceProvider.jvm/ios）
 * ## Painter
 * - ic_arrow_right (FAB 上下处导航, 已存在)
 * - ic_toc (结果, 已存在) / ic_auto_page_stop (退出, 已存在)
 * - ic_menu (主菜单, 已存在)
 * ## String
 * ## 硬编码中文文案（原布局硬编码，保留以不改变实现逻辑）
 * - "结果" / "退出" / searchInfo 中的 "当前章节"
 *
 * ## 有意偏离原版
 * - 原 `iv_search_content_up` / `iv_search_content_down`（信息行左侧两个箭头）已删除：
 *   原版它们与 fabLeft/fabRight 走同一对 `updateSearchResultIndex(±1)` + `navigateToSearch`，
 *   功能完全重复，且图标与 `contentDescription`（go_to_top/go_to_bottom）跟实际行为不符。
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.platform.platformNavigationBarPadding
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.LocalEInk

/**
 * SearchMenu 状态接口：暴露 shared Composable 所需的状态属性 + 动作回调。
 *
 * app 端 [SearchMenu] 类实现本接口，保留 Android 专属逻辑（ReadBook / R.string /
 * CallBack 桥接到 ReadBookActivity 等）。shared 端 [SearchMenuOverlay] 仅依赖本接口，
 * 达成 KMP 解耦；桌面端可同样实现本接口复用 [SearchMenuOverlay]。
 *
 * # 设计说明
 *
 * - 所有 `val` 属性均为只读视图（[SearchMenuStateImpl] 用 `mutableStateOf` + `override var` 实现）
 * - `fun` 为动作回调，由 [SearchMenuStateImpl] 桥接到 [ReaderScreenModel]
 *   （对照原版 `runMenuOut { callBack.openSearchActivity(...) }`，如 [clickResults]）
 * - [bottomVisibleState] 为 `MutableTransitionState`，[SearchMenuStateImpl] 写入 `targetState`
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

    // ---- 沉浸式菜单色彩（与阅读菜单同源，经 [rememberReadMenuPalette] 成对解析）----

    /** 菜单栏是否跟随阅读背景（纯色阅读背景时 true，图片背景回落主题色） */
    val immersive: Boolean

    /** 沉浸式下的背景色（阅读背景色，含 bgAlpha 透明度；E-Ink 模式由 palette 成对覆盖为白底） */
    val bgColor: Int

    /** 沉浸式下的文字/图标色（阅读文字色；E-Ink 模式由 palette 成对覆盖为黑色高对比前景，避免白底浅色字） */
    val textColor: Int

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
    // 取色与 ReadMenu.kt 完全同源: 统一使用 [rememberReadMenuPalette] 成对解析 surface 与 onSurface。
    // E-Ink 模式下成对固定白底与深色前景, 彻底消除白底浅色字。
    val palette = rememberReadMenuPalette(
        immersive = state.immersive,
        bgColor = state.bgColor,
        textColor = state.textColor,
    )
    val bg = palette.surface
    val textColor = palette.onSurface
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
                bg = bg, tint = textColor,
                modifier = Modifier.align(Alignment.CenterStart),
                iconModifier = Modifier.rotate(180f),
            ) { state.navigate(-1) }
            ReadMenuFab(
                iconKey = "ic_arrow_right",
                contentDescription = "下个结果",
                bg = bg, tint = textColor,
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
                    // E-Ink 下由 palette 强制白底, 且与 textColor (深色前景) 成对保证对比度
                    .background(bg)
                    // 浮层底栏逐帧跟随导航栏 insets (与 ReadMenu 底栏同理)
                    .platformNavigationBarPadding(),
            ) {
                // 搜索信息行(原 ll_search_base_info)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.searchInfo,
                        color = textColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
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
