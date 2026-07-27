package io.legado.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.ui.about.OhosAboutScreen
import io.legado.app.ui.about.OhosReadRecordScreen
import io.legado.app.ui.association.OhosAssociationScreen
import io.legado.app.ui.association.OhosJsScreen
import io.legado.app.ui.association.OhosRuleSubScreen
import io.legado.app.ui.book.audio.OhosAudioPlayScreen
import io.legado.app.ui.book.bookmark.OhosBookmarkScreen
import io.legado.app.ui.book.changesource.OhosChangeBookSourceScreen
import io.legado.app.ui.book.changesource.OhosChangeChapterSourceScreen
import io.legado.app.ui.book.explore.OhosExploreShowScreen
import io.legado.app.ui.book.filter.OhosSourceFilterRuleScreen
import io.legado.app.ui.book.import.local.OhosImportBookScreen
import io.legado.app.ui.book.import.remote.OhosRemoteBookScreen
import io.legado.app.ui.book.manage.OhosBookshelfManageScreen
import io.legado.app.ui.book.manga.OhosMangaReaderScreen
import io.legado.app.ui.book.review.OhosReviewPostScreen
import io.legado.app.ui.book.search.OhosSearchScreen
import io.legado.app.ui.book.searchcontent.OhosSearchContentScreen
import io.legado.app.ui.book.toc.OhosTocScreen
import io.legado.app.ui.book.toc.rule.OhosTxtTocRuleScreen
import io.legado.app.ui.book.video.OhosVideoPlayerScreen
import io.legado.app.ui.bookinfo.OhosBookInfoEditScreen
import io.legado.app.ui.bookinfo.OhosBookInfoScreen
import io.legado.app.ui.booksource.OhosBookSourceEditScreen
import io.legado.app.ui.booksource.OhosBookSourceScreen
import io.legado.app.ui.booksource.debug.OhosBookSourceDebugScreen
import io.legado.app.ui.browser.OhosWebViewScreen
import io.legado.app.ui.config.OhosBackupConfigScreen
import io.legado.app.ui.config.OhosCoverConfigScreen
import io.legado.app.ui.config.OhosOtherConfigScreen
import io.legado.app.ui.config.OhosReadConfigScreen
import io.legado.app.ui.config.OhosThemeConfigScreen
import io.legado.app.ui.config.OhosWelcomeConfigScreen
import io.legado.app.ui.dict.rule.OhosDictRuleScreen
import io.legado.app.ui.login.OhosLoginScreen
import io.legado.app.ui.replace.OhosReplaceEditScreen
import io.legado.app.ui.replace.OhosReplaceRuleScreen
import io.legado.app.ui.rss.OhosReadRssScreen
import io.legado.app.ui.rss.OhosRssArticlesScreen
import io.legado.app.ui.rss.OhosRssSourcesScreen

