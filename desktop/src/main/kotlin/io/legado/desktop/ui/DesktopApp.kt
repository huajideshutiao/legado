package io.legado.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.desktop.help.book.DesktopBookshelfManagePlatform
import io.legado.desktop.ui.about.AboutScreen
import io.legado.desktop.ui.about.ReadRecordScreen
import io.legado.desktop.ui.association.RuleSubScreen
import io.legado.desktop.ui.book.audio.AudioPlayScreen
import io.legado.desktop.ui.book.bookmark.AllBookmarkScreen
import io.legado.desktop.ui.book.changesource.ChangeSourceScreen
import io.legado.desktop.ui.book.filter.SourceFilterRuleScreen
import io.legado.desktop.ui.book.import.local.ImportBookScreen
import io.legado.desktop.ui.book.import.remote.RemoteBookScreen
import io.legado.desktop.ui.book.manga.MangaReaderScreen
import io.legado.desktop.ui.book.read.EffectiveReplacesScreen
import io.legado.desktop.ui.book.read.ReviewListScreen
import io.legado.desktop.ui.book.video.VideoPlayerScreen
import io.legado.desktop.ui.book.read.config.MoreConfigScreen
import io.legado.desktop.ui.book.read.config.PaddingConfigScreen
import io.legado.desktop.ui.book.read.config.ReadAloudConfigScreen
import io.legado.desktop.ui.book.read.config.TipConfigScreen
import io.legado.desktop.ui.book.toc.TocScreen
import io.legado.desktop.ui.book.toc.TxtTocRuleScreen
import io.legado.desktop.ui.bookinfo.BookInfoEditScreen
import io.legado.desktop.ui.bookinfo.BookInfoScreen
import io.legado.desktop.ui.bookshelf.BookshelfManageScreen
import io.legado.desktop.ui.bookshelf.BookshelfScreen
import io.legado.desktop.ui.booksource.BookSourceDebugScreen
import io.legado.desktop.ui.booksource.BookSourceEditScreen
import io.legado.desktop.ui.booksource.BookSourceScreen
import io.legado.desktop.ui.config.BackupConfigScreen
import io.legado.desktop.ui.config.CoverConfigScreen
import io.legado.desktop.ui.config.DesktopOtherConfigScreen
import io.legado.desktop.ui.config.DesktopThemeConfigScreen
import io.legado.desktop.ui.config.WelcomeConfigScreen
import io.legado.desktop.ui.dict.DictRuleScreen
import io.legado.desktop.ui.explore.ExploreScreen
import io.legado.desktop.ui.explore.ExploreShowScreen
import io.legado.desktop.ui.main.home.DesktopHomeScreen
import io.legado.desktop.ui.my.MyScreen
import io.legado.desktop.ui.reader.ReaderScreen
import io.legado.desktop.ui.replace.ReplaceEditScreen
import io.legado.desktop.ui.replace.ReplaceRuleScreen
import io.legado.desktop.ui.rss.RssArticlesScreen
import io.legado.desktop.ui.rss.ReadRssScreen
import io.legado.desktop.ui.rss.RssSourcesScreen
import io.legado.desktop.ui.search.SearchContentScreen
import io.legado.desktop.ui.search.SearchScreen
import kotlinx.coroutines.launch

/**
 * 桌面端路由枚举：4 个一级入口 (HOME/BOOKSHELF/DISCOVERY/MY) + 阅读/详情/编辑等子路由。
 *
 * 4 个一级入口与 app 端 [io.legado.app.constant.BottomNavTag] 顺序一致:
 * - HOME 主页
 * - BOOKSHELF 书架
 * - DISCOVERY 发现 (对齐 BottomNavTag.DISCOVERY)
 * - MY 我的 (替换原 SETTINGS, 包装 shared MyConfigScreen)
 *
 * 原 BOOK_SOURCE / SEARCH / REPLACE / WEBDAV 降级为 MY 的子路由 (枚举保留, 由 MyScreen
 * 子项触发), 不再是侧栏一级入口。
 *
 * READER 为 KP2-D 新增: 由书架点击书籍触发, 不在侧栏导航项内。
 * BOOK_INFO / REPLACE_EDIT / OTHER_CONFIG / THEME_CONFIG:
 * - BOOK_INFO: 搜索/书架长按点击书籍 → 详情页 (包装 shared BookInfoScreen)
 * - REPLACE_EDIT: 替换规则列表点击编辑/新增 → 编辑页 (包装 shared ReplaceEditScreen)
 * - OTHER_CONFIG / THEME_CONFIG: MY 页子项 → 对应配置页 (包装 shared 子配置页)
 */
