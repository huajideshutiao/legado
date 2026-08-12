package io.legado.app.help.config

import android.content.SharedPreferences
import android.os.Build
import io.legado.app.BuildConfig
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.i18n.androidAppString
import io.legado.app.ui.book.getRealBookSort
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isNightMode
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.sysConfiguration
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx

@Suppress("MemberVisibilityCanBePrivate", "ConstPropertyName")
object AppConfig : SharedPreferences.OnSharedPreferenceChangeListener {

    // 缓存字段：热路径读取，监听器中重载
    var isCronet by cachedBoolPref(PreferKey.cronet)
    var userAgent by cachedPref(
        PreferKey.userAgent,
        { getPrefUserAgent() },
        { appCtx.putPrefString(PreferKey.userAgent, it) },
    )
    var themeMode by cachedStringPref(PreferKey.themeMode, "0")
    var useDefaultCover by cachedBoolPref(PreferKey.useDefaultCover, false)
    var recordLog by cachedBoolPref(PreferKey.recordLog)
    var clickActionTL by cachedIntPref(PreferKey.clickActionTL, 2)
    var clickActionTC by cachedIntPref(PreferKey.clickActionTC, 2)
    var clickActionTR by cachedIntPref(PreferKey.clickActionTR, 1)
    var clickActionML by cachedIntPref(PreferKey.clickActionML, 2)
    var clickActionMC by cachedIntPref(PreferKey.clickActionMC, 0)
    var clickActionMR by cachedIntPref(PreferKey.clickActionMR, 1)
    var clickActionBL by cachedIntPref(PreferKey.clickActionBL, 2)
    var clickActionBC by cachedIntPref(PreferKey.clickActionBC, 1)
    var clickActionBR by cachedIntPref(PreferKey.clickActionBR, 1)

