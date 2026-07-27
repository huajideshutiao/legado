package io.legado.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isVideo
import io.legado.app.ui.about.IosAboutScreen
import io.legado.app.ui.about.IosReadRecordScreen
import io.legado.app.ui.association.DeepLinkImportHost
import io.legado.app.ui.association.IosRuleSubScreen
import io.legado.app.ui.book.audio.IosAudioPlayScreen
import io.legado.app.ui.book.bookmark.IosBookmarkScreen
import io.legado.app.ui.book.changesource.IosChangeBookSourceScreen
import io.legado.app.ui.book.explore.IosExploreShowScreen
import io.legado.app.ui.book.filter.IosSourceFilterRuleScreen
import io.legado.app.ui.book.import.local.IosImportBookScreen
import io.legado.app.ui.book.import.remote.IosRemoteBookScreen
import io.legado.app.ui.book.manage.IosBookshelfManageScreen
import io.legado.app.ui.book.manga.IosMangaReaderScreen
import io.legado.app.ui.book.read.config.IosReadAloudConfigScreen
import io.legado.app.ui.book.toc.IosTocScreen
import io.legado.app.ui.book.toc.rule.IosTxtTocRuleScreen
import io.legado.app.ui.book.video.IosVideoPlayerScreen
import io.legado.app.ui.bookinfo.IosBookInfoEditScreen
import io.legado.app.ui.bookinfo.IosBookInfoScreen
import io.legado.app.ui.bookshelf.IosBookshelfScreen
import io.legado.app.ui.booksource.IosBookSourceScreen
import io.legado.app.ui.config.IosBackupConfigScreen
import io.legado.app.ui.config.IosCoverConfigScreen
import io.legado.app.ui.config.IosOtherConfigScreen
import io.legado.app.ui.config.IosThemeConfigScreen
import io.legado.app.ui.config.IosWelcomeConfigScreen
import io.legado.app.ui.dict.rule.IosDictRuleScreen
import io.legado.app.ui.main.explore.IosExploreScreen
import io.legado.app.ui.main.my.IosMyConfigScreen
import io.legado.app.ui.reader.IosReaderScreen
import io.legado.app.ui.replace.IosReplaceEditScreen
import io.legado.app.ui.replace.IosReplaceRuleScreen
import io.legado.app.ui.rss.IosReadRssScreen
import io.legado.app.ui.rss.IosRssArticlesScreen
import io.legado.app.ui.rss.IosRssSourcesScreen
import io.legado.app.ui.search.IosSearchScreen

/**
 * iOS 端阅读流子路由宿主 (KP5: 对齐 desktop `DesktopApp.kt` 的 when(currentRoute) 模式)。
 *
 * # 背景
 *
 * KP3 阶段 [io.legado.app.MainViewController] 仅渲染书架 Screen (IosBookshelfScreen),
 * 路由跳转回调全部 no-op; KP4 接入阅读流 4 个核心子路由; KP5 扩展至全部 30 个路由,
 * KP6 追加 RSS 3 个, KP7 追加发现列表共 34 个路由, 覆盖配置/规则/书架管理/导入/目录/
 * 媒体播放/发现/RSS 等全部 sharedUiMain + commonMain Screen 包装。
 *
 * # 路由状态
 *
 * - [currentRoute]: 当前显示的子路由 (默认 [IosRoute.BOOKSHELF])
 * - [readerBook]: READER 路由消费, 书架 onBookClick / 详情 onReadClick 触发
 * - [infoBook]: BOOK_INFO 路由消费, 搜索/书架 onBookLongClick 触发
 * - [audioBook]: AUDIO_PLAY 路由消费, 详情 onReadClick (音频书) 触发
 * - [mangaBook]: MANGA_READER 路由消费, 详情 onReadClick (漫画书) 触发
 * - [videoBook]: VIDEO_PLAYER 路由消费, 详情 onReadClick (视频书) 触发
 * - [tocBook]: TOC 路由消费, 详情 onTocClick 触发
 * - [bookInfoEditUrl]: BOOK_INFO_EDIT 路由消费, 详情 onEditClick 触发
 * - [exploreShowSource]/[exploreShowTitle]/[exploreShowUrl]: EXPLORE_SHOW 路由消费,
 *   EXPLORE 路由 (IosExploreScreen) 点击分类/收藏项触发赋值
 *
 * 子路由返回逻辑: 子页 onBack 回到调用方路由
 * (BOOK_INFO → BOOKSHELF, SEARCH → BOOKSHELF, READER → BOOKSHELF, etc.)
 *
 * # Dialog/overlay 包装 (不走路由, 由父 Screen 内部管理)
 *
 * 以下包装已在父 Screen 内部以 Dialog/overlay 形式接入, 不需要在 IosNavHost 注册路由:
 * - IosEffectiveReplacesScreen (IosReaderScreen 内 Dialog)
 * - IosBookSourceDebugScreen (IosBookSourceScreen 内 Dialog)
 * - IosBookSourceEditScreen (IosBookSourceScreen / IosChangeBookSourceScreen 内 Dialog)
 * - IosSearchContentScreen (IosReaderScreen 内 overlay)
 * - IosChangeBookSourceScreen (IosReaderScreen 内 overlay)
 * - IosChangeChapterSourceScreen (IosReaderScreen 内 overlay)
 * - IosHomeScreen / IosMainScreen (tab 容器, 当前 iOS 端用扁平路由不接入;
 *   发现页已改走 EXPLORE 全屏路由, 入口为书架溢出菜单"发现")
 *
 * # macOS 编译命令 (Windows 无法编译 iOS target)
 *
 * ```
 * ./gradlew :shared:compileKotlinIosArm64
 * ./gradlew :shared:linkDebugFrameworkIosArm64
 * ```
 */
