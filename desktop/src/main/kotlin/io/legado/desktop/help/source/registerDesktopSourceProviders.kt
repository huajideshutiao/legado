package io.legado.desktop.help.source

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.ExploreKindsCacheProvider
import io.legado.app.help.ExploreKindsCacheProviders
import io.legado.app.help.JsExtFactory
import io.legado.app.help.JsExtProviders
import io.legado.app.help.RuleBigDataProvider
import io.legado.app.help.RuleBigDataProviders
import io.legado.app.help.source.SourceCacheProvider
import io.legado.app.help.source.SourceCacheProviders
import io.legado.app.help.source.SourceDebugLogger
import io.legado.app.help.source.SourceDebugLoggers
import io.legado.app.help.source.SourceNetworkProvider
import io.legado.app.help.source.SourceNetworkProviders
import io.legado.app.help.UserAgentProvider
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.model.Debug
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面端 source 扩展 provider 注册入口。
 *
 * 对应 app 端 `registerAndroidJsEngines` 中桥接 app 单例 (CacheManager / CookieStore /
 * Debug / RuleBigDataHelp / ACache / JsExtProviders / UserAgentProviders) 的部分,
 * 桌面端因这些 app 单例都依赖 Android-only 资源 (appDb / appCtx / ACache / SharedPreferences),
 * 改用 in-memory 简化实现, 进程退出即丢失 (与 [io.legado.desktop.http.DesktopCookieJarBridge] 一致)。
 *
 * 注册 7 项 source 扩展 provider:
 * - [SourceDebugLoggers]: 桥接 shared commonMain 的 [Debug] 单例 (已下沉, 直接调用)
 * - [RuleBigDataProviders]: in-memory nested Map (bookUrl/chapterUrl → key → value)
 * - [SourceCacheProviders]: in-memory Map + asBinding 返回自身
 * - [SourceNetworkProviders]: in-memory cookie Map (与 DesktopCookieJarBridge 独立, 互不干扰)
 * - [ExploreKindsCacheProviders]: in-memory Map (替代 ACache.get("explore"))
 * - [JsExtProviders]: wrap 直接返回 source (不补 JsExtensions 面, JS 调用 source.ajax 会失败)
 * - [UserAgentProviders]: 返回 AppConst.UA_NAME (与未注册时 fallback 一致, 显式注册保持一致语义)
 *
 * 注册时机: desktop main 入口, 在 [io.legado.desktop.config.registerDesktopConfig]
 * (PreferenceProviders 就绪) 之后, 任何 shared commonMain 调用 source 扩展面之前。
 */
fun registerDesktopSourceProviders() {
    SourceDebugLoggers.impl = DesktopSourceDebugLogger
    RuleBigDataProviders.impl = DesktopRuleBigDataProvider
    SourceCacheProviders.impl = DesktopSourceCacheProvider
    SourceNetworkProviders.impl = DesktopSourceNetworkProvider
    ExploreKindsCacheProviders.impl = DesktopExploreKindsCacheProvider
    JsExtProviders.register(DesktopJsExtFactory)
    UserAgentProviders.impl = desktopUserAgentProvider
}

/**
 * 桌面端 [SourceDebugLogger] 实现: 桥接 shared commonMain 的 [Debug] 单例。
 *
 * [Debug] 已下沉 commonMain (shared/.../model/Debug.kt), 桌面端直接调用其 log 方法,
 * 与 app 端 JsEnginesAndroid.kt 中的 SourceDebugLoggers.impl 实现行为完全一致。
 *
 * 未注册时 SourceDebugLoggers.impl 为 null, shared 中 SourceDebugLogger 调用方
 * (webBook 编排层 / 书源调试) 会因 NPE 被 runCatching 吞掉, 表现为调试日志缺失。
 */
private object DesktopSourceDebugLogger : SourceDebugLogger {
    override fun log(key: String, msg: String, print: Boolean, state: Int) {
        Debug.log(key, msg, print = print, state = state)
    }

    override fun log(msg: String) {
        Debug.log(msg)
    }
}

