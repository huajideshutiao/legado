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
import io.legado.app.model.LocalReadBookProvider
import io.legado.app.model.ReadBookProvider
import io.legado.app.model.ReadBookShared
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.browser.OhosWebViewSlot
import io.legado.app.ui.OhosPlatformCapabilities
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.SharedAudioPlayPlatformProvider
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.manga.OhosMangaReaderPlatform
import io.legado.app.ui.book.read.OhosReaderPlatformProvider
import io.legado.app.ui.dict.DictDialogHost
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.source.SourceUiEventBridgeHost
import io.legado.app.ui.book.video.OhosVideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayPlatformProviders
import io.legado.app.ui.association.DeepLinkImportHost
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.OhosAppConfigProvider
import io.legado.app.ui.compose.platform.OhosEventBusProvider
import io.legado.app.ui.compose.platform.OhosPreferenceStoreProvider
import io.legado.app.ui.compose.platform.OhosThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
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

    // 注入 4 个鸿蒙 Compose UI Provider
    val themeStoreProvider = remember { OhosThemeStoreProvider() }
    val appConfigProvider = remember { OhosAppConfigProvider() }
    val eventBusProvider = remember { OhosEventBusProvider() }
    val preferenceStoreProvider = remember { OhosPreferenceStoreProvider() }

    // 阅读页两个注入点: 未注入时 LocalReadConfigProviders/LocalReadBookProvider 取值即 error,
    // 阅读页与 EffectiveReplaces 路由会崩 (二者默认值均为 error 而非兜底实现);
    // readBookProvider 范式同 iosMain IosReadBookProvider (直接持有 commonMain ReadBookShared)
    val readConfigProviders = remember { ReadConfigProviders(preferenceStoreProvider) }
    val readBookProvider = remember {
        object : ReadBookProvider {
            override val readBook = ReadBookShared()
        }
    }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStoreProvider,
        LocalAppConfigProvider provides appConfigProvider,
        LocalEventBusProvider provides eventBusProvider,
        LocalPreferenceStoreProvider provides preferenceStoreProvider,
        LocalReadConfigProviders provides readConfigProviders,
        LocalReadBookProvider provides readBookProvider,
        LocalWebViewSlot provides { config, modifier, callbacks ->
            OhosWebViewSlot(config, modifier, callbacks)
        },
    ) {
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
            // 阅读页文本操作菜单查词宿主 (对照 desktop TextSelectionHost 的 dictWord 分支)
            val dictWord = OhosReaderPlatformProvider.dictWord
            if (dictWord != null) {
                DictDialogHost(
                    word = dictWord,
                    onDismiss = { OhosReaderPlatformProvider.dictWord = null },
                )
            }
        }
    }
}
