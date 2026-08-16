// 鸿蒙端 Room3 生成代码的 fork 适配: KSP 生成物为 suspend override + executeSQL 旧名,
// 与 CPF fork runtime (非 suspend) 不兼容, 故派生本文件: 剥离 suspend、executeSQL→execSQL。
// 与 KSP 输出保持同步: build.gradle.kts 的 ohosX64Main 排除同名生成文件 (2026-08-04)。
package io.legado.app.`data`

import androidx.room3.InvalidationTracker
import androidx.room3.RoomOpenDelegate
import androidx.room3.migration.AutoMigrationSpec
import androidx.room3.migration.Migration
import androidx.room3.util.TableInfo
import androidx.room3.util.ViewInfo
import androidx.room3.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.legado.app.`data`.dao.BookChapterDao
import io.legado.app.`data`.dao.BookChapterDao_Impl
import io.legado.app.`data`.dao.BookDao
import io.legado.app.`data`.dao.BookDao_Impl
import io.legado.app.`data`.dao.BookGroupDao
import io.legado.app.`data`.dao.BookGroupDao_Impl
import io.legado.app.`data`.dao.BookSourceDao
import io.legado.app.`data`.dao.BookSourceDao_Impl
import io.legado.app.`data`.dao.BookmarkDao
import io.legado.app.`data`.dao.BookmarkDao_Impl
import io.legado.app.`data`.dao.CacheDao
import io.legado.app.`data`.dao.CacheDao_Impl
import io.legado.app.`data`.dao.CookieDao
import io.legado.app.`data`.dao.CookieDao_Impl
import io.legado.app.`data`.dao.DictRuleDao
import io.legado.app.`data`.dao.DictRuleDao_Impl
import io.legado.app.`data`.dao.HttpTTSDao
import io.legado.app.`data`.dao.HttpTTSDao_Impl
import io.legado.app.`data`.dao.KeyboardAssistsDao
import io.legado.app.`data`.dao.KeyboardAssistsDao_Impl
import io.legado.app.`data`.dao.ReadRecordDao
import io.legado.app.`data`.dao.ReadRecordDao_Impl
import io.legado.app.`data`.dao.ReplaceRuleDao
import io.legado.app.`data`.dao.ReplaceRuleDao_Impl
import io.legado.app.`data`.dao.RuleSubDao
import io.legado.app.`data`.dao.RuleSubDao_Impl
import io.legado.app.`data`.dao.SearchKeywordDao
import io.legado.app.`data`.dao.SearchKeywordDao_Impl
import io.legado.app.`data`.dao.ServerDao
import io.legado.app.`data`.dao.ServerDao_Impl
import io.legado.app.`data`.dao.SourceFilterRuleDao
import io.legado.app.`data`.dao.SourceFilterRuleDao_Impl
import io.legado.app.`data`.dao.TxtTocRuleDao
import io.legado.app.`data`.dao.TxtTocRuleDao_Impl
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass
import androidx.room3.util.TableInfo.Companion.read as tableInfoRead
import androidx.room3.util.ViewInfo.Companion.read as viewInfoRead

@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
internal class AppDatabase_Impl : AppDatabase() {
    private val _bookDao: Lazy<BookDao> = lazy {
        BookDao_Impl(this)
    }

    public override val bookDao: BookDao
        get() = _bookDao.value

    private val _bookGroupDao: Lazy<BookGroupDao> = lazy {
        BookGroupDao_Impl(this)
    }

    public override val bookGroupDao: BookGroupDao
        get() = _bookGroupDao.value

    private val _bookSourceDao: Lazy<BookSourceDao> = lazy {
        BookSourceDao_Impl(this)
    }

    public override val bookSourceDao: BookSourceDao
        get() = _bookSourceDao.value

    private val _bookChapterDao: Lazy<BookChapterDao> = lazy {
        BookChapterDao_Impl(this)
    }

    public override val bookChapterDao: BookChapterDao
        get() = _bookChapterDao.value

    private val _replaceRuleDao: Lazy<ReplaceRuleDao> = lazy {
        ReplaceRuleDao_Impl(this)
    }

    public override val replaceRuleDao: ReplaceRuleDao
        get() = _replaceRuleDao.value

    private val _searchKeywordDao: Lazy<SearchKeywordDao> = lazy {
        SearchKeywordDao_Impl(this)
    }

    public override val searchKeywordDao: SearchKeywordDao
        get() = _searchKeywordDao.value

    private val _bookmarkDao: Lazy<BookmarkDao> = lazy {
        BookmarkDao_Impl(this)
    }

    public override val bookmarkDao: BookmarkDao
        get() = _bookmarkDao.value

    private val _cookieDao: Lazy<CookieDao> = lazy {
        CookieDao_Impl(this)
    }

    public override val cookieDao: CookieDao
        get() = _cookieDao.value

    private val _txtTocRuleDao: Lazy<TxtTocRuleDao> = lazy {
        TxtTocRuleDao_Impl(this)
    }

    public override val txtTocRuleDao: TxtTocRuleDao
        get() = _txtTocRuleDao.value

    private val _readRecordDao: Lazy<ReadRecordDao> = lazy {
        ReadRecordDao_Impl(this)
    }

    public override val readRecordDao: ReadRecordDao
        get() = _readRecordDao.value

    private val _httpTTSDao: Lazy<HttpTTSDao> = lazy {
        HttpTTSDao_Impl(this)
    }

    public override val httpTTSDao: HttpTTSDao
        get() = _httpTTSDao.value

    private val _cacheDao: Lazy<CacheDao> = lazy {
        CacheDao_Impl(this)
    }

    public override val cacheDao: CacheDao
        get() = _cacheDao.value

    private val _ruleSubDao: Lazy<RuleSubDao> = lazy {
        RuleSubDao_Impl(this)
    }

    public override val ruleSubDao: RuleSubDao
        get() = _ruleSubDao.value

    private val _dictRuleDao: Lazy<DictRuleDao> = lazy {
        DictRuleDao_Impl(this)
    }

    public override val dictRuleDao: DictRuleDao
        get() = _dictRuleDao.value

    private val _keyboardAssistsDao: Lazy<KeyboardAssistsDao> = lazy {
        KeyboardAssistsDao_Impl(this)
    }

    public override val keyboardAssistsDao: KeyboardAssistsDao
        get() = _keyboardAssistsDao.value

