package io.legado.app.data.entities

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.androidId
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.help.crypto.CryptoHelper
import io.legado.app.help.JsExtProviders
import io.legado.app.help.source.SourceCacheProviders
import io.legado.app.help.source.SourceDebugLoggers
import io.legado.app.help.source.SourceNetworkProviders
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.JsExtensionsCommon
import io.legado.app.help.coroutine.printOnDebug
import io.legado.app.help.source.getShareScope
import io.legado.app.model.script.JsBindings
import io.legado.app.model.script.JsEngines
import io.legado.app.model.script.buildScriptBindings
import io.legado.app.utils.FlowBus
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.KS_JSON_STRICT
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.has

/**
 * 可在js里调用,source.xxx()
 */
@com.script.jsdispatch.JsApi
@Suppress("unused")
interface BaseSource : JsExtensionsCommon {
    /**
     * 并发率
     */
    var concurrentRate: String?

    /**
     * 登录地址
     */
    var loginUrl: String?

    /**
     * 登录UI
     */
    var loginUi: String?

    /**
     * 请求头
     */
    var header: String?

    /**
     * 启用cookieJar
     */
    var enabledCookieJar: Boolean?

    /*
    * 高危api
    * */
    var enableDangerousApi: Boolean?

    /**
     * js库
     */
    var jsLib: String?

    fun getTag(): String

    fun getKey(): String

    fun getSourceType(): Int

    /**
     * F2: 行为对齐 app 端 JsExtensions.log, 保证 BookSource/HttpTTS 下沉后去掉
     * `override fun log(msg) = super<JsExtensions>.log(msg)` 仍能将调试日志推到
     * SourceDebugLoggers (Debug 面板)。原 JsExtensions.log 还含 jsContextOrNull?.ensureActive(),
     * 但 BaseSource.log 在非 JS 上下文也会被调用 (如 getHeaderMap 内部 log), 不能假设有 JS context,
     * 故不引入 ensureActive; JS 调用 source.log(...) 时仍走包装器 JsExtensions.log 默认实现 (含 ensureActive)。
     */
    override fun log(msg: Any?): Any? {
        val msgStr = msg.toString()
        SourceDebugLoggers.impl?.log(getKey(), msgStr)
        AppLog.putDebug("${getSource()?.getTag() ?: "源"}调试输出: $msg")
        return msg
    }

    override fun getSource(): BaseSource? {
        return this
    }

    fun loginUi(): List<RowUi>? {
        val uiStr = loginUi ?: return null
        val json = try {
            when {
                uiStr.startsWith("@js:") -> evalJS(uiStr.substring(4)).toString()
                uiStr.startsWith("<js>") -> evalJS(
                    uiStr.substring(4, uiStr.lastIndexOf("<"))
                ).toString()

                else -> uiStr
            }
        } catch (e: Exception) {
            AppLog.put("执行登录UI规则出错\n$e", e)
            return null
        }
        // 复刻原 GSON.fromJsonArray<RowUi>(json).onFailure { it.printOnDebug() }.getOrNull()
        // KS_JSON.decodeFromString 抛异常时打印 debug 并返回 null
        return try {
            KS_JSON.decodeFromString<List<RowUi>>(json)
        } catch (e: Exception) {
            e.printOnDebug()
            null
        }
    }

    fun getLoginJs(): String? {
        val loginJs = loginUrl
        return when {
            loginJs == null -> null
            loginJs.startsWith("@js:") -> loginJs.substring(4)
            loginJs.startsWith("<js>") -> loginJs.substring(4, loginJs.lastIndexOf("<"))
            else -> loginJs
        }
    }

    /**
     * 调用login函数 实现登录请求
     */
    fun login() {
        val loginJs = getLoginJs()
        if (!loginJs.isNullOrBlank()) {
            // K5-c Phase 4: 移除 @Language("js") (org.intellij.lang.annotations 为 JDK 注解, commonMain 不可用)
            val js = """$loginJs
                if(typeof login=='function'){
                    login.apply(this);
                } else {
                    throw('Function login not implements!!!')
                }
            """.trimIndent()
            evalJS(js)
        }
    }

