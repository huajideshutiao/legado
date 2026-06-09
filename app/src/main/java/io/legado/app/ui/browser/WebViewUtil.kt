package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.fragment.app.commit
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.Download
import io.legado.app.ui.association.FileAssociationFragment
import io.legado.app.ui.widget.anima.RefreshProgressBar
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.setDarkeningAllowed
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.visible
import java.net.URLDecoder

class CommonWebChromeClient(
    private val activity: Activity,
    private val progressBar: RefreshProgressBar,
    private val llView: ConstraintLayout,
    private val customWebView: FrameLayout,
    private val onCloseWindow: ((WebView?) -> Unit)? = null
) : WebChromeClient() {

    var customViewCallback: CustomViewCallback? = null

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        progressBar.setDurProgress(newProgress)
        progressBar.gone(newProgress == 100)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        llView.invisible()
        customWebView.addView(view)
        customViewCallback = callback
        activity.keepScreenOn(true)
        activity.toggleSystemBar(false)
    }

    override fun onHideCustomView() {
        customWebView.removeAllViews()
        llView.visible()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity.keepScreenOn(false)
        activity.toggleSystemBar(true)
    }

    override fun onCloseWindow(window: WebView?) {
        if (onCloseWindow != null) {
            onCloseWindow.invoke(window)
        } else {
            super.onCloseWindow(window)
        }
    }
}

abstract class BaseWebViewClient : WebViewClient() {

    abstract fun interceptUrl(url: Uri): Boolean

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        return interceptUrl(request.url)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return interceptUrl(url.toUri())
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

object WebViewUtil {

    @SuppressLint("SetJavaScriptEnabled")
    fun applyCommonSettings(settings: WebSettings) {
        settings.apply {
            javaScriptEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            domStorageEnabled = true
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            setDarkeningAllowed(AppConfig.isNightTheme)
        }
    }

    fun setupDownloadListener(webView: WebView, hostView: View, activity: AppCompatActivity) {
        webView.setDownloadListener { url, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            hostView.longSnackbar(fileName, activity.getString(R.string.action_download)) {
                Download.start(activity, url, fileName)
            }
        }
    }

    fun shouldOverrideUrl(url: Uri, activity: AppCompatActivity, snackbarHost: View): Boolean {
        when (url.scheme) {
            "http", "https" -> return false
            "legado", "yuedu" -> {
                activity.supportFragmentManager.commit {
                    add(FileAssociationFragment(url), "FileAssociationFragment")
                }
                return true
            }

            else -> {
                snackbarHost.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                    activity.openUrl(url)
                }
                return true
            }
        }
    }

    fun setupImageLongClick(
        webView: WebView,
        activity: AppCompatActivity,
        onSave: (String) -> Unit,
        onSelectFolder: () -> Unit
    ) {
        webView.setOnLongClickListener {
            val hitTestResult = webView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                hitTestResult.extra?.let { webPic ->
                    val items: List<SelectItem<String>> = arrayListOf(
                        SelectItem(activity.getString(R.string.action_save), "save"),
                        SelectItem(activity.getString(R.string.select_folder), "selectFolder")
                    )
                    activity.selector(items) { _, item, _ ->
                        when (item.value) {
                            "save" -> onSave(webPic)
                            "selectFolder" -> onSelectFolder()
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
    }
}