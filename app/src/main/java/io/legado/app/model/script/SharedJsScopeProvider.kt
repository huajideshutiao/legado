package io.legado.app.model.script

import kotlin.coroutines.CoroutineContext

/**
 * JS 共享作用域缓存 provider 抽象。
 *
 * 由 rhino / quickjs 各实现一套，[io.legado.app.model.SharedJsScope] object 持两个 provider
 * 按当前引擎类型转发。两套缓存完全隔离，切换引擎时不互相污染。
 *
 * - rhino 实现 [io.legado.app.model.script.rhino.RhinoSharedJsScopeProvider]:
 *   复刻 master 分支 `LruCache<String, WeakReference<Scriptable>>(16)` + `sealObject()` 全局共享
 * - quickjs 实现 [io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider]:
 *   三层缓存（bytecodeCache + ThreadLocal LRU + versionSeq）
 */
interface SharedJsScopeProvider {

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
    ): JsScope?

    /** 删除 jsLib 的缓存条目（版本号失效）。 */
    fun remove(jsLib: String?)

    /** 清空所有缓存（切换引擎时调用，避免 stale scope 内存泄漏）。 */
    fun clearAll()
}
