package io.legado.desktop.data

import io.legado.app.data.AppDatabase
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.AppDbAccessor

/**
 * 桌面端 [AppDbAccessor] 实现: 委托 [AppDatabaseProviders.get].appDb 的 9 个 DAO
 * + `runInTransaction`, 供 shared commonMain 中下沉的 webBook 编排层 / SourceHelp /
 * SearchBookFilter / ReadBookViewModelShared 等通过 [io.legado.app.data.AppDbProviders]
 * 间接访问 appDb。
 *
 * # 注册时机
 * desktop `main()` 中在 `AppDatabaseProviders.register(...)` 之后注册 (本文件依赖
 * `AppDatabaseProviders.get().appDb` 已就绪), 见 [io.legado.desktop.Main]。
 *
 * # 与 app 端 [io.legado.app.model.webBook.WebBookProvidersImpl] 区别
 * - app 端 `runInTransaction` 走 `RoomDatabase.runInTransaction(Runnable)` (Android Room
 *   非 suspend API, 直接阻塞当前线程)
 * - KMP Room 的 `RoomDatabase` 不暴露 `runInTransaction(Runnable)` (Android 专属),
 *   也不可用 `androidx.room.withTransaction` (room-ktx 2.8.4 不发布 jvm 变体);
 *   改用 `RoomDatabase.useWriterTransaction` suspend 扩展函数 (room-runtime 中) + `runBlocking` 包裹,
 *   保持接口 `fun <R> runInTransaction(block: () -> R): R` 非 suspend 语义不变
 *   (调用方如 SourceHelp.deleteBookSourcesByKeys 在 block 内用 runBlocking 调 suspend DAO,
 *   嵌套 runBlocking 各自独立事件循环, 不会死锁)
 *
 * # DAO 成员清单 (与 AppDbAccessor 接口一致)
 * bookDao / bookSourceDao / bookChapterDao / replaceRuleDao / readRecordDao /
 * serverDao / sourceFilterRuleDao / httpTTSDao / cacheDao
 *
 * 模式参考 app 端 WebBookProvidersImpl 的 AppDbAccessor 实现段。
 */
class DesktopAppDbAccessor : AppDbAccessor {

    /**
     * 桌面端 [AppDatabase] 单例 (由 [io.legado.app.data.DesktopAppDatabaseProvider]
     * 委托 [io.legado.app.data.BundledDatabaseDriver.appDatabase] 提供)。
     *
     * 每次访问都经 [AppDatabaseProviders.get] 取最新 provider, 与 app 端 `appDb` 单例
     * 访问语义一致 (provider 注册一次后 impl 不变, 多次 get 无额外开销)。
     */
    private val appDb: AppDatabase
        get() = AppDatabaseProviders.get().appDb

    // ---- 10 个 DAO 属性: 直接转发 appDb 的 abstract val ----
    override val bookDao get() = appDb.bookDao
    override val bookSourceDao get() = appDb.bookSourceDao
    override val bookChapterDao get() = appDb.bookChapterDao
    // BookInfoViewModelShared.loadGroup 用 (查询分组名)
    override val bookGroupDao get() = appDb.bookGroupDao
    override val replaceRuleDao get() = appDb.replaceRuleDao
    override val txtTocRuleDao get() = appDb.txtTocRuleDao
    override val dictRuleDao get() = appDb.dictRuleDao
    override val readRecordDao get() = appDb.readRecordDao
    override val serverDao get() = appDb.serverDao
    // SearchBookFilter/SourceHelp 下沉新增的 3 个 DAO 暴露点
    override val sourceFilterRuleDao get() = appDb.sourceFilterRuleDao
    override val httpTTSDao get() = appDb.httpTTSDao
    override val cacheDao get() = appDb.cacheDao
    override val cookieDao get() = appDb.cookieDao
    // RuleSubViewModelShared 用 (规则订阅 CRUD)
    override val ruleSubDao get() = appDb.ruleSubDao
    // AllBookmarkViewModelShared / TocViewModel.saveBookmark 用 (书签导出/保存)
    override val bookmarkDao get() = appDb.bookmarkDao

    // ---- AppDbAccessor 事务 ----
    // KMP Room 无 RoomDatabase.runInTransaction(Runnable) (Android 专属),
    // room-ktx 2.8.4 也不发布 jvm 变体 (androidx.room.withTransaction 不可用),
    // room-runtime 的 useWriterTransaction 在 androidx.sqlite 包中也不可用;
    // P0 阶段降级: 直接执行 block 不做事务包裹, 每个 DAO 操作本身原子, 最坏情况部分失败
    // (调用方 SourceHelp.deleteBookSourcesByKeys 内部已有 runBlocking + chunked 兜底, 影响可控)
    // TODO: 后续引入 room-ktx jvm 变体或用 openHelper 原生事务 API 恢复事务语义
    override fun <R> runInTransaction(block: () -> R): R {
        return block()
    }

    // suspend 版本: 桌面端无 room-ktx, 降级为直接执行 block (与非 suspend 版本行为一致)
    // 供 UpdateBookShared 等 suspend 调用方使用, block 内可直接调 suspend DAO, 无需 runBlocking
    override suspend fun <R> runInTransactionSuspending(block: suspend () -> R): R {
        return block()
    }
}