    /**
     * 解析header规则
     */
    fun getHeaderMap(hasLoginHeader: Boolean = false) = getHeaderMap(hasLoginHeader, this::evalJS)
    fun getHeaderMap(
        hasLoginHeader: Boolean = false, evalJs: (String) -> Any?
    ) = HashMap<String, String>().apply {
        header?.let {
            try {
                val json = when {
                    it.startsWith("@js:") -> evalJs(it.substring(4)).toString()
                    it.startsWith("<js>") -> evalJs(
                        it.substring(4, it.lastIndexOf("<"))
                    ).toString()

                    else -> it
                }
                // 复刻原 GSONStrict.fromJsonObject(...).getOrNull() ?: GSON.fromJsonObject(...).getOrNull() 双栈
                // KS_JSON_STRICT 严格解析失败时降级到 KS_JSON 宽松解析 (并打 log 提示 JSON 格式不规范)
                val strictMap = try {
                    KS_JSON_STRICT.decodeFromString<Map<String, String>>(json)
                } catch (_: Exception) {
                    null
                }
                if (strictMap != null) {
                    putAll(strictMap)
                } else {
                    val lenientMap = decodeStringMapOrNull(json)
                    if (lenientMap != null) {
                        log("请求头规则 JSON 格式不规范，请改为规范格式")
                        putAll(lenientMap)
                    }
                }
            } catch (e: Exception) {
                "执行请求头规则出错\n${e.localizedMessage}".let { msg ->
                    AppLog.put(msg, e)
                    SourceDebugLoggers.impl?.log(getKey(), msg, state = -1)
                }
            }
        }
        if (!has(AppConst.UA_NAME, true)) {
            put(AppConst.UA_NAME, UserAgentProviders.get())
        }
        if (hasLoginHeader) {
            getLoginHeaderMap()?.let {
                putAll(it)
            }
        }
    }

    /**
     * 获取用于登录的头部信息
     */
    fun getLoginHeader(): String? {
        return SourceCacheProviders.impl?.get("loginHeader_${getKey()}")
    }

    fun getLoginHeaderMap(): Map<String, String>? {
        val cache = getLoginHeader() ?: return null
        return decodeStringMapOrNull(cache)
    }

    /**
     * 保存登录头部信息,map格式,访问时自动添加
     */
    fun putLoginHeader(header: String) {
        val headerMap = decodeStringMapOrNull(header)
        val cookie = headerMap?.get("Cookie") ?: headerMap?.get("cookie")
        cookie?.let {
            SourceNetworkProviders.impl?.replaceCookie(getKey(), it)
        }
        SourceCacheProviders.impl?.put("loginHeader_${getKey()}", header)
    }

    fun removeLoginHeader() {
        SourceCacheProviders.impl?.delete("loginHeader_${getKey()}")
        SourceNetworkProviders.impl?.removeCookie(getKey())
    }

    /**
     * 获取用户信息,可以用来登录
     * 用户信息采用aes加密存储
     * K5-c Phase 4: AES 解密下沉到 CryptoHelper (expect/actual), commonMain 不再引用 javax.crypto
     */
    fun getLoginInfo(): String? {
        return try {
            val key = AppConst.androidId.encodeToByteArray(0, 16)
            val cache = SourceCacheProviders.impl?.get("userInfo_${getKey()}") ?: return null
            CryptoHelper.decryptAesEcbPkcs5Base64(key, cache)
        } catch (e: Exception) {
            AppLog.put("获取登陆信息出错", e)
            null
        }
    }

    // 返回空 Map 而非 null,避免书源 JS 直接 .get(key) 时 NPE
    fun getLoginInfoMap(): Map<String, String> {
        return decodeStringMapOrNull(getLoginInfo()) ?: emptyMap()
    }

    /**
     * 保存用户信息,aes加密
     * K5-c Phase 4: AES 加密下沉到 CryptoHelper (expect/actual), commonMain 不再引用 javax.crypto
     */
    fun putLoginInfo(info: String): Boolean {
        return try {
            val key = AppConst.androidId.encodeToByteArray(0, 16)
            val encodeStr = CryptoHelper.encryptAesEcbPkcs5Base64(key, info)
            SourceCacheProviders.impl?.put("userInfo_${getKey()}", encodeStr)
            true
        } catch (e: Exception) {
            AppLog.put("保存登陆信息出错", e)
            false
        }
    }

