package io.legado.app.help.config

import io.legado.app.constant.PreferKey

/**
 * nativeMain: [AppConfigAccessor] 的 iOS / 鸿蒙 两端共用实现。
 *
 * 详见 [AppConfigAccessor] 接口注释。Android 端在 app 模块用
 * `AppConfigAccessorImpl` 包装 `AppConfig` (SharedPreferences-backed),
 * iOS / 鸿蒙端无 SharedPreferences, 用 [PreferenceProvider] 委托:
 * - 数值类配置走 [PreferenceProvider] (iOS 端 NSUserDefaults 真实持久化 / 鸿蒙端文件持久化,
 *   由各端 [IosPreferenceProvider] / [OhosPreferenceProvider] 提供)
 * - 复杂计算属性 (isNightTheme/isEInkMode) 基于 themeMode 计算
 *
 * 两端实现逻辑完全一致 (仅类名 Ios/Ohos 前缀与注释平台描述不同), 下沉到 nativeMain 共用。
 *
 * 存储 key 一律引用 [PreferKey] 常量: 部分 key 的字面量与属性名不同
 * (如 ttsEngine="appTtsEngine"、webDavUrl="web_dav_url"), 裸字符串会与 Android 端错位。
 *
 * 注册入口 (iOS/鸿蒙共用): [registerNativeAppConfigAccessor]
 *
 * 前置依赖: PreferenceProvider 需先注册 (AppConfigAccessor 委托 PreferenceProvider)。
 *
 * 模式参考桌面端 `DesktopAppConfigAccessorImpl`。
 */
class NativeAppConfigAccessor(
    private val prefs: PreferenceProvider = PreferenceProviders.get(),
) : AppConfigAccessor {

    // ---- 并发 / 目录 ----
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

    override val autoRefreshBook: Boolean
        get() = prefs.getBoolean(PreferKey.autoRefresh, false)

    override val preDownloadNum: Int
        get() = prefs.getInt(PreferKey.preDownloadNum, 10)

    // ---- 搜索业务 ----
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

    // webDavDeviceName 默认空串 (app 端默认 Build.MODEL, iOS/鸿蒙端暂用空串,
    // 后续可替换为 UIDevice.name / ohos deviceInfo)
    override val webDavDeviceName: String
        get() = prefs.getString(PreferKey.webDavDeviceName, "")

    // ---- 朗读业务 ----
    override val ttsEngine: String
        get() = prefs.getString(PreferKey.ttsEngine, "")

    override val ttsSpeechRate: Int
        get() = prefs.getInt(PreferKey.ttsSpeechRate, 5)

    override val ttsTimer: Int
        get() = prefs.getInt(PreferKey.ttsTimer, 0)

    // ---- 主题 ----
    override val themeMode: String
        get() = prefs.getString(PreferKey.themeMode, "0")

    override val isNightTheme: Boolean
        get() = themeMode == "2"

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

/**
 * 注册 [NativeAppConfigAccessor] 到 [AppConfigProviders] (iOS/鸿蒙共用)。
 *
 * 前置依赖: [PreferenceProviders] 已注册 (AppConfigAccessor 委托 PreferenceProvider)。
 */
fun registerNativeAppConfigAccessor() {
    AppConfigProviders.register(NativeAppConfigAccessor())
}