    val isEInkMode get() = themeMode == "3"
    val optimizeRender get() = CanvasRecorderFactory.isSupport

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        key ?: return
        reloadCachedPref(key)
        when (key) {
            PreferKey.useZhLayout -> ReadBookConfig.useZhLayout =
                appCtx.getPrefBoolean(PreferKey.useZhLayout)

            PreferKey.cronet -> if (isCronet) {
                io.legado.app.help.http.Cronet.preDownload { success ->
                    if (success) {
                        io.legado.app.help.http.recreateOkHttpClient()
                        appCtx.toastOnUi(androidAppString("cronet_enabled"))
                    } else {
                        appCtx.toastOnUi(androidAppString("cronet_download_failed"))
                    }
                }
            }
        }
    }

    var isNightTheme: Boolean
        get() = when (themeMode) {
            "1" -> false
            "2" -> true
            "3" -> false
            else -> sysConfiguration.isNightMode
        }
        set(value) {
            if (isNightTheme != value) {
                appCtx.putPrefString(PreferKey.themeMode, if (value) "2" else "1")
            }
        }

    var showUnread by boolPref(PreferKey.showUnread, true)
    var showBookshelfFastScroller by boolPref(PreferKey.showBookshelfFastScroller, true)
    var showLastUpdateTime by boolPref(PreferKey.showLastUpdateTime, false)
    var bookshelfListShowKind by boolPref(PreferKey.bookshelfListShowKind, false)
    var bookshelfListShowIntro by boolPref(PreferKey.bookshelfListShowIntro, false)
    var bookshelfListIntroLines by intPref(PreferKey.bookshelfListIntroLines, 2, 1..3)
    var bookshelfCoverHeight by intPref(PreferKey.bookshelfCoverHeight, 120, 90..220)

    // 与 BookSource.exploreStyle 同一套位编码 (低 3 位列数, 0x10 视频)
    var searchLayout by intPref(PreferKey.searchLayout, 1)

    var bottomBarHeight by intPref(
        PreferKey.bottomBarHeight,
        AppConfigConstants.BOTTOM_BAR_HEIGHT_DEFAULT,
        AppConfigConstants.BOTTOM_BAR_HEIGHT_MIN..AppConfigConstants.BOTTOM_BAR_HEIGHT_MAX,
    )
    var bottomBarIconSize by intPref(
        PreferKey.bottomBarIconSize,
        AppConfigConstants.BOTTOM_BAR_ICON_DEFAULT,
        AppConfigConstants.BOTTOM_BAR_ICON_MIN..AppConfigConstants.BOTTOM_BAR_ICON_MAX,
    )

    /**
     * 0 = unlabeled, 1 = labeled, 2 = selected, 3 = auto
     */
    var bottomBarLabelMode by intPref(
        PreferKey.bottomBarLabelMode,
        AppConfigConstants.BOTTOM_BAR_LABEL_DEFAULT,
        0..3
    )

    var bookshelfShowGroupCount by boolPref(PreferKey.bookshelfShowGroupCount, true)

    val screenOrientation by stringPref(PreferKey.screenOrientation)

    var bookGroupStyle by intPref(PreferKey.bookGroupStyle, 0)

    var bookshelfLayout: Int
        get() {
            val value = appCtx.getPrefInt(PreferKey.bookshelfLayout, 0)
            if (!appCtx.getPrefBoolean("bookshelfLayoutMigrated", false)) {
                val migrated = when (value) {
                    1 -> 3
                    2 -> 4
                    3 -> 5
                    4 -> 6
                    else -> value
                }
                appCtx.putPrefInt(PreferKey.bookshelfLayout, migrated)
                appCtx.putPrefBoolean("bookshelfLayoutMigrated", true)
                return migrated
            }
            return value
        }
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfLayout, value)
        }

    var bookshelfFixedWidthMode by boolPref(PreferKey.bookshelfFixedWidthMode, false)
    var bookshelfGridWidth by intPref(PreferKey.bookshelfGridWidth, 120)
    var saveTabPosition by intPref(PreferKey.saveTabPosition, 0)
    var bookExportFileName by stringPref(PreferKey.bookExportFileName)

    // 保存 自定义导出章节模式 文件名js表达式
    var episodeExportFileName by stringPref(PreferKey.episodeExportFileName, "")

    var bookImportFileName by stringPref(PreferKey.bookImportFileName)
    var backupPath by stringPrefClearOnEmpty(PreferKey.backupPath)

    // 书籍保存位置
    var defaultBookTreeUri by stringPrefClearOnEmpty(PreferKey.defaultBookTreeUri)

    var showDiscovery by boolPref(PreferKey.showDiscovery, true)

    var showHome by boolPref(PreferKey.showHome, true)

    var bottomNavItemOrder by stringPref(PreferKey.bottomNavItemOrder, "")

    val autoRefreshBook by boolPref(PreferKey.autoRefresh)

    var threadCount by intPref(PreferKey.threadCount, 16)
    var remoteServerId by longPref(PreferKey.remoteServerId)

    // 添加本地选择的目录
    var importBookPath by stringPrefClearOnEmpty("importBookPath")

    var ttsFlowSys by boolPref(PreferKey.ttsFollowSys, true)
    var ttsSpeechRate by intPref(PreferKey.ttsSpeechRate, AppConfigConstants.defaultSpeechRate)
    var ttsTimer by intPref(PreferKey.ttsTimer, 0)

    val readAloudWakeLock by boolPref(PreferKey.readAloudWakeLock, false)
    val readAloudByPage by boolPref(PreferKey.readAloudByPage)
    val mediaButtonPerNext by boolPref("mediaButtonPerNext", false)
    val systemMediaControlCompatibilityChange by boolPref("systemMediaControlCompatibilityChange")

    val speechRatePlay: Int get() = if (ttsFlowSys) AppConfigConstants.defaultSpeechRate else ttsSpeechRate

    var chineseConverterType by intPref(PreferKey.chineseConverterType)
    var systemTypefaces by intPref(PreferKey.systemTypefaces)
    var fontFolder by stringPref(PreferKey.fontFolder)
    var processText by boolPref(PreferKey.processText, true)
    var checkSource by stringPref(PreferKey.checkSource)

    var readUrlInBrowser by boolPref(PreferKey.readUrlOpenInBrowser)

    var exportCharset: String
        get() {
            val c = appCtx.getPrefString(PreferKey.exportCharset)
            return if (c.isNullOrBlank()) "UTF-8" else c
        }
        set(value) {
            appCtx.putPrefString(PreferKey.exportCharset, value)
        }

    var exportUseReplace by boolPref(PreferKey.exportUseReplace, true)
    var exportToWebDav by boolPref(PreferKey.exportToWebDav)
    var exportNoChapterName by boolPref(PreferKey.exportNoChapterName)

    // 是否启用自定义导出 default->false
    var enableCustomExport by boolPref(PreferKey.enableCustomExport, false)

    var exportType by intPref(PreferKey.exportType)
    var changeSourceCheckAuthor by boolPref(PreferKey.changeSourceCheckAuthor)
    var ttsEngine by stringPref(PreferKey.ttsEngine)
    var webPort by intPref(PreferKey.webPort, 1122)
    val webServiceWakeLock by boolPref(PreferKey.webServiceWakeLock, false)
    var webService by boolPref(PreferKey.webService)
    var tocUiUseReplace by boolPref(PreferKey.tocUiUseReplace)
    var tocCountWords by boolPref(PreferKey.tocCountWords, true)
    var enableReadRecord by boolPref(PreferKey.enableReadRecord, true)

    val autoChangeSource by boolPref(PreferKey.autoChangeSource, true)

    var changeSourceLoadInfo by boolPref(PreferKey.changeSourceLoadInfo)
    var changeSourceLoadToc by boolPref(PreferKey.changeSourceLoadToc)
    var changeSourceLoadWordCount by boolPref(PreferKey.changeSourceLoadWordCount)
    var contentSelectSpeakMod by intPref(PreferKey.contentSelectSpeakMod)
    var batchChangeSourceDelay by intPref(PreferKey.batchChangeSourceDelay)

    var importKeepName by boolPref(PreferKey.importKeepName)
    var importKeepGroup by boolPref(PreferKey.importKeepGroup)
    var importKeepEnable by boolPref(PreferKey.importKeepEnable, false)
    var localBookImportSort by intPref(PreferKey.localBookImportSort)

    var previewImageByClick by boolPref(PreferKey.previewImageByClick, false)
    var preDownloadNum by intPref(PreferKey.preDownloadNum, 10)

    val syncBookProgress by boolPref(PreferKey.syncBookProgress, true)
    val syncBookProgressPlus by boolPref(PreferKey.syncBookProgressPlus, false)
    val mediaButtonOnExit by boolPref("mediaButtonOnExit", true)
    val readAloudByMediaButton by boolPref(PreferKey.readAloudByMediaButton, false)
    val replaceEnableDefault by boolPref(PreferKey.replaceEnableDefault, true)
    val webDavUrl by stringPref(PreferKey.webDavUrl)
    val webDavAccount by stringPref(PreferKey.webDavAccount)
    var webDavPassword by stringPref(PreferKey.webDavPassword)
    val webDavDir by stringPref(PreferKey.webDavDir, "legado")
    val webDavDeviceName by stringPref(PreferKey.webDavDeviceName, Build.MODEL)
    val recordHeapDump by boolPref(PreferKey.recordHeapDump, false)
    val loadCoverOnlyWifi by boolPref(PreferKey.loadCoverOnlyWifi, false)
    val showAddToShelfAlert by boolPref(PreferKey.showAddToShelfAlert, true)
    val coverShowName by boolPref(PreferKey.coverShowName, true)
    val coverShowNameN by boolPref(PreferKey.coverShowNameN, true)
    val coverShowAuthor by boolPref(PreferKey.coverShowAuthor, true)
    val coverShowAuthorN by boolPref(PreferKey.coverShowAuthorN, true)

    var bookInfoDeleteAlert by boolPref(PreferKey.bookInfoDeleteAlert, true)

    val ignoreAudioFocus by boolPref(PreferKey.ignoreAudioFocus, false)

    var pauseReadAloudWhilePhoneCalls by boolPref(PreferKey.pauseReadAloudWhilePhoneCalls, false)

    val onlyLatestBackup by boolPref(PreferKey.onlyLatestBackup, true)
    val autoCheckNewBackup by boolPref(PreferKey.autoCheckNewBackup, true)
    val autoCheckUpdate by boolPref(PreferKey.autoCheckUpdate, true)
    val defaultHomePage by stringPref(PreferKey.defaultHomePage, "bookshelf")
    val updateToVariant by stringPref(PreferKey.updateToVariant, "default_version")
    val streamReadAloudAudio by boolPref(PreferKey.streamReadAloudAudio, false)
    val doublePageHorizontal by stringPref(PreferKey.doublePageHorizontal)
    val progressBarBehavior by stringPref(PreferKey.progressBarBehavior, "page")
    var prevKeys by stringPref(PreferKey.prevKeys)
    var nextKeys by stringPref(PreferKey.nextKeys)
    val keepLight by stringPref(PreferKey.keepLight)

    var precisionSearch by boolPref(PreferKey.precisionSearch)

    var searchScope by nonNullStringPref("searchScope", "")
    var searchGroup by nonNullStringPref("searchGroup", "")

    var pageTouchSlop by intPref(PreferKey.pageTouchSlop, 0)
    var bookshelfSort by intPref(PreferKey.bookshelfSort, 0)

    fun getBookSortByGroupId(groupId: Long): Int {
        return runBlocking { appDb.bookGroupDao.getByID(groupId) }?.getRealBookSort()
            ?: bookshelfSort
    }

    private fun getPrefUserAgent(): String {
        val ua = appCtx.getPrefString(PreferKey.userAgent)
        if (ua.isNullOrBlank()) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + BuildConfig.Cronet_Main_Version + " Safari/537.36"
        }
        return ua
    }

    var bitmapCacheSize by intPref(PreferKey.bitmapCacheSize, 50)
    var showReadTitleBarAddition by boolPref(PreferKey.showReadTitleAddition, true)

    var sourceEditMaxLine: Int
        get() {
            val maxLine = appCtx.getPrefInt(PreferKey.sourceEditMaxLine, Int.MAX_VALUE)
            return if (maxLine < 10) Int.MAX_VALUE else maxLine
        }
        set(value) {
            appCtx.putPrefInt(PreferKey.sourceEditMaxLine, value)
        }

    var audioPlayUseWakeLock by boolPref(PreferKey.audioPlayWakeLock)

    fun detectClickArea() {
        if (clickActionTL * clickActionTC * clickActionTR
            * clickActionML * clickActionMC * clickActionMR
            * clickActionBL * clickActionBC * clickActionBR != 0
        ) {
            appCtx.putPrefInt(PreferKey.clickActionMC, 0)
            appCtx.toastOnUi("当前没有配置菜单区域,自动恢复中间区域为菜单.")
        }
    }

    //点击书目直接进入阅读，跳过详情页
    val devFeat by boolPref(PreferKey.devFeat, false)

    //书目详情页改为横向布局（封面在左、信息在右）
    val bookInfoHorizontalLayout by boolPref(PreferKey.bookInfoHorizontalLayout, false)

    var disableMangaPageAnim by boolPref(PreferKey.disableMangaPageAnim, false)

    //漫画预加载数量
    var mangaPreDownloadNum by intPref(PreferKey.mangaPreDownloadNum, 10)

    //漫画滚动速度
    var mangaAutoPageSpeed by intPref(PreferKey.mangaAutoPageSpeed, 3)

    //漫画页脚配置
    var mangaFooterConfig by stringPref(PreferKey.mangaFooterConfig, "")

    //漫画水平滚动
    var enableMangaHorizontalScroll by boolPref(PreferKey.enableMangaHorizontalScroll, false)

    var mangaColorFilter by stringPref(PreferKey.mangaColorFilter, "")

    //禁用漫画内标题
    var hideMangaTitle by boolPref(PreferKey.hideMangaTitle, false)

    var enableMangaGray by boolPref(PreferKey.enableMangaGray, false)

    //GIF播放完成后自动翻到下一张（仅横向翻页模式）
    var enableMangaGifAutoNext by boolPref(PreferKey.enableMangaGifAutoNext, false)

    var welcomeImage by stringPref(PreferKey.welcomeImage)
    var welcomeShowText by boolPref(PreferKey.welcomeShowText, true)
    var welcomeShowIcon by boolPref(PreferKey.welcomeShowIcon, true)
    var welcomeShowTime by intPref(PreferKey.welcomeShowTime, 600, 0..3000)
    var welcomeImageDark by stringPref(PreferKey.welcomeImageDark)
    val enableWelcome by boolPref(PreferKey.enableWelcome, true)

    /** 恢复默认 UA:清 pref 并立即重载缓存,不等监听器 */
    fun resetUserAgent() {
        appCtx.removePref(PreferKey.userAgent)
        reloadCachedPref(PreferKey.userAgent)
    }

}
