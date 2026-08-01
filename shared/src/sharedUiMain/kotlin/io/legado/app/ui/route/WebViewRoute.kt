package io.legado.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppPattern
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelpShared
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.browser.WebViewCallbacks
import io.legado.app.ui.browser.WebViewConfig
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.coroutineContext

/**
 * WebView 共享路由入口。
 *
 * 对照原 app 端 WebViewActivity + WebViewModel (origin/quickjs):
 * - [prepareWebViewData] = 原 WebViewModel.initData: 书源 headerMap 解析 (含 `,{...}` URL 级请求头)、
 *   POST body 预拉、data: 前缀解包、非 http 前缀视为原始 HTML;
 * - 验证回传 (saveResult/refetchAfterSuccess) = 原 WebViewModel.saveVerificationResult:
 *   refetchAfterSuccess=true 走 IO 重拉页面 → setResult; false 走
 *   evaluateJavascript("document.documentElement.outerHTML") (平台侧已归一为纯文本:
 *   Android unescapeJson+去引号, iOS WKWebView 原生值) → setResult,
 *   完成后唤醒 [SourceVerificationHelpShared] 等待线程;
 * - 交互入口与原版一致: "确定"按钮 (原 menu_ok)、Cloudflare 挑战自动检测
 *   (原 onPageFinished 的 `!!window._cf_chl_opt`)、返回 (原 finish → checkResult 补空结果唤醒)。
 */
@Composable
fun WebViewRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    @Suppress("UNUSED_PARAMETER") screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.WebView
    val scope = rememberCoroutineScope()
    val callbacks = remember { WebViewCallbacks() }
    var loadState by remember { mutableStateOf<WebViewLoadState?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var cloudflareChallenge by remember { mutableStateOf(false) }
    val backStack by navigator.backStack.collectAsState()
    val isTopEntry = backStack.lastOrNull()?.id == entry.id

    // 预取加载参数 (原 WebViewModel.initData, 原版在 IO 执行)
    LaunchedEffect(route) {
        loadError = null
        loadState = null
        cloudflareChallenge = false
        runCatching { withContext(IoDispatcher) { prepareWebViewData(route) } }
            .onSuccess { loadState = it }
            .onFailure { e -> loadError = e.message ?: "error" }
    }

    // 验证完成回传 (原 WebViewModel.saveVerificationResult)
    fun saveVerificationResult() {
        if (!route.saveResult) {
            navigator.pop()
            return
        }
        val headerMap = loadState?.headerMap ?: emptyMap()
        if (route.refetchAfterSuccess || callbacks.host == null) {
            // 原 refetchAfterSuccess=true 分支; host 缺失 (desktop/鸿蒙占位) 时兜底走重拉
            scope.launch(IoDispatcher) {
                try {
                    val source = SourceHelp.getSource(route.sourceKey)
                    val body = AnalyzeUrlCore(
                        route.url,
                        headerMapF = headerMap,
                        source = source,
                        coroutineContext = coroutineContext,
                    ).getStrResponseAwait(allowWebView = false).body
                    SourceVerificationHelpShared.setResult(route.sourceKey, body ?: "")
                    withContext(Dispatchers.Main) { navigator.pop() }
                } catch (_: Exception) {
                    // 原版 execute.onError: 提示并停留页面等待重试
                }
            }
        } else {
            // host 侧已按平台归一为纯文本 (Android: unescapeJson+去引号; iOS: WKWebView 原生值)
            callbacks.host?.evaluateJavascript("document.documentElement.outerHTML") { result ->
                scope.launch(IoDispatcher) {
                    SourceVerificationHelpShared.setResult(route.sourceKey, result ?: "")
                    withContext(Dispatchers.Main) { navigator.pop() }
                }
            }
        }
    }

    // 原 WebViewActivity.onPageFinished 的 CF 挑战检测: 挑战完成即自动回传
    SideEffect {
        callbacks.onPageFinished = {
            if (route.saveResult) {
                callbacks.host?.evaluateJavascript("!!window._cf_chl_opt") { r ->
                    if (r == "true") {
                        cloudflareChallenge = true
                    } else if (cloudflareChallenge) {
                        saveVerificationResult()
                    }
                }
            }
        }
    }

    // 返回: 页面可后退则后退 (原 canGoBack && history>1 的简化), 否则出栈
    AppBackHandler(enabled = isTopEntry) {
        if (callbacks.host?.canGoBack() == true) {
            callbacks.host?.goBack()
        } else {
            navigator.pop()
        }
    }

    // 出栈对应原 finish(): 未回传结果时补空结果并唤醒等待线程 (原 checkResult)
    DisposableEffect(route) {
        onDispose {
            if (route.sourceKey.isNotEmpty()) {
                SourceVerificationHelpShared.getResult(route.sourceKey)
                    ?: SourceVerificationHelpShared.setResult(route.sourceKey, "")
                SourceVerificationHelpShared.notifyResultArrived(route.sourceKey)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val state = loadState
        val error = loadError
        when {
            error != null -> Text(
                text = error,
                color = AppTheme.colors.secondaryText,
                modifier = Modifier.align(Alignment.Center),
            )

            state == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            else -> {
                LocalWebViewSlot.current(
                    WebViewConfig(
                        url = state.url,
                        headerMap = state.headerMap,
                        html = state.html,
                        title = route.title,
                        isLogin = route.isLogin,
                        saveResult = route.saveResult,
                        refetchAfterSuccess = route.refetchAfterSuccess,
                        sourceKey = route.sourceKey,
                    ),
                    Modifier.fillMaxSize(),
                    callbacks,
                )
                if (route.saveResult) {
                    // 完成按钮 (对照原 menu_ok: 验证完成后手动回传并关闭)
                    TextButton(
                        onClick = { saveVerificationResult() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                    ) {
                        Text(stringResource(Res.string.ok))
                    }
                }
            }
        }
    }
}

/** 原 WebViewModel.initData 的结果: 实际加载 URL + 书源 headerMap + 预取 HTML。 */
private data class WebViewLoadState(
    val url: String,
    val headerMap: Map<String, String>,
    val html: String?,
)

/** 原 WebViewModel.initData 的 execute 块: 加载参数预取 (headerMap/POST/data:/原始 HTML)。 */
private suspend fun prepareWebViewData(route: AppRoute.WebView): WebViewLoadState {
    val url = route.url
    // 原版: 非登录且非 data/http 前缀时, url 直接视为原始 HTML
    if (!route.isLogin && !url.startsWith("data") && !url.startsWith("http")) {
        return WebViewLoadState(url, emptyMap(), url)
    }
    val source = SourceHelp.getSource(route.sourceKey, route.sourceType)
    val analyzeUrl = AnalyzeUrlCore(
        url,
        source = source,
        coroutineContext = coroutineContext,
    )
    val baseUrl = analyzeUrl.headerMap["Origin"] ?: analyzeUrl.url
    var html: String? = null
    if (analyzeUrl.isPost()) {
        html = analyzeUrl.getStrResponseAwait(allowWebView = false).body
    }
    if (AppPattern.dataUriRegex.matches(analyzeUrl.url)) {
        html = analyzeUrl.getByteArrayAwait().decodeToString()
    }
    return WebViewLoadState(baseUrl, analyzeUrl.headerMap, html)
}
