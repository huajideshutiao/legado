package io.legado.app.help.tvbox

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppLog
import io.legado.app.utils.NetworkUtils
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * TVBox JS spider 宿主门面 (Kotlin 侧, 被 TvBoxJsApi.js 的全局函数反向调用)。
 *
 * 契约: JS 侧一律 `__host.call(method, jsonArgsArray)`, 本类按 method 分派,
 * 参数从 JSON 数组取, 返回值一律为字符串 (结构化结果回 JSON)。
 * 全字符串出入参是为了绕开 JS 对象跨 JNI 边界的类型歧义 (句柄/Map/JavaObject 不定)。
 *
 * 逐条对齐 FongMi/TV fongmi 分支 (源码路径见随附 README/汇报):
 * - Global.java  @JSMethod: req/_http/joinUrl/md5X/aesX/desX/rsaX/getProxy/js2Proxy
 *   /getPort/setTimeout/clearTimeout/s2t/t2s
 * - Local.java   @JSMethod: local.get/set/delete
 * - utils/Connect.java + utils/Module.java: HTTP 语义与模块取源
 *
 * 差异 (相对 FongMi 的 complete 回调异步路径): HTTP 一律阻塞同步执行。宿主已实现
 * Promise 泵送 (QuickJsAsync.settleAndUnwrap → QuickJsNative.nativePumpJobs), 异步语义
 * 本身可用; 但 spider 取数要求同步拿到终值, 故走阻塞式 OkHttp 而非 FongMi 的异步回调。
 */
