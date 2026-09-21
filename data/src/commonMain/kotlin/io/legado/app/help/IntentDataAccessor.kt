package io.legado.app.help

import kotlin.concurrent.Volatile

/**
 * IntentData 跨模块只读访问接口。
 *
 * IntentData 依赖 SearchResult (UI 类, app 端), 整体留 app 端。
 * 本接口仅暴露 webBook 编排层 (WebBook.getBookInfoByUrlAwait) 用到的
 * setSource(...), 由 app 端 IntentDataAccessorImpl 包装 IntentData 实现,
 * 在 App.onCreate 经 [IntentDataProviders.register] 注册。
 *
 * 模式参考 BookInfoRefreshers / SourceDebugLoggers。
 */
interface IntentDataAccessor {

    /**
     * 设置当前书源 (对应 IntentData.source = value)。
     *
     * 实现内部由 app 端 IntentData.source = value 写入, 行为与原 app 端直接调用完全一致。
     */
    fun setSource(source: Any?)
}

/**
 * IntentData provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用
 * `IntentDataProviders.get().setSource(source)` 替代
 * 原 `IntentData.source = source`, 行为完全一致, 仅多一层 provider 间接。
 */
object IntentDataProviders {
    @Volatile
    private var impl: IntentDataAccessor? = null

    /** 宿主启动早期注册一次(任何 webBook 调用之前)。 */
    fun register(impl: IntentDataAccessor) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): IntentDataAccessor = impl ?: error("IntentDataProviders not registered")
}
