package io.legado.app.ui.book.rss

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
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
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.size
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.browser.BaseWebViewClient
import io.legado.app.ui.browser.CommonWebChromeClient
import io.legado.app.ui.browser.VisibleWebView
import io.legado.app.ui.browser.WebViewUtil
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.anima.RefreshProgressBar
import io.legado.app.utils.ACache
import io.legado.app.utils.EscapeUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isTrue
import io.legado.app.utils.openUrl
import io.legado.app.utils.share
import io.legado.app.utils.textArray
import io.legado.app.utils.toastOnUi
import com.fleeksoft.ksoup.Ksoup

/**
 * rss阅读界面：外围纯 Compose，WebView 本体走 AndroidView 白名单。
 */
class ReadRssActivity : BaseComposeActivity() {

    val viewModel by viewModels<ReadRssViewModel>()

    // 原 TitleBar 标题 + 菜单项状态
    var pageTitle by mutableStateOf<String?>(null)
        private set
    var starVisible by mutableStateOf(false)
        private set
    var inShelf by mutableStateOf(false)
        private set
    var ttsPlaying by mutableStateOf(false)
        private set
    var hasLogin by mutableStateOf(false)
        private set
    private var videoFullScreen by mutableStateOf(false)

    private lateinit var chromeClient: CommonWebChromeClient
    private val selectImageDir = registerHandleFile {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }
    private val rssJsExtensions by lazy { RssJsExtensions(this) }

    // 白名单互操作 View 群，代码构建无 XML；容器类型按 CommonWebChromeClient 契约
    val webView by lazy { VisibleWebView(this) }
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

