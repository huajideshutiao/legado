package io.legado.desktop.ui.book.read.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.PaddingConfigController
import io.legado.app.ui.book.read.config.PaddingConfigScreen as SharedPaddingConfigScreen
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 桌面端"边距配置" Screen 入口 (包装 shared/sharedUiMain 的 [SharedPaddingConfigScreen])。
 *
 * # 职责
 *
 * - 在 [SharedPaddingConfigScreen] 之上加 [AppTitleBar] (标题"边距配置" + 返回按钮)
 * - 装配一个桌面版 [PaddingConfigController] (内部 state 持有, 写不持久化, 加 TODO)
 * - 装配 onPostConfig 回调 (no-op, 加 TODO; 桌面端 ReadBookEvents 未下沉)
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] /
 *   [SharedPaddingConfigScreen] 通过 LocalXxx 取依赖
 *
 * # 简化项 (依赖未下沉的功能用 TODO 注释 + no-op)
 *
 * - controller: app 端 thin wrapper 桥接到 ReadBookConfig (持久化到 SharedPreferences),
 *   桌面端 ReadBookConfig 未下沉, 用 [DesktopPaddingConfigController] (内部 state 持有,
 *   写不持久化, 重进页面状态丢失); 后续接入 ReadBookConfig KMP 化后改为真实持久化
 * - onPostConfig: app 端调用 ReadBookEvents.postConfig(...) 刷新阅读页渲染,
 *   桌面端 ReadBookEvents 未下沉, 暂 no-op
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun PaddingConfigScreen(onBack: () -> Unit) {
    // 桌面端 Provider 注入: 供 commonMain 的 AppTheme / SharedPaddingConfigScreen 取依赖
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
                Column(modifier = Modifier.fillMaxSize()) {
                    AppTitleBar(
                        title = rememberString("padding_config"),
                        onBack = onBack,
                    )
                    PaddingConfigContent()
                }
            }
        }
    }
}

/**
 * 装配 controller + onPostConfig, 位置传参调用 [SharedPaddingConfigScreen]。
 *
 * 与 app 端 PaddingConfigDialog 内 PaddingConfigScreen(...) 调用对齐, 差异见顶层 KDoc。
 */
@Composable
private fun PaddingConfigContent() {
    val controller = remember { DesktopPaddingConfigController() }
    val onPostConfig: (List<ReadConfigChange>) -> Unit = {
        // TODO: 桌面端 ReadBookEvents.postConfig 未下沉, 暂不刷新阅读页渲染
    }

    SharedPaddingConfigScreen(
        controller = controller,
        onPostConfig = onPostConfig,
    )
}

/**
 * 桌面版 [PaddingConfigController] no-op 实现: 内部 [mutableIntStateOf] 持有值,
 * 写后立即生效 (UI 反馈正常), 但不持久化到 SharedPreferences, 重进页面状态丢失。
 *
 * TODO: 接入 ReadBookConfig KMP 化后, 改为读写 PreferenceProviders 真实持久化,
 * 字段 key 与 app 端 ReadBookConfig 对齐 (showHeaderLine/headerPaddingTop/...)。
 */
private class DesktopPaddingConfigController : PaddingConfigController {
    override var showHeaderLine: Boolean by mutableStateOf(false)
    override var showFooterLine: Boolean by mutableStateOf(false)
    override var headerPaddingTop: Int by mutableIntStateOf(0)
    override var headerPaddingBottom: Int by mutableIntStateOf(0)
    override var headerPaddingLeft: Int by mutableIntStateOf(0)
    override var headerPaddingRight: Int by mutableIntStateOf(0)
    override var paddingTop: Int by mutableIntStateOf(0)
    override var paddingBottom: Int by mutableIntStateOf(0)
    override var paddingLeft: Int by mutableIntStateOf(0)
    override var paddingRight: Int by mutableIntStateOf(0)
    override var footerPaddingTop: Int by mutableIntStateOf(0)
    override var footerPaddingBottom: Int by mutableIntStateOf(0)
    override var footerPaddingLeft: Int by mutableIntStateOf(0)
    override var footerPaddingRight: Int by mutableIntStateOf(0)
}
