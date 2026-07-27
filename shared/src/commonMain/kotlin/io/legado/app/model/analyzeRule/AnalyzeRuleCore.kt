package io.legado.app.model.analyzeRule

import androidx.annotation.Keep
import com.fleeksoft.ksoup.nodes.Node
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppPattern.JS_PATTERN
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapterLike
import io.legado.app.data.entities.BookLike
import io.legado.app.help.JsExtensionsCommon
import io.legado.app.help.source.SourceCacheProviders
import io.legado.app.help.source.SourceDebugLoggers
import io.legado.app.help.source.SourceNetworkProviders
import io.legado.app.help.coroutine.printOnDebug
import io.legado.app.help.source.getShareScope
import io.legado.app.model.script.JsCompiledScript
import io.legado.app.model.script.JsEngines
import io.legado.app.model.script.JsScope
import io.legado.app.model.script.buildScriptBindings
import io.legado.app.utils.Closeable
import io.legado.app.utils.EscapeUtils
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.KS_JSON_STRICT
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.URL
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.formatDoubleNoDecimal
import io.legado.app.utils.getOrPutLimit
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.isJson
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * AnalyzeRule 主体: 纯 JVM 逻辑, 无 android-only 依赖。
 * app 端 [AnalyzeRule] 继承本类并实现 [io.legado.app.help.JsExtensions],
 * 添加 android-only 方法 (refreshTocUrl override)。
 *
 * KSP @JsApi 分派表由 app 端 AnalyzeRule 生成, 通过 getAllFunctions() 继承链
 * 自动包含本类的 public 方法, 方法名集合与原 AnalyzeRule 完全一致 (零 diff)。
 *
 * P3-3b: 参考 P3-3a AnalyzeUrlCore/AnalyzeUrl 继承拆分方案。
 *
 * **commonMain 下沉说明**:
 * - JVM 专属类型 (URL/Closeable) 经 expect/actual 包装, 详见 commonMain/utils/JvmPlatformTypes.kt。
 * - Pattern → kotlin.text.Regex (commonMain 原生), matcher 循环改 findAll/find, 行为等价。
 * - String.format(Locale.ROOT, "%.0f", value) → formatDoubleNoDecimal (expect/actual 包装)。
 */