@Composable
fun IosNavHost() {
    var currentRoute by remember { mutableStateOf(IosRoute.BOOKSHELF) }

    // 阅读流核心状态
    var readerBook by remember { mutableStateOf<Book?>(null) }
    var infoBook by remember { mutableStateOf<BaseBook?>(null) }

    // 媒体播放状态 (音频/漫画/视频, 由 BOOK_INFO onReadClick 按书类型分流)
    var audioBook by remember { mutableStateOf<Book?>(null) }
    var mangaBook by remember { mutableStateOf<Book?>(null) }
    var videoBook by remember { mutableStateOf<Book?>(null) }

    // 目录页状态 (由 BOOK_INFO onTocClick 触发)
    var tocBook by remember { mutableStateOf<Book?>(null) }

    // 整书换源状态 (由 BOOK_INFO onOriginClick 触发)
    var changeSourceBook by remember { mutableStateOf<Book?>(null) }

    // 书籍信息编辑状态 (由 BOOK_INFO onEditClick 触发)
    var bookInfoEditUrl by remember { mutableStateOf("") }

    // 替换规则编辑状态 (由 REPLACE_RULE onAddRule/onEditRule 触发, -1 = 新增)
    var editRuleId by remember { mutableStateOf(-1L) }

    // 发现结果页状态 (EXPLORE 路由点击分类/收藏项触发)
    var exploreShowSource by remember { mutableStateOf<BookSource?>(null) }
    var exploreShowTitle by remember { mutableStateOf("") }
    var exploreShowUrl by remember { mutableStateOf<String?>(null) }

    // RSS 流状态 (由 RSS_SOURCES 点击源 → RSS_ARTICLES → READ_RSS)
    var rssSourceBook by remember { mutableStateOf<Book?>(null) }
    var rssChapterIndex by remember { mutableStateOf(0) }

    when (currentRoute) {
        // ===== 阅读流核心 5 路由 (KP4 已接入, KP5 扩展回调) =====

        IosRoute.BOOKSHELF -> IosBookshelfScreen(
            onBookClick = { book ->
                readerBook = book
                currentRoute = IosRoute.READER
            },
            onBookLongClick = { book ->
                infoBook = book
                currentRoute = IosRoute.BOOK_INFO
            },
            onSearchClick = { currentRoute = IosRoute.SEARCH },
            onAddLocalBook = { currentRoute = IosRoute.IMPORT_BOOK },
            onAddRemoteBook = { currentRoute = IosRoute.REMOTE_BOOK },
            onOpenBookshelfManage = { currentRoute = IosRoute.BOOKSHELF_MANAGE },
            onOpenMyConfig = { currentRoute = IosRoute.MY_CONFIG },
            onOpenExplore = { currentRoute = IosRoute.EXPLORE },
        )

        IosRoute.READER -> readerBook?.let { book ->
            IosReaderScreen(
                book = book,
                onBack = {
                    readerBook = null
                    currentRoute = IosRoute.BOOKSHELF
                },
                onOpenBookInfo = { infoBookArg ->
                    infoBook = infoBookArg
                    readerBook = null
                    currentRoute = IosRoute.BOOK_INFO
                },
            )
        }

        IosRoute.SEARCH -> IosSearchScreen(
            onBack = { currentRoute = IosRoute.BOOKSHELF },
            onBookClick = { book ->
                infoBook = book
                currentRoute = IosRoute.BOOK_INFO
            },
            onManageBookSources = { currentRoute = IosRoute.BOOK_SOURCE },
        )

        IosRoute.BOOK_INFO -> infoBook?.let { book ->
            IosBookInfoScreen(
                book = book,
                onBack = {
                    infoBook = null
                    currentRoute = IosRoute.BOOKSHELF
                },
                onReadClick = { readBook ->
                    infoBook = null
                    // 按书类型分流到对应媒体播放路由
                    when {
                        readBook.isAudio -> {
                            audioBook = readBook
                            currentRoute = IosRoute.AUDIO_PLAY
                        }
                        readBook.isImage -> {
                            mangaBook = readBook
                            currentRoute = IosRoute.MANGA_READER
                        }
                        readBook.isVideo -> {
                            videoBook = readBook
                            currentRoute = IosRoute.VIDEO_PLAYER
                        }
                        else -> {
                            readerBook = readBook
                            currentRoute = IosRoute.READER
                        }
                    }
                },
                onEditClick = { url ->
                    bookInfoEditUrl = url
                    currentRoute = IosRoute.BOOK_INFO_EDIT
                },
                onOriginClick = { originBook ->
                    changeSourceBook = originBook
                    currentRoute = IosRoute.CHANGE_SOURCE
                },
                onTocClick = { tocBookArg ->
                    tocBook = tocBookArg
                    currentRoute = IosRoute.TOC
                },
            )
        }

        IosRoute.BOOK_SOURCE -> IosBookSourceScreen(
            onBack = { currentRoute = IosRoute.BOOKSHELF },
            onSearchBook = { currentRoute = IosRoute.SEARCH },
        )

        // ===== 配置路由 (6 个, 由 MY_CONFIG 入口跳转) =====

        IosRoute.MY_CONFIG -> IosMyConfigScreen(
            onBack = { currentRoute = IosRoute.BOOKSHELF },
            onThemeSetting = { currentRoute = IosRoute.THEME_CONFIG },
            onWebDavSetting = { currentRoute = IosRoute.BACKUP_CONFIG },
            onOtherSetting = { currentRoute = IosRoute.OTHER_CONFIG },
            onBookSourceManage = { currentRoute = IosRoute.BOOK_SOURCE },
            onReplaceManage = { currentRoute = IosRoute.REPLACE_RULE },
            onSourceFilterRuleManage = { currentRoute = IosRoute.SOURCE_FILTER_RULE },
            onTxtTocRuleManage = { currentRoute = IosRoute.TXT_TOC_RULE },
            onDictRuleManage = { currentRoute = IosRoute.DICT_RULE },
            onRuleSubManage = { currentRoute = IosRoute.RULE_SUB },
            onBookmark = { currentRoute = IosRoute.BOOKMARK },
            onReadRecord = { currentRoute = IosRoute.READ_RECORD },
            onAbout = { currentRoute = IosRoute.ABOUT },
            onRssSources = { currentRoute = IosRoute.RSS_SOURCES },
        )

        IosRoute.THEME_CONFIG -> IosThemeConfigScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
            onCoverConfig = { currentRoute = IosRoute.COVER_CONFIG },
            onWelcomeStyle = { currentRoute = IosRoute.WELCOME_CONFIG },
        )

        IosRoute.COVER_CONFIG -> IosCoverConfigScreen(
            onBack = { currentRoute = IosRoute.THEME_CONFIG },
        )

        IosRoute.WELCOME_CONFIG -> IosWelcomeConfigScreen(
            onBack = { currentRoute = IosRoute.THEME_CONFIG },
        )

        IosRoute.BACKUP_CONFIG -> IosBackupConfigScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
        )

        IosRoute.OTHER_CONFIG -> IosOtherConfigScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
        )

        // ===== 关于/记录/订阅/书签 (4 个) =====

        IosRoute.ABOUT -> IosAboutScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
        )

        IosRoute.READ_RECORD -> IosReadRecordScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
            onOpenBook = { book ->
                readerBook = book
                currentRoute = IosRoute.READER
            },
        )

        IosRoute.RULE_SUB -> IosRuleSubScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
        )

        IosRoute.BOOKMARK -> IosBookmarkScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
            onOpenBookmark = { book, chapterIndex, _ ->
                book.durChapterIndex = chapterIndex
                readerBook = book
                currentRoute = IosRoute.READER
            },
        )

        // ===== 规则管理 (5 个) =====

        IosRoute.SOURCE_FILTER_RULE -> IosSourceFilterRuleScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
        )

        IosRoute.TXT_TOC_RULE -> IosTxtTocRuleScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
        )

        IosRoute.DICT_RULE -> IosDictRuleScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
        )

        IosRoute.REPLACE_RULE -> IosReplaceRuleScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
            onAddRule = {
                editRuleId = -1L
                currentRoute = IosRoute.REPLACE_EDIT
            },
            onEditRule = { id ->
                editRuleId = id
                currentRoute = IosRoute.REPLACE_EDIT
            },
        )

        IosRoute.REPLACE_EDIT -> IosReplaceEditScreen(
            ruleId = editRuleId,
            onBack = {
                currentRoute = IosRoute.REPLACE_RULE
            },
            onSaved = {
                currentRoute = IosRoute.REPLACE_RULE
            },
        )

        // ===== 书架子页 (5 个) =====

        IosRoute.BOOKSHELF_MANAGE -> IosBookshelfManageScreen(
            onBack = { currentRoute = IosRoute.BOOKSHELF },
            onOpenBook = { book ->
                infoBook = book
                currentRoute = IosRoute.BOOK_INFO
            },
        )

        IosRoute.IMPORT_BOOK -> IosImportBookScreen(
            onBack = { currentRoute = IosRoute.BOOKSHELF },
        )

        IosRoute.REMOTE_BOOK -> IosRemoteBookScreen(
            onBack = { currentRoute = IosRoute.BOOKSHELF },
        )

        IosRoute.BOOK_INFO_EDIT -> IosBookInfoEditScreen(
            bookUrl = bookInfoEditUrl,
            onBack = {
                bookInfoEditUrl = ""
                currentRoute = IosRoute.BOOK_INFO
            },
            onSaved = {
                bookInfoEditUrl = ""
                currentRoute = IosRoute.BOOK_INFO
            },
        )

        IosRoute.TOC -> tocBook?.let { book ->
            IosTocScreen(
                book = book,
                onBack = {
                    tocBook = null
                    currentRoute = IosRoute.BOOK_INFO
                },
                onChapterClick = { chapterIndex ->
                    book.durChapterIndex = chapterIndex
                    readerBook = book
                    tocBook = null
                    currentRoute = IosRoute.READER
                },
            )
        }

        // ===== 媒体播放 (4 个, 由 BOOK_INFO onReadClick 按书类型分流) =====

        IosRoute.AUDIO_PLAY -> audioBook?.let { book ->
            IosAudioPlayScreen(
                book = book,
                onBack = {
                    audioBook = null
                    currentRoute = IosRoute.BOOKSHELF
                },
                onOpenToc = { tocBook = book; currentRoute = IosRoute.TOC },
                onOpenChangeSource = { changeSourceBook = book; currentRoute = IosRoute.CHANGE_SOURCE },
            )
        }

        IosRoute.MANGA_READER -> mangaBook?.let { book ->
            IosMangaReaderScreen(
                book = book,
                onBack = {
                    mangaBook = null
                    currentRoute = IosRoute.BOOKSHELF
                },
                onOpenToc = { tocBook = book; currentRoute = IosRoute.TOC },
                onOpenChangeSource = { changeSourceBook = book; currentRoute = IosRoute.CHANGE_SOURCE },
            )
        }

        IosRoute.VIDEO_PLAYER -> videoBook?.let { book ->
            IosVideoPlayerScreen(
                book = book,
                onBack = {
                    videoBook = null
                    currentRoute = IosRoute.BOOKSHELF
                },
                onOpenToc = { tocBook = book; currentRoute = IosRoute.TOC },
                onOpenChangeSource = { changeSourceBook = book; currentRoute = IosRoute.CHANGE_SOURCE },
            )
        }

        // ===== 整书换源 (BOOK_INFO onOriginClick 触发, 对照 OhosNavHost CHANGE_SOURCE) =====

        IosRoute.CHANGE_SOURCE -> changeSourceBook?.let { book ->
            IosChangeBookSourceScreen(
                book = book,
                onBack = {
                    changeSourceBook = null
                    infoBook?.let { currentRoute = IosRoute.BOOK_INFO }
                        ?: run { currentRoute = IosRoute.BOOKSHELF }
                },
                onChangeSource = { _, _, _ ->
                    // 换源执行后返回详情页 (迁移逻辑后续接入)
                    changeSourceBook = null
                    infoBook?.let { currentRoute = IosRoute.BOOK_INFO }
                        ?: run { currentRoute = IosRoute.BOOKSHELF }
                },
            )
        }

        IosRoute.READ_ALOUD_CONFIG -> IosReadAloudConfigScreen(
            onBack = { currentRoute = IosRoute.READER },
        )

        // ===== 发现 (2 个: 发现列表 + 发现结果) =====

        IosRoute.EXPLORE -> IosExploreScreen(
            onBack = { currentRoute = IosRoute.BOOKSHELF },
            onOpenExplore = { source, title, exploreUrl ->
                exploreShowSource = source
                exploreShowTitle = title
                exploreShowUrl = exploreUrl
                currentRoute = IosRoute.EXPLORE_SHOW
            },
            onSearchBook = { currentRoute = IosRoute.SEARCH },
        )

        IosRoute.EXPLORE_SHOW -> exploreShowSource?.let { source ->
            IosExploreShowScreen(
                source = source,
                title = exploreShowTitle,
                exploreUrl = exploreShowUrl,
                onBack = {
                    exploreShowSource = null
                    exploreShowTitle = ""
                    exploreShowUrl = null
                    currentRoute = IosRoute.EXPLORE
                },
                onBookClick = { searchBook ->
                    infoBook = searchBook
                    currentRoute = IosRoute.BOOK_INFO
                },
            )
        }

        // ===== RSS (3 个, 由 MY_CONFIG 的 onRssSources 入口, 对照 desktop DesktopApp RSS_SOURCES) =====

        IosRoute.RSS_SOURCES -> IosRssSourcesScreen(
            onBack = { currentRoute = IosRoute.MY_CONFIG },
            onRssSourceClick = { book ->
                rssSourceBook = book
                currentRoute = IosRoute.RSS_ARTICLES
            },
        )

        IosRoute.RSS_ARTICLES -> rssSourceBook?.let { book ->
            IosRssArticlesScreen(
                book = book,
                onBack = {
                    rssSourceBook = null
                    currentRoute = IosRoute.RSS_SOURCES
                },
                onArticleClick = { chapterIndex ->
                    rssChapterIndex = chapterIndex
                    currentRoute = IosRoute.READ_RSS
                },
            )
        }

        IosRoute.READ_RSS -> rssSourceBook?.let { book ->
            IosReadRssScreen(
                book = book,
                chapterIndex = rssChapterIndex,
                onBack = { currentRoute = IosRoute.RSS_ARTICLES },
            )
        }
    }

    // legado:// deep link 导入宿主 (与路由 when 平级, 覆盖在任意路由之上弹对话框)
    // 投递侧: iOSApp.swift onOpenURL → handleLegadoDeepLink → LegadoDeepLinkHandler.pending
    DeepLinkImportHost()
}

