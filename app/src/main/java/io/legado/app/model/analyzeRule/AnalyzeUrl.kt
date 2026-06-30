package io.legado.app.model.analyzeRule

import android.annotation.SuppressLint
import android.util.Base64
import androidx.annotation.Keep
import androidx.media3.common.MediaItem
import cn.hutool.core.codec.PercentCodec
import cn.hutool.core.net.RFC3986
import com.bumptech.glide.load.model.GlideUrl
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.script.quickjs.QuickJsEngine
import com.script.quickjs.buildScriptBindings
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.UA_NAME
import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.JS_PATTERN
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.CacheManager
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.JsExtensions
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.glide.GlideHeaders
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieManager.mergeCookies
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.StrResponse
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.get
import io.legado.app.help.http.getProxyClient
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.postForm
import io.legado.app.help.http.postJson
import io.legado.app.help.http.postMultipart
import io.legado.app.help.source.getShareScope
import io.legado.app.model.Debug
import io.legado.app.model.webBook.replaceExploreOptionsInUrl
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.get
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.isJson
import io.legado.app.utils.isXml
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.InputStream
import java.lang.reflect.Type
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.max

/**
 * Created by GKF on 2018/1/24.
 * 搜索URL规则解析
 */
@Keep
@SuppressLint("DefaultLocale")
class AnalyzeUrl(
    val rawUrl: String,
    private var baseUrl: String = "",
    private val source: BaseSource? = null,
    private val ruleData: RuleDataInterface? = null,
    private val chapter: BookChapter? = null,
    private val readTimeout: Long? = null,
    private val callTimeout: Long? = null,
    private var coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    hasLoginHeader: Boolean = true,
    private val selectedOptions: Map<String, String>? = null,
    /** 额外注入到 evalJS 作用域的键值对，例如 key、page */
    private val variables: Map<AppConst.JsVarName, Any>? = null
) : JsExtensions {

    private var tmpUrl = rawUrl

    /**
     * `ruleUrl` 经过 @js / <js></js> 解析后、{{...}} 与 <name(opts)> 替换之前的形态，
     * 供调用方发现 URL 中静态声明的可选项。
     */
    var urlAfterJs = ""
        private set
    var url: String = ""
    val headerMap = LinkedHashMap<String, String>()
    var urlNoQuery: String = ""
    var encodedParams: String? = null
    private var proxy: String? = null
    private var option: UrlOption? = null
    private val enabledCookieJar = source?.enabledCookieJar == true
    private val domain: String
    private val concurrentRateLimiter = ConcurrentRateLimiter(source)

    // 直接转发到 option 的派生属性
    val type: String? get() = option?.type
    val serverID: Long? get() = option?.serverID

    init {
        coroutineContext = coroutineContext.minusKey(ContinuationInterceptor)
        if (baseUrl.isBlank()) baseUrl = source?.getKey() ?: ""
        if (baseUrl.contains("{")) {
            val matcher = paramPattern.matcher(baseUrl)
            if (matcher.find()) {
                baseUrl = baseUrl.substring(0, matcher.start())
            }
        }
        // 直接前置执行 initUrl()，这样 source.header 里的 js 才能拿到相关参数
        initUrl()

        // 保存 URL 级别的请求头临时拷贝
        val urlHeaders = LinkedHashMap(headerMap)

        // 添加 source 级别的请求头
        if (headerMapF.isNullOrEmpty()) {
            val sourceHeaders = source?.getHeaderMap(hasLoginHeader, this::evalJS) ?: emptyMap()
            headerMap.putAll(sourceHeaders)
            headerMap.remove("proxy")?.let { proxy = it }
        } else {
            headerMap.putAll(headerMapF)
        }

        // 如果 URL 级别的请求头非空，再添加回去，确保 URL 级别的请求头不会被 source 级别的请求头覆盖
        if (urlHeaders.isNotEmpty()) {
            headerMap.putAll(urlHeaders)
        }

        domain =
            NetworkUtils.getSubDomain(source?.getKey()?.takeIf { it.startsWith("http") } ?: url)
    }

    /**
     * 处理url，可由书源 JS 在登录检测后再次调用以重新解析。
     */
    fun initUrl() {
        tmpUrl = rawUrl
        //执行@js,<js></js>
        analyzeJs()
        urlAfterJs = tmpUrl
        //替换参数
        tmpUrl = replaceKeyPageJs(replaceDynamicOptions(tmpUrl))
        //处理URL
        analyzeUrl()
    }

    private fun replaceDynamicOptions(curRuleUrl: String): String =
        replaceExploreOptionsInUrl(curRuleUrl) { name -> selectedOptions?.get(name) }

    /**
     * 执行@js,<js></js>
     */
    private fun analyzeJs() {
        if (!tmpUrl.contains("js")) return
        val jsMatcher = JS_PATTERN.matcher(tmpUrl)
        var result = tmpUrl
        var start = 0
        fun useSegment(end: Int) {
            tmpUrl.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { result = it }
        }
        while (jsMatcher.find()) {
            useSegment(jsMatcher.start())
            result = evalJS(jsMatcher.group(2) ?: jsMatcher.group(1), result).toString()
            start = jsMatcher.end()
        }
        useSegment(tmpUrl.length)
        tmpUrl = result
    }

    /**
     * 替换关键字,页数,JS
     */
    private fun replaceKeyPageJs(curRuleUrl: String): String {
        //先替换内嵌规则再替换页数规则，避免内嵌规则中存在大于小于号时，规则被切错
        if (curRuleUrl.contains("{{") && curRuleUrl.contains("}}")) {
            val res = RuleAnalyzer(curRuleUrl).innerRule("{{", "}}") {
                when (val jsEval = evalJS(it) ?: "") {
                    is String -> jsEval
                    is Double if jsEval % 1.0 == 0.0 -> String.format("%.0f", jsEval)
                    else -> jsEval.toString()
                }
            }
            if (res.isNotEmpty()) {
                return res
            }
        }
        return curRuleUrl
    }

    /**
     * 解析Url
     */
    private fun analyzeUrl() {
        var urlNoOption = tmpUrl
        var urlOptionEnd = -1
        if (tmpUrl.contains("{")) {
            val urlMatcher = paramPattern.matcher(tmpUrl)
            if (urlMatcher.find()) {
                urlNoOption = tmpUrl.substring(0, urlMatcher.start())
                urlOptionEnd = urlMatcher.end()
            }
        }
        url = if (urlNoOption.isDataUrl()) urlNoOption
        else NetworkUtils.getAbsoluteURL(baseUrl, urlNoOption)
        NetworkUtils.getBaseUrl(url)?.let { baseUrl = it }
        if (urlOptionEnd != -1) {
            val urlOptionStr = tmpUrl.substring(urlOptionEnd)
            option = GSONStrict.fromJsonObject<UrlOption>(urlOptionStr).getOrNull()
                ?: GSON.fromJsonObject<UrlOption>(urlOptionStr).getOrNull()?.also {
                    log("链接参数 JSON 格式不规范，请改为规范格式")
                }
            option?.let { opt ->
                opt.headers?.forEach { (k, v) -> headerMap[k] = v.toString() }
                opt.js?.let { jsStr -> evalJS(jsStr, url)?.toString()?.let { url = it } }
            }
        }
        urlNoQuery = url
        if (isPost()) {
            val body = option?.body
            if (body != null && !body.isJson() && !body.isXml() && headerMap["Content-Type"].isNullOrEmpty()) {
                analyzeParams(body, false)
            }
        } else {
            val pos = url.indexOf('?')
            if (pos != -1) {
                analyzeParams(url.substring(pos + 1), true)
                urlNoQuery = url.substring(0, pos)
            }
        }
    }

    /**
     * 解析参数 <key>=<value>
     * name=
     * name=name
     * name=<BASE64> eg name=bmFtZQ==
     * isQuery=true 时是 URL query，false 时是 POST form body
     */
    private fun analyzeParams(text: String, isQuery: Boolean) {
        encodedParams = encodeParams(text, option?.charset, isQuery)
    }

    private fun encodeParams(params: String, charset: String?, isQuery: Boolean): String {
        val checkEncoded = charset.isNullOrEmpty()
        val cs = when {
            charset.isNullOrEmpty() -> Charsets.UTF_8
            charset == "escape" -> null
            else -> charset(charset)
        }
        if (isQuery && cs != null) {
            return if (NetworkUtils.encodedQuery(params)) params
            else queryEncoder.encode(params, cs)
        }
        // 与旧实现保持一致：吃掉单个结尾 '&' 和所有开头 '&'，中间空段保留为 '&&'
        return params.removeSuffix("&")
            .split('&')
            .dropWhile { it.isEmpty() }
            .joinToString("&") { pair ->
                val eq = pair.indexOf('=')
                if (eq == -1) {
                    encodeOne(pair, checkEncoded, cs)
                } else {
                    encodeOne(pair.substring(0, eq), checkEncoded, cs) +
                        "=" + encodeOne(pair.substring(eq + 1), checkEncoded, cs)
                }
            }
    }

    private fun encodeOne(value: String, checkEncoded: Boolean, charset: Charset?): String =
        when {
            checkEncoded && NetworkUtils.encodedForm(value) -> value
            charset == null -> EncoderUtils.escape(value)
            else -> URLEncoder.encode(value, charset)
        }

    /**
     * 执行JS
     */
    fun evalJS(jsStr: String, result: Any? = null): Any? {
        // 空字符串早返回，避免不必要的编译执行开销
        if (jsStr.isBlank()) return null
        val bindings = buildScriptBindings { bindings ->
            variables?.forEach { (k, v) -> bindings[k.key] = v }
            bindings["java"] = this
            // 响应阶段(loginCheckJs/请求头 JS)需要"当前请求 URL"，所以优先用 url；
            // 但 {{...}} 模板求值发生在 analyzeUrl() 之前，此时 url 还是空，降级用构造器传入的 baseUrl
            bindings["baseUrl"] = url.ifEmpty { baseUrl }
            bindings["cookie"] = CookieStore
            bindings["cache"] = CacheManager
            bindings["book"] = ruleData as? Book
            bindings["chapter"] = chapter
            bindings["source"] = source
            bindings["result"] = result
            bindings.dangerousApi = source?.enableDangerousApi == true
        }
        val sharedScope = source?.getShareScope(coroutineContext)
        // - sharedScope == null: 创建独立 scope, bindings 注入 globalThis
        // - sharedScope 路径: SharedJsScope 缓存的 topScope (ThreadLocal 线程独占),
        //   bindings 注入该 topScope 的 globalThis 后再执行, evalInSubScope 内部清理,
        //   保证 jsLib 自由函数 (如 lk) 能命中 cache/book 等 binding。
        return if (sharedScope == null) {
            val scope = QuickJsEngine.getRuntimeScope(bindings)
            val wrappedJs = QuickJsEngine.wrapJsForEval(jsStr)
            QuickJsEngine.eval(wrappedJs, scope, coroutineContext)
        } else {
            val compiled = QuickJsEngine.compileForSubScope(jsStr)
            QuickJsEngine.evalInSubScope(compiled, sharedScope, bindings, coroutineContext)
        }
    }

    fun put(key: String, value: String): String {
        if (key == "bookName" || key == "title") {
            Debug.log("≡变量 $key 在特定情况下会被覆盖，建议使用其他键名")
        }
        chapter?.putVariable(key, value)
            ?: ruleData?.putVariable(key, value)
        return value
    }

    fun get(key: String): String = when (key) {
        "bookName" -> (ruleData as? Book)?.name ?: ""
        "title" -> chapter?.title ?: ""
        else -> chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    /**
     * 访问网站,返回StrResponse
     */
    suspend fun getStrResponseAwait(
        jsStr: String? = null,
        sourceRegex: String? = null,
        allowWebView: Boolean = true,
    ): StrResponse {
        getByteArrayIfDataUri()?.let { return StrResponse(url, it.toHexString()) }
        concurrentRateLimiter.withLimit {
            setCookie()
            try {
                return if (option?.useWebView == true && allowWebView) {
                    getWebViewResponse(jsStr, sourceRegex)
                } else {
                    getOkHttpStrResponse()
                }
            } finally {
                saveCookie()
            }
        }
    }

    /**
     * WebView方式获取响应
     */
    private suspend fun getWebViewResponse(
        jsStr: String?,
        sourceRegex: String?,
    ): StrResponse {
        val js = option?.webJs ?: jsStr
        val delay = option?.let { max(0L, it.webViewDelayTime ?: 0L) } ?: 1000L
        if (!isPost()) {
            return BackstageWebView(
                url = url, tag = source?.getKey(),
                javaScript = js, sourceRegex = sourceRegex,
                headerMap = headerMap, delayTime = delay
            ).getStrResponse()
        }
        val body = option?.body
        val res = getClient().newCallStrResponse(option?.retry ?: 0) {
            addHeaders(headerMap)
            url(urlNoQuery)
            if (!encodedParams.isNullOrEmpty() || body.isNullOrBlank()) postForm(
                encodedParams ?: ""
            )
            else postJson(body)
        }
        return BackstageWebView(
            url = res.url, html = res.body, tag = source?.getKey(),
            javaScript = js, sourceRegex = sourceRegex,
            headerMap = headerMap, delayTime = delay
        ).getStrResponse()
    }

    /**
     * OkHttp方式获取StrResponse
     */
    private suspend fun getOkHttpStrResponse(): StrResponse =
        getClient().newCallStrResponse(option?.retry ?: 0) {
            addHeaders(headerMap)
            configureRequest()
        }.let {
            val isXml = it.raw.body.contentType()?.toString()
                ?.matches(AppPattern.xmlContentTypeRegex) == true
            if (isXml && it.body?.trim()?.startsWith("<?xml", true) == false)
                StrResponse(it.raw, "<?xml version=\"1.0\"?>" + it.body)
            else it
        }

    /**
     * 配置OkHttp请求参数（GET/POST）
     */
    private fun okhttp3.Request.Builder.configureRequest() {
        if (!isPost()) {
            get(urlNoQuery, encodedParams)
            return
        }
        url(urlNoQuery)
        val contentType = headerMap["Content-Type"]
        val body = option?.body
        if (!encodedParams.isNullOrEmpty() || body.isNullOrBlank()) postForm(encodedParams ?: "")
        else if (!contentType.isNullOrBlank()) post(body.toRequestBody(contentType.toMediaType()))
        else postJson(body)
    }

    @JvmOverloads
    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        allowWebView: Boolean = true,
    ): StrResponse =
        runBlocking(coroutineContext) { getStrResponseAwait(jsStr, sourceRegex, allowWebView) }

    /**
     * 访问网站,返回Response
     */
    suspend fun getResponseAwait(): Response = concurrentRateLimiter.withLimit {
        setCookie()
        try {
            getClient().newCallResponse(option?.retry ?: 0) {
                addHeaders(headerMap)
                tag(String::class.java, rawUrl)
                configureRequest()
            }
        } finally {
            saveCookie()
        }
    }

    private fun getClient(): OkHttpClient {
        val client = getProxyClient(proxy)
        if (readTimeout == null && callTimeout == null) return client
        return client.newBuilder().apply {
            readTimeout?.let {
                readTimeout(it, TimeUnit.MILLISECONDS)
                callTimeout(max(timeLimit, it) * 2, TimeUnit.MILLISECONDS)
            }
            callTimeout?.let { callTimeout(it, TimeUnit.MILLISECONDS) }
        }.build()
    }

    @Suppress("unused")
    fun getResponse(): Response = runBlocking(coroutineContext) { getResponseAwait() }

    private fun getByteArrayIfDataUri(): ByteArray? {
        if (!url.isDataUrl()) return null
        val pos = urlNoQuery.indexOf(";base64,")
        return if (pos != -1) Base64.decode(urlNoQuery.substring(pos + 8), Base64.DEFAULT)
        else ByteArray(0)
    }

    /**
     * 访问网站,返回ByteArray
     */
    suspend fun getByteArrayAwait(): ByteArray = getByteArrayIfDataUri() ?: getResponseAwait().use {
        val source = it.body.source()
        val buffer = Buffer()
        source.readAll(buffer)
        buffer.readByteArray()
    }

    fun getByteArray(): ByteArray = runBlocking(coroutineContext) { getByteArrayAwait() }

    /**
     * 访问网站,返回InputStream
     */
    suspend fun getInputStreamAwait(): InputStream =
        getByteArrayIfDataUri()?.inputStream() ?: getResponseAwait().body.byteStream()

    fun getInputStream(): InputStream = runBlocking(coroutineContext) { getInputStreamAwait() }

    /**
     * 上传文件
     */
    suspend fun upload(fileName: String, file: Any, contentType: String): StrResponse {
        return getProxyClient(proxy).newCallStrResponse(option?.retry ?: 0) {
            url(urlNoQuery)
            val bodyMap = GSON.fromJsonObject<HashMap<String, Any>>(option?.body).getOrNull()!!
            bodyMap.forEach { (k, v) ->
                if (v.toString() == "fileRequest") {
                    bodyMap[k] =
                        mapOf("fileName" to fileName, "file" to file, "contentType" to contentType)
                }
            }
            postMultipart(type, bodyMap)
        }
    }

    /**
     * 设置cookie 优先级
     * urlOption临时cookie > 数据库cookie
     */
    private fun setCookie() {
        val cookie = CookieStore.getCookie(domain)
        if (cookie.isNotEmpty()) {
            mergeCookies(cookie, headerMap["Cookie"])?.let { headerMap["Cookie"] = it }
        }
        if (enabledCookieJar) headerMap[CookieManager.cookieJarHeader] = "1"
        else headerMap.remove(CookieManager.cookieJarHeader)
    }

    /**
     * 保存cookieJar中的cookie在访问结束时就保存,不等到下次访问
     */
    private fun saveCookie() {
        if (!enabledCookieJar) return
        val key = "${domain}_cookieJar"
        (CacheManager.getFromMemory(key) as? String)?.let {
            CookieStore.replaceCookie(domain, it)
            CacheManager.deleteMemory(key)
        }
    }

    /**
     *获取处理过阅读定义的urlOption和cookie的GlideUrl
     */
    fun getGlideUrl(): GlideUrl {
        setCookie()
        return GlideUrl(url, GlideHeaders(headerMap))
    }

    fun getUserAgent(): String = headerMap.get(UA_NAME, true) ?: AppConfig.userAgent

    fun isPost(): Boolean = option?.method.equals("POST", true)

    override fun getSource(): BaseSource? = source

    companion object {
        val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")
        private val queryEncoder =
            RFC3986.UNRESERVED.orNew(PercentCodec.of("!$%&()*+,/:;=?@[\\]^`{|}"))

        fun AnalyzeUrl.getMediaItem(): MediaItem {
            setCookie()
            return ExoPlayerHelper.createMediaItem(url, headerMap)
        }

    }

    @Keep
    class UrlOption {
        var method: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }
        var charset: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** 源Url */
        var origin: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** 类型 */
        var type: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** webView中执行的js */
        var webJs: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /**
         * 解析完url参数时执行的js
         * 执行结果会赋值给url
         */
        var js: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** 重试次数 */
        var retry: Int? = null

        /** 服务器id */
        var serverID: Long? = null

        /** webview等待页面加载完毕的延迟时间（毫秒） */
        var webViewDelayTime: Long? = null

        /** 请求体；持有字符串，序列化时若内容可解析为 JSON 对象/数组会被还原为嵌套结构 */
        var body: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** 请求头 */
        var headers: Map<String, Any?>? = null

        /** 是否使用 webView */
        var useWebView: Boolean = false

        companion object {
            private val mapType: Type = TypeToken.getParameterized(
                Map::class.java, String::class.java, Any::class.java
            ).type

            private val falseStrings = setOf("", "false")

            val jsonDeserializer = JsonDeserializer<UrlOption> { json, _, _ ->
                val obj = json.asJsonObject
                UrlOption().apply {
                    method = obj.flexString("method")
                    charset = obj.flexString("charset")
                    origin = obj.flexString("origin")
                    type = obj.flexString("type")
                    webJs = obj.flexString("webJs")
                    js = obj.flexString("js")
                    retry = obj.flexNumber("retry")?.toInt()
                    serverID = obj.flexNumber("serverID")?.toLong()
                    webViewDelayTime = obj.flexNumber("webViewDelayTime")?.toLong()
                    useWebView = obj.flexBool("webView")
                    body = obj["body"]?.let { el ->
                        when {
                            el.isJsonNull -> null
                            el.isJsonPrimitive -> el.asString.ifBlank { null }
                            else -> el.toString()
                        }
                    }
                    headers = obj["headers"]?.let { el ->
                        when {
                            el.isJsonNull -> null
                            el.isJsonObject ->
                                GSON.fromJson<Map<String, Any?>>(el, mapType)

                            el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                                GSON.fromJsonObject<Map<String, Any?>>(el.asString).getOrNull()

                            else -> null
                        }
                    }
                }
            }

            val jsonSerializer = JsonSerializer<UrlOption> { src, _, ctx ->
                JsonObject().apply {
                    src.method?.let { addProperty("method", it) }
                    src.charset?.let { addProperty("charset", it) }
                    src.origin?.let { addProperty("origin", it) }
                    src.type?.let { addProperty("type", it) }
                    src.webJs?.let { addProperty("webJs", it) }
                    src.js?.let { addProperty("js", it) }
                    src.retry?.let { addProperty("retry", it) }
                    src.serverID?.let { addProperty("serverID", it) }
                    src.webViewDelayTime?.let { addProperty("webViewDelayTime", it) }
                    if (src.useWebView) addProperty("webView", true)
                    src.body?.let { b ->
                        add("body", b.parseAsJsonContainer() ?: JsonPrimitive(b))
                    }
                    src.headers?.let { add("headers", ctx.serialize(it)) }
                }
            }

            private fun JsonObject.flexString(key: String): String? = this[key]
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.ifBlank { null }

            private fun JsonObject.flexNumber(key: String): Number? {
                val el = this[key] ?: return null
                if (!el.isJsonPrimitive) return null
                val p = el.asJsonPrimitive
                return when {
                    p.isNumber -> p.asNumber
                    p.isString -> p.asString.toLongOrNull() ?: p.asString.toDoubleOrNull()
                    else -> null
                }
            }

            private fun JsonObject.flexBool(key: String): Boolean {
                val el = this[key] ?: return false
                if (el.isJsonNull) return false
                if (!el.isJsonPrimitive) return true
                val p = el.asJsonPrimitive
                return when {
                    p.isBoolean -> p.asBoolean
                    p.isString -> p.asString.lowercase() !in falseStrings
                    else -> true
                }
            }

            private fun String.parseAsJsonContainer(): JsonElement? = runCatching {
                JsonParser.parseString(this)
            }.getOrNull()?.takeIf { it.isJsonObject || it.isJsonArray }
        }
    }

    data class ConcurrentRecord(
        /**
         * 是否按频率
         */
        val isConcurrent: Boolean,
        /**
         * 开始访问时间
         */
        var time: Long,
        /**
         * 正在访问的个数
         */
        var frequency: Int
    )

}
