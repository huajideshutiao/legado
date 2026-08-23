package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.legado.app.constant.AppConst
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.Download
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.OrientationPolicy
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.imageSaveFileName
import io.legado.app.utils.EscapeUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_download
import legado.shared.generated.resources.action_save
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.save_success
import legado.shared.generated.resources.select_folder
import org.jetbrains.compose.resources.stringResource
import java.net.URLDecoder

/**
 * Android 端 WebView 平台实现 (供 [LocalWebViewSlot] 注入)。
 *
 * 复用下沉的 [VisibleWebView] (原 app 端, 保持 onWindowVisibilityChanged 强制 VISIBLE 语义);
 * WebSettings 对齐原 app 端 `WebViewUtil.applyCommonSettings`;
 * onPageFinished 同步 WebView cookie → [CookieStoreProviders] (供 OkHttp 复用登录态)
 * 并回调 [WebViewCallbacks.onPageFinished] (CF 挑战检测/验证回传由 WebViewRoute 处理)。
 *
 * 加载语义对照原 WebViewActivity.initWebView / ReadRssActivity.initWebView:
 * - 加载前把业务层 cookie 灌进 WebView (原 `CookieManager.applyToWebView(url)`);
 * - [WebViewConfig.headerMap] 注入 loadUrl(url, headers), 含 User-Agent 头 (原
 *   `headerMap[AppConst.UA_NAME]` → settings.userAgentString);
 * - [WebViewConfig.html] 非空时 loadDataWithBaseURL (POST body/data: 解包/RSS clHtml 正文);
 * - [WebViewCallbacks.shouldOverrideUrl] 接原 `BaseWebViewClient.interceptUrl` (书源跳转拦截 JS);
 * - [WebViewCallbacks.onReceivedTitle] / [WebViewCallbacks.onFullScreenChanged] 接原
 *   `CommonWebChromeClient` 的标题与 `<video>` 全屏 (custom view 铺满本组件自己的容器);
 * - 长按图片保存与下载监听 (原 WebViewUtil.setupImageLongClick / setupDownloadListener):
 *   长按图片弹保存菜单 (保存到上次目录/选择文件夹, 目录经 SAF 持久化授权后写入),
 *   下载走 shared [Download] (Android 端仍落系统 DownloadManager/DownloadService)。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AndroidWebView(
    config: WebViewConfig,
    modifier: Modifier = Modifier,
    callbacks: WebViewCallbacks = WebViewCallbacks(),
) {
    // 全屏 custom view 由本组件自己托管: 有值时铺满整个 slot, 盖住 WebView
    var customView by remember { mutableStateOf<View?>(null) }
    // 视频全屏退出回调 (对照原 WebChromeClient.CustomViewCallback): 返回键需主动退出视频全屏
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    // WebView 实例: 帧后创建, 避免 WebView 构造 (Chromium 内核初始化, 主线程可达数百 ms) 阻塞
    // 组合路径导致整页(含顶栏)延迟渲染。页面先渲染(顶栏立即可见), WebView 就绪后加入容器。
    var webViewInstance by remember { mutableStateOf<VisibleWebView?>(null) }
    val callbacksRef by rememberUpdatedState(callbacks)
    // 供 WebViewClient 读最新 isLogin/sourceKey (客户端只在首帧创建一次, 不能捕获首帧 config)
    val configRef by rememberUpdatedState(config)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // WebView 默认纯白底, 页面/预取完成前会白屏刺眼; 对齐主题底色 (原版 activity_web_view 内容区
    // 随主题色, 这里在 factory 创建时一次设置, 与主题切换后重建行为一致)
    val webViewBgColor = AppTheme.colors.background.toArgb()
    // 长按图片的 hitTest extra (图片 url 或 base64 data uri), 非空时弹保存菜单
    var imageToSave by remember { mutableStateOf<String?>(null) }
    // 下载确认 (url to fileName), 非空时弹下载对话框
    var downloadRequest by remember { mutableStateOf<Pair<String, String>?>(null) }
    val saveSuccessText = stringResource(Res.string.save_success)

    // 下载图片字节 → 落盘 (对照原 FileUtils.saveImage(url, dirUri) + WebViewModel.saveImage;
    // 目录记忆与失败清除都在 FilePickerService.saveImageRememberingDir 里,
    // forcePickDir = 长按菜单的"选择文件夹")
    fun saveImage(pic: String, forcePickDir: Boolean = false) {
        scope.launch(IoDispatcher) {
            runCatching {
                // data: 前缀自动解包 (原 urlOrBase64ToBytes 的 base64 分支)
                val bytes = AnalyzeUrlCore(
                    pic, coroutineContext = coroutineContext
                ).getByteArrayAwait()
                PlatformServiceProviders.get().files
                    .saveImageRememberingDir(imageSaveFileName(pic), bytes, forcePickDir)
            }.onSuccess { saved ->
                val text = when (saved) {
                    true -> saveSuccessText
                    false -> "保存图片失败"
                    null -> null // 用户取消选目录: 静默
                }
                text?.let { withContext(Dispatchers.Main) { Toasters.get().toast(it) } }
            }.onFailure { e ->
                // 对照原 WebViewModel.saveImage.onError
                withContext(Dispatchers.Main) {
                    Toasters.get().toast("保存图片失败:${e.message}")
                }
            }
        }
    }

    // 应用加载配置 (原 factory+update 的 loadUrl 逻辑; tag 判等避免无关重组触发重复加载)
    fun applyWebViewConfig(web: WebView, config: WebViewConfig) {
        // 原 WebViewActivity.initWebView 与 UA 同处设置的视口项 (原 ReadRssActivity 不设,
        // 故按 config 开关而非塞进 applyCommonSettings); 幂等, 重复设置无副作用
        if (config.wideViewPort) {
            web.settings.useWideViewPort = true
            web.settings.loadWithOverviewMode = true
        }
        config.headerMap[AppConst.UA_NAME]?.let { web.settings.userAgentString = it }
        // tag 持最终加载 url, html 模式与 loadUrl 模式互斥同源
        val loadUrl = if (config.html.isNullOrEmpty()) config.url else ""
        if (web.tag != loadUrl) {
            web.tag = loadUrl
            // 原 CookieManager.applyToWebView: 业务层 cookie → WebView, 登录态才带得过去
            applyCookiesToWebView(config.url)
            if (config.html.isNullOrEmpty()) {
                web.loadUrl(config.url, HashMap(config.headerMap))
            } else {
                web.loadDataWithBaseURL(
                    config.url, config.html, "text/html", "utf-8", config.url
                )
            }
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            // factory 只返回轻量容器: WebView 构造 (Chromium 初始化) 移出组合路径, 整页(含顶栏)不被阻塞
            factory = { ctx -> FrameLayout(ctx) },
            update = { container ->
                val web = webViewInstance
                if (web != null && container.childCount == 0) {
                    (web.parent as? ViewGroup)?.removeView(web)
                    container.addView(
                        web,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                if (web != null) {
                    // tag 判等幂等: 首次组合 loadUrl, 无关重组/刷新时不再重复加载
                    applyWebViewConfig(web, config)
                }
            },
        )
        customView?.let { view ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> FrameLayout(ctx) },
                update = { container ->
                    if (container.childCount == 0 || container.getChildAt(0) !== view) {
                        container.removeAllViews()
                        (view.parent as? ViewGroup)?.removeView(view)
                        container.addView(view)
                    }
                },
                onRelease = { it.removeAllViews() },
            )
        }
    }

    // 帧后创建 WebView: View 必须在主线程创建; 组合帧已渲染(顶栏立即可见), 创建耗时只影响后续帧。
    // 对照原版 WebViewActivity: 顶栏是布局内独立视图, 不受 WebView 初始化影响, 始终立即显示。
    LaunchedEffect(Unit) {
        val web = VisibleWebView(context).apply {
            setBackgroundColor(webViewBgColor)
            applyCommonSettings(settings)
            webViewClient = AndroidWebViewClient(callbacksRef) { configRef }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    callbacksRef.onReceivedTitle?.invoke(title)
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    // 对照原 CommonWebChromeClient.onProgressChanged → RefreshProgressBar
                    callbacksRef.onProgressChanged?.invoke(newProgress)
                }

                override fun onShowCustomView(
                    view: View?,
                    callback: CustomViewCallback?,
                ) {
                    // 对照原 CommonWebChromeClient.onShowCustomView: 方向解锁为 SENSOR、
                    // 屏幕常亮、隐藏系统栏; llView.invisible() 对应上报全屏态后由
                    // WebViewScreen 隐藏顶栏/进度条
                    PlatformServiceProviders.getOrNull()?.window?.let { window ->
                        window.setOrientation(OrientationPolicy.Sensor)
                        window.setKeepScreenOn(true)
                        window.setFullscreen(true)
                    }
                    customView = view
                    customViewCallback = callback
                    callbacksRef.onFullScreenChanged?.invoke(true)
                }

                override fun onHideCustomView() {
                    // 对照原 CommonWebChromeClient.onHideCustomView: 方向复位、取消常亮、恢复系统栏
                    customView = null
                    customViewCallback = null
                    PlatformServiceProviders.getOrNull()?.window?.let { window ->
                        window.setOrientation(OrientationPolicy.Unspecified)
                        window.setKeepScreenOn(false)
                        window.setFullscreen(false)
                    }
                    callbacksRef.onFullScreenChanged?.invoke(false)
                }

                // 原 CommonWebChromeClient.onCloseWindow: 有回调走回调 (浏览器形态: 回传验证
                // 结果并关闭), 无回调回退 super (原 ReadRssActivity 未传回调的行为)
                override fun onCloseWindow(window: WebView?) {
                    val handler = callbacksRef.onCloseWindow
                    if (handler != null) handler() else super.onCloseWindow(window)
                }
            }
            callbacksRef.host = WebViewHostImpl(this) {
                customViewCallback?.onCustomViewHidden()
            }
            // 长按图片弹保存菜单 (原 WebViewUtil.setupImageLongClick)
            setOnLongClickListener {
                val hit = hitTestResult
                if (hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                    hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                ) {
                    hit.extra?.let { pic ->
                        imageToSave = pic
                        return@setOnLongClickListener true
                    }
                }
                return@setOnLongClickListener false
            }
            // 下载监听弹确认 (原 WebViewUtil.setupDownloadListener)
            setDownloadListener { url, _, contentDisposition, _, _ ->
                val downloadUrl = url ?: return@setDownloadListener
                var fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, null)
                fileName = URLDecoder.decode(fileName, "UTF-8")
                downloadRequest = downloadUrl to fileName
            }
        }
        webViewInstance = web
    }

    // 页面离开组合: 释放 WebView (对照原 WebViewActivity.onDestroy 的无条件 webView.destroy())。
    // AndroidView 释放只把 View 从容器摘掉, 不会调 destroy() —— Chromium 原生资源要自己放,
    // 故先摘除再无条件 destroy (尚未入容器的孤儿 View 同样走这条)。
    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.let { web ->
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
    }

    // 长按图片保存菜单 (对照原 WebViewUtil.setupImageLongClick 的 selector: 保存/选择文件夹)
    imageToSave?.let { pic ->
        AlertDialog(
            onDismissRequest = { imageToSave = null },
            title = { Text(stringResource(Res.string.action_save)) },
            confirmButton = {
                TextButton(onClick = {
                    imageToSave = null
                    saveImage(pic)
                }) { Text(stringResource(Res.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    imageToSave = null
                    saveImage(pic, forcePickDir = true)
                }) { Text(stringResource(Res.string.select_folder)) }
            },
        )
    }
    // 下载确认 (对照原 setupDownloadListener 的 alert: 标题=文件名, 确认=下载)
    downloadRequest?.let { (url, fileName) ->
        AlertDialog(
            onDismissRequest = { downloadRequest = null },
            title = { Text(fileName) },
            confirmButton = {
                TextButton(onClick = {
                    downloadRequest = null
                    Download.start(url, fileName)
                }) { Text(stringResource(Res.string.action_download)) }
            },
            dismissButton = {
                TextButton(onClick = { downloadRequest = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}


/** 对照原 app 端 WebViewUtil.applyCommonSettings。 */
@SuppressLint("SetJavaScriptEnabled")
private fun applyCommonSettings(settings: WebSettings) {
    settings.apply {
        javaScriptEnabled = true
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        domStorageEnabled = true
        allowContentAccess = true
        builtInZoomControls = true
        displayZoomControls = false
        setDarkeningAllowed(AppConfigProviders.get().isNightTheme)
    }
}

