package io.legado.app.ui.book.changesource

import kotlin.concurrent.Volatile

/**
 * [ChangeBookSourcePlatform] 注入容器 (shared commonMain)。
 *
 * 模式参考 [io.legado.app.data.AppDatabaseProviders]。
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 IllegalStateException。
 */
object ChangeBookSourcePlatformProviders {

    @Volatile
    private var impl: ChangeBookSourcePlatform? = null

    /** 宿主启动早期注册一次 (任何 ChangeBookSourceViewModelShared 实例化之前)。 */
    fun register(impl: ChangeBookSourcePlatform) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): ChangeBookSourcePlatform =
        impl ?: error("ChangeBookSourcePlatformProviders not registered")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