@Keep
@Suppress("unused", "RegExpRedundantEscape")
open class AnalyzeRuleCore(
    var ruleData: RuleDataInterface? = null,
    private val source: BaseSource? = null,
    @Suppress("unused") private val preUpdateJs: Boolean = false
) : JsExtensionsCommon, Closeable {

    var chapter: BookChapterLike? = null
    var nextChapterUrl: String? = null
    private var content: Any? = null
    private var baseUrl: String? = null
    private var redirectUrl: URL? = null
    private var isJSON: Boolean = false
    private var isRegex: Boolean = false

    var variables: Map<AppConst.JsVarName, Any>? = null

    private var analyzeByXPath: AnalyzeByXPath? = null
    private var analyzeByJSoup: AnalyzeByJSoup? = null
    private var analyzeByJSonPath: AnalyzeByJSonPath? = null

    private val stringRuleCache = hashMapOf<String, List<SourceRule>>()
    private val regexCache = hashMapOf<String, Regex?>()
    private val scriptCache = hashMapOf<String, JsCompiledScript>()
    private var topScopeRef: JsScope? = null

    var coroutineContext: CoroutineContext = EmptyCoroutineContext
        set(value) {
            field = value.minusKey(ContinuationInterceptor)
        }

    private var loggedNonStandardJSON = false

    @JvmOverloads
    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRuleCore {
        if (content == null) throw AssertionError("内容不可空（Content cannot be null）")
        this.content = content
        isJSON = when (content) {
            is Node -> false
            else -> content.toString().isJson()
        }
        setBaseUrl(baseUrl)
        analyzeByXPath = null
        analyzeByJSoup = null
        analyzeByJSonPath = null
        return this
    }

    fun setBaseUrl(baseUrl: String?): AnalyzeRuleCore {
        baseUrl?.let {
            this.baseUrl = baseUrl
        }
        return this
    }

    fun setRedirectUrl(url: String): URL? {
        if (url.isDataUrl()) {
            return redirectUrl
        }
        try {
            redirectUrl = URL(url)
        } catch (e: Exception) {
            SourceDebugLoggers.impl?.log("URL($url) error\n${e.localizedMessage}")
        }
        return redirectUrl
    }

    /**
     * redirectUrl 转绝对地址。commonMain 只有 String 门面, 会 substringBefore(",") 截断含逗号的 URL;
     * JVM 端子类 override 走 java.net.URL 重载, 与原版 AnalyzeRule 行为一致。
     */
    protected open fun getAbsoluteURL(redirectUrl: URL?, relativePath: String): String {
        return NetworkUtils.getAbsoluteURL(redirectUrl?.toString(), relativePath)
    }

    /**
     * 获取XPath解析类
     */
    private fun getAnalyzeByXPath(o: Any): AnalyzeByXPath {
        return if (o != content) {
            AnalyzeByXPath(o)
        } else {
            if (analyzeByXPath == null) {
                analyzeByXPath = AnalyzeByXPath(content!!)
            }
            analyzeByXPath!!
        }
    }

    /**
     * 获取JSOUP解析类
     */
    private fun getAnalyzeByJSoup(o: Any): AnalyzeByJSoup {
        return if (o != content) {
            AnalyzeByJSoup(o)
        } else {
            if (analyzeByJSoup == null) {
                analyzeByJSoup = AnalyzeByJSoup(content!!)
            }
            analyzeByJSoup!!
        }
    }

    /**
     * 获取JSON解析类
     */
    private fun getAnalyzeByJSonPath(o: Any): AnalyzeByJSonPath {
        return if (o != content) {
            AnalyzeByJSonPath(o)
        } else {
            if (analyzeByJSonPath == null) {
                analyzeByJSonPath = AnalyzeByJSonPath(content!!)
            }
            analyzeByJSonPath!!
        }
    }

    /**
     * 获取文本列表
     */
    @JvmOverloads
    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        if (rule.isNullOrEmpty()) return null
        val ruleList = splitSourceRuleCacheString(rule)
        return getStringList(ruleList, mContent, isUrl)
    }

    @JvmOverloads
    fun getStringList(
        ruleList: List<SourceRule>,
        mContent: Any? = null,
        isUrl: Boolean = false
    ): List<String>? {
        var result: Any? = null
        val content = mContent ?: this.content
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            // JS 返回的对象在 Kotlin 侧是 NativeObject (对齐 rhino NativeObject)
            // JsonPath 返回的普通 Map 走 else 分支, 执行后续 JS/JsonPath 等
            val jsObj = JsEngines.asJsObject(result)
            if (jsObj != null) {
                val sourceRule = ruleList.first()
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(jsObj)
                result = if (sourceRule.getParamSize() > 1) {
                    // get {{}}
                    sourceRule.rule
                } else {
                    // 键值直接访问
                    jsObj[sourceRule.rule]
                }
                result?.let {
                    if (sourceRule.replaceRegex.isNotEmpty() && it is List<*>) {
                        result = it.map { o ->
                            replaceRegex(o.toString(), sourceRule)
                        }
                    } else if (sourceRule.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(result.toString(), sourceRule)
                    }
                }
            } else {
                for (sourceRule in ruleList) {
                    putRule(sourceRule.putMap)
                    sourceRule.makeUpRule(result)
                    result ?: continue
                    val rule = sourceRule.rule
                    if (rule.isNotEmpty()) {
                        result = when (sourceRule.mode) {
                            Mode.Js -> evalJS(rule, result)
                            Mode.Json -> getAnalyzeByJSonPath(result).getStringList(rule)
                            Mode.XPath -> getAnalyzeByXPath(result).getStringList(rule)
                            Mode.Default -> getAnalyzeByJSoup(result).getStringList(rule)
                            else -> rule
                        }
                    }
                    if (sourceRule.replaceRegex.isNotEmpty() && result is List<*>) {
                        val newList = ArrayList<String>()
                        for (item in result) {
                            newList.add(replaceRegex(item.toString(), sourceRule))
                        }
                        result = newList
                    } else if (sourceRule.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(result.toString(), sourceRule)
                    }
                }
            }
        }
        if (result == null) return null
        if (result is String) {
            result = result.split("\n")
        }
        if (isUrl) {
            val urlList = ArrayList<String>()
            if (result is List<*>) {
                for (url in result) {
                    val absoluteURL = getAbsoluteURL(redirectUrl, url.toString())
                    if (absoluteURL.isNotEmpty() && !urlList.contains(absoluteURL)) {
                        urlList.add(absoluteURL)
                    }
                }
            }
            return urlList
        }
        @Suppress("UNCHECKED_CAST")
        return result as? List<String>
    }

    /**
     * 获取文本
     */
    @JvmOverloads
    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        if (ruleStr.isNullOrEmpty()) return ""
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getString(ruleList, mContent, isUrl)
    }

    fun getString(ruleStr: String?, unescape: Boolean): String {
        if (ruleStr.isNullOrEmpty()) return ""
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getString(ruleList, unescape = unescape)
    }

    @JvmOverloads
    fun getString(
        ruleList: List<SourceRule>,
        mContent: Any? = null,
        isUrl: Boolean = false,
        unescape: Boolean = true
    ): String {
        var result: Any? = null
        val content = mContent ?: this.content
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            // JS 返回的对象在 Kotlin 侧是 NativeObject (对齐 rhino NativeObject)
            // JsonPath 返回的普通 Map 走 else 分支, 执行后续 JS/JsonPath 等
            // 否则 `$.postTime<js>格式化</js>` 这类规则只取到原始时间戳,JS 被跳过。
            val jsObj = JsEngines.asJsObject(result)
            if (jsObj != null) {
                val sourceRule = ruleList.first()
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(jsObj)
                result = if (sourceRule.getParamSize() > 1) {
                    // get {{}}
                    sourceRule.rule
                } else {
                    // 键值直接访问
                    jsObj[sourceRule.rule]?.toString()
                }?.let {
                    replaceRegex(it, sourceRule)
                }
            } else {
                for (sourceRule in ruleList) {
                    putRule(sourceRule.putMap)
                    sourceRule.makeUpRule(result)
                    result ?: continue
                    val rule = sourceRule.rule
                    if (rule.isNotBlank() || sourceRule.replaceRegex.isEmpty()) {
                        result = when (sourceRule.mode) {
                            Mode.Js -> evalJS(rule, result)
                            Mode.Json -> getAnalyzeByJSonPath(result).getString(rule)
                            Mode.XPath -> getAnalyzeByXPath(result).getString(rule)
                            Mode.Default -> if (isUrl) {
                                getAnalyzeByJSoup(result).getString0(rule)
                            } else {
                                getAnalyzeByJSoup(result).getString(rule)
                            }

                            else -> rule
                        }
                    }
                    if (result != null && sourceRule.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(result.toString(), sourceRule)
                    }
                }
            }
        }
        if (result == null) result = ""
        val resultStr = result.toString()
        val str = if (unescape && resultStr.indexOf('&') > -1) {
            EscapeUtils.unescapeHtml(resultStr)
        } else {
            resultStr
        }
        if (str.indexOf("::") > -1) {
            return str
        }
        if (isUrl) {
            return if (str.isBlank()) {
                baseUrl ?: ""
            } else {
                if (str.isDataUrl()) str
                else getAbsoluteURL(redirectUrl, str)
            }
        }
        return str
    }

    /**
     * 获取Element
     */
    fun getElement(ruleStr: String): Any? {
        if (ruleStr.isNullOrEmpty()) return null
        var result: Any? = null
        val content = this.content
        val ruleList = splitSourceRule(ruleStr, true)
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(result)
                result ?: continue
                val rule = sourceRule.rule
                result = when (sourceRule.mode) {
                    Mode.Regex -> AnalyzeByRegex.getElement(
                        result.toString(),
                        rule.splitNotBlank("&&")
                    )

                    Mode.Js -> evalJS(rule, result)
                    Mode.Json -> getAnalyzeByJSonPath(result).getObject(rule)
                    Mode.XPath -> getAnalyzeByXPath(result).getElements(rule)
                    else -> getAnalyzeByJSoup(result).getElements(rule)
                }
                if (sourceRule.replaceRegex.isNotEmpty()) {
                    result = replaceRegex(result.toString(), sourceRule)
                }
            }
        }
        return result
    }

    /**
     * 获取列表
     */
    @Suppress("UNCHECKED_CAST")
    fun getElements(ruleStr: String): List<Any> {
        var result: Any? = null
        val content = this.content
        val ruleList = splitSourceRule(ruleStr, true)
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                result ?: continue
                val rule = sourceRule.rule
                result = when (sourceRule.mode) {
                    Mode.Regex -> AnalyzeByRegex.getElements(
                        result.toString(),
                        rule.splitNotBlank("&&")
                    )

                    Mode.Js -> evalJS(rule, result)
                    Mode.Json -> getAnalyzeByJSonPath(result).getList(rule)
                    Mode.XPath -> getAnalyzeByXPath(result).getElements(rule)
                    else -> getAnalyzeByJSoup(result).getElements(rule)
                }
            }
        }
        result?.let {
            return it as List<Any>
        }
        return ArrayList()
    }

    /**
     * 保存变量
     */
    private fun putRule(map: Map<String, String>) {
        for ((key, value) in map) {
            put(key, getString(value))
        }
    }

    /**
     * 分离put规则
     */
    private fun splitPutRule(ruleStr: String, putMap: HashMap<String, String>): String {
        var vRuleStr = ruleStr
        // Pattern.matcher → Regex.findAll: match.value 对应 matcher.group(), groupValues[1] 对应 matcher.group(1)
        for (match in putPattern.findAll(vRuleStr)) {
            vRuleStr = vRuleStr.replace(match.value, "")
            val putJsonStr = match.groupValues[1]
            // 复刻原 GSONStrict.fromJsonObject<Map<String, String>>(putJsonStr).getOrNull() ?: GSON.fromJsonObject<...>.getOrNull() 双栈
            // KS_JSON_STRICT 严格解析失败时降级到 KS_JSON 宽松解析 (并触发一次 log 提示 JSON 格式不规范)
            val strictMap = try {
                KS_JSON_STRICT.decodeFromString<Map<String, String>>(putJsonStr)
            } catch (_: Exception) {
                null
            }
            if (strictMap != null) {
                putMap.putAll(strictMap)
                continue
            }
            val lenientMap = decodeStringMapOrNull(putJsonStr)
            if (lenientMap != null) {
                if (!loggedNonStandardJSON) {
                    SourceDebugLoggers.impl?.log("≡@put 规则 JSON 格式不规范，请改为规范格式")
                    loggedNonStandardJSON = true
                }
                putMap.putAll(lenientMap)
            }
        }
        return vRuleStr
    }

    /**
     * 正则替换
     */
    private fun replaceRegex(result: String, rule: SourceRule): String {
        if (rule.replaceRegex.isEmpty()) return result
        val replaceRegex = rule.replaceRegex
        val replacement = rule.replacement
        val regex = compileRegexCache(replaceRegex)
        if (rule.replaceFirst) {
            /* ##match##replace### 获取第一个匹配到的结果并进行替换 */
            if (regex != null) kotlin.runCatching {
                // regex.toPattern().matcher → regex.find: match.value 对应 matcher.group(0) (整个匹配)
                val match = regex.find(result)
                return if (match != null) {
                    match.value.replaceFirst(regex, replacement)
                } else {
                    ""
                }
            }
            return replacement
        } else {
            /* ##match##replace 替换*/
            if (regex != null) kotlin.runCatching {
                return result.replace(regex, replacement)
            }
            return result.replace(replaceRegex, replacement)
        }
    }

    private fun compileRegexCache(regex: String): Regex? {
        return regexCache.getOrPutLimit(regex, 16) {
            try {
                regex.toRegex()
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * getString 类规则缓存
     */
    private fun splitSourceRuleCacheString(ruleStr: String?): List<SourceRule> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        return stringRuleCache.getOrPut(ruleStr) {
            splitSourceRule(ruleStr)
        }
    }

    /**
     * 分解规则生成规则列表
     */
    fun splitSourceRule(ruleStr: String?, allInOne: Boolean = false): List<SourceRule> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        val ruleList = ArrayList<SourceRule>()
        var mMode: Mode = Mode.Default
        var start = 0
        //仅首字符为:时为AllInOne，其实:与伪类选择器冲突，建议改成?更合理
        if (allInOne && ruleStr.startsWith(":")) {
            mMode = Mode.Regex
            isRegex = true
            start = 1
        } else if (isRegex) {
            mMode = Mode.Regex
        }
        var tmp: String
        // Pattern.matcher → Regex.findAll: groups[n]?.value 对应 matcher.group(n) (null 若组未参与匹配)
        for (match in JS_PATTERN.findAll(ruleStr)) {
            if (match.range.first > start) {
                tmp = ruleStr.substring(start, match.range.first).trim()
                if (tmp.isNotEmpty()) {
                    ruleList.add(SourceRule(tmp, mMode))
                }
            }
            ruleList.add(SourceRule(match.groups[2]?.value ?: match.groups[1]?.value ?: "", Mode.Js))
            start = match.range.last + 1
        }

        if (ruleStr.length > start) {
            tmp = ruleStr.substring(start).trim()
            if (tmp.isNotEmpty()) {
                ruleList.add(SourceRule(tmp, mMode))
            }
        }

        return ruleList
    }

    private fun getOrCreateSingleSourceRule(rule: String): List<SourceRule> {
        return stringRuleCache.getOrPutLimit(rule, 16) {
            listOf(SourceRule(rule))
        }
    }

    /**
     * 规则类
     */
    inner class SourceRule internal constructor(
        ruleStr: String,
        internal var mode: Mode = Mode.Default
    ) {
        internal var rule: String
        internal var replaceRegex = ""
        internal var replacement = ""
        internal var replaceFirst = false
        internal val putMap = HashMap<String, String>()
        private val ruleParam = ArrayList<String>()
        private val ruleType = ArrayList<Int>()
        private val getRuleType = -2
        private val jsRuleType = -1
        private val defaultRuleType = 0

        init {
            rule = when {
                mode == Mode.Js || mode == Mode.Regex -> ruleStr
                ruleStr.startsWith("@CSS:", true) -> {
                    mode = Mode.Default
                    ruleStr
                }

                ruleStr.startsWith("@@") -> {
                    mode = Mode.Default
                    ruleStr.substring(2)
                }

                ruleStr.startsWith("@XPath:", true) -> {
                    mode = Mode.XPath
                    ruleStr.substring(7)
                }

                ruleStr.startsWith("@Json:", true) -> {
                    mode = Mode.Json
                    ruleStr.substring(6)
                }

                isJSON || ruleStr.startsWith("$.") || ruleStr.startsWith("$[") -> {
                    mode = Mode.Json
                    ruleStr
                }

                ruleStr.startsWith("/") -> {//XPath特征很明显,无需配置单独的识别标头
                    mode = Mode.XPath
                    ruleStr
                }

                else -> ruleStr
            }
            //分离put
            rule = splitPutRule(rule, putMap)
            //@get,{{ }}, 拆分
            var start = 0
            var tmp: String
            // Pattern.matcher → Regex.findAll: 先收集所有匹配, 替代 while(find)+do-while(find) 语义;
            // match.range.first 对应 matcher.start(), match.range.last + 1 对应 matcher.end()
            val evalMatches = evalPattern.findAll(rule).toList()
            if (evalMatches.isNotEmpty()) {
                val firstMatch = evalMatches.first()
                tmp = rule.substring(start, firstMatch.range.first)
                if (mode != Mode.Js && mode != Mode.Regex &&
                    (firstMatch.range.first == 0 || !tmp.contains("##"))
                ) {
                    mode = Mode.Regex
                }
                for (match in evalMatches) {
                    if (match.range.first > start) {
                        tmp = rule.substring(start, match.range.first)
                        splitRegex(tmp)
                    }
                    tmp = match.value
                    when {
                        tmp.startsWith("@get:", true) -> {
                            ruleType.add(getRuleType)
                            ruleParam.add(tmp.substring(6, tmp.lastIndex))
                        }

                        tmp.startsWith("{{") -> {
                            ruleType.add(jsRuleType)
                            ruleParam.add(tmp.substring(2, tmp.length - 2))
                        }

                        else -> {
                            splitRegex(tmp)
                        }
                    }
                    start = match.range.last + 1
                }
            }
            if (rule.length > start) {
                tmp = rule.substring(start)
                splitRegex(tmp)
            }
        }

        /**
         * 拆分\$\d{1,2}
         */
        private fun splitRegex(ruleStr: String) {
            var start = 0
            var tmp: String
            val ruleStrArray = ruleStr.split("##")
            // Pattern.matcher → Regex.findAll: 先收集所有匹配, 替代 while(find)+do-while(find) 语义
            val regexMatches = regexPattern.findAll(ruleStrArray[0]).toList()
            if (regexMatches.isNotEmpty()) {
                if (mode != Mode.Js && mode != Mode.Regex) {
                    mode = Mode.Regex
                }
                for (match in regexMatches) {
                    if (match.range.first > start) {
                        tmp = ruleStr.substring(start, match.range.first)
                        ruleType.add(defaultRuleType)
                        ruleParam.add(tmp)
                    }
                    tmp = match.value
                    ruleType.add(tmp.substring(1).toInt())
                    ruleParam.add(tmp)
                    start = match.range.last + 1
                }
            }
            if (ruleStr.length > start) {
                tmp = ruleStr.substring(start)
                ruleType.add(defaultRuleType)
                ruleParam.add(tmp)
            }
        }

        /**
         * 替换@get,{{ }}
         */
        fun makeUpRule(result: Any?) {
            val infoVal = StringBuilder()
            if (ruleParam.isNotEmpty()) {
                var index = ruleParam.size
                while (index-- > 0) {
                    val regType = ruleType[index]
                    when {
                        regType > defaultRuleType -> {
                            @Suppress("UNCHECKED_CAST")
                            (result as? List<String?>)?.run {
                                if (this.size > regType) {
                                    this[regType]?.let {
                                        infoVal.insert(0, it)
                                    }
                                }
                            } ?: infoVal.insert(0, ruleParam[index])
                        }

                        regType == jsRuleType -> {
                            if (isRule(ruleParam[index])) {
                                val ruleList = getOrCreateSingleSourceRule(ruleParam[index])
                                getString(ruleList).let {
                                    infoVal.insert(0, it)
                                }
                            } else {
                                val jsEval: Any? = evalJS(ruleParam[index], result)
                                when {
                                    jsEval == null -> Unit
                                    jsEval is String -> infoVal.insert(0, jsEval)
                                    jsEval is Double && jsEval % 1.0 == 0.0 -> infoVal.insert(
                                        0,
                                        formatDoubleNoDecimal(jsEval)
                                    )

                                    else -> infoVal.insert(0, jsEval.toString())
                                }
                            }
                        }

                        regType == getRuleType -> {
                            infoVal.insert(0, get(ruleParam[index]))
                        }

                        else -> infoVal.insert(0, ruleParam[index])
                    }
                }
                rule = infoVal.toString()
            }
            //分离正则表达式
            val ruleStrS = rule.split("##")
            rule = ruleStrS[0].trim()
            if (ruleStrS.size > 1) {
                replaceRegex = ruleStrS[1]
            }
            if (ruleStrS.size > 2) {
                replacement = ruleStrS[2]
            }
            if (ruleStrS.size > 3) {
                replaceFirst = true
            }
        }

        private fun isRule(ruleStr: String): Boolean {
            return ruleStr.startsWith('@') //js首个字符不可能是@，除非是装饰器，所以@开头规定为规则
                    || ruleStr.startsWith("$.")
                    || ruleStr.startsWith("$[")
                    || ruleStr.startsWith("//")
        }

        fun getParamSize(): Int {
            return ruleParam.size
        }
    }

    enum class Mode {
        XPath, Json, Default, Js, Regex
    }

    /**
     * 保存数据
     */
    fun put(key: String, value: String): String {
        if (key == "bookName" || key == "title") {
            SourceDebugLoggers.impl?.log("≡变量 $key 在特定情况下会被覆盖，建议使用其他键名")
        }
        chapter?.putVariable(key, value)
            ?: ruleData?.putVariable(key, value)
            ?: source?.put(key, value)
        return value
    }

    /**
     * 获取保存的数据
     */
    fun get(key: String): String {
        when (key) {
            "bookName" -> (ruleData as? BookLike)?.let {
                return it.name
            }

            "title" -> chapter?.let {
                return it.title
            }
        }
        return chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: source?.get(key)?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    /**
     * 执行JS
     *
     * quickjs 没有 rhino 的 prototype 继承链, 不能直接 `bindings.prototype = topScope`,
     * 这里用 IIFE + eval 包裹用户 JS, bindings 直接注入 topScope 的 globalThis:
     * - let/const 留在 eval 词法环境(或 IIFE 函数作用域), 不污染 topScope
     *   (避免重复执行报 "redeclaration of 'xxx'")
     * - return 在 IIFE 函数内生效(对齐 rhino 顶层 return 扩展)
     * - eval 返回末尾表达式值(对齐 rhino script.exec 返回最后一个表达式)
     * - bindings 通过 [io.legado.app.model.script.JsEngine.injectBindings] 写入 globalThis, jsLib 里
     *   定义在 topScope 上的自由函数 (如 `lk`) 内部访问 `cache` 等 binding 也能命中,
     *   执行后由 [io.legado.app.model.script.JsEngine.evalInSubScope] 调 cleanupBindings 删除, 避免残留。
     *
     * 注: jsLib 在 topScope(SharedJsScope)上执行,是共享的,不需要包裹。
     * 这里只包裹 AnalyzeRule 的即开即走 JS。
     */
    fun evalJS(jsStr: String, result: Any? = null): Any? {
        // 空字符串早返回，避免不必要的编译执行开销
        if (jsStr.isBlank()) return null
        val bindings = buildScriptBindings { bindings ->
            variables?.forEach { (k, v) -> bindings[k.key] = v }
            bindings["java"] = this
            bindings["cookie"] = SourceNetworkProviders.impl?.asBinding()
            bindings["cache"] = SourceCacheProviders.impl?.asBinding()
            bindings["source"] = source
            bindings["book"] = ruleData as? BookLike
            bindings["result"] = result
            bindings["baseUrl"] = baseUrl
            bindings["chapter"] = chapter
            bindings["title"] = chapter?.title
            bindings["src"] = content
            bindings["nextChapterUrl"] = nextChapterUrl
            bindings.dangerousApi = source?.enableDangerousApi == true
        }
        val topScope = source?.getShareScope(coroutineContext) ?: topScopeRef
        // - topScope == null: 创建独立 scope, bindings 注入 globalThis
        // - sharedScope 路径: SharedJsScope 缓存的 topScope (ThreadLocal 线程独占),
        //   bindings 也注入到该 topScope 的 globalThis, 执行后由 evalInSubScope 自行清理,
        //   既能让 jsLib 自由函数命中 binding, 又不会跨 evalJS 残留。
        return if (topScope == null) {
            val scope = JsEngines.get().getRuntimeScope(bindings)
            topScopeRef = scope
            val wrappedJs = JsEngines.get().wrapJsForEval(jsStr)
            compileScriptCache(wrappedJs).eval(scope, coroutineContext)
        } else {
            JsEngines.get().evalInSubScope(
                compileSubScopeCache(jsStr),
                topScope,
                bindings,
                coroutineContext
            )
        }
    }

    private fun compileScriptCache(jsStr: String): JsCompiledScript {
        return scriptCache.getOrPutLimit(jsStr, 16) {
            JsEngines.get().compile(jsStr)
        }
    }

    private fun compileSubScopeCache(jsStr: String): JsCompiledScript {
        // 与 compileScriptCache 共用 LRU, 但 key 用 "sub:" 前缀避免与 wrapJsForEval 路径冲突
        return scriptCache.getOrPutLimit("sub:$jsStr", 16) {
            JsEngines.get().compileForSubScope(jsStr)
        }
    }

    override fun getSource(): BaseSource? {
        return source
    }

    /**
     * js实现跨域访问,不能删
     *
     * 注: 本方法 override [JsExtensionsCommon.ajax] 默认实现, 传递 ruleData (行为更完整,
     * commonMain 默认实现只传 source)。app 端 [AnalyzeRule] 可继续 override 转发到
     * super<AnalyzeRuleCore>.ajax 选择本方法。
     * 此处用 [AnalyzeUrlCore] (shared 同包) 替代 app 端 AnalyzeUrl, 行为等价 (ajax 只用 getStrResponse)。
     */
    override fun ajax(url: Any): String? {
        val urlStr = if (url is List<*>) {
            url.firstOrNull().toString()
        } else {
            url.toString()
        }
        val analyzeUrl = AnalyzeUrlCore(
            urlStr,
            source = source,
            ruleData = ruleData,
            coroutineContext = coroutineContext
        )
        return kotlin.runCatching {
            analyzeUrl.getStrResponse().body
        }.onFailure {
            coroutineContext.ensureActive()
            SourceDebugLoggers.impl?.log("ajax(${urlStr}) error\n${it.stackTraceToString()}")
            it.printOnDebug()
        }.getOrElse {
            it.stackTraceStr
        }
    }

    /**
     * 更新tocUrl,有些书源目录url定期更新,可以在js调用更新
     *
     * P2 Step 2: 经 [io.legado.app.model.webBook.BookInfoRefreshers] provider 反向调用 app 端 WebBook.getBookInfoAwait,
     * 解除 AnalyzeRule→WebBook 直接依赖, 为 AnalyzeRule 主体下沉 shared 做前置。
     *
     * 本方法依赖 app 端 BookSource/Book 类型, 无法在 shared 中实现, 留作 open fun
     * 由 app 端 [AnalyzeRule] override 实现原逻辑。
     * KSP @JsApi 分派表通过继承链自动包含本方法名, 零 diff。
     */
    open fun refreshTocUrl() {
        // app 端 AnalyzeRule 实现
    }

    /**
     * 释放 AnalyzeRule 持有的 native 资源。
     *
     * 仅 [topScopeRef] 需显式 close: 当 source 为 null (DictRule/DirectLinkUpload 等无书源构造) 时,
     * evalJS 会走 [JsEngines.get().getRuntimeScope] 自建 scope 并缓存到 [topScopeRef],
     * 该 scope 不在 SharedJsScope 的 LruCache 中, 无显式释放路径会泄漏 native QuickJs 实例。
     *
     * 有 source 路径走 SharedJsScope 共享 scope, 由其 LruCache 淘汰时 close, 此处 topScopeRef 为 null, close 是空操作。
     */
    override fun close() {
        topScopeRef?.let {
            topScopeRef = null
            it.close()
        }
    }

    companion object {
        // Pattern.compile → Regex: setOf(RegexOption.IGNORE_CASE) 对应 Pattern.CASE_INSENSITIVE
        private val putPattern = Regex("@put:(\\{[^}]+?\\})", setOf(RegexOption.IGNORE_CASE))
        private val evalPattern =
            Regex("@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", setOf(RegexOption.IGNORE_CASE))
        private val regexPattern = Regex("\\$\\d{1,2}")
    }

}
