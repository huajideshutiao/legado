package io.legado.app.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.check_host_cookie
import legado.shared.generated.resources.jump_to_another_app
import legado.shared.generated.resources.login_source
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.coroutineContext

/**
 * 内置浏览器整屏实现, 全屏路由与半屏 Sheet 的**唯一**实现来源。
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
 *
 * # 两种形态只允许形态本身的差异
 *
 * 参数包同为 [AppRoute.WebView], 加载/回传/拦截/菜单/返回链全部走本函数, 不存在"半屏版实现"。
 * 宿主只注入形态壳:
 * - [onClose]: 出栈 (路由) / 收弹层 (Sheet);
 * - [backHandler]: 返回拦截的注册机制不同 (路由 AppBackHandler 按栈顶 / Sheet BackLayerHandler
 *   按覆盖物栈), 注册进去的动作是同一个;
 * - [fullScreen] + [onToggleFullScreen]: 全屏由谁承载不同 (路由切系统全屏 / Sheet 切弹层高度),
 *   但"全屏即隐藏顶栏与进度条、返回键先退出全屏"两端一致;
 * - [contentModifier]: Sheet 需要 `sheetDragExclusion()` 把竖直手势让给平台 WebView, 并补
 *   主题底色占位 (弹层圆角内不能露白)。
 */