    private val _serverDao: Lazy<ServerDao> = lazy {
        ServerDao_Impl(this)
    }

    public override val serverDao: ServerDao
        get() = _serverDao.value

    private val _sourceFilterRuleDao: Lazy<SourceFilterRuleDao> = lazy {
        SourceFilterRuleDao_Impl(this)
    }

    public override val sourceFilterRuleDao: SourceFilterRuleDao
        get() = _sourceFilterRuleDao.value

    protected override fun createOpenDelegate(): RoomOpenDelegate {
        val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(
            86,
            "d8cc931b4b7f52a7e82c3d1748f4e5e3",
            "9247eb330c5a3df271fc0869692fcd3f"
        ) {
            public override fun createAllTables(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `books` (`bookUrl` TEXT NOT NULL DEFAULT '', `tocUrl` TEXT NOT NULL DEFAULT '', `origin` TEXT NOT NULL DEFAULT 'loc_book', `originName` TEXT NOT NULL DEFAULT '', `name` TEXT NOT NULL DEFAULT '', `author` TEXT NOT NULL DEFAULT '', `kind` TEXT, `customTag` TEXT, `coverUrl` TEXT, `customCoverUrl` TEXT, `intro` TEXT, `customIntro` TEXT, `charset` TEXT, `type` INTEGER NOT NULL DEFAULT 0, `group` INTEGER NOT NULL DEFAULT 0, `latestChapterTitle` TEXT, `latestChapterTime` INTEGER NOT NULL DEFAULT 0, `lastCheckTime` INTEGER NOT NULL DEFAULT 0, `lastCheckCount` INTEGER NOT NULL DEFAULT 0, `totalChapterNum` INTEGER NOT NULL DEFAULT 0, `durChapterTitle` TEXT, `durChapterIndex` INTEGER NOT NULL DEFAULT 0, `durChapterPos` INTEGER NOT NULL DEFAULT 0, `durChapterTime` INTEGER NOT NULL DEFAULT 0, `wordCount` TEXT, `canUpdate` INTEGER NOT NULL DEFAULT 1, `order` INTEGER NOT NULL DEFAULT 0, `originOrder` INTEGER NOT NULL DEFAULT 0, `variable` TEXT, `readConfig` TEXT, `syncTime` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`bookUrl`))"
                )
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_name_author` ON `books` (`name`, `author`)")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `book_groups` (`groupId` INTEGER NOT NULL, `groupName` TEXT NOT NULL, `cover` TEXT, `order` INTEGER NOT NULL, `enableRefresh` INTEGER NOT NULL DEFAULT 1, `show` INTEGER NOT NULL DEFAULT 1, `bookSort` INTEGER NOT NULL DEFAULT -1, PRIMARY KEY(`groupId`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `book_sources` (`bookSourceUrl` TEXT NOT NULL, `bookSourceName` TEXT NOT NULL, `bookSourceGroup` TEXT, `bookSourceType` INTEGER NOT NULL, `bookUrlPattern` TEXT, `customOrder` INTEGER NOT NULL DEFAULT 0, `enabled` INTEGER NOT NULL DEFAULT 1, `enabledExplore` INTEGER NOT NULL DEFAULT 1, `enabledReview` INTEGER NOT NULL DEFAULT 1, `jsLib` TEXT, `enabledCookieJar` INTEGER DEFAULT 0, `enableDangerousApi` INTEGER DEFAULT 0, `concurrentRate` TEXT, `header` TEXT, `loginUrl` TEXT, `loginUi` TEXT, `loginCheckJs` TEXT, `coverDecodeJs` TEXT, `bookSourceComment` TEXT, `variableComment` TEXT, `lastUpdateTime` INTEGER NOT NULL, `respondTime` INTEGER NOT NULL, `weight` INTEGER NOT NULL, `exploreUrl` TEXT, `exploreScreen` TEXT, `exploreStyle` INTEGER NOT NULL DEFAULT 0, `ruleExplore` TEXT, `searchUrl` TEXT, `ruleSearch` TEXT, `ruleBookInfo` TEXT, `ruleToc` TEXT, `ruleContent` TEXT, `ruleReview` TEXT, PRIMARY KEY(`bookSourceUrl`))")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_book_sources_bookSourceUrl` ON `book_sources` (`bookSourceUrl`)")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `chapters` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, `isVolume` INTEGER NOT NULL, `bookUrl` TEXT NOT NULL, `index` INTEGER NOT NULL, `isVip` INTEGER NOT NULL, `isPay` INTEGER NOT NULL, `resourceUrl` TEXT, `tag` TEXT, `wordCount` TEXT, `start` INTEGER, `end` INTEGER, `startFragmentId` TEXT, `endFragmentId` TEXT, `variable` TEXT, PRIMARY KEY(`bookUrl`, `url`), FOREIGN KEY(`bookUrl`) REFERENCES `books`(`bookUrl`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `replace_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL DEFAULT '', `group` TEXT, `pattern` TEXT NOT NULL DEFAULT '', `replacement` TEXT NOT NULL DEFAULT '', `scope` TEXT, `scopeTitle` INTEGER NOT NULL DEFAULT 0, `scopeContent` INTEGER NOT NULL DEFAULT 1, `excludeScope` TEXT, `isEnabled` INTEGER NOT NULL DEFAULT 1, `isRegex` INTEGER NOT NULL DEFAULT 1, `timeoutMillisecond` INTEGER NOT NULL DEFAULT 3000, `sortOrder` INTEGER NOT NULL DEFAULT 0)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_replace_rules_id` ON `replace_rules` (`id`)")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `search_keywords` (`word` TEXT NOT NULL, `usage` INTEGER NOT NULL, `lastUseTime` INTEGER NOT NULL, PRIMARY KEY(`word`))")
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_search_keywords_word` ON `search_keywords` (`word`)")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `cookies` (`url` TEXT NOT NULL, `cookie` TEXT NOT NULL, PRIMARY KEY(`url`))")
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cookies_url` ON `cookies` (`url`)")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `bookmarks` (`time` INTEGER NOT NULL, `bookName` TEXT NOT NULL, `bookAuthor` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `chapterPos` INTEGER NOT NULL, `chapterName` TEXT NOT NULL, `bookText` TEXT NOT NULL, `content` TEXT NOT NULL, PRIMARY KEY(`time`))")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookName_bookAuthor` ON `bookmarks` (`bookName`, `bookAuthor`)")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `txtTocRules` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `rule` TEXT NOT NULL, `example` TEXT, `serialNumber` INTEGER NOT NULL, `enable` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `readRecord` (`bookName` TEXT NOT NULL, `day` INTEGER NOT NULL, `startSec` INTEGER NOT NULL, `endSec` INTEGER NOT NULL, PRIMARY KEY(`bookName`, `day`, `startSec`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `httpTTS` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `contentType` TEXT, `concurrentRate` TEXT DEFAULT '0', `loginUrl` TEXT, `loginUi` TEXT, `header` TEXT, `jsLib` TEXT, `enabledCookieJar` INTEGER DEFAULT 0, `enableDangerousApi` INTEGER DEFAULT 0, `loginCheckJs` TEXT, `lastUpdateTime` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `caches` (`key` TEXT NOT NULL, `value` TEXT, `deadline` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_caches_key` ON `caches` (`key`)")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `ruleSubs` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` INTEGER NOT NULL, `customOrder` INTEGER NOT NULL, `autoUpdate` INTEGER NOT NULL, `update` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `dictRules` (`name` TEXT NOT NULL, `urlRule` TEXT NOT NULL, `showRule` TEXT NOT NULL, `enabled` INTEGER NOT NULL DEFAULT 1, `sortNumber` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`name`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `keyboardAssists` (`type` INTEGER NOT NULL DEFAULT 0, `key` TEXT NOT NULL DEFAULT '', `value` TEXT NOT NULL DEFAULT '', `serialNo` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`type`, `key`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `servers` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `config` TEXT, `sortNumber` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS `source_filter_rules` (`id` TEXT NOT NULL, `name` TEXT NOT NULL DEFAULT '', `enabled` INTEGER NOT NULL DEFAULT 1, `pattern` TEXT NOT NULL DEFAULT '', `fields` TEXT NOT NULL DEFAULT '', `scope` TEXT NOT NULL DEFAULT '', `sortOrder` INTEGER NOT NULL DEFAULT 0, `createTime` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
                connection.execSQL(
                    """
            |CREATE VIEW `book_sources_part` AS select bookSourceUrl, bookSourceName, bookSourceGroup, customOrder, enabled, enabledExplore,
            |    (ifnull(trim(loginUrl), '') <> '' or ifnull(trim(loginUi), '') <> '') hasLoginUrl, lastUpdateTime, respondTime, weight,
            |    (ifnull(trim(exploreUrl), '') <> '') hasExploreUrl
            |    from book_sources
            """.trimMargin()
                )
                connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd8cc931b4b7f52a7e82c3d1748f4e5e3')")
            }

            public override fun dropAllTables(connection: SQLiteConnection) {
                connection.execSQL("DROP TABLE IF EXISTS `books`")
                connection.execSQL("DROP TABLE IF EXISTS `book_groups`")
                connection.execSQL("DROP TABLE IF EXISTS `book_sources`")
                connection.execSQL("DROP TABLE IF EXISTS `chapters`")
                connection.execSQL("DROP TABLE IF EXISTS `replace_rules`")
                connection.execSQL("DROP TABLE IF EXISTS `search_keywords`")
                connection.execSQL("DROP TABLE IF EXISTS `cookies`")
                connection.execSQL("DROP TABLE IF EXISTS `bookmarks`")
                connection.execSQL("DROP TABLE IF EXISTS `txtTocRules`")
                connection.execSQL("DROP TABLE IF EXISTS `readRecord`")
                connection.execSQL("DROP TABLE IF EXISTS `httpTTS`")
                connection.execSQL("DROP TABLE IF EXISTS `caches`")
                connection.execSQL("DROP TABLE IF EXISTS `ruleSubs`")
                connection.execSQL("DROP TABLE IF EXISTS `dictRules`")
                connection.execSQL("DROP TABLE IF EXISTS `keyboardAssists`")
                connection.execSQL("DROP TABLE IF EXISTS `servers`")
                connection.execSQL("DROP TABLE IF EXISTS `source_filter_rules`")
                connection.execSQL("DROP VIEW IF EXISTS `book_sources_part`")
            }

            public override fun onCreate(connection: SQLiteConnection) {
            }

            public override fun onOpen(connection: SQLiteConnection) {
                connection.execSQL("PRAGMA foreign_keys = ON")
                internalInitInvalidationTracker(connection)
            }

            public override fun onPreMigrate(connection: SQLiteConnection) {
                dropFtsSyncTriggers(connection)
            }

            public override fun onPostMigrate(connection: SQLiteConnection) {
            }

            public override fun onValidateSchema(connection: SQLiteConnection): RoomOpenDelegate.ValidationResult {
                val _columnsBooks: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsBooks.put(
                    "bookUrl",
                    TableInfo.Column(
                        "bookUrl",
                        "TEXT",
                        true,
                        1,
                        "''",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "tocUrl",
                    TableInfo.Column("tocUrl", "TEXT", true, 0, "''", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsBooks.put(
                    "origin",
                    TableInfo.Column(
                        "origin",
                        "TEXT",
                        true,
                        0,
                        "'loc_book'",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "originName",
                    TableInfo.Column(
                        "originName",
                        "TEXT",
                        true,
                        0,
                        "''",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "name",
                    TableInfo.Column("name", "TEXT", true, 0, "''", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsBooks.put(
                    "author",
                    TableInfo.Column("author", "TEXT", true, 0, "''", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsBooks.put(
                    "kind",
                    TableInfo.Column("kind", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsBooks.put(
                    "customTag",
                    TableInfo.Column(
                        "customTag",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "coverUrl",
                    TableInfo.Column(
                        "coverUrl",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "customCoverUrl",
                    TableInfo.Column(
                        "customCoverUrl",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "intro",
                    TableInfo.Column("intro", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsBooks.put(
                    "customIntro",
                    TableInfo.Column(
                        "customIntro",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "charset",
                    TableInfo.Column(
                        "charset",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "type",
                    TableInfo.Column("type", "INTEGER", true, 0, "0", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsBooks.put(
                    "group",
                    TableInfo.Column(
                        "group",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "latestChapterTitle",
                    TableInfo.Column(
                        "latestChapterTitle",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "latestChapterTime",
                    TableInfo.Column(
                        "latestChapterTime",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "lastCheckTime",
                    TableInfo.Column(
                        "lastCheckTime",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "lastCheckCount",
                    TableInfo.Column(
                        "lastCheckCount",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "totalChapterNum",
                    TableInfo.Column(
                        "totalChapterNum",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "durChapterTitle",
                    TableInfo.Column(
                        "durChapterTitle",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "durChapterIndex",
                    TableInfo.Column(
                        "durChapterIndex",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "durChapterPos",
                    TableInfo.Column(
                        "durChapterPos",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "durChapterTime",
                    TableInfo.Column(
                        "durChapterTime",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "wordCount",
                    TableInfo.Column(
                        "wordCount",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "canUpdate",
                    TableInfo.Column(
                        "canUpdate",
                        "INTEGER",
                        true,
                        0,
                        "1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "order",
                    TableInfo.Column(
                        "order",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "originOrder",
                    TableInfo.Column(
                        "originOrder",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "variable",
                    TableInfo.Column(
                        "variable",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "readConfig",
                    TableInfo.Column(
                        "readConfig",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBooks.put(
                    "syncTime",
                    TableInfo.Column(
                        "syncTime",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysBooks: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesBooks: MutableSet<TableInfo.Index> = mutableSetOf()
                _indicesBooks.add(
                    TableInfo.Index(
                        "index_books_name_author",
                        true,
                        listOf("name", "author"),
                        listOf("ASC", "ASC")
                    )
                )
                val _infoBooks: TableInfo =
                    TableInfo("books", _columnsBooks, _foreignKeysBooks, _indicesBooks)
                val _existingBooks: TableInfo = tableInfoRead(connection, "books")
                if (!_infoBooks.equals(_existingBooks)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |books(io.legado.app.data.entities.Book).
              | Expected:
              |""".trimMargin() + _infoBooks + """
              |
              | Found:
              |""".trimMargin() + _existingBooks
                    )
                }
                val _columnsBookGroups: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsBookGroups.put(
                    "groupId",
                    TableInfo.Column(
                        "groupId",
                        "INTEGER",
                        true,
                        1,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookGroups.put(
                    "groupName",
                    TableInfo.Column(
                        "groupName",
                        "TEXT",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookGroups.put(
                    "cover",
                    TableInfo.Column("cover", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsBookGroups.put(
                    "order",
                    TableInfo.Column(
                        "order",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookGroups.put(
                    "enableRefresh",
                    TableInfo.Column(
                        "enableRefresh",
                        "INTEGER",
                        true,
                        0,
                        "1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookGroups.put(
                    "show",
                    TableInfo.Column("show", "INTEGER", true, 0, "1", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsBookGroups.put(
                    "bookSort",
                    TableInfo.Column(
                        "bookSort",
                        "INTEGER",
                        true,
                        0,
                        "-1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysBookGroups: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesBookGroups: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoBookGroups: TableInfo = TableInfo(
                    "book_groups",
                    _columnsBookGroups,
                    _foreignKeysBookGroups,
                    _indicesBookGroups
                )
                val _existingBookGroups: TableInfo = tableInfoRead(connection, "book_groups")
                if (!_infoBookGroups.equals(_existingBookGroups)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |book_groups(io.legado.app.data.entities.BookGroup).
              | Expected:
              |""".trimMargin() + _infoBookGroups + """
              |
              | Found:
              |""".trimMargin() + _existingBookGroups
                    )
                }
                val _columnsBookSources: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsBookSources.put(
                    "bookSourceUrl",
                    TableInfo.Column(
                        "bookSourceUrl",
                        "TEXT",
                        true,
                        1,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "bookSourceName",
                    TableInfo.Column(
                        "bookSourceName",
                        "TEXT",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "bookSourceGroup",
                    TableInfo.Column(
                        "bookSourceGroup",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "bookSourceType",
                    TableInfo.Column(
                        "bookSourceType",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "bookUrlPattern",
                    TableInfo.Column(
                        "bookUrlPattern",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "customOrder",
                    TableInfo.Column(
                        "customOrder",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "enabled",
                    TableInfo.Column(
                        "enabled",
                        "INTEGER",
                        true,
                        0,
                        "1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "enabledExplore",
                    TableInfo.Column(
                        "enabledExplore",
                        "INTEGER",
                        true,
                        0,
                        "1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "enabledReview",
                    TableInfo.Column(
                        "enabledReview",
                        "INTEGER",
                        true,
                        0,
                        "1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "jsLib",
                    TableInfo.Column("jsLib", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsBookSources.put(
                    "enabledCookieJar",
                    TableInfo.Column(
                        "enabledCookieJar",
                        "INTEGER",
                        false,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "enableDangerousApi",
                    TableInfo.Column(
                        "enableDangerousApi",
                        "INTEGER",
                        false,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "concurrentRate",
                    TableInfo.Column(
                        "concurrentRate",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "header",
                    TableInfo.Column(
                        "header",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "loginUrl",
                    TableInfo.Column(
                        "loginUrl",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "loginUi",
                    TableInfo.Column(
                        "loginUi",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "loginCheckJs",
                    TableInfo.Column(
                        "loginCheckJs",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "coverDecodeJs",
                    TableInfo.Column(
                        "coverDecodeJs",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "bookSourceComment",
                    TableInfo.Column(
                        "bookSourceComment",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "variableComment",
                    TableInfo.Column(
                        "variableComment",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "lastUpdateTime",
                    TableInfo.Column(
                        "lastUpdateTime",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "respondTime",
                    TableInfo.Column(
                        "respondTime",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "weight",
                    TableInfo.Column(
                        "weight",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "exploreUrl",
                    TableInfo.Column(
                        "exploreUrl",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "exploreScreen",
                    TableInfo.Column(
                        "exploreScreen",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "exploreStyle",
                    TableInfo.Column(
                        "exploreStyle",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "ruleExplore",
                    TableInfo.Column(
                        "ruleExplore",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "searchUrl",
                    TableInfo.Column(
                        "searchUrl",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "ruleSearch",
                    TableInfo.Column(
                        "ruleSearch",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "ruleBookInfo",
                    TableInfo.Column(
                        "ruleBookInfo",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "ruleToc",
                    TableInfo.Column(
                        "ruleToc",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "ruleContent",
                    TableInfo.Column(
                        "ruleContent",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookSources.put(
                    "ruleReview",
                    TableInfo.Column(
                        "ruleReview",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysBookSources: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesBookSources: MutableSet<TableInfo.Index> = mutableSetOf()
                _indicesBookSources.add(
                    TableInfo.Index(
                        "index_book_sources_bookSourceUrl",
                        false,
                        listOf("bookSourceUrl"),
                        listOf("ASC")
                    )
                )
                val _infoBookSources: TableInfo = TableInfo(
                    "book_sources",
                    _columnsBookSources,
                    _foreignKeysBookSources,
                    _indicesBookSources
                )
                val _existingBookSources: TableInfo = tableInfoRead(connection, "book_sources")
                if (!_infoBookSources.equals(_existingBookSources)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |book_sources(io.legado.app.data.entities.BookSource).
              | Expected:
              |""".trimMargin() + _infoBookSources + """
              |
              | Found:
              |""".trimMargin() + _existingBookSources
                    )
                }
                val _columnsChapters: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsChapters.put(
                    "url",
                    TableInfo.Column("url", "TEXT", true, 2, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsChapters.put(
                    "title",
                    TableInfo.Column("title", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsChapters.put(
                    "isVolume",
                    TableInfo.Column(
                        "isVolume",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "bookUrl",
                    TableInfo.Column(
                        "bookUrl",
                        "TEXT",
                        true,
                        1,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "index",
                    TableInfo.Column(
                        "index",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "isVip",
                    TableInfo.Column(
                        "isVip",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "isPay",
                    TableInfo.Column(
                        "isPay",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "resourceUrl",
                    TableInfo.Column(
                        "resourceUrl",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "tag",
                    TableInfo.Column("tag", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsChapters.put(
                    "wordCount",
                    TableInfo.Column(
                        "wordCount",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "start",
                    TableInfo.Column(
                        "start",
                        "INTEGER",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "end",
                    TableInfo.Column(
                        "end",
                        "INTEGER",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "startFragmentId",
                    TableInfo.Column(
                        "startFragmentId",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "endFragmentId",
                    TableInfo.Column(
                        "endFragmentId",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsChapters.put(
                    "variable",
                    TableInfo.Column(
                        "variable",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysChapters: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                _foreignKeysChapters.add(
                    TableInfo.ForeignKey(
                        "books",
                        "CASCADE",
                        "NO ACTION",
                        listOf("bookUrl"),
                        listOf("bookUrl")
                    )
                )
                val _indicesChapters: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoChapters: TableInfo =
                    TableInfo("chapters", _columnsChapters, _foreignKeysChapters, _indicesChapters)
                val _existingChapters: TableInfo = tableInfoRead(connection, "chapters")
                if (!_infoChapters.equals(_existingChapters)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |chapters(io.legado.app.data.entities.BookChapter).
              | Expected:
              |""".trimMargin() + _infoChapters + """
              |
              | Found:
              |""".trimMargin() + _existingChapters
                    )
                }
                val _columnsReplaceRules: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsReplaceRules.put(
                    "id",
                    TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsReplaceRules.put(
                    "name",
                    TableInfo.Column("name", "TEXT", true, 0, "''", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsReplaceRules.put(
                    "group",
                    TableInfo.Column("group", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsReplaceRules.put(
                    "pattern",
                    TableInfo.Column(
                        "pattern",
                        "TEXT",
                        true,
                        0,
                        "''",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsReplaceRules.put(
                    "replacement",
                    TableInfo.Column(
                        "replacement",
                        "TEXT",
                        true,
                        0,
                        "''",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsReplaceRules.put(
                    "scope",
                    TableInfo.Column("scope", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsReplaceRules.put(
                    "scopeTitle",
                    TableInfo.Column(
                        "scopeTitle",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsReplaceRules.put(
                    "scopeContent",
                    TableInfo.Column(
                        "scopeContent",
                        "INTEGER",
                        true,
                        0,
                        "1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsReplaceRules.put(
                    "excludeScope",
                    TableInfo.Column(
                        "excludeScope",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsReplaceRules.put(
                    "isEnabled",
                    TableInfo.Column(
                        "isEnabled",
                        "INTEGER",
                        true,
                        0,
                        "1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsReplaceRules.put(
                    "isRegex",
                    TableInfo.Column(
                        "isRegex",
                        "INTEGER",
                        true,
                        0,
                        "1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsReplaceRules.put(
                    "timeoutMillisecond",
                    TableInfo.Column(
                        "timeoutMillisecond",
                        "INTEGER",
                        true,
                        0,
                        "3000",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsReplaceRules.put(
                    "sortOrder",
                    TableInfo.Column(
                        "sortOrder",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysReplaceRules: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesReplaceRules: MutableSet<TableInfo.Index> = mutableSetOf()
                _indicesReplaceRules.add(
                    TableInfo.Index(
                        "index_replace_rules_id",
                        false,
                        listOf("id"),
                        listOf("ASC")
                    )
                )
                val _infoReplaceRules: TableInfo = TableInfo(
                    "replace_rules",
                    _columnsReplaceRules,
                    _foreignKeysReplaceRules,
                    _indicesReplaceRules
                )
                val _existingReplaceRules: TableInfo = tableInfoRead(connection, "replace_rules")
                if (!_infoReplaceRules.equals(_existingReplaceRules)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |replace_rules(io.legado.app.data.entities.ReplaceRule).
              | Expected:
              |""".trimMargin() + _infoReplaceRules + """
              |
              | Found:
              |""".trimMargin() + _existingReplaceRules
                    )
                }
                val _columnsSearchKeywords: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsSearchKeywords.put(
                    "word",
                    TableInfo.Column("word", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsSearchKeywords.put(
                    "usage",
                    TableInfo.Column(
                        "usage",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsSearchKeywords.put(
                    "lastUseTime",
                    TableInfo.Column(
                        "lastUseTime",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysSearchKeywords: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesSearchKeywords: MutableSet<TableInfo.Index> = mutableSetOf()
                _indicesSearchKeywords.add(
                    TableInfo.Index(
                        "index_search_keywords_word",
                        true,
                        listOf("word"),
                        listOf("ASC")
                    )
                )
                val _infoSearchKeywords: TableInfo = TableInfo(
                    "search_keywords",
                    _columnsSearchKeywords,
                    _foreignKeysSearchKeywords,
                    _indicesSearchKeywords
                )
                val _existingSearchKeywords: TableInfo =
                    tableInfoRead(connection, "search_keywords")
                if (!_infoSearchKeywords.equals(_existingSearchKeywords)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |search_keywords(io.legado.app.data.entities.SearchKeyword).
              | Expected:
              |""".trimMargin() + _infoSearchKeywords + """
              |
              | Found:
              |""".trimMargin() + _existingSearchKeywords
                    )
                }
                val _columnsCookies: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsCookies.put(
                    "url",
                    TableInfo.Column("url", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsCookies.put(
                    "cookie",
                    TableInfo.Column("cookie", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                val _foreignKeysCookies: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesCookies: MutableSet<TableInfo.Index> = mutableSetOf()
                _indicesCookies.add(
                    TableInfo.Index(
                        "index_cookies_url",
                        true,
                        listOf("url"),
                        listOf("ASC")
                    )
                )
                val _infoCookies: TableInfo =
                    TableInfo("cookies", _columnsCookies, _foreignKeysCookies, _indicesCookies)
                val _existingCookies: TableInfo = tableInfoRead(connection, "cookies")
                if (!_infoCookies.equals(_existingCookies)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |cookies(io.legado.app.data.entities.Cookie).
              | Expected:
              |""".trimMargin() + _infoCookies + """
              |
              | Found:
              |""".trimMargin() + _existingCookies
                    )
                }
                val _columnsBookmarks: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsBookmarks.put(
                    "time",
                    TableInfo.Column(
                        "time",
                        "INTEGER",
                        true,
                        1,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookmarks.put(
                    "bookName",
                    TableInfo.Column(
                        "bookName",
                        "TEXT",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookmarks.put(
                    "bookAuthor",
                    TableInfo.Column(
                        "bookAuthor",
                        "TEXT",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookmarks.put(
                    "chapterIndex",
                    TableInfo.Column(
                        "chapterIndex",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookmarks.put(
                    "chapterPos",
                    TableInfo.Column(
                        "chapterPos",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookmarks.put(
                    "chapterName",
                    TableInfo.Column(
                        "chapterName",
                        "TEXT",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookmarks.put(
                    "bookText",
                    TableInfo.Column(
                        "bookText",
                        "TEXT",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsBookmarks.put(
                    "content",
                    TableInfo.Column(
                        "content",
                        "TEXT",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysBookmarks: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesBookmarks: MutableSet<TableInfo.Index> = mutableSetOf()
                _indicesBookmarks.add(
                    TableInfo.Index(
                        "index_bookmarks_bookName_bookAuthor",
                        false,
                        listOf("bookName", "bookAuthor"),
                        listOf("ASC", "ASC")
                    )
                )
                val _infoBookmarks: TableInfo = TableInfo(
                    "bookmarks",
                    _columnsBookmarks,
                    _foreignKeysBookmarks,
                    _indicesBookmarks
                )
                val _existingBookmarks: TableInfo = tableInfoRead(connection, "bookmarks")
                if (!_infoBookmarks.equals(_existingBookmarks)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |bookmarks(io.legado.app.data.entities.Bookmark).
              | Expected:
              |""".trimMargin() + _infoBookmarks + """
              |
              | Found:
              |""".trimMargin() + _existingBookmarks
                    )
                }
                val _columnsTxtTocRules: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsTxtTocRules.put(
                    "id",
                    TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsTxtTocRules.put(
                    "name",
                    TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsTxtTocRules.put(
                    "rule",
                    TableInfo.Column("rule", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsTxtTocRules.put(
                    "example",
                    TableInfo.Column(
                        "example",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsTxtTocRules.put(
                    "serialNumber",
                    TableInfo.Column(
                        "serialNumber",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsTxtTocRules.put(
                    "enable",
                    TableInfo.Column(
                        "enable",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysTxtTocRules: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesTxtTocRules: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoTxtTocRules: TableInfo = TableInfo(
                    "txtTocRules",
                    _columnsTxtTocRules,
                    _foreignKeysTxtTocRules,
                    _indicesTxtTocRules
                )
                val _existingTxtTocRules: TableInfo = tableInfoRead(connection, "txtTocRules")
                if (!_infoTxtTocRules.equals(_existingTxtTocRules)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |txtTocRules(io.legado.app.data.entities.TxtTocRule).
              | Expected:
              |""".trimMargin() + _infoTxtTocRules + """
              |
              | Found:
              |""".trimMargin() + _existingTxtTocRules
                    )
                }
                val _columnsReadRecord: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsReadRecord.put(
                    "bookName",
                    TableInfo.Column(
                        "bookName",
                        "TEXT",
                        true,
                        1,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsReadRecord.put(
                    "day",
                    TableInfo.Column("day", "INTEGER", true, 2, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsReadRecord.put(
                    "startSec",
                    TableInfo.Column(
                        "startSec",
                        "INTEGER",
                        true,
                        3,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsReadRecord.put(
                    "endSec",
                    TableInfo.Column(
                        "endSec",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysReadRecord: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesReadRecord: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoReadRecord: TableInfo = TableInfo(
                    "readRecord",
                    _columnsReadRecord,
                    _foreignKeysReadRecord,
                    _indicesReadRecord
                )
                val _existingReadRecord: TableInfo = tableInfoRead(connection, "readRecord")
                if (!_infoReadRecord.equals(_existingReadRecord)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |readRecord(io.legado.app.data.entities.ReadRecord).
              | Expected:
              |""".trimMargin() + _infoReadRecord + """
              |
              | Found:
              |""".trimMargin() + _existingReadRecord
                    )
                }
                val _columnsHttpTTS: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsHttpTTS.put(
                    "id",
                    TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsHttpTTS.put(
                    "name",
                    TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsHttpTTS.put(
                    "url",
                    TableInfo.Column("url", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsHttpTTS.put(
                    "contentType",
                    TableInfo.Column(
                        "contentType",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsHttpTTS.put(
                    "concurrentRate",
                    TableInfo.Column(
                        "concurrentRate",
                        "TEXT",
                        false,
                        0,
                        "'0'",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsHttpTTS.put(
                    "loginUrl",
                    TableInfo.Column(
                        "loginUrl",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsHttpTTS.put(
                    "loginUi",
                    TableInfo.Column(
                        "loginUi",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsHttpTTS.put(
                    "header",
                    TableInfo.Column(
                        "header",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsHttpTTS.put(
                    "jsLib",
                    TableInfo.Column("jsLib", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsHttpTTS.put(
                    "enabledCookieJar",
                    TableInfo.Column(
                        "enabledCookieJar",
                        "INTEGER",
                        false,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsHttpTTS.put(
                    "enableDangerousApi",
                    TableInfo.Column(
                        "enableDangerousApi",
                        "INTEGER",
                        false,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsHttpTTS.put(
                    "loginCheckJs",
                    TableInfo.Column(
                        "loginCheckJs",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsHttpTTS.put(
                    "lastUpdateTime",
                    TableInfo.Column(
                        "lastUpdateTime",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysHttpTTS: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesHttpTTS: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoHttpTTS: TableInfo =
                    TableInfo("httpTTS", _columnsHttpTTS, _foreignKeysHttpTTS, _indicesHttpTTS)
                val _existingHttpTTS: TableInfo = tableInfoRead(connection, "httpTTS")
                if (!_infoHttpTTS.equals(_existingHttpTTS)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |httpTTS(io.legado.app.data.entities.HttpTTS).
              | Expected:
              |""".trimMargin() + _infoHttpTTS + """
              |
              | Found:
              |""".trimMargin() + _existingHttpTTS
                    )
                }
                val _columnsCaches: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsCaches.put(
                    "key",
                    TableInfo.Column("key", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsCaches.put(
                    "value",
                    TableInfo.Column("value", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsCaches.put(
                    "deadline",
                    TableInfo.Column(
                        "deadline",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysCaches: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesCaches: MutableSet<TableInfo.Index> = mutableSetOf()
                _indicesCaches.add(
                    TableInfo.Index(
                        "index_caches_key",
                        true,
                        listOf("key"),
                        listOf("ASC")
                    )
                )
                val _infoCaches: TableInfo =
                    TableInfo("caches", _columnsCaches, _foreignKeysCaches, _indicesCaches)
                val _existingCaches: TableInfo = tableInfoRead(connection, "caches")
                if (!_infoCaches.equals(_existingCaches)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |caches(io.legado.app.data.entities.Cache).
              | Expected:
              |""".trimMargin() + _infoCaches + """
              |
              | Found:
              |""".trimMargin() + _existingCaches
                    )
                }
                val _columnsRuleSubs: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsRuleSubs.put(
                    "id",
                    TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsRuleSubs.put(
                    "name",
                    TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsRuleSubs.put(
                    "url",
                    TableInfo.Column("url", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsRuleSubs.put(
                    "type",
                    TableInfo.Column(
                        "type",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsRuleSubs.put(
                    "customOrder",
                    TableInfo.Column(
                        "customOrder",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsRuleSubs.put(
                    "autoUpdate",
                    TableInfo.Column(
                        "autoUpdate",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsRuleSubs.put(
                    "update",
                    TableInfo.Column(
                        "update",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysRuleSubs: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesRuleSubs: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoRuleSubs: TableInfo =
                    TableInfo("ruleSubs", _columnsRuleSubs, _foreignKeysRuleSubs, _indicesRuleSubs)
                val _existingRuleSubs: TableInfo = tableInfoRead(connection, "ruleSubs")
                if (!_infoRuleSubs.equals(_existingRuleSubs)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |ruleSubs(io.legado.app.data.entities.RuleSub).
              | Expected:
              |""".trimMargin() + _infoRuleSubs + """
              |
              | Found:
              |""".trimMargin() + _existingRuleSubs
                    )
                }
                val _columnsDictRules: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsDictRules.put(
                    "name",
                    TableInfo.Column("name", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsDictRules.put(
                    "urlRule",
                    TableInfo.Column(
                        "urlRule",
                        "TEXT",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsDictRules.put(
                    "showRule",
                    TableInfo.Column(
                        "showRule",
                        "TEXT",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsDictRules.put(
                    "enabled",
                    TableInfo.Column(
                        "enabled",
                        "INTEGER",
                        true,
                        0,
                        "1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsDictRules.put(
                    "sortNumber",
                    TableInfo.Column(
                        "sortNumber",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysDictRules: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesDictRules: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoDictRules: TableInfo = TableInfo(
                    "dictRules",
                    _columnsDictRules,
                    _foreignKeysDictRules,
                    _indicesDictRules
                )
                val _existingDictRules: TableInfo = tableInfoRead(connection, "dictRules")
                if (!_infoDictRules.equals(_existingDictRules)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |dictRules(io.legado.app.data.entities.DictRule).
              | Expected:
              |""".trimMargin() + _infoDictRules + """
              |
              | Found:
              |""".trimMargin() + _existingDictRules
                    )
                }
                val _columnsKeyboardAssists: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsKeyboardAssists.put(
                    "type",
                    TableInfo.Column("type", "INTEGER", true, 1, "0", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsKeyboardAssists.put(
                    "key",
                    TableInfo.Column("key", "TEXT", true, 2, "''", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsKeyboardAssists.put(
                    "value",
                    TableInfo.Column("value", "TEXT", true, 0, "''", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsKeyboardAssists.put(
                    "serialNo",
                    TableInfo.Column(
                        "serialNo",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysKeyboardAssists: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesKeyboardAssists: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoKeyboardAssists: TableInfo = TableInfo(
                    "keyboardAssists",
                    _columnsKeyboardAssists,
                    _foreignKeysKeyboardAssists,
                    _indicesKeyboardAssists
                )
                val _existingKeyboardAssists: TableInfo =
                    tableInfoRead(connection, "keyboardAssists")
                if (!_infoKeyboardAssists.equals(_existingKeyboardAssists)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |keyboardAssists(io.legado.app.data.entities.KeyboardAssist).
              | Expected:
              |""".trimMargin() + _infoKeyboardAssists + """
              |
              | Found:
              |""".trimMargin() + _existingKeyboardAssists
                    )
                }
                val _columnsServers: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsServers.put(
                    "id",
                    TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsServers.put(
                    "name",
                    TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsServers.put(
                    "type",
                    TableInfo.Column("type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsServers.put(
                    "config",
                    TableInfo.Column(
                        "config",
                        "TEXT",
                        false,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsServers.put(
                    "sortNumber",
                    TableInfo.Column(
                        "sortNumber",
                        "INTEGER",
                        true,
                        0,
                        null,
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysServers: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesServers: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoServers: TableInfo =
                    TableInfo("servers", _columnsServers, _foreignKeysServers, _indicesServers)
                val _existingServers: TableInfo = tableInfoRead(connection, "servers")
                if (!_infoServers.equals(_existingServers)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |servers(io.legado.app.data.entities.Server).
              | Expected:
              |""".trimMargin() + _infoServers + """
              |
              | Found:
              |""".trimMargin() + _existingServers
                    )
                }
                val _columnsSourceFilterRules: MutableMap<String, TableInfo.Column> = mutableMapOf()
                _columnsSourceFilterRules.put(
                    "id",
                    TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsSourceFilterRules.put(
                    "name",
                    TableInfo.Column("name", "TEXT", true, 0, "''", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsSourceFilterRules.put(
                    "enabled",
                    TableInfo.Column(
                        "enabled",
                        "INTEGER",
                        true,
                        0,
                        "1",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsSourceFilterRules.put(
                    "pattern",
                    TableInfo.Column(
                        "pattern",
                        "TEXT",
                        true,
                        0,
                        "''",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsSourceFilterRules.put(
                    "fields",
                    TableInfo.Column("fields", "TEXT", true, 0, "''", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsSourceFilterRules.put(
                    "scope",
                    TableInfo.Column("scope", "TEXT", true, 0, "''", TableInfo.CREATED_FROM_ENTITY)
                )
                _columnsSourceFilterRules.put(
                    "sortOrder",
                    TableInfo.Column(
                        "sortOrder",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                _columnsSourceFilterRules.put(
                    "createTime",
                    TableInfo.Column(
                        "createTime",
                        "INTEGER",
                        true,
                        0,
                        "0",
                        TableInfo.CREATED_FROM_ENTITY
                    )
                )
                val _foreignKeysSourceFilterRules: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
                val _indicesSourceFilterRules: MutableSet<TableInfo.Index> = mutableSetOf()
                val _infoSourceFilterRules: TableInfo = TableInfo(
                    "source_filter_rules",
                    _columnsSourceFilterRules,
                    _foreignKeysSourceFilterRules,
                    _indicesSourceFilterRules
                )
                val _existingSourceFilterRules: TableInfo =
                    tableInfoRead(connection, "source_filter_rules")
                if (!_infoSourceFilterRules.equals(_existingSourceFilterRules)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |source_filter_rules(io.legado.app.data.entities.SourceFilterRule).
              | Expected:
              |""".trimMargin() + _infoSourceFilterRules + """
              |
              | Found:
              |""".trimMargin() + _existingSourceFilterRules
                    )
                }
                val _infoBookSourcesPart: ViewInfo = ViewInfo(
                    "book_sources_part", """
            |CREATE VIEW `book_sources_part` AS select bookSourceUrl, bookSourceName, bookSourceGroup, customOrder, enabled, enabledExplore,
            |    (ifnull(trim(loginUrl), '') <> '' or ifnull(trim(loginUi), '') <> '') hasLoginUrl, lastUpdateTime, respondTime, weight,
            |    (ifnull(trim(exploreUrl), '') <> '') hasExploreUrl
            |    from book_sources
            """.trimMargin()
                )
                val _existingBookSourcesPart: ViewInfo =
                    viewInfoRead(connection, "book_sources_part")
                if (!_infoBookSourcesPart.equals(_existingBookSourcesPart)) {
                    return RoomOpenDelegate.ValidationResult(
                        false, """
              |book_sources_part(io.legado.app.data.entities.BookSourcePart).
              | Expected:
              |""".trimMargin() + _infoBookSourcesPart + """
              |
              | Found:
              |""".trimMargin() + _existingBookSourcesPart
                    )
                }
                return RoomOpenDelegate.ValidationResult(true, null)
            }
        }
        return _openDelegate
    }

    protected override fun createInvalidationTracker(): InvalidationTracker {
        val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
        val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
        val _tables: MutableSet<String> = mutableSetOf()
        _tables.add("book_sources")
        _viewTables.put("book_sources_part", _tables)
        return InvalidationTracker(
            this,
            _shadowTablesMap,
            _viewTables,
            "books",
            "book_groups",
            "book_sources",
            "chapters",
            "replace_rules",
            "search_keywords",
            "cookies",
            "bookmarks",
            "txtTocRules",
            "readRecord",
            "httpTTS",
            "caches",
            "ruleSubs",
            "dictRules",
            "keyboardAssists",
            "servers",
            "source_filter_rules"
        )
    }

    protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
        val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
        _typeConvertersMap.put(BookDao::class, BookDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(BookGroupDao::class, BookGroupDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(BookSourceDao::class, BookSourceDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(BookChapterDao::class, BookChapterDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(ReplaceRuleDao::class, ReplaceRuleDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(
            SearchKeywordDao::class,
            SearchKeywordDao_Impl.getRequiredConverters()
        )
        _typeConvertersMap.put(BookmarkDao::class, BookmarkDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(CookieDao::class, CookieDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(TxtTocRuleDao::class, TxtTocRuleDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(ReadRecordDao::class, ReadRecordDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(HttpTTSDao::class, HttpTTSDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(CacheDao::class, CacheDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(RuleSubDao::class, RuleSubDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(DictRuleDao::class, DictRuleDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(
            KeyboardAssistsDao::class,
            KeyboardAssistsDao_Impl.getRequiredConverters()
        )
        _typeConvertersMap.put(ServerDao::class, ServerDao_Impl.getRequiredConverters())
        _typeConvertersMap.put(
            SourceFilterRuleDao::class,
            SourceFilterRuleDao_Impl.getRequiredConverters()
        )
        return _typeConvertersMap
    }

    public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
        val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
        return _autoMigrationSpecsSet
    }

    public override fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>): List<Migration> {
        val _autoMigrations: MutableList<Migration> = mutableListOf()
        _autoMigrations.add(AppDatabase_AutoMigration_83_84_Impl())
        _autoMigrations.add(AppDatabase_AutoMigration_84_85_Impl())
        _autoMigrations.add(AppDatabase_AutoMigration_85_86_Impl())
        return _autoMigrations
    }
}

