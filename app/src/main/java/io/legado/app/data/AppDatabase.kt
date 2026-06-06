package io.legado.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultData
import org.intellij.lang.annotations.Language
import splitties.init.appCtx
import java.util.Locale

val appDb by lazy {
    Room.databaseBuilder(appCtx, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
        // 包名已由 io.legado.app 改为 shutiao.reader（DB v80 时），新包名最低从 v80 起，
        // 之前所有版本的旧库都属于不同应用，无法原地升级，统一走破坏性重建。
        .fallbackToDestructiveMigrationFrom(
            false,
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
            61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79
        )
        .addMigrations(*DatabaseMigrations.migrations)
        .allowMainThreadQueries()
        .addCallback(AppDatabase.dbCallback)
        .build()
}

@Database(
    version = 85,
    exportSchema = true,
    entities = [Book::class, BookGroup::class, BookSource::class, BookChapter::class,
        ReplaceRule::class, SearchKeyword::class, Cookie::class,
        Bookmark::class, TxtTocRule::class, ReadRecord::class,
        HttpTTS::class, Cache::class,
        RuleSub::class, DictRule::class, KeyboardAssist::class, Server::class],
    views = [BookSourcePart::class],
    autoMigrations = [
        AutoMigration(from = 83, to = 84),
        AutoMigration(from = 84, to = 85),
    ]
)
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

    companion object {

        const val DATABASE_NAME = "legado.db"

        const val BOOK_TABLE_NAME = "books"
        const val BOOK_SOURCE_TABLE_NAME = "book_sources"

        val dbCallback = object : Callback() {

            override fun onCreate(db: SupportSQLiteDatabase) {
                // 只在 API 级别 23 (Marshmallow) 及以上版本尝试设置区域设置
                try {
                    Log.d(
                        "AppDatabaseCallback",
                        "准备 设置 locale for API ${Build.VERSION.SDK_INT}..."
                    )
                    db.setLocale(Locale.CHINESE)
                    // 在 21 上报错，但无法拦截
                    Log.d(
                        "AppDatabaseCallback",
                        "成功 设置 locale for API ${Build.VERSION.SDK_INT}."
                    )
                } catch (e: Exception) {
                    Log.e(
                        "AppDatabaseCallback",
                        "错误 设置 locale in onCreate for API ${Build.VERSION.SDK_INT}",
                        e
                    )
                }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // 预置分组：groupId, 名称, 排序, 可刷新(1/0), 显示(1/0)
                val presetGroups = arrayOf(
                    arrayOf<Any>(BookGroup.IdAll, "全部", -10, 1, 1),
                    arrayOf<Any>(BookGroup.IdLocal, "本地", -9, 0, 1),
                    arrayOf<Any>(BookGroup.IdUngrouped, "未分组", -7, 1, 1),
                    arrayOf<Any>(BookGroup.IdError, "更新失败", -1, 1, 1),
                )
                @Language("sql")
                val insertGroupSql =
                    "insert into book_groups(groupId, groupName, `order`, enableRefresh, show) " +
                        "select ?, ?, ?, ?, ? " +
                        "where not exists (select 1 from book_groups where groupId = ?)"
                presetGroups.forEach { g ->
                    db.execSQL(insertGroupSql, arrayOf(g[0], g[1], g[2], g[3], g[4], g[0]))
                }
                // 移除已废弃的分组：音频(-3)、本地未分组(-5)；网络未分组(-4)统一重命名为未分组
                db.execSQL("delete from book_groups where groupId in (-3, -5)")
                db.execSQL(
                    "update book_groups set groupName = '未分组' " +
                        "where groupId = ${BookGroup.IdUngrouped} and groupName = '网络未分组'"
                )
                @Language("sql")
                val upBookSourceLoginUiSql =
                    "update book_sources set loginUi = null where loginUi = 'null'"
                db.execSQL(upBookSourceLoginUiSql)
                @Language("sql")
                val upHttpTtsLoginUiSql =
                    "update httpTTS set loginUi = null where loginUi = 'null'"
                db.execSQL(upHttpTtsLoginUiSql)
                @Language("sql")
                val upHttpTtsConcurrentRateSql =
                    "update httpTTS set concurrentRate = '0' where concurrentRate is null"
                db.execSQL(upHttpTtsConcurrentRateSql)
                db.query("select * from keyboardAssists order by serialNo").use {
                    if (it.count == 0) {
                        DefaultData.keyboardAssists.forEach { keyboardAssist ->
                            val contentValues = ContentValues().apply {
                                put("type", keyboardAssist.type)
                                put("key", keyboardAssist.key)
                                put("value", keyboardAssist.value)
                                put("serialNo", keyboardAssist.serialNo)
                            }
                            db.insert(
                                "keyboardAssists",
                                SQLiteDatabase.CONFLICT_REPLACE,
                                contentValues
                            )
                        }
                    }
                }
            }
        }

    }

}