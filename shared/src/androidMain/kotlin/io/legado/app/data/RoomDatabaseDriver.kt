package io.legado.app.data

import androidx.room.RoomDatabase

/**
 * Android 端 [DatabaseDriverProvider] 实现: 委托给 Room。
 *
 * # 设计要点
 * - **不直接构造 RoomDatabase**: 那仍由 app 端 [appDb] 单例负责
 *   (依赖 appCtx + AndroidSQLiteDriver + DefaultData + Locale.CHINESE)
 * - 本类作为"已构造的 Room 数据库"的包装, 暴露给 commonMain 经 [DatabaseDriverProvider] 契约访问
 * - [rawDatabase] 返回 [RoomDatabase] (实际为 [AppDatabase] 实例), commonMain 端只看到 [Any]
 *
 * # 使用示例
 * ```kotlin
 * // App.onCreate 中, 在 AppDbProviders.register(...) 之后:
 * DatabaseDriverProviders.register(RoomDatabaseDriver(appDb))
 * ```
 *
 * # 现状
 * 当前 app 端实际仍直接用 [appDb] 单例, 本类为"未来切换驱动"预留:
 * - 短期: BundledSQLiteDriver 替换 AndroidSQLiteDriver (iOS 可用, 行为一致)
 * - 长期: 切换到 SQLDelight 时, 新增 SqlDelightDatabaseDriver 实现, 此处保持不动
 */
class RoomDatabaseDriver(
    private val db: RoomDatabase
) : DatabaseDriverProvider {

    override val databaseName: String
        get() = AppDatabase.DATABASE_NAME

    override val databaseVersion: Int
        get() = try {
            // 触发 lazy 创建; 失败时回退到编译期常量 (与 @Database(version=86) 同步)
            db.openHelper.readableDatabase.version
        } catch (e: Exception) {
            // 数据库尚未真正打开或处于异常状态时回退
            86
        }

    override val isInitialized: Boolean
        get() = db.isOpen

    override val rawDatabase: Any
        get() = db

    override fun close() {
        // 幂等: RoomDatabase.close() 内部已处理重复调用
        if (db.isOpen) {
            db.close()
        }
    }
}