/** 下沉自 app 端 `WebSettings.setDarkeningAllowed`: Q 以上走算法反色, 以下退回 forceDark。 */
@SuppressLint("RequiresFeature")
private fun WebSettings.setDarkeningAllowed(allow: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val applied = runCatching {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, allow)
        }.isSuccess
        if (applied) return
    }
    if (!allow) return
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
        @Suppress("DEPRECATION")
        WebSettingsCompat.setForceDarkStrategy(
            this,
            WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
        )
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
        @Suppress("DEPRECATION")
        WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_ON)
    }
}

/**
 * 业务层 cookie → WebView (原 `io.legado.app.help.http.CookieManager.applyToWebView`)。
 *
 * 不在这里 removeSessionCookies —— 那是全局操作, 会影响别的页面。
 */
private fun applyCookiesToWebView(url: String) {
    val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
    val cookies = CookieStoreProviders.get()?.getCookie(url)?.splitNotBlank(";") ?: return
    if (cookies.isEmpty()) return
    val webManager = CookieManager.getInstance()
    cookies.forEach { webManager.setCookie(baseUrl, it) }
    webManager.flush()
}

/**
 * 对照原 WebViewActivity.CustomWebViewClient:
 * - onPageStarted: isLogin 时把 WebView cookie 按书源 key 写回业务层 (登录态可复用);
 * - onPageFinished: 按 url 写回 cookie, isLogin 时再按书源 key 写一份, 然后通知路由
 *   (CF 检测/验证回传/menu_ok 的 checking 收尾);
 * - shouldOverrideUrlLoading 交路由拦截 (书源跳转 JS); SSL 错误一律放行 (原 BaseWebViewClient)。
 *
 * cookie 写入用 setCookie (原版语义: 页面加载后 WebView 的 cookie 串是权威值, 直接覆写;
 * 不用 replaceCookie 合并 —— 那会把服务端刚清掉的旧 cookie 又并回去)。
 */