/**
 * 鸿蒙端路由宿主 (对标 iOS `IosNavHost` / desktop `DesktopApp` 的 when(currentRoute) 模式)。
 *
 * # 路由状态
 *
 * - [currentRoute]: 当前显示的子路由 (默认 [OhosRoute.INDEX])
 * - [readerBookUrl]/[readerBookName]/[readerChapterIndex]: READER 路由消费 (OhosReaderScreen 接收原始值)
 * - [infoBook]: BOOK_INFO 路由消费, 书架长按 / 搜索点击触发
 * - [editBookUrl]: BOOK_INFO_EDIT 路由消费, 详情 onEditClick 触发
 * - [searchContentBook]: SEARCH_CONTENT 路由消费, 阅读页 onSearchContentClick 触发
 * - [tocBook]: TOC 路由消费, 详情 onTocClick 触发
 * - [changeSourceBook]: CHANGE_SOURCE 路由消费, 详情 onOriginClick 触发
 * - [editSourceUrl]: BOOK_SOURCE_EDIT 路由消费
 * - [exploreShowSourceUrl]: EXPLORE_SHOW 路由消费 (OhosExploreTab.onOpenExplore 触发)
 * - [bookSourceDebugUrl]: BOOK_SOURCE_DEBUG 路由消费 (OhosBookSourceScreen.onDebug 触发)
 * - [editRuleId]: REPLACE_EDIT 路由消费 (-1 = 新增)
 * - [rssSourceBook]/[rssChapterIndex]: RSS_ARTICLES / READ_RSS 路由消费
 * - [loginSource]: LOGIN 路由消费 (源要求登录时由业务层设置后跳转)
 * - [webViewUrl]/[webViewTitle]: WEB_VIEW 路由消费 (URL 登录场景由 OhosLoginScreen.onOpenWebView 触发)
 * - [reviewPostBook]: REVIEW_POST 路由消费 (详情 onOpenCommentDialog 触发, 携带 Book)
 * - [associationType]: ASSOCIATION 路由消费 (区分书源/订阅源/替换规则等导入类型)
 * - [jsCode]: JS_EDIT 路由消费 (待运行的 JS 代码片段, 由书源调试等场景传入)
 *
 * # 与 iOS IosNavHost 的差异
 *
 * - iOS 用 Book 对象驱动 READER; 鸿蒙 OhosReaderScreen 接收 bookUrl/bookName/chapterIndex 原始值
 * - iOS 媒体路由 (AUDIO_PLAY/MANGA_READER/VIDEO_PLAYER) 接收 Book; 鸿蒙对应 Screen 无 book 参数 (stub),
 *   onReadClick 统一路由到 READER (媒体路由注册但暂不由 BOOK_INFO 分流触发)
 * - iOS 注入 4 个 CompositionLocal Provider; 鸿蒙 OhosXxxScreen 直接调 AppDbProviders 等单例, 无需注入
 */
