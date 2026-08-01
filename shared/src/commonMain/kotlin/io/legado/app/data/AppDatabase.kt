package io.legado.app.data

import androidx.room3.AutoMigration
import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
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
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.dao.ReplaceRuleDao
import io.legado.app.data.dao.RuleSubDao
import io.legado.app.data.dao.SearchKeywordDao
import io.legado.app.data.dao.ServerDao
import io.legado.app.data.dao.SourceFilterRuleDao
import io.legado.app.data.dao.TxtTocRuleDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.Cookie
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.data.entities.TxtTocRule

/**
 * K5-c Phase 5: AppDatabase 主体下沉 commonMain。
 *
 * - @Database 注解 + abstract DAO 属性 + companion 常量 在 commonMain (平台无关)
 * - appDb 单例 + dbCallback 留 app 端 (依赖 appCtx + AndroidSQLiteConnection + Locale.CHINESE + DefaultData)
 * - DatabaseMigrations (手写 Migration 数组) 留 app 端 (依赖 java.util.Calendar)
 */
@Database(
    version = 86,
    exportSchema = true,
    entities = [Book::class, BookGroup::class, BookSource::class, BookChapter::class,
        ReplaceRule::class, SearchKeyword::class, Cookie::class,
        Bookmark::class, TxtTocRule::class, ReadRecord::class,
        HttpTTS::class, Cache::class,
        RuleSub::class, DictRule::class, KeyboardAssist::class, Server::class,
        SourceFilterRule::class],
    views = [BookSourcePart::class],
    autoMigrations = [
        AutoMigration(from = 83, to = 84),
        AutoMigration(from = 85, to = 86),
    ]
)
// DATABASE 作用域注册 Book.Converters: iOS/ohos KSP 处理 BookChapter.ForeignKey 跨实体解析时,
// 需在 Database 级可见 ReadConfig <-> String 转换链 (与 Book 实体上的 ENTITY 作用域双重注册,
// 详见 Book.kt 顶部注释)。缺此注册会导致 iOS/ohos KSP 报 ReadConfig 类型链解析失败。
@ColumnTypeConverters(Book.Converters::class)
// 非 Android 平台 (iOS/desktop/鸿蒙) Room3 要求显式 @ConstructedBy 提供实例工厂
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {

    abstract val bookDao: BookDao
    abstract val bookGroupDao: BookGroupDao
    abstract val bookSourceDao: BookSourceDao
    abstract val bookChapterDao: BookChapterDao
    abstract val replaceRuleDao: ReplaceRuleDao
    abstract val searchKeywordDao: SearchKeywordDao
    abstract val bookmarkDao: BookmarkDao
    abstract val cookieDao: CookieDao
    abstract val txtTocRuleDao: TxtTocRuleDao
    abstract val readRecordDao: ReadRecordDao
    abstract val httpTTSDao: HttpTTSDao
    abstract val cacheDao: CacheDao
    abstract val ruleSubDao: RuleSubDao
    abstract val dictRuleDao: DictRuleDao
    abstract val keyboardAssistsDao: KeyboardAssistsDao
    abstract val serverDao: ServerDao
    abstract val sourceFilterRuleDao: SourceFilterRuleDao

    companion object {

        const val DATABASE_NAME = "legado.db"

        const val BOOK_TABLE_NAME = "books"
        const val BOOK_SOURCE_TABLE_NAME = "book_sources"
    }

}

// Room3 编译期生成 actual (KSP expect/actual 约定), 各平台源集无需手写
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
