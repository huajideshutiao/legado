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
import io.legado.app.ui.book.info.LocalBlurCoverBgSlot
import io.legado.app.ui.book.info.SharedBlurCoverBgCoil
import io.legado.app.ui.browser.IosWebViewStub
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.IosPlatformCapabilities
import io.legado.app.ui.IosPlatformServices
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.IosAudioPlayPlatformProvider
import io.legado.app.ui.book.manga.IosMangaReaderPlatform
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.read.IosReaderPlatformProvider
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.source.SourceUiEventBridgeHost
import io.legado.app.ui.book.video.IosVideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayPlatformProviders
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
import io.legado.app.ui.root.PlatformServiceProviders
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
    // iOS 平台服务 + 4 个媒体 Provider stub (对照 Android MainActivity onActivityCreated)
    PlatformServiceProviders.register(IosPlatformServices)
    ReaderPlatformProviders.register(IosReaderPlatformProvider)
    AudioPlayPlatformProviders.register(IosAudioPlayPlatformProvider)
    MangaReaderScreenModel.Providers.register(IosMangaReaderPlatform)
    VideoPlayPlatformProviders.register(IosVideoPlayPlatformProvider)

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
        LocalWebViewSlot provides { url, modifier -> IosWebViewStub(url, modifier) },
        // 注入 Coil3 模糊封面背景到 shared 详情页路由, 覆盖 LocalBlurCoverBgSlot 兜底
        LocalBlurCoverBgSlot provides { book, coverTick, inBookshelf, isEInkMode, modifier ->
            SharedBlurCoverBgCoil(book, coverTick, inBookshelf, isEInkMode, modifier)
        },
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
