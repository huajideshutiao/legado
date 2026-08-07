package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
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
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.legado.app.constant.AppConst
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.Download
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.utils.DocumentUtils
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val callbacksRef by rememberUpdatedState(callbacks)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // WebView 默认纯白底, 页面/预取完成前会白屏刺眼; 对齐主题底色 (原版 activity_web_view 内容区
    // 随主题色, 这里在 factory 创建时一次设置, 与主题切换后重建行为一致)
    val webViewBgColor = AppTheme.colors.background.toArgb()
    // 长按图片的 hitTest extra (图片 url 或 base64 data uri), 非空时弹保存菜单
    var imageToSave by remember { mutableStateOf<String?>(null) }
    // 下载确认 (url to fileName), 非空时弹下载对话框
    var downloadRequest by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 上次保存目录 (对照原 ACache imagePathKey: 保存到上次目录, 失败时清除)
    var lastImageDir by remember {
        mutableStateOf(PreferenceProviders.get().getString(AppConst.imagePathKey))
    }
    val saveSuccessText = stringResource(Res.string.save_success)

    // 下载图片字节并写入目录 (对照原 FileUtils.saveImage(url, dirUri) + WebViewModel.saveImage)
    // 注意: 局部函数不可前向引用, doSaveImage 被 pickDirAndSave/saveImage 依赖, 故排在最前
    fun doSaveImage(pic: String, dirUri: String) {
        scope.launch(IoDispatcher) {
            runCatching {
                // data: 前缀自动解包 (原 urlOrBase64ToBytes 的 base64 分支)
                val bytes = AnalyzeUrlCore(
                    pic, coroutineContext = coroutineContext
                ).getByteArrayAwait()
                val dirDoc = DocumentFile.fromTreeUri(context, Uri.parse(dirUri))
                    ?: error("目录不可用")
                val ext = pic.substringAfterLast('.', "").let {
                    if (it.length <= 5 && it.matches(Regex("[a-zA-Z0-9]+"))) ".$it" else ".jpg"
                }
                val name = SimpleDateFormat("yy-MM-dd-HH-mm-ss", Locale.getDefault())
                    .format(Date()) + ext
                val file = DocumentUtils.createFileIfNotExist(dirDoc, name, mimeType = "image/*")
                    ?: error("创建文件失败")
                // 2026-08-04: documentfile 无 openOutputStream(官方指引 ContentResolver), 此处为标准用法。
                val os = context.contentResolver.openOutputStream(file.uri, "w")
                    ?: error("打开文件失败")
                os.use { it.write(bytes) }
            }.onSuccess {
                withContext(Dispatchers.Main) { Toasters.get().toast(saveSuccessText) }
            }.onFailure { e ->
                // 对照原 WebViewModel.saveImage.onError: 清目录缓存并提示
                PreferenceProviders.get().remove(AppConst.imagePathKey)
                withContext(Dispatchers.Main) {
                    Toasters.get().toast("保存图片失败:${e.message}")
                }
            }
        }
    }

    // 选目录后保存 (对照原 setupImageLongClick 的 selectFolder 分支)
    fun pickDirAndSave(pic: String) {
        scope.launch(IoDispatcher) {
            // pickDirectory 内部 runBlocking 等主线程回调, 必须在 IO 线程调用
            val dir = PlatformServiceProviders.get().files.pickDirectory() ?: return@launch
            lastImageDir = dir
            PreferenceProviders.get().putString(AppConst.imagePathKey, dir)
            doSaveImage(pic, dir)
        }
    }

    // 保存: 有上次目录直接存, 否则先选目录 (对照原 WebViewActivity.saveImage)
    fun saveImage(pic: String) {
        if (lastImageDir.isNullOrEmpty()) {
            pickDirAndSave(pic)
        } else {
            doSaveImage(pic, lastImageDir)
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VisibleWebView(ctx).apply {
                    setBackgroundColor(webViewBgColor)
                    applyCommonSettings(settings)
                    webViewClient = AndroidWebViewClient(callbacksRef)
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
                            customView = view
                            callbacksRef.onFullScreenChanged?.invoke(true)
                        }

                        override fun onHideCustomView() {
                            customView = null
                            callbacksRef.onFullScreenChanged?.invoke(false)
                        }
                    }
                    callbacksRef.host = WebViewHostImpl(this)
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
            },
            update = { web ->
                config.headerMap[AppConst.UA_NAME]?.let { web.settings.userAgentString = it }
                // tag 判等避免无关重组触发重复加载 (tag 持最终加载 url, html 模式与 loadUrl 模式互斥同源)
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
                    pickDirAndSave(pic)
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
 * onPageFinished 同步 cookie 到业务层 store + 通知路由 (CF 检测/验证回传);
 * shouldOverrideUrlLoading 交路由拦截 (书源跳转 JS); SSL 错误一律放行 (对照原 BaseWebViewClient)。
 */
private class AndroidWebViewClient(
    private val callbacks: WebViewCallbacks,
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

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url ?: return
        val webCookie = CookieManager.getInstance().getCookie(url)
        if (!webCookie.isNullOrEmpty()) {
            CookieStoreProviders.get()?.replaceCookie(url, webCookie)
        }
        callbacks.onPageFinished?.invoke(url)
        // 页面 URL 状态同步 (页面内跳转后菜单取最新链接)
        callbacks.onUrlChanged?.invoke(url)
    }
}

/** [WebViewHost] 的 Android 实现, 直通 android.webkit.WebView。 */
private class WebViewHostImpl(private val webView: WebView) : WebViewHost {
    override fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
        webView.evaluateJavascript(script) { raw ->
            // Android evaluateJavascript 返回 JSON 转义串, 归一为纯文本
            // (原 WebViewModel.saveVerificationResult 的 unescapeJson + 去首尾引号)
            onResult(if (raw == null) null else EscapeUtils.unescapeJson(raw).trim('"'))
        }
    }

    override fun canGoBack(): Boolean = webView.canGoBack()

    override fun goBack() = webView.goBack()

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
