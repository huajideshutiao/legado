package io.legado.app.model.analyzeRule

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.UA_NAME
import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.JS_PATTERN
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapterLike
import io.legado.app.data.entities.BookLike
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.JsExtensionsCommon
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.coroutine.ConcurrentRateLimiter
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.help.http.BackstageWebViewProviders
import io.legado.app.help.http.KmpHttpClient
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.KmpResponse
import io.legado.app.help.http.OkHttpProxyClientProviders
import io.legado.app.help.http.StrResponse
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.cookieJarHeader
import io.legado.app.help.http.get
import io.legado.app.help.http.mergeCookies
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.postForm
import io.legado.app.help.http.postJson
import io.legado.app.help.http.postMultipart
import io.legado.app.help.http.tagKmp
import io.legado.app.help.http.toKmpMediaType
import io.legado.app.help.http.toKmpRequestBody
import io.legado.app.help.source.SourceCacheProviders
import io.legado.app.help.source.SourceDebugLoggers
import io.legado.app.help.source.SourceNetworkProviders
import io.legado.app.help.source.getShareScope
import io.legado.app.model.script.JsEngines
import io.legado.app.model.script.buildScriptBindings
import io.legado.app.model.webBook.replaceExploreOptionsInUrl
import io.legado.app.utils.InputStream
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.KS_JSON_STRICT
import io.legado.app.utils.MimeBase64Decoder
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.PercentCodec
import io.legado.app.utils.byteStreamAsInput
import io.legado.app.utils.decodeAnyMapOrNull
import io.legado.app.utils.formatDoubleNoDecimal
import io.legado.app.utils.get
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.isJson
import io.legado.app.utils.isXml
import io.legado.app.utils.toInputStream
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * AnalyzeUrl 主体: 纯 JVM 逻辑, 无 android-only 依赖。
 * app 端 [AnalyzeUrl] 继承本类并实现 [io.legado.app.help.JsExtensionsJvm],
 * 添加 android-only 方法 (getGlideUrl/getMediaItem)。
 *
 * KSP @JsApi 分派表由 app 端 AnalyzeUrl 生成, 通过 getAllFunctions() 继承链
 * 自动包含本类的 public 方法, 方法名集合与原 AnalyzeUrl 完全一致 (零 diff)。
 *
 * **commonMain 下沉说明**:
 * - JVM 专属 API (URL/InputStream/URLEncoder/Base64/Locale/runBlocking) 经 expect/actual 包装,
 *   详见 commonMain/utils/JvmPlatformTypes.kt、PlatformEncoding.kt、PlatformFormat.kt、
 *   commonMain/help/RunBlockingPlatform.kt。
 * - Pattern → kotlin.text.Regex (commonMain 原生), matcher 循环改 findAll/find, 行为等价。
 * - TimeUnit → kotlin.time.Duration (OkHttp 5.3.2 KotlinDuration 重载, commonMain 可见)。
 * - Charsets.UTF_8 / charset(name) / String.toByteArray(charset) 为 kotlin.text 标准 API, commonMain 可用。
 *
 * Created by GKF on 2018/1/24.
 * 搜索URL规则解析
 */