class TvBoxJsBridge internal constructor(
    private val fetcher: TvBoxJsSourceFetcher,
    private val siteKey: String,
    private val siteType: Int,
) {

    /** 供 JS 反射调用: (method, jsonArgs) -> String。 */
    @Suppress("unused")
    fun call(method: String, jsonArgs: String): String {
        val args = jsonArgsArgs(jsonArgs)
        return try {
            when (method) {
                "req" -> req(args.arg(0), args.arg(1))
                "fetch" -> fetcher.fetch(args.arg(0), args.arg(1))
                "joinUrl" -> joinUrl(args.arg(0), args.arg(1))
                "md5" -> com.github.catvod.utils.Crypto.md5(args.arg(0))
                "aes" -> crypto("aes", args)
                "des" -> crypto("des", args)
                "rsa" -> rsaX(args)
                "getProxy" -> getProxy(args.arg(0) == "1")
                "getPort" -> TvBoxJsProxy.port.toString()
                "js2Proxy" -> js2Proxy(args)
                "s2t" -> args.arg(0)
                "t2s" -> args.arg(0)
                "localGet" -> TvBoxJsLocal.get(args.arg(0), args.arg(1))
                "localSet" -> {
                    TvBoxJsLocal.set(args.arg(0), args.arg(1), args.arg(2))
                    ""
                }
                "localDelete" -> {
                    TvBoxJsLocal.delete(args.arg(0), args.arg(1))
                    ""
                }
                "log" -> {
                    log(args.arg(0))
                    ""
                }
                else -> throw UnsupportedOperationException("TVBox JS 宿主未实现: $method")
            }
        } catch (t: Throwable) {
            if (t is UnsupportedOperationException) throw t
            AppLog.put("TVBox JS 宿主调用失败 $method", t)
            throw t
        }
    }

    // ============ HTTP (对齐 FongMi utils/Connect + bean/Req) ============

    /**
     * 阻塞式 HTTP, 返回 JSON `{code, headers, content}`。
     *
     * options 面 (FongMi bean/Req.json): method/headers/body/data/postType/timeout
     * /redirect/buffer。buffer: 0=文本(按 charset 解码) 1=字节数组 2=base64 3=字节(序列化为数组)。
     */
    private fun req(url: String, optionsJson: String): String {
        val options = optionsJson.trim().takeIf { it.startsWith("{") }?.let { JSONObject(it) }
            ?: JSONObject()
        val timeout = options.optLong("timeout", 10000L).let { if (it <= 0) 10000L else it }
        val redirect = options.optInt("redirect", 1) == 1
        val buffer = options.optInt("buffer", 0)
        val client: OkHttpClient = if (redirect) {
            timeoutClient(timeout)
        } else {
            timeoutClient(timeout).newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
        val headers = headersOf(options.opt("headers"))
        val method = options.optString("method").ifBlank { "get" }.lowercase(Locale.ROOT)
        // post/put 共用请求体构造 (FongMi bean/Req: body 与 method 无关)
        val body = if (method == "post" || method == "put") requestBody(options, headers) else null
        val builder = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }
        when (method) {
            "post" -> builder.post(body ?: ByteArray(0).toRequestBody(null))
            "header" -> builder.head()
            "put" -> builder.put(body ?: ByteArray(0).toRequestBody(null))
            "delete" -> builder.delete()
            else -> builder.get()
        }
        val call = client.newCall(builder.build())
        return call.execute().use { response -> responseJson(response, options, buffer) }
    }

    private fun responseJson(response: Response, options: JSONObject, buffer: Int): String {
        val bytes = response.body.bytes()
        val headers = JSONObject()
        for ((key, values) in response.headers.toMultimap()) {
            headers.put(key, if (values.size == 1) values[0] else JSONArray(values))
        }
        val root = JSONObject()
            .put("code", response.code)
            .put("headers", headers)
            .put("url", response.request.url.toString())
        when (buffer) {
            1 -> root.put("content", JSONArray().apply { bytes.forEach { put(it.toInt() and 0xFF) } })
            2 -> root.put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            3 -> root.put("content", JSONArray().apply { bytes.forEach { put(it.toInt() and 0xFF) } })
            else -> root.put("content", String(bytes, charsetOf(options, headers)))
        }
        return root.toString()
    }

    /** charset 按 Content-Type 或请求头推断 (FongMi bean/Req.getCharset 同语义)。 */
    private fun charsetOf(options: JSONObject, responseHeaders: JSONObject): java.nio.charset.Charset {
        val candidates = listOf(
            responseHeaders.optString("Content-Type"),
            responseHeaders.optString("content-type"),
            options.opt("headers")?.let { headersOf(it)["Content-Type"] }.orEmpty(),
        )
        for (value in candidates) {
            for (part in value.split(";")) {
                val trimmed = part.trim()
                if (trimmed.startsWith("charset=", ignoreCase = true)) {
                    return runCatching { java.nio.charset.Charset.forName(trimmed.substring(8).trim()) }
                        .getOrDefault(Charsets.UTF_8)
                }
            }
        }
        return Charsets.UTF_8
    }

    private fun requestBody(options: JSONObject, headers: Map<String, String>): RequestBody? {
        val data = options.opt("data")
        val postType = options.optString("postType").ifBlank { "json" }
        val raw = options.optString("body")
        if (data == null || data === JSONObject.NULL) {
            val contentType = headers.entries.firstOrNull {
                it.key.equals("Content-Type", true)
            }?.value
            return raw.toRequestBody(contentType?.toMediaTypeOrNull())
        }
        return when (postType) {
            "form" -> FormBody.Builder().apply {
                for ((key, value) in flatMapOf(data)) add(key, value ?: "")
            }.build()
            "form-data" -> okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM).apply {
                for ((key, value) in flatMapOf(data)) addFormDataPart(key, value ?: "")
            }.build()
            else -> data.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        }
    }

    /** headers 可为对象或 JSON 字符串; 值一律取字符串形态。 */
    private fun headersOf(raw: Any?): Map<String, String> {
        val obj = when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            val value = obj.opt(key)?.takeIf { it !== JSONObject.NULL }?.toString().orEmpty()
            if (value.isNotEmpty()) map[key] = value
        }
        return map
    }

    private fun flatMapOf(raw: Any): Map<String, String?> {
        val map = LinkedHashMap<String, String?>()
        when (raw) {
            is JSONObject -> for (key in raw.keys()) {
                map[key] = raw.opt(key)?.takeIf { it !== JSONObject.NULL }?.toString()
            }
            is Map<*, *> -> raw.forEach { (k, v) -> map[k.toString()] = v?.toString() }
            else -> runCatching {
                Gson().fromJson<Map<String, Any?>>(
                    raw.toString(),
                    object : TypeToken<Map<String, Any?>>() {}.type,
                )
            }.getOrNull()?.let { it.forEach { (k, v) -> map[k] = v?.toString() } }
        }
        return map
    }

    private fun timeoutClient(timeoutMs: Long): OkHttpClient =
        com.github.catvod.net.OkHttp.client().newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

    // ============ 其余全局函数 ============

    private fun joinUrl(parent: String, child: String): String =
        NetworkUtils.getAbsoluteURL(parent, child)

    /**
     * aesX/desX/rsaX: 参数面 (mode, encrypt, input, inBase64, key, iv, outBase64)
     * 对齐 FongMi Crypto.aes/des; rsa 的第 2 参为 pub 位 (非 encrypt), 走 [rsaX] 专属签名。
     */
    private fun crypto(kind: String, args: JsonArgs): String {
        val mode = args.arg(0).ifBlank { "CBC" }
        val encrypt = args.arg(1) == "1"
        val input = args.arg(2)
        val inBase64 = args.arg(3) == "1"
        val key = args.arg(4)
        val iv = args.arg(5)
        val outBase64 = args.arg(6) == "1"
        return when (kind) {
            "aes" -> com.github.catvod.utils.Crypto.aes(mode, encrypt, input, inBase64, key, iv, outBase64)
            "des" -> com.github.catvod.utils.Crypto.des(mode, encrypt, input, inBase64, key, iv, outBase64)
            else -> com.github.catvod.utils.Crypto.rsa(mode, encrypt, true, input, inBase64, key, outBase64)
        }
    }

    /** rsaX(mode, pub, encrypt, input, inBase64, key, outBase64) —— pub/encrypt 两位分立。 */
    private fun rsaX(args: JsonArgs): String = com.github.catvod.utils.Crypto.rsa(
        args.arg(0).ifBlank { "RSA/ECB/PKCS1Padding" },
        args.arg(1) == "1",
        args.arg(2) == "1",
        args.arg(3),
        args.arg(4) == "1",
        args.arg(5),
        args.arg(6) == "1",
    )

    private fun getProxy(isLocal: Boolean): String {
        val url = TvBoxJsProxy.url(isLocal)
        return if (url.isBlank()) "" else "$url?do=js"
    }

    /** js2Proxy(dynamic, siteType, siteKey, url, headersJson)。 */
    private fun js2Proxy(args: JsonArgs): String {
        val dynamic = args.arg(0) == "1"
        val type = args.arg(1).ifBlank { siteType.toString() }
        val key = args.arg(2).ifBlank { siteKey }
        val url = args.arg(3)
        val headers = args.arg(4)
        val base = getProxy(!dynamic)
        if (base.isBlank()) return ""
        return base + String.format(
            Locale.ROOT,
            "&from=catvod&siteType=%s&siteKey=%s&header=%s&url=%s",
            type,
            java.net.URLEncoder.encode(key, "UTF-8"),
            java.net.URLEncoder.encode(headers, "UTF-8"),
            java.net.URLEncoder.encode(url, "UTF-8"),
        )
    }

    private fun log(message: String) {
        val line = message.replace('\n', ' ').take(2000)
        android.util.Log.d("TvBoxJs", line)
        AppLog.put("TVBox JS: $line")
    }

    private fun jsonArgsArgs(json: String): JsonArgs {
        val raw = runCatching { JSONArray(json) }.getOrNull() ?: JSONArray()
        return JsonArgs(raw)
    }

    /** JSON 数组参数访问器: 越界/非串一律空串 (JS 侧常传 undefined→'')。 */
    private class JsonArgs(val raw: JSONArray) {
        fun arg(index: Int): String =
            raw.opt(index)?.takeIf { it !== JSONObject.NULL }?.toString().orEmpty()
    }

    companion object {
        /** 本地文件读取 (file:// / 裸路径 → 文本); 供取源器落盘缓存复用。 */
        fun readLocalFile(path: String): String? {
            val file = File(path.replace("file://", ""))
            if (!file.isFile) return null
            return runCatching { file.readText() }.getOrNull()
        }
    }
}