/**
 * 桌面端 [RuleBigDataProvider] 实现: in-memory nested Map。
 *
 * app 端 [io.legado.app.help.RuleBigDataHelp] 持久化到 appDb.cacheDao (BigDataKind=book/chapter),
 * 桌面端简化为进程内 Map, 进程退出即丢失 (与桌面端其他 in-memory provider 一致)。
 *
 * 数据结构:
 * - bookVariables: bookUrl → (key → value)
 * - chapterVariables: bookUrl → (chapterUrl → (key → value))
 *
 * 未注册时 shared 实体类 (BaseBook/BookChapter) putBigVariable/getBigVariable 会 NPE,
 * 被 runCatching 吞掉, 表现为 JS 调用 source.getVariable/setVariable 失效。
 */
private object DesktopRuleBigDataProvider : RuleBigDataProvider {

    // bookUrl → (key → value)
    private val bookVariables: ConcurrentHashMap<String, ConcurrentHashMap<String, String?>> =
        ConcurrentHashMap()

    // bookUrl → (chapterUrl → (key → value))
    private val chapterVariables:
        ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, String?>>> =
        ConcurrentHashMap()

    override fun putBookVariable(bookUrl: String, key: String, value: String?) {
        bookVariables.computeIfAbsent(bookUrl) { ConcurrentHashMap() }[key] = value
    }

    override fun getBookVariable(bookUrl: String, key: String?): String? {
        if (key == null) return null
        return bookVariables[bookUrl]?.get(key)
    }

    override fun hasBookVariable(bookUrl: String, key: String): Boolean {
        val map = bookVariables[bookUrl] ?: return false
        return map.containsKey(key)
    }

    override fun putChapterVariable(
        bookUrl: String,
        chapterUrl: String,
        key: String,
        value: String?,
    ) {
        chapterVariables
            .computeIfAbsent(bookUrl) { ConcurrentHashMap() }
            .computeIfAbsent(chapterUrl) { ConcurrentHashMap() }[key] = value
    }

    override fun getChapterVariable(
        bookUrl: String,
        chapterUrl: String,
        key: String,
    ): String? {
        return chapterVariables[bookUrl]?.get(chapterUrl)?.get(key)
    }

    override fun listBookDataDirs(): List<String> = emptyList()

    override fun clearInvalidBookData(bookUrls: Set<String>) {
        // in-memory 实现, 进程退出即丢失, 无需清理
    }
}

/**
 * 桌面端 [SourceCacheProvider] 实现: in-memory Map。
 *
 * app 端 [io.legado.app.help.CacheManager] 持久化到 appDb.cacheDao + 内存 LruCache,
 * 桌面端简化为单层 in-memory Map (不分持久/内存层), asBinding 返回自身供 JS 绑定调用。
 *
 * 未注册时 SourceCacheProviders.impl 为 null, shared 中 SourceCacheProvider 调用方
 * (BookSource.getCache/putCache/deleteCache) 会 NPE 被 runCatching 吞掉,
 * 表现为书源变量缓存 (source.getVariable/setVariable 持久层) 失效。
 */
private object DesktopSourceCacheProvider : SourceCacheProvider {

    // 持久层 (与 CacheManager 的 cacheDao 对应, 桌面端 in-memory 不分持久/内存)
    private val store: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    // 内存层 (与 CacheManager.getFromMemory LruCache 对应, 桌面端复用同一 store 简化)
    private val memoryStore: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

    override fun get(key: String): String? = store[key]
    override fun put(key: String, value: String) { store[key] = value }
    override fun delete(key: String) { store.remove(key) }

    override fun getFromMemory(key: String): Any? = memoryStore[key]
    override fun putMemory(key: String, value: Any) { memoryStore[key] = value }
    override fun deleteMemory(key: String) { memoryStore.remove(key) }

    override fun asBinding(): Any = this
}

/**
 * 桌面端 [SourceNetworkProvider] 实现: in-memory cookie Map。
 *
 * app 端 [io.legado.app.help.http.CookieStore] 持久化到 appDb.cacheDao,
 * 桌面端简化为进程内 Map (与 [io.legado.desktop.http.DesktopCookieJarBridge] 独立,
 * 互不干扰; DesktopCookieJarBridge 处理 OkHttp 拦截器层的 cookie 自动加载/保存,
 * 本 provider 处理 JS 调用 source.getCookie/replaceCookie/removeCookie 的主动读写)。
 *
 * 未注册时 SourceNetworkProviders.impl 为 null, shared 中 SourceNetworkProvider 调用方
 * (BaseSourceJsExt.getCookie 等 JS API) 会 NPE 被 runCatching 吞掉。
 */