@Composable
fun OhosNavHost() {
    var currentRoute by remember { mutableStateOf(OhosRoute.INDEX) }

    // READER 路由状态 (OhosReaderScreen 接收原始值, 非 Book 对象)
    var readerBookUrl by remember { mutableStateOf("") }
    var readerBookName by remember { mutableStateOf("") }
    var readerChapterIndex by remember { mutableStateOf(0) }

    // BOOK_INFO 路由状态 (书架长按 / 搜索点击 / 书架管理点击触发)
    var infoBook by remember { mutableStateOf<BaseBook?>(null) }

    // BOOK_INFO_EDIT 路由状态 (详情 onEditClick 触发, 携带 bookUrl)
    var editBookUrl by remember { mutableStateOf("") }

    // SEARCH_CONTENT 路由状态 (阅读页 onSearchContentClick 触发, 携带 Book)
    var searchContentBook by remember { mutableStateOf<Book?>(null) }

    // TOC 路由状态 (详情 onTocClick 触发)
    var tocBook by remember { mutableStateOf<Book?>(null) }

    // CHANGE_SOURCE 路由状态 (详情 onOriginClick 触发)
    var changeSourceBook by remember { mutableStateOf<Book?>(null) }

    // CHANGE_CHAPTER_SOURCE 路由状态 (阅读页 CHAPTER_CHANGE_SOURCE 触发)
    var changeChapterSourceBook by remember { mutableStateOf<Book?>(null) }
    var changeChapterSourceIndex by remember { mutableStateOf(0) }
    var changeChapterSourceTitle by remember { mutableStateOf("") }

    // BOOK_SOURCE_EDIT 路由状态
    var editSourceUrl by remember { mutableStateOf("") }

    // EXPLORE_SHOW 路由状态 (OhosExploreTab.onOpenExplore 触发, 携带 sourceUrl)
    var exploreShowSourceUrl by remember { mutableStateOf("") }

    // BOOK_SOURCE_DEBUG 路由状态 (OhosBookSourceScreen.onDebug 触发, 携带 sourceUrl)
    var bookSourceDebugUrl by remember { mutableStateOf("") }

    // REPLACE_EDIT 路由状态 (-1 = 新增)
    var editRuleId by remember { mutableStateOf(-1L) }

    // RSS 流状态 (RSS_SOURCES 点击源 → RSS_ARTICLES → READ_RSS)
    var rssSourceBook by remember { mutableStateOf<Book?>(null) }
    var rssChapterIndex by remember { mutableStateOf(0) }

    // LOGIN 路由状态 (源要求登录时由业务层设置后跳转)
    var loginSource by remember { mutableStateOf<BaseSource?>(null) }

    // ASSOCIATION 路由状态 (关联导入类型, 区分书源/订阅源/替换规则等)
    var associationType by remember { mutableStateOf(0) }

    // JS_EDIT 路由状态 (待运行的 JS 代码片段, 由书源调试等场景传入)
    var jsCode by remember { mutableStateOf("") }

    // WEB_VIEW 路由状态 (URL 登录场景由 OhosLoginScreen.onOpenWebView 触发, 携带 url)
    var webViewUrl by remember { mutableStateOf("") }
    var webViewTitle by remember { mutableStateOf("") }

    // REVIEW_POST 路由状态 (详情 onOpenCommentDialog 触发, 携带 Book)
    var reviewPostBook by remember { mutableStateOf<Book?>(null) }

    when (currentRoute) {
        // ===== 主框架 (4 tab 容器 + 直接 tab 路由) =====

        OhosRoute.INDEX -> OhosIndexScreen(
            onOpenReader = { bookUrl, bookName, chapterIndex ->
                readerBookUrl = bookUrl
                readerBookName = bookName
                readerChapterIndex = chapterIndex
                currentRoute = OhosRoute.READER
            },
            onOpenExplore = { sourceUrl ->
                // 发现结果页: 跳 EXPLORE_SHOW 路由 (由 OhosExploreShowScreen 包装 shared)
                exploreShowSourceUrl = sourceUrl
                currentRoute = OhosRoute.EXPLORE_SHOW
            },
            onMyItemClick = { title -> currentRoute = myItemRoute(title) ?: return@OhosIndexScreen },
        )

        OhosRoute.BOOKSHELF -> OhosBookshelfTab(
            onOpenReader = { bookUrl, bookName, chapterIndex ->
                readerBookUrl = bookUrl
                readerBookName = bookName
                readerChapterIndex = chapterIndex
                currentRoute = OhosRoute.READER
            },
        )

        OhosRoute.EXPLORE -> OhosExploreTab(
            onOpenExplore = { sourceUrl ->
                // 发现结果页: 跳 EXPLORE_SHOW 路由 (由 OhosExploreShowScreen 包装 shared)
                exploreShowSourceUrl = sourceUrl
                currentRoute = OhosRoute.EXPLORE_SHOW
            },
        )

        OhosRoute.MY -> OhosMyTab(
            onItemClick = { title -> currentRoute = myItemRoute(title) ?: return@OhosMyTab },
        )

        // ===== 阅读流核心 =====

        OhosRoute.READER -> OhosReaderScreen(
            bookUrl = readerBookUrl,
            bookName = readerBookName,
            initialChapterIndex = readerChapterIndex,
            onBack = { currentRoute = OhosRoute.INDEX },
            onChapterChangeSource = { book, index, title ->
                changeChapterSourceBook = book
                changeChapterSourceIndex = index
                changeChapterSourceTitle = title
                currentRoute = OhosRoute.CHANGE_CHAPTER_SOURCE
            },
            onSearchContentClick = { book ->
                searchContentBook = book
                currentRoute = OhosRoute.SEARCH_CONTENT
            },
        )

        OhosRoute.BOOK_INFO -> infoBook?.let { book ->
            OhosBookInfoScreen(
                book = book,
                onBack = {
                    infoBook = null
                    currentRoute = OhosRoute.INDEX
                },
                onReadClick = { readBook ->
                    infoBook = null
                    readerBookUrl = readBook.bookUrl
                    readerBookName = readBook.name
                    readerChapterIndex = readBook.durChapterIndex
                    currentRoute = OhosRoute.READER
                },
                onEditClick = { bookUrl ->
                    editBookUrl = bookUrl
                    currentRoute = OhosRoute.BOOK_INFO_EDIT
                },
                onOriginClick = { originBook ->
                    changeSourceBook = originBook
                    currentRoute = OhosRoute.CHANGE_SOURCE
                },
                onTocClick = { tocBookArg ->
                    tocBook = tocBookArg
                    currentRoute = OhosRoute.TOC
                },
                onOpenReviewPost = { reviewBook ->
                    // 详情 "发布书评" 菜单入口 (sourceHasReviewRule=true 时显示), 切到 REVIEW_POST 路由
                    reviewPostBook = reviewBook
                    currentRoute = OhosRoute.REVIEW_POST
                },
            )
        }

        OhosRoute.BOOK_INFO_EDIT -> OhosBookInfoEditScreen(
            bookUrl = editBookUrl,
            onBack = {
                editBookUrl = ""
                currentRoute = OhosRoute.BOOK_INFO
            },
            onSaved = {
                editBookUrl = ""
                currentRoute = OhosRoute.BOOK_INFO
            },
        )

        OhosRoute.SEARCH -> OhosSearchScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
            onBookClick = { book ->
                infoBook = book
                currentRoute = OhosRoute.BOOK_INFO
            },
            onManageBookSources = { currentRoute = OhosRoute.BOOK_SOURCE },
        )

        OhosRoute.TOC -> tocBook?.let { book ->
            OhosTocScreen(
                book = book,
                onBack = {
                    tocBook = null
                    infoBook?.let { currentRoute = OhosRoute.BOOK_INFO }
                        ?: run { currentRoute = OhosRoute.INDEX }
                },
                onChapterClick = { chapterIndex ->
                    // 跳阅读页并定位章节
                    readerBookUrl = book.bookUrl
                    readerBookName = book.name
                    readerChapterIndex = chapterIndex
                    tocBook = null
                    currentRoute = OhosRoute.READER
                },
            )
        }

        OhosRoute.SEARCH_CONTENT -> searchContentBook?.let { book ->
            OhosSearchContentScreen(
                book = book,
                onBack = {
                    searchContentBook = null
                    currentRoute = OhosRoute.READER
                },
                onChapterClick = { chapterIndex ->
                    // 跳阅读页并定位章节
                    readerBookUrl = book.bookUrl
                    readerBookName = book.name
                    readerChapterIndex = chapterIndex
                    searchContentBook = null
                    currentRoute = OhosRoute.READER
                },
            )
        }

        // ===== 书源管理 =====

        OhosRoute.BOOK_SOURCE -> OhosBookSourceScreen(
            onSearchBook = { _ -> currentRoute = OhosRoute.SEARCH },
            onBack = { currentRoute = OhosRoute.INDEX },
            onDebug = { sourceUrl ->
                // 书源调试: 跳 BOOK_SOURCE_DEBUG 路由 (由 OhosBookSourceDebugScreen 包装 shared)
                bookSourceDebugUrl = sourceUrl
                currentRoute = OhosRoute.BOOK_SOURCE_DEBUG
            },
        )

        OhosRoute.BOOK_SOURCE_EDIT -> OhosBookSourceEditScreen(
            sourceUrl = editSourceUrl,
            onBack = {
                editSourceUrl = ""
                currentRoute = OhosRoute.BOOK_SOURCE
            },
            onSaved = {
                editSourceUrl = ""
                currentRoute = OhosRoute.BOOK_SOURCE
            },
        )

        // ===== 发现结果页 (EXPLORE tab 点击源触发) =====

        OhosRoute.EXPLORE_SHOW -> OhosExploreShowScreen(
            sourceUrl = exploreShowSourceUrl,
            onBack = {
                exploreShowSourceUrl = ""
                currentRoute = OhosRoute.INDEX
            },
            onBookClick = { book ->
                infoBook = book
                currentRoute = OhosRoute.BOOK_INFO
            },
        )

        // ===== 书源调试 (BOOK_SOURCE 列表项 / 编辑页触发) =====

        OhosRoute.BOOK_SOURCE_DEBUG -> OhosBookSourceDebugScreen(
            sourceUrl = bookSourceDebugUrl,
            onBack = {
                bookSourceDebugUrl = ""
                currentRoute = OhosRoute.BOOK_SOURCE
            },
        )

        // ===== 媒体播放 (stub, 暂不由 BOOK_INFO 分流触发) =====

        OhosRoute.AUDIO_PLAY -> OhosAudioPlayScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.VIDEO_PLAYER -> OhosVideoPlayerScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.MANGA_READER -> OhosMangaReaderScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        // ===== 书架子页 =====

        OhosRoute.BOOKSHELF_MANAGE -> OhosBookshelfManageScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
            onOpenBook = { book ->
                infoBook = book
                currentRoute = OhosRoute.BOOK_INFO
            },
        )

        OhosRoute.CHANGE_SOURCE -> changeSourceBook?.let { book ->
            OhosChangeBookSourceScreen(
                book = book,
                onBack = {
                    changeSourceBook = null
                    infoBook?.let { currentRoute = OhosRoute.BOOK_INFO }
                        ?: run { currentRoute = OhosRoute.INDEX }
                },
                onChangeSource = { _, _, _ ->
                    // 换源执行后返回详情页 (迁移逻辑后续接入)
                    changeSourceBook = null
                    infoBook?.let { currentRoute = OhosRoute.BOOK_INFO }
                        ?: run { currentRoute = OhosRoute.INDEX }
                },
            )
        }

        OhosRoute.CHANGE_CHAPTER_SOURCE -> changeChapterSourceBook?.let { book ->
            OhosChangeChapterSourceScreen(
                book = book,
                chapterIndex = changeChapterSourceIndex,
                chapterTitle = changeChapterSourceTitle,
                onBack = {
                    changeChapterSourceBook = null
                    currentRoute = OhosRoute.READER
                },
                onReplaceContent = { _ ->
                    // 章节换源正文替换逻辑后续接入 (对照 iOS IosReaderScreen.onReplaceContent)
                    changeChapterSourceBook = null
                    currentRoute = OhosRoute.READER
                },
            )
        }

        OhosRoute.IMPORT_BOOK -> OhosImportBookScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.REMOTE_BOOK -> OhosRemoteBookScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        // ===== 配置路由 (由 MY tab 入口跳转) =====

        OhosRoute.THEME_CONFIG -> OhosThemeConfigScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
            onCoverConfig = { currentRoute = OhosRoute.COVER_CONFIG },
            onWelcomeStyle = { currentRoute = OhosRoute.WELCOME_CONFIG },
        )

        OhosRoute.COVER_CONFIG -> OhosCoverConfigScreen(
            onBack = { currentRoute = OhosRoute.THEME_CONFIG },
        )

        OhosRoute.WELCOME_CONFIG -> OhosWelcomeConfigScreen(
            onBack = { currentRoute = OhosRoute.THEME_CONFIG },
        )

        OhosRoute.READ_CONFIG -> OhosReadConfigScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.OTHER_CONFIG -> OhosOtherConfigScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.BACKUP_CONFIG -> OhosBackupConfigScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.ABOUT -> OhosAboutScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.READ_RECORD -> OhosReadRecordScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
            onOpenBook = { book ->
                // 点击记录行跳转阅读 (对照 iOS IosNavHost READ_RECORD 路由)
                readerBookUrl = book.bookUrl
                readerBookName = book.name
                readerChapterIndex = book.durChapterIndex
                currentRoute = OhosRoute.READER
            },
        )

        // ===== 规则管理 (由 MY tab 入口跳转) =====

        OhosRoute.REPLACE_RULE -> OhosReplaceRuleScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
            onAddRule = {
                editRuleId = -1L
                currentRoute = OhosRoute.REPLACE_EDIT
            },
            onEditRule = { id ->
                editRuleId = id
                currentRoute = OhosRoute.REPLACE_EDIT
            },
        )

        OhosRoute.REPLACE_EDIT -> OhosReplaceEditScreen(
            ruleId = editRuleId,
            onBack = {
                editRuleId = -1L
                currentRoute = OhosRoute.REPLACE_RULE
            },
            onSaved = {
                editRuleId = -1L
                currentRoute = OhosRoute.REPLACE_RULE
            },
        )

        OhosRoute.SOURCE_FILTER_RULE -> OhosSourceFilterRuleScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.TXT_TOC_RULE -> OhosTxtTocRuleScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.DICT_RULE -> OhosDictRuleScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.RULE_SUB -> OhosRuleSubScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        OhosRoute.BOOKMARK -> OhosBookmarkScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
            onOpenBookmark = { book, chapterIndex, _ ->
                // 点击书签跳转阅读
                readerBookUrl = book.bookUrl
                readerBookName = book.name
                readerChapterIndex = chapterIndex
                currentRoute = OhosRoute.READER
            },
        )

        // ===== RSS 流 (RSS_SOURCES → RSS_ARTICLES → READ_RSS) =====

        OhosRoute.RSS_SOURCES -> OhosRssSourcesScreen(
            onBack = { currentRoute = OhosRoute.INDEX },
            onRssSourceClick = { book ->
                rssSourceBook = book
                currentRoute = OhosRoute.RSS_ARTICLES
            },
        )

        OhosRoute.RSS_ARTICLES -> rssSourceBook?.let { book ->
            OhosRssArticlesScreen(
                book = book,
                onBack = {
                    rssSourceBook = null
                    currentRoute = OhosRoute.RSS_SOURCES
                },
                onArticleClick = { chapterIndex ->
                    rssChapterIndex = chapterIndex
                    currentRoute = OhosRoute.READ_RSS
                },
            )
        }

        OhosRoute.READ_RSS -> rssSourceBook?.let { book ->
            OhosReadRssScreen(
                book = book,
                chapterIndex = rssChapterIndex,
                onBack = { currentRoute = OhosRoute.RSS_ARTICLES },
            )
        }

        // ===== 登录 (源要求登录时由业务层设置 loginSource 后跳转) =====

        OhosRoute.LOGIN -> loginSource?.let { source ->
            OhosLoginScreen(
                source = source,
                onDismiss = {
                    loginSource = null
                    currentRoute = OhosRoute.INDEX
                },
                onOpenWebView = { url ->
                    // URL 登录场景切到 WEB_VIEW 路由 (OhosLoginScreen 内 loginUi 空 + loginUrl 非空触发)
                    webViewUrl = url
                    webViewTitle = url
                    loginSource = null
                    currentRoute = OhosRoute.WEB_VIEW
                },
            )
        }

        // ===== 内置浏览器 (URL 登录 / 源验证场景, stub 待接入 @ohos.web.webview) =====

        OhosRoute.WEB_VIEW -> OhosWebViewScreen(
            url = webViewUrl,
            onBack = {
                webViewUrl = ""
                webViewTitle = ""
                currentRoute = OhosRoute.INDEX
            },
            onTitleChanged = { title ->
                webViewTitle = title
            },
        )

        // ===== 发布书评 (详情 onOpenCommentDialog 触发, stub 待接入 reviewRule.replyRule) =====

        OhosRoute.REVIEW_POST -> reviewPostBook?.let { book ->
            OhosReviewPostScreen(
                book = book,
                onBack = {
                    reviewPostBook = null
                    currentRoute = OhosRoute.BOOK_INFO
                },
                onPosted = {
                    // 真实提交成功后切回详情页 (stub 阶段 OhosReviewPostScreen 不调用)
                    reviewPostBook = null
                    currentRoute = OhosRoute.BOOK_INFO
                },
            )
        }

        // ===== 关联导入 (外部文件/deep link 导入书源/订阅源/替换规则等) =====

        OhosRoute.ASSOCIATION -> OhosAssociationScreen(
            type = associationType,
            onBack = { currentRoute = OhosRoute.INDEX },
        )

        // ===== JS 编辑 (书源调试中运行 JS 代码) =====

        OhosRoute.JS_EDIT -> OhosJsScreen(
            jsCode = jsCode,
            onBack = {
                jsCode = ""
                currentRoute = OhosRoute.INDEX
            },
        )
    }
}

