package io.legado.app.model.script

import android.provider.Settings
import io.legado.app.constant.AndroidIdHolder
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourceJsExt
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.HttpTTSJsExt
import io.legado.app.help.CacheManager
import io.legado.app.help.DEFAULT_DATA_ASSET_PREFIX
import io.legado.app.help.ExploreKindsCacheProvider
import io.legado.app.help.ExploreKindsCacheProviders
import io.legado.app.help.JsExtFactory
import io.legado.app.help.JsExtProviders
import io.legado.app.help.RuleBigDataHelp
import io.legado.app.help.RuleBigDataProviders
import io.legado.app.help.source.SourceCacheProvider
import io.legado.app.help.source.SourceCacheProviders
import io.legado.app.help.source.SourceDebugLogger
import io.legado.app.help.source.SourceDebugLoggers
import io.legado.app.help.source.SourceNetworkProvider
import io.legado.app.help.source.SourceNetworkProviders
import io.legado.app.help.UserAgentProvider
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.OkHttpProxyClientProvider
import io.legado.app.help.http.OkHttpProxyClientProviders
import io.legado.app.help.http.getProxyClient
import io.legado.app.model.Debug
import io.legado.app.help.image.BitmapImageOps
import io.legado.app.model.SharedJsScope
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.model.script.quickjs.QuickJsJsEngine
import io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider
import io.legado.app.utils.ACache
import io.legado.app.utils.registerAndroidChineseUtils

/**
 * JS 引擎面的安卓绑定注册（引擎实现/共享 scope 缓存/image 实现留 app，抽象面在 shared）。
 *
 * shared 侧 [JsEngines]/[SharedJsScope]/[JsBindingInjector] 走 provider 注入（非 expect/actual），
 * 宿主启动早期 App.onCreate 调 [registerAndroidJsEngines]（任何 JS eval 之前）。
 *
 * 引擎固定 quickjs（rhino 已于 2026-07-17 KMP 裁决弃用，模块及适配层已删除）。
 */
