package io.legado.desktop.help.source

import io.legado.app.constant.PreferKey
import io.legado.app.help.CacheManager
import io.legado.app.help.ExploreKindsCacheProvider
import io.legado.app.help.ExploreKindsCacheProviders
import io.legado.app.help.RuleBigDataProviders
import io.legado.app.help.RuleBigDataShared
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.source.SourceCacheProvider
import io.legado.app.help.source.SourceCacheProviders
import io.legado.app.help.source.SourceDebugLogger
import io.legado.app.help.source.SourceDebugLoggers
import io.legado.app.help.http.SharedCookieStore
import io.legado.app.help.source.SourceNetworkProvider
import io.legado.app.help.source.SourceNetworkProviders
import io.legado.app.help.UserAgentProvider
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.registerDesktopJsExtFactory
import io.legado.app.model.Debug
import io.legado.app.utils.ACacheBase
import io.legado.desktop.http.DesktopHttpProvider
import java.io.File

/**
 * 桌面端 source 扩展 provider 注册入口。
 *
 * 对应 app 端 `registerAndroidJsEngines` 中桥接 app 单例 (CacheManager / CookieStore /
 * Debug / RuleBigDataHelp / ACache / JsExtProviders / UserAgentProviders) 的部分,
 * 桌面端复用同名下沉件 (CacheManager/RuleBigDataShared/ACacheBase 均已在 shared),
 * 仅注入桌面路径, 与 app 端一样持久化, 重启不丢。
 *
 * 注册 7 项 source 扩展 provider:
 * - [SourceDebugLoggers]: 桥接 shared commonMain 的 [Debug] 单例 (已下沉, 直接调用)
 * - [RuleBigDataProviders]: [RuleBigDataShared] + 桌面 filesDir/ruleData/book (文件持久化)
 * - [SourceCacheProviders]: 委托 shared [CacheManager] (cacheDao 持久层 + LruCache 内存层)
 * - [SourceNetworkProviders]: 桥接 commonMain SharedCookieStore (Room 持久化, 与 CookieJar 同源)
 * - [ExploreKindsCacheProviders]: [ACacheBase] 子类 + 桌面 cacheDir/explore (对齐 ACache.get("explore"))
 * - JsExtProviders: 走 shared jvmMain 的 [registerDesktopJsExtFactory] (BookSource/HttpTTS 包装器)
 * - [UserAgentProviders]: 读 "userAgent" 配置, 兜底桌面 Chrome UA (与 app AppConfig.userAgent 对齐)
 *
 * 注册时机: desktop main 入口 registerSecondaryProviders, 在
 * [io.legado.app.help.file.registerDesktopAppFilesDir] (目录就绪) /
 * [io.legado.desktop.config.registerDesktopConfig] (PreferenceProviders 就绪) 之后,
 * 任何 shared commonMain 调用 source 扩展面之前。
 */
fun registerDesktopSourceProviders() {
    SourceDebugLoggers.impl = DesktopSourceDebugLogger
    RuleBigDataProviders.impl = RuleBigDataShared(desktopRuleBigDataDir())
    SourceCacheProviders.impl = DesktopSourceCacheProvider
    SourceNetworkProviders.impl = DesktopSourceNetworkProvider
    ExploreKindsCacheProviders.impl = DesktopExploreKindsCacheProvider
    registerDesktopJsExtFactory()
    UserAgentProviders.impl = desktopUserAgentProvider
}

/**
 * 大变量文件根目录, 对照 app 端 `RuleBigDataHelp` 的 `externalFiles/ruleData/book`。
 * 桌面端无外部存储, 落 filesDir (~/.legado/files 或便携目录)。
 */
private fun desktopRuleBigDataDir(): String {
    val dirs = AppFilesDirs.get()
    val root = dirs.externalFilesDir ?: dirs.filesDir
    return File(File(root, "ruleData"), "book").apply { mkdirs() }.absolutePath
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
 * 桌面端 [SourceCacheProvider] 实现: 委托 shared [CacheManager] (与 app 端 JsEnginesAndroid 同构)。
 *
 * 持久层走 cacheDao (Room, 重启保留), 内存层走 CacheManager 的 LruCache,
 * asBinding 返回 CacheManager 本体以命中 @JsApi 静态分派表。
 */
private object DesktopSourceCacheProvider : SourceCacheProvider {

    override fun get(key: String): String? = CacheManager.get(key)
    override fun put(key: String, value: String) = CacheManager.put(key, value)
    override fun delete(key: String) = CacheManager.delete(key)

    override fun getFromMemory(key: String): Any? = CacheManager.getFromMemory(key)
    override fun putMemory(key: String, value: Any) = CacheManager.putMemory(key, value)
    override fun deleteMemory(key: String) = CacheManager.deleteMemory(key)

    override fun asBinding(): Any = CacheManager
}

/**
 * 桌面端 [ExploreKindsCacheProvider] 实现: 对齐 app 端 `ACache.get("explore")`。
 *
 * 复用 shared [ACacheBase] (ACache 的纯 JDK 部分), 缓存目录 `{cacheDir}/explore`,
 * 文件格式/LRU 裁剪与 app 端完全一致, 发现分类解析结果重启后仍命中缓存。
 */
private object DesktopExploreKindsCacheProvider : ExploreKindsCacheProvider {

    // lazy: 注册期 AppFilesDirs 已就绪, 但目录创建推迟到首次读写
    private val cache by lazy { DesktopACache(File(AppFilesDirs.get().cacheDir, "explore")) }

    override fun getAsString(key: String): String? = cache.getAsString(key)
    override fun put(key: String, value: String) = cache.put(key, value)
    override fun remove(key: String) {
        cache.remove(key)
    }
}

/** [ACacheBase] 构造器是 protected, 桌面端开一个薄子类接管目录 (对照 app 端 ACache)。 */
private class DesktopACache(cacheDir: File) : ACacheBase(
    cacheDir, ACacheBase.MAX_SIZE.toLong(), ACacheBase.MAX_COUNT
)

/**
 * 桌面端 [SourceNetworkProvider]: 桥接 commonMain [SharedCookieStore]
 * (对照 app 端 JsEnginesAndroid 中桥接 CookieStore 单例; 原 in-memory Map 版与
 * DesktopCookieJarBridge/业务层三份存储互相隔离, 现统一为 Room 持久化同一份)。
 */
private object DesktopSourceNetworkProvider : SourceNetworkProvider {
    override fun getCookie(tag: String): String = SharedCookieStore.getCookie(tag)
    override fun replaceCookie(tag: String, cookie: String) =
        SharedCookieStore.replaceCookie(tag, cookie)

    override fun removeCookie(tag: String) = SharedCookieStore.removeCookie(tag)
    override fun asBinding(): Any = SharedCookieStore
}

/**
 * 桌面端 [UserAgentProvider] 实现: 对齐 app 端 `AppConfig.userAgent`。
 *
 * app 端 `getPrefUserAgent()` 读 PreferKey.userAgent, 空白时回落到桌面 Chrome UA;
 * 桌面端同构, 兜底值复用 [DesktopHttpProvider.DEFAULT_UA] 与 OkHttp 头注入保持一致。
 */
private val desktopUserAgentProvider = UserAgentProvider {
    PreferenceProviders.get().getString(PreferKey.userAgent, "")
        .takeIf { it.isNotBlank() }
        ?: DesktopHttpProvider.DEFAULT_UA
}