/**
 * 鸿蒙端子路由枚举 (43 路由, 对照 iOS `IosRoute` 子集 + 鸿蒙端 INDEX 主框架路由)。
 *
 * Dialog/overlay 包装 (OhosCrashLogsDialog 等) 由父 Screen 内部管理, 不走路由。
 */
enum class OhosRoute {
    /** 主框架 (4 tab 容器: Home/Bookshelf/Explore/My) */
    INDEX,
    /** 书架 tab (独立路由, 通常作为 INDEX 内嵌 tab) */
    BOOKSHELF,
    /** 发现 tab */
    EXPLORE,
    /** 我的 tab */
    MY,

    /** 阅读页 */
    READER,
    /** 书籍详情页 */
    BOOK_INFO,
    /** 书籍信息编辑页 */
    BOOK_INFO_EDIT,
    /** 搜索页 */
    SEARCH,
    /** 书内全文搜索 */
    SEARCH_CONTENT,
    /** 目录页 */
    TOC,

    /** 书源管理页 */
    BOOK_SOURCE,
    /** 书源编辑页 */
    BOOK_SOURCE_EDIT,
    /** 书源调试页 */
    BOOK_SOURCE_DEBUG,
    /** 发现结果页 (EXPLORE tab 点击源触发) */
    EXPLORE_SHOW,

