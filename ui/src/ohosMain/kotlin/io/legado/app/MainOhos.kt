package io.legado.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeArkUIViewController
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.help.config.registerOhosProviders
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.browser.OhosWebViewSlot
import io.legado.app.ui.OhosPlatformCapabilities
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.SharedAudioPlayPlatformProvider
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.manga.OhosMangaReaderPlatform
import io.legado.app.ui.book.read.OhosReaderPlatformProvider
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.source.SourceUiEventBridgeHost
import io.legado.app.ui.book.video.OhosVideoPlayPlatformProvider
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.initMainHandler
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_env
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_value
import kotlin.experimental.ExperimentalNativeApi

/**
 * 鸿蒙端 Compose 入口 (零薄壳: 直接调用 shared LegadoApp)。
 *
 * [MainArkUIViewController] 由 CPF 融合渲染宿主创建并接入 ArkUI RenderNode。
 */
@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("MainArkUIViewController")
fun MainArkUIViewController(env: napi_env): napi_value {
    initMainHandler(env)
    return ComposeArkUIViewController(env) {
        MainOhos()
    }
}

@Composable
fun MainOhos() {
    // provider 注册 (首次组合时执行一次, 幂等)
    remember { registerOhosProviders() }
    // 注册平台能力 (供 shared LegadoApp 经 PlatformCapabilityProviders.get() 取能力)
    remember { PlatformCapabilityProviders.register(OhosPlatformCapabilities) }
    // 注册平台服务 (11 项能力经 napi 桥接 ArkTS, 供 shared LegadoApp 经 PlatformServiceProviders.get() 取用)
    remember { PlatformServiceProviders.register(OhosPlatformServices) }
    // 注册 4 个媒体平台 Provider (Reader/Audio/Manga/Video, 均为真实实现)
    remember { ReaderPlatformProviders.register(OhosReaderPlatformProvider) }
    remember { AudioPlayPlatformProviders.register(SharedAudioPlayPlatformProvider) }
    remember { MangaReaderScreenModel.Providers.register(OhosMangaReaderPlatform) }
    remember { VideoPlayPlatformProviders.register(OhosVideoPlayPlatformProvider) }

    // 零薄壳导航: AppNavigator 替代原平台导航宿主的 20+ 并行状态字段
    val navigator = remember { AppNavigator(AppRoute.Main()) }
    val screenModelStore = remember { ScreenModelStore() }

    // 注入 3 个鸿蒙 Compose UI Provider
    val themeStoreProvider = remember { SharedThemeStoreProvider() }
    val appConfigProvider = remember { SharedAppConfigProvider() }
    val eventBusProvider = remember { SharedEventBusProvider() }

    // 阅读页注入点: 未注入时 LocalReadConfigProviders 取值即 error,
    // 阅读页与 EffectiveReplaces 路由会崩 (默认值为 error 而非兜底实现)
    val readConfigProviders = remember { ReadConfigProviders() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStoreProvider,
        LocalAppConfigProvider provides appConfigProvider,
        LocalEventBusProvider provides eventBusProvider,
        LocalReadConfigProviders provides readConfigProviders,
        LocalWebViewSlot provides { config, modifier, callbacks ->
            OhosWebViewSlot(config, modifier, callbacks)
        },
    ) {
        // 字体缩放: 把"界面设置→字体大小"档位接到 LocalDensity (安卓由 AppContextWrapper.wrap
        // 写进 Configuration.fontScale 生效, 不需要这一层)。未设档位时不覆写, 保留平台自身
        // fontScale (iOS 跟系统 Dynamic Type, 桌面/鸿蒙恒 1) —— 语义对照原版 getFontScale
        AppFontScaleScope {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = AppTheme.colors.background) {
                    // 零薄壳: shared LegadoApp 统一管理导航栈 + ScreenModel 生命周期,
                    // 所有路由由 shared RouteContent 直接渲染; 根级键盘焦点由 shared 内部处理
                    // (handleBackKey 与焦点节点同链, 无控件持焦时键盘事件仍可达)
                    LegadoApp(
                        navigator = navigator,
                        screenModelStore = screenModelStore,
                    )
                    // legado:// deep link 导入宿主
                    DeepLinkImportHost()
                }
                // 书源 UI 事件桥
                SourceUiEventBridgeHost()
                // 阅读页长按文本的自绘浮动操作菜单 (四端同一份, 见 shared ReaderTextActionMenu)
                OhosReaderPlatformProvider.TextSelectionHost()
                // 阅读页长按图片的自绘浮动操作菜单 (与文本菜单同款样式)
                ReaderImageActionMenu.Host()
                // 阅读页文本操作菜单查词宿主 (四端同一份, 见 shared ReaderDictWord)
                ReaderDictWord.Host()
            }
        }
    }
}
