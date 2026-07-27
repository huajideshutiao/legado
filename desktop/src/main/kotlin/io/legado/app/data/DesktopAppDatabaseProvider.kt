package io.legado.app.data

/**
 * 桌面端 [AppDatabaseProvider] 实现: 委托 [BundledDatabaseDriver] 暴露 [AppDatabase] 单例。
 *
 * 委托 [BundledDatabaseDriver.appDatabase] (shared jvmMain) 真实 Room 数据库实例,
 * 同一实例供 [AppDatabaseProviders] + [DatabaseDriverProviders] 共享, 避免重复构造。
 *
 * # 实现链路
 * - desktop Main.kt 构造 `BundledDatabaseDriver()`, 注册到 [DatabaseDriverProviders]
 * - 同时用本类包装 driver, 注册到 [AppDatabaseProviders]
 * - 业务层通过 [AppDatabaseProviders.get().appDb] 访问 Room AppDatabase (suspend DAO)
 *
 * 模式参考 app 端 [AndroidAppDatabaseProvider] (委托 [appDb] lazy 单例)。
 *
 * @param driver 桌面端 BundledDatabaseDriver (Room KMP + BundledSQLiteDriver)
 */
class DesktopAppDatabaseProvider(
    private val driver: BundledDatabaseDriver
) : AppDatabaseProvider {

    override val appDb: AppDatabase
        get() = driver.appDatabase
}
