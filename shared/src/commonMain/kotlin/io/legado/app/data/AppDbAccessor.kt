package io.legado.app.data

import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookGroupDao
import io.legado.app.data.dao.BookmarkDao
import io.legado.app.data.dao.BookSourceDao
import io.legado.app.data.dao.CacheDao
import io.legado.app.data.dao.DictRuleDao
import io.legado.app.data.dao.HttpTTSDao
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.dao.ReplaceRuleDao
import io.legado.app.data.dao.RuleSubDao
import io.legado.app.data.dao.ServerDao
import io.legado.app.data.dao.SourceFilterRuleDao
import io.legado.app.data.dao.TxtTocRuleDao

/**
 * appDb 跨模块只读访问接口。
 *
 * 17 个 DAO 接口已下沉 shared (commonMain), 但 appDb 单例 (AppDatabase)
 * 仍依赖 Room + appCtx, 留 app 端。本接口仅暴露 webBook 编排层
 * (WebBook/BookChapterList/BookContent) 及下沉的 Book 扩展
 * (getDisplayTitle 等) 及 ReadTimeRecorder 用到的 5 个 DAO
 * (BookDao/BookSourceDao/BookChapterDao/ReplaceRuleDao/ReadRecordDao),
 * 以及 WebDav 下沉模块用到的 ServerDao, 共 6 个 DAO,
 * 由 app 端 AppDbAccessorImpl 包装 appDb 实现,
 * 在 App.onCreate 经 [AppDbProviders.register] 注册。
 *
 * 模式参考 BookInfoRefreshers / SourceDebugLoggers。
 *
 * 下沉 SearchModel/SearchScope/SearchBookFilter/SourceHelp 时新增
 * sourceFilterRuleDao/httpTTSDao/cacheDao 3 个 DAO 暴露点。
 *
 * 下沉 BookInfoViewModelShared.loadGroup 时新增 bookGroupDao 暴露点
 * (BookshelfViewModel 仍走 AppDatabaseProviders.get().appDb 直接访问, 两条路径等价)。
 */
interface AppDbAccessor {
    val bookDao: BookDao
    val bookSourceDao: BookSourceDao
    val bookChapterDao: BookChapterDao
    /** 书架分组 DAO (BookInfoViewModelShared.loadGroup / BookshelfViewModel 用)。 */
    val bookGroupDao: BookGroupDao
    val replaceRuleDao: ReplaceRuleDao
    val readRecordDao: ReadRecordDao
    val serverDao: ServerDao

    /** TxtToc 规则 DAO (TxtTocRuleViewModelShared 用)。 */
    val txtTocRuleDao: TxtTocRuleDao

    /** 字典规则 DAO (DictRuleViewModelShared 用)。 */
    val dictRuleDao: DictRuleDao

    /** 搜索/发现结果过滤规则 DAO (SearchBookFilter 用)。 */
    val sourceFilterRuleDao: SourceFilterRuleDao

    /** HttpTTS DAO (SourceHelp.getSource/deleteSource 走 SourceType.tts 分支用)。 */
    val httpTTSDao: HttpTTSDao

    /** 缓存 DAO (SourceHelp.deleteBookSource* 清理 source 变量用)。 */
    val cacheDao: CacheDao

    /** 规则订阅 DAO (RuleSubViewModelShared 用: save/delete/upOrder/toTop/toBottom)。 */
    val ruleSubDao: RuleSubDao

    /** 书签 DAO (AllBookmarkViewModelShared 用: exportBookmark/exportBookmarkMd; TocViewModel.saveBookmark)。 */
    val bookmarkDao: BookmarkDao

    /**
     * 在单个 Room 事务中执行 [block] (对应 appDb.runInTransaction)。
     *
     * SourceHelp.deleteBookSourcesByKeys 原走 `appDb.runInTransaction { ... }` 包裹
     * 多个 DAO 写操作 (delete/deleteIn/deleteSourceVariables) 保证原子性, 下沉后
     * 由 app 端 WebBookProvidersImpl 委托 `appDb.runInTransaction { block() }` 实现,
     * 行为与原完全一致。
     *
     * 注: 本方法接收非 suspend block, 供非 suspend 调用方使用 (内部 DAO 调用需自行 runBlocking)。
     */
    fun <R> runInTransaction(block: () -> R): R

    /**
     * suspend 版本的事务执行 (KMP 安全, Native 端无死锁风险)。
     *
     * 供 suspend 调用方使用 (如 [io.legado.app.help.service.UpdateBookShared]),
     * block 内可直接调 suspend DAO 方法, 无需 runBlocking 桥接。
     *
     * 平台实现:
     * - Android: `appDb.withTransaction { block() }` (room-ktx suspend 事务)
     * - Desktop/Native: 降级为直接执行 block (无真实事务, 与非 suspend 版本一致)
     */
    suspend fun <R> runInTransactionSuspending(block: suspend () -> R): R
}

/**
 * appDb provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `AppDbProviders.get().bookDao` 替代原 `appDb.bookDao`,
 * 行为完全一致, 仅多一层 provider 间接。
 */
object AppDbProviders {
    @Volatile
    private var impl: AppDbAccessor? = null

    /** 宿主启动早期注册一次(任何 webBook 调用之前)。 */
    fun register(impl: AppDbAccessor) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): AppDbAccessor = impl ?: error("AppDbProviders not registered")
}
