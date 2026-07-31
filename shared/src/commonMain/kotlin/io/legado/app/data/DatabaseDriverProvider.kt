package io.legado.app.data

import kotlin.concurrent.Volatile

/**
 * 跨平台 SQLite 数据库驱动提供者接口。
 *
 * # 设计目的
 * K5-c Phase 5 之后, AppDatabase 主体 (@Database 注解 + 17 个 DAO + companion 常量)
 * 已下沉 shared/commonMain, 但仍依赖 Room KMP (room-runtime/room-ktx/room-common);
 * 真正的 [appDb] 单例 (依赖 AndroidSQLiteDriver + appCtx + DefaultData + Locale.CHINESE)
 * 留 app 端 [io.legado.app.data.AppDatabase.kt] (app/src/main/...).
 *
 * 本接口在 commonMain 定义"驱动契约", 不直接暴露 Room/SQLDelight 等平台特定类型,
 * 让未来切换底层驱动 (Room KMP -> SQLDelight, 或 AndroidSQLiteDriver -> BundledSQLiteDriver)
 * 时, commonMain 调用方零改动:
 *
 * - androidMain actual: [RoomDatabaseDriver] 委托 androidx.room3.RoomDatabase
 *   (现状: 包装 app 端已构造的 appDb 单例)
 * - jvmMain actual: [BundledDatabaseDriver] 基于 Room KMP + BundledSQLiteDriver (KP1.2 桌面端落地)
 * - 未来 iOS/HarmonyOS actual: 可用 SQLDelight 实现 (与 Room 共存或逐步替换)
 *
 * # 现状注意
 * 当前 app 端实际仍直接用 appDb 单例 + AppDbProviders 访问 DAO,
 * 本接口为"未来切换驱动"预留口子, **不替换** appDb 单例与 AppDbProviders 流程。
 * 与 [AppDbAccessor]/[AppDbProviders] 解耦: 后者负责 DAO 访问, 本接口负责底层驱动元信息。
 *
 * 模式参考: [AppDbProviders] (宿主启动早期注册一次)。
 */
interface DatabaseDriverProvider {

    /**
     * 数据库文件名 (跨平台一致)。
     *
     * Android 端取自 [AppDatabase.DATABASE_NAME] = "legado.db";
     * 其他平台应保持同名, 以便跨平台库文件互通。
     */
    val databaseName: String

    /**
     * 数据库 schema 版本 (与 @Database(version=...) 同步)。
     *
     * Android 端运行时取自 RoomDatabase.openHelper.readableDatabase.version;
     * 占位实现可返回编译期常量 (当前为 86)。
     */
    val databaseVersion: Int

    /** 是否已初始化 (lazy 创建后置 true; 占位实现恒为 false)。 */
    val isInitialized: Boolean

    /**
     * 返回平台原生数据库句柄。
     *
     * - Android: androidx.room3.RoomDatabase (实际为 AppDatabase 实例)
     * - JVM: androidx.room3.RoomDatabase (实际为 AppDatabase 实例, 由 BundledSQLiteDriver 构造)
     * - iOS/HarmonyOS (未来): SQLDelight Database 实例
     *
     * commonMain 不假定具体类型, 仅作 [Any] 传递; 由 actual 端强转使用。
     */
    val rawDatabase: Any

    /**
     * 关闭数据库连接 (应用退出 / 测试场景使用)。
     *
     * 实现应保证幂等 (重复调用不报错)。
     */
    fun close()
}

/**
 * [DatabaseDriverProvider] 容器 (与 [AppDbProviders] 同模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate), shared 内通过 [get] 获取。
 *
 * 当前 app 端实际仍直接用 appDb 单例, 本容器为未来切换驱动预留。
 * 现阶段可不必注册 (调用方继续走 AppDbProviders), 仅在真正需要驱动元信息时使用。
 */
object DatabaseDriverProviders {

    @Volatile
    private var impl: DatabaseDriverProvider? = null

    /** 宿主启动早期注册一次 (任何 DAO 访问之前)。 */
    fun register(impl: DatabaseDriverProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): DatabaseDriverProvider =
        impl ?: error("DatabaseDriverProviders not registered")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
