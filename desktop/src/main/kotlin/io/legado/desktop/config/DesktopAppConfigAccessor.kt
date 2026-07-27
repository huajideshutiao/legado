package io.legado.desktop.config

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigAccessor
import io.legado.app.help.config.AppConfigRanges
import io.legado.app.help.config.PreferenceProviders

/**
 * [AppConfigAccessor] 桌面端实现（KP1.4）。
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
 * 注册到 [io.legado.app.help.config.AppConfigProviders] 由 [registerDesktopConfig] 完成。
 *
 * 注: isNightTheme / isEInkMode 在 app 端基于 themeMode 计算 (isNightTheme 的 else
 * 分支会回退到 Android 系统夜间模式), 桌面端无系统夜间模式概念, else 分支统一返回 false
 * (相当于日间), 与 themeMode 默认 "0" 跟随系统的语义对齐。
 */
class DesktopAppConfigAccessor : AppConfigAccessor {

    private val prefs get() = PreferenceProviders.get()

    override val threadCount: Int
        get() = prefs.getInt(PreferKey.threadCount, 16)

    override val tocCountWords: Boolean
        get() = prefs.getBoolean(PreferKey.tocCountWords, true)

    override val tocUiUseReplace: Boolean
        get() = prefs.getBoolean(PreferKey.tocUiUseReplace, false)

    override var chineseConverterType: Int
        get() = prefs.getInt(PreferKey.chineseConverterType, 0)
        set(value) = prefs.putInt(PreferKey.chineseConverterType, value)

    override val replaceEnableDefault: Boolean
        get() = prefs.getBoolean(PreferKey.replaceEnableDefault, true)

    override val enableReadRecord: Boolean
        get() = prefs.getBoolean(PreferKey.enableReadRecord, true)

    // ---- 书架业务 ----
    override val bookshelfSort: Int
        get() = prefs.getInt(PreferKey.bookshelfSort, 0)

    override val bookshelfLayout: Int
        get() = prefs.getInt(PreferKey.bookshelfLayout, 0)

    override val bookshelfCoverHeight: Int
        get() = prefs.getInt(PreferKey.bookshelfCoverHeight, 120)
            .coerceIn(AppConfigRanges.bookshelfCoverHeight)

    override val bookshelfGridWidth: Int
        get() = prefs.getInt(PreferKey.bookshelfGridWidth, 120)

    override val showUnread: Boolean
        get() = prefs.getBoolean(PreferKey.showUnread, true)

    override val bookshelfListShowKind: Boolean
        get() = prefs.getBoolean(PreferKey.bookshelfListShowKind, false)

    override val bookshelfListShowIntro: Boolean
        get() = prefs.getBoolean(PreferKey.bookshelfListShowIntro, false)

    override val bookshelfListIntroLines: Int
        get() = prefs.getInt(PreferKey.bookshelfListIntroLines, 2)
            .coerceIn(AppConfigRanges.bookshelfListIntroLines)

    override val bookshelfShowGroupCount: Boolean
        get() = prefs.getBoolean(PreferKey.bookshelfShowGroupCount, true)

    override val bookshelfFixedWidthMode: Boolean
        get() = prefs.getBoolean(PreferKey.bookshelfFixedWidthMode, false)

    override val showLastUpdateTime: Boolean
        get() = prefs.getBoolean(PreferKey.showLastUpdateTime, false)

    override val saveTabPosition: Int
        get() = prefs.getInt(PreferKey.saveTabPosition, 0)

    override val bookExportFileName: String
        get() = prefs.getString(PreferKey.bookExportFileName, "")

    override val episodeExportFileName: String
        get() = prefs.getString(PreferKey.episodeExportFileName, "")

    override val bookGroupStyle: Int
        get() = prefs.getInt(PreferKey.bookGroupStyle, 0)

    // autoRefreshBook 在 AppConfig 中用 PreferKey.autoRefresh 作为 key
    override val autoRefreshBook: Boolean
        get() = prefs.getBoolean(PreferKey.autoRefresh, false)

    override val preDownloadNum: Int
        get() = prefs.getInt(PreferKey.preDownloadNum, 10)

    // ---- 搜索业务 ----
    // var: 接口要求 var (SearchScope.save() 写回), actual 用 var + setter 写回 prefs
    override var searchScope: String
        get() = prefs.getString(PreferKey.searchScope, "")
        set(value) = prefs.putString(PreferKey.searchScope, value)

    override var searchGroup: String
        get() = prefs.getString(PreferKey.searchGroup, "")
        set(value) = prefs.putString(PreferKey.searchGroup, value)

    override val searchLayout: Int
        get() = prefs.getInt(PreferKey.searchLayout, 1)

    override val precisionSearch: Boolean
        get() = prefs.getBoolean(PreferKey.precisionSearch, false)

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

    // ---- 主题 ----
    override val themeMode: String
        get() = prefs.getString(PreferKey.themeMode, "0")

    // 与 AppConfig.isNightTheme 对齐: "1"=false, "2"=true, "3"=false, else=系统夜间模式
    // 桌面端无系统夜间模式概念, else 分支返回 false
    override val isNightTheme: Boolean
        get() = when (themeMode) {
            "1" -> false
            "2" -> true
            "3" -> false
            else -> false
        }

    override val isEInkMode: Boolean
        get() = themeMode == "3"

    override val useDefaultCover: Boolean
        get() = prefs.getBoolean(PreferKey.useDefaultCover, false)

    // ---- 底栏配置 (桌面端侧栏竖版复用, 底栏高度视为侧栏宽度) ----
    // 默认值与 app 端 AppConfig.BOTTOM_BAR_* 常量一致
    override val bottomBarHeight: Int
        get() = prefs.getInt(PreferKey.bottomBarHeight, 50)
            .coerceIn(AppConfigRanges.bottomBarHeight)

    override val bottomBarIconSize: Int
        get() = prefs.getInt(PreferKey.bottomBarIconSize, 24)
            .coerceIn(AppConfigRanges.bottomBarIconSize)

    override val bottomBarLabelMode: Int
        get() = prefs.getInt(PreferKey.bottomBarLabelMode, 0)
            .coerceIn(AppConfigRanges.bottomBarLabelMode)

    override val showHome: Boolean
        get() = prefs.getBoolean(PreferKey.showHome, true)

    override val showDiscovery: Boolean
        get() = prefs.getBoolean(PreferKey.showDiscovery, true)

    override val bottomNavItemOrder: String
        get() = prefs.getString(PreferKey.bottomNavItemOrder, "")

    override val defaultHomePage: String
        get() = prefs.getString(PreferKey.defaultHomePage, "bookshelf")

    // ---- 导入业务 (ImportBookSourceViewModelShared / ImportBookViewModelShared 用) ----
    override val importKeepName: Boolean
        get() = prefs.getBoolean(PreferKey.importKeepName, false)

    override val importKeepGroup: Boolean
        get() = prefs.getBoolean(PreferKey.importKeepGroup, false)

    override val importKeepEnable: Boolean
        get() = prefs.getBoolean(PreferKey.importKeepEnable, false)

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
}
