package io.legado.desktop.ui.book.read.config

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.TipConfigController
import io.legado.app.ui.book.read.config.TipConfigScreen as SharedTipConfigScreen
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 桌面端"提示信息配置" Screen 入口 (包装 shared/sharedUiMain 的 [SharedTipConfigScreen])。
 *
 * # 职责
 *
 * - 用 [Surface] 包裹 [SharedTipConfigScreen], 让其 DialogTitleBar 作为页面标题栏
 *   (原为对话框正文, 桌面端无 Dialog 宿主, 升格为全屏页面, DialogTitleBar 的 onBack
 *   即页面 onBack)
 * - 装配一个桌面版 [TipConfigController] (内部 state 持有, 写不持久化, 加 TODO)
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] /
 *   [SharedTipConfigScreen] 通过 LocalXxx 取依赖
 *
 * # 简化项 (依赖未下沉的功能用 TODO 注释 + no-op)
 *
 * - controller: app 端 thin wrapper 桥接到 ReadBookConfig / ReadTipConfig
 *   (持久化到 SharedPreferences), 桌面端 ReadBookConfig / ReadTipConfig 未下沉,
 *   用 [DesktopTipConfigController] (内部 state 持有, 写不持久化, 重进页面状态丢失);
 *   后续接入 KMP 化后改为真实持久化
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun TipConfigScreen(onBack: () -> Unit) {
    // 桌面端 Provider 注入: 供 commonMain 的 AppTheme / SharedTipConfigScreen 取依赖
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                TipConfigContent(onBack = onBack)
            }
        }
    }
}

/**
 * 装配 controller + onBack + onPostConfig, 位置传参调用 [SharedTipConfigScreen]。
 *
 * 与 app 端 TipConfigDialog 内 TipConfigScreen(...) 调用对齐, 差异见顶层 KDoc。
 */
@Composable
private fun TipConfigContent(onBack: () -> Unit) {
    val controller = remember { DesktopTipConfigController() }
    val onPostConfig: (List<ReadConfigChange>) -> Unit = {
    }

    SharedTipConfigScreen(
        controller = controller,
        onBack = onBack,
        onPostConfig = onPostConfig,
    )
}

/**
 * 桌面版 [TipConfigController] no-op 实现: 内部 [mutableIntStateOf] 持有值,
 * 写后立即生效 (UI 反馈正常), 但不持久化到 SharedPreferences, 重进页面状态丢失。
 *
 * TODO: 接入 ReadBookConfig / ReadTipConfig KMP 化后, 改为读写 PreferenceProviders
 * 真实持久化, 字段 key 与 app 端对齐 (titleMode/titleSize/headerMode/...)。
 */
private class DesktopTipConfigController : TipConfigController {
    override var titleMode: Int by mutableIntStateOf(0)
    override var titleSize: Int by mutableIntStateOf(0)
    override var titleTop: Int by mutableIntStateOf(0)
    override var titleBottom: Int by mutableIntStateOf(0)
    override var headerMode: Int by mutableIntStateOf(0)
    override var footerMode: Int by mutableIntStateOf(0)
    override var tipHeaderLeft: Int by mutableIntStateOf(0)
    override var tipHeaderMiddle: Int by mutableIntStateOf(0)
    override var tipHeaderRight: Int by mutableIntStateOf(0)
    override var tipFooterLeft: Int by mutableIntStateOf(0)
    override var tipFooterMiddle: Int by mutableIntStateOf(0)
    override var tipFooterRight: Int by mutableIntStateOf(0)
    override var tipColor: Int by mutableIntStateOf(0)
    override var tipDividerColor: Int by mutableIntStateOf(0)
}