@Suppress("unused")
open class AnalyzeUrlCore(
    val rawUrl: String,
    private var baseUrl: String = "",
    private val source: BaseSource? = null,
    private val ruleData: RuleDataInterface? = null,
    private val chapter: BookChapterLike? = null,
    private val readTimeout: Long? = null,
    private val callTimeout: Long? = null,
    private var coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    hasLoginHeader: Boolean = true,
    private val selectedOptions: Map<String, String>? = null,
    /** 额外注入到 evalJS 作用域的键值对，例如 key、page */
    private val variables: Map<AppConst.JsVarName, Any>? = null
) : JsExtensionsCommon {

    private var tmpUrl = rawUrl

    /**
     * `ruleUrl` 经过 @js / <js></js> 解析后、{{...}} 与 <name(opts)> 替换之前的形态，
     * 供调用方发现 URL 中静态声明的可选项。
     */
    var urlAfterJs = ""
        protected set
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
            val match = paramPattern.find(baseUrl)
            if (match != null) {
                baseUrl = baseUrl.substring(0, match.range.first)
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
        var result = tmpUrl
        var start = 0
        fun useSegment(end: Int) {
            tmpUrl.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { result = it }
        }
        // Pattern.matcher → Regex.findAll: match.range.first 对应 matcher.start(),
        // match.range.last + 1 对应 matcher.end(), groups[n]?.value 对应 group(n) (null 若组未参与匹配)
        for (match in JS_PATTERN.findAll(tmpUrl)) {
            useSegment(match.range.first)
            result = evalJS(match.groups[2]?.value ?: match.groups[1]?.value ?: "", result).toString()
            start = match.range.last + 1
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
                    is Double if jsEval % 1.0 == 0.0 -> formatDoubleNoDecimal(jsEval)
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
            val match = paramPattern.find(tmpUrl)
            if (match != null) {
                urlNoOption = tmpUrl.substring(0, match.range.first)
                urlOptionEnd = match.range.last + 1
            }
        }
        url = if (urlNoOption.isDataUrl()) urlNoOption
        else NetworkUtils.getAbsoluteURL(baseUrl, urlNoOption)
        NetworkUtils.getBaseUrl(url)?.let { baseUrl = it }
        if (urlOptionEnd != -1) {
            val urlOptionStr = tmpUrl.substring(urlOptionEnd)
            // GSONStrict/GSON.fromJsonObject<UrlOption>(urlOptionStr) 双栈 → KS_JSON_STRICT/KS_JSON.decodeFromString(UrlOptionSerializer)
            // 复刻原双栈语义: 先严格解析, 失败则降级到宽松 (并打 log 提示 JSON 格式不规范)
            option = try {
                KS_JSON_STRICT.decodeFromString(UrlOptionSerializer, urlOptionStr)
            } catch (_: Exception) {
                null
            } ?: try {
                KS_JSON.decodeFromString(UrlOptionSerializer, urlOptionStr)
            } catch (_: Exception) {
                null
            }?.also {
                SourceDebugLoggers.impl?.log("链接参数 JSON 格式不规范，请改为规范格式")
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
        encodedParams = encodeUrlParams(text, option?.charset, isQuery)
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
            bindings["cookie"] = SourceNetworkProviders.impl?.asBinding()
            bindings["cache"] = SourceCacheProviders.impl?.asBinding()
            bindings["book"] = ruleData as? BookLike
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
            val scope = JsEngines.get().getRuntimeScope(bindings)
            val wrappedJs = JsEngines.get().wrapJsForEval(jsStr)
            try {
                JsEngines.get().eval(wrappedJs, scope, coroutineContext)
            } finally {
                // 无 sharedScope 时创建的独立 scope 必须显式 close,
                // 否则 native JSRuntime/JSContext 只能等 GC + PhantomReference 异步释放,
                // 漫画场景下大量图片加载会快速累积 native 内存。
                scope.close()
            }
        } else {
            val compiled = JsEngines.get().compileForSubScope(jsStr)
            JsEngines.get().evalInSubScope(compiled, sharedScope, bindings, coroutineContext)
        }
    }

    fun put(key: String, value: String): String {
        if (key == "bookName" || key == "title") {
            SourceDebugLoggers.impl?.log("≡变量 $key 在特定情况下会被覆盖，建议使用其他键名")
        }
        chapter?.putVariable(key, value)
            ?: ruleData?.putVariable(key, value)
        return value
    }

    fun get(key: String): String = when (key) {
        "bookName" -> (ruleData as? BookLike)?.name ?: ""
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
            return BackstageWebViewProviders.get().create(
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
        return BackstageWebViewProviders.get().create(
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
     *
     * KP4 OkHttp 跨平台修复: 原直接引用 okhttp3.Request.Builder / okhttp3.RequestBody / okhttp3.MediaType,
     * iOS/鸿蒙 target 无 OkHttp 变体编译失败; 现改用 [KmpRequestBuilder] / [toKmpRequestBody] / [toKmpMediaType]
     * 跨平台抽象 (jvmAndAndroidMain 经 typealias 等价 okhttp3.*; iOS/鸿蒙 stub)。
     * jvm/android 行为与原实现完全一致 (零 diff)。
     */
    private fun KmpRequestBuilder.configureRequest() {
        if (!isPost()) {
            get(urlNoQuery, encodedParams)
            return
        }
        url(urlNoQuery)
        val contentType = headerMap["Content-Type"]
        val body = option?.body
        if (!encodedParams.isNullOrEmpty() || body.isNullOrBlank()) postForm(encodedParams ?: "")
        else if (!contentType.isNullOrBlank()) post(
            body.toKmpRequestBody(contentType.toKmpMediaType())
        )
        else postJson(body)
    }

    // 短参显式重载: 补回原 @JvmOverloads 生成的 JVM 签名 (commonMain 无该注解), 书源 JS 按 arity 匹配
    fun getStrResponse(): StrResponse = getStrResponse(null, null, true)

    fun getStrResponse(jsStr: String?): StrResponse = getStrResponse(jsStr, null, true)

    fun getStrResponse(jsStr: String?, sourceRegex: String?): StrResponse =
        getStrResponse(jsStr, sourceRegex, true)

    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        allowWebView: Boolean = true,
    ): StrResponse =
        runBlockingInScope(coroutineContext) { getStrResponseAwait(jsStr, sourceRegex, allowWebView) }

    /**
     * 访问网站,返回Response
     */
    suspend fun getResponseAwait(): KmpResponse = concurrentRateLimiter.withLimit {
        setCookie()
        try {
            getClient().newCallResponse(option?.retry ?: 0) {
                addHeaders(headerMap)
                tagKmp(String::class, rawUrl)
                configureRequest()
            }
        } finally {
            saveCookie()
        }
    }

    private fun getClient(): KmpHttpClient {
        val client = OkHttpProxyClientProviders.get().getProxyClient(proxy)
        if (readTimeout == null && callTimeout == null) return client
        return client.newBuilder().apply {
            // OkHttp 5.3.2 提供 KotlinDuration 重载 (kotlin.time.Duration), commonMain 可见,
            // 替代原 readTimeout(Long, TimeUnit.MILLISECONDS) / callTimeout(Long, TimeUnit.MILLISECONDS) JVM-only API
            readTimeout?.let {
                readTimeout(it.milliseconds)
                callTimeout((max(timeLimit, it) * 2).milliseconds)
            }
            callTimeout?.let { callTimeout(it.milliseconds) }
        }.build()
    }

    @Suppress("unused")
    fun getResponse(): KmpResponse = runBlockingInScope(coroutineContext) { getResponseAwait() }

    private fun getByteArrayIfDataUri(): ByteArray? {
        if (!url.isDataUrl()) return null
        val pos = urlNoQuery.indexOf(";base64,")
        return if (pos != -1) MimeBase64Decoder.decode(urlNoQuery.substring(pos + 8))
        else ByteArray(0)
    }

    /**
     * 访问网站,返回ByteArray
     */
    suspend fun getByteArrayAwait(): ByteArray = getByteArrayIfDataUri() ?: run {
        // KP4 修复: commonMain 没有 kotlin.io.use 扩展函数 (JVM-only),
        // 用 try-finally 替代 getResponseAwait().use { ... } 保证 response 关闭。
        // 原 okio.Buffer().use { source.readAll(it); it.readByteArray() } 等价于 ResponseBody.bytes(),
        // 改用 body.bytes() 避免在 commonMain 直接 import okio.Buffer (iOS/鸿蒙无 okio native variant)
        val response = getResponseAwait()
        try {
            response.body.bytes()
        } finally {
            response.close()
        }
    }

    fun getByteArray(): ByteArray = runBlockingInScope(coroutineContext) { getByteArrayAwait() }

    /**
     * 访问网站,返回InputStream
     */
    suspend fun getInputStreamAwait(): InputStream =
        getByteArrayIfDataUri()?.toInputStream() ?: getResponseAwait().body.byteStreamAsInput()

    fun getInputStream(): InputStream = runBlockingInScope(coroutineContext) { getInputStreamAwait() }

    /**
     * 上传文件
     */
    suspend fun upload(fileName: String, file: Any, contentType: String): StrResponse {
        return OkHttpProxyClientProviders.get().getProxyClient(proxy).newCallStrResponse(option?.retry ?: 0) {
            url(urlNoQuery)
            // 对齐原版 GSON.fromJsonObject<HashMap<String, Any>>(option?.body).getOrNull()!! 的 fail-loud:
            // body 缺失/格式错时直接抛，让书源作者立刻发现，不能静默丢参数发出空表单。
            @Suppress("UNCHECKED_CAST")
            val bodyMap = (decodeAnyMapOrNull(option?.body)
                ?: throw NoStackTraceException(
                    "上传规则 body 解析失败, 需为 JSON 对象: ${option?.body?.take(120) ?: "null"}"
                )) as MutableMap<String, Any>
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
    protected fun setCookie() {
        val cookie = SourceNetworkProviders.impl?.getCookie(domain) ?: ""
        if (cookie.isNotEmpty()) {
            mergeCookies(cookie, headerMap["Cookie"])?.let { headerMap["Cookie"] = it }
        }
        if (enabledCookieJar) headerMap[cookieJarHeader] = "1"
        else headerMap.remove(cookieJarHeader)
    }

    /**
     * 保存cookieJar中的cookie在访问结束时就保存,不等到下次访问
     */
    private fun saveCookie() {
        if (!enabledCookieJar) return
        val key = "${domain}_cookieJar"
        (SourceCacheProviders.impl?.getFromMemory(key) as? String)?.let {
            SourceNetworkProviders.impl?.replaceCookie(domain, it)
            SourceCacheProviders.impl?.deleteMemory(key)
        }
    }

    fun getUserAgent(): String = headerMap.get(UA_NAME, true) ?: UserAgentProviders.get()

    fun isPost(): Boolean = option?.method.equals("POST", true)

    override fun getSource(): BaseSource? = source

    companion object {
        // Pattern → Regex: AppPattern.urlParamPattern 本身是 Regex (commonMain),
        // 原 .toPattern() 返回 java.util.regex.Pattern (JVM-only), commonMain 不可用, 直接用 Regex
        val paramPattern: Regex = AppPattern.urlParamPattern
    }

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
            // 原 Gson jsonDeserializer/jsonSerializer + flexString/flexNumber/flexBool/parseAsJsonContainer
            // 已移除, 序列化/反序列化由 [UrlOptionSerializer] 复刻 (KSerializer 自定义实现)
        }
    }

    // ConcurrentRecord 已抽出独立下沉 shared commonMain (io.legado.app.model.analyzeRule.ConcurrentRecord),
    // 解除 ConcurrentRateLimiter 对 AnalyzeUrl 的反向依赖。

}

/**
 * URL 参数编码器 (RFC3986 unreserved + query 段保留字符)。
 *
 * 从原 AnalyzeUrlCore companion object 的 private val queryEncoder 下沉为 top-level internal val,
 * 供 [encodeUrlParams] actual 实现 (jvmAndAndroidMain) 使用。commonMain 不直接使用。
 */
internal val urlQueryEncoder: PercentCodec =
    PercentCodec.UNRESERVED.orNew(PercentCodec.of("!$%&()*+,/:;=?@[\\]^`{|}"))

/**
 * URL 参数编码 (Charset 相关逻辑下沉 jvmAndAndroidMain)。
 *
 * commonMain 无 kotlin.text.Charset expect class (Kotlin 2.3 stdlib), 原 encodeParams/encodeOne
 * 依赖 `Charsets.UTF_8` / `charset(name)` / `URLEncoder.encode(value, charset)`, 均 JVM-only。
 * 故把整个 encodeParams + encodeOne 下沉为 top-level expect fun, actual 在 jvmAndAndroidMain
 * 直接调用 java.net.URLEncoder.encode + java.nio.charset.Charset。
 *
 * 行为与原 private fun encodeParams 完全一致:
 * - charset 空 → UTF-8 编码
 * - charset == "escape" → EncoderUtils.escape (不 URL 编码)
 * - 其他 → 用指定 charset URL 编码
 * - isQuery=true 且非 escape: 整段用 urlQueryEncoder 编码 (若已 encoded 则原样返回)
 * - isQuery=false: 按 '&' 分段, 每段按 '=' 分 key/value, 分别 encodeOne
 */
internal expect fun encodeUrlParams(params: String, charset: String?, isQuery: Boolean): String