    /** 音频播放 */
    AUDIO_PLAY,
    /** 视频播放 */
    VIDEO_PLAYER,
    /** 漫画阅读 */
    MANGA_READER,

    /** 书架管理 */
    BOOKSHELF_MANAGE,
    /** 换源 */
    CHANGE_SOURCE,
    /** 章节换源 */
    CHANGE_CHAPTER_SOURCE,
    /** 导入本地书籍 */
    IMPORT_BOOK,
    /** 远程书籍 */
    REMOTE_BOOK,
    /** 发布书评 (详情 onOpenCommentDialog 触发, stub 待接入 reviewRule.replyRule) */
    REVIEW_POST,

    /** 主题设置 */
    THEME_CONFIG,
    /** 封面设置 */
    COVER_CONFIG,
    /** 启动界面 (欢迎页) 设置 */
    WELCOME_CONFIG,
    /** 朗读/阅读配置 */
    READ_CONFIG,
    /** 其他设置 */
    OTHER_CONFIG,
    /** 备份/恢复 */
    BACKUP_CONFIG,
    /** 关于 */
    ABOUT,
    /** 阅读记录 (阅读时长统计) */
    READ_RECORD,

    /** 替换净化规则 */
    REPLACE_RULE,
    /** 替换规则编辑 */
    REPLACE_EDIT,
    /** 书源过滤规则 */
    SOURCE_FILTER_RULE,
    /** TXT 目录规则 */
    TXT_TOC_RULE,
    /** 字典规则 */
    DICT_RULE,
    /** 规则订阅 */
    RULE_SUB,
    /** 书签 */
    BOOKMARK,

