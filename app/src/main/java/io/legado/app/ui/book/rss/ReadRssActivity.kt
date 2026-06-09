package io.legado.app.ui.book.rss

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.size
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityWebViewBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.browser.BaseWebViewClient
import io.legado.app.ui.browser.CommonWebChromeClient
import io.legado.app.ui.browser.WebViewUtil
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.utils.ACache
import io.legado.app.utils.EscapeUtils
import io.legado.app.utils.isTrue
import io.legado.app.utils.openUrl
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.setTintMutate
import io.legado.app.utils.share
import io.legado.app.utils.textArray
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import splitties.views.bottomPadding

/**
 * rss阅读界面
 */
class ReadRssActivity : VMBaseActivity<ActivityWebViewBinding, ReadRssViewModel>() {

    override val binding by viewBinding(ActivityWebViewBinding::inflate)
    override val viewModel by viewModels<ReadRssViewModel>()

    private var starMenuItem: MenuItem? = null
    private var ttsMenuItem: MenuItem? = null
    private lateinit var chromeClient: CommonWebChromeClient
    private val selectImageDir = registerHandleFile {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }
    private val rssJsExtensions by lazy { RssJsExtensions(this) }

    fun getSource(): BookSource? {
        return viewModel.curBookSource
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData()
        viewModel.upStarMenuData.observe(this) { upStarMenu() }
        viewModel.upTtsMenuData.observe(this) { upTtsMenu(it) }
        binding.titleBar.title = viewModel.curBook?.name
        initView()
        initWebView()
        initLiveData()
        onBackPressedDispatcher.addCallback(this) {
            if (binding.customWebView.size > 0) {
                chromeClient.customViewCallback?.onCustomViewHidden()
                return@addCallback
            } else if (binding.webView.canGoBack()
                && binding.webView.copyBackForwardList().size > 1
            ) {
                binding.webView.goBack()
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

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_read, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        starMenuItem = menu.findItem(R.id.menu_rss_star)
        ttsMenuItem = menu.findItem(R.id.menu_aloud)
        upStarMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible =
            viewModel.curBookSource?.hasLogin() == true
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_rss_refresh -> viewModel.refresh {
                binding.webView.reload()
            }

            R.id.menu_rss_star -> {
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
                        item.setIcon(R.drawable.ic_star)
                        item.setTitle(R.string.in_favorites)
                    }
                }
            }

            R.id.menu_share_it -> {
                binding.webView.url?.let {
                    share(it)
                } ?: viewModel.curBook?.let {
                    share(it.tocUrl)
                } ?: toastOnUi(R.string.null_url)
            }

            R.id.menu_aloud -> readAloud()
            R.id.menu_login -> viewModel.curBookSource?.showLoginDialog(this)

            R.id.menu_browser_open -> binding.webView.url?.let {
                openUrl(it)
            } ?: toastOnUi("url null")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    @JavascriptInterface
    fun isNightTheme(): Boolean {
        return AppConfig.isNightTheme
    }

    private fun initView() {
        binding.root.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val insets = windowInsets.getInsets(typeMask)
            view.bottomPadding = insets.bottom
            windowInsets
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun initWebView() {
        chromeClient = CommonWebChromeClient(
            this, binding.progressBar, binding.llView, binding.customWebView
        )
        binding.progressBar.fontColor = accentColor
        binding.webView.webChromeClient = chromeClient
        binding.webView.webViewClient = CustomWebViewClient()
        WebViewUtil.applyCommonSettings(binding.webView.settings)
        binding.webView.addJavascriptInterface(this, "thisActivity")
        WebViewUtil.setupImageLongClick(
            binding.webView, this,
            onSave = { saveImage(it) },
            onSelectFolder = { selectSaveFolder(null) }
        )
        WebViewUtil.setupDownloadListener(binding.webView, binding.llView, this)

    }

    private fun saveImage(webPic: String) {
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder(webPic)
        } else {
            viewModel.saveImage(webPic, Uri.parse(path))
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
            binding.webView.settings.userAgentString =
                viewModel.UA ?: AppConfig.userAgent
            binding.webView
                .loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
        }
        viewModel.urlLiveData.observe(this) {
            CookieManager.applyToWebView(it.url)
            binding.webView.settings.userAgentString = it.getUserAgent()
            binding.webView.loadUrl(it.url, it.headerMap)
        }
    }

    private fun upStarMenu() {
        starMenuItem?.isVisible = viewModel.curBook != null
        if (viewModel.inBookshelf) {
            starMenuItem?.setIcon(R.drawable.ic_star)
            starMenuItem?.setTitle(R.string.in_favorites)
        } else {
            starMenuItem?.setIcon(R.drawable.ic_star_border)
            starMenuItem?.setTitle(R.string.out_favorites)
        }
        starMenuItem?.icon?.setTintMutate(primaryTextColor)
    }

    private fun upTtsMenu(isPlaying: Boolean) {
        lifecycleScope.launch {
            if (isPlaying) {
                ttsMenuItem?.setIcon(R.drawable.ic_stop_black_24dp)
                ttsMenuItem?.setTitle(R.string.aloud_stop)
            } else {
                ttsMenuItem?.setIcon(R.drawable.ic_volume_up)
                ttsMenuItem?.setTitle(R.string.read_aloud)
            }
            ttsMenuItem?.icon?.setTintMutate(primaryTextColor)
        }
    }

    private fun readAloud() {
        if (viewModel.tts?.isSpeaking == true) {
            viewModel.tts?.stop()
            upTtsMenu(false)
        } else {
            binding.webView.evaluateJavascript("document.documentElement.outerHTML") {
                val html = EscapeUtils.unescapeJson(it)
                    .replace("^\"|\"$".toRegex(), "")
                viewModel.readAloud(
                    Jsoup.parse(html)
                        .textArray()
                        .joinToString("\n")
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.webView.destroy()
    }

    inner class CustomWebViewClient : BaseWebViewClient() {

        override fun interceptUrl(url: Uri): Boolean {
            val source = viewModel.curBookSource
            val js = source?.ruleContent?.shouldOverrideUrlLoading
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
            return WebViewUtil.shouldOverrideUrl(url, this@ReadRssActivity, binding.root)
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
                    binding.titleBar.title = title
                } else {
                    binding.titleBar.title = intent.getStringExtra("title")
                }
            }
            viewModel.curBookSource?.ruleContent?.webJs?.let {
                if (it.isNotBlank()) {
                    view.evaluateJavascript(it, null)
                }
            }
        }

    }

}
