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
import io.legado.app.data.dao.KeywordHighlightDao
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
 * # 事务行为
 *
 * [runInTransactionSuspending] 走 Room 3 真事务, 实现下沉在 commonMain
 * [AppDatabase.runInImmediateTransaction] (desktop 端 DesktopAppDbAccessor 复用同一函数):
 * useWriterConnection 把写连接放进协程上下文 (ConnectionElement), block 内的 suspend DAO
 * 会复用同一连接加入事务; immediateTransaction 对应 BEGIN IMMEDIATE, 抛异常自动回滚。
 *
 * 鸿蒙端的 room3 是 CPF fork (androidx.room3:room3-*-ohosarm64:3.0.0-alpha01-0.3.0),
 * 本文件只经 klib 元数据核对过 immediateTransaction/useWriterConnection 声明存在,
 * 未做编译验证; iOS 端用的官方 room3 变体同理。
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
    // 详情页读分组名 / 书架分组流用 (bookGroupDao)
    override val bookGroupDao: BookGroupDao get() = appDb.bookGroupDao
    override val keywordHighlightDao: KeywordHighlightDao get() = appDb.keywordHighlightDao
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
    // suspend 版本走 Room KMP 真事务, 与 desktop 端复用同一下沉实现
    // (commonMain AppDatabase.runInImmediateTransaction: useWriterConnection 把写连接放进
    // 协程上下文, immediateTransaction 对应 BEGIN IMMEDIATE, 抛异常自动回滚)。
    override suspend fun <R> runInTransactionSuspending(block: suspend () -> R): R {
        return appDb.runInImmediateTransaction(block)
    }
}