    fun removeLoginInfo() {
        SourceCacheProviders.impl?.delete("userInfo_${getKey()}")
    }

    /**
     * 设置自定义变量
     * @param variable 变量内容
     */
    fun setVariable(variable: String?) {
        if (variable != null) {
            SourceCacheProviders.impl?.put("sourceVariable_${getKey()}", variable)
        } else {
            SourceCacheProviders.impl?.delete("sourceVariable_${getKey()}")
        }
    }

    /**
     * 获取自定义变量
     */
    fun getVariable(): String {
        return SourceCacheProviders.impl?.get("sourceVariable_${getKey()}") ?: ""
    }

    /**
     * 保存数据
     */
    fun put(key: String, value: String): String {
        SourceCacheProviders.impl?.put("v_${getKey()}_${key}", value)
        return value
    }

    /**
     * 获取保存的数据
     */
    fun get(key: String): String {
        return SourceCacheProviders.impl?.get("v_${getKey()}_${key}") ?: ""
    }

    /**
     * 执行JS
     */
    @Throws(Exception::class)
    fun evalJS(jsStr: String, bindingsConfig: JsBindings.() -> Unit = {}): Any? {
        // 空字符串早返回，避免不必要的编译执行开销
        if (jsStr.isBlank()) return null
        // F2: BookSource/HttpTTS 下沉后不再继承 JsExtensions, 注入包装器 (BookSourceJsExt/HttpTTSJsExt)
        // 让 JS 调用 source.ajax(...) 等方法时通过 JsExtensionsJsDispatcher 分派。
        // AnalyzeUrl/AnalyzeRule 等本就实现 JsExtensions 的对象, wrap() 兜底返回原对象。
        val jsBinding = JsExtProviders.get().wrap(this)
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = jsBinding
            bindings["source"] = jsBinding
            bindings["baseUrl"] = getKey()
            bindings["cookie"] = SourceNetworkProviders.impl?.asBinding()
            bindings["cache"] = SourceCacheProviders.impl?.asBinding()
            bindings.apply(bindingsConfig)
            bindings.dangerousApi = enableDangerousApi == true
        }
        val sharedScope = getShareScope()
        // - sharedScope == null: 创建独立 scope, bindings 注入 globalThis
        // - sharedScope 路径: SharedJsScope 缓存的 topScope (ThreadLocal 线程独占),
        //   bindings 注入该 topScope 的 globalThis 后再执行, evalInSubScope 内部清理,
        //   保证 jsLib 自由函数能命中 cache/book 等 binding。
        return if (sharedScope == null) {
            val scope = JsEngines.get().getRuntimeScope(bindings)
            val wrappedJs = JsEngines.get().wrapJsForEval(jsStr)
            try {
                JsEngines.get().eval(wrappedJs, scope, null)
            } finally {
                // 无 jsLib 时创建的独立 scope 必须显式 close,
                // 否则 native JSRuntime/JSContext 只能等 GC + PhantomReference 异步释放
                scope.close()
            }
        } else {
            val compiled = JsEngines.get().compileForSubScope(jsStr)
            JsEngines.get().evalInSubScope(compiled, sharedScope, bindings, null)
        }
    }

    fun hasLogin(): Boolean = !(loginUrl.isNullOrBlank() && loginUi.isNullOrBlank())

    /**
     * 请求弹出登录对话框(供 JS source.showLoginDialog() 调用)。
     * entity 只发纯事件, 弹窗由 app 侧 SourceUiEventBridge 拿 currentActivity 解析。
     */
    fun showLoginDialog() {
        FlowBus.with(EventBus.SOURCE_UI_REQUEST).tryEmit(SourceUiRequest.Login(this))
    }

    /**
     * 请求弹出源变量对话框(供 JS source.showSourceVariableDialog() 调用)。
     */
    fun showSourceVariableDialog() {
        FlowBus.with(EventBus.SOURCE_UI_REQUEST).tryEmit(SourceUiRequest.SourceVariable(this))
    }
}