package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.size
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.anima.RefreshProgressBar
import io.legado.app.utils.ACache
import io.legado.app.utils.dpToPx
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.visible
import io.legado.app.help.http.CookieManager as AppCookieManager

/**
 * 内置浏览器(源验证/登录)：外围纯 Compose，WebView 本体走 AndroidView 白名单。
 */
class WebViewActivity : BaseComposeActivity() {

    val viewModel by viewModels<WebViewModel>()

    // 原 TitleBar 标题/副标题 + 菜单可见性状态
    private var pageTitle by mutableStateOf<String?>(null)
    private var subTitle by mutableStateOf<String?>(null)
    private var okMenuVisible by mutableStateOf(false)
    private var sourceMenuVisible by mutableStateOf(false)
    private var isFullScreen by mutableStateOf(false)
    private var videoFullScreen by mutableStateOf(false)

    private lateinit var chromeClient: CommonWebChromeClient
    private var webPic: String? = null
    private var isCloudflareChallenge = false
    private var checking = false
    private val saveImage by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                ACache.get().put(imagePathKey, uri.toString())
                viewModel.saveImage(webPic, uri.toString())
            }
        }
    }

    // 白名单互操作 View 群，代码构建无 XML；容器类型按 CommonWebChromeClient 契约
    private val webView by lazy { VisibleWebView(this) }
    private val progressBar by lazy {
        RefreshProgressBar(this).apply { fontColor = accentColor }
    }
    private val customViewContainer by lazy {
        FrameLayout(this).apply {
            setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View, child: View) {
                    videoFullScreen = true
                }

                override fun onChildViewRemoved(parent: View, child: View) {
                    videoFullScreen = size > 0
                }
            })
        }
    }
    private val webContainer by lazy {
        ConstraintLayout(this).apply {
            addView(
                webView, ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                progressBar, ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1.dpToPx()
                ).apply { topToTop = ConstraintLayout.LayoutParams.PARENT_ID }
            )
        }
    }

    @Composable
    override fun Content() {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.systemBars.union(WindowInsets.ime)
                            .only(WindowInsetsSides.Bottom)
                    )
            ) {
                if (!videoFullScreen && !isFullScreen) {
                    AppTitleBar(
                        title = pageTitle ?: "",
                        onBack = { supportFinishAfterTransition() },
                        titleContent = { TitleWithSubtitle() },
                        actions = { WebActions() },
                    )
                }
                AndroidView(
                    factory = { webContainer },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
            // 全屏视频容器(原 custom_web_view 覆盖层)，chromeClient 塞入 View 时挂载
            if (videoFullScreen) {
                AndroidView(
                    factory = { customViewContainer },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // 复刻 Toolbar 标题 20sp + 副标题(源名)小字
    @Composable
    private fun TitleWithSubtitle() {
        val colors = AppTheme.colors
        Column {
            Text(
                text = pageTitle ?: "",
                color = colors.primaryText,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subTitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // 对齐 web_view.xml：刷新/确认常驻，浏览器打开/复制/全屏/禁用删除源走溢出
    @Composable
    private fun WebActions() {
        val colors = AppTheme.colors
        IconButton(onClick = { refresh() }) {
            Icon(
                painter = rememberPainter("ic_refresh_black_24dp"),
                contentDescription = stringResource(R.string.refresh),
                tint = colors.primaryText,
            )
        }
        if (okMenuVisible) {
            IconButton(onClick = { onOkMenu() }) {
                Icon(
                    painter = rememberPainter("ic_check"),
                    contentDescription = stringResource(R.string.ok),
                    tint = colors.primaryText,
                )
            }
        }
        OverflowMenu { dismiss ->
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.open_in_browser), color = colors.primaryText)
                },
                onClick = {
                    dismiss()
                    openUrl(webView.url ?: viewModel.baseUrl)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_url), color = colors.primaryText) },
                onClick = {
                    dismiss()
                    sendToClip(webView.url ?: viewModel.baseUrl)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.full_screen), color = colors.primaryText) },
                onClick = {
                    dismiss()
                    toggleFullScreen()
                },
            )
            if (sourceMenuVisible) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.disable_source), color = colors.primaryText)
                    },
                    onClick = {
                        dismiss()
                        viewModel.disableSource { finish() }
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.delete_source), color = colors.primaryText)
                    },
                    onClick = {
                        dismiss()
                        alert(R.string.draw) {
                            setMessage(getString(R.string.sure_del) + "\n" + viewModel.sourceName)
                            noButton()
                            yesButton {
                                viewModel.deleteSource { finish() }
                            }
                        }
                    },
                )
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pageTitle = intent.getStringExtra("title") ?: getString(R.string.loading)
        subTitle = intent.getStringExtra("sourceName")
        viewModel.initData(intent) {
            val url = viewModel.baseUrl
            val headerMap = viewModel.headerMap
            initWebView(url, headerMap)
            val html = viewModel.html
            if (html.isNullOrEmpty()) {
                webView.loadUrl(url, headerMap)
            } else {
                webView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
            }
            sourceMenuVisible = viewModel.sourceOrigin.isNotEmpty()
            okMenuVisible = viewModel.isLogin || viewModel.sourceVerificationEnable
        }
        onBackPressedDispatcher.addCallback(this) {
            if (customViewContainer.size > 0) {
                chromeClient.customViewCallback?.onCustomViewHidden()
                return@addCallback
            } else if (webView.canGoBack()
                && webView.copyBackForwardList().size > 1
            ) {
                webView.goBack()
                return@addCallback
            }
            if (isFullScreen) {
                toggleFullScreen()
                return@addCallback
            }
            finish()
        }
    }

    private fun refresh() {
        progressBar.visible()
        progressBar.setDurProgress(0)
        webView.reload()
    }

    private fun onOkMenu() {
        if (viewModel.isLogin) {
            if (!checking) {
                checking = true
                toastOnUi(R.string.check_host_cookie)
                webView.reload()
            }
        } else if (viewModel.sourceVerificationEnable) {
            viewModel.saveVerificationResult(webView) {
                finish()
            }
        } else {
            finish()
        }
    }

    //实现starBrowser调起页面全屏
    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String, headerMap: HashMap<String, String>) {
        chromeClient = CommonWebChromeClient(
            this, progressBar, webContainer, customViewContainer
        ) {
            if (viewModel.sourceVerificationEnable) {
                viewModel.saveVerificationResult(webView) { finish() }
            } else {
                finish()
            }
        }
        webView.webChromeClient = chromeClient
        webView.webViewClient = CustomWebViewClient()
        WebViewUtil.applyCommonSettings(webView.settings)
        webView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            headerMap[AppConst.UA_NAME]?.let {
                userAgentString = it
            }
        }
        AppCookieManager.applyToWebView(url)
        WebViewUtil.setupImageLongClick(
            webView, this,
            onSave = { saveImage(it) },
            onSelectFolder = { saveImage.launch {} }
        )
        WebViewUtil.setupDownloadListener(webView, this)
    }

    private fun saveImage(webPic: String) {
        this.webPic = webPic
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            saveImage.launch {}
        } else {
            viewModel.saveImage(webPic, path)
        }
    }

    override fun finish() {
        SourceVerificationHelp.checkResult(viewModel.sourceOrigin)
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }

    inner class CustomWebViewClient : BaseWebViewClient() {
        override fun interceptUrl(url: Uri): Boolean {
            return WebViewUtil.shouldOverrideUrl(url, this@WebViewActivity)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (viewModel.isLogin) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.getCookie(url)?.let {
                    CookieStore.setCookie(viewModel.sourceOrigin, it)
                }
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val cookieManager = CookieManager.getInstance()
            url?.let {
                CookieStore.setCookie(it, cookieManager.getCookie(it))
                if (viewModel.isLogin) {
                    CookieStore.setCookie(viewModel.sourceOrigin, cookieManager.getCookie(it))
                }
            }
            if (checking) finish()
            view?.title?.let { title ->
                if (title != url && title != view.url && title.isNotBlank()) {
                    pageTitle = title
                } else {
                    pageTitle = intent.getStringExtra("title")
                }
                view.evaluateJavascript("!!window._cf_chl_opt") {
                    if (it == "true") {
                        isCloudflareChallenge = true
                    } else if (isCloudflareChallenge && viewModel.sourceVerificationEnable) {
                        viewModel.saveVerificationResult(webView) {
                            finish()
                        }
                    }
                }
            }
        }

    }

}
