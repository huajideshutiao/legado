package io.legado.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.ComposeArkUIViewController
import io.legado.app.help.config.registerOhosProviders
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.browser.OhosWebViewSlot
import io.legado.app.ui.AppBackground
import io.legado.app.ui.OhosPlatformCapabilities
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.OhosAudioPlayPlatformProvider
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
    // 注册平台服务 (10 项能力 no-op stub, 供 shared LegadoApp 经 PlatformServiceProviders.get() 取用)
    remember { PlatformServiceProviders.register(OhosPlatformServices) }
    // 注册 4 个媒体平台 Provider stub (Reader/Audio/Manga/Video, no-op 占位)
    remember { ReaderPlatformProviders.register(OhosReaderPlatformProvider) }
    remember { AudioPlayPlatformProviders.register(OhosAudioPlayPlatformProvider) }
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

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStoreProvider,
        LocalAppConfigProvider provides appConfigProvider,
        LocalEventBusProvider provides eventBusProvider,
        LocalPreferenceStoreProvider provides preferenceStoreProvider,
        LocalWebViewSlot provides { url, modifier -> OhosWebViewSlot(url, modifier) },
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
                val rootFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    runCatching { rootFocusRequester.requestFocus() }
                }
                Box(
                    Modifier
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
                    // legado:// deep link 导入宿主
                    DeepLinkImportHost()
                }
            }
            // 书源 UI 事件桥
            SourceUiEventBridgeHost()
        }
    }
}