private object DesktopSourceNetworkProvider : SourceNetworkProvider {

    // tag → cookie 字符串
    private val cookieStore: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    override fun getCookie(tag: String): String = cookieStore[tag] ?: ""
    override fun replaceCookie(tag: String, cookie: String) { cookieStore[tag] = cookie }
    override fun removeCookie(tag: String) { cookieStore.remove(tag) }
    override fun asBinding(): Any = this
}

/**
 * 桌面端 [ExploreKindsCacheProvider] 实现: in-memory Map。
 *
 * app 端用 ACache.get("explore") (Android 文件缓存) 存发现分类 JSON,
 * 桌面端简化为进程内 Map, 配合 [io.legado.app.help.source.clearExploreKindsCache] (shared)
 * 的 remove 调用清理条目。内存版重启即丢, 但发现分类在书源加载时会重新拉取, 影响可控。
 *
 * getAsString/put 由下沉的 `BookSource.exploreKinds()` (shared) 在 JS 分支调用
 * (读缓存 / 写 JS 解析结果), 与 app 端 ACache 行为对齐。
 *
 * 未注册时 ExploreKindsCacheProviders.impl 为 null, shared 中 BookSource.exploreKindsJson()
 * / exploreKinds() 调用 ExploreKindsCacheProviders.impl?.getAsString(...) 拿到 null,
 * 行为退化 (无缓存), 表现为发现分类每次重新拉取 (性能略差但功能正常)。
 */
private object DesktopExploreKindsCacheProvider : ExploreKindsCacheProvider {

    // key → JSON 字符串
    private val store: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    override fun getAsString(key: String): String? = store[key]
    override fun put(key: String, value: String) { store[key] = value }
    override fun remove(key: String) { store.remove(key) }
}

/**
 * 桌面端 [JsExtFactory] 实现: wrap 直接返回 source。
 *
 * app 端注册 [io.legado.app.data.entities.BookSourceJsExt] / [HttpTTSJsExt] 包装器,
 * 让下沉的 BookSource/HttpTTS 实体经 wrap 后补回 JsExtensions 面 (ajax/connect/webView 等)。
 * 桌面端无 BookSourceJsExt (依赖 app 端 JsExtensions 接口, 含 Android 专属方法), 简化实现:
 *
 * - [BaseSource] 子类 (BookSource/HttpTTS): wrap 直接返回 source, JS 调用 source.xxx 时
 *   走 BaseSourceJsDispatcher (KSP 生成) 委托到 BaseSource 自身方法 (getKey/getHeaderMap 等),
 *   JsExtensions 接口方法 (ajax/connect/webView 等) 因 source 未实现 JsExtensions 会失败
 *   (反射 fallback 抛 NoSuchMethodException), 被 runCatching 吞掉, 行为可控
 * - 已是 JsExtensions 的对象: 直接返回 (与 app 端兜底分支一致)
 *
 * 这是 app 端注释里的"兜底分支", 当前仅 BookSource/HttpTTS 两个 BaseSource 子类,
 * 行为可控; 后续若桌面端引入 BookSourceJsExt 等价实现, 替换本 factory 即可。
 */
private object DesktopJsExtFactory : JsExtFactory {
    override fun wrap(source: BaseSource): Any = source
}

/**
 * 桌面端 [UserAgentProvider] 实现: 从 PreferenceProviders 读 "userAgent" key, 兜底 [AppConst.UA_NAME]。
 *
 * app 端 [io.legado.app.help.UserAgentProvider] lambda 内部返回 `AppConfig.userAgent`,
 * 桌面端 AppConfig 在 app 模块无法直接引用, 改为从 [PreferenceProviders] 读 "userAgent" key
 * (与 app 端 PreferKey.userAgent 对应), 默认返回 [AppConst.UA_NAME] (与未注册时 fallback 一致)。
 *
 * 注册保持语义一致性: 显式注册让 UserAgentProviders.get() 不走 fallback 分支,
 * 后续若桌面端支持自定义 UA 配置, 仅需写 PreferenceProviders 即可生效。
 */
private val desktopUserAgentProvider = UserAgentProvider {
    PreferenceProviders.get().getString("userAgent", AppConst.UA_NAME)
}
