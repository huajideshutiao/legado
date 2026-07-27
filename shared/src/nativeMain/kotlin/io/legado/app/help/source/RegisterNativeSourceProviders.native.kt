package io.legado.app.help.source

import io.legado.app.constant.AppConst
import io.legado.app.help.RuleBigDataProviders
import io.legado.app.help.RuleBigDataShared
import io.legado.app.help.UserAgentProvider
import io.legado.app.help.UserAgentProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.model.Debug

/**
 * native (iOS/鸿蒙) source 扩展 provider 注册入口, 对照 desktop registerDesktopSourceProviders
 * (SourceCacheProviders/ExploreKindsCacheProviders 由 Ios/OhosProviderRegistry 经
 * registerNativeSourceCacheProvider / registerNativeExploreKindsCacheProvider 注册,
 * JsExtProviders 由 registerNativeJsEngines 注册, 此处不重复)。
 *
 * - [SourceDebugLoggers]: 桥接 commonMain [Debug] 单例 (未注册时书源调试日志缺失)
 * - [RuleBigDataProviders]: 复用 commonMain [RuleBigDataShared] 文件持久化实现,
 *   路径 `{filesDir}/ruleData/book` (对齐 app 端 `externalFiles/ruleData/book`)
 * - [UserAgentProviders] (help 包): 读 PreferenceProviders "userAgent", 兜底 [AppConst.UA_NAME]
 *   (未注册时请求 UA 恒为字面量兜底; 与 help.ui.UserAgentProviders 是两套, 都要注册)
 * - [SourceNetworkProviders]: 适配已注册的 [CookieStoreProviders]
 *   (BaseSource.putLoginHeader 写 cookie / JS cookie 绑定 / AnalyzeUrlCore.setCookie 都靠它)
 *
 * 须在 AppFilesDirs / PreferenceProviders / CookieStoreProviders 注册之后调用。
 */
fun registerNativeSourceProviders() {
    SourceDebugLoggers.impl = NativeSourceDebugLogger
    RuleBigDataProviders.impl =
        RuleBigDataShared(AppFilesDirs.get().filesDir + "/ruleData/book")
    UserAgentProviders.impl = UserAgentProvider {
        PreferenceProviders.get().getString("userAgent", AppConst.UA_NAME)
    }
    SourceNetworkProviders.impl = NativeSourceNetworkProvider
}

/** 桥接 commonMain [Debug] 单例 (与 app/desktop 端实现一致)。 */
private object NativeSourceDebugLogger : SourceDebugLogger {
    override fun log(key: String, msg: String, print: Boolean, state: Int) {
        Debug.log(key, msg, print = print, state = state)
    }

    override fun log(msg: String) {
        Debug.log(msg)
    }
}

/** cookie 桥: 适配已注册的 [CookieStoreProviders] (与 app 端 CookieStore 单例桥接语义一致)。 */
private object NativeSourceNetworkProvider : SourceNetworkProvider {
    override fun getCookie(tag: String): String =
        CookieStoreProviders.get()?.getCookie(tag) ?: ""

    override fun replaceCookie(tag: String, cookie: String) {
        CookieStoreProviders.get()?.replaceCookie(tag, cookie)
    }

    override fun removeCookie(tag: String) {
        CookieStoreProviders.get()?.removeCookie(tag)
    }
}
