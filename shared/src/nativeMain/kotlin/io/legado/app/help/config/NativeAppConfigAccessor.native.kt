package io.legado.app.help.config

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
        get() = prefs.getInt("threadCount", 16)

    override val tocCountWords: Boolean
        get() = prefs.getBoolean("tocCountWords", false)

    override var chineseConverterType: Int
        get() = prefs.getInt("chineseConverterType", 0)
        set(value) = prefs.putInt("chineseConverterType", value)

    override val replaceEnableDefault: Boolean
        get() = prefs.getBoolean("replaceEnableDefault", true)

    override val enableReadRecord: Boolean
        get() = prefs.getBoolean("enableReadRecord", true)

    // ---- 书架业务 ----
    override val bookshelfSort: Int
        get() = prefs.getInt("bookshelfSort", 0)

    override val bookshelfLayout: Int
        get() = prefs.getInt("bookshelfLayout", 0)

    override val bookshelfCoverHeight: Int
        get() = prefs.getInt("bookshelfCoverHeight", 120).coerceIn(90, 220)

    override val bookshelfGridWidth: Int
        get() = prefs.getInt("bookshelfGridWidth", 120)

    override val showUnread: Boolean
        get() = prefs.getBoolean("showUnread", true)

    override val bookshelfListShowKind: Boolean
        get() = prefs.getBoolean("bookshelfListShowKind", false)

    override val bookshelfListShowIntro: Boolean
        get() = prefs.getBoolean("bookshelfListShowIntro", false)

    override val bookshelfListIntroLines: Int
        get() = prefs.getInt("bookshelfListIntroLines", 2).coerceIn(1, 3)

    override val bookshelfShowGroupCount: Boolean
        get() = prefs.getBoolean("bookshelfShowGroupCount", true)

    override val bookshelfFixedWidthMode: Boolean
        get() = prefs.getBoolean("bookshelfFixedWidthMode", false)

    override val showLastUpdateTime: Boolean
        get() = prefs.getBoolean("showLastUpdateTime", false)

    override val saveTabPosition: Int
        get() = prefs.getInt("saveTabPosition", 0)

    override val bookExportFileName: String
        get() = prefs.getString("bookExportFileName", "")

    override val episodeExportFileName: String
        get() = prefs.getString("episodeExportFileName", "")

    override val bookGroupStyle: Int
        get() = prefs.getInt("bookGroupStyle", 0)

    override val autoRefreshBook: Boolean
        get() = prefs.getBoolean("autoRefreshBook", false)

    override val preDownloadNum: Int
        get() = prefs.getInt("preDownloadNum", 10)

    // ---- 搜索业务 ----
    override var searchScope: String
        get() = prefs.getString("searchScope", "")
        set(value) = prefs.putString("searchScope", value)

    override var searchGroup: String
        get() = prefs.getString("searchGroup", "")
        set(value) = prefs.putString("searchGroup", value)

    override val searchLayout: Int
        get() = prefs.getInt("searchLayout", 1)

    override val precisionSearch: Boolean
        get() = prefs.getBoolean("precisionSearch", false)

    // ---- 缓存业务 ----
    override val exportCharset: String
        get() = prefs.getString("exportCharset", "UTF-8")

    // ---- WebDav ----
    override val webDavUrl: String
        get() = prefs.getString("webDavUrl", "")

    override val webDavAccount: String
        get() = prefs.getString("webDavAccount", "")

    override val webDavPassword: String
        get() = prefs.getString("webDavPassword", "")

    override val syncBookProgress: Boolean
        get() = prefs.getBoolean("syncBookProgress", true)

    // webDavDir 默认 "legado" (与 app 端 AppConfig.webDavDir stringPref 默认值对齐)
    override val webDavDir: String
        get() = prefs.getString("webDavDir", "legado")

    // webDavDeviceName 默认空串 (app 端默认 Build.MODEL, iOS/鸿蒙端暂用空串,
    // 后续可替换为 UIDevice.name / ohos deviceInfo)
    override val webDavDeviceName: String
        get() = prefs.getString("webDavDeviceName", "")

    // ---- 朗读业务 ----
    override val ttsEngine: String
        get() = prefs.getString("ttsEngine", "")

    override val ttsSpeechRate: Int
        get() = prefs.getInt("ttsSpeechRate", 5)

    override val ttsTimer: Int
        get() = prefs.getInt("ttsTimer", 0)

    // ---- 主题 ----
    override val themeMode: String
        get() = prefs.getString("themeMode", "0")

    override val isNightTheme: Boolean
        get() = themeMode == "2"

    override val isEInkMode: Boolean
        get() = themeMode == "3"

    override val useDefaultCover: Boolean
        get() = prefs.getBoolean("useDefaultCover", false)

    // ---- 底栏配置 (桌面端侧栏竖版复用, 底栏高度视为侧栏宽度) ----
    // 默认值与 app 端 AppConfig.BOTTOM_BAR_* 常量一致
    override val bottomBarHeight: Int
        get() = prefs.getInt("bottomBarHeight", 50)

    override val bottomBarIconSize: Int
        get() = prefs.getInt("bottomBarIconSize", 24)

    override val bottomBarLabelMode: Int
        get() = prefs.getInt("bottomBarLabelMode", 0)

    override val showHome: Boolean
        get() = prefs.getBoolean("showHome", true)

    override val showDiscovery: Boolean
        get() = prefs.getBoolean("showDiscovery", true)

    override val bottomNavItemOrder: String
        get() = prefs.getString("bottomNavItemOrder", "")

    override val defaultHomePage: String
        get() = prefs.getString("defaultHomePage", "bookshelf")

    // ---- 导入业务 (ImportBookSourceViewModelShared / ImportBookViewModelShared 用) ----
    override val importKeepName: Boolean
        get() = prefs.getBoolean("importKeepName", false)

    override val importKeepGroup: Boolean
        get() = prefs.getBoolean("importKeepGroup", false)

    override val importKeepEnable: Boolean
        get() = prefs.getBoolean("importKeepEnable", false)

    override val localBookImportSort: Int
        get() = prefs.getInt("localBookImportSort", 0)

    // ---- 远程服务 / 批量管理 ----
    override val remoteServerId: Long
        get() = prefs.getLong("remoteServerId", 0L)

    override val batchChangeSourceDelay: Int
        get() = prefs.getInt("batchChangeSourceDelay", 0)
}

/**
 * 注册 [NativeAppConfigAccessor] 到 [AppConfigProviders] (iOS/鸿蒙共用)。
 *
 * 前置依赖: [PreferenceProviders] 已注册 (AppConfigAccessor 委托 PreferenceProvider)。
 */
fun registerNativeAppConfigAccessor() {
    AppConfigProviders.register(NativeAppConfigAccessor())
}