fun registerAndroidJsEngines() {
    JsBindingInjector.registerImageOps(BitmapImageOps)
    JsEngines.registerProvider { type ->
        when (type) {
            JsEngineType.QUICKJS -> QuickJsJsEngine
            else -> error("rhino 已弃用,JsEngines.type 固定 QUICKJS,不应到达 type=$type")
        }
    }
    SharedJsScope.registerProviders { type ->
        when (type) {
            JsEngineType.QUICKJS -> QuickJsSharedJsScopeProvider
            else -> error("rhino 已弃用,JsEngines.type 固定 QUICKJS,不应到达 type=$type")
        }
    }
    // 简繁词典 tc 缓存定位器(含缺失后台拉取), shared ChineseUtils 走 provider 注入
    registerAndroidChineseUtils()

    // shared 侧实体类 (BaseBook/BookChapter) 通过此 provider 访问大变量存储;
    // 任何 shared 实体 putBigVariable/getBigVariable 调用之前必须已注册
    RuleBigDataProviders.impl = RuleBigDataHelp

    AndroidIdHolder.value = Settings.System.getString(
        splitties.init.appCtx.contentResolver, Settings.Secure.ANDROID_ID
    ) ?: "null"
    SourceCacheProviders.impl = object : SourceCacheProvider {
        override fun get(key: String) = CacheManager.get(key)
        override fun put(key: String, value: String) = CacheManager.put(key, value)
        override fun delete(key: String) = CacheManager.delete(key)
        override fun getFromMemory(key: String) = CacheManager.getFromMemory(key)
        override fun putMemory(key: String, value: Any) = CacheManager.putMemory(key, value)
        override fun deleteMemory(key: String) = CacheManager.deleteMemory(key)
        override fun asBinding(): Any = CacheManager
    }
    SourceNetworkProviders.impl = object : SourceNetworkProvider {
        override fun getCookie(tag: String) = CookieStore.getCookie(tag)
        override fun replaceCookie(tag: String, cookie: String) = CookieStore.replaceCookie(tag, cookie)
        override fun removeCookie(tag: String) = CookieStore.removeCookie(tag)
        override fun asBinding(): Any = CookieStore
    }
    // shared webBook 编排层创建 AnalyzeRule 走此工厂: 返回 app 端 AnalyzeRule 子类,
    // 补全 JsExtensions 面 (md5Encode/createSymmetricCrypto/get/post 等) 并命中 KSP @JsApi 分派表。
    AnalyzeRuleFactories.register { ruleData, source, preUpdateJs ->
        AnalyzeRule(ruleData, source, preUpdateJs)
    }
    // shared 各处创建 AnalyzeUrl 同理走工厂: url 内 <js>/{{}}/js 头等场景的 java 绑定
    // 恢复完整 JsExtensions 面 (createSymmetricCrypto 等) 并命中 KSP @JsApi 分派表。
    AnalyzeUrlFactories.register {
            rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
            coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables ->
        AnalyzeUrl(
            rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
            coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables
        )
    }
    SourceDebugLoggers.impl = object : SourceDebugLogger {
        override fun log(key: String, msg: String, print: Boolean, state: Int) =
            Debug.log(key, msg, print = print, state = state)

        override fun log(msg: String) = Debug.log(msg)
    }
    // F2: BookSource/HttpTTS 下沉后不再继承 JsExtensions, 通过包装器补回 JS 可见的 JsExtensions 面。
    // BaseSource.evalJS 注入 bindings["java"]/["source"] 时调用 wrap(this) 取得 BookSourceJsExt/HttpTTSJsExt。
    JsExtProviders.register(object : JsExtFactory {
        override fun wrap(source: BaseSource): Any = when (source) {
            is BookSource -> BookSourceJsExt(source)
            is HttpTTS -> HttpTTSJsExt(source)
            // 兜底: 已是 JsExtensions 的对象 (如 AnalyzeUrl/AnalyzeRule, 但它们不继承 BaseSource,
            // 实际不会命中) 直接返回。未知 BaseSource 子类无 JsExtensions 实现, JS 调用 ajax 等会失败,
            // 但当前仅 BookSource/HttpTTS 两个 BaseSource 子类, 行为可控。
            else -> source
        }
    })
    // F2: BookSource 下沉后访问 ACache.get("explore").getAsString/put(...) 走 provider, 行为不变。
    // exploreKinds() 已下沉到 shared BookSourceExtensionsShared.kt, 写入侧 (JS 解析出 ruleStr 后 put)
    // 与读侧 (getAsString) 均经此 provider 转发到 ACache。
    // clearExploreKindsCache (shared) 清理 exploreKindsMap 内存缓存后调 remove, 此处仅清理 ACache 磁盘缓存。
    ExploreKindsCacheProviders.impl = object : ExploreKindsCacheProvider {
        override fun getAsString(key: String): String? = ACache.get("explore").getAsString(key)
        override fun put(key: String, value: String) = ACache.get("explore").put(key, value)
        override fun remove(key: String) {
            ACache.get("explore").remove(key)
        }
    }
    UserAgentProviders.impl = UserAgentProvider { AppConfig.userAgent }
    // shared 侧 OkHttp 代理客户端实现注入: 转发到 app 端 HttpHelper.getProxyClient
    OkHttpProxyClientProviders.impl = object : OkHttpProxyClientProvider {
        override fun getProxyClient(proxy: String?) = io.legado.app.help.http.getProxyClient(proxy)
    }
    // DefaultData 下沉 shared 后, 资源读取走 provider 注入。
    // 必须在 registerAndroidWebBookProviders() 之前注册: appDb lazy 初始化触发 dbCallback.onCreate
    // 时会访问 DefaultData.keyboardAssists (经 DefaultDataShared 间接读 assets)。
    // 注册时机: App.onCreate 中 registerAndroidJsEngines() 先于 registerAndroidWebBookProviders() 调用。
    io.legado.app.help.DefaultDataResourceProviders.register(
        object : io.legado.app.help.DefaultDataResourceProvider {
            override fun readResource(name: String): String {
                // 单一数据源在 shared/commonMain/composeResources/files/defaultData/,
                // 由 compose 资源插件打进 assets (前缀含模块限定名, 同 AndroidWebAssetSource)。
                return String(
                    splitties.init.appCtx.assets
                        .open("$DEFAULT_DATA_ASSET_PREFIX$name").readBytes()
                )
            }
        }
    )
}
