package io.legado.app.help

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.androidId
import io.legado.app.constant.dateFormat
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.http.BackstageWebViewProviders
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.help.http.StrResponse
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.model.script.jsContext
import io.legado.app.model.script.jsContextOrNull
import io.legado.app.utils.Base64Lenient
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.JsURL
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.mapAsync
import io.legado.app.utils.postEvent
import io.legado.app.utils.randomUUIDString
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toStringArray
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * JsExtensions 纯函数面(下沉 commonMain)。
 * app JsExtensions 继承本接口, 调用处写法不变。
 *
 * JDK 依赖 (URLEncoder/SimpleDateFormat/Date/Locale/SimpleTimeZone/UUID/Charset/ChineseUtils)
 * 经 [JsExtensionsPlatform] expect 门面委托 jvmAndAndroidMain actual, 行为与原 jvmAndAndroidMain 内联实现一致。
 *
 * # runBlocking 保留说明 (逃生舱)
 *
 * `webView` / `webViewGetSource` / `webViewGetOverrideUrl` / `ajaxAll` 经 app 端 [JsExtensions]
 * (标注 `@JsApi`) 暴露给 JS 引擎 (QuickJS), JS 调用是同步的, 不能 suspend。故这四个方法无法改为
 * suspend, 内部 `runBlocking` 保留 (把 [BackstageWebViewProviders] 的 suspend 调用 / Flow 收集包装为同步返回)。
 * - JVM 端 (Android/desktop): runBlocking 安全, JS 引擎在 IO 线程同步调用 (与 [CacheManager] 同策略)。
 * - Native 端 (iOS/鸿蒙): JS 引擎暂未启用, 这些方法不会被触发, 不会执行 runBlocking;
 *   后续 Native 启用 JS 引擎时, 需为这两条路径提供 suspend 替代方案或改用 async/await 桥接
 *   (属大型重构, 不在当前范围)。
 * - 同策略参考: [CacheManager] (object 级 `@JsApi`, runBlocking 保留) /
 *   AnalyzeUrlCore (经 [runBlockingInScope] expect 门面委托, 行为等价)。
 * - KMP 兼容替代: 可改为 [runBlockingInScope] (commonMain expect, 各端 actual), 行为零变化,
 *   但当前与 [CacheManager] 保持一致直调 runBlocking; 统一迁移待后续重构。
 */
@Suppress("unused")
interface JsExtensionsCommon {

    /* Str转ByteArray */
    fun strToBytes(str: String): ByteArray {
        return strToBytes(str, "UTF-8")
    }

    fun strToBytes(str: String, charset: String): ByteArray {
        return JsExtensionsPlatform.strToBytes(str, charset)
    }

    /* ByteArray转Str */
    fun bytesToStr(bytes: ByteArray): String {
        return bytesToStr(bytes, "UTF-8")
    }

    fun bytesToStr(bytes: ByteArray, charset: String): String {
        return JsExtensionsPlatform.bytesToStr(bytes, charset)
    }

    /**
     * js实现base64解码,不能删
     */
    fun base64Decode(str: String?): String? {
        return Base64Lenient.decodeStr(str)
    }

    fun base64Decode(str: String?, charset: String): String? {
        return JsExtensionsPlatform.base64DecodeStr(str, charset)
    }

    /* HexString 解码为字节数组 */
    @OptIn(ExperimentalStdlibApi::class)
    fun hexDecodeToByteArray(hex: String): ByteArray? = hex.hexToByteArray()

    /* hexString 解码为utf8String*/
    @OptIn(ExperimentalStdlibApi::class)
    fun hexDecodeToString(hex: String): String? = hex.hexToByteArray().decodeToString()

    /* utf8 编码为hexString */
    @OptIn(ExperimentalStdlibApi::class)
    fun hexEncodeToString(utf8: String): String? = utf8.toByteArray().toHexString()

    /**
     * 格式化时间
     */
    fun timeFormatUTC(time: Long, format: String, sh: Int): String? {
        return JsExtensionsPlatform.formatTimeUtc(time, format, sh)
    }

