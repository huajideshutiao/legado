package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.root.PlatformCapabilityProviders
import kotlinx.coroutines.flow.filter

// ---- 系统栏避让: 全 app 唯一数据源 = "该栏应当占的高度" ----
// 避让量只由 getInsetsIgnoringVisibility 采样出的应有高度决定, 与系统栏当前是否可见
// 无关。对照原版: 内容避让由配置/占位 View 决定 (PageView.upStatusBar 只看
// hideStatusBar), 系统栏显隐动画不改布局。
// 若改回跟随可见性, 任何系统栏显隐 (阅读菜单呼出/手势 transient/对话框) 都会改避让量,
// 波及所有读它的页面重新测量 —— 实测菜单呼出时整条导航栈 (含被盖住的书架) 重算,
// 掉帧从 1 次涨到 6 次。

/**
 * 状态栏应有高度 px (与当前可见性无关, 隐藏/显隐动画期间取真实高度)。
 * Android 走 getInsetsIgnoringVisibility; 仅在配置变化 (横竖屏等) 时重采, 不订阅 insets 流。
 */
@Composable
expect fun rememberVisibleStatusBarHeightPx(): Int

/** 导航栏应有高度 px (同 [rememberVisibleStatusBarHeightPx] 语义) */
@Composable
expect fun rememberVisibleNavigationBarHeightPx(): Int

/**
 * 当前容器是否启用状态栏避让。
 * 默认 true；底部弹层等贴底容器可置为 false，使内容区内的顶栏不叠加额外的状态栏内边距。
 */
val LocalStatusBarPaddingEnabled = staticCompositionLocalOf { true }

/**
 * 系统栏避让 padding: 按应有高度避让, 与系统栏当前可见性无关。
 *
 * @param avoidStatusBar false = 本页状态栏沉浸 (阅读页 hideStatusBar / 多窗口)
 * @param avoidNavigationBar false = 本页导航栏沉浸 (阅读页 hideNavigationBar)
 */
@Composable
fun Modifier.systemBarFixedPadding(
    avoidStatusBar: Boolean,
    avoidNavigationBar: Boolean,
): Modifier {
    // 无条件采样: 高度内部用 remember, 不应因配置变化跳变调用点
    val statusBarEnabled = LocalStatusBarPaddingEnabled.current
    val statusHeightPx = rememberVisibleStatusBarHeightPx()
    val navigationHeightPx = rememberVisibleNavigationBarHeightPx()
    val top = if (avoidStatusBar && statusBarEnabled) statusHeightPx else 0
    val bottom = if (avoidNavigationBar) navigationHeightPx else 0
    if (top <= 0 && bottom <= 0) return this
    val density = LocalDensity.current
    return this.padding(
        top = with(density) { top.toDp() },
        bottom = with(density) { bottom.toDp() },
    )
}

/** 仅状态栏避让 (顶栏/浮层顶栏)。 */
@Composable
fun Modifier.platformStatusBarPadding(): Modifier =
    systemBarFixedPadding(avoidStatusBar = true, avoidNavigationBar = false)

/** 仅导航栏避让 (底栏/浮层底栏)。 */
@Composable
fun Modifier.platformNavigationBarPadding(): Modifier =
    systemBarFixedPadding(avoidStatusBar = false, avoidNavigationBar = true)

/** 状态栏应有高度 Dp (供 Spacer 等占位使用)。 */
@Composable
fun platformStatusBarHeight(): Dp {
    if (!LocalStatusBarPaddingEnabled.current) return 0.dp
    val heightPx = rememberVisibleStatusBarHeightPx()
    if (heightPx <= 0) return 0.dp
    val density = LocalDensity.current
    return with(density) { heightPx.toDp() }
}

/** 导航栏避让量 (用于 LazyColumn contentPadding 等)。 */
@Composable
fun rememberNavigationBarPaddingValues(): PaddingValues {
    val heightPx = rememberVisibleNavigationBarHeightPx()
    if (heightPx <= 0) return PaddingValues(0.dp)
    val density = LocalDensity.current
    return PaddingValues(bottom = with(density) { heightPx.toDp() })
}

// ---- 阅读页避让 (配置驱动) ----
// 阅读页是沉浸式页面: 避让量由 hideStatusBar / hideNavigationBar 配置决定, 配置为隐藏
// 则避让 0。对照原版 PageView.upStatusBar/upNavigationBar 的占位 View isGone 判据。
// 若改回跟随可见性, 菜单呼出时正文视口高度变化 → 高度-only 去抖重排 → 按字符位置归位
// 后页首漂移, 表现为收起菜单自动翻到上一页。

/** 阅读页 hideStatusBar / hideNavigationBar 配置 (对照原版 PageView 占位 View isGone 判据) */
@Composable
private fun readerBarConfig(): Pair<Boolean, Boolean> {
    // prefs 直读无 Compose 快照, 靠 configChange 事件自增版本号驱动重组后重读
    var configTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        ReadBookEvents.configChange
            .filter { changes -> ReadConfigChange.SYSTEM_UI in changes }
            .collect { configTick++ }
    }
    // configTick 作 remember 键: prefs 直读无 Compose 快照, 靠事件自增强制重读
    // (同 PageViewComposable 的 tipRefreshTick 用法)
    return remember(configTick) {
        val cfg = ReadBookConfigProviders.getOrNull()
        // 多窗口下窗口无真全屏语义, 原版 PageView.upStatusBar 让状态栏占位恒 gone
        val inMultiWindow = PlatformCapabilityProviders.getOrNull()?.isInMultiWindow == true
        ((cfg?.hideStatusBar == true) || inMultiWindow) to (cfg?.hideNavigationBar == true)
    }
}

/** 阅读页系统栏避让 padding (配置驱动)。 */
@Composable
fun Modifier.readerSystemBarPadding(): Modifier {
    val (hideStatus, hideNav) = readerBarConfig()
    return systemBarFixedPadding(
        avoidStatusBar = !hideStatus,
        avoidNavigationBar = !hideNav,
    )
}

/**
 * 阅读页系统栏避让高度 (配置驱动, 供命中/选择坐标基准使用): 与 [readerSystemBarPadding]
 * 同判据同高度, 保证正文区顶边与命中基准同源。
 */
@Composable
fun readerSystemBarInsetsPx(): Pair<Int, Int> {
    val (hideStatus, hideNav) = readerBarConfig()
    val statusHeightPx = rememberVisibleStatusBarHeightPx()
    val navigationHeightPx = rememberVisibleNavigationBarHeightPx()
    return (if (hideStatus) 0 else statusHeightPx) to
        (if (hideNav) 0 else navigationHeightPx)
}