enum class DesktopRoute {
    // 4 个一级入口 (与 app 端 BottomNavTag 顺序一致)
    HOME, BOOKSHELF, DISCOVERY, MY,
    // MY 的子路由 (原一级入口降级, 由 MyScreen 子项触发)
    BOOK_SOURCE, SEARCH, REPLACE, WEBDAV,
    READER,
    // 音频书播放路由 (KP2: 由书架/详情点击 audio 类型书籍触发, 与 READER 平级, 走 AudioPlayScreen)
    AUDIO_PLAYER,
    // 漫画书阅读路由 (P2-5: 由书架/详情点击 image 类型书籍触发, 与 READER 平级, 走 MangaReaderScreen)
    MANGA_READER,
    // 视频书播放路由 (由书架/详情点击 video 类型书籍触发, 与 READER 平级, 走 VideoPlayerScreen)
    // 注: 已接入 vlcj (EmbeddedMediaPlayerComponent 经 SwingPanel 嵌入 AWT 渲染)
    VIDEO_PLAYER,
    BOOK_INFO, REPLACE_EDIT, OTHER_CONFIG, THEME_CONFIG,
    READ_RECORD, BOOK_INFO_EDIT, BOOK_SOURCE_DEBUG,
    BOOK_SOURCE_EDIT, TOC, CHANGE_SOURCE, BOOKSHELF_MANAGE, ABOUT,
    EXPLORE_SHOW,
    // 规则管理类子路由 (由 MY 入口触发, 包装 shared Screen)
    DICT_RULE, TXT_TOC_RULE, RULE_SUB, ALL_BOOKMARK, SOURCE_FILTER_RULE,
    // 配置类子路由 (由 MY 入口触发, 包装 shared Screen)
    BACKUP_CONFIG, SEARCH_CONTENT, WELCOME_CONFIG, COVER_CONFIG,
    // 阅读配置类子路由 (由 MY 入口触发, 包装 shared/sharedUiMain 阅读 config Screen)
    EFFECTIVE_REPLACES, PADDING_CONFIG, TIP_CONFIG, READ_ALOUD_CONFIG, MORE_CONFIG,
    // 段评列表页 (由 ReaderScreen 段评气泡点击触发, 包装 desktop ReviewListScreen, onBack 回 READER)
    REVIEW_LIST,
    // 导入书籍子路由 (由书架顶栏溢出菜单触发, 包装 shared ImportBookScreen / RemoteBookScreen)
    IMPORT_BOOK, REMOTE_BOOK,
    // RSS 阅读子路由 (由 MY 入口 onRssSources / 书架点击 RSS 书 / 文章列表点击文章触发)
    // RSS_SOURCES: RSS 源列表 (订阅 bookDao.flowAll 过滤 isRss)
    // RSS_ARTICLES: RSS 文章列表 (WebBook.getChapterListAwait 拉取)
    // READ_RSS: RSS 文章阅读 (WebBook.getContentAwait 拉取正文, HtmlFormatter 转纯文本显示)
    RSS_SOURCES, RSS_ARTICLES, READ_RSS,
}

/**
 * 桌面端主窗口 Composable：常驻侧边栏 + 右侧主区域。
 *
 * 由 [io.legado.desktop.Main] 通过 `AppTheme { DesktopApp() }` 调用，
 * 已注入 4 个 DesktopXxxProvider，此处可直接消费 LocalXxx 取依赖。
 *
 * 结构：
 * - 侧边栏 (常驻)：[PermanentNavigationDrawer] + [DesktopSideBar] 渲染 4 个一级导航项
 *   (HOME/BOOKSHELF/DISCOVERY/MY)
 * - 右侧主区域：根据 [currentRoute] 显示对应 Screen
 * - 顶部菜单栏：暂未实现，预留扩展位
 *
 * # 路由状态
 * - [currentRoute]: 侧栏一级入口 + 子路由 (BOOK_INFO/REPLACE_EDIT/OTHER_CONFIG/THEME_CONFIG)
 * - [readerBook]: READER 路由消费, 书架 onBookClick 触发后写入
 * - [infoBook]: BOOK_INFO 路由消费, 搜索 onBookClick / 书架 onBookLongClick 触发后写入
 * - [editRuleId]: REPLACE_EDIT 路由消费, 替换规则列表 onEditRule/onAddRule 触发后写入
 *   (-1 表示新增, >0 表示编辑已有规则)
 *
 * 子路由返回逻辑: 子页 onBack 回到调用方路由 (BOOK_INFO → SEARCH/MY, REPLACE_EDIT → REPLACE,
 * OTHER_CONFIG/THEME_CONFIG → MY), 不一律回书架。
 */