    fun getSource(): BookSource? {
        return viewModel.curBookSource
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
                if (!videoFullScreen) {
                    AppTitleBar(
                        title = pageTitle ?: "",
                        // 原 TitleBar 返回箭头直接结束页面，webView 回退只走系统返回
                        onBack = { supportFinishAfterTransition() },
                        actions = { RssActions() },
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

    // 对齐 rss_read.xml：刷新/收藏 always，分享/朗读 ifRoom，登录/浏览器打开 never
    @Composable
    private fun RssActions() {
        val colors = AppTheme.colors
        val configuration = LocalConfiguration.current
        val widthDp = configuration.screenWidthDp
        val heightDp = configuration.screenHeightDp
        val maxActionButtons = when {
            configuration.smallestScreenWidthDp > 600 || widthDp > 600 ||
                (widthDp > 960 && heightDp > 720) ||
                (widthDp > 720 && heightDp > 960) -> 5

            widthDp >= 500 ||
                (widthDp > 640 && heightDp > 480) ||
                (widthDp > 480 && heightDp > 640) -> 4

            widthDp >= 360 -> 3
            else -> 2
        }
        // overflow 固定存在；always 项优先占位，剩余容量按 XML 顺序分给 ifRoom 项。
        val ifRoomSlots = (
            maxActionButtons - 1 - 1 - if (starVisible) 1 else 0
            ).coerceAtLeast(0)
        val showShareAction = ifRoomSlots >= 1
        val showAloudAction = ifRoomSlots >= 2

        IconButton(onClick = { viewModel.refresh { webView.reload() } }) {
            Icon(
                painter = rememberPainter("ic_refresh_black_24dp"),
                contentDescription = stringResource(R.string.refresh),
                tint = colors.primaryText,
            )
        }
        if (starVisible) {
            IconButton(onClick = { toggleStar() }) {
                Icon(
                    painter = rememberPainter(
                        if (inShelf) "ic_star" else "ic_star_border"
                    ),
                    contentDescription = stringResource(
                        if (inShelf) R.string.in_favorites else R.string.out_favorites
                    ),
                    tint = colors.primaryText,
                )
            }
        }
        if (showShareAction) {
            IconButton(onClick = { shareUrl() }) {
                Icon(
                    painter = rememberPainter("ic_share"),
                    contentDescription = stringResource(R.string.share),
                    tint = colors.primaryText,
                )
            }
        }
        if (showAloudAction) {
            IconButton(onClick = { readAloud() }) {
                Icon(
                    painter = rememberPainter(
                        if (ttsPlaying) "ic_stop_black_24dp" else "ic_volume_up"
                    ),
                    contentDescription = stringResource(
                        if (ttsPlaying) R.string.aloud_stop else R.string.read_aloud
                    ),
                    tint = colors.primaryText,
                )
            }
        }
        OverflowMenu { dismiss ->
            if (!showShareAction) {
                DropdownMenuItem(
                    onClick = {
                        dismiss()
                        shareUrl()
                    },
                ) { Text(stringResource(R.string.share), color = colors.primaryText) }
            }
            if (!showAloudAction) {
                DropdownMenuItem(
                    onClick = {
                        dismiss()
                        readAloud()
                    },
                ) {
                    Text(
                        stringResource(if (ttsPlaying) R.string.aloud_stop else R.string.read_aloud),
                        color = colors.primaryText,
                    )
                }
            }
            if (hasLogin) {
                DropdownMenuItem(
                    onClick = {
                        dismiss()
                        viewModel.curBookSource?.showLoginDialog()
                    },
                ) { Text(stringResource(R.string.login), color = colors.primaryText) }
            }
            DropdownMenuItem(
                onClick = {
                    dismiss()
                    webView.url?.let { openUrl(it) } ?: toastOnUi("url null")
                },
            ) {
                Text(stringResource(R.string.open_in_browser), color = colors.primaryText)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData()
        viewModel.upStarMenuData.observe(this) { upStarMenu() }
        viewModel.upTtsMenuData.observe(this) { ttsPlaying = it }
        pageTitle = viewModel.curBook?.name
        initWebView()
        initLiveData()
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
            finish()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SwitchIntDef")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            Configuration.ORIENTATION_PORTRAIT -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            }
        }
    }

    private fun toggleStar() {
        if (viewModel.inBookshelf) {
            if (AppConfig.bookInfoDeleteAlert) {
                alert(
                    titleResource = R.string.draw, messageResource = R.string.sure_del
                ) {
                    yesButton {
                        viewModel.delBook {
                            setResult(RESULT_DELETED)
                            finish()
                        }
                    }
                    noButton()
                }
            } else {
                viewModel.delBook {
                    setResult(RESULT_DELETED)
                    finish()
                }
            }
        } else {
            viewModel.addToBookshelf {
                setResult(RESULT_OK)
                inShelf = true
            }
        }
    }

    private fun shareUrl() {
        webView.url?.let {
            share(it)
        } ?: viewModel.curBook?.let {
            share(it.tocUrl)
        } ?: toastOnUi(R.string.null_url)
    }

    @JavascriptInterface
    fun isNightTheme(): Boolean {
        return AppConfig.isNightTheme
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun initWebView() {
        chromeClient = CommonWebChromeClient(
            this, progressBar, webContainer, customViewContainer
        )
        webView.webChromeClient = chromeClient
        webView.webViewClient = CustomWebViewClient()
        WebViewUtil.applyCommonSettings(webView.settings)
        webView.addJavascriptInterface(this, "thisActivity")
        WebViewUtil.setupImageLongClick(
            webView, this,
            onSave = { saveImage(it) },
            onSelectFolder = { selectSaveFolder(null) }
        )
        WebViewUtil.setupDownloadListener(webView, this)
    }

    private fun saveImage(webPic: String) {
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder(webPic)
        } else {
            viewModel.saveImage(webPic, path.toUri())
        }
    }

    private fun selectSaveFolder(webPic: String?) {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        selectImageDir.launch {
            otherActions = default
            value = webPic
        }
    }

    private fun initLiveData() {
        viewModel.contentLiveData.observe(this) { (url, html) ->
            webView.settings.userAgentString =
                viewModel.UA ?: AppConfig.userAgent
            webView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
        }
        viewModel.urlLiveData.observe(this) {
            CookieManager.applyToWebView(it.url)
            webView.settings.userAgentString = it.getUserAgent()
            webView.loadUrl(it.url, it.headerMap)
        }
    }

    private fun upStarMenu() {
        starVisible = viewModel.curBook != null
        inShelf = viewModel.inBookshelf
        hasLogin = viewModel.curBookSource?.hasLogin() == true
    }

    private fun readAloud() {
        if (viewModel.tts?.isSpeaking == true) {
            viewModel.tts?.stop()
            ttsPlaying = false
        } else {
            webView.evaluateJavascript("document.documentElement.outerHTML") {
                val html = EscapeUtils.unescapeJson(it)
                    .replace("^\"|\"$".toRegex(), "")
                viewModel.readAloud(
                    Ksoup.parse(html)
                        .textArray()
                        .joinToString("\n")
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }

    inner class CustomWebViewClient : BaseWebViewClient() {

        override fun interceptUrl(url: Uri): Boolean {
            val source = viewModel.curBookSource
            val js = source?.contentRule?.shouldOverrideUrlLoading
            if (!js.isNullOrBlank()) {
                val t = SystemClock.uptimeMillis()
                val result = runCatching {
                    runScriptWithContext(lifecycleScope.coroutineContext) {
                        source.evalJS(js) {
                            put("java", rssJsExtensions)
                            put("url", url.toString())
                        }.toString()
                    }
                }.onFailure {
                    AppLog.put("${source.getTag()}: url跳转拦截js出错", it)
                }.getOrNull()
                if (SystemClock.uptimeMillis() - t > 30) {
                    AppLog.put("${source.getTag()}: url跳转拦截js执行耗时过长")
                }
                if (result.isTrue()) {
                    return true
                }
            }
            if (url.scheme == "jsbridge") return false
            return WebViewUtil.shouldOverrideUrl(url, this@ReadRssActivity)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            view.title?.let { title ->
                if (title != url
                    && title != view.url
                    && title.isNotBlank()
                    && url != "about:blank"
                    && !url.contains(title)
                ) {
                    pageTitle = title
                } else {
                    pageTitle = intent.getStringExtra("title")
                }
            }
            viewModel.curBookSource?.contentRule?.webJs?.let {
                if (it.isNotBlank()) {
                    view.evaluateJavascript(it, null)
                }
            }
        }

    }

}
