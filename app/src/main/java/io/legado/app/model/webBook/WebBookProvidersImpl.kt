package io.legado.app.model.webBook

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import io.legado.app.api.controller.BookControllerImageProviderImpl
import io.legado.app.api.controller.ImageControllerProviders
import io.legado.app.api.controller.ReadBookStateProviderImpl
import io.legado.app.api.controller.ReadBookStateProviders
import io.legado.app.data.AndroidAppDatabaseProvider
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.AppDbAccessor
import io.legado.app.data.AppDbProviders
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppCacheManager
import io.legado.app.help.IntentData
import io.legado.app.help.IntentDataAccessor
import io.legado.app.help.IntentDataProviders
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookHelpAccessor
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.ContentProcessorAccessor
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppConfigAccessor
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemeConfigData
import io.legado.app.help.config.ThemeConfigProvider
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.http.OkHttpClientProvider
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.source.SourceHelpAccessor
import io.legado.app.help.source.SourceHelpAccessors
import io.legado.app.help.source.registerAndroidVerificationUiProvider
import io.legado.app.help.storage.AndroidDataStorage
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.model.AudioPlay
import io.legado.app.model.Debug
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.BitmapProviderImpl
import io.legado.app.model.fileBook.BitmapProviders
import io.legado.app.model.fileBook.ZipFileWrapperFactoryImpl
import io.legado.app.model.fileBook.ZipFileWrapperFactoryProviders
import io.legado.app.utils.RegexReplacer
import io.legado.app.utils.RegexReplacers
import io.legado.app.utils.replace
import splitties.init.appCtx

/**
 * webBook 编排层下沉 (W3-f) 配套 provider 安卓实现。
 *
 * 6 个 provider 接口 (AppDbAccessor/AppConfigAccessor/ContentProcessorAccessor/
 * BookHelpAccessor/IntentDataAccessor/RegexReplacer) 在 shared 中定义, 由本文件
 * 统一实现并在 App.onCreate 经 [registerAndroidWebBookProviders] 注册。
 *
 * 实现内部委托给 app 端原有单例 (appDb/AppConfig/ContentProcessor/BookHelp/
 * IntentData) 及原 [CharSequence.replace] 扩展 (RegexExtensions.kt 中, 依赖
 * Coroutine.async/JsEngines/CrashHandler/appCtx 无法下沉 shared),
 * 行为与下沉前完全一致。
 *
 * 注册顺序参考 BookInfoRefreshers (App.onCreate 早期), Debug.SourceDebugLogger
 * 注册由 app 端 Debug 模块负责 (见 [Debug.registerAndroidSourceDebugLogger]),
 * 不在本文件范围。
 */