/**
 * JS 侧模块取源接口 (阻塞 IO): 由 [TvBoxJsSpiderLoader] 实现,
 * 负责 http(s) 下载 (带缓存) 与 assets:// 宿主页解析。
 */
fun interface TvBoxJsSourceFetcher {
    fun fetch(name: String, base: String): String
}

/**
 * 本地代理地址供应点: 由 [TvBoxLocalProxy.start] 接线。
 * 未接线时返回空串, JS 侧 getProxy/js2Proxy 得到空值 (解析页链路走遗留豁口, 不影响直链)。
 */
object TvBoxJsProxy {
    @Volatile
    var port: Int = 0

    @Volatile
    var urlProvider: ((Boolean) -> String)? = null

    fun url(isLocal: Boolean): String = urlProvider?.invoke(isLocal).orEmpty()
}

/** JS local.* 的持久化 (对齐 FongMi method/Local: 站点级 k/v 存 SharedPreferences)。 */
internal object TvBoxJsLocal {

    private const val PREF = "tvbox_js_local"

    private fun prefs() = com.github.catvod.Init.context()!!
        .getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)

    private fun keyOf(rule: String, key: String): String =
        "cache_" + (if (rule.isEmpty()) "" else rule + "_") + key

    @Synchronized
    fun get(rule: String, key: String): String = prefs().getString(keyOf(rule, key), "").orEmpty()

    @Synchronized
    fun set(rule: String, key: String, value: String) {
        prefs().edit().putString(keyOf(rule, key), value).apply()
    }

    @Synchronized
    fun delete(rule: String, key: String) {
        prefs().edit().remove(keyOf(rule, key)).apply()
    }
}
