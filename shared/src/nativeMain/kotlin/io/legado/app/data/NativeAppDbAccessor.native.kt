package io.legado.app.data

import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookGroupDao
import io.legado.app.data.dao.BookmarkDao
import io.legado.app.data.dao.BookSourceDao
import io.legado.app.data.dao.CacheDao
import io.legado.app.data.dao.CookieDao
import io.legado.app.data.dao.DictRuleDao
import io.legado.app.data.dao.HttpTTSDao
import io.legado.app.data.dao.KeyboardAssistsDao
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.dao.ReplaceRuleDao
import io.legado.app.data.dao.RuleSubDao
import io.legado.app.data.dao.SearchKeywordDao
import io.legado.app.data.dao.ServerDao
import io.legado.app.data.dao.SourceFilterRuleDao
import io.legado.app.data.dao.TxtTocRuleDao

/**
 * iOS/鸿蒙 (Native target) 共用 [AppDbAccessor] 实现:
 * 委托 [AppDatabaseProviders.get].appDb 的全部 17 个 DAO,
 * 供 shared commonMain 中下沉的 webBook 编排层 (WebBook/BookContent/
 * SourceHelp/SearchBookFilter/ReadBookViewModelShared 等) 通过 [AppDbProviders]
 * 间接访问 appDb。
 *
 * # 共用原因
 * iOS 与鸿蒙两端 AppDbAccessor 主体实现完全一致 (17 个 DAO 直接转发 appDb 的 abstract val),
 * 仅类名 (IosAppDbAccessor / OhosAppDbAccessor)
 * 与注册函数不同, 故下沉到 nativeMain 共用, 平台源集用 typealias 别名 + 各自 register 函数。
 *
 * # 事务行为 (Native 端仍是降级实现)
 *
 * [runInTransactionSuspending] 直接执行 block 不做事务包裹, 每个 DAO 操作本身原子,
 * 最坏情况部分失败 (调用方 SourceHelp.deleteBookSourcesByKeys 有 chunked 兜底, 影响可控)。
 * TODO: Room 3 的 useWriterConnection + immediateTransaction 在 Native 端可用, 待验证后接真事务
 * (jvm/android 两端已走该路径, 见 DesktopAppDbAccessor / WebBookProvidersImpl)。
 *
 * 模式参考 desktop `DesktopAppDbAccessor` 实现。
 */
class NativeAppDbAccessor : AppDbAccessor {

    /**
     * Native 端 [AppDatabase] 单例 (由各端 AppDatabaseProvider 委托 DatabaseDriver.appDatabase 提供)。
     *
     * 每次访问都经 [AppDatabaseProviders.get] 取最新 provider, 与 app 端 `appDb` 单例
     * 访问语义一致 (provider 注册一次后 impl 不变, 多次 get 无额外开销)。
     */
    private val appDb: AppDatabase
        get() = AppDatabaseProviders.get().appDb

    // ---- 17 个 DAO 属性: 直接转发 appDb 的 abstract val ----
    override val bookDao: BookDao get() = appDb.bookDao
    override val bookSourceDao: BookSourceDao get() = appDb.bookSourceDao
    override val bookChapterDao: BookChapterDao get() = appDb.bookChapterDao
    // BookInfoViewModelShared.loadGroup 用 (查询分组名)
    override val bookGroupDao: BookGroupDao get() = appDb.bookGroupDao
    override val replaceRuleDao: ReplaceRuleDao get() = appDb.replaceRuleDao
    override val txtTocRuleDao: TxtTocRuleDao get() = appDb.txtTocRuleDao
    override val dictRuleDao: DictRuleDao get() = appDb.dictRuleDao
    override val readRecordDao: ReadRecordDao get() = appDb.readRecordDao
    override val serverDao: ServerDao get() = appDb.serverDao
    // SearchBookFilter/SourceHelp 下沉新增的 3 个 DAO 暴露点
    override val sourceFilterRuleDao: SourceFilterRuleDao get() = appDb.sourceFilterRuleDao
    override val httpTTSDao: HttpTTSDao get() = appDb.httpTTSDao
    override val cacheDao: CacheDao get() = appDb.cacheDao
    override val cookieDao: CookieDao get() = appDb.cookieDao
    // RuleSubViewModelShared 用 (规则订阅 CRUD)
    override val ruleSubDao: RuleSubDao get() = appDb.ruleSubDao
    // AllBookmarkViewModelShared / TocViewModel.saveBookmark 用 (书签导出/保存)
    override val bookmarkDao: BookmarkDao get() = appDb.bookmarkDao

    // SearchViewModel 用 (搜索历史)
    override val searchKeywordDao: SearchKeywordDao get() = appDb.searchKeywordDao

    // KeyboardToolbar / 备份恢复用 (键盘助手)
    override val keyboardAssistsDao: KeyboardAssistsDao get() = appDb.keyboardAssistsDao

    // ---- AppDbAccessor 事务 ----
    // suspend 版本: Native 端无 room-ktx, 降级为直接执行 block (与非 suspend 版本行为一致)
    // 供 UpdateBookShared 等 suspend 调用方使用, block 内可直接调 suspend DAO, 无需 runBlocking
    // (Native 端 runBlocking 有死锁风险, 用 suspend 版本规避)
    override suspend fun <R> runInTransactionSuspending(block: suspend () -> R): R {
        return block()
    }
}