object WebBookProvidersImpl :
    AppDbAccessor,
    AppConfigAccessor,
    ContentProcessorAccessor,
    BookHelpAccessor,
    IntentDataAccessor,
    RegexReplacer,
    OkHttpClientProvider,
    SourceHelpAccessor,
    ThemeConfigProvider {

    // ---- AppDbAccessor ----
    override val bookDao get() = appDb.bookDao
    override val bookSourceDao get() = appDb.bookSourceDao
    override val bookChapterDao get() = appDb.bookChapterDao
    // BookInfoViewModelShared.loadGroup 用 (查询分组名)
    override val bookGroupDao get() = appDb.bookGroupDao
    override val replaceRuleDao get() = appDb.replaceRuleDao
    override val readRecordDao get() = appDb.readRecordDao
    override val serverDao get() = appDb.serverDao
    // TxtTocRuleViewModelShared / DictRuleViewModelShared 下沉新增的 2 个 DAO 暴露点
    override val txtTocRuleDao get() = appDb.txtTocRuleDao
    override val dictRuleDao get() = appDb.dictRuleDao
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
    // Room 3 移除了同步的 runInTransaction (事务入口只剩 suspend 的 useWriterConnection);
    // 用 runBlocking 包裹会与 block 内自己的 runBlocking 争抢写连接而死锁, 故保持直通,
    // 每个 DAO 操作本身原子 (与桌面端 DesktopAppDbAccessor 一致)。
    override fun <R> runInTransaction(block: () -> R): R {
        return block()
    }

    // suspend 版本走 Room 真事务: useWriterConnection 把写连接放进协程上下文,
    // block 内的 suspend DAO 复用同一连接; immediateTransaction 对应 BEGIN IMMEDIATE, 异常自动回滚。
    override suspend fun <R> runInTransactionSuspending(block: suspend () -> R): R {
        return appDb.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }
    }

    // ---- AppConfigAccessor ----
    override val threadCount: Int get() = AppConfig.threadCount
    override val tocCountWords: Boolean get() = AppConfig.tocCountWords
    override fun setTocCountWords(value: Boolean) {
        AppConfig.tocCountWords = value
    }
    override val tocUiUseReplace: Boolean get() = AppConfig.tocUiUseReplace
    override fun setTocUiUseReplace(value: Boolean) {
        AppConfig.tocUiUseReplace = value
    }
    override var chineseConverterType: Int
        get() = AppConfig.chineseConverterType
        set(value) { AppConfig.chineseConverterType = value }
    override val replaceEnableDefault: Boolean get() = AppConfig.replaceEnableDefault
    override val enableReadRecord: Boolean get() = AppConfig.enableReadRecord

    // ---- 书架业务 ----
    override val bookshelfSort: Int get() = AppConfig.bookshelfSort
    override val bookshelfLayout: Int get() = AppConfig.bookshelfLayout
    override val bookshelfCoverHeight: Int get() = AppConfig.bookshelfCoverHeight
    override val bookshelfGridWidth: Int get() = AppConfig.bookshelfGridWidth
    override val showUnread: Boolean get() = AppConfig.showUnread
    override val bookshelfListShowKind: Boolean get() = AppConfig.bookshelfListShowKind
    override val bookshelfListShowIntro: Boolean get() = AppConfig.bookshelfListShowIntro
    override val bookshelfListIntroLines: Int get() = AppConfig.bookshelfListIntroLines
    override val bookshelfShowGroupCount: Boolean get() = AppConfig.bookshelfShowGroupCount
    override val bookshelfFixedWidthMode: Boolean get() = AppConfig.bookshelfFixedWidthMode
    override val showLastUpdateTime: Boolean get() = AppConfig.showLastUpdateTime
    override val saveTabPosition: Int get() = AppConfig.saveTabPosition
    // AppConfig.bookExportFileName 类型 String? (stringPref 默认 null), 接口要求非空, 用 ?: "" 兜底
    override val bookExportFileName: String get() = AppConfig.bookExportFileName ?: ""
    override val episodeExportFileName: String get() = AppConfig.episodeExportFileName ?: ""
    override val bookGroupStyle: Int get() = AppConfig.bookGroupStyle
    override val autoRefreshBook: Boolean get() = AppConfig.autoRefreshBook
    override val preDownloadNum: Int get() = AppConfig.preDownloadNum

    // ---- 换源业务 ----
    override var changeSourceCheckAuthor: Boolean
        get() = AppConfig.changeSourceCheckAuthor
        set(value) {
            AppConfig.changeSourceCheckAuthor = value
        }
    override var changeSourceLoadInfo: Boolean
        get() = AppConfig.changeSourceLoadInfo
        set(value) {
            AppConfig.changeSourceLoadInfo = value
        }
    override var changeSourceLoadToc: Boolean
        get() = AppConfig.changeSourceLoadToc
        set(value) {
            AppConfig.changeSourceLoadToc = value
        }
    override var changeSourceLoadWordCount: Boolean
        get() = AppConfig.changeSourceLoadWordCount
        set(value) {
            AppConfig.changeSourceLoadWordCount = value
        }

    // ---- 搜索业务 ----
    // var: 接口要求 var (SearchScope.save() 写回), actual 用 var + setter 写回 AppConfig
    override var searchScope: String
        get() = AppConfig.searchScope
        set(value) { AppConfig.searchScope = value }
    override var searchGroup: String
        get() = AppConfig.searchGroup
        set(value) { AppConfig.searchGroup = value }
    override val searchLayout: Int get() = AppConfig.searchLayout
    override val precisionSearch: Boolean get() = AppConfig.precisionSearch
    // 持久化 precisionSearch: 原 app 端 SearchActivity.togglePrecisionSearch 直接写 AppConfig,
    // UI 下沉 shared 后由 shared SearchViewModel 调此方法写回。
    override fun setPrecisionSearch(value: Boolean) {
        AppConfig.precisionSearch = value
    }

    // ---- 缓存业务 ----
    override val exportCharset: String get() = AppConfig.exportCharset

    // ---- WebDav ----
    // AppConfig.webDav* 类型 String? (stringPref 默认 null), 接口要求非空, 用 ?: "" 兜底
    override val webDavUrl: String get() = AppConfig.webDavUrl ?: ""
    override val webDavAccount: String get() = AppConfig.webDavAccount ?: ""
    override val webDavPassword: String get() = AppConfig.webDavPassword ?: ""
    override val syncBookProgress: Boolean get() = AppConfig.syncBookProgress
    // AppConfig.webDavDir 默认 "legado", webDavDeviceName 默认 Build.MODEL; 下沉 BackupConfigScreen 仅取 ?: "legado"/"" 兜底
    override val webDavDir: String get() = AppConfig.webDavDir ?: "legado"
    override val webDavDeviceName: String get() = AppConfig.webDavDeviceName ?: ""

    // ---- 朗读业务 ----
    override val ttsEngine: String get() = AppConfig.ttsEngine ?: ""
    override val audioPlayUseWakeLock: Boolean get() = AppConfig.audioPlayUseWakeLock
    override fun setAudioPlayUseWakeLock(value: Boolean) {
        AppConfig.audioPlayUseWakeLock = value
    }

    override val showAddToShelfAlert: Boolean get() = AppConfig.showAddToShelfAlert
    override val ttsSpeechRate: Int get() = AppConfig.ttsSpeechRate
    override val ttsTimer: Int get() = AppConfig.ttsTimer

    // ---- 主题 ----
    // AppConfig.themeMode 类型 String? (cachedStringPref), 接口要求非空, 用 ?: "0" 兜底
    override val themeMode: String get() = AppConfig.themeMode ?: "0"
    override val isNightTheme: Boolean get() = AppConfig.isNightTheme
    override val isEInkMode: Boolean get() = AppConfig.isEInkMode
    override val useDefaultCover: Boolean get() = AppConfig.useDefaultCover
    override val coverDrawBookName: Boolean
        get() = if (AppConfig.isNightTheme) AppConfig.coverShowNameN else AppConfig.coverShowName
    override val coverDrawBookAuthor: Boolean
        get() = if (AppConfig.isNightTheme) AppConfig.coverShowAuthorN else AppConfig.coverShowAuthor

    // ---- 底栏配置 (桌面端侧栏竖版复用, 底栏高度视为侧栏宽度) ----
    override val bottomBarHeight: Int get() = AppConfig.bottomBarHeight
    override val bottomBarIconSize: Int get() = AppConfig.bottomBarIconSize
    override val bottomBarLabelMode: Int get() = AppConfig.bottomBarLabelMode
    override val showHome: Boolean get() = AppConfig.showHome
    override val showDiscovery: Boolean get() = AppConfig.showDiscovery
    // AppConfig.bottomNavItemOrder 类型 String? (stringPref), 接口要求非空, 用 ?: "" 兜底
    override val bottomNavItemOrder: String get() = AppConfig.bottomNavItemOrder ?: ""
    // AppConfig.defaultHomePage 类型 String? (stringPref 默认 "bookshelf"), 兜底同默认值
    override val defaultHomePage: String get() = AppConfig.defaultHomePage ?: "bookshelf"

    // ---- 导入业务 (ImportBookSourceViewModelShared / ImportBookViewModelShared 用) ----
    override var importKeepName: Boolean
        get() = AppConfig.importKeepName
        set(value) {
            AppConfig.importKeepName = value
        }
    override var importKeepGroup: Boolean
        get() = AppConfig.importKeepGroup
        set(value) {
            AppConfig.importKeepGroup = value
        }
    override var importKeepEnable: Boolean
        get() = AppConfig.importKeepEnable
        set(value) {
            AppConfig.importKeepEnable = value
        }
    override val localBookImportSort: Int get() = AppConfig.localBookImportSort

    // ---- 远程服务 / 批量管理 ----
    override val remoteServerId: Long get() = AppConfig.remoteServerId
    override val batchChangeSourceDelay: Int get() = AppConfig.batchChangeSourceDelay

    // ---- Web 服务 / 其他 ----
    override val webPort: Int get() = AppConfig.webPort
    override val bitmapCacheSize: Int get() = AppConfig.bitmapCacheSize
    override val sourceEditMaxLine: Int get() = AppConfig.sourceEditMaxLine
    override val welcomeShowTime: Int get() = AppConfig.welcomeShowTime

    // 书籍详情页横向布局开关, BookInfoRoute 计算 useDevFeat 用
    override val bookInfoHorizontalLayout: Boolean get() = AppConfig.bookInfoHorizontalLayout

    // ---- ContentProcessorAccessor ----
    override fun getTitleReplaceRules(
        book: io.legado.app.data.entities.Book
    ): List<io.legado.app.data.entities.ReplaceRule> =
        ContentProcessor.get(book).getTitleReplaceRules()

    // SearchContentViewModelShared 用: 走完整正文处理 (替换规则/简繁/重排段/去重复标题)
    override fun getContent(
        book: io.legado.app.data.entities.Book,
        chapter: io.legado.app.data.entities.BookChapter,
        content: String,
        useReplace: Boolean
    ): CharSequence = ContentProcessor.get(book).getContent(
        book, chapter, content, useReplace = useReplace
    ).toString()

    // BookController.getBookContent web API 下沉用: 显式传 includeTitle (原 app 端 includeTitle = false)
    override fun getContent(
        book: io.legado.app.data.entities.Book,
        chapter: io.legado.app.data.entities.BookChapter,
        content: String,
        includeTitle: Boolean,
        useReplace: Boolean
    ): CharSequence = ContentProcessor.get(book).getContent(
        book, chapter, content, includeTitle = includeTitle, useReplace = useReplace
    ).toString()

    // 阅读排版主链用: 保留 BookContent.textList / sameTitleRemoved / effectiveReplaceRules，
    // 避免 shared 提前压平正文后再 split/trim/filter 改变原版排版输入语义。
    override fun getBookContent(
        book: io.legado.app.data.entities.Book,
        chapter: io.legado.app.data.entities.BookChapter,
        content: String,
        includeTitle: Boolean,
        useReplace: Boolean,
        chineseConvert: Boolean,
        reSegment: Boolean,
    ): io.legado.app.help.book.BookContent = ContentProcessor.get(book).getContent(
        book = book,
        chapter = chapter,
        content = content,
        includeTitle = includeTitle,
        useReplace = useReplace,
        chineseConvert = chineseConvert,
        reSegment = reSegment,
    )

    // ImportBookSourceViewModelShared 用: 刷新所有缓存 ContentProcessor 的替换规则
    override fun upReplaceRules() = ContentProcessor.upReplaceRules()

    // ---- BookHelpAccessor ----
    override fun saveContent(
        bookSource: io.legado.app.data.entities.BookSource,
        book: io.legado.app.data.entities.Book,
        bookChapter: io.legado.app.data.entities.BookChapter,
        content: String
    ) {
        BookHelp.saveContent(bookSource, book, bookChapter, content)
    }

    // CacheBookShared 下沉新增: 漫画/插画图片保存 (app 端真实实现, 桌面端默认 no-op)
    override suspend fun saveImages(
        bookSource: io.legado.app.data.entities.BookSource,
        book: io.legado.app.data.entities.Book,
        bookChapter: io.legado.app.data.entities.BookChapter,
        content: String,
        concurrency: Int
    ) {
        BookHelp.saveImages(bookSource, book, bookChapter, content, concurrency)
    }

    // CacheBookShared 下沉新增: 图片完整性检测 (app 端真实实现, 桌面端默认 false)
    override fun hasImageContent(
        book: io.legado.app.data.entities.Book,
        bookChapter: io.legado.app.data.entities.BookChapter
    ): Boolean = BookHelp.hasImageContent(book, bookChapter)

    // CacheBookShared 下沉新增: 读取已缓存正文 (委托 BookHelp.getContent, 与 BookStorage 等价)
    override fun getContent(
        book: io.legado.app.data.entities.Book,
        bookChapter: io.legado.app.data.entities.BookChapter
    ): String? = BookHelp.getContent(book, bookChapter)

    // CbzFile 下沉新增: 书籍缓存目录路径 (委托 BookHelp.cachePath, 与 BookStorage.rootPath 等价)
    override val cachePath: String
        get() = BookHelp.cachePath

    // CbzFile 下沉新增: 封面文件路径 (委托 FileBook.getCoverPath)
    override fun getCoverPath(bookUrl: String): String =
        io.legado.app.model.fileBook.FileBook.getCoverPath(bookUrl)

    // BookHelpShared 下沉新增: 平台专属临时文件清理 (委托 BookHelp.clearCacheExtra)
    override suspend fun clearCacheExtra() {
        BookHelp.clearCacheExtra()
    }

    // ---- IntentDataAccessor ----
    override fun setSource(source: Any?) {
        IntentData.source = source
    }

    // ---- RegexReplacer ----
    override fun replace(
        source: CharSequence,
        regex: Regex,
        replacement: String,
        timeout: Long
    ): String = source.replace(regex, replacement, timeout)

    // ---- OkHttpClientProvider ----
    override val okHttpClient get() = io.legado.app.help.http.okHttpClient

    // ---- SourceHelpAccessor ----
    // 桥接 ReadBook/AudioPlay/SourceConfig/AppCacheManager, 供 shared SourceHelp 走 provider 间接调用
    override fun getCachedReadingBookSource(key: String?): BookSource? =
        ReadBook.bookSource?.takeIf { it.bookSourceUrl == key }

    override fun getCachedAudioBookSource(key: String?): BookSource? =
        AudioPlay.bookSource?.takeIf { it.bookSourceUrl == key }

    override fun onBookSourceDeleted(key: String) {
        SourceConfig.removeSource(key)
        AppCacheManager.clearSourceVariables()
    }

    override fun onBookSourcesDeleted(keys: List<String>) {
        SourceConfig.removeSources(keys)
        AppCacheManager.clearSourceVariables()
    }

    // ---- ThemeConfigProvider ----
    // 桥接 ThemeConfig 单例, 供 ImportThemeViewModelShared 走 provider 间接调用。
    // ThemeConfig 依赖 SharedPreferences + File + Context, 留 app 端。
    override fun getConfigList(): List<ThemeConfigData> =
        ThemeConfig.configList.map { it.toThemeConfigData() }

    override fun addConfig(config: ThemeConfigData) {
        ThemeConfig.addConfig(config.toThemeConfig())
    }

    override fun delConfig(index: Int) {
        ThemeConfig.delConfig(index)
    }

    /** 包装 ThemeConfig.applyBuiltin (清 pref + applyDayNight 含 postEvent) */
    override fun applyBuiltin(isNight: Boolean) {
        ThemeConfig.applyBuiltin(appCtx, isNight)
    }

    /** 包装 ThemeConfig.applyConfig (applyConfigToPrefs + applyDayNight 含 postEvent) */
    override fun applyConfig(config: ThemeConfigData) {
        ThemeConfig.applyConfig(appCtx, config.toThemeConfig())
    }

    /** 包装 ThemeConfig.getBuiltinConfigs (默认日间 + 默认夜间, isBuiltin=true) */
    override fun getBuiltinConfigs(): List<ThemeConfigData> =
        ThemeConfig.getBuiltinConfigs(appCtx).map { it.toThemeConfigData() }

    /** 包装 ThemeConfig.save (configList → themeConfig.json) */
    override fun save() {
        ThemeConfig.save()
    }

    /** ThemeConfig.Config → ThemeConfigData (字段一一对应, 含 isBuiltin 运行时标记)。 */
    private fun ThemeConfig.Config.toThemeConfigData(): ThemeConfigData =
        ThemeConfigData(
            themeName = themeName,
            isNightTheme = isNightTheme,
            primaryColor = primaryColor,
            accentColor = accentColor,
            backgroundColor = backgroundColor,
            bottomBackground = bottomBackground,
        ).also { it.isBuiltin = isBuiltin }

    /** ThemeConfigData → ThemeConfig.Config (字段一一对应, 含 isBuiltin 运行时标记)。 */
    private fun ThemeConfigData.toThemeConfig(): ThemeConfig.Config =
        ThemeConfig.Config(
            themeName = themeName,
            isNightTheme = isNightTheme,
            primaryColor = primaryColor,
            accentColor = accentColor,
            backgroundColor = backgroundColor,
            bottomBackground = bottomBackground,
        ).also { it.isBuiltin = isBuiltin }
}

