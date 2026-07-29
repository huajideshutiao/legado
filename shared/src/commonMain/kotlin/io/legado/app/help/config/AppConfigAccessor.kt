package io.legado.app.help.config

import kotlin.concurrent.Volatile

/**
 * AppConfig 跨模块只读访问接口。
 *
 * AppConfig 依赖 SharedPreferences + appCtx, 留 app 端。本接口暴露 webBook
 * 编排层 (BookChapterList/BookContent) 及下沉的 Book 扩展
 * (getDisplayTitle/getUseReplaceRule 等) 用到的配置项, 由 app 端
 * AppConfigAccessorImpl 包装 AppConfig 实现, 在 App.onCreate 经
 * [AppConfigProviders.register] 注册。
 *
 * 模式参考 BookInfoRefreshers / SourceDebugLoggers。
 */
interface AppConfigAccessor {
    /** 并发线程数 (原 AppConfig.threadCount)。 */
    val threadCount: Int

    /** 目录页是否统计字数 (原 AppConfig.tocCountWords)。 */
    val tocCountWords: Boolean

    /** 持久化 tocCountWords (原 AppConfig.tocCountWords = value)。 */
    fun setTocCountWords(value: Boolean) {}

    /** 目录界面是否使用替换规则 (原 AppConfig.tocUiUseReplace), 默认 false。 */
    val tocUiUseReplace: Boolean

    /** 持久化 tocUiUseReplace (原 AppConfig.tocUiUseReplace = value)。 */
    fun setTocUiUseReplace(value: Boolean) {}

    /** 简繁转换类型 (原 AppConfig.chineseConverterType): 0=不转换, 1=t2s, 2=s2t。 */
    var chineseConverterType: Int

    /** 默认是否启用替换规则 (原 AppConfig.replaceEnableDefault)。 */
    val replaceEnableDefault: Boolean

    /** 是否启用阅读时长记录 (原 AppConfig.enableReadRecord), ReadTimeRecorder 用。 */
    val enableReadRecord: Boolean

    // ---- 书架业务 ----
    /** 书架排序方式 (原 AppConfig.bookshelfSort), 默认 0。 */
    val bookshelfSort: Int

    /** 书架布局 (原 AppConfig.bookshelfLayout), 默认 0。 */
    val bookshelfLayout: Int

    /** 书架封面高度 (原 AppConfig.bookshelfCoverHeight), 默认 120, 范围 90..220。 */
    val bookshelfCoverHeight: Int

    /** 书架网格宽度 (原 AppConfig.bookshelfGridWidth), 默认 120。 */
    val bookshelfGridWidth: Int

    /** 是否显示未读 (原 AppConfig.showUnread), 默认 true。 */
    val showUnread: Boolean

    /** 书架列表显示种类 (原 AppConfig.bookshelfListShowKind), 默认 false。 */
    val bookshelfListShowKind: Boolean

    /** 书架列表显示简介 (原 AppConfig.bookshelfListShowIntro), 默认 false。 */
    val bookshelfListShowIntro: Boolean

    /** 书架列表简介行数 (原 AppConfig.bookshelfListIntroLines), 默认 2, 范围 1..3。 */
    val bookshelfListIntroLines: Int

    /** 书架是否显示分组数量 (原 AppConfig.bookshelfShowGroupCount), 默认 true。 */
    val bookshelfShowGroupCount: Boolean

    /** 书架固定宽度模式 (原 AppConfig.bookshelfFixedWidthMode), 默认 false。 */
    val bookshelfFixedWidthMode: Boolean

    /** 书架列表是否显示最后更新时间 (原 AppConfig.showLastUpdateTime), 默认 false。 */
    val showLastUpdateTime: Boolean

    /** 保存 Tab 位置 (原 AppConfig.saveTabPosition), 默认 0。 */
    val saveTabPosition: Int

    /** 书籍导出文件名表达式 (原 AppConfig.bookExportFileName), 默认空串。 */
    val bookExportFileName: String

    /** 章节导出文件名表达式 (原 AppConfig.episodeExportFileName), 默认空串。 */
    val episodeExportFileName: String

