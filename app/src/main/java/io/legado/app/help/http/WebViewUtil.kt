// Copyright The Mihon Authors. Apache-2.0.
// 移植自 mihon core/common .../util/system/WebViewUtil.kt, 仅保留 Cloudflare 挑战路径所需部分
package io.legado.app.help.http

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.legado.app.utils.LogUtils

object WebViewUtil {

    // 与 mihon main 一致; 低于此版本的 WebView 无法自行完成 JS 挑战
    const val MINIMUM_WEBVIEW_VERSION = 118

    fun supportsWebView(context: Context): Boolean {
        try {
            // WebView 未安装时可能抛 WebViewFactory$MissingWebViewPackageException
            CookieManager.getInstance()
        } catch (_: Throwable) {
            return false
        }

        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
    }
}

fun WebView.isOutdated(): Boolean {
    return getWebViewMajorVersion() < WebViewUtil.MINIMUM_WEBVIEW_VERSION
}

@SuppressLint("SetJavaScriptEnabled")
fun WebView.setDefaultSettings() {
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        cacheMode = WebSettings.LOAD_DEFAULT

        // 挑战页 iframe/弹窗需与主页面共享会话
        setSupportMultipleWindows(true)

        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }

    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

/**
 * 设置 UA 时同步 Sec-CH-UA client hints; 否则 hints 仍暴露真实 WebView 版本,
 * 与伪造 UA 矛盾时 Cloudflare 可能判定环境异常。
 */
fun WebView.setUserAgent(userAgent: String) {
    settings.userAgentString = userAgent

    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return

    val versionMatch = CHROME_VERSION_REGEX.find(userAgent) ?: return
    val majorVersion = versionMatch.groupValues[1]
    val fullVersion = majorVersion + versionMatch.groupValues[2].ifEmpty { ".0.0.0" }

    try {
        val metadata = WebSettingsCompat.getUserAgentMetadata(settings)
        val brandVersionList = metadata.brandVersionList.map { brandVersion ->
            val brand = when (brandVersion.brand) {
                WEBVIEW_BRAND -> CHROME_BRAND
                CHROMIUM_BRAND -> CHROMIUM_BRAND
                else -> return@map brandVersion
            }

            UserAgentMetadata.BrandVersion.Builder()
                .setBrand(brand)
                .setMajorVersion(majorVersion)
                .setFullVersion(fullVersion)
                .build()
        }

        WebSettingsCompat.setUserAgentMetadata(
            settings,
            UserAgentMetadata.Builder(metadata)
                .setBrandVersionList(brandVersionList)
                .setFullVersion(fullVersion)
                .build(),
        )
    } catch (e: Exception) {
        LogUtils.e(TAG, "setUserAgentMetadata failed: $e")
    }
}

private const val TAG = "WebViewUtil"
private const val WEBVIEW_BRAND = "Android WebView"
private const val CHROMIUM_BRAND = "Chromium"
private const val CHROME_BRAND = "Google Chrome"
private val CHROME_VERSION_REGEX = """Chrome/(\d+)(\.[\d.]+)?""".toRegex()

private fun WebView.getWebViewMajorVersion(): Int {
    val uaRegexMatch = """.*Chrome/(\d+)\..*""".toRegex().matchEntire(getDefaultUserAgentString())
    return if (uaRegexMatch != null && uaRegexMatch.groupValues.size > 1) {
        uaRegexMatch.groupValues[1].toInt()
    } else {
        0
    }
}

// Based on https://stackoverflow.com/a/29218966
private fun WebView.getDefaultUserAgentString(): String {
    val originalUA: String = settings.userAgentString

    // 置空后下次读取即返回系统默认 UA
    settings.userAgentString = null
    val defaultUserAgentString = settings.userAgentString

    // 还原原 UA
    settings.userAgentString = originalUA

    return defaultUserAgentString
}
