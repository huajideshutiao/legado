package io.legado.app.model

import io.legado.app.model.script.JsEngineType
import io.legado.app.model.script.JsEngines
import io.legado.app.model.script.JsScope
import io.legado.app.model.script.SharedJsScopeProvider
import kotlin.coroutines.CoroutineContext

/**
 * 按引擎类型供给 [SharedJsScopeProvider]。引擎绑定实现留 app，
 * 宿主启动早期经 [SharedJsScope.registerProviders] 注册一次；
 * 工厂 lambda 惰性取实现（provider object 的类初始化推迟到首次用到对应引擎）。
 */
fun interface SharedJsScopeProviders {
    fun get(type: JsEngineType): SharedJsScopeProvider
}

/**
 * JS 共享作用域缓存转发。
 *
 * 按 [JsEngines.type] 转发到对应 provider 实现，两套缓存完全隔离。
 * 切换引擎时调用 [clearAll] 清空两侧缓存避免 stale scope 内存泄漏。
 *
 * - rhino `RhinoSharedJsScopeProvider`（app）: 全局 LruCache + sealObject（master 分支语义）
 * - quickjs `QuickJsSharedJsScopeProvider`（app）: 三层缓存（bytecodeCache + ThreadLocal LRU + versionSeq）
 *
 * 业务层（BaseSourceExtensions.getShareScope / AnalyzeRule / AnalyzeUrl）通过本 object
 * 获取共享 scope，不再直接依赖某个引擎的 ctx 类型。
 */
object SharedJsScope {

    @Volatile
    private var providers: SharedJsScopeProviders? = null

    /** 宿主启动早期注册一次（任何 JS eval 之前，安卓在 App.onCreate）。 */
    fun registerProviders(sharedJsScopeProviders: SharedJsScopeProviders) {
        providers = sharedJsScopeProviders
    }

    /**
     * 获取（或创建）jsLib 对应的共享 scope。
     *
     * @param jsLib jsLib 源码（可能是 JS 字符串或 JSON Map 形式）
     * @param enableDangerousApi 是否旁路安全名单
     * @param coroutineContext 协程上下文（用于取消传递）
     * @return 共享 scope，jsLib 为空时返回 null
     */
    fun getScope(
        jsLib: String?,
        enableDangerousApi: Boolean,
        coroutineContext: CoroutineContext?
    ): JsScope? {
        return provider().getScope(jsLib, enableDangerousApi, coroutineContext)
    }

    /**
     * 删除 jsLib 的缓存条目（版本号失效）。
     *
     * 各引擎实现保证：不会同步 close 任何 ctx（老 ctx 可能仍被某条 evalJS 持栈强引用，
     * 同步释放会与正在执行的 native 调用形成 use-after-free）。
     */
    fun remove(jsLib: String?) {
        provider().remove(jsLib)
    }

    /**
     * 清空所有缓存（切换引擎时调用）。
     *
     * rhino 已弃用（2026-07-17 KMP 裁决），仅清空 QUICKJS provider 的缓存。
     * stale ctx 由各引擎的 GC/PhantomReference 兜底释放。
     */
    fun clearAll() {
        val p = providers ?: return
        p.get(JsEngineType.QUICKJS).clearAll()
    }

    private fun provider(): SharedJsScopeProvider {
        val p = checkNotNull(providers) {
            "SharedJsScopeProviders 未注册(宿主启动早期 registerProviders)"
        }
        return p.get(JsEngines.type)
    }
}