@Composable
fun DesktopApp() {
    // 默认路由按 app 端 AppConfig.defaultHomePage 配置决定 (对齐 MainActivity.homePageIndex):
    // home→HOME / bookshelf→BOOKSHELF / explore→DISCOVERY / my→MY / else→BOOKSHELF (默认书架)
    val appConfig = AppConfigProviders.get()
    val initialRoute = when (appConfig.defaultHomePage) {
        "home" -> DesktopRoute.HOME
        "bookshelf" -> DesktopRoute.BOOKSHELF
        "explore" -> DesktopRoute.DISCOVERY
        "my" -> DesktopRoute.MY
        else -> DesktopRoute.BOOKSHELF
    }
    // 侧栏当前选中项, 默认按 app 端 defaultHomePage 配置决定 (不再硬编码 HOME)
    var currentRoute by remember { mutableStateOf(initialRoute) }
    // KP2-D: 当前正在阅读的书籍，由书架 onBookClick 触发后写入，READER 路由分支消费
    // 退出阅读时置回 null，避免 ReaderScreen 在 readerBook=null 时被重组
    var readerBook by remember { mutableStateOf<Book?>(null) }
    // KP2: 当前正在播放的音频书, 由书架/详情 onBookClick (BookType.audio) 触发后写入,
    // AUDIO_PLAYER 路由分支消费; 退出时置回 null 避免下次进入残留
    var audioBook by remember { mutableStateOf<Book?>(null) }
    // P2-5: 当前正在阅读的漫画书, 由书架/详情 onBookClick (BookType.image) 触发后写入,
    // MANGA_READER 路由分支消费; 退出时置回 null 避免下次进入残留
    var mangaBook by remember { mutableStateOf<Book?>(null) }
    // 当前正在播放的视频书, 由书架/详情 onBookClick (BookType.video) 触发后写入,
    // VIDEO_PLAYER 路由分支消费; 退出时置回 null 避免下次进入残留
    // 注: 已接入 vlcj (VideoPlayerScreen 内部用 EmbeddedMediaPlayerComponent 渲染)
    var videoBook by remember { mutableStateOf<Book?>(null) }
    // 详情页书籍: 搜索/书架长按点击后写入, BOOK_INFO 路由消费
    // 用 BaseBook? 统一持有 SearchBook / Book (BookInfoScreen 内部转 Book 使用)
    var infoBook by remember { mutableStateOf<BaseBook?>(null) }
    // 搜索路由预填关键词 (由 BookInfo 详情页 onSearchAuthor/onSearchKind/onNameClick 触发,
    // 对照 app 端 SearchActivity intent 契约 key; null 表示无预填)
    var searchQuery by remember { mutableStateOf<String?>(null) }
    // 搜索路由是否自动提交 (对照 app 端 SearchActivity intent 契约 submit)
    var searchSubmit by remember { mutableStateOf(true) }
    // 替换规则编辑页: 规则 id, -1 表示新增, >0 表示编辑
    var editRuleId by remember { mutableStateOf(-1L) }
    // 书籍信息编辑页: bookUrl, 由 BookInfo 详情页 onEditClick 触发
    var editBookUrl by remember { mutableStateOf("") }
    // 书源调试页: sourceUrl, 由书源管理页 onDebugSource 触发
    var debugSourceUrl by remember { mutableStateOf("") }
    // 书源编辑页: sourceUrl, 由书源管理页 onEditSource 触发
    var editSourceUrl by remember { mutableStateOf("") }
    // 目录页: Book, 由详情页/阅读页触发
    var tocBook by remember { mutableStateOf<Book?>(null) }
    // 换源页: Book, 由详情页 onOriginClick 触发
    var changeSourceBook by remember { mutableStateOf<Book?>(null) }
    // 发现结果页: source/title/exploreUrl, 由 ExploreScreen onOpenExplore 触发后写入,
    // EXPLORE_SHOW 路由消费 (source 为 null 时该路由分支不渲染)
    var exploreShowSource by remember { mutableStateOf<BookSource?>(null) }
    var exploreShowTitle by remember { mutableStateOf("") }
    var exploreShowUrl by remember { mutableStateOf<String?>(null) }
    // 书内全文搜索结果跳转: 待跳转的章节索引, 由 SearchContentScreen onOpenResult 触发后写入,
    // ReaderScreen 消费后置回 null (LaunchedEffect 监听调 loadChapter)
    var pendingChapterIndex by remember { mutableStateOf<Int?>(null) }
    // 书内全文搜索结果列表 + 选中索引 (由 SearchContentScreen onOpenResult 存入 IntentData,
    // DesktopApp 读取后传给 ReaderScreen 供 SearchMenuOverlay 显示, 对照 app 端 searchContentActivity 回调)
    var pendingSearchResults by remember { mutableStateOf<List<SearchResult>?>(null) }
    var pendingSearchResultIndex by remember { mutableStateOf(0) }
    // RSS 文章列表页: 当前 RSS Book, 由 RssSourcesScreen onRssSourceClick / 书架 onBookClick(isRss) 触发,
    // RSS_ARTICLES 路由消费; 退出时置回 null 避免下次进入残留
    var rssBook by remember { mutableStateOf<Book?>(null) }
    // RSS 文章阅读页: 当前章节 index, 由 RssArticlesScreen onArticleClick 触发,
    // READ_RSS 路由消费; 退出时置回 -1 避免下次进入残留
    var rssChapterIndex by remember { mutableStateOf(-1) }
    // RSS_ARTICLES 来源标记: true=从书架点击 RSS 书进入 (onBack 回 BOOKSHELF),
    // false=从 RSS_SOURCES 点击源进入 (onBack 回 RSS_SOURCES)
    var rssFromShelf by remember { mutableStateOf(false) }

    // 桌面端协程作用域: CHANGE_SOURCE 等路由分支内异步写回 DB 用
    val scope = rememberCoroutineScope()

    // 侧边栏常驻显示
    PermanentNavigationDrawer(
        drawerContent = {
            DesktopSideBar(
                currentRoute = currentRoute,
                onRouteChange = {
                    currentRoute = it
                },
            )
        },
    ) {
        // 主区域：根据路由切换 Screen
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            when (currentRoute) {
                // 主页 (HOME): 接入 DesktopHomeScreen (io.legado.desktop.ui.main.home 包下)
                // slot 已真实化 (SectionBlock/InfiniteHeader/InfiniteGridCard + 管理对话框),
                // 书籍点击/长按均跳 BOOK_INFO 详情路由 (对照 SearchScreen onBookClick, app 端长按恒跳详情)
                DesktopRoute.HOME -> DesktopHomeScreen(
                    onBookClick = { book ->
                        infoBook = book
                        currentRoute = DesktopRoute.BOOK_INFO
                    },
                    onBookLongClick = { book ->
                        infoBook = book
                        currentRoute = DesktopRoute.BOOK_INFO
                    },
                )
                DesktopRoute.BOOKSHELF -> BookshelfScreen(
                    onBookClick = { book ->
                        // KP2: 音频书走 AUDIO_PLAYER 路由 (AudioPlayScreen), 文字书走 READER
                        // RSS 书走 RSS_ARTICLES 路由 (RssArticlesScreen), 对照 app 端 isRss→ReadRssActivity
                        if (book.type and BookType.audio != 0) {
                            audioBook = book
                            currentRoute = DesktopRoute.AUDIO_PLAYER
                        } else if (book.isRss) {
                            rssBook = book
                            rssFromShelf = true
                            currentRoute = DesktopRoute.RSS_ARTICLES
                        } else if (book.isVideo) {
                            // 视频书走 VIDEO_PLAYER 路由 (VideoPlayerScreen), 对照 app 端 isVideo→VideoPlayActivity
                            videoBook = book
                            currentRoute = DesktopRoute.VIDEO_PLAYER
                        } else {
                            // 点击书架书籍 → 切换到 READER 路由并携带 Book
                            readerBook = book
                            currentRoute = DesktopRoute.READER
                        }
                    },
                    onBookLongClick = { book ->
                        // 长按书架书籍 → 切换到 BOOK_INFO 详情路由
                        infoBook = book
                        currentRoute = DesktopRoute.BOOK_INFO
                    },
                    // 顶栏搜索按钮 → 切到搜索路由 (MY 子路由, 与侧栏 MY 入口行为一致)
                    onSearchClick = { currentRoute = DesktopRoute.SEARCH },
                )
                // 发现页 (DISCOVERY, 侧栏一级入口, 原 EXPLORE 改名对齐 BottomNavTag.DISCOVERY)
                DesktopRoute.DISCOVERY -> ExploreScreen(
                    onOpenExplore = { source, title, url ->
                        // 点击发现分类 → 切到 EXPLORE_SHOW 路由 (包装 shared ExploreShowScreen)
                        exploreShowSource = source
                        exploreShowTitle = title
                        exploreShowUrl = url
                        currentRoute = DesktopRoute.EXPLORE_SHOW
                    },
                )
                // 我的 (MY): 包装 shared MyConfigScreen, 子项 onClick 切到各子路由
                DesktopRoute.MY -> MyScreen(
                    onThemeSetting = { currentRoute = DesktopRoute.THEME_CONFIG },
                    onWebDavSetting = { currentRoute = DesktopRoute.BACKUP_CONFIG },
                    onOtherSetting = { currentRoute = DesktopRoute.OTHER_CONFIG },
                    onBookSourceManage = { currentRoute = DesktopRoute.BOOK_SOURCE },
                    onReplaceManage = { currentRoute = DesktopRoute.REPLACE },
                    onSourceFilterRuleManage = { currentRoute = DesktopRoute.SOURCE_FILTER_RULE },
                    onTxtTocRuleManage = { currentRoute = DesktopRoute.TXT_TOC_RULE },
                    onDictRuleManage = { currentRoute = DesktopRoute.DICT_RULE },
                    onRuleSubManage = { currentRoute = DesktopRoute.RULE_SUB },
                    onBookmark = { currentRoute = DesktopRoute.ALL_BOOKMARK },
                    onReadRecord = { currentRoute = DesktopRoute.READ_RECORD },
                    onAbout = { currentRoute = DesktopRoute.ABOUT },
                    onRssSources = { currentRoute = DesktopRoute.RSS_SOURCES },
                )
                DesktopRoute.BOOK_SOURCE -> BookSourceScreen(
                    onSearchBook = {
                        // 单项菜单"搜索书籍" → 切到搜索路由
                        currentRoute = DesktopRoute.SEARCH
                    },
                    onDebugSource = { sourceUrl ->
                        // 单项菜单"调试书源" → 切到书源调试路由
                        debugSourceUrl = sourceUrl
                        currentRoute = DesktopRoute.BOOK_SOURCE_DEBUG
                    },
                    onEditSource = { sourceUrl ->
                        // 单项菜单"编辑书源" → 切到书源编辑路由
                        editSourceUrl = sourceUrl
                        currentRoute = DesktopRoute.BOOK_SOURCE_EDIT
                    },
                    // 左上角返回: 切回 MY 路由 (BOOK_SOURCE 是 MY 的子路由, 由 onBookSourceManage 触发进入)
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                DesktopRoute.SEARCH -> SearchScreen(
                    initialQuery = searchQuery,
                    initialSubmit = searchSubmit,
                    onBack = {
                        // 退出搜索清空预填状态, 避免下次进入残留
                        searchQuery = null
                        searchSubmit = true
                        currentRoute = DesktopRoute.MY
                    },
                    onBookClick = { book ->
                        // 搜索结果点击 → 切到 BOOK_INFO 详情路由
                        searchQuery = null
                        searchSubmit = true
                        infoBook = book
                        currentRoute = DesktopRoute.BOOK_INFO
                    },
                    onManageBookSources = { currentRoute = DesktopRoute.BOOK_SOURCE },
                )
                DesktopRoute.REPLACE -> ReplaceRuleScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                    onAddRule = {
                        // 新增规则: id = -1
                        editRuleId = -1L
                        currentRoute = DesktopRoute.REPLACE_EDIT
                    },
                    onEditRule = { id ->
                        // 编辑已有规则: id 由列表项传入
                        editRuleId = id
                        currentRoute = DesktopRoute.REPLACE_EDIT
                    },
                )
                DesktopRoute.WEBDAV -> BackupConfigScreen(onBack = { currentRoute = DesktopRoute.MY })
                DesktopRoute.READER -> readerBook?.let { book ->
                    // 进入阅读路由：包装 shared ReadViewComposable
                    // onBack: 切回书架并清空 readerBook，让下次进入重新装载
                    ReaderScreen(
                        book = book,
                        pendingChapterIndex = pendingChapterIndex,
                        onChapterIndexConsumed = { pendingChapterIndex = null },
                        onBack = {
                            readerBook = null
                            currentRoute = DesktopRoute.BOOKSHELF
                        },
                        onOpenBookInfo = { infoBookArg ->
                            // 阅读页"书籍详情" → 切到 BOOK_INFO 路由, 清空 readerBook 让下次进入重新装载
                            infoBook = infoBookArg
                            readerBook = null
                            currentRoute = DesktopRoute.BOOK_INFO
                        },
                        onOpenSearchContent = {
                            // 设置 IntentData.book 供 SearchContentViewModelShared.initBook 读取
                            // (对照 app 端 IntentData.book = book, 桌面端 ReadBookShared 不设 IntentData)
                            IntentData.book = book
                            currentRoute = DesktopRoute.SEARCH_CONTENT
                        },
                        onOpenReplaceRule = { currentRoute = DesktopRoute.EFFECTIVE_REPLACES },
                        onOpenMoreConfig = { currentRoute = DesktopRoute.MORE_CONFIG },
                        // KP2-D P1: ReadStyleDialog 内切 padding/tip 配置路由 (与现有路由切换模式一致)
                        onOpenPaddingConfig = { currentRoute = DesktopRoute.PADDING_CONFIG },
                        onOpenTipConfig = { currentRoute = DesktopRoute.TIP_CONFIG },
                        // 段评列表: 设置 IntentData 供 ReviewListScreen 读取 (对照 app 端
                        // ReviewListDialog arguments book/chapter/paragraphIndex)
                        onOpenReviewList = { chapter, paragraphIndex ->
                            IntentData.put("reviewBookKey", book)
                            IntentData.put("reviewChapterKey", chapter)
                            IntentData.put("reviewParagraphIndex", paragraphIndex)
                            currentRoute = DesktopRoute.REVIEW_LIST
                        },
                        // 整书换源: 切到 CHANGE_SOURCE 路由 (对照 app 端 ChangeBookSourceDialog)
                        // 与 Audio/Manga 换源入口一致: 清空 readerBook + 设置 changeSourceBook
                        onOpenChangeSource = { changeBook ->
                            changeSourceBook = changeBook
                            readerBook = null
                            currentRoute = DesktopRoute.CHANGE_SOURCE
                        },
                        // 书内全文搜索结果: 传给 ReaderScreen 供 SearchMenuOverlay 显示
                        // (对照 app 端 searchContentActivity 回调 searchMenu.upSearchResultList)
                        pendingSearchResults = pendingSearchResults,
                        pendingSearchResultIndex = pendingSearchResultIndex,
                        onSearchResultsConsumed = {
                            pendingSearchResults = null
                            pendingSearchResultIndex = 0
                        },
                    )
                }
                // KP2: 音频书播放路由 (由书架/详情/目录点击 audio 类型书籍触发, 与 READER 平级)
                // 包装 desktop 端 AudioPlayScreen, 内部通过 AudioPlayShared 命令 DesktopAudioPlayProvider
                DesktopRoute.AUDIO_PLAYER -> audioBook?.let { book ->
                    AudioPlayScreen(
                        book = book,
                        // onBack: 切回书架并清空 audioBook, 让下次进入重新装载
                        onBack = {
                            audioBook = null
                            currentRoute = DesktopRoute.BOOKSHELF
                        },
                        // "目录" → 切到 TOC 路由, 携带当前 Book (复用 tocBook 状态)
                        onOpenToc = { tocBookArg ->
                            tocBook = tocBookArg
                            audioBook = null
                            currentRoute = DesktopRoute.TOC
                        },
                        // "换源" → 切到 CHANGE_SOURCE 路由, 携带当前 Book
                        onOpenChangeSource = { changeBook ->
                            changeSourceBook = changeBook
                            audioBook = null
                            currentRoute = DesktopRoute.CHANGE_SOURCE
                        },
                    )
                }
                // P2-5: 漫画阅读路由 (由书架/详情/目录点击 image 类型书籍触发, 与 READER 平级)
                // 包装 desktop 端 MangaReaderScreen, 内部用 shared MangaReaderViewModelShared 管理章节图片列表
                DesktopRoute.MANGA_READER -> mangaBook?.let { book ->
                    MangaReaderScreen(
                        book = book,
                        // 初始章节用 book.durChapterIndex (TocScreen 跳转前已写入; 默认 0)
                        chapterIndex = book.durChapterIndex,
                        onBack = {
                            mangaBook = null
                            currentRoute = DesktopRoute.BOOKSHELF
                        },
                        onOpenToc = { tocBookArg ->
                            tocBook = tocBookArg
                            mangaBook = null
                            currentRoute = DesktopRoute.TOC
                        },
                        onOpenChangeSource = { changeBook ->
                            changeSourceBook = changeBook
                            mangaBook = null
                            currentRoute = DesktopRoute.CHANGE_SOURCE
                        },
                    )
                }
                // 视频书播放路由 (由书架/详情/目录点击 video 类型书籍触发, 与 READER 平级)
                // 包装 desktop 端 VideoPlayerScreen, 内部用 VideoPlayerViewModel 管理章节视频 URL
                // 注: 已接入 vlcj (EmbeddedMediaPlayerComponent 经 SwingPanel 嵌入 AWT 渲染)
                DesktopRoute.VIDEO_PLAYER -> videoBook?.let { book ->
                    VideoPlayerScreen(
                        book = book,
                        // 初始章节用 book.durChapterIndex (TocScreen 跳转前已写入; 默认 0)
                        chapterIndex = book.durChapterIndex,
                        onBack = {
                            videoBook = null
                            currentRoute = DesktopRoute.BOOKSHELF
                        },
                        onOpenToc = { tocBookArg ->
                            tocBook = tocBookArg
                            videoBook = null
                            currentRoute = DesktopRoute.TOC
                        },
                        onOpenChangeSource = { changeBook ->
                            changeSourceBook = changeBook
                            videoBook = null
                            currentRoute = DesktopRoute.CHANGE_SOURCE
                        },
                    )
                }
                DesktopRoute.BOOK_INFO -> infoBook?.let { book ->
                    // 进入详情路由：包装 shared BookInfoScreen
                    // onBack: 切回书架 (默认调用方), 详情页其他动作走 onReadClick/onShelfClick
                    BookInfoScreen(
                        book = book,
                        onBack = {
                            infoBook = null
                            currentRoute = DesktopRoute.BOOKSHELF
                        },
                        onReadClick = { readBook ->
                            // KP2: 详情页"开始阅读" → 音频书走 AUDIO_PLAYER, 文字书走 READER
                            // RSS 书走 RSS_ARTICLES, 漫画书走 MANGA_READER, 视频书走 VIDEO_PLAYER,
                            // 对照 app 端 BookInfoActivity isRss→ReadRssActivity / isImage→ReadMangaActivity / isVideo→VideoPlayActivity
                            if (readBook.type and BookType.audio != 0) {
                                audioBook = readBook
                                infoBook = null
                                currentRoute = DesktopRoute.AUDIO_PLAYER
                            } else if (readBook.isRss) {
                                rssBook = readBook
                                rssFromShelf = true
                                infoBook = null
                                currentRoute = DesktopRoute.RSS_ARTICLES
                            } else if (readBook.isImage) {
                                // P2-5: 漫画书: 切到 MANGA_READER 路由, 携带 readBook
                                mangaBook = readBook
                                infoBook = null
                                currentRoute = DesktopRoute.MANGA_READER
                            } else if (readBook.isVideo) {
                                // 视频书: 切到 VIDEO_PLAYER 路由, 携带 readBook
                                videoBook = readBook
                                infoBook = null
                                currentRoute = DesktopRoute.VIDEO_PLAYER
                            } else {
                                // 文字书: 切到 READER 路由, 复用 readerBook 状态
                                readerBook = readBook
                                infoBook = null
                                currentRoute = DesktopRoute.READER
                            }
                        },
                        onEditClick = { bookUrl ->
                            // 详情页"编辑信息" → 切到 BOOK_INFO_EDIT 路由
                            editBookUrl = bookUrl
                            currentRoute = DesktopRoute.BOOK_INFO_EDIT
                        },
                        onOriginClick = { originBook ->
                            // 详情页"切换来源" → 切到 CHANGE_SOURCE 路由, 携带当前 Book
                            changeSourceBook = originBook
                            currentRoute = DesktopRoute.CHANGE_SOURCE
                        },
                        onTocClick = { tocBookArg ->
                            // 详情页"目录" → 切到 TOC 路由, 携带当前 Book
                            tocBook = tocBookArg
                            currentRoute = DesktopRoute.TOC
                        },
                        onSearch = { key, submit ->
                            // 详情页作者/分类/书名搜索 → 切到 SEARCH 路由, 预填关键词
                            // (对照 app 端 BookInfoActivity.search → SearchActivity putExtra key/submit)
                            searchQuery = key
                            searchSubmit = submit
                            infoBook = null
                            currentRoute = DesktopRoute.SEARCH
                        },
                        onExplore = { source, exploreName, exploreUrl ->
                            // 详情页 author/kind 含 "::" → 切到 EXPLORE_SHOW 路由 (发现结果页)
                            // (对照 app 端 BookInfoActivity.search 内 tmp.size > 1 分支:
                            //   IntentData.source + ExploreShowActivity putExtra exploreName/exploreUrl)
                            exploreShowSource = source
                            exploreShowTitle = exploreName
                            exploreShowUrl = exploreUrl
                            infoBook = null
                            currentRoute = DesktopRoute.EXPLORE_SHOW
                        },
                    )
                }
                DesktopRoute.REPLACE_EDIT -> ReplaceEditScreen(
                    ruleId = editRuleId,
                    onBack = {
                        editRuleId = -1L
                        currentRoute = DesktopRoute.REPLACE
                    },
                    onSaved = {
                        editRuleId = -1L
                        currentRoute = DesktopRoute.REPLACE
                    },
                )
                DesktopRoute.OTHER_CONFIG -> DesktopOtherConfigScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                DesktopRoute.THEME_CONFIG -> DesktopThemeConfigScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 阅读记录页 (由 MY 入口触发)
                DesktopRoute.READ_RECORD -> ReadRecordScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 关于页 (由 MY 入口触发, 包装 shared AboutScreen)
                DesktopRoute.ABOUT -> AboutScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 书籍信息编辑页 (由 BookInfo 详情页 onEditClick 触发)
                DesktopRoute.BOOK_INFO_EDIT -> BookInfoEditScreen(
                    bookUrl = editBookUrl,
                    onBack = {
                        editBookUrl = ""
                        currentRoute = DesktopRoute.BOOK_INFO
                    },
                    onSaved = {
                        editBookUrl = ""
                        currentRoute = DesktopRoute.BOOK_INFO
                    },
                )
                // 书源调试页 (由 BookSource 管理页 onDebugSource 触发)
                DesktopRoute.BOOK_SOURCE_DEBUG -> BookSourceDebugScreen(
                    sourceUrl = debugSourceUrl,
                    onBack = {
                        debugSourceUrl = ""
                        currentRoute = DesktopRoute.BOOK_SOURCE
                    },
                )
                // 书源编辑页 (由 BookSource 管理页 onEditSource 触发)
                DesktopRoute.BOOK_SOURCE_EDIT -> BookSourceEditScreen(
                    sourceUrl = editSourceUrl,
                    onBack = {
                        editSourceUrl = ""
                        currentRoute = DesktopRoute.BOOK_SOURCE
                    },
                    onSaved = {
                        editSourceUrl = ""
                        currentRoute = DesktopRoute.BOOK_SOURCE
                    },
                    onDebugSource = { sourceUrl ->
                        // 编辑页"调试" → 切到书源调试路由 (对照 BookSourceScreen onDebugSource)
                        debugSourceUrl = sourceUrl
                        currentRoute = DesktopRoute.BOOK_SOURCE_DEBUG
                    },
                    onSearchSource = {
                        // 编辑页"搜索" → 切到搜索路由 (searchScope 已由 BookSourceEditScreen 写入全局)
                        currentRoute = DesktopRoute.SEARCH
                    },
                )
                // 目录页 (由详情页/阅读页触发)
                DesktopRoute.TOC -> tocBook?.let { book ->
                    TocScreen(
                        book = book,
                        onBack = {
                            tocBook = null
                            currentRoute = DesktopRoute.BOOK_INFO
                        },
                        onChapterClick = { chapterIndex ->
                            // 切到阅读页并跳转到指定章节
                            // (修改 tocBook.durChapterIndex 让 ReaderScreen LaunchedEffect 内
                            //  loadChapter 自动跳到该章节; 不显式写回 DB, 退出阅读后下次进入仍用 DB 原值)
                            // KP2: 音频书目录点击 → 切到 AUDIO_PLAYER (AudioPlayScreen 内部按
                            //  book.durChapterIndex 调 AudioPlayShared.skipToPrev/next 定位章节)
                            // P2-5: 漫画书目录点击 → 切到 MANGA_READER (MangaReaderScreen 按
                            //  book.durChapterIndex 调 MangaReaderViewModelShared.initData 加载该章节)
                            // 视频书目录点击 → 切到 VIDEO_PLAYER (VideoPlayerScreen 按
                            //  book.durChapterIndex 调 VideoPlayerViewModel.initData 加载该章节)
                            book.durChapterIndex = chapterIndex
                            if (book.type and BookType.audio != 0) {
                                audioBook = book
                                tocBook = null
                                currentRoute = DesktopRoute.AUDIO_PLAYER
                            } else if (book.isImage) {
                                mangaBook = book
                                tocBook = null
                                currentRoute = DesktopRoute.MANGA_READER
                            } else if (book.isVideo) {
                                videoBook = book
                                tocBook = null
                                currentRoute = DesktopRoute.VIDEO_PLAYER
                            } else {
                                readerBook = book
                                tocBook = null
                                currentRoute = DesktopRoute.READER
                            }
                        },
                    )
                }
                // 换源页 (由详情页 onOriginClick 触发)
                DesktopRoute.CHANGE_SOURCE -> changeSourceBook?.let { book ->
                    ChangeSourceScreen(
                        book = book,
                        onBack = {
                            changeSourceBook = null
                            currentRoute = DesktopRoute.BOOK_INFO
                        },
                        onChangeSource = { source, newBook, toc ->
                            // 执行换源: 用 DesktopBookshelfManagePlatform.migrateBook 迁移旧书阅读进度/
                            // 分组/自定义封面等到新 Book, 保存到 DB, 然后返回
                            // 对照 app 端 BaseReadViewModel.changeTo (curBook.migrateTo + dao.insert + chapterList)
                            val oldBook = changeSourceBook
                            changeSourceBook = null
                            if (oldBook == null) {
                                currentRoute = DesktopRoute.BOOK_INFO
                            } else {
                                scope.launch {
                                    // 迁移旧书阅读进度与个性化字段 (对照 app 端 Book.migrateTo,
                                    // 内部走章节名相似度匹配 BookHelp.getDurChapter 算法)
                                    val migratedBook = DesktopBookshelfManagePlatform().migrateBook(oldBook, newBook, toc)
                                    // 写回 DB: 旧 bookUrl 不存在则 insert, 否则 delete+insert (主键可能变)
                                    val dao = AppDbProviders.get().bookDao
                                    if (dao.has(oldBook.bookUrl)) {
                                        dao.delete(oldBook)
                                    }
                                    dao.insert(migratedBook)
                                    currentRoute = DesktopRoute.BOOK_INFO
                                }
                            }
                        },
                    )
                }
                // 发现结果页 (由 DISCOVERY 路由 ExploreScreen onOpenExplore 触发)
                DesktopRoute.EXPLORE_SHOW -> exploreShowSource?.let { source ->
                    ExploreShowScreen(
                        source = source,
                        title = exploreShowTitle,
                        exploreUrl = exploreShowUrl,
                        onBack = {
                            // 返回发现页 (调用方路由), 清空状态避免下次进入残留
                            exploreShowSource = null
                            exploreShowTitle = ""
                            exploreShowUrl = null
                            currentRoute = DesktopRoute.DISCOVERY
                        },
                        onBookClick = { book ->
                            // 发现结果点击 → 切到 BOOK_INFO 详情路由 (与搜索/书架一致)
                            infoBook = book
                            exploreShowSource = null
                            currentRoute = DesktopRoute.BOOK_INFO
                        },
                    )
                }
                // 书架管理页 (由书架顶栏溢出菜单"书架管理"触发)
                DesktopRoute.BOOKSHELF_MANAGE -> BookshelfManageScreen(
                    onBack = { currentRoute = DesktopRoute.BOOKSHELF },
                )
                // 导入本地书籍页 (由书架顶栏溢出菜单"添加本地书籍"触发, 包装 shared ImportBookScreen)
                DesktopRoute.IMPORT_BOOK -> ImportBookScreen(
                    onBack = { currentRoute = DesktopRoute.BOOKSHELF },
                )
                // 远程书籍页 (由书架顶栏溢出菜单"添加远程书籍"触发, 包装 shared RemoteBookScreen)
                DesktopRoute.REMOTE_BOOK -> RemoteBookScreen(
                    onBack = { currentRoute = DesktopRoute.BOOKSHELF },
                    onStartRead = { book ->
                        // 已上架远程书籍点击阅读: 切到 READER 路由 (对照 app 端 startReadBook)
                        readerBook = book
                        currentRoute = DesktopRoute.READER
                    },
                )
                // 字典规则页 (由 MY 入口触发, 包装 shared DictRuleScreen)
                DesktopRoute.DICT_RULE -> DictRuleScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // TXT 目录规则页 (由 MY 入口触发, 包装 shared TxtTocRuleScreen)
                DesktopRoute.TXT_TOC_RULE -> TxtTocRuleScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 规则订阅页 (由 MY 入口触发, 包装 shared RuleSubScreen)
                DesktopRoute.RULE_SUB -> RuleSubScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 所有书签页 (由 MY 入口触发, 包装 shared AllBookmarkScreen)
                DesktopRoute.ALL_BOOKMARK -> AllBookmarkScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                    onOpenBookmark = { book, chapterIndex ->
                        // 点击书签跳转阅读: 设置 readerBook + pendingChapterIndex, 切到 READER 路由
                        // (对照 app 端 AllBookmarkActivity.openBookmark → startActivityForBook)
                        readerBook = book
                        pendingChapterIndex = chapterIndex
                        currentRoute = DesktopRoute.READER
                    },
                )
                // 书源过滤规则页 (由 MY 入口触发, 包装 shared SourceFilterRuleScreen)
                DesktopRoute.SOURCE_FILTER_RULE -> SourceFilterRuleScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 备份设置页 (由 MY 入口触发, 包装 shared BackupConfigScreen)
                DesktopRoute.BACKUP_CONFIG -> BackupConfigScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 书内全文搜索页 (由 ReaderScreen onOpenSearchContent 触发, 包装 shared SearchContentScreen)
                DesktopRoute.SEARCH_CONTENT -> SearchContentScreen(
                    onBack = {
                        // 清空 IntentData.book 避免下次进入残留
                        IntentData.book = null
                        currentRoute = DesktopRoute.MY
                    },
                    onOpenResult = { result ->
                        // 搜索结果点击: 切回 READER 路由并跳转章节
                        // (对照 app 端 onOpenResult 跳阅读页 + skipToChapterPos)
                        // readerBook 为空时 (从 MY 入口进入无阅读上下文) 不跳转
                        readerBook?.let {
                            pendingChapterIndex = result.chapterIndex
                            // 读取 SearchContentScreen 存入的搜索结果列表 + 索引
                            // (对照 app 端 searchContentActivity 回调读取 IntentData searchResultList)
                            pendingSearchResults = IntentData.get<List<SearchResult>>("searchResultList")
                            pendingSearchResultIndex = IntentData.get<Int>("searchResultIndex") ?: 0
                            IntentData.book = null
                            currentRoute = DesktopRoute.READER
                        }
                    },
                )
                // 欢迎页设置页 (由 MY 入口触发, 包装 shared WelcomeConfigScreen)
                DesktopRoute.WELCOME_CONFIG -> WelcomeConfigScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 封面设置页 (由 MY 入口触发, 包装 shared CoverConfigScreen)
                DesktopRoute.COVER_CONFIG -> CoverConfigScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 有效替换页 (由 MY 入口触发, 包装 shared/sharedUiMain EffectiveReplacesScreen)
                DesktopRoute.EFFECTIVE_REPLACES -> EffectiveReplacesScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 边距配置页 (由 MY 入口触发, 包装 shared/sharedUiMain PaddingConfigScreen)
                DesktopRoute.PADDING_CONFIG -> PaddingConfigScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 提示信息配置页 (由 MY 入口触发, 包装 shared/sharedUiMain TipConfigScreen)
                DesktopRoute.TIP_CONFIG -> TipConfigScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 朗读配置页 (由 MY 入口触发, 包装 shared/sharedUiMain ReadAloudConfigScreen)
                DesktopRoute.READ_ALOUD_CONFIG -> ReadAloudConfigScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 更多配置页 (由 MY 入口触发, 包装 shared/sharedUiMain MoreConfigScreen)
                DesktopRoute.MORE_CONFIG -> MoreConfigScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                )
                // 段评列表页 (由 ReaderScreen 段评气泡点击触发, 包装 desktop ReviewListScreen)
                // onBack 回 READER 路由 (readerBook 仍保留, ReaderScreen 重新装载恢复阅读)
                DesktopRoute.REVIEW_LIST -> ReviewListScreen(
                    onBack = { currentRoute = DesktopRoute.READER },
                )
                // RSS 源列表 (由 MY 入口 onRssSources 触发, 订阅 bookDao.flowAll 过滤 isRss)
                DesktopRoute.RSS_SOURCES -> RssSourcesScreen(
                    onBack = { currentRoute = DesktopRoute.MY },
                    onRssSourceClick = { book ->
                        rssBook = book
                        rssFromShelf = false
                        currentRoute = DesktopRoute.RSS_ARTICLES
                    },
                )
                // RSS 文章列表 (由 RSS_SOURCES onRssSourceClick / 书架 onBookClick(isRss) / 详情 onReadClick(isRss) 触发)
                // onBack 按来源标记回 BOOKSHELF 或 RSS_SOURCES
                DesktopRoute.RSS_ARTICLES -> rssBook?.let { book ->
                    RssArticlesScreen(
                        book = book,
                        onBack = {
                            rssBook = null
                            currentRoute = if (rssFromShelf) DesktopRoute.BOOKSHELF else DesktopRoute.RSS_SOURCES
                        },
                        onArticleClick = { chapterIndex ->
                            rssChapterIndex = chapterIndex
                            currentRoute = DesktopRoute.READ_RSS
                        },
                    )
                }
                // RSS 文章阅读 (由 RSS_ARTICLES onArticleClick 触发, WebBook.getContentAwait 拉正文)
                DesktopRoute.READ_RSS -> rssBook?.let { book ->
                    ReadRssScreen(
                        book = book,
                        chapterIndex = rssChapterIndex,
                        onBack = { currentRoute = DesktopRoute.RSS_ARTICLES },
                    )
                }
            }
        }
    }
}
