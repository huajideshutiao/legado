package io.legado.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
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
import io.legado.app.constant.AppPattern
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelpShared
import io.legado.app.help.toast.Toasters
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.browser.WebViewCallbacks
import io.legado.app.ui.browser.WebViewConfig
import io.legado.app.ui.browser.WebViewLoadingBar
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.check_host_cookie
import legado.shared.generated.resources.copy_url
import legado.shared.generated.resources.delete_source
import legado.shared.generated.resources.disable_source
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.full_screen
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.jump_to_another_app
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.open_in_browser
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.sure_del
import org.jetbrains.compose.resources.painterResource
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
 * - 交互入口与原版一致: 标题栏 (原 TitleBar: 动态网页标题 + 书源 subtitle) + 加载进度条
 *   (原 RefreshProgressBar) + 菜单动作 (原 web_view.xml: 刷新/确定/浏览器打开/拷贝 URL/全屏/
 *   禁用源/删除源)、跳转拦截 (原 WebViewUtil.shouldOverrideUrl: legado/yuedu 走内置导入,
 *   其他 scheme 确认后交系统浏览器)、Cloudflare 挑战自动检测 (原 onPageFinished 的
 *   `!!window._cf_chl_opt`)、返回 (原 finish → checkResult 补空结果唤醒)。
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
    // 页面标题 (原 WebViewActivity: intent title → onPageFinished 更新为网页 title)
    var pageTitle by remember(route) { mutableStateOf(route.title) }
    // 页面当前 URL 状态 (原 menu_copy_url / menu_open_in_browser 取 webView.url ?: baseUrl;
    // 平台在每次导航完成时经 onUrlChanged 更新, 页面内跳转后菜单取最新链接)
    var pageUrl by remember(route) { mutableStateOf<String?>(null) }
    // 加载进度 (原 RefreshProgressBar: menu_refresh 置 0, onProgressChanged 更新, 100 隐藏)
    var loadProgress by remember { mutableStateOf<Int?>(null) }
    // 原 menu_ok 的 isLogin 分支: 确认后 reload 检查 cookie, 下次加载完成即返回
    var checking by remember { mutableStateOf(false) }
    // 非 http/https scheme 跳转确认 (原 WebViewUtil.shouldOverrideUrl 的 else 分支)
    var jumpUrl by remember { mutableStateOf<String?>(null) }
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

    // 原 WebViewUtil.shouldOverrideUrl: http/https 放行 WebView 自己加载;
    // legado/yuedu → 内置导入 (原 FileAssociationFragment → shared LegadoDeepLinkHandler);
    // 其他 scheme → 弹确认后交系统浏览器, 并拦截本次加载。回调在 WebView 线程同步调用,
    // 弹窗状态经 scope.launch 切回主线程设置。
    fun interceptUrl(url: String): Boolean {
        val scheme = url.substringBefore(':').lowercase()
        return when (scheme) {
            "http", "https" -> false
            "legado", "yuedu" -> {
                LegadoDeepLinkHandler.handle(url)
                true
            }

            else -> {
                scope.launch { jumpUrl = url }
                true
            }
        }
    }

    // 视频全屏态 (HTML5 video onShowCustomView, 对照原版 WebViewActivity 的
    // binding.customWebView.size > 0): 返回键先退出视频全屏再走后续。
    // 声明在事件接线之前 (SideEffect lambda 引用)
    var videoFullScreen by remember { mutableStateOf(false) }

    // 平台 WebView 事件接线 (对照原 WebViewActivity 各回调):
    // - onPageFinished: menu_ok(isLogin) 确认后下次加载完成即返回; CF 挑战检测/验证回传
    // - onReceivedTitle: 网页 title 更新标题栏 (原 onPageFinished 读 view.title)
    // - onProgressChanged: 加载进度 → RefreshProgressBar 语义 (100 隐藏)
    // - shouldOverrideUrl: 原 WebViewUtil.shouldOverrideUrl (legado/yuedu 导入 / 其他 scheme 确认跳转)
    SideEffect {
        callbacks.onPageFinished = {
            if (checking) {
                checking = false
                navigator.pop()
            } else if (route.saveResult) {
                callbacks.host?.evaluateJavascript("!!window._cf_chl_opt") { r ->
                    if (r == "true") {
                        cloudflareChallenge = true
                    } else if (cloudflareChallenge) {
                        saveVerificationResult()
                    }
                }
            }
        }
        callbacks.onReceivedTitle = { title ->
            // 原版: 网页 title 非空且不等于 url 时才更新标题栏, 否则保留 intent title
            if (!title.isNullOrBlank() && !title.startsWith("http")) {
                pageTitle = title
            }
        }
        callbacks.onProgressChanged = { progress ->
            loadProgress = if (progress >= 100) null else progress
        }
        callbacks.onUrlChanged = { url -> pageUrl = url }
        callbacks.shouldOverrideUrl = { url -> interceptUrl(url) }
        callbacks.onFullScreenChanged = { full -> videoFullScreen = full }
    }

    // 全屏态 (原 menu_full_screen → toggleFullScreen): 标题栏随全屏隐藏
    var isFullScreen by remember { mutableStateOf(false) }
    // 删除源确认 (原 menu_delete_source 的 alert)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        PlatformServiceProviders.getOrNull()?.window?.setFullscreen(isFullScreen)
    }

    // 当前页 URL (原 menu_copy_url / menu_open_in_browser 取 webView.url ?: baseUrl):
    // 优先平台实时 URL (跳转后最新, 含 SPA history 变化), 其次导航完成状态,
    // 最后回退预取/初始 URL
    fun currentUrl(): String =
        callbacks.host?.getUrl() ?: pageUrl ?: loadState?.url ?: route.url

    // 返回: 视频全屏先退出 (原 customView 分支), 页面可后退则后退 (原 canGoBack && history>1),
    // 再退出页面全屏, 最后出栈
    fun handleBack() {
        if (videoFullScreen) {
            callbacks.host?.exitFullScreen()
        } else if (callbacks.host?.canGoBack() == true) {
            callbacks.host?.goBack()
        } else if (isFullScreen) {
            toggleFullScreen()
        } else {
            navigator.pop()
        }
    }
    AppBackHandler(enabled = isTopEntry) { handleBack() }

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

    Column(Modifier.fillMaxSize()) {
        if (!isFullScreen) {
            // 原 menu_ok isLogin 分支的 toast 文案 (Composable 内取值, 供 onClick 使用)
            val checkHostCookieText = stringResource(Res.string.check_host_cookie)
            AppTitleBar(
                title = pageTitle.ifBlank { stringResource(Res.string.loading) },
                // 原 titleBar.subtitle = sourceName
                subtitle = route.sourceName.takeIf { it.isNotBlank() },
                onBack = { handleBack() },
                actions = {
                    // 刷新 (原 menu_refresh: progressBar 可见 + webView.reload())
                    IconButton(onClick = {
                        loadProgress = 0
                        callbacks.host?.reload()
                    }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                            contentDescription = stringResource(Res.string.refresh),
                            tint = AppTheme.colors.primaryText,
                        )
                    }
                    // 完成按钮 (对照原 menu_ok: 登录模式确认 cookie / 验证完成后手动回传并关闭)
                    if ((route.saveResult || route.isLogin) && loadState != null) {
                        TextButton(onClick = {
                            when {
                                route.isLogin -> {
                                    // 原 menu_ok isLogin 分支: toast + reload, 下次加载完成即 finish
                                    if (!checking) {
                                        checking = true
                                        Toasters.get().toast(checkHostCookieText)
                                        callbacks.host?.reload()
                                    }
                                }

                                route.saveResult -> saveVerificationResult()
                                else -> navigator.pop()
                            }
                        }) {
                            Text(stringResource(Res.string.ok))
                        }
                    }
                    OverflowMenu { dismiss ->
                        // 浏览器打开 (原 menu_open_in_browser → openUrl)
                        DropdownMenuItem(
                            onClick = {
                                dismiss()
                                PlatformCapabilityProviders.getOrNull()
                                    ?.openExternalUrl(currentUrl())
                            },
                        ) {
                            Text(
                                stringResource(Res.string.open_in_browser),
                                color = AppTheme.colors.primaryText
                            )
                        }
                        // 拷贝 URL (原 menu_copy_url → sendToClip)
                        DropdownMenuItem(
                            onClick = {
                                dismiss()
                                PlatformCapabilityProviders.getOrNull()
                                    ?.copyToClipboard(currentUrl())
                            },
                        ) {
                            Text(
                                stringResource(Res.string.copy_url),
                                color = AppTheme.colors.primaryText
                            )
                        }
                        // 全屏 (原 menu_full_screen → toggleFullScreen)
                        DropdownMenuItem(
                            onClick = {
                                dismiss()
                                toggleFullScreen()
                            },
                        ) {
                            Text(
                                stringResource(Res.string.full_screen),
                                color = AppTheme.colors.primaryText
                            )
                        }
                        // 原 onPrepareOptionsMenu: sourceOrigin 非空才显示禁用/删除源
                        if (route.sourceKey.isNotEmpty()) {
                            // 禁用源 (原 menu_disable_source → viewModel.disableSource { finish() })
                            DropdownMenuItem(
                                onClick = {
                                    dismiss()
                                    scope.launch(IoDispatcher) {
                                        runCatching {
                                            SourceHelp.enableSource(
                                                route.sourceKey, route.sourceType, false
                                            )
                                        }.onSuccess {
                                            withContext(Dispatchers.Main) { navigator.pop() }
                                        }
                                    }
                                },
                            ) {
                                Text(
                                    stringResource(Res.string.disable_source),
                                    color = AppTheme.colors.primaryText
                                )
                            }
                            // 删除源 (原 menu_delete_source → alert 确认后 viewModel.deleteSource { finish() })
                            DropdownMenuItem(
                                onClick = {
                                    dismiss()
                                    showDeleteConfirm = true
                                },
                            ) {
                                Text(
                                    stringResource(Res.string.delete_source),
                                    color = AppTheme.colors.primaryText
                                )
                            }
                        }
                    }
                },
            )
        }
        // 加载进度条 (原 RefreshProgressBar: 1dp, 预取中 indeterminate, 100 隐藏;
        // 预取阶段 loadState==null 时 WebView 尚未组合, 无进度回调 → indeterminate 常驻,
        // 对齐原版"加载即常驻" (修复: 原先 loadProgress==null 不显示, 预取期间无任何加载反馈)
        if (!isFullScreen && loadError == null) {
            WebViewLoadingBar(indeterminate = loadState == null, progress = loadProgress)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
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
                }
            }
        }
    }

    // 非 http/https scheme 跳转确认 (原 WebViewUtil.shouldOverrideUrl 的 alert:
    // jump_to_another_app + confirm → openUrl, 拦截 WebView 加载)
    jumpUrl?.let { url ->
        AppAlertDialog(
            onDismissRequest = { jumpUrl = null },
            title = stringResource(Res.string.jump_to_another_app),
            okButton = AlertButton(stringResource(Res.string.ok), dismissOnClick = false) {
                jumpUrl = null
                PlatformCapabilityProviders.getOrNull()?.openExternalUrl(url)
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel), dismissOnClick = false) {
                jumpUrl = null
            },
        )
    }

    // 删除源确认 (原 menu_delete_source 的 alert: sure_del + 源名)
    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n" + route.sourceName,
            okButton = AlertButton(stringResource(Res.string.ok), dismissOnClick = false) {
                showDeleteConfirm = false
                scope.launch(IoDispatcher) {
                    runCatching {
                        SourceHelp.deleteSource(route.sourceKey, route.sourceType)
                    }.onSuccess {
                        withContext(Dispatchers.Main) { navigator.pop() }
                    }
                }
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel), dismissOnClick = false) {
                showDeleteConfirm = false
            },
        )
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
