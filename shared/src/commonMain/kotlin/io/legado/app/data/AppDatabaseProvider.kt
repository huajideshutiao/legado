package io.legado.app.data

import kotlin.concurrent.Volatile

/**
 * [AppDatabase] 单例注入接口 (shared commonMain)。
 *
 * # KP1.2: 桌面端落地 Room KMP
 * - commonMain 的 [AppDatabase] (@Database 主体 + 17 DAO + companion 常量) 平台无关
 * - 但 appDb 单例构造依赖平台特定驱动 (Android: AndroidSQLiteDriver + appCtx;
 *   JVM: BundledSQLiteDriver + 本地文件), 通过本接口在 commonMain 解耦
 * - app 端在 [io.legado.app.model.webBook.registerAndroidWebBookProviders] 中注册实现
 *   (委托 [io.legado.app.data.appDb] lazy 单例)
 * - desktop 端在 main() 注册 [DesktopAppDatabaseProvider] 实现
 *   (用 Room.databaseBuilder + BundledSQLiteDriver 构造)
 *
 * # 与 [AppDbAccessor] 区别
 * - [AppDbAccessor] 仅暴露 6 个 DAO (webBook 编排层用到的), 是 appDb 的"只读视图"
 * - [AppDatabaseProvider] 暴露完整 [AppDatabase] 实例, 供需要直接访问 Room API 的场景
 *   (如 useWriterConnection / openHelper / queryExecutor 等)
 *
 * 模式参考 [AppDbProviders] / [OkHttpClientProviders]。
 */
interface AppDatabaseProvider {
    val appDb: AppDatabase
}

/**
 * [AppDatabaseProvider] 容器 (与 [AppDbProviders] 同模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 */
object AppDatabaseProviders {

    @Volatile
    private var impl: AppDatabaseProvider? = null

    /** 宿主启动早期注册一次 (任何 DAO 访问之前)。 */
    fun register(impl: AppDatabaseProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): AppDatabaseProvider =
        impl ?: error("AppDatabaseProviders not registered")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