private class AndroidWebViewClient(
    private val callbacks: WebViewCallbacks,
    private val config: () -> WebViewConfig,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = callbacks.shouldOverrideUrl?.invoke(request.url.toString()) == true

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        callbacks.shouldOverrideUrl?.invoke(url) == true

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: android.net.http.SslError?,
    ) {
        handler?.proceed()
    }

    // 原 CustomWebViewClient.onPageStarted: 登录页在导航开始时就把 cookie 按书源 key 存一份
    // (登录站常在跳转链中途下发 cookie, 只等 onPageFinished 会漏)
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val cfg = config()
        if (cfg.isLogin) {
            CookieManager.getInstance().getCookie(url)?.let { cookie ->
                CookieStoreProviders.get()?.setCookie(cfg.sourceKey, cookie)
            }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val cfg = config()
        url?.let {
            val webCookie = CookieManager.getInstance().getCookie(it)
            CookieStoreProviders.get()?.setCookie(it, webCookie)
            if (cfg.isLogin) {
                CookieStoreProviders.get()?.setCookie(cfg.sourceKey, webCookie)
            }
        }
        // 原版 onPageFinished 里 `if (checking) finish()` 不受 url 是否为空影响, 故无条件通知
        callbacks.onPageFinished?.invoke(url)
        // 页面 URL 状态同步 (页面内跳转后菜单取最新链接)
        url?.let { callbacks.onUrlChanged?.invoke(it) }
    }
}

