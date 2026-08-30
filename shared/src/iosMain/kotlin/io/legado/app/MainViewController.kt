package io.legado.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import io.legado.app.help.config.NativeSystemTheme
import io.legado.app.help.config.registerIosProviders
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.model.IosReadBookProvider
import io.legado.app.model.LocalReadBookProvider
import io.legado.app.ui.book.info.LocalBlurCoverBgSlot
import io.legado.app.ui.book.info.SharedBlurCoverBgCoil
import io.legado.app.ui.browser.IosWebViewSlot
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.IosPlatformCapabilities
import io.legado.app.ui.IosPlatformServices
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.SharedAudioPlayPlatformProvider
import io.legado.app.ui.book.manga.IosMangaReaderPlatform
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.read.IosReaderPlatformProvider
import io.legado.app.ui.dict.DictDialogHost
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.source.SourceUiEventBridgeHost
import io.legado.app.ui.book.video.IosVideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayPlatformProviders
import io.legado.app.ui.association.DeepLinkImportHost
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.SharedAppConfigProvider
import io.legado.app.ui.compose.platform.SharedEventBusProvider
import io.legado.app.ui.compose.platform.SharedThemeStoreProvider
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
    AudioPlayPlatformProviders.register(SharedAudioPlayPlatformProvider)
    MangaReaderScreenModel.Providers.register(IosMangaReaderPlatform)
    VideoPlayPlatformProviders.register(IosVideoPlayPlatformProvider)

    // 2. 注入 3 个 iOS Compose UI Provider (对照 desktop Main.kt 阶段2)
    val themeStoreProvider = remember { SharedThemeStoreProvider() }
    val appConfigProvider = remember { SharedAppConfigProvider() }
    val eventBusProvider = remember { SharedEventBusProvider() }

    // 阅读页两个注入点: 未注入时 LocalReadConfigProviders/LocalReadBookProvider 取值即 error,
    // 阅读页与 EffectiveReplaces 路由会崩 (二者默认值均为 error 而非兜底实现)
    val readConfigProviders = remember { ReadConfigProviders() }
    val readBookProvider = remember { IosReadBookProvider() }

    // 零薄壳: AppNavigator + ScreenModelStore 是唯一状态源 (对照 desktop Main.kt line 346-347)
    val navigator = remember { AppNavigator(AppRoute.Main()) }
    val screenModelStore = remember { ScreenModelStore() }

    // 系统深色跟随 (themeMode="0"): 使用 Compose 标准 isSystemInDarkTheme() API,
    // 在系统深浅色切换时触发 LaunchedEffect 回写业务层 NativeSystemTheme 缓存
    val isSystemDark = isSystemInDarkTheme()
    LaunchedEffect(isSystemDark) {
        NativeSystemTheme.update(isSystemDark)
    }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStoreProvider,
        LocalAppConfigProvider provides appConfigProvider,
        LocalEventBusProvider provides eventBusProvider,
        LocalReadConfigProviders provides readConfigProviders,
        LocalReadBookProvider provides readBookProvider,
        LocalWebViewSlot provides { config, modifier, callbacks ->
            IosWebViewSlot(config, modifier, callbacks)
        },
        // 注入 Coil3 模糊封面背景到 shared 详情页路由, 覆盖 LocalBlurCoverBgSlot 兜底
        LocalBlurCoverBgSlot provides { book, coverTick, inBookshelf, isEInkMode, modifier, land ->
            SharedBlurCoverBgCoil(book, coverTick, inBookshelf, isEInkMode, modifier, land)
        },
    ) {
        AppTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                // 零薄壳: shared LegadoApp 统一管理导航栈 + ScreenModel 生命周期,
                // 所有路由由 shared RouteContent 直接渲染; 根级键盘焦点由 shared 内部处理
                // (handleBackKey 与焦点节点同链, 无控件持焦时键盘事件仍可达)
                LegadoApp(
                    navigator = navigator,
                    screenModelStore = screenModelStore,
                )
            }
            // 书源 UI 事件桥: 订阅 SOURCE_UI_REQUEST, 承接 JS 的 showLoginDialog/
            // showSourceVariableDialog 弹窗 (对照 desktop Main.kt line 391)
            SourceUiEventBridgeHost()
            // legado:// deep link 导入对话框宿主 (投递侧: iOSApp.swift onOpenURL → handleLegadoDeepLink)
            DeepLinkImportHost()
            // 阅读页文本操作菜单查词宿主 (对照 desktop TextSelectionHost 的 dictWord 分支)
            val dictWord = IosReaderPlatformProvider.dictWord
            if (dictWord != null) {
                DictDialogHost(
                    word = dictWord,
                    onDismiss = { IosReaderPlatformProvider.dictWord = null },
                )
            }
        }
    }
}
