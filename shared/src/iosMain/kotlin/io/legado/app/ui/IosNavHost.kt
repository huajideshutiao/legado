package io.legado.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.ui.bookinfo.IosBookInfoScreen
import io.legado.app.ui.bookshelf.IosBookshelfScreen
import io.legado.app.ui.booksource.IosBookSourceScreen
import io.legado.app.ui.reader.IosReaderScreen
import io.legado.app.ui.search.IosSearchScreen

/**
 * iOS 端阅读流子路由宿主 (KP4: 对齐 desktop `DesktopApp.kt` 的 when(currentRoute) 模式)。
 *
 * # 背景
 *
 * KP3 阶段 [io.legado.app.MainViewController] 仅渲染书架 Screen (IosBookshelfScreen),
 * 路由跳转回调全部 no-op; 本文件 KP4 在 [MainViewController] 内部替换原直接调
 * IosBookshelfScreen 的位置, 仿照 desktop `DesktopApp.kt` 用 `when(currentRoute)`
 * 分支接入阅读流 4 个子路由:
 * - BOOKSHELF: 书架 (复用 [IosBookshelfScreen])
 * - READER: 阅读页 (包装 shared/sharedUiMain `ReadViewComposable`, 见 [IosReaderScreen])
 * - SEARCH: 搜索页 (包装 shared/sharedUiMain `SearchScreen`, 见 [IosSearchScreen])
 * - BOOK_INFO: 详情页 (包装 shared/sharedUiMain `BookInfoScreen`, 见 [IosBookInfoScreen])
 * - BOOK_SOURCE: 书源管理页 (包装 shared/sharedUiMain `BookSourceListScreen`, 见 [IosBookSourceScreen])
 *
 * # 路由状态
 *
 * - [currentRoute]: 当前显示的子路由 (默认 [IosRoute.BOOKSHELF], 对齐 desktop 默认书架)
 * - [readerBook]: READER 路由消费, 书架 onBookClick 触发后写入
 * - [infoBook]: BOOK_INFO 路由消费, 搜索/书架 onBookLongClick 触发后写入
 *
 * 子路由返回逻辑 (与 desktop 一致): 子页 onBack 回到调用方路由
 * (BOOK_INFO → SEARCH/BOOKSHELF, SEARCH → BOOKSHELF, READER → BOOKSHELF, BOOK_SOURCE → BOOKSHELF)。
 *
 * # 与 desktop 的差异
 *
 * iOS 端暂只接入阅读流 4 个核心子路由 (READER/SEARCH/BOOK_INFO/BOOK_SOURCE),
 * 不接入 desktop 端的 MY 入口/音频/漫画/视频/RSS/TOC/CHANGE_SOURCE 等扩展路由
 * (这些依赖 desktop 端自己的 Screen 实现或 shared/sharedUiMain 后续补充)。
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
    // 当前路由, 默认书架 (对齐 desktop DesktopApp 初始路由按 defaultHomePage 配置决定, 默认 BOOKSHELF)
    var currentRoute by remember { mutableStateOf(IosRoute.BOOKSHELF) }
    // READER 路由消费: 书架 onBookClick 触发后写入, 退出阅读置回 null 避免下次进入残留
    var readerBook by remember { mutableStateOf<Book?>(null) }
    // BOOK_INFO 路由消费: 搜索 onBookClick / 书架 onBookLongClick 触发后写入
    // 用 BaseBook? 统一持有 SearchBook/Book (IosBookInfoScreen 内部转 Book)
    var infoBook by remember { mutableStateOf<BaseBook?>(null) }

    when (currentRoute) {
        // 书架: 复用现有 IosBookshelfScreen, 注入真实路由跳转回调 (替代 KP3 阶段的 no-op)
        IosRoute.BOOKSHELF -> IosBookshelfScreen(
            onBookClick = { book ->
                // 点击书架书籍 → 切到 READER 路由并携带 Book
                readerBook = book
                currentRoute = IosRoute.READER
            },
            onBookLongClick = { book ->
                // 长按书架书籍 → 切到 BOOK_INFO 详情路由
                infoBook = book
                currentRoute = IosRoute.BOOK_INFO
            },
            onSearchClick = { currentRoute = IosRoute.SEARCH },
            // 顶栏溢出菜单"书源管理" → 切到 BOOK_SOURCE 路由 (对照 desktop BOOKSHELF 路由的溢出菜单)
            // 注: 当前 IosBookshelfScreen 的 onOpenBookshelfManage 已暴露, 这里接路由;
            // onAddLocalBook/onAddRemoteBook 暂留 no-op, 待后续接入导入子路由
            onOpenBookshelfManage = { currentRoute = IosRoute.BOOK_SOURCE },
        )

        // 阅读页: 包装 shared/sharedUiMain ReadViewComposable, 详见 IosReaderScreen
        IosRoute.READER -> readerBook?.let { book ->
            IosReaderScreen(
                book = book,
                onBack = {
                    // 退出阅读 → 切回书架并清空 readerBook, 让下次进入重新装载
                    readerBook = null
                    currentRoute = IosRoute.BOOKSHELF
                },
                onOpenBookInfo = { infoBookArg ->
                    // 阅读页"书籍详情" → 切到 BOOK_INFO 路由, 清空 readerBook 让下次进入重新装载
                    infoBook = infoBookArg
                    readerBook = null
                    currentRoute = IosRoute.BOOK_INFO
                },
            )
        }

        // 搜索页: 包装 shared/sharedUiMain SearchScreen, 详见 IosSearchScreen
        IosRoute.SEARCH -> IosSearchScreen(
            onBack = { currentRoute = IosRoute.BOOKSHELF },
            onBookClick = { book ->
                // 搜索结果点击 → 切到 BOOK_INFO 详情路由 (与 desktop SEARCH 路由一致)
                infoBook = book
                currentRoute = IosRoute.BOOK_INFO
            },
            onManageBookSources = { currentRoute = IosRoute.BOOK_SOURCE },
        )

        // 详情页: 包装 shared/sharedUiMain BookInfoScreen, 详见 IosBookInfoScreen
        IosRoute.BOOK_INFO -> infoBook?.let { book ->
            IosBookInfoScreen(
                book = book,
                onBack = {
                    // 退出详情 → 切回书架 (默认调用方), 清空 infoBook 避免下次进入残留
                    infoBook = null
                    currentRoute = IosRoute.BOOKSHELF
                },
                onReadClick = { readBook ->
                    // 详情页"开始阅读" → 切到 READER 路由, 携带 Book
                    // (iOS 端暂不区分音频/漫画/视频/RSS 书, 全部走 READER; 后续接 IosAudioPlay/IosMangaReader 时扩展)
                    readerBook = readBook
                    infoBook = null
                    currentRoute = IosRoute.READER
                },
            )
        }

        // 书源管理页: 包装 shared/sharedUiMain BookSourceListScreen, 详见 IosBookSourceScreen
        IosRoute.BOOK_SOURCE -> IosBookSourceScreen(
            onBack = { currentRoute = IosRoute.BOOKSHELF },
            onSearchBook = {
                // 单项菜单"搜索书籍" → 切到搜索路由 (对照 desktop BookSourceScreen.onSearchBook)
                currentRoute = IosRoute.SEARCH
            },
        )
    }
}

/**
 * iOS 端子路由枚举 (KP4 阅读流核心 4 路由 + 书架, 对照 desktop `DesktopRoute` 子集)。
 *
 * 桌面端 [io.legado.desktop.ui.DesktopRoute] 含 30+ 路由 (HOME/DISCOVERY/MY/AUDIO_PLAYER 等),
 * iOS 端 KP4 阶段仅接入阅读流 4 个核心子路由 + 书架, 其余路由待后续 KP5+ 接入对应 Screen。
 */
enum class IosRoute {
    /** 书架 (KP3 已接入, 复用 [IosBookshelfScreen]) */
    BOOKSHELF,
    /** 阅读页 (KP4: 包装 shared ReadViewComposable) */
    READER,
    /** 搜索页 (KP4: 包装 shared SearchScreen) */
    SEARCH,
    /** 详情页 (KP4: 包装 shared BookInfoScreen) */
    BOOK_INFO,
    /** 书源管理页 (KP4: 包装 shared BookSourceListScreen) */
    BOOK_SOURCE,
}
