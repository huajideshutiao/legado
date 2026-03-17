@file:Keep
@file:Suppress("DEPRECATION")

package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.DebugLog
import io.legado.app.utils.externalCache
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import org.chromium.net.CronetEngine.Builder.HTTP_CACHE_DISK
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.chromium.net.X509Util
import org.json.JSONObject
import android.os.Build
import splitties.init.appCtx

internal const val BUFFER_SIZE = 32 * 1024

private var cronetEngineCache: ExperimentalCronetEngine? = null
private var cronetEngineInitialized = false

val cronetEngine: ExperimentalCronetEngine?
    get() {
        if (cronetEngineInitialized) {
            return cronetEngineCache
        }
        synchronized(appCtx) {
            if (cronetEngineInitialized) {
                return cronetEngineCache
            }
            cronetEngineInitialized = true
            cronetEngineCache = createCronetEngine()
            return cronetEngineCache
        }
    }

private fun createCronetEngine(): ExperimentalCronetEngine? {
    disableCertificateVerify()
    val builder = ExperimentalCronetEngine.Builder(appCtx).apply {
        setStoragePath(appCtx.externalCache.absolutePath)
        enableHttpCache(HTTP_CACHE_DISK, (1024 * 1024 * 50).toLong())
        enableQuic(true)
        enableHttp2(true)
        enablePublicKeyPinningBypassForLocalTrustAnchors(true)
        enableBrotli(true)
        setExperimentalOptions(options)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val engine = builder.build()
            DebugLog.d("Cronet Version (System):", engine.versionString)
            return engine
        } catch (e: Throwable) {
            DebugLog.d("Cronet", "System cronet not available: ${e.message}")
        }
    }
    CronetLoader.preDownload()
    if (CronetLoader.install()) {
        builder.setLibraryLoader(CronetLoader)
        try {
            val engine = builder.build()
            DebugLog.d("Cronet Version (Downloaded):", engine.versionString)
            return engine
        } catch (e: Throwable) {
            AppLog.put("初始化cronetEngine出错", e)
        }
    }
    return null
}

val options by lazy {
    val options = JSONObject()

    //设置域名映射规则
    //MAP hostname ip,MAP hostname ip
//    val host = JSONObject()
//    host.put("host_resolver_rules","")
//    options.put("HostResolverRules", host)

    //启用DnsHttpsSvcb更容易迁移到http3
    val dnsSvcb = JSONObject()
    dnsSvcb.put("enable", true)
    dnsSvcb.put("enable_insecure", true)
    dnsSvcb.put("use_alpn", true)
    options.put("UseDnsHttpsSvcb", dnsSvcb)

    options.put("AsyncDNS", JSONObject("{'enable':true}"))


    options.toString()
}

fun buildRequest(request: Request, callback: UrlRequest.Callback): UrlRequest? {
    val url = request.url.toString()
    val headers: Headers = request.headers
    val requestBody = request.body
    return cronetEngine?.newUrlRequestBuilder(
        url,
        callback,
        okHttpClient.dispatcher.executorService
    )?.apply {
        setHttpMethod(request.method)//设置
        allowDirectExecutor()
        headers.forEachIndexed { index, _ ->
            if (headers.name(index) == cookieJarHeader) return@forEachIndexed
            addHeader(headers.name(index), headers.value(index))
        }
        if (requestBody != null) {
            val contentType: MediaType? = requestBody.contentType()
            if (contentType != null) {
                addHeader("Content-Type", contentType.toString())
            } else {
                addHeader("Content-Type", "text/plain")
            }
            val provider: UploadDataProvider = if (requestBody.contentLength() > BUFFER_SIZE) {
                LargeBodyUploadProvider(requestBody, okHttpClient.dispatcher.executorService)
            } else {
                BodyUploadProvider(requestBody)
            }
            provider.use {
                this.setUploadDataProvider(it, okHttpClient.dispatcher.executorService)
            }

        }

    }?.build()

}

private fun disableCertificateVerify() {
    runCatching {
        val sDefaultTrustManager = X509Util::class.java.getDeclaredField("sDefaultTrustManager")
        sDefaultTrustManager.isAccessible = true
        sDefaultTrustManager.set(null, SSLHelper.unsafeTrustManagerExtensions)
    }
    runCatching {
        val sTestTrustManager = X509Util::class.java.getDeclaredField("sTestTrustManager")
        sTestTrustManager.isAccessible = true
        sTestTrustManager.set(null, SSLHelper.unsafeTrustManagerExtensions)
    }
}
