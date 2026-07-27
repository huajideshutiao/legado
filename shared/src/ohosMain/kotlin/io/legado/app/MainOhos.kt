package io.legado.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.legado.app.help.config.registerOhosProviders
import io.legado.app.ui.AppBackground
import io.legado.app.ui.OhosNavHost
import io.legado.app.ui.SourceUiEventBridgeHost
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.OhosAppConfigProvider
import io.legado.app.ui.compose.platform.OhosEventBusProvider
import io.legado.app.ui.compose.platform.OhosPreferenceStoreProvider
import io.legado.app.ui.compose.platform.OhosThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 鸿蒙端 Compose 入口 (对标 iOS `MainViewController` / desktop `Main.kt`)。
 *
 * 由 EntryAbility 通过 napi 桥接调用, 顶层提供 Surface 容器 + [OhosNavHost] 路由。
 *
 * # 启动序列
 *
 * 1. [registerOhosProviders] 注册 commonMain 业务 provider (AppFilesDir / Preference /
 *    Database / BookStorage / JsEngine / Tts / WebServer 等, 详见
 *    [io.legado.app.help.config.OhosProviderRegistry] 注册顺序约束)
 * 2. [CompositionLocalProvider] 注入 4 个鸿蒙 Compose UI Provider
 *    (ThemeStore / AppConfig / EventBus / PreferenceStore, 对照 iOS MainViewController)
 * 3. [AppTheme] 包装内容, 提供主题色 + EInk 标记 + 文本样式
 *    (sharedUiMain 各 Screen 读 LocalAppColors, 缺 AppTheme 包装会崩)
 * 4. [Surface] 容器铺满全屏, 背景色 [AppBackground], 内嵌 [OhosNavHost]
 *    用 when(currentRoute) 切换全部子路由
 */
@Composable
fun MainOhos() {
    // provider 注册 (首次组合时执行一次, 幂等; 对照 iOS MainViewController 中 registerIosProviders())
    remember { registerOhosProviders() }

    // 注入 4 个鸿蒙 Compose UI Provider (对照 iOS MainViewController / desktop Main.kt)
    val themeStoreProvider = remember { OhosThemeStoreProvider() }
    val appConfigProvider = remember { OhosAppConfigProvider() }
    val eventBusProvider = remember { OhosEventBusProvider() }
    val preferenceStoreProvider = remember { OhosPreferenceStoreProvider() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStoreProvider,
        LocalAppConfigProvider provides appConfigProvider,
        LocalEventBusProvider provides eventBusProvider,
        LocalPreferenceStoreProvider provides preferenceStoreProvider,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
                OhosNavHost()
            }
            // 书源 UI 事件桥: 订阅 SOURCE_UI_REQUEST, 承接 JS 的 showLoginDialog/
            // showSourceVariableDialog 弹窗 (对照 desktop DesktopApp 的 SourceUiEventBridgeHost)
            SourceUiEventBridgeHost()
        }
    }
}