@Composable
internal fun WebViewScreen(
    spec: AppRoute.WebView,
    onClose: () -> Unit,
    fullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
    backHandler: @Composable (onBack: () -> Unit) -> Unit,
    contentModifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val callbacks = remember { WebViewCallbacks() }
    var loadState by remember { mutableStateOf<WebViewLoadState?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var cloudflareChallenge by remember { mutableStateOf(false) }
    // 传入标题 (原 WebViewActivity 的 intent title); 登录页原版传
    // getString(login_source, 源名) + sourceName 作副标题, 路由参数在 commonMain 无本地化
    // 能力, 故标题在此按 isLogin 补齐
    val specTitle = if (spec.isLogin && spec.title.isBlank() && spec.sourceName.isNotBlank()) {
        stringResource(Res.string.login_source, spec.sourceName)
    } else {
        spec.title
    }
    // 页面标题 (原 WebViewActivity: intent title → onPageFinished 更新为网页 title)
    var pageTitle by remember(specTitle) { mutableStateOf(specTitle) }
    // 页面当前 URL 状态 (原 menu_copy_url / menu_open_in_browser 取 webView.url ?: baseUrl;
    // 平台在每次导航完成时经 onUrlChanged 更新, 页面内跳转后菜单取最新链接)
    var pageUrl by remember(spec) { mutableStateOf<String?>(null) }
    // 加载进度 (原 RefreshProgressBar: menu_refresh 置 0, onProgressChanged 更新, 100 隐藏)
    var loadProgress by remember { mutableStateOf<Int?>(null) }
    // 原 menu_ok 的 isLogin 分支: 确认后 reload 检查 cookie, 下次加载完成即返回
    var checking by remember { mutableStateOf(false) }
    // 非 http/https scheme 跳转确认 (原 WebViewUtil.shouldOverrideUrl 的 else 分支)
    var jumpUrl by remember { mutableStateOf<String?>(null) }
    // 删除源确认 (原 menu_delete_source 的 alert)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 视频全屏态 (HTML5 video onShowCustomView, 对照原版 WebViewActivity 的
    // binding.customWebView.size > 0): 返回键先退出视频全屏再走后续。
    // 声明在事件接线之前 (SideEffect lambda 引用)
    var videoFullScreen by remember { mutableStateOf(false) }

    // 预取加载参数 (原 WebViewModel.initData, 原版在 IO 执行)
    LaunchedEffect(spec) {
        loadError = null
        loadState = null
        cloudflareChallenge = false
        runCatching { withContext(IoDispatcher) { prepareWebViewData(spec) } }
            .onSuccess { loadState = it }
            .onFailure { e -> loadError = e.message ?: "error" }
    }

    // 验证完成回传 (原 WebViewModel.saveVerificationResult)
    fun saveVerificationResult() {
        if (!spec.saveResult) {
            onClose()
            return
        }
        val headerMap = loadState?.headerMap ?: emptyMap()
        if (spec.refetchAfterSuccess || callbacks.host == null) {
            // 原 refetchAfterSuccess=true 分支; host 缺失 (desktop/鸿蒙占位) 时兜底走重拉
            scope.launch(IoDispatcher) {
                try {
                    val source = SourceHelp.getSource(spec.sourceKey)
                    val body = AnalyzeUrlCore(
                        spec.url,
                        headerMapF = headerMap,
                        source = source,
                        coroutineContext = coroutineContext,
                    ).getStrResponseAwait(allowWebView = false).body
                    SourceVerificationHelpShared.setResult(spec.sourceKey, body ?: "")
                    withContext(Dispatchers.Main) { onClose() }
                } catch (_: Exception) {
                    // 原版 execute.onError: 提示并停留页面等待重试
                }
            }
        } else {
            // host 侧已按平台归一为纯文本 (Android: unescapeJson+去引号; iOS: WKWebView 原生值)
            callbacks.host?.evaluateJavascript("document.documentElement.outerHTML") { result ->
                scope.launch(IoDispatcher) {
                    SourceVerificationHelpShared.setResult(spec.sourceKey, result ?: "")
                    withContext(Dispatchers.Main) { onClose() }
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

    // 平台 WebView 事件接线 (对照原 WebViewActivity 各回调):
    // - onPageFinished: menu_ok(isLogin) 确认后下次加载完成即返回; CF 挑战检测/验证回传
    // - onReceivedTitle: 网页 title 更新标题栏 (原 onPageFinished 读 view.title)
    // - onProgressChanged: 加载进度 → RefreshProgressBar 语义 (100 隐藏)
    // - shouldOverrideUrl: 原 WebViewUtil.shouldOverrideUrl (legado/yuedu 导入 / 其他 scheme 确认跳转)
    SideEffect {
        callbacks.onPageFinished = {
            if (checking) {
                checking = false
                onClose()
            } else if (spec.saveResult) {
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
            // 原版: 网页 title 非空且既不等于 url 也不等于 webView.url 时才更新标题栏,
            // 否则回退到传入标题 (不保留上一页的标题)
            pageTitle = acceptedPageTitle(title, callbacks.host?.getUrl() ?: pageUrl)
                ?: specTitle
        }
        callbacks.onProgressChanged = { progress ->
            loadProgress = if (progress >= 100) null else progress
        }
        callbacks.onUrlChanged = { url -> pageUrl = url }
        callbacks.shouldOverrideUrl = { url -> interceptUrl(url) }
        callbacks.onFullScreenChanged = { full -> videoFullScreen = full }
        // 原 WebViewActivity 传给 CommonWebChromeClient 的 onCloseWindow: 网页 window.close()
        // 时验证场景先回传网页源码再关闭, 否则直接关闭
        callbacks.onCloseWindow = {
            if (spec.saveResult) saveVerificationResult() else onClose()
        }
    }

    // 当前页 URL (原 menu_copy_url / menu_open_in_browser 取 webView.url ?: baseUrl):
    // 优先平台实时 URL (跳转后最新, 含 SPA history 变化), 其次导航完成状态,
    // 最后回退预取/初始 URL
    fun currentUrl(): String =
        callbacks.host?.getUrl() ?: pageUrl ?: loadState?.url ?: spec.url

    // 系统返回键: 视频全屏先退出 → 网页可后退则后退 → 退出全屏 → 关闭浏览器
    // (原 WebViewActivity 的 onBackPressedDispatcher 回调)。顶栏返回箭头不走这条链, 见下方调用点。
    fun handleBack() {
        webViewHandleBack(
            host = callbacks.host,
            videoFullScreen = videoFullScreen,
            fullScreen = fullScreen,
            onExitFullScreen = onToggleFullScreen,
            onClose = onClose,
        )
    }
    backHandler { handleBack() }

    // 关闭对应原 finish(): 未回传结果时补空结果并唤醒等待线程 (原 checkResult)。
    // startBrowser 无论 await 与否都 registerWaitingThread, 故两形态都必须补, 否则
    // getVerificationResult 的等待线程永挂 (后续 getVerificationResult 开头会 clearResult,
    // 空结果不会污染下一次验证)。
    DisposableEffect(spec) {
        onDispose {
            if (spec.sourceKey.isNotEmpty()) {
                SourceVerificationHelpShared.getResult(spec.sourceKey)
                    ?: SourceVerificationHelpShared.setResult(spec.sourceKey, "")
                SourceVerificationHelpShared.notifyResultArrived(spec.sourceKey)
                // URL 登录 (loginUi 为空) 直接开本页, 关闭即唤醒阻塞在
                // source.showLoginDialog() 上的 JS 线程
                if (spec.isLogin) {
                    SourceVerificationHelpShared.notifyLoginFinished(spec.sourceKey)
                }
            }
        }
    }

    // 顶栏/进度条隐藏条件: 页面全屏 (原 menu_full_screen 的 supportActionBar.hide) 或
    // HTML5 视频全屏 (原 CommonWebChromeClient.onShowCustomView 的 llView.invisible())
    val hideChrome = fullScreen || videoFullScreen
    Column(Modifier.fillMaxSize()) {
        if (!hideChrome) {
            // 原 menu_ok isLogin 分支的 toast 文案 (Composable 内取值, 供 onClick 使用)
            val checkHostCookieText = stringResource(Res.string.check_host_cookie)
            WebViewTitleBar(
                title = pageTitle,
                // 原 titleBar.subtitle = sourceName
                subtitle = spec.sourceName.takeIf { it.isNotBlank() },
                // 顶栏返回箭头直接关页面, 不退网页历史 (原版 android.R.id.home →
                // supportFinishAfterTransition, 只有系统返回键才走 goBack 链)。
                // 全屏态顶栏已隐藏, 无需在此兼顾退出全屏
                onBack = onClose,
                // 原 menu_refresh: progressBar 可见 + webView.reload()
                onRefresh = {
                    loadProgress = 0
                    callbacks.host?.reload()
                },
                currentUrl = { currentUrl() },
                onFullScreen = onToggleFullScreen,
                sourceKey = spec.sourceKey,
                onDisableSource = {
                    scope.disableWebViewSource(spec.sourceKey, spec.sourceType, onClose)
                },
                onDeleteSource = { showDeleteConfirm = true },
                extraActions = {
                    // 完成按钮 (对照原 menu_ok: 登录模式确认 cookie / 验证完成后手动回传并关闭)
                    if ((spec.saveResult || spec.isLogin) && loadState != null) {
                        WebViewOkAction {
                            when {
                                spec.isLogin -> {
                                    // 原 menu_ok isLogin 分支: toast + reload, 下次加载完成即 finish
                                    if (!checking) {
                                        checking = true
                                        Toasters.get().toast(checkHostCookieText)
                                        callbacks.host?.reload()
                                    }
                                }

                                spec.saveResult -> saveVerificationResult()
                                else -> onClose()
                            }
                        }
                    }
                },
            )
        }
        // 加载进度条 (原 RefreshProgressBar: 1dp, 预取中 indeterminate, 100 隐藏;
        // 预取阶段 loadState==null 时 WebView 尚未组合, 无进度回调 → indeterminate 常驻,
        // 对齐原版"加载即常驻")
        if (!hideChrome && loadError == null) {
            WebViewLoadingBar(indeterminate = loadState == null, progress = loadProgress)
        }
        Box(Modifier.fillMaxWidth().weight(1f).then(contentModifier)) {
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
                            isLogin = spec.isLogin,
                            saveResult = spec.saveResult,
                            sourceKey = spec.sourceKey,
                            // 原 WebViewActivity.initWebView 设 useWideViewPort + loadWithOverviewMode
                            wideViewPort = true,
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
        WebViewDeleteSourceConfirm(
            sourceName = spec.sourceName,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                scope.deleteWebViewSource(spec.sourceKey, spec.sourceType, onClose)
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
private suspend fun prepareWebViewData(spec: AppRoute.WebView): WebViewLoadState {
    val url = spec.url
    // 原版: 非登录且非 data/http 前缀时, url 直接视为原始 HTML
    if (!spec.isLogin && !url.startsWith("data") && !url.startsWith("http")) {
        return WebViewLoadState(url, emptyMap(), url)
    }
    val source = SourceHelp.getSource(spec.sourceKey, spec.sourceType)
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

/**
 * 网页 title 过滤 (原版 onReceivedTitle/onPageFinished: 非空且既不等于 [url] 也不是当前地址
 * 才更新标题栏), 返回 null 表示本次 title 不采用, 由调用方回退到传入标题。
 */
private fun acceptedPageTitle(title: String?, url: String?): String? =
    title?.takeIf { it.isNotBlank() && it != url }

/**
 * 系统返回键的返回链 (原 WebViewActivity 的 onBackPressed): 视频全屏先退出 →
 * 网页可后退则后退 → 退出页面全屏 → 关闭浏览器。顶栏返回箭头不走这里, 直接关闭。
 */
private fun webViewHandleBack(
    host: WebViewHost?,
    videoFullScreen: Boolean,
    fullScreen: Boolean,
    onExitFullScreen: () -> Unit,
    onClose: () -> Unit,
) {
    when {
        videoFullScreen -> host?.exitFullScreen()
        // 原版守卫: canGoBack && history>1 (由平台 host 实现)
        host != null && host.canGoBack() -> host.goBack()
        fullScreen -> onExitFullScreen()
        else -> onClose()
    }
}

/** 禁用源 (原 menu_disable_source → viewModel.disableSource { finish() })。 */
private fun CoroutineScope.disableWebViewSource(
    sourceKey: String,
    sourceType: Int,
    onClosed: () -> Unit,
) = launchSourceAction(onClosed) { SourceHelp.enableSource(sourceKey, sourceType, false) }

/** 删除源 (原 menu_delete_source → viewModel.deleteSource { finish() })。 */
private fun CoroutineScope.deleteWebViewSource(
    sourceKey: String,
    sourceType: Int,
    onClosed: () -> Unit,
) = launchSourceAction(onClosed) { SourceHelp.deleteSource(sourceKey, sourceType) }

/** IO 线程改库, 成功后切主线程关闭浏览器 (原版两个动作都是 finish 收尾)。 */
private fun CoroutineScope.launchSourceAction(
    onClosed: () -> Unit,
    action: suspend () -> Unit,
) {
    launch(IoDispatcher) {
        runCatching { action() }.onSuccess {
            withContext(Dispatchers.Main) { onClosed() }
        }
    }
}
