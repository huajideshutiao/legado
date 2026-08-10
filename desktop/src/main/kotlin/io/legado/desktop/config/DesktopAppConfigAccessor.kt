package io.legado.desktop.config

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigAccessor
import io.legado.app.help.config.AppConfigRanges
import io.legado.app.help.config.CachedPrefValue
import io.legado.app.help.config.PreferenceProviders

/**
 * [AppConfigAccessor] 桌面端实现。
 *
 * 从 [PreferenceProviders.get()] 读只读配置项, 默认值与 app 端 [io.legado.app.help.config.AppConfig]
 * 保持一致:
 * - threadCount = 16
 * - tocCountWords = true
 * - chineseConverterType = 0 (不转换)
 * - replaceEnableDefault = true
 * - enableReadRecord = true
 * - 其余书架/搜索/缓存/WebDav/朗读/主题字段默认值见各 getter 注释
 *
 * 热路径字段 (书架/侧栏/封面/主题) 用 [CachedPrefValue] 内存缓存, 对齐原版 AppConfig
 * cachedBoolPref/cachedIntPref 语义: 组合期读内存字段, pref 变更经
 * [io.legado.app.help.config.PreferenceProvider.addPreferenceChangeListener] 刷新。
 * 冷路径字段 (WebDav/TTS/导入等) 仍每次直读 (低频, 无需缓存)。
 *
 * 注册到 [io.legado.app.help.config.AppConfigProviders] 由 [registerDesktopConfig] 完成。
 *
 * 注: isNightTheme / isEInkMode 在 app 端基于 themeMode 计算 (isNightTheme 的 else
 * 分支会回退到 Android 系统夜间模式), 桌面端 else 分支用系统深色模式检测:
 * Windows 读注册表 `HKCU\...\Themes\Personalize\AppsUseLightTheme` (JNA Advapi32Util,
 * 项目已有 JNA 依赖), 其他平台无标准 API 回退 false (相当于日间), 与 themeMode 默认
 * "0" 跟随系统的语义对齐。
 */
class DesktopAppConfigAccessor : AppConfigAccessor {

    private val prefs = PreferenceProviders.get()