/** [WebViewHost] 的 Android 实现, 直通 android.webkit.WebView。 */
private class WebViewHostImpl(
    private val webView: WebView,
    private val onExitFullScreen: () -> Unit,
) : WebViewHost {
    override fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
        webView.evaluateJavascript(script) { raw ->
            // Android evaluateJavascript 返回 JSON 转义串, 归一为纯文本
            // (原 WebViewModel.saveVerificationResult 的 unescapeJson + 去首尾引号)
            onResult(if (raw == null) null else EscapeUtils.unescapeJson(raw).trim('"'))
        }
    }

    // 返回守卫对齐原版 WebViewActivity: canGoBack && copyBackForwardList().size > 1。
    // 登录/校验页常有不可见历史 (about:blank 初始项、302 重定向链), 仅 canGoBack 时第一下
    // goBack 画面无变化, 第二下才退出——表现为"返回键要按两次"
    override fun canGoBack(): Boolean =
        webView.canGoBack() && webView.copyBackForwardList().size > 1

    override fun goBack() = webView.goBack()

    override fun exitFullScreen() = onExitFullScreen()

    override fun getUrl(): String? = webView.url

    override fun reload() = webView.reload()
}

/**
 * 可见态 WebView: 覆盖 onWindowVisibilityChanged 强制 VISIBLE,
 * 避免嵌入 Fragment/ViewPager 时被 visibility 隐藏暂停渲染。
 */
class VisibleWebView(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(VISIBLE)
    }
}
