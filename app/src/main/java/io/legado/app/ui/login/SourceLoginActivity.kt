package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.viewModels
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.databinding.ActivityWebViewBinding
import io.legado.app.help.http.CookieStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.gone
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.snackbar
import io.legado.app.utils.viewbindingdelegate.viewBinding

class SourceLoginActivity : VMBaseActivity<ActivityWebViewBinding, SourceLoginViewModel>() {

    override val binding by viewBinding(ActivityWebViewBinding::inflate)
    override val viewModel by viewModels<SourceLoginViewModel>()

    private var checking = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData(success = { source ->
            binding.titleBar.title = getString(R.string.login_source, source.getTag())
            initWebView(source)
        }, error = {
            finish()
        })
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.source_webview_login, menu)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_ok && !checking) {
            checking = true
            binding.titleBar.snackbar(R.string.check_host_cookie)
            viewModel.source?.let { loadUrl(it) }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(source: io.legado.app.data.entities.BaseSource) {
        binding.progressBar.fontColor = accentColor
        binding.webView.settings.apply {
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            javaScriptEnabled = true
            displayZoomControls = false
            viewModel.headerMap[AppConst.UA_NAME]?.let { userAgentString = it }
        }
        val cookieManager = CookieManager.getInstance()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                cookieManager.getCookie(url)?.let { CookieStore.setCookie(source.getKey(), it) }
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                cookieManager.getCookie(url)?.let { CookieStore.setCookie(source.getKey(), it) }
                if (checking) finish()
                super.onPageFinished(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return request?.url?.let { shouldOverrideUrlLoading(it) } ?: true
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return url?.let { shouldOverrideUrlLoading(Uri.parse(it)) } ?: true
            }

            private fun shouldOverrideUrlLoading(url: Uri): Boolean {
                return when (url.scheme) {
                    "http", "https" -> false
                    else -> {
                        binding.root.longSnackbar(
                            R.string.jump_to_another_app,
                            R.string.confirm
                        ) { openUrl(url) }
                        true
                    }
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.setDurProgress(newProgress)
                binding.progressBar.gone(newProgress == 100)
            }
        }
        loadUrl(source)
    }

    private fun loadUrl(source: io.legado.app.data.entities.BaseSource) {
        source.loginUrl?.let { loginUrl ->
            val absoluteUrl = NetworkUtils.getAbsoluteURL(source.getKey(), loginUrl)
            binding.webView.loadUrl(absoluteUrl, viewModel.headerMap)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.webView.destroy()
    }
}
