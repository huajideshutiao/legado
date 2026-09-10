package io.legado.desktop.data

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import io.legado.app.data.AppDatabase
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.AppDbAccessor

/**
 * 桌面端 [AppDbAccessor] 实现: 委托 [AppDatabaseProviders.get].appDb 的全部 17 个 DAO
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
 * - KMP Room 的事务入口只有 suspend 的 `useWriterConnection` + `immediateTransaction`,
 *   故 suspend 版本走真事务, 非 suspend 版本只能直通 (见方法注释)
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

    // ---- 17 个 DAO 属性: 直接转发 appDb 的 abstract val ----
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

    // SearchViewModel 用 (搜索历史)
    override val searchKeywordDao get() = appDb.searchKeywordDao

    // KeyboardToolbar / 备份恢复用 (键盘助手)
    override val keyboardAssistsDao get() = appDb.keyboardAssistsDao

    // ---- AppDbAccessor 事务 ----
    // suspend 版本走 Room KMP 真事务: useWriterConnection 把写连接放进协程上下文
    // (ConnectionElement), block 内的 suspend DAO 会复用同一连接加入事务;
    // immediateTransaction 对应 BEGIN IMMEDIATE, 抛异常自动回滚。
    override suspend fun <R> runInTransactionSuspending(block: suspend () -> R): R {
        return appDb.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }
    }
}