/**
 * iOS 端子路由枚举 (KP5: 30 路由, KP6: +3 RSS, KP7: +发现列表 = 34 路由, 对照 desktop `DesktopRoute` 子集)。
 *
 * Dialog/overlay 包装 (IosEffectiveReplacesScreen / IosBookSourceDebugScreen /
 * IosBookSourceEditScreen / IosSearchContentScreen / IosChangeBookSourceScreen /
 * IosChangeChapterSourceScreen) 由父 Screen 内部管理, 不走路由。
 */
enum class IosRoute {
    /** 书架 (KP3 已接入) */
    BOOKSHELF,
    /** 阅读页 (KP4: 包装 shared ReadViewComposable) */
    READER,
    /** 搜索页 (KP4: 包装 shared SearchScreen) */
    SEARCH,
    /** 详情页 (KP4: 包装 shared BookInfoScreen) */
    BOOK_INFO,
    /** 书源管理页 (KP4: 包装 shared BookSourceListScreen) */
    BOOK_SOURCE,

    /** 我的设置页 (KP5: 包装 shared MyConfigScreen, 入口为书架溢出菜单"我的") */
    MY_CONFIG,
    /** 主题设置 (KP5: 包装 shared ThemeConfigScreen) */
    THEME_CONFIG,
    /** 封面设置 (KP5: 包装 shared CoverConfigScreen) */
    COVER_CONFIG,
    /** 启动界面设置 (KP5: 包装 shared WelcomeConfigScreen) */
    WELCOME_CONFIG,
    /** 备份设置 (KP5: 包装 shared BackupConfigScreen) */
    BACKUP_CONFIG,
    /** 其它设置 (KP5: 包装 shared OtherConfigScreen) */
    OTHER_CONFIG,

