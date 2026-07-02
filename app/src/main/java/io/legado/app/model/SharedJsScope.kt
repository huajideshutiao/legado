package io.legado.app.model

import io.legado.app.model.SharedJsScope.clearAll
import io.legado.app.model.script.JsEngineType
import io.legado.app.model.script.JsEngines
import io.legado.app.model.script.JsScope
import io.legado.app.model.script.SharedJsScopeProvider
import io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider
import io.legado.app.model.script.rhino.RhinoSharedJsScopeProvider
import kotlin.coroutines.CoroutineContext

/**
 * JS 共享作用域缓存转发。
 *
 * 按 [JsEngines.type] 转发到对应 provider 实现，两套缓存完全隔离。
 * 切换引擎时调用 [clearAll] 清空两侧缓存避免 stale scope 内存泄漏。
 *
 * - rhino [RhinoSharedJsScopeProvider]: 全局 LruCache + sealObject（master 分支语义）
 * - quickjs [QuickJsSharedJsScopeProvider]: 三层缓存（bytecodeCache + ThreadLocal LRU + versionSeq）
 *
 * 业务层（BaseSourceExtensions.getShareScope / AnalyzeRule / AnalyzeUrl）通过本 object
 * 获取共享 scope，不再直接依赖某个引擎的 ctx 类型。
 */
object SharedJsScope {

    private val quickjsProvider = QuickJsSharedJsScopeProvider
    private val rhinoProvider = RhinoSharedJsScopeProvider

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
     * 清空两侧 provider 的缓存，避免切换引擎后 stale scope 内存泄漏。
     * stale ctx 由各引擎的 GC/PhantomReference 兜底释放。
     */
    fun clearAll() {
        quickjsProvider.clearAll()
        rhinoProvider.clearAll()
    }

    private fun provider(): SharedJsScopeProvider = when (JsEngines.type) {
        JsEngineType.RHINO -> rhinoProvider
        JsEngineType.QUICKJS -> quickjsProvider
    }
}