    /** RSS 源列表 */
    RSS_SOURCES,
    /** RSS 文章列表 */
    RSS_ARTICLES,
    /** RSS 文章阅读 */
    READ_RSS,

    /** 登录页 (源要求登录时触发) */
    LOGIN,
    /** 内置浏览器 (URL 登录 / 源验证场景, stub 待接入 @ohos.web.webview) */
    WEB_VIEW,
    /** 关联导入 (外部文件/deep link 导入书源/订阅源/替换规则等) */
    ASSOCIATION,
    /** JS 编辑 (书源调试中运行 JS 代码) */
    JS_EDIT,
}

/** My tab 入口标题 → 路由映射 (对齐 OhosMyTab myTabItems 顺序)。 */
private fun myItemRoute(title: String): OhosRoute? = when (title) {
    "书源管理" -> OhosRoute.BOOK_SOURCE
    "订阅源管理" -> OhosRoute.RSS_SOURCES
    "替换规则" -> OhosRoute.REPLACE_RULE
    "备份/恢复" -> OhosRoute.BACKUP_CONFIG
    "主题设置" -> OhosRoute.THEME_CONFIG
    "其他设置" -> OhosRoute.OTHER_CONFIG
    "阅读记录" -> OhosRoute.READ_RECORD
    "关于" -> OhosRoute.ABOUT
    else -> null
}
