package io.legado.app.data

/**
 * Native (iOS/ohos) 端 [DatabaseDriverProvider] 共用扩展接口:
 * 在 [DatabaseDriverProvider] 之上暴露 [appDatabase] 单例, 供 [NativeAppDatabaseProvider] 委托。
 *
 * # 设计目的
 * commonMain 的 [DatabaseDriverProvider] 仅暴露驱动元信息
 * (databaseName / databaseVersion / isInitialized / rawDatabase / close),
 * 不直接暴露 [AppDatabase] (避免 commonMain 耦合 Room KMP 实例类型)。
 * iOS / 鸿蒙两端 driver ([IosDatabaseDriver] / [OhosDatabaseDriver]) 均有
 * `val appDatabase: AppDatabase` 公开属性, 但接口签名分散,
 * 故在 nativeMain 提取本接口统一契约, 让 [NativeAppDatabaseProvider] 通过本接口委托,
 * 避免在每个平台源集重复定义仅类名不同的 [AppDatabaseProvider] 实现。
 *
 * 仅 nativeMain 可见 (iosMain + ohosMain 继承), 不影响 androidMain / jvmMain / commonMain。
 *
 * 实现方: [IosDatabaseDriver] / [OhosDatabaseDriver] (均 lazy 构造 Room KMP + BundledSQLiteDriver)。
 */
interface NativeDatabaseDriver : DatabaseDriverProvider {

    /** Native 端 [AppDatabase] 单例 (Room KMP + BundledSQLiteDriver 构造的真实数据库实例)。 */
    val appDatabase: AppDatabase
}

/**
 * Native (iOS/ohos) 端 [AppDatabaseProvider] 共享实现。
 *
 * 抽自 iosMain IosAppDatabaseProvider / ohosMain OhosAppDatabaseProvider,
 * 两端逻辑完全一致: 仅委托 [NativeDatabaseDriver.appDatabase] 暴露 [AppDatabase] 单例,
 * 同一实例供 [AppDatabaseProviders] + [DatabaseDriverProviders] 共享, 避免重复构造。
 *
 * 模式参考 desktop [DesktopAppDatabaseProvider] (委托 BundledDatabaseDriver.appDatabase)。
 *
 * 调用方:
 * - iOS: [registerIosDatabaseDriver] 内 `AppDatabaseProviders.register(NativeAppDatabaseProvider(driver))`
 *   (保留分步: accessor 在 BookStorage 之后单独注册, 见 [io.legado.app.help.config.registerIosProviders])
 * - ohos: [registerNativeAppDb] 一次性注册 provider + accessor
 *
 * @param driver Native 端 [NativeDatabaseDriver] (IosDatabaseDriver / OhosDatabaseDriver)
 */
class NativeAppDatabaseProvider(
    private val driver: NativeDatabaseDriver,
) : AppDatabaseProvider {

    override val appDb: AppDatabase
        get() = driver.appDatabase
}

/**
 * Native 端 AppDb 一次性注册入口 (同时注册 [AppDatabaseProviders] + [AppDbProviders])。
 *
 * 替代原 ohosMain `registerOhosAppDb(driver)` 两步注册 (OhosAppDatabaseProvider + NativeAppDbAccessor)。
 * iOS 端因 BookStorage 顺序约束保留分步注册, 不走本入口
 * (见 [registerIosDatabaseDriver] + [io.legado.app.data.registerIosAppDbAccessor])。
 *
 * 调用前需先 `DatabaseDriverProviders.register(driver)` 注册驱动元信息
 * (与 desktop `Main.kt` 中 `DatabaseDriverProviders.register(dbDriver)` +
 * `AppDatabaseProviders.register(DesktopAppDatabaseProvider(dbDriver))` +
 * `AppDbProviders.register(DesktopAppDbAccessor())` 三步对齐, 本函数合并后两步)。
 */
fun registerNativeAppDb(driver: NativeDatabaseDriver) {
    AppDatabaseProviders.register(NativeAppDatabaseProvider(driver))
    AppDbProviders.register(NativeAppDbAccessor())
}
