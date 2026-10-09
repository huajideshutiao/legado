package io.legado.app.data

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookGroupDao
import io.legado.app.data.dao.BookSourceDao
import io.legado.app.data.dao.BookmarkDao
import io.legado.app.data.dao.CacheDao
import io.legado.app.data.dao.CookieDao
import io.legado.app.data.dao.DictRuleDao
import io.legado.app.data.dao.HttpTTSDao
import io.legado.app.data.dao.KeyboardAssistsDao
import io.legado.app.data.dao.KeywordHighlightDao
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.dao.ReplaceRuleDao
import io.legado.app.data.dao.RuleSubDao
import io.legado.app.data.dao.SearchKeywordDao
import io.legado.app.data.dao.ServerDao
import io.legado.app.data.dao.SourceFilterRuleDao
import io.legado.app.data.dao.TxtTocRuleDao
import kotlin.concurrent.Volatile

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
 * 详情页读分组名时新增 bookGroupDao 暴露点。
 *
 * 全部 17 个 DAO 均已暴露: shared 业务代码统一走本接口 (AppDbProviders.get()),
 * AppDatabaseProviders 仅保留给 AppDbAccessor 实现与需要完整 Room API
 * (useWriterConnection / VACUUM) 的场景。
 */
interface AppDbAccessor {
    val bookDao: BookDao
    val bookSourceDao: BookSourceDao
    val bookChapterDao: BookChapterDao
    /** 书架分组 DAO (详情页读分组名 / BookshelfViewModel 用)。 */
    val bookGroupDao: BookGroupDao

    /** 关键词高亮 DAO (备份/恢复 keywordHighlight.json 用)。 */
    val keywordHighlightDao: KeywordHighlightDao
    val replaceRuleDao: ReplaceRuleDao
    val readRecordDao: ReadRecordDao
    val serverDao: ServerDao

    /** TxtToc 规则 DAO (TxtTocRuleViewModelShared 用)。 */
    val txtTocRuleDao: TxtTocRuleDao

    /** 字典规则 DAO。 */
    val dictRuleDao: DictRuleDao

    /** 搜索/发现结果过滤规则 DAO (SearchBookFilter 用)。 */
    val sourceFilterRuleDao: SourceFilterRuleDao

    /** HttpTTS DAO (SourceHelp.getSource/deleteSource 走 SourceType.tts 分支用)。 */
    val httpTTSDao: HttpTTSDao

    /** 缓存 DAO (SourceHelp.deleteBookSource* 清理 source 变量用)。 */
    val cacheDao: CacheDao

    // SharedCookieStore 用 (cookie 持久化)
    val cookieDao: CookieDao

    /** 规则订阅 DAO (RuleSubViewModelShared 用: save/delete/upOrder/toTop/toBottom)。 */
    val ruleSubDao: RuleSubDao

    /** 书签 DAO (AllBookmarkViewModelShared 用: exportBookmark/exportBookmarkMd; TocViewModel.saveBookmark)。 */
    val bookmarkDao: BookmarkDao

    /** 搜索历史 DAO (SearchViewModel 用: flowByTime/flowSearch/delete/deleteAll)。 */
    val searchKeywordDao: SearchKeywordDao

    /** 键盘助手 DAO (KeyboardToolbar / 备份恢复用)。 */
    val keyboardAssistsDao: KeyboardAssistsDao

    /**
     * suspend 版本的事务执行 (KMP 安全, Native 端无死锁风险)。
     *
     * 供 suspend 调用方使用 (如 [io.legado.app.help.service.UpdateBookShared]),
     * block 内可直接调 suspend DAO 方法, 无需 runBlocking 桥接。
     *
     * 平台实现 (三端都是 Room KMP 真事务, 内容相同的两行):
     * `appDb.useWriterConnection { it.immediateTransaction { block() } }`
     * —— useWriterConnection 把写连接放进协程上下文 (ConnectionElement), block 内的 suspend DAO
     * 复用同一连接加入事务; immediateTransaction 对应 BEGIN IMMEDIATE, 抛异常自动回滚。
     * desktop / native 两端复用同一下沉实现 [runInImmediateTransaction],
     * app 端实现见 `io.legado.app.model.webBook.WebBookProvidersImpl`。
     */
    suspend fun <R> runInTransactionSuspending(block: suspend () -> R): R
}

/**
 * 用 Room KMP 真事务执行 [block] (desktop / native 共用的下沉实现)。
 *
 * 原 desktop `DesktopAppDbAccessor` 与 native `NativeAppDbAccessor` 内各写了一份相同的
 * `useWriterConnection{ it.immediateTransaction{...} }`, 下沉至此一处复用:
 * useWriterConnection 把写连接放进协程上下文 (ConnectionElement), [block] 内的 suspend DAO
 * 复用同一连接加入事务; immediateTransaction 对应 BEGIN IMMEDIATE, 抛异常自动回滚。
 *
 * app (Android) 端的同款两行在 `io.legado.app.model.webBook.WebBookProvidersImpl.runInTransactionSuspending`
 * (app 模块不在本文件域内, 未改为复用本函数)。
 */
suspend fun <R> AppDatabase.runInImmediateTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor ->
        transactor.immediateTransaction { block() }
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

    /** 注销已注册实现 (宿主/测试收尾)。 */
    fun unregister() {
        impl = null
    }
}
