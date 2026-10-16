package io.legado.app.ui.book.read

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.root.PageSystemBarAppearance
import io.legado.app.ui.root.PageSystemBarAppearanceEffect
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.flow.filter

/**
 * 阅读页系统栏外观 (对照原版 `BaseReadBookActivity.upSystemUiVisibility` 的 setLightStatusBar
 * 分支与 `ReadBookActivity.upNavigationBarColor`)。
 *
 * 原版这段逻辑挂在 upSystemUiVisibility 上, 由 onResume/onPause、菜单显隐、底部弹层增减、
 * 系统栏配置变更共同驱动; 此处按同一组事实驱动: 可见期内 (对照原版 onResume/onPause) 订阅
 * 菜单/搜索菜单/底部弹层显隐与系统栏配置事件重算 (见 [PageSystemBarAppearanceEffect])。
 *
 * 菜单布局可见性 = 阅读菜单可见 || 搜索底部菜单可见 || 底部弹层可见
 * (对照原版 `menuLayoutIsVisible = bottomDialog > 0 || readMenu.isVisible || searchMenu.bottomMenuVisible`)。
 */
@Composable
fun ReaderSystemBarAppearanceEffect(screenModel: ReaderScreenModel) {
    val themeStore = LocalThemeStoreProvider.current
    // 系统栏配置与样式配置是直读持久层的普通 getter, 无 Compose 快照:
    // 靠 SYSTEM_UI 事件自增版本号驱动重算 (同 StatusBarsPadding.readerBarConfig)
    var configTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        ReadBookEvents.configChange
            .filter { ReadConfigChange.SYSTEM_UI in it }
            .collect { configTick++ }
    }
    val dialogEvent = screenModel.dialogSlot.collectAsState().value?.event
    val bottomSheetVisible = dialogEvent != null && dialogEvent in readerBottomSheetDialogs
    val menuLayoutVisible = screenModel.menuState.isVisible ||
        (
            screenModel.searchMenuState.rootVisible &&
                screenModel.searchMenuState.bottomVisibleState.currentState
            ) ||
        bottomSheetVisible
    // 声明只在键变化时重算: 背景色与阅读样式是直读持久层的调用 (含颜色解析), 每次重组都读没意义
    val appearance = remember(menuLayoutVisible, bottomSheetVisible, themeStore, configTick) {
        val style = ReadBookConfigProviders.getOrNull()?.durConfig
        val bgColor = style?.curBgColor()
        PageSystemBarAppearance(
            lightStatusBarIcons = if (menuLayoutVisible) {
                // 原版 else 分支: 纯色背景或底部弹层打开时按阅读背景色亮度, 否则按主题状态栏色亮度
                val bg = if (style?.curBgType() == 0 || bottomSheetVisible) {
                    bgColor ?: themeStore.statusBarColor.toArgb()
                } else {
                    themeStore.statusBarColor.toArgb()
                }
                ColorUtils.isColorLight(bg)
            } else {
                style?.curStatusIconDark() ?: true
            },
            navigationBarColor = if (menuLayoutVisible) null else bgColor,
        )
    }
    PageSystemBarAppearanceEffect(
        refreshTick = configTick,
        appearance = { appearance },
    )
}

/**
 * 原版 bottomDialog 计数覆盖的底部弹层: BaseBottomDialogFragment 子类 (自动翻页面板 /
 * 背景文字设置 / 朗读面板 / 阅读样式) 与显式增减计数的更多设置弹层。
 * 背景文字设置在阅读样式弹层内叠层, 同属 [ReaderDialogEvent.ReadStyle]。
 */
private val readerBottomSheetDialogs = setOf(
    ReaderDialogEvent.AutoRead,
    ReaderDialogEvent.ReadAloud,
    ReaderDialogEvent.ReadStyle,
    ReaderDialogEvent.MoreConfig,
)
