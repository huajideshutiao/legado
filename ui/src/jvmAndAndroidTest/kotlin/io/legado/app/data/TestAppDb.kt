package io.legado.app.data

import java.io.File

/**
 * JVM 测试用的真实 Room 库夹具。
 *
 * 生产侧 DAO 容器由各宿主在启动时注册 (`AppDbProviders.register`), 测试里没有宿主, 所以每个
 * 需要 DB 的用例调 [registerTempTestDb] 拿一个独立临时库 + 已注册的容器, 用完 [TempTestDb.close]。
 *
 * 为什么不用内存库: Room KMP 的 `databaseBuilder(name = ...)` 走文件路径 (BundledSQLiteDriver),
 * 临时文件与生产同一条代码路径 (含迁移/回调), 比自造 in-memory 更接近真实。
 */
class TempTestDb(
    private val driver: BundledDatabaseDriver,
    private val path: String,
) {
    val appDatabase: AppDatabase get() = driver.appDatabase

    /** 关闭库、注销容器、删除临时库文件 (含 wal/shm 旁路文件)。 */
    fun close() {
        driver.close()
        AppDbProviders.unregister()
        listOf(path, "$path-wal", "$path-shm").forEach { runCatching { File(it).delete() } }
    }
}

fun registerTempTestDb(prefix: String = "legado-test"): TempTestDb {
    val path = File.createTempFile(prefix, ".db").apply { delete() }.absolutePath
    val driver = BundledDatabaseDriver(path)
    AppDbProviders.register(TestDbAccessor(driver.appDatabase))
    return TempTestDb(driver, path)
}

/** 测试用 DAO 容器: 全部委托真实 [AppDatabase] (18 个 DAO 与产品实现同源)。 */
class TestDbAccessor(private val db: AppDatabase) : AppDbAccessor {
    override val bookDao get() = db.bookDao
    override val bookSourceDao get() = db.bookSourceDao
    override val bookChapterDao get() = db.bookChapterDao
    override val bookGroupDao get() = db.bookGroupDao
    override val keywordHighlightDao get() = db.keywordHighlightDao
    override val replaceRuleDao get() = db.replaceRuleDao
    override val readRecordDao get() = db.readRecordDao
    override val serverDao get() = db.serverDao
    override val txtTocRuleDao get() = db.txtTocRuleDao
    override val dictRuleDao get() = db.dictRuleDao
    override val sourceFilterRuleDao get() = db.sourceFilterRuleDao
    override val httpTTSDao get() = db.httpTTSDao
    override val cacheDao get() = db.cacheDao
    override val cookieDao get() = db.cookieDao
    override val ruleSubDao get() = db.ruleSubDao
    override val bookmarkDao get() = db.bookmarkDao
    override val searchKeywordDao get() = db.searchKeywordDao
    override val keyboardAssistsDao get() = db.keyboardAssistsDao
    override suspend fun <R> runInTransactionSuspending(block: suspend () -> R): R = block()
}
