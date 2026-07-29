package io.legado.app.ui.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.platform.handleBackKey

/**
 * 四端统一应用根 Composable：整合 AppNavigator + ScreenModelStore + PlatformServices + WindowPolicy。
 *
 * 零薄壳方案: 所有路由由 shared [RouteContent] 直接渲染, 平台入口只调用 [LegadoApp],
 * 不再注入平台兜底渲染器。
 */
@Composable
fun LegadoApp(
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore = remember { ScreenModelStore() },
    capabilities: PlatformCapabilities = PlatformCapabilityProviders.get(),
    platformServices: PlatformServices? = PlatformServiceProviders.getOrNull(),
    initialRequest: LaunchRequest? = null,
    overlayContent: @Composable (AppOverlay) -> Unit = {},
) {
    CompositionLocalProvider(
        LocalAppNavigator provides navigator,
        LocalScreenModelStore provides screenModelStore,
        LocalPlatformCapabilities provides capabilities,
        LocalPlatformServices provides platformServices,
    ) {
        // 暴露 navigator 给非 Composable 代码 (Dialog/Fragment/Activity)
        SideEffect { AppNavigatorProviders.register(navigator) }

        val entries by navigator.backStack.collectAsState()
        val currentEntry = entries.lastOrNull()
        val currentRoute = currentEntry?.route

        // 应用当前路由对应的窗口策略
        val windowPolicy =
            currentRoute?.let { WindowPolicies.forRoute(it) } ?: WindowPolicies.Default
        SideEffect { applyWindowPolicy(windowPolicy) }

        // ScreenModel 生命周期与栈绑定 (清理已出栈的 ScreenModel)
        LaunchedEffect(entries) {
            screenModelStore.retain(entries)
        }
        DisposableEffect(screenModelStore) {
            onDispose { screenModelStore.clear() }
        }

        // ESC/BackSpace 返回键由 shared 统一处理 (替代三端入口 onPreviewKeyEvent 重复实现)
        Box(Modifier.fillMaxSize().handleBackKey { navigator.pop() }) {
            // SaveableStateHolder: 按 entry.id 保留 saveable state,
            // push/pop 时 Composable 树销毁重建, rememberSaveable 状态 (tab 位置/滚动位置等) 可恢复
            val saveableStateHolder = rememberSaveableStateHolder()
            currentEntry?.let { entry ->
                saveableStateHolder.SaveableStateProvider(entry.id.value) {
                    RouteContent(entry, navigator, screenModelStore)
                }
            }

            // 渲染 Overlay 栈
            val overlays by navigator.overlays.collectAsState()
            overlays.forEach { overlay ->
                overlayContent(overlay)
            }
        }

        // 处理初始启动请求
        initialRequest?.let { request ->
            LaunchedEffect(request) {
                handleLaunchRequest(request, navigator)
            }
        }
        // 消费 LaunchRequestBus: 各平台入口 (app MainActivity.onNewIntent) 投递的外部请求
        // StateFlow 保留最新值, 冷启动投递先于组合也不丢
        LaunchedEffect(Unit) {
            LaunchRequestBus.pending.collect { request ->
                request?.let {
                    handleLaunchRequest(it, navigator)
                    LaunchRequestBus.consume()
                }
            }
        }
    }
}

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> {
    error("AppNavigator is not provided")
}

val LocalScreenModelStore = staticCompositionLocalOf<ScreenModelStore> {
    error("ScreenModelStore is not provided")
}

val LocalPlatformCapabilities = staticCompositionLocalOf<PlatformCapabilities> {
    PlatformCapabilityProviders.get()
}

val LocalPlatformServices = staticCompositionLocalOf<PlatformServices?> { null }

// 窗口策略应用：当前为空实现，后续各端通过 PlatformServices.window 接入
private fun applyWindowPolicy(policy: WindowPolicy) {
    // TODO: 各端通过 PlatformServices.window 应用窗口策略
}

// 启动请求路由分发
private fun handleLaunchRequest(request: LaunchRequest, navigator: AppNavigator) {
    when (request) {
        is LaunchRequest.DeepLink -> navigator.push(AppRoute.WebView(request.url))
        is LaunchRequest.SearchBook -> navigator.push(AppRoute.Search())
        // 以下三类需 BookRef 解析，由平台层先解析再调用 navigator
        is LaunchRequest.OpenBook,
        is LaunchRequest.OpenBookInfo,
        is LaunchRequest.OpenReader -> Unit

        is LaunchRequest.OpenBookSource -> navigator.push(AppRoute.BookSourceEdit(request.sourceUrl))
        is LaunchRequest.ProcessText -> navigator.push(AppRoute.Search())
        is LaunchRequest.ImportFile -> navigator.push(AppRoute.ImportBook)
        is LaunchRequest.SourceUi -> when (request.type) {
            LaunchRequest.SourceUiType.LOGIN -> navigator.push(AppRoute.Login(request.sourceUrl))
            // 由平台层 SourceUi 处理器消费
            LaunchRequest.SourceUiType.SOURCE_VARIABLE,
            LaunchRequest.SourceUiType.VERIFICATION_CODE -> Unit
        }

        is LaunchRequest.NavigateTo -> when (request.routeName) {
            "book_source_manage" -> navigator.push(AppRoute.BookSourceManage)
            else -> Unit
        }
    }
}