    /**
     * 时间格式化
     */
    fun timeFormat(time: Long): String {
        // AppConst.dateFormat 已下沉 commonMain, format(Date) 重载已删除, 改传 Long (行为不变)
        return AppConst.dateFormat.format(time)
    }

    fun encodeURI(str: String): String {
        return try {
            JsExtensionsPlatform.urlEncode(str, "UTF-8")
        } catch (e: Exception) {
            ""
        }
    }

    fun encodeURI(str: String, enc: String): String {
        return try {
            JsExtensionsPlatform.urlEncode(str, enc)
        } catch (e: Exception) {
            ""
        }
    }

    fun htmlFormat(str: String): String {
        return HtmlFormatter.formatKeepImg(str)
    }

    fun toURL(urlStr: String): JsURL {
        return JsURL(urlStr)
    }

    fun toURL(url: String, baseUrl: String? = null): JsURL {
        return JsURL(url, baseUrl)
    }

    /**
     * 生成UUID
     */
    fun randomUUID() = randomUUIDString()

    fun base64Decode(str: String, flags: Int): String {
        return EncoderUtils.base64Decode(str, flags)
    }

    fun base64DecodeToByteArray(str: String?): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return EncoderUtils.base64DecodeToByteArray(str, 0)
    }

    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return EncoderUtils.base64DecodeToByteArray(str, flags)
    }

    fun base64Encode(str: String): String? {
        return EncoderUtils.base64Encode(str, 2)
    }

    fun base64Encode(str: String, flags: Int): String? {
        return EncoderUtils.base64Encode(str, flags)
    }

    fun t2s(text: String): String {
        return JsExtensionsPlatform.chineseT2S(text)
    }

    fun s2t(text: String): String {
        return JsExtensionsPlatform.chineseS2T(text)
    }

    /**
     * 章节数转数字
     */
    fun toNumChapter(s: String?): String? {
        s ?: return null
        val match = AppPattern.titleNumPattern.find(s)
        if (match != null) {
            val intStr = StringUtils.stringToInt(match.groupValues[2])
            return "${match.groupValues[1]}${intStr}${match.groupValues[3]}"
        }
        return s
    }

    /**
     * 当前书源/订阅源, 由实现端注入。
     * app 端 JsExtensions 子类(BookSource/HttpTTS 包装器/RssJsExtensions)已 override。
     * commonMain 的 AnalyzeUrlCore/AnalyzeRuleCore/BaseSource 也已 override。
     */
    fun getSource(): BaseSource?

    //****************** P1: webView 系列 (从 app JsExtensions 下沉) ******************//

    /**
     * 协程上下文, 用于 [runBlocking] 包装 webView 挂起调用。
     * 取自 [jsContext] (js 执行上下文注入), 缺失时退化为 [EmptyCoroutineContext]。
     */
    private val context: CoroutineContext
        get() = jsContext.coroutineContext ?: EmptyCoroutineContext

    /**
     * 使用webView访问网络
     * @param html 直接用webView载入的html, 如果html为空直接访问url
     * @param url html内如果有相对路径的资源不传入url访问不了
     * @param js 用来取返回值的js语句, 没有就返回整个源代码
     * @return 返回js获取的内容
     */
    fun webView(html: String?, url: String?, js: String?, delayTime: Long = 1000L): String? {
        if (JsExtensionsPlatform.isMainThread()) {
            error("webView must be called on a background thread")
        }
        // @JsApi 同步调用约束: JS 引擎不能调 suspend, runBlocking 不可避免; 详见接口级 KDoc
        return runBlocking(context) {
            BackstageWebViewProviders.get().create(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                delayTime = delayTime
            ).getStrResponse().body
        }
    }

    fun webView(html: String?, url: String?, js: String?) = webView(html, url, js, 1000L)

    /**
     * 使用webView获取资源url
     */
    fun webViewGetSource(
        html: String?,
        url: String?,
        js: String?,
        sourceRegex: String?,
        delayTime: Long = 1000L
    ): String? {
        if (JsExtensionsPlatform.isMainThread()) {
            error("webViewGetSource must be called on a background thread")
        }
        // @JsApi 同步调用约束: JS 引擎不能调 suspend, runBlocking 不可避免; 详见接口级 KDoc
        return runBlocking(context) {
            BackstageWebViewProviders.get().create(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                sourceRegex = sourceRegex,
                delayTime = delayTime
            ).getStrResponse().body
        }
    }

    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String?) =
        webViewGetSource(html, url, js, sourceRegex, 1000L)

    /**
     * 使用webView获取跳转url
     */
    fun webViewGetOverrideUrl(
        html: String?,
        url: String?,
        js: String?,
        overrideUrlRegex: String?,
        delayTime: Long = 1000L
    ): String? {
        if (JsExtensionsPlatform.isMainThread()) {
            error("webViewGetOverrideUrl must be called on a background thread")
        }
        // @JsApi 同步调用约束: JS 引擎不能调 suspend, runBlocking 不可避免; 详见接口级 KDoc
        return runBlocking(context) {
            BackstageWebViewProviders.get().create(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                overrideUrlRegex = overrideUrlRegex,
                delayTime = delayTime
            ).getStrResponse().body
        }
    }

    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String?) =
        webViewGetOverrideUrl(html, url, js, overrideUrlRegex, 1000L)

    /**
     * 访问网络,返回String
     */
    fun ajax(url: Any): String? {
        val urlStr = if (url is List<*>) {
            url.firstOrNull().toString()
        } else {
            url.toString()
        }
        // 用 AnalyzeUrlCore 替代 app 端 AnalyzeUrl (AnalyzeUrlCore 已下沉 commonMain, 行为等价)
        val analyzeUrl = AnalyzeUrlCore(
            urlStr,
            source = getSource(),
            coroutineContext = jsContext.coroutineContext ?: EmptyCoroutineContext
        )
        return kotlin.runCatching {
            analyzeUrl.getStrResponse().body
        }.onFailure {
            jsContext.ensureActive()
            AppLog.put("ajax(${urlStr}) error\n${it.localizedMessage}", it)
        }.getOrElse {
            it.stackTraceStr
        }
    }

    /**
     * 并发访问网络
     */
    fun ajaxAll(urlList: Array<String>): Array<StrResponse> {
        // AppConfig.threadCount 未下沉, 改用 AppConfigProviders.get().threadCount (等价)
        // @JsApi 同步调用约束: JS 引擎不能调 suspend, runBlocking 不可避免; 详见接口级 KDoc
        return runBlocking(jsContext.coroutineContext ?: EmptyCoroutineContext) {
            urlList.asFlow().mapAsync(AppConfigProviders.get().threadCount) { url ->
                val analyzeUrl = AnalyzeUrlCore(
                    url,
                    source = getSource(),
                    coroutineContext = coroutineContext
                )
                analyzeUrl.getStrResponseAwait()
            }.flowOn(IO).toList().toTypedArray()
        }
    }

    /**
     * 访问网络,返回Response<String>
     */
    fun connect(urlStr: String): StrResponse {
        val analyzeUrl = AnalyzeUrlCore(
            urlStr,
            source = getSource(),
            coroutineContext = jsContext.coroutineContext ?: EmptyCoroutineContext
        )
        return kotlin.runCatching {
            analyzeUrl.getStrResponse()
        }.onFailure {
            jsContext.ensureActive()
            AppLog.put("connect(${urlStr}) error\n${it.localizedMessage}", it)
        }.getOrElse {
            StrResponse(analyzeUrl.url, it.stackTraceStr)
        }
    }

    /**
     * 访问网络,返回Response<String>, 带自定义 header
     */
    fun connect(urlStr: String, header: String?): StrResponse {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull()
        val analyzeUrl = AnalyzeUrlCore(
            urlStr,
            headerMapF = headerMap,
            source = getSource(),
            coroutineContext = jsContext.coroutineContext ?: EmptyCoroutineContext
        )
        return kotlin.runCatching {
            analyzeUrl.getStrResponse()
        }.onFailure {
            jsContext.ensureActive()
            AppLog.put("ajax($urlStr,$header) error\n${it.localizedMessage}", it)
        }.getOrElse {
            StrResponse(analyzeUrl.url, it.stackTraceStr)
        }
    }

    /**
     * 刷新 UI (通过 EventBus 通知, login/explore/book)
     */
    fun refreshUi(target: String) {
        val tag = when (target.lowercase()) {
            "login" -> EventBus.REFRESH_LOGIN_UI
            "explore" -> EventBus.REFRESH_EXPLORE
            "book" -> EventBus.REFRESH_BOOK_INFO
            else -> {
                AppLog.put("java.refreshUi: 未知 target=$target")
                return
            }
        }
        postEvent(tag, true)
    }

    /**
     * 调试日志输出
     */
    fun log(msg: Any?): Any? {
        jsContextOrNull?.ensureActive()
        getSource()?.let {
            Debug.log(it.getKey(), msg.toString())
        } ?: Debug.log(msg.toString())
        AppLog.putDebug("${getSource()?.getTag() ?: "源"}调试输出: $msg")
        return msg
    }

    /**
     * 输出对象类型
     */
    fun logType(any: Any?) {
        // commonMain 无 javaClass, 用 KClass.simpleName 替代 (与原 javaClass.name 略有差异, 仅类型名)
        if (any == null) log("null")
        else log(any::class.simpleName ?: "Unknown")
    }

    /**
     * 安卓 ID (AppConst.androidId 已下沉 commonMain, 由宿主启动时注入)
     */
    fun androidId() = AppConst.androidId

    //****************** P1: 字体反混淆 (queryTTF / replaceFont) ******************//

    /**
     * 解析字体Base64数据,返回字体解析类
     */
    @Deprecated(
        "Deprecated",
        ReplaceWith("queryTTF(data)")
    )
    fun queryBase64TTF(data: String?): QueryTTF? {
        log("queryBase64TTF(String)方法已过时,并将在未来删除；请无脑使用queryTTF(Any)替代，新方法支持传入 url、本地文件、base64、ByteArray 自动判断&自动缓存，特殊情况需禁用缓存请传入第二可选参数false:Boolean")
        return queryTTF(data)
    }

    /**
     * 返回字体解析类
     * @param data 支持url,本地文件,base64,ByteArray,自动判断,自动缓存
     * @param useCache 可选开关缓存,不传入该值默认开启缓存
     */
    fun queryTTF(data: Any?, useCache: Boolean): QueryTTF? {
        try {
            var key: String? = null
            var qTTF: QueryTTF?
            when (data) {
                is String -> {
                    if (useCache) {
                        // 替代 java.security.MessageDigest SHA-256 hex (JVM 专属), 走 JsExtensionsPlatform 门面
                        key = JsExtensionsPlatform.sha256Hex(data.toByteArray())
                        qTTF = AppCacheManager.getQueryTTF(key)
                        qTTF?.let { return it }
                    }
                    val font: ByteArray? = when {
                        // AnalyzeUrl → AnalyzeUrlCore (已下沉 commonMain, 行为等价)
                        data.isAbsUrl() -> AnalyzeUrlCore(
                            data,
                            source = getSource(),
                            coroutineContext = jsContext.coroutineContext ?: EmptyCoroutineContext
                        ).getByteArray()

                        else -> base64DecodeToByteArray(data)
                    }
                    font ?: return null
                    qTTF = QueryTTF(font)
                }

                is ByteArray -> {
                    if (useCache) {
                        key = JsExtensionsPlatform.sha256Hex(data)
                        qTTF = AppCacheManager.getQueryTTF(key)
                        qTTF?.let { return it }
                    }
                    qTTF = QueryTTF(data)
                }

                else -> return null
            }
            key?.let { AppCacheManager.put(it, qTTF) }
            return qTTF
        } catch (e: Exception) {
            AppLog.put("[queryTTF] 获取字体处理类出错", e)
            throw e
        }
    }

    fun queryTTF(data: Any?): QueryTTF? {
        return queryTTF(data, true)
    }

    /**
     * @param text 包含错误字体的内容
     * @param errorQueryTTF 错误的字体
     * @param correctQueryTTF 正确的字体
     * @param filter 删除 errorQueryTTF 中不存在的字符
     */
    fun replaceFont(
        text: String,
        errorQueryTTF: QueryTTF?,
        correctQueryTTF: QueryTTF?,
        filter: Boolean
    ): String {
        if (errorQueryTTF == null || correctQueryTTF == null) return text
        // 这里不能用toCharArray,因为有些文字占多个字节
        val contentArray = text.toStringArray()
        val intArray = IntArray(1)
        contentArray.forEachIndexed { index, s ->
            // String.codePointAt(0) 是 JVM 专属, 走 firstCodePoint() 辅助函数处理代理对
            val oldCode = s.firstCodePoint()
            // 忽略正常的空白字符
            if (errorQueryTTF.isBlankUnicode(oldCode)) {
                return@forEachIndexed
            }
            // 删除轮廓数据不存在的字符
            var glyf = errorQueryTTF.getGlyfByUnicode(oldCode)  // 轮廓数据不存在
            if (errorQueryTTF.getGlyfIdByUnicode(oldCode) == 0) glyf = null // 轮廓数据指向保留索引0
            if (filter && (glyf == null)) {
                contentArray[index] = ""
                return@forEachIndexed
            }
            // 使用轮廓数据反查Unicode
            val code = correctQueryTTF.getUnicodeByGlyf(glyf)
            if (code != 0) {
                intArray[0] = code
                // String(IntArray, 0, 1) 是 JDK 专属构造, 走 codePointToString() 辅助函数
                contentArray[index] = codePointToString(intArray[0])
            }
        }
        return contentArray.joinToString("")
    }

    /**
     * @param text 包含错误字体的内容
     * @param errorQueryTTF 错误的字体
     * @param correctQueryTTF 正确的字体
     */
    fun replaceFont(
        text: String,
        errorQueryTTF: QueryTTF?,
        correctQueryTTF: QueryTTF?
    ): String {
        return replaceFont(text, errorQueryTTF, correctQueryTTF, false)
    }

    //****************** P1: Cookie 读取 (getCookie) ******************//

    /**
     * js实现读取cookie
     *
     * 底层走 [CookieStoreProviders] (已下沉 commonMain, app 端注册 AndroidCookieStoreProvider
     * 委托 CookieStore)。未注册时返回空串, 与原 app 端 CookieStore.getCookie 等价。
     */
    fun getCookie(tag: String): String {
        return getCookie(tag, null)
    }

    fun getCookie(tag: String, key: String?): String {
        val provider = CookieStoreProviders.get() ?: return ""
        return key?.let { provider.getKey(tag, it) } ?: provider.getCookie(tag)
    }
}