    // ---- 热路径字段缓存 (声明顺序即初始化顺序: themeMode 必须先于依赖它的字段) ----
    private val themeModeCache = CachedPrefValue(prefs) { it.getString(PreferKey.themeMode, "0") }
    private val useDefaultCoverCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.useDefaultCover, false) }
    private val loadCoverOnlyWifiCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.loadCoverOnlyWifi, false) }
    private val coverShowNameCache = CachedPrefValue(prefs) {
        it.getBoolean(
            if (isNightTheme) PreferKey.coverShowNameN else PreferKey.coverShowName, true
        )
    }
    private val coverShowAuthorCache = CachedPrefValue(prefs) {
        it.getBoolean(
            if (isNightTheme) PreferKey.coverShowAuthorN else PreferKey.coverShowAuthor, true
        )
    }
    private val bookshelfSortCache =
        CachedPrefValue(prefs) { it.getInt(PreferKey.bookshelfSort, 0) }
    private val bookshelfLayoutCache =
        CachedPrefValue(prefs) { it.getInt(PreferKey.bookshelfLayout, 0) }
    private val bookshelfCoverHeightCache = CachedPrefValue(prefs) {
        it.getInt(PreferKey.bookshelfCoverHeight, 120)
            .coerceIn(AppConfigRanges.bookshelfCoverHeight)
    }
    private val bookshelfGridWidthCache =
        CachedPrefValue(prefs) { it.getInt(PreferKey.bookshelfGridWidth, 120) }
    private val showUnreadCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.showUnread, true) }
    private val showBookshelfFastScrollerCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.showBookshelfFastScroller, true) }
    private val bookshelfListShowKindCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.bookshelfListShowKind, false) }
    private val bookshelfListShowIntroCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.bookshelfListShowIntro, false) }
    private val bookshelfListIntroLinesCache = CachedPrefValue(prefs) {
        it.getInt(PreferKey.bookshelfListIntroLines, 2)
            .coerceIn(AppConfigRanges.bookshelfListIntroLines)
    }
    private val bookshelfShowGroupCountCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.bookshelfShowGroupCount, true) }
    private val bookshelfFixedWidthModeCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.bookshelfFixedWidthMode, false) }
    private val showLastUpdateTimeCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.showLastUpdateTime, false) }
    private val saveTabPositionCache =
        CachedPrefValue(prefs) { it.getInt(PreferKey.saveTabPosition, 0) }
    private val bookGroupStyleCache =
        CachedPrefValue(prefs) { it.getInt(PreferKey.bookGroupStyle, 0) }
    private val bottomBarHeightCache = CachedPrefValue(prefs) {
        it.getInt(PreferKey.bottomBarHeight, 50)
            .coerceIn(AppConfigRanges.bottomBarHeight)
    }
    private val bottomBarIconSizeCache = CachedPrefValue(prefs) {
        it.getInt(PreferKey.bottomBarIconSize, 24)
            .coerceIn(AppConfigRanges.bottomBarIconSize)
    }
    private val bottomBarLabelModeCache = CachedPrefValue(prefs) {
        it.getInt(PreferKey.bottomBarLabelMode, 0)
            .coerceIn(AppConfigRanges.bottomBarLabelMode)
    }
    private val showHomeCache = CachedPrefValue(prefs) { it.getBoolean(PreferKey.showHome, true) }
    private val showDiscoveryCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.showDiscovery, true) }
    private val bottomNavItemOrderCache =
        CachedPrefValue(prefs) { it.getString(PreferKey.bottomNavItemOrder, "") }
    private val defaultHomePageCache =
        CachedPrefValue(prefs) { it.getString(PreferKey.defaultHomePage, "bookshelf") }
    private val searchLayoutCache = CachedPrefValue(prefs) { it.getInt(PreferKey.searchLayout, 1) }
    private val precisionSearchCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.precisionSearch, false) }
    private val devFeatCache = CachedPrefValue(prefs) { it.getBoolean(PreferKey.devFeat, false) }
    private val bookInfoHorizontalLayoutCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.bookInfoHorizontalLayout, false) }
    private val showAddToShelfAlertCache =
        CachedPrefValue(prefs) { it.getBoolean(PreferKey.showAddToShelfAlert, true) }

    // 变更监听: 刷新全部缓存字段 (变更只发生在用户改设置, 全量重读开销可忽略)
    init {
        prefs.addPreferenceChangeListener { refreshCached() }
    }

    private fun refreshCached() {
        themeModeCache.refresh(prefs)
        useDefaultCoverCache.refresh(prefs)
        loadCoverOnlyWifiCache.refresh(prefs)
        coverShowNameCache.refresh(prefs)
        coverShowAuthorCache.refresh(prefs)
        bookshelfSortCache.refresh(prefs)
        bookshelfLayoutCache.refresh(prefs)
        bookshelfCoverHeightCache.refresh(prefs)
        bookshelfGridWidthCache.refresh(prefs)
        showUnreadCache.refresh(prefs)
        showBookshelfFastScrollerCache.refresh(prefs)
        bookshelfListShowKindCache.refresh(prefs)
        bookshelfListShowIntroCache.refresh(prefs)
        bookshelfListIntroLinesCache.refresh(prefs)
        bookshelfShowGroupCountCache.refresh(prefs)
        bookshelfFixedWidthModeCache.refresh(prefs)
        showLastUpdateTimeCache.refresh(prefs)
        saveTabPositionCache.refresh(prefs)
        bookGroupStyleCache.refresh(prefs)
        bottomBarHeightCache.refresh(prefs)
        bottomBarIconSizeCache.refresh(prefs)
        bottomBarLabelModeCache.refresh(prefs)
        showHomeCache.refresh(prefs)
        showDiscoveryCache.refresh(prefs)
        bottomNavItemOrderCache.refresh(prefs)
        defaultHomePageCache.refresh(prefs)
        searchLayoutCache.refresh(prefs)
        precisionSearchCache.refresh(prefs)
        devFeatCache.refresh(prefs)
        bookInfoHorizontalLayoutCache.refresh(prefs)
        showAddToShelfAlertCache.refresh(prefs)
    }

    override val threadCount: Int
        get() = prefs.getInt(PreferKey.threadCount, 16)

    override val tocCountWords: Boolean
        get() = prefs.getBoolean(PreferKey.tocCountWords, true)

    override fun setTocCountWords(value: Boolean) {
        prefs.putBoolean(PreferKey.tocCountWords, value)
    }

    override val tocUiUseReplace: Boolean
        get() = prefs.getBoolean(PreferKey.tocUiUseReplace, false)

    override fun setTocUiUseReplace(value: Boolean) {
        prefs.putBoolean(PreferKey.tocUiUseReplace, value)
    }

    override var chineseConverterType: Int
        get() = prefs.getInt(PreferKey.chineseConverterType, 0)
        set(value) = prefs.putInt(PreferKey.chineseConverterType, value)

    override val replaceEnableDefault: Boolean
        get() = prefs.getBoolean(PreferKey.replaceEnableDefault, true)

    override val enableReadRecord: Boolean
        get() = prefs.getBoolean(PreferKey.enableReadRecord, true)

    // ---- 书架业务 (热路径, 走缓存) ----
    override val bookshelfSort: Int
        get() = bookshelfSortCache.get()

    override val bookshelfLayout: Int
        get() = bookshelfLayoutCache.get()

    override val bookshelfCoverHeight: Int
        get() = bookshelfCoverHeightCache.get()

    override val bookshelfGridWidth: Int
        get() = bookshelfGridWidthCache.get()

    override val showUnread: Boolean
        get() = showUnreadCache.get()

    override val showBookshelfFastScroller: Boolean
        get() = showBookshelfFastScrollerCache.get()

    override val bookshelfListShowKind: Boolean
        get() = bookshelfListShowKindCache.get()

    override val bookshelfListShowIntro: Boolean
        get() = bookshelfListShowIntroCache.get()

    override val bookshelfListIntroLines: Int
        get() = bookshelfListIntroLinesCache.get()

    override val bookshelfShowGroupCount: Boolean
        get() = bookshelfShowGroupCountCache.get()

    override val bookshelfFixedWidthMode: Boolean
        get() = bookshelfFixedWidthModeCache.get()

    override val showLastUpdateTime: Boolean
        get() = showLastUpdateTimeCache.get()

    override val saveTabPosition: Int
        get() = saveTabPositionCache.get()

    override val bookExportFileName: String
        get() = prefs.getString(PreferKey.bookExportFileName, "")

    override val episodeExportFileName: String
        get() = prefs.getString(PreferKey.episodeExportFileName, "")

    override val bookGroupStyle: Int
        get() = bookGroupStyleCache.get()

    // autoRefreshBook 在 AppConfig 中用 PreferKey.autoRefresh 作为 key
    override val autoRefreshBook: Boolean
        get() = prefs.getBoolean(PreferKey.autoRefresh, false)

    override val preDownloadNum: Int
        get() = prefs.getInt(PreferKey.preDownloadNum, 10)

    // ---- 换源业务 ----
    override var changeSourceCheckAuthor: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceCheckAuthor, true)
        set(value) = prefs.putBoolean(PreferKey.changeSourceCheckAuthor, value)

    override var changeSourceLoadInfo: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceLoadInfo, false)
        set(value) = prefs.putBoolean(PreferKey.changeSourceLoadInfo, value)

    override var changeSourceLoadToc: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceLoadToc, false)
        set(value) = prefs.putBoolean(PreferKey.changeSourceLoadToc, value)

    override var changeSourceLoadWordCount: Boolean
        get() = prefs.getBoolean(PreferKey.changeSourceLoadWordCount, false)
        set(value) = prefs.putBoolean(PreferKey.changeSourceLoadWordCount, value)

    // ---- 搜索业务 ----
    // var: 接口要求 var (SearchScope.save() 写回), actual 用 var + setter 写回 prefs
    override var searchScope: String
        get() = prefs.getString(PreferKey.searchScope, "")
        set(value) = prefs.putString(PreferKey.searchScope, value)

    override var searchGroup: String
        get() = prefs.getString(PreferKey.searchGroup, "")
        set(value) = prefs.putString(PreferKey.searchGroup, value)

    override val searchLayout: Int
        get() = searchLayoutCache.get()

    override val precisionSearch: Boolean
        get() = precisionSearchCache.get()

    override fun setPrecisionSearch(value: Boolean) {
        prefs.putBoolean(PreferKey.precisionSearch, value)
    }

    // ---- 缓存业务 ----
    // exportCharset 在 AppConfig 中自定义 getter, pref 为空时返回 "UTF-8"
    override val exportCharset: String
        get() = prefs.getString(PreferKey.exportCharset, "UTF-8")

    // ---- WebDav ----
    override val webDavUrl: String
        get() = prefs.getString(PreferKey.webDavUrl, "")

    override val webDavAccount: String
        get() = prefs.getString(PreferKey.webDavAccount, "")

    override val webDavPassword: String
        get() = prefs.getString(PreferKey.webDavPassword, "")

    override val syncBookProgress: Boolean
        get() = prefs.getBoolean(PreferKey.syncBookProgress, true)

    // webDavDir 默认 "legado" (与 app 端 AppConfig.webDavDir stringPref 默认值对齐)
    override val webDavDir: String
        get() = prefs.getString(PreferKey.webDavDir, "legado")

    // webDavDeviceName 默认空串 (app 端默认 Build.MODEL, 桌面端无 Build.MODEL, 用空串)
    override val webDavDeviceName: String
        get() = prefs.getString(PreferKey.webDavDeviceName, "")

    // ---- 朗读业务 ----
    // ttsSpeechRate 默认值 = AppConfig.defaultSpeechRate = 5
    override val ttsEngine: String
        get() = prefs.getString(PreferKey.ttsEngine, "")

    override val ttsSpeechRate: Int
        get() = prefs.getInt(PreferKey.ttsSpeechRate, 5)

    override val ttsTimer: Int
        get() = prefs.getInt(PreferKey.ttsTimer, 0)

    // ---- 主题 (热路径, 走缓存) ----
    override val themeMode: String
        get() = themeModeCache.get()

    // 与 AppConfig.isNightTheme 对齐: "1"=false, "2"=true, "3"=false, else=系统夜间模式
    override val isNightTheme: Boolean
        get() = when (themeModeCache.get()) {
            "1" -> false
            "2" -> true
            "3" -> false
            else -> systemNightMode()
        }

    override val isEInkMode: Boolean
        get() = themeModeCache.get() == "3"

    override val useDefaultCover: Boolean
        get() = useDefaultCoverCache.get()
    override val loadCoverOnlyWifi: Boolean
        get() = loadCoverOnlyWifiCache.get()
    override val coverDrawBookName: Boolean
        get() = coverShowNameCache.get()
    override val coverDrawBookAuthor: Boolean
        get() = coverShowAuthorCache.get()

    // ---- 底栏配置 (桌面端侧栏竖版复用, 底栏高度视为侧栏宽度; 热路径, 走缓存) ----
    // 默认值与 app 端 AppConfig.BOTTOM_BAR_* 常量一致
    override val bottomBarHeight: Int
        get() = bottomBarHeightCache.get()

    override val bottomBarIconSize: Int
        get() = bottomBarIconSizeCache.get()

    override val bottomBarLabelMode: Int
        get() = bottomBarLabelModeCache.get()

    override val showHome: Boolean
        get() = showHomeCache.get()

    override val showDiscovery: Boolean
        get() = showDiscoveryCache.get()

    override val bottomNavItemOrder: String
        get() = bottomNavItemOrderCache.get()

    override val defaultHomePage: String
        get() = defaultHomePageCache.get()

    // ---- 导入业务 (ImportBookSourceViewModelShared / ImportBookViewModelShared 用) ----
    override var importKeepName: Boolean
        get() = prefs.getBoolean(PreferKey.importKeepName, false)
        set(value) = prefs.putBoolean(PreferKey.importKeepName, value)

    override var importKeepGroup: Boolean
        get() = prefs.getBoolean(PreferKey.importKeepGroup, false)
        set(value) = prefs.putBoolean(PreferKey.importKeepGroup, value)

    override var importKeepEnable: Boolean
        get() = prefs.getBoolean(PreferKey.importKeepEnable, false)
        set(value) = prefs.putBoolean(PreferKey.importKeepEnable, value)

    override val localBookImportSort: Int
        get() = prefs.getInt(PreferKey.localBookImportSort, 0)

    // ---- 远程服务 / 批量管理 ----
    override val remoteServerId: Long
        get() = prefs.getLong(PreferKey.remoteServerId, 0L)

    override val batchChangeSourceDelay: Int
        get() = prefs.getInt(PreferKey.batchChangeSourceDelay, 0)

    // ---- Web 服务 / 其他 ----
    override val webPort: Int
        get() = prefs.getInt(PreferKey.webPort, 1122)

    override val bitmapCacheSize: Int
        get() = prefs.getInt(PreferKey.bitmapCacheSize, 50)

    // 与 app 端 AppConfig.sourceEditMaxLine 语义一致: <10 视为不限制
    override val sourceEditMaxLine: Int
        get() {
            val maxLine = prefs.getInt(PreferKey.sourceEditMaxLine, Int.MAX_VALUE)
            return if (maxLine < 10) Int.MAX_VALUE else maxLine
        }

    override val welcomeShowTime: Int
        get() = prefs.getInt(PreferKey.welcomeShowTime, 600)
            .coerceIn(AppConfigRanges.welcomeShowTime)

    // ---- 系统深色模式检测 (themeMode="0" 跟随系统时用) ----

    /** 系统深色模式缓存时间戳 (-1 = 未初始化), 避免热路径频繁读注册表。 */
    @Volatile
    private var sysNightCacheAt: Long = -1L

    @Volatile
    private var sysNightValue: Boolean = false

    /** 注册表读取结果缓存时长 (毫秒): 系统主题切换不频繁, 10s 足够平滑。 */
    private fun systemNightMode(): Boolean {
        val now = System.currentTimeMillis()
        if (sysNightCacheAt != -1L && now - sysNightCacheAt < SYS_NIGHT_TTL_MS) {
            return sysNightValue
        }
        val value = detectSystemNightMode()
        sysNightValue = value
        sysNightCacheAt = now
        return value
    }

    /**
     * 仅 Windows: 读 `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize`
     * 的 `AppsUseLightTheme` DWORD (0=深色, 1=浅色); 非 Windows 平台 (macOS/Linux)
     * 无等价注册表, 返回 false (与 JVM 无系统主题 API 的事实对齐, UI 回落日间属正常降级)。
     */
    private fun detectSystemNightMode(): Boolean {
        if (!com.sun.jna.Platform.isWindows()) return false
        return runCatching {
            com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(
                com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "AppsUseLightTheme",
            ) == 0
        }.getOrDefault(false)
    }

    private companion object {
        /** 系统深色模式检测缓存时长 (毫秒)。 */
        const val SYS_NIGHT_TTL_MS = 10_000L
    }

    // ---- 设置界面直写 pref 的开关 (热路径, 走缓存) ----
    // 原版 AppConfig 均为 boolPref 直读; 桌面端覆写为缓存字段, 变更由监听刷新
    override val devFeat: Boolean
        get() = devFeatCache.get()

    override val bookInfoHorizontalLayout: Boolean
        get() = bookInfoHorizontalLayoutCache.get()

    override val showAddToShelfAlert: Boolean
        get() = showAddToShelfAlertCache.get()
}
