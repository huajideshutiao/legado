package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.addType
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.info.BookInfoMenuState
import io.legado.app.ui.book.info.BookInfoScreen
import io.legado.app.ui.book.info.BookInfoScreenModel
import io.legado.app.ui.book.info.BookInfoUiActions
import io.legado.app.ui.book.info.BookInfoUiEvent
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.FlowBus
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 书籍详情页 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [BookInfoScreenModel], 渲染 [BookInfoScreen]。
 */
@Composable
fun BookInfoRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.BookInfo
    // BookRef -> Book, 导航时再 toRouteRef() 转回 (防御性拷贝, 避免与路由持有对象别名)
    val book = route.book.asBook()

    val screenModel = screenModelStore.getOrCreateTyped(entry) { BookInfoScreenModel() }
    val state by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 状态初始化 (对照 BookInfoActivity.onActivityCreated + showBook + upWordCount + upGroup)
    val lastedTitle = rememberString("lasted_show", book.latestChapterTitle ?: "")
    val noGroupLabel = rememberString("no_group")
    val needMoreTimeLabel = rememberString("need_more_time_load_content")
    LaunchedEffect(book.bookUrl) {
        screenModel.dispatch(BookInfoUiEvent.ShowBook(book, lastedTitle))
        scope.launch(IoDispatcher) {
            // 检查是否在书架
            val inShelf = AppDbProviders.get().bookDao.getBook(book.bookUrl) != null
            screenModel.dispatch(BookInfoUiEvent.UpdateBookshelf(inShelf))
            // 加载分组名 (对照 Activity upGroup: 空→no_group)
            val groupName = try {
                AppDbProviders.get().bookGroupDao.getGroupNames(book.group).joinToString(",")
            } catch (e: Throwable) {
                ""
            }
            screenModel.dispatch(BookInfoUiEvent.UpdateGroup(groupName.takeIf { it.isNotEmpty() }
                ?: noGroupLabel))
            // 字数信息 (对照 Activity upWordCount: 本地文件大小需平台 FileDoc, 此处仅取 book.wordCount + 兜底)
            val wordCountRaw = book.wordCount?.takeIf { it.isNotBlank() }
            val wordCountText = when {
                !wordCountRaw.isNullOrBlank() -> wordCountRaw
                book.isLocal -> ""
                else -> null
            }
            screenModel.dispatch(BookInfoUiEvent.UpdateWordCount(wordCountText))
            // 目录: 用当前阅读章节标题占位 (实际章节加载依赖平台 BaseReadViewModel)
            screenModel.dispatch(
                BookInfoUiEvent.UpdateToc(
                    tocText = book.durChapterTitle ?: "",
                    lastedTitle = null,
                )
            )
        }
    }

    val actions = object : BookInfoUiActions {
        // 返回栈由导航器统一管理
        override fun onBack() {
            navigator.pop()
        }

        // 编辑: 复用路由持有的同一 BookRef (原地编辑需反映回详情)
        override fun onEdit() {
            navigator.push(AppRoute.BookInfoEdit(route.book))
        }

        // 阅读: 按类型分发到对应阅读路由; webFile 需下载导入, 平台专属
        override fun onReadClick() {
            val b = state.book ?: book
            if (b.isWebFile) {
                PlatformCapabilityProviders.getOrNull()?.handleWebFileRead(b)
                return
            }
            if (!state.inBookshelf) {
                b.addType(BookType.notShelf)
            }
            val target = when {
                b.isAudio -> AppRoute.AudioPlay(b.toRouteRef())
                b.isVideo -> AppRoute.VideoPlay(b.toRouteRef())
                b.isImage -> AppRoute.MangaReader(b.toRouteRef())
                b.isRss -> AppRoute.ReadRss(b.toRouteRef())
                else -> AppRoute.Reader(b.toRouteRef())
            }
            navigator.push(target)
        }

        // 来源点击: 编辑书源 (对照 Activity editSourceResult, 非 ChangeSource)
        override fun onOriginClick() {
            if (book.isLocal) return
            navigator.push(AppRoute.BookSourceEdit(book.origin))
        }

        // 来源长按: 换源
        override fun onOriginLongClick() {
            navigator.push(AppRoute.ChangeSource(book.toRouteRef()))
        }

        // 目录
        override fun onTocClick() {
            navigator.push(AppRoute.Toc(book.toRouteRef()))
        }

        // 分享: 构建书籍信息 JSON, 通过平台能力分享
        override fun onShare() {
            val b = state.book ?: book
            val json = buildJsonObject {
                put("bookUrl", b.bookUrl)
                put("tocUrl", b.tocUrl)
                put("origin", b.origin)
                put("originName", b.originName)
                put("name", b.name)
                put("author", b.author)
                put("kind", b.kind)
                put("coverUrl", b.coverUrl)
                put("customCoverUrl", b.customCoverUrl)
                put("intro", b.intro)
                put("customIntro", b.customIntro)
                put("type", b.type)
                put("wordCount", b.wordCount)
            }
            PlatformCapabilityProviders.get().shareText("[$json]")
        }

        // 刷新: 标记加载中 + 委托平台拉取数据
        override fun onRefresh() {
            val b = state.book ?: book
            screenModel.dispatch(BookInfoUiEvent.Refresh)
            PlatformCapabilityProviders.getOrNull()?.refreshBookInfo(b)
        }

        // 上传: 委托平台 (依赖确认弹窗 + WebDav)
        override fun onUploadBook() {
            PlatformCapabilityProviders.getOrNull()?.uploadBook(state.book ?: book)
        }

        // 下载到本地: 委托平台 (依赖 FileBook/Uri)
        override fun onDownloadToLocal() {
            PlatformCapabilityProviders.getOrNull()?.downloadBookToLocal(state.book ?: book)
        }

        // 置顶: 更新 order + durChapterTime
        override fun onTopBook() {
            val b = state.book ?: book
            scope.launch(IoDispatcher) {
                b.order = AppDbProviders.get().bookDao.minOrder() - 1
                b.durChapterTime = systemCurrentTimeMillis()
                AppDbProviders.get().bookDao.update(b)
            }
        }

        // 登录: 跳转书源登录页
        override fun onLogin() {
            val sourceUrl = (state.book ?: book).origin
            navigator.push(AppRoute.Login(sourceUrl))
        }

        // 评论: 跳转书评列表页
        override fun onOpenCommentDialog() {
            navigator.push(AppRoute.ReviewList(book.toRouteRef()))
        }

        // 源变量: 委托平台弹窗 (需 BookSource 对象)
        override fun onSetSourceVariable() {
            PlatformCapabilityProviders.getOrNull()?.showSourceVariableDialog(state.book ?: book)
        }

        // 书籍变量: 委托平台弹窗 (需 BookSource 对象)
        override fun onSetBookVariable() {
            PlatformCapabilityProviders.getOrNull()?.showBookVariableDialog(state.book ?: book)
        }

        // 复制书籍 URL
        override fun onCopyBookUrl() {
            PlatformCapabilityProviders.get().copyToClipboard((state.book ?: book).bookUrl)
        }

        // 复制目录 URL
        override fun onCopyTocUrl() {
            PlatformCapabilityProviders.get().copyToClipboard((state.book ?: book).tocUrl)
        }

        // 切换可更新: 修改 book + 驱动重组 + 入库
        override fun onToggleCanUpdate() {
            val b = state.book ?: return
            b.canUpdate = !b.canUpdate
            screenModel.dispatch(BookInfoUiEvent.BumpBookTick)
            if (state.inBookshelf) {
                if (!b.canUpdate) b.removeType(BookType.updateError)
                scope.launch(IoDispatcher) { AppDbProviders.get().bookDao.update(b) }
            }
        }

        // 切换拆分长章: 修改 config + 驱动重组 + 标记加载中 + 委托平台重载
        override fun onToggleSplitLongChapter() {
            val b = state.book ?: return
            val newValue = !b.config.splitLongChapter
            b.config.splitLongChapter = newValue
            screenModel.dispatch(BookInfoUiEvent.BumpBookTick)
            screenModel.dispatch(BookInfoUiEvent.Refresh)
            PlatformCapabilityProviders.getOrNull()?.reloadBookInfo(b)
            if (!newValue) Toasters.get().toastLong(needMoreTimeLabel)
        }

        // 清缓存: 委托平台 (依赖 BookHelp/ReadBook)
        override fun onClearCache() {
            PlatformCapabilityProviders.getOrNull()?.clearBookCache(state.book ?: book)
        }

        // 日志: 弹窗
        override fun onShowLog() {
            navigator.showOverlay(AppOverlay.Dialog(key = "app_log"))
        }

        // 书名点击: 跳搜索 (AppRoute.Search 暂不支持传 key, 待路由参数扩展)
        override fun onNameClick() {
            navigator.push(AppRoute.Search())
        }

        // 封面点击: 查看大图
        override fun onCoverClick() {
            (state.book ?: book).getDisplayCover()?.let { cover ->
                navigator.showOverlay(AppOverlay.Dialog(key = "photo", payload = cover))
            }
        }

        // 封面长按: 换封面弹窗
        override fun onCoverLongClick() {
            val b = state.book ?: book
            val payload = "${b.name}\n${b.getRealAuthor()}"
            navigator.showOverlay(AppOverlay.Dialog(key = "change_cover", payload = payload))
        }

        // 分组点击: 选择分组弹窗
        override fun onGroupClick() {
            val group = (state.book ?: book).group
            navigator.showOverlay(
                AppOverlay.Dialog(
                    key = "group_select",
                    payload = group.toString()
                )
            )
        }

        // 书架: 上架/下架, 委托平台 (含删除确认/webFile 弹窗)
        override fun onShelfClick() {
            val b = state.book ?: book
            PlatformCapabilityProviders.getOrNull()
                ?.toggleBookshelf(b, state.inBookshelf) { result ->
                    if (result == null) navigator.pop()
                    else screenModel.dispatch(BookInfoUiEvent.UpdateBookshelf(result))
                }
        }

        // 搜索作者: :: 探索需 BookSource, 加载后跳 ExploreShow; 普通搜索跳 Search
        override fun onSearchAuthor(author: String, submit: Boolean) {
            val tmp = author.split("::", limit = 2)
            if (tmp.size > 1) {
                val b = state.book ?: book
                scope.launch(IoDispatcher) {
                    val source =
                        AppDbProviders.get().bookSourceDao.getBookSource(b.origin) ?: return@launch
                    withContext(Dispatchers.Main) {
                        navigator.push(AppRoute.ExploreShow(source, tmp[0], tmp[1]))
                    }
                }
            } else {
                navigator.push(AppRoute.Search())
            }
        }

        // 搜索分类: :: 探索需 BookSource, 加载后跳 ExploreShow; 普通搜索跳 Search
        override fun onSearchKind(kind: String, submit: Boolean) {
            val tmp = kind.split("::", limit = 2)
            if (tmp.size > 1) {
                val b = state.book ?: book
                scope.launch(IoDispatcher) {
                    val source =
                        AppDbProviders.get().bookSourceDao.getBookSource(b.origin) ?: return@launch
                    withContext(Dispatchers.Main) {
                        navigator.push(AppRoute.ExploreShow(source, tmp[0], tmp[1]))
                    }
                }
            } else {
                navigator.push(AppRoute.Search())
            }
        }

        // 简介动作: JS 派发, 委托平台 (需 BookSource + JS 引擎)
        override fun onDispatchIntroAction(action: String) {
            val js = action.trim().ifEmpty { return }
            PlatformCapabilityProviders.getOrNull()?.evalIntroAction(state.book ?: book, js)
        }

        // 查看简介图片
        override fun onShowPhoto(src: String) {
            navigator.showOverlay(AppOverlay.Dialog(key = "photo", payload = src))
        }
    }

    // 对照 Activity observeLiveBus: EventBus.REFRESH_BOOK_INFO → refreshBook
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.REFRESH_BOOK_INFO).collect {
            val b = screenModel.state.value.book ?: book
            screenModel.dispatch(BookInfoUiEvent.Refresh)
            PlatformCapabilityProviders.getOrNull()?.refreshBookInfo(b)
        }
    }

    // 加载书源 (对照 Activity viewModel.curBookSource, 供 menuState 判断)
    var bookSource by remember { mutableStateOf<BookSource?>(null) }
    LaunchedEffect(book.origin) {
        if (book.isLocal) {
            bookSource = null
        } else {
            scope.launch(IoDispatcher) {
                bookSource = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            }
        }
    }

    // 三态注入 (对照 BookInfoActivity.Content: isLandscape/useDevFeat/isDarkTheme)
    val currentBook = state.book ?: book
    // isLandscape: 窗口宽度 > 高度 (跨平台, 对照 Android LocalConfiguration.orientation)
    val containerSize = LocalWindowInfo.current.containerSize
    val isLandscape = containerSize.width > containerSize.height
    // isDarkTheme: 背景色亮度反推 (对照 Activity.isDarkTheme)
    val isDarkTheme = AppTheme.colors.isDark
    // useDevFeat: 竖屏 + 开启横向布局 + 非视频 (对照 Activity Content)
    val useDevFeat = AppConfigProviders.get().bookInfoHorizontalLayout &&
        !currentBook.isVideo && !isLandscape

    // 计算 menuState (对照 Activity Content 内 menuState 构造)
    val menuState = BookInfoMenuState(
        isLocal = currentBook.origin == BookType.localTag,
        isWebDav = currentBook.origin?.startsWith(BookType.webDavTag) == true,
        hasSource = bookSource != null,
        sourceHasLogin = bookSource?.hasLogin() == true,
        sourceHasReviewRule = !bookSource?.reviewRule?.reviewUrl.isNullOrBlank(),
        canUpdate = currentBook.canUpdate,
        isLocalTxt = currentBook.isLocalTxt,
        splitLongChapter = currentBook.config.splitLongChapter,
        bookUrl = currentBook.bookUrl,
        tocUrl = currentBook.tocUrl,
    )
    val screenState = state.copy(
        menuState = menuState,
        isLandscape = isLandscape,
        useDevFeat = useDevFeat,
        isDarkTheme = isDarkTheme,
    )

    // L3: 模糊封面背景 / 封面 / 简介图依赖平台 Glide/AndroidView, 由平台注入
    // 封面: 取 LocalBookCoverSlot (宿主端注入平台实现, 兜底 SharedBookCover), 适配 (Book?, Modifier) 签名
    val bookCoverSlot = LocalBookCoverSlot.current
    BookInfoScreen(
        state = screenState,
        actions = actions,
        blurCoverBgSlot = {},
        coverSlot = { book, modifier ->
            book?.let { bookCoverSlot(it, modifier, false) }
        },
        introImageSlot = { _, _ -> },
    )
}
