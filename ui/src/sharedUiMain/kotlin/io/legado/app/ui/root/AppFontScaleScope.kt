package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.fontScaleFromLevel
import io.legado.app.ui.compose.platform.LocalEventBusProvider

/**
 * 应用级字体缩放作用域: 把"界面设置 → 字体大小"的档位接到 `LocalDensity` 的 fontScale。
 *
 * 只包桌面/iOS/鸿蒙三端入口。安卓端 `AppContextWrapper.wrap` 在 attachBaseContext 就把档位
 * 写进 `Configuration.fontScale`, `LocalDensity` 天然带着它, 再覆写一次是恒等操作。
 *
 * 语义与安卓逐字对齐 (对照原版 `AppContextWrapper.getFontScale`): **档位有效才覆写**,
 * 未设置档位时原样保留平台自身的 fontScale —— iOS 的 fontScale 来自系统 Dynamic Type
 * (`androidx.compose.ui.uikit.density` 按 `preferredContentSizeCategory` 查表), 桌面/鸿蒙
 * 才是恒 1。拿 1f 当回退值会把 iOS 用户的系统大字档静默清零。
 *
 * 只缩放 `sp` (含阅读页 `config.textSize.sp.toPx()`), `dp` 布局尺寸不动。
 * 档位变更经 recreateEvent 重读 —— [io.legado.app.ui.route.ThemeConfigRoute] 改完 pref
 * 就 `emitRecreate()`, 对照原版 `onSharedPreferenceChanged -> upPreferenceSummary + recreateActivities`。
 *
 * 挂在三端入口的 `AppTheme` 外层: 挂在 LegadoApp 之外的对话框宿主 (桌面 DesktopDialogHost、
 * iOS/鸿蒙的 SourceUiEventBridgeHost 等) 同样要吃到缩放。
 */
@Composable
fun AppFontScaleScope(content: @Composable () -> Unit) {
    val eventBus = LocalEventBusProvider.current
    var recreateTick by remember(eventBus) { mutableIntStateOf(0) }
    LaunchedEffect(eventBus) {
        eventBus.recreateEvent.collect { recreateTick++ }
    }
    val override = remember(recreateTick) { prefFontScaleOverride() }
    val density = LocalDensity.current
    if (override == null || override == density.fontScale) {
        content()
    } else {
        CompositionLocalProvider(
            LocalDensity provides Density(density = density.density, fontScale = override),
            content = content,
        )
    }
}

/**
 * "字体大小"条目选出的档位，未设置/越界返回 null = 不覆写。
 * 换算和合法范围由 commonMain [fontScaleFromLevel] 统一维护；本端仅保留平台回退策略。
 */
private fun prefFontScaleOverride(): Float? =
    fontScaleFromLevel(PreferenceProviders.get().getInt(PreferKey.fontScale))