//****************** P1 辅助函数 (顶层 private, 替代 JVM 专属 API) ******************//

/**
 * 取字符串首个码点, 替代 `String.codePointAt(0)` (JVM 专属扩展)。
 *
 * 处理代理对: 高代理 (D800..DBFF) + 低代理 (DC00..DFFF) 组合为补充平面码点;
 * 否则取首 Char 的 code。空串返回 -1 (与原 codePointAt 行为对齐: 越界抛 IndexOutOfBoundsException,
 * 这里返回 -1 安全兜底, 调用方 toStringArray 的元素至少 1 Char, 不会命中)。
 */
private fun String.firstCodePoint(): Int {
    if (isEmpty()) return -1
    val c = this[0].code
    if (c in 0xD800..0xDBFF && length >= 2) {
        val low = this[1].code
        if (low in 0xDC00..0xDFFF) {
            // 补充平面码点公式: 0x10000 + (高 - 0xD800) * 0x400 + (低 - 0xDC00)
            return 0x10000 + (c - 0xD800) * 0x400 + (low - 0xDC00)
        }
    }
    return c
}

/**
 * 码点转字符串, 替代 `String(IntArray, offset, length)` JDK 专属构造函数。
 *
 * BMP 平面 (code < 0x10000) 单 Char; 补充平面拆为高/低代理对。
 */
private fun codePointToString(code: Int): String {
    return if (code < 0x10000) {
        String(charArrayOf(code.toChar()))
    } else {
        val normalized = code - 0x10000
        val high = 0xD800 + (normalized shr 10)
        val low = 0xDC00 + (normalized and 0x3FF)
        String(charArrayOf(high.toChar(), low.toChar()))
    }
}