    /** 书籍分组样式 (原 AppConfig.bookGroupStyle), 默认 0。 */
    val bookGroupStyle: Int

    /** 是否自动刷新书籍 (原 AppConfig.autoRefreshBook), 默认 false。 */
    val autoRefreshBook: Boolean

    /** 预下载数量 (原 AppConfig.preDownloadNum), 默认 10。 */
    val preDownloadNum: Int

    // ---- 搜索业务 ----
    /**
     * 搜索范围 (原 AppConfig.searchScope), 默认空串。
     * var 因 SearchScope.save() 写回。
     */
    var searchScope: String

    /**
     * 搜索分组 (原 AppConfig.searchGroup), 默认空串。
     * var 因 SearchScope.save() 按 scope 形态同步刷新。
     */
    var searchGroup: String

    /** 搜索布局 (原 AppConfig.searchLayout), 默认 1。 */
    val searchLayout: Int

    /** 是否精确搜索 (原 AppConfig.precisionSearch), 默认 false。 */
    val precisionSearch: Boolean

    /**
     * 持久化 precisionSearch (原 `AppConfig.precisionSearch = value`)。
     *
     * 无默认实现: SearchViewModel 切换开关依赖写回, 各端必须实现
     * (Android 写 AppConfig, 桌面/iOS/鸿蒙写 PreferenceProvider)。
     */
    fun setPrecisionSearch(value: Boolean)

    // ---- 缓存业务 ----
    /** 导出字符集 (原 AppConfig.exportCharset), 默认 "UTF-8"。 */
    val exportCharset: String

    // ---- WebDav ----
    /** WebDav 地址 (原 AppConfig.webDavUrl), 默认空串。 */
    val webDavUrl: String

    /** WebDav 账号 (原 AppConfig.webDavAccount), 默认空串。 */
    val webDavAccount: String

    /** WebDav 密码 (原 AppConfig.webDavPassword), 默认空串。 */
    val webDavPassword: String

    /** 是否同步阅读进度 (原 AppConfig.syncBookProgress), 默认 true。 */
    val syncBookProgress: Boolean

    /** WebDav 子目录 (原 AppConfig.webDavDir), 默认 "legado"。 */
    val webDavDir: String

    /** WebDav 设备名 (原 AppConfig.webDavDeviceName), 默认空串。 */
    val webDavDeviceName: String

    // ---- 朗读业务 ----
    /** TTS 引擎 (原 AppConfig.ttsEngine), 默认空串。 */
    val ttsEngine: String

    // 持久化 ttsEngine (原 AppConfig.ttsEngine = value), 默认空实现供各端按需覆写
    fun setTtsEngine(value: String?) {}

    /** TTS 语速 (原 AppConfig.ttsSpeechRate), 默认 5 (= AppConfig.defaultSpeechRate)。 */
    val ttsSpeechRate: Int

    /** TTS 定时器 (原 AppConfig.ttsTimer), 默认 0。 */
    val ttsTimer: Int

    // ---- 主题 ----
    /** 主题模式 (原 AppConfig.themeMode): "0"=跟随系统, "1"=日间, "2"=夜间, "3"=E-Ink, 默认 "0"。 */
    val themeMode: String

    /** 是否夜间主题 (原 AppConfig.isNightTheme), 基于 themeMode 计算。 */
    val isNightTheme: Boolean

    /** 是否 E-Ink 模式 (原 AppConfig.isEInkMode), themeMode == "3"。 */
    val isEInkMode: Boolean

    /** 是否使用默认封面 (原 AppConfig.useDefaultCover), 默认 false。 */
    val useDefaultCover: Boolean

    // ---- 底栏配置 (桌面端侧栏竖版复用, 底栏高度视为侧栏宽度) ----
    /** 底栏高度 (原 AppConfig.bottomBarHeight), 默认 50, 范围 36..80。桌面端侧栏作为宽度使用。 */
    val bottomBarHeight: Int

    /** 底栏图标尺寸 (原 AppConfig.bottomBarIconSize), 默认 24, 范围 18..36。 */
    val bottomBarIconSize: Int

