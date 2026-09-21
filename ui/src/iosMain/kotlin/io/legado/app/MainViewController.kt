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
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadConfigProviders
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
import io.legado.app.ui.reader.ReaderDictWord
import io.legado.app.ui.reader.ReaderImageActionMenu
import io.legado.app.ui.root.AppFontScaleScope
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
 *
 * commonMain 业务 provider 由宿主在 didFinishLaunching 注册 (见 iosApp/iOSApp.swift 的
 * registerIosProviders), 本函数只补 UI 入口级平台 provider。
 */
fun MainViewController(): UIViewController {
    // 平台能力/服务 + 4 个媒体 Provider (对照 Android MainActivity.initializePlatform):
    // 均为 object 单例, 在组合之外注册, 系统深浅色切换重组时不重跑
    PlatformCapabilityProviders.register(IosPlatformCapabilities)
    PlatformServiceProviders.register(IosPlatformServices)
    ReaderPlatformProviders.register(IosReaderPlatformProvider)
    AudioPlayPlatformProviders.register(SharedAudioPlayPlatformProvider)
    MangaReaderScreenModel.Providers.register(IosMangaReaderPlatform)
    VideoPlayPlatformProviders.register(IosVideoPlayPlatformProvider)
    return ComposeUIViewController {
        // 注入 3 个 iOS Compose UI Provider (对照 desktop Main.kt 阶段2)
        val themeStoreProvider = remember { SharedThemeStoreProvider() }
        val appConfigProvider = remember { SharedAppConfigProvider() }
        val eventBusProvider = remember { SharedEventBusProvider() }

        // 阅读页注入点: 未注入时 LocalReadConfigProviders 取值即 error,
        // 阅读页与 EffectiveReplaces 路由会崩 (默认值为 error 而非兜底实现)
        val readConfigProviders = remember { ReadConfigProviders() }

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
            LocalWebViewSlot provides { config, modifier, callbacks ->
                IosWebViewSlot(config, modifier, callbacks)
            },
            // 注入 Coil3 模糊封面背景到 shared 详情页路由, 覆盖 LocalBlurCoverBgSlot 兜底
            LocalBlurCoverBgSlot provides { book, coverTick, inBookshelf, isEInkMode, modifier, land ->
                SharedBlurCoverBgCoil(book, coverTick, inBookshelf, isEInkMode, modifier, land)
            },
        ) {
            // 字体缩放: 把"界面设置→字体大小"档位接到 LocalDensity (安卓由 AppContextWrapper.wrap
            // 写进 Configuration.fontScale 生效, 不需要这一层)。未设档位时不覆写, 保留平台自身
            // fontScale (iOS 跟系统 Dynamic Type, 桌面/鸿蒙恒 1) —— 语义对照原版 getFontScale
            AppFontScaleScope {
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
                    // 阅读页长按文本的自绘浮动操作菜单 (四端同一份, 见 shared ReaderTextActionMenu)
                    IosReaderPlatformProvider.TextSelectionHost()
                    // 阅读页长按图片的自绘浮动操作菜单 (与文本菜单同款样式)
                    ReaderImageActionMenu.Host()
                    // 阅读页文本操作菜单查词宿主 (四端同一份, 见 shared ReaderDictWord)
                    ReaderDictWord.Host()
                }
            }
        }
    }
}
