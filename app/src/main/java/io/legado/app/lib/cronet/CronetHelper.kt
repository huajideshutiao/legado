@file:Keep @file:Suppress("DEPRECATION")

package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.help.config.AppConfig
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
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

internal const val BUFFER_SIZE = 32 * 1024

internal const val GMS_PROVIDER_NAME = "Google-Play-Services-Cronet-Provider"

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

fun resetCronetEngine() {
    synchronized(appCtx) {
        cronetEngineCache = null
        cronetEngineInitialized = false
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

    // 1. 优先使用 GMS Cronet Provider（功能完整），忽略系统 HttpEngine
    providers.find { it.name == GMS_PROVIDER_NAME && it.isEnabled }?.let { provider ->
        try {
            val builder = provider.createBuilder() as ExperimentalCronetEngine.Builder
            builder.applyConfig()
            // 外部引擎严禁调用 setLibraryLoader
            val engine = builder.build()
            LogUtils.d("Cronet", "Using GMS Provider: ${provider.name}")
            return engine
        } catch (e: Throwable) {
            LogUtils.d("Cronet", "GMS Provider init failed: ${e.message}")
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

    // 3. 既无 GMS 也无 SO，触发预下载
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
    setExperimentalOptions(buildExperimentalOptions())
}

private fun buildExperimentalOptions(): String {
    val options = JSONObject()

    // 1. 强制激活内置 DNS 栈 (DoH 和 ECH 的前提)
    // 覆盖了顶层、AsyncDNS 内部以及 DnsConfig 内部，确保在各种版本的 Cronet 中强制启用
    options.put("enable_built_in_dns", true)
    val asyncDns = JSONObject()
    asyncDns.put("enable", true)
    asyncDns.put("enable_built_in_dns", true)
    options.put("AsyncDNS", asyncDns)
    options.put("DnsConfig", JSONObject().put("enable_built_in_dns", true))

    // 2. 启用 HTTPS 记录支持 (SVCB/HTTPS)，这是获取 ECHConfig 的唯一途径
    val svcb = JSONObject().put("enable", true)
    options.put("UseDnsHttpsSvcb", svcb)
    options.put("HTTPSRR", svcb) // 兼容旧版本
    asyncDns.put("UseDnsHttpsSvcb", true)

    // 3. 显式开启 ECH (Encrypted Client Hello)
    options.put("EncryptedClientHello", JSONObject().put("enable", true))
    options.put("ECH", JSONObject().put("enable", true)) // 备用 Key

    // 4. QUIC 配置 (完全同步 Shaft，用于绕过 SNI 干扰)
    val quic = JSONObject()
    quic.put("force_quic_on_all_ips", true)
    quic.put("race_cert_verification", true)
    quic.put("connection_options", "PACE,IW10,CHLO,BBR2")
    quic.put("enable_ech", true) // 增加 QUIC 层的 ECH 支持（如果存在）
    options.put("quic", quic)

    if (AppConfig.isCronetCfDoh) {
        // 5. DoH 配置 (SECURE 模式强制走加密，不回退)
        val dohOverrides = JSONObject()
        val servers = JSONArray()

        // 模板 1: 域名形式 (配合下面的 HostResolverRules 引导)
        servers.put(
            JSONObject()
                .put("server_template", "https://cloudflare-dns.com/dns-query")
                .put("use_post", false)
        )
        // 模板 2: IP 直接访问 (双重保险)
        servers.put(
            JSONObject()
                .put("server_template", "https://1.1.1.1/dns-query")
                .put("use_post", false)
        )
        dohOverrides.put("dns_over_https_servers", servers)
        dohOverrides.put("secure_dns_mode", "SECURE")
        options.put("DnsConfigOverrides", dohOverrides)

        // 6. 域名映射规则 (抄自 Shaft，解决解析 DoH 服务器域名本身的“循环依赖”问题)
        // 将 DoH 域名直接指向通畅的 CF 节点，确保 DoH 握手时不依赖外部 DNS
        val hostRules = JSONObject()
        hostRules.put(
            "host_resolver_rules",
            "MAP cloudflare-dns.com 104.18.42.239, MAP cloudflare-dns.com 172.64.145.17, MAP cloudflare-dns.com 1.1.1.1, MAP cloudflare-dns.com 1.0.0.1"
        )
        options.put("HostResolverRules", hostRules)
    }

    return options.toString()
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