    /** 底栏标签模式 (原 AppConfig.bottomBarLabelMode), 默认 0, 范围 0..3。0=无/1=恒显/2=仅选中/3=自动。 */
    val bottomBarLabelMode: Int

    /** 是否显示主页入口 (原 AppConfig.showHome), 默认 true。 */
    val showHome: Boolean

    /** 是否显示发现入口 (原 AppConfig.showDiscovery), 默认 true。 */
    val showDiscovery: Boolean

    /** 底栏入口顺序 (原 AppConfig.bottomNavItemOrder), 逗号分隔的 4 个 tag, 空串表示默认顺序。 */
    val bottomNavItemOrder: String

    /** 默认首页 (原 AppConfig.defaultHomePage), "home"/"bookshelf"/"explore"/"my", 默认 "bookshelf"。 */
    val defaultHomePage: String

    // ---- 导入业务 (ImportBookSourceViewModel / ImportBookViewModel 用) ----
    /** 导入书源时是否保留名称 (原 AppConfig.importKeepName), 默认 false。 */
    val importKeepName: Boolean

    /** 导入书源时是否保留分组 (原 AppConfig.importKeepGroup), 默认 false。 */
    val importKeepGroup: Boolean

    /** 导入书源时是否保留启用状态 (原 AppConfig.importKeepEnable), 默认 false。 */
    val importKeepEnable: Boolean

    /** 本地书导入排序方式 (原 AppConfig.localBookImportSort), 默认 0。 */
    val localBookImportSort: Int

    // ---- 远程服务 / 批量管理 ----
    /** 远程服务器 ID (原 AppConfig.remoteServerId), RemoteBookViewModel 用。 */
    val remoteServerId: Long

    /** 批量换源延迟毫秒 (原 AppConfig.batchChangeSourceDelay), BookshelfManageViewModel.changeSource 用。 */
    val batchChangeSourceDelay: Int

    // ---- Web 服务 / 其他 ----
    /** Web 服务端口 (原 AppConfig.webPort), 默认 1122。合法区间校验在 WebServerManager.getPort。 */
    val webPort: Int

    /** 图片缓存大小 MB (原 AppConfig.bitmapCacheSize), 默认 50。 */
    val bitmapCacheSize: Int

    /** 源编辑最大行数 (原 AppConfig.sourceEditMaxLine): 存储值 <10 视为不限制, 返回 Int.MAX_VALUE。 */
    val sourceEditMaxLine: Int

    /** 欢迎页展示时长毫秒 (原 AppConfig.welcomeShowTime), 默认 600, 范围 0..3000。 */
    val welcomeShowTime: Int

    /** 是否启用开发特性 (原 AppConfig.devFeat), 默认 false。 */
    val devFeat: Boolean get() = false

    /** 书籍详情页横向布局开关 (原 AppConfig.bookInfoHorizontalLayout), 默认 false。
     *  BookInfoRoute 据此计算 useDevFeat (与 isVideo/isLandscape 组合)。 */
    val bookInfoHorizontalLayout: Boolean get() = false
}

/**
 * app 端 AppConfig intPref 区间钳制表 (语义权威), 各端 Accessor 读取时 coerceIn。
 * Android 端由 AppConfig.intPref 自带钳制, 无需引用。
 */
object AppConfigRanges {
    val bookshelfListIntroLines = 1..3
    val bookshelfCoverHeight = 90..220
    val bottomBarHeight = 36..80
    val bottomBarIconSize = 18..36
    val bottomBarLabelMode = 0..3
    val welcomeShowTime = 0..3000
}

/**
 * AppConfig provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `AppConfigProviders.get().threadCount` 替代
 * 原 `AppConfig.threadCount`, 行为完全一致, 仅多一层 provider 间接。
 */
object AppConfigProviders {
    @Volatile
    private var impl: AppConfigAccessor? = null

    /** 宿主启动早期注册一次(任何 webBook 调用之前)。 */
    fun register(impl: AppConfigAccessor) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): AppConfigAccessor = impl ?: error("AppConfigProviders not registered")
}
