package io.legado.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.ComposeUIViewController
import io.legado.app.help.config.registerIosProviders
import io.legado.app.ui.IosPlatformCapabilities
import io.legado.app.ui.book.source.SourceUiEventBridgeHost
import io.legado.app.ui.association.DeepLinkImportHost
import io.legado.app.ui.compose.platform.IosAppConfigProvider
import io.legado.app.ui.compose.platform.IosEventBusProvider
import io.legado.app.ui.compose.platform.IosPreferenceStoreProvider
import io.legado.app.ui.compose.platform.IosThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.LegadoApp
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.ScreenModelStore
import platform.UIKit.UIViewController

/**
 * iOS 端 Compose 入口: 用 [ComposeUIViewController] 把 shared/sharedUiMain 下沉的
 * Composable 树包装为 [UIViewController], 由 SwiftUI / UIKit 宿主直接展示。
 *
 * 零薄壳: shared [LegadoApp] 统一管理导航栈 + ScreenModel 生命周期,
 * 所有路由由 shared RouteContent 直接渲染, 不再维护并行状态字段。
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    // 1. 注册 commonMain 业务 provider + iOS 平台能力 (供 shared LegadoApp 经 PlatformCapabilityProviders.get() 取能力)
    registerIosProviders()
    PlatformCapabilityProviders.register(IosPlatformCapabilities)

    // 2. 注入 4 个 iOS Compose UI Provider (对照 desktop Main.kt line 377-385)
    val themeStoreProvider = remember { IosThemeStoreProvider() }
    val appConfigProvider = remember { IosAppConfigProvider() }
    val eventBusProvider = remember { IosEventBusProvider() }
    val preferenceStoreProvider = remember { IosPreferenceStoreProvider() }

    // 零薄壳: AppNavigator + ScreenModelStore 是唯一状态源 (对照 desktop Main.kt line 346-347)
    val navigator = remember { AppNavigator(AppRoute.Main()) }
    val screenModelStore = remember { ScreenModelStore() }

    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { rootFocusRequester.requestFocus() }
    }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStoreProvider,
        LocalAppConfigProvider provides appConfigProvider,
        LocalEventBusProvider provides eventBusProvider,
        LocalPreferenceStoreProvider provides preferenceStoreProvider,
    ) {
        AppTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                // 主区域: 接收键盘焦点, 供 shared LegadoApp 内部 handleBackKey 处理返回键
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(rootFocusRequester)
                        .focusable()
                ) {
                    // 零薄壳: shared LegadoApp 统一管理导航栈 + ScreenModel 生命周期,
                    // 所有路由由 shared RouteContent 直接渲染
                    LegadoApp(
                        navigator = navigator,
                        screenModelStore = screenModelStore,
                    )
                }
            }
            // 书源 UI 事件桥: 订阅 SOURCE_UI_REQUEST, 承接 JS 的 showLoginDialog/
            // showSourceVariableDialog 弹窗 (对照 desktop Main.kt line 391)
            SourceUiEventBridgeHost()
            // legado:// deep link 导入对话框宿主 (投递侧: iOSApp.swift onOpenURL → handleLegadoDeepLink)
            DeepLinkImportHost()
        }
    }
}