/**
 * 安卓宿主启动早期注册所有 webBook 编排层 provider。
 *
 * 调用时机: App.onCreate, 在 BookInfoRefreshers 注册之后 (WebBook 主体已下沉
 * shared, 经 BookInfoRefresherImpl 反向调用, 无直接依赖)。
 */
fun registerAndroidWebBookProviders() {
    val impl = WebBookProvidersImpl
    // 存储路径集中 provider: 须早于任何 BookStorage.rootPath / 封面目录访问
    DataStorageProviders.register(AndroidDataStorage)
    AppDbProviders.register(impl)
    AppConfigProviders.register(impl)
    ContentProcessorProviders.register(impl)
    BookHelpProviders.register(impl)
    IntentDataProviders.register(impl)
    RegexReplacers.register(impl)
    OkHttpClientProviders.register(impl)
    // CbzFile 下沉新增: 注册 BitmapProvider (封面提取 BitmapFactory) + ZipFileWrapperFactory (zip 工厂)
    BitmapProviders.register(BitmapProviderImpl)
    ZipFileWrapperFactoryProviders.register(ZipFileWrapperFactoryImpl)
    // SourceHelp 下沉新增: 注册 SourceHelpAccessor (桥接 ReadBook/AudioPlay/SourceConfig/AppCacheManager)
    SourceHelpAccessors.register(impl)
    // ImportThemeViewModelShared 下沉新增: 注册 ThemeConfigProvider (桥接 ThemeConfig.configList 读 + addConfig 写)
    ThemeConfigProviders.register(impl)
    // KP1.2: 注册 AppDatabase 单例 provider (app 端委托 appDb lazy 单例)
    AppDatabaseProviders.register(AndroidAppDatabaseProvider)
    // SourceVerificationHelp 下沉新增: 注册 VerificationUiProvider (桥接 VerificationCodeDialog/WebViewActivity)
    registerAndroidVerificationUiProvider()
    // BookController 下沉新增: 注册 ImageControllerProvider (桥接 Glide/ImageProvider, 供 BookController.getCover/getImg)
    ImageControllerProviders.register(BookControllerImageProviderImpl)
    // BookController 下沉新增: 注册 ReadBookStateProvider (桥接 ReadBook 单例, 供 BookController.deleteBook/saveBookProgress)
    ReadBookStateProviders.register(ReadBookStateProviderImpl)
}
