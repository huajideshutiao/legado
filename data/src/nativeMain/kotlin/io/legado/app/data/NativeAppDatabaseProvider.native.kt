package io.legado.app.data

/**
 * Native (iOS/ohos) 端 driver 契约: 暴露 [appDatabase] 单例, 供 [NativeAppDatabaseProvider] 委托。
 *
 * iOS / 鸿蒙两端 driver ([IosDatabaseDriver] / [OhosDatabaseDriver]) 均有同签名的
 * `appDatabase` 属性, 提取本接口让 [NativeAppDatabaseProvider] 通过统一契约委托,
 * 避免在每个平台源集重复定义仅类名不同的 [AppDatabaseProvider] 实现。
 *
 * 仅 nativeMain 可见 (iosMain + ohosMain 继承)。
 *
 * 实现方: [IosDatabaseDriver] / [OhosDatabaseDriver] (均 lazy 构造 Room KMP + 平台 SQLite 驱动)。
 */
interface NativeDatabaseDriver {

    /** Native 端 [AppDatabase] 单例 (Room KMP + 平台 SQLite 驱动构造的真实数据库实例)。 */
    val appDatabase: AppDatabase
}

/**
 * Native (iOS/ohos) 端 [AppDatabaseProvider] 共享实现。
 *
 * 抽自 iosMain IosAppDatabaseProvider / ohosMain OhosAppDatabaseProvider,
 * 两端逻辑完全一致: 仅委托 [NativeDatabaseDriver.appDatabase] 暴露 [AppDatabase] 单例,
 * 同一实例经 [AppDatabaseProviders] 注册共享, 避免重复构造。
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
 */
fun registerNativeAppDb(driver: NativeDatabaseDriver) {
    AppDatabaseProviders.register(NativeAppDatabaseProvider(driver))
    AppDbProviders.register(NativeAppDbAccessor())
}