    /** 关于 (KP5: 包装 shared AboutScreen) */
    ABOUT,
    /** 阅读记录 (KP5: 包装 shared ReadRecordScreen) */
    READ_RECORD,
    /** 规则订阅 (KP5: 包装 shared RuleSubScreen) */
    RULE_SUB,
    /** 书签 (KP5: 包装 shared AllBookmarkScreen) */
    BOOKMARK,

    /** 书源过滤规则 (KP5: 包装 shared SourceFilterRuleScreen) */
    SOURCE_FILTER_RULE,
    /** TXT 目录规则 (KP5: 包装 shared TxtTocRuleScreen) */
    TXT_TOC_RULE,
    /** 字典规则 (KP5: 包装 shared DictRuleScreen) */
    DICT_RULE,
    /** 替换净化规则 (KP5: 包装 shared ReplaceRuleListScreen) */
    REPLACE_RULE,
    /** 替换规则编辑 (KP5: 包装 shared ReplaceEditScreen) */
    REPLACE_EDIT,

    /** 书架管理 (KP5: 包装 shared BookshelfManageScreen) */
    BOOKSHELF_MANAGE,
    /** 导入本地书籍 (KP5: 包装 shared ImportBookScreen) */
    IMPORT_BOOK,
    /** 远程书籍 (KP5: 包装 shared RemoteBookScreen) */
    REMOTE_BOOK,
    /** 书籍信息编辑 (KP5: 包装 shared BookInfoEditScreen) */
    BOOK_INFO_EDIT,
    /** 目录页 (KP5: 包装 shared TocScreen) */
    TOC,
    /** 整书换源 (包装 IosChangeBookSourceScreen, 详情 onOriginClick 触发) */
    CHANGE_SOURCE,

    /** 音频播放 (KP5: 包装 shared AudioPlayScreenContent) */
    AUDIO_PLAY,
    /** 漫画阅读 (KP5: 包装 shared MangaReaderScreenContent) */
    MANGA_READER,
    /** 视频播放 (KP5: 包装 shared VideoPlayerScreenContent) */
    VIDEO_PLAYER,
    /** 朗读设置 (KP5: 包装 shared ReadAloudConfigScreen) */
    READ_ALOUD_CONFIG,

    /** 发现列表 (KP7: 包装 shared ExploreScreen, 入口为书架溢出菜单"发现") */
    EXPLORE,
    /** 发现结果 (KP5: 包装 shared ExploreShowScreen, 由 EXPLORE 点击分类/收藏项进入) */
    EXPLORE_SHOW,

    /** RSS 源列表 (KP6: 包装 shared/commonMain RssSourcesViewModelShared) */
    RSS_SOURCES,
    /** RSS 文章列表 (KP6: 包装 shared/commonMain RssArticlesViewModelShared) */
    RSS_ARTICLES,
    /** RSS 文章阅读 (KP6: 包装 shared/commonMain ReadRssViewModelShared) */
    READ_RSS,
}
