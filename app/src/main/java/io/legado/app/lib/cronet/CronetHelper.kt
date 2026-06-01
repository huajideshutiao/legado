@file:Keep @file:Suppress("DEPRECATION")

package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.LogUtils
import io.legado.app.utils.externalCache
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import org.chromium.net.CronetEngine.Builder.HTTP_CACHE_DISK
import org.chromium.net.CronetProvider
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.json.JSONObject
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
    runCatching {
        val x509UtilClass = Class.forName("org.chromium.net.impl.X509Util")
        val sDefaultTrustManager = x509UtilClass.getDeclaredField("sDefaultTrustManager")
        sDefaultTrustManager.isAccessible = true
        sDefaultTrustManager.set(null, SSLHelper.unsafeTrustManagerExtensions)
        val sTestTrustManager = x509UtilClass.getDeclaredField("sTestTrustManager")
        sTestTrustManager.isAccessible = true
        sTestTrustManager.set(null, SSLHelper.unsafeTrustManagerExtensions)
    }.onFailure {
        LogUtils.d("Cronet", "Failed to disable cert verify: ${it.message}")
    }

    val providers = CronetProvider.getAllProviders(appCtx)

    // 1. 优先尝试系统原生 HttpEngine (Android 14+) 或其他外部 Provider (GMS 等)
    providers.find {
        it.name != CronetProvider.PROVIDER_NAME_APP_PACKAGED &&
            it.name != CronetProvider.PROVIDER_NAME_FALLBACK &&
            it.isEnabled
    }?.let { provider ->
        try {
            val builder = provider.createBuilder() as ExperimentalCronetEngine.Builder
            builder.applyConfig()
            // 外部引擎严禁调用 setLibraryLoader
            val engine = builder.build()
            LogUtils.d("Cronet", "Using External Provider: ${provider.name}")
            return engine
        } catch (e: Throwable) {
            LogUtils.d("Cronet", "External Provider ${provider.name} init failed: ${e.message}")
        }
    }

    // 2. 尝试使用下载的 146 SO
    if (CronetLoader.isSoDownloaded()) {
        providers.find { it.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED }?.let { provider ->
            try {
                val builder = provider.createBuilder() as ExperimentalCronetEngine.Builder
                builder.applyConfig()
                builder.setLibraryLoader(CronetLoader)
                val engine = builder.build()
                LogUtils.d("Cronet", "Using Downloaded 146 SO")
                return engine
            } catch (e: Throwable) {
                LogUtils.d("Cronet", "Downloaded SO build failed: ${e.message}")
            }
        }
    }

    // 3. 既无系统支持也无 SO，触发预下载
    CronetLoader.preDownload(null)
    return null
}

private fun ExperimentalCronetEngine.Builder.applyConfig() {
    setStoragePath(appCtx.externalCache.absolutePath)
    enableHttpCache(HTTP_CACHE_DISK, (1024 * 1024 * 50).toLong())
    enableQuic(true)
    enableHttp2(true)
    enablePublicKeyPinningBypassForLocalTrustAnchors(true)
    enableBrotli(true)
    setExperimentalOptions(options)
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
        url, callback, okHttpClient.dispatcher.executorService
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

