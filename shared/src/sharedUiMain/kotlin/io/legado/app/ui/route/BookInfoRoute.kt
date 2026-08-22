package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
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
import io.legado.app.help.showSourceLogin
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.info.BookInfoCover
import io.legado.app.ui.book.info.BookInfoMenuState
import io.legado.app.ui.book.info.BookInfoScreen
import io.legado.app.ui.book.info.BookInfoScreenModel
import io.legado.app.ui.book.info.BookInfoUiActions
import io.legado.app.ui.book.info.BookInfoUiEvent
import io.legado.app.ui.book.info.LocalBlurCoverBgSlot
import io.legado.app.ui.book.info.LocalIntroImageSlot
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FlowBus
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.error_load_toc
import legado.shared.generated.resources.need_more_time_load_content
import legado.shared.generated.resources.no_group
import org.jetbrains.compose.resources.stringResource

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
    // BookRef -> Book (toRouteRef/asBook 不再内部拷贝: DB-flow 边界已在书架/搜索书架区块
    // 显式 copy, 此处直接共享路由快照; 页面改动走 bookDao.update 落库, 快照随 DB 一致)
    val book = route.book.asBook()

    val screenModel = screenModelStore.getOrCreateTyped(entry) { BookInfoScreenModel() }
    val state by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 搜索结果进入的书 (对照 app 端 BaseReadViewModel.upBook 的 isSearchBook)
    val isSearchBook = route.book is BookRef.Search

    // 加载书源 (对照 Activity viewModel.curBookSource, 供菜单与标签搜索限定当前书源)
    // 自动加载依赖它, 故在初始化 effect 内同步查好再用
    var bookSource by remember { mutableStateOf<BookSource?>(null) }

    // 整书换源弹窗显示开关 (对照原版长按来源 showDialogFragment(ChangeBookSourceDialog))
    var showChangeSourceDialog by remember { mutableStateOf(false) }

    // 状态初始化 (对照 BaseReadViewModel.upBook + Activity showBook + upWordCount + upGroup)
    val noGroupLabel = stringResource(Res.string.no_group)
    val errorLoadTocLabel = stringResource(Res.string.error_load_toc)
    val needMoreTimeLabel = stringResource(Res.string.need_more_time_load_content)
    LaunchedEffect(book.bookUrl) {
        screenModel.dispatch(
            BookInfoUiEvent.ShowBook(book, screenModel.lastedTitleOf(book))
        )
        scope.launch(IoDispatcher) {
            val db = AppDbProviders.get()
            val source = if (book.isLocal) null else db.bookSourceDao.getBookSource(book.origin)
            bookSource = source
            // 检查是否在书架 (对照 upBook / master loadBookInfo 的 inBookshelf 语义:
            // 书架的唯一定位是 bookUrl; 同名异源书不算在架, 否则加载目录会误把搜索/发现
            // 的书 replace+insert 进书架。同名查询仅用于 isSearchBook 的同源合并, 不参与在架判定)
            val dbBook = db.bookDao.getBook(book.bookUrl)
                ?: db.bookDao.getBook(book.name, book.author)
            val inShelf = if (isSearchBook) dbBook?.origin == book.origin
            else db.bookDao.getBook(book.bookUrl) != null
            println(
                "[NOTSHELF-DEBUG] BookInfo init url=${book.bookUrl} isSearch=$isSearchBook " +
                    "dbBook=${dbBook?.bookUrl} inShelf=$inShelf"
            )
            screenModel.dispatch(BookInfoUiEvent.UpdateBookshelf(inShelf))
            // 搜索来源的同名异源书: 对齐 master loadBookInfo else 分支 addType(notShelf),
            // 标记为临时书 (目录/阅读不落库, 阅读器退出清理)
            if (isSearchBook && dbBook != null && dbBook.origin != book.origin) {
                book.addType(io.legado.app.constant.BookType.notShelf)
            }
            // 搜索来源的书已在书架且同源时改用书架那本 (保留阅读进度/分组等);
            // 同名异源仍用搜索书 (带 notShelf 标记, 对齐 master loadBookInfo 的异源分支),
            // 否则标记落在被丢弃对象上且 UI 会误显示书架书为"未在架"
            val curBook =
                if (isSearchBook && dbBook?.origin == book.origin) dbBook else book
            // rss 书 url 换位 (对照 upBook)
            if (curBook.isRss) {
                curBook.tocUrl = curBook.bookUrl
                curBook.bookUrl = "data:"
            }
            if (curBook !== book) {
                screenModel.dispatch(
                    BookInfoUiEvent.ShowBook(curBook, screenModel.lastedTitleOf(curBook))
                )
            } else if (curBook.isRss) {
                // 原地改 tocUrl/bookUrl 不换实例, state.book 引用不变, 需 bump tick 驱动重组
                screenModel.dispatch(BookInfoUiEvent.BumpBookTick)
            }
            // 加载分组名 (对照 Activity upGroup: 空→no_group)
            val groupName = try {
                db.bookGroupDao.getGroupNames(curBook.group).joinToString(",")
            } catch (e: Throwable) {
                ""
            }
            screenModel.dispatch(BookInfoUiEvent.UpdateGroup(groupName.takeIf { it.isNotEmpty() }
                ?: noGroupLabel))
            // 字数信息 (对照 Activity upWordCount: 字数 + 本地书文件大小, 逗号拼接)
            val wordCounts = arrayListOf<String>()
            curBook.wordCount?.takeIf { it.isNotBlank() }?.let { wordCounts.add(it) }
            if (curBook.isLocal) {
                val size = try {
                    if (curBook.bookUrl.startsWith("http", true) ||
                        curBook.bookUrl.startsWith("dav", true)
                    ) 0L
                    else PlatformCapabilityProviders.getOrNull()
                        ?.localBookFileSize(curBook.bookUrl) ?: 0L
                } catch (_: Exception) {
                    0L
                }
                if (size > 0) wordCounts.add(ConvertUtils.formatFileSize(size))
            }
            val wordCountText = when {
                wordCounts.isNotEmpty() -> wordCounts.joinToString(",")
                curBook.isLocal -> ""
                else -> null
            }
            screenModel.dispatch(BookInfoUiEvent.UpdateWordCount(wordCountText))
            // 自动加载书籍信息/目录 (对照 upBook 的 tocUrl 分支)
            when {
                curBook.tocUrl.isEmpty() -> screenModel.refresh(
                    curBook, source, errorLoadTocLabel,
                    runPreUpdateJs = inShelf, isSearchBook = isSearchBook,
                )

                !inShelf || curBook.totalChapterNum == 0 ->
                    screenModel.loadToc(curBook, source, errorLoadTocLabel)

                else -> {
                    val chapters = db.bookChapterDao.getChapterList(curBook.bookUrl)
                    if (chapters.isEmpty()) {
                        screenModel.loadToc(curBook, source, errorLoadTocLabel)
                    } else {
                        screenModel.dispatch(
                            BookInfoUiEvent.UpdateToc(
                                tocText = curBook.durChapterTitle.orEmpty(),
                                lastedTitle = screenModel.lastedTitleOf(curBook),
                            )
                        )
                    }
                }
            }
        }
    }

    val actions = object : BookInfoUiActions {
        // 返回栈由导航器统一管理
        override fun onBack() {
            navigator.pop()
        }

        // 编辑: 复用路由持有的同一 BookRef (原地编辑需反映回详情)
        override fun onEdit() {
            navigator.push(AppRoute.BookInfoEdit(route.book), RouteResults.BOOK_INFO_EDIT)
        }

        // 阅读: 按类型分发到对应阅读路由; webFile 需下载导入, 平台专属
        override fun onReadClick() {
            val b = state.book ?: book
            if (b.isWebFile) {
                // webFile: 下载导入后跳阅读 (对照 onReadClick isWebFile + readBook)
                PlatformCapabilityProviders.getOrNull()?.handleWebFileRead(
                    b,
                    onWaitDialog = { screenModel.upWaitDialog(it) },
                    onAction = { screenModel.postAction(it) },
                    onSuccess = { navBook ->
                        scope.launch {
                            screenModel.dispatch(
                                BookInfoUiEvent.ShowBook(
                                    navBook,
                                    screenModel.lastedTitleOf(navBook)
                                )
                            )
                        }
                        screenModel.dispatch(BookInfoUiEvent.UpdateBookshelf(true))
                        navigator.push(AppRoute.Reader(navBook.toRouteRef()), RouteResults.READER)
                    }
                )
                return
            }
            if (!state.inBookshelf) {
                b.addType(BookType.notShelf)
            }
            // 目录内存交接 (对照原版 BookInfoActivity.startReadActivity:
            // IntentData.chapterList = viewModel.chapterListData.value)。
            // 不交接时阅读/播放页只能读 DB, 未加书架的书目录不落库 → 落空后走回源重拉。
            // 在架书目录已落库 (DB 为权威), 不写避免残留过期内存目录 (与 TOC 交接的
            // !inBookshelf 条件一致)。
            if (!screenModel.state.value.inBookshelf) {
                IntentData.chapterList = screenModel.loadedChapterList
            }
            val target = when {
                b.isAudio -> AppRoute.AudioPlay(b.toRouteRef())
                b.isVideo -> AppRoute.VideoPlay(b.toRouteRef())
                b.isImage -> AppRoute.MangaReader(b.toRouteRef())
                b.isRss -> AppRoute.ReadRss(b.toRouteRef())
                else -> AppRoute.Reader(b.toRouteRef())
            }
            navigator.push(target, RouteResults.READER)
        }

        // 来源点击: 编辑书源 (对照 Activity editSourceResult, 非 ChangeSource)
        override fun onOriginClick() {
            if (book.isLocal) return
            navigator.push(AppRoute.BookSourceEdit(book.origin), RouteResults.BOOK_SOURCE_EDIT)
        }

        // 来源长按: 换源弹窗 (对照原版 showDialogFragment(ChangeBookSourceDialog), 全高底部弹窗同阅读页)
        override fun onOriginLongClick() {
            showChangeSourceDialog = true
        }

        // 目录
        override fun onTocClick() {
            // 对照 app 端 BookInfoActivity: 未加书架的书目录不落库, 用内存章节表跨页传递;
            // 书籍取刷新后的 state.book, 否则 totalChapterNum 还是进页时的旧值(目录会被截断)
            IntentData.chapterList = screenModel.loadedChapterList
            navigator.push(
                AppRoute.Toc((state.book ?: book).toRouteRef()),
                RouteResults.TOC,
            )
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

        // 刷新: 重新拉取书籍信息 + 目录
        override fun onRefresh() {
            screenModel.refresh(
                state.book ?: book, bookSource, errorLoadTocLabel, isSearchBook = isSearchBook
            )
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

        // 登录: 统一登录入口, URL 登录桌面端直开登录窗口 (2026-08-07);
        // 表单登录弹 Overlay (带上当前书, 对照原版 menu_login 预置 IntentData.book)
        override fun onLogin() {
            val b = state.book ?: book
            val source = bookSource
            showSourceLogin(source?.getKey() ?: b.origin, source, b)
        }

        // 评论: Android 恢复原版 BottomSheet 对话框 (全功能交互+提交), 其余平台回退共享列表页
        override fun onOpenCommentDialog() {
            if (PlatformCapabilityProviders.get().showReviewListDialog(book, null, -1)) return
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

        // 切换拆分长章: 修改 config + 驱动重组 + 重新加载书籍信息
        override fun onToggleSplitLongChapter() {
            val b = state.book ?: return
            val newValue = !b.config.splitLongChapter
            b.config.splitLongChapter = newValue
            screenModel.dispatch(BookInfoUiEvent.BumpBookTick)
            screenModel.refresh(b, bookSource, errorLoadTocLabel, isSearchBook = isSearchBook)
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

        // 书名点击：按原版带入书名并立即搜索。
        override fun onNameClick() {
            navigator.push(AppRoute.Search(key = (state.book ?: book).name, submit = true))
        }

        // 封面点击: 查看大图 (payload 随带书源身份: 图片查看器按书源走防盗链 header +
        // coverDecodeJs 封面解密完整链路, 与列表封面 SharedBookCover 同款身份;
        // 本地书/无书源不传, 保持裸 GET)
        override fun onCoverClick() {
            val b = state.book ?: book
            b.getDisplayCover()?.let { cover ->
                navigator.showOverlay(
                    AppOverlay.Dialog(
                        key = "photo",
                        payload = cover,
                        sourceOrigin = b.origin.takeIf { !b.isLocal && it.isNotBlank() },
                    )
                )
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
                ?.toggleBookshelf(
                    b, state.inBookshelf,
                    onComplete = { result ->
                        if (result == null) navigator.pop(RouteResultPayload.Deleted)
                        else screenModel.dispatch(BookInfoUiEvent.UpdateBookshelf(result))
                    },
                    onWaitDialog = { screenModel.upWaitDialog(it) },
                    onAction = { screenModel.postAction(it) },
                )
        }

        // 搜索作者: :: 探索直接用内存 bookSource (对照 master search(): IntentData.source =
        // curBookSource 不查库, ExploreShow 变体 source 直传); 普通搜索跳 Search
        override fun onSearchAuthor(author: String, submit: Boolean) {
            val tmp = author.split("::", limit = 2)
            if (tmp.size > 1) {
                val source = bookSource ?: return
                navigator.push(AppRoute.ExploreShow(source, tmp[0], tmp[1]))
            } else {
                navigator.push(
                    AppRoute.Search(
                        key = tmp[0],
                        searchScope = bookSource?.takeIf { it.searchUrl != null }
                            ?.let { SearchScope(it).toString() },
                        submit = submit,
                    )
                )
            }
        }

        // 搜索分类: :: 探索需 BookSource, 加载后跳 ExploreShow; 普通搜索限定当前书源
        override fun onSearchKind(kind: String, submit: Boolean) {
            val tmp = kind.split("::", limit = 2)
            if (tmp.size > 1) {
                val source = bookSource ?: return
                navigator.push(AppRoute.ExploreShow(source, tmp[0], tmp[1]))
            } else {
                navigator.push(
                    AppRoute.Search(
                        key = tmp[0],
                        searchScope = bookSource?.takeIf { it.searchUrl != null }
                            ?.let { SearchScope(it).toString() },
                        submit = submit,
                    )
                )
            }
        }

        // 简介动作: JS 派发, 委托平台 (需 BookSource + JS 引擎)
        override fun onDispatchIntroAction(action: String) {
            val js = action.trim().ifEmpty { return }
            PlatformCapabilityProviders.getOrNull()?.evalIntroAction(state.book ?: book, js)
        }

        // 查看简介图片 (同封面: 带书源身份, 简介图防盗链/解密与封面同链路)
        override fun onShowPhoto(src: String) {
            val b = state.book ?: book
            navigator.showOverlay(
                AppOverlay.Dialog(
                    key = "photo",
                    payload = src,
                    sourceOrigin = b.origin.takeIf { !b.isLocal && it.isNotBlank() },
                )
            )
        }
    }

    DisposableEffect(entry.id, actions) {
        navigator.registerRefreshHandler(entry.id, actions::onRefresh)
        onDispose { navigator.unregisterRefreshHandler(entry.id) }
    }

    // 事件订阅作用域: 各 launch 独立收集路由结果与 FlowBus
    LaunchedEffect(Unit) {
        coroutineScope {
            // 换封面对话框返回：同步当前书籍封面并驱动详情封面、模糊背景重载。
            launch {
                navigator.overlayResults
                    .filter { it.key == RouteResults.OVERLAY_CHANGE_COVER }
                    .collect { result ->
                        val coverUrl = (result.payload as? RouteResultPayload.ChangeCover)?.coverUrl
                            ?: return@collect
                        val b = screenModel.state.value.book ?: book
                        b.customCoverUrl = coverUrl
                        if (screenModel.state.value.inBookshelf) {
                            withContext(IoDispatcher) { AppDbProviders.get().bookDao.update(b) }
                        }
                        screenModel.dispatch(BookInfoUiEvent.BumpCoverTick)
                    }
            }
            // 分组选择对话框返回：应用分组 + 落库 + 刷新分组名 (对照 app 端 upGroup + saveBook)
            launch {
                navigator.overlayResults
                    .filter { it.key == RouteResults.OVERLAY_GROUP_SELECT }
                    .collect { result ->
                        val groupId = (result.payload as? RouteResultPayload.GroupSelect)?.groupId
                            ?: return@collect
                        val b = screenModel.state.value.book ?: book
                        b.group = groupId
                        // 在书架: 直接更新; 不在书架且选了非"未分组": 去 notShelf 后入库
                        // (对照 app 端 addToBookshelf → Book.save)
                        if (screenModel.state.value.inBookshelf) {
                            withContext(IoDispatcher) { AppDbProviders.get().bookDao.update(b) }
                        } else if (groupId > 0) {
                            withContext(IoDispatcher) {
                                b.removeType(BookType.notShelf)
                                val db = AppDbProviders.get()
                                if (db.bookDao.has(b.bookUrl)) db.bookDao.update(b)
                                else db.bookDao.insert(b)
                            }
                            screenModel.dispatch(BookInfoUiEvent.UpdateBookshelf(true))
                        }
                        // 刷新分组名 label (对照 app 端 upGroup → loadGroup)
                        val groupName = try {
                            AppDbProviders.get().bookGroupDao
                                .getGroupNames(groupId).joinToString(",")
                        } catch (e: Throwable) {
                            ""
                        }
                        screenModel.dispatch(
                            BookInfoUiEvent.UpdateGroup(
                                groupName.takeIf { it.isNotEmpty() } ?: noGroupLabel
                            )
                        )
                    }
            }
            // 对照 Activity observeLiveBus: EventBus.REFRESH_BOOK_INFO → refreshBook
            launch {
                FlowBus.with(EventBus.REFRESH_BOOK_INFO).collect {
                    val b = screenModel.state.value.book ?: book
                    screenModel.refresh(
                        b,
                        bookSource,
                        errorLoadTocLabel,
                        isSearchBook = isSearchBook
                    )
                }
            }
            // 路由结果统一收集: resultsFor 按 entry 返回同一个 Channel, 每页只应收集一次,
            // 多次 filter+collect 会互相抢元素 (首个 collector 吃掉全部回执, 不匹配的 key 被丢弃),
            // 导致目录/换源/书源编辑/阅读器回执丢失。改为单 collect + when 分发 (同 ReaderRoute)。
            launch {
                navigator.resultsFor(entry.id).collect { result ->
                    when (result.key) {
                        // 书籍信息编辑返回: 接收编辑后的 Book 替换内存对象 (对照 app 端
                        // viewModel.upEditBook → bookData.postValue(IntentData.book as? Book)):
                        // 只驱动 UI 更新, 不网络重拉不写 DB (网络重拉会把刚保存的
                        // customCoverUrl 顶回旧值)
                        RouteResults.BOOK_INFO_EDIT -> {
                            val edited = (result.payload as? RouteResultPayload.BookEdited)?.book
                                ?: return@collect
                            screenModel.dispatch(
                                BookInfoUiEvent.ShowBook(
                                    edited,
                                    screenModel.lastedTitleOf(edited),
                                )
                            )
                            screenModel.dispatch(BookInfoUiEvent.BumpCoverTick)
                        }

                        // 书源编辑返回: 直接采用回传的已保存 source 对象 (对照 master
                        // upSource → curBookSource = IntentData.source, 不查库); 同步赋值
                        // 消除返回后立刻点登录/看菜单读到旧源的竞态
                        RouteResults.BOOK_SOURCE_EDIT -> {
                            val source =
                                (result.payload as? RouteResultPayload.BookSourceEdit)?.source
                                    ?: return@collect
                            bookSource = source
                            // 顺手同步源名到内存 book (对照 refresh 内 originName 同步, 不落库),
                            // 消除改源名后详情页来源行/后续传阅读页仍显示旧名
                            val b = screenModel.state.value.book ?: book
                            if (b.originName != source.bookSourceName) {
                                b.originName = source.bookSourceName
                                screenModel.dispatch(BookInfoUiEvent.BumpBookTick)
                            }
                        }

                        // 目录返回: 选章节跳阅读, 未选则删书 (对照 app 端 BookInfoActivity tocActivityResult)
                        RouteResults.TOC -> {
                            val payload = result.payload as? RouteResultPayload.Toc
                            val b = screenModel.state.value.book ?: book
                            if (payload != null) {
                                // 对照原版 tocActivityResult: 先把 book.durChapterIndex/durChapterPos
                                // 改为选中章节, await 落库完成后再打开阅读 (只带 chapterChanged, 不带
                                // chapterIndex/chapterPos 定位 extra) —— 阅读页属正常打开而非跳转,
                                // 不触发 lastBookProgress 快照机制 (返回不弹"恢复进度"对话框);
                                // await 落库避免异步整行 update 与阅读退出时 updateProgress(PATCH)
                                // 竞态: 晚到的整行写会用旧 dur 覆盖最新进度 → 书架显示旧进度
                                b.durChapterIndex = payload.chapterIndex
                                b.durChapterPos = payload.chapterPos
                                val target = when {
                                    b.isAudio -> AppRoute.AudioPlay(b.toRouteRef())
                                    b.isVideo -> AppRoute.VideoPlay(b.toRouteRef())
                                    b.isImage -> AppRoute.MangaReader(b.toRouteRef())
                                    b.isRss -> AppRoute.ReadRss(b.toRouteRef())
                                    else -> AppRoute.Reader(b.toRouteRef())
                                }
                                // 目录内存交接 (对照原版 startReadActivity: IntentData.chapterList
                                // = chapterListData.value)。onTocClick 写入的 IntentData.chapterList
                                // 已被目录页消费 (取一次即失效), 阅读页打开前重新写入内存目录;
                                // 未加书架的书目录不落库, 不交接则阅读页读库落空后回源重拉目录
                                // (表现为打开阅读页的加载空白/卡顿)。在架书优先 DB (目录页反转等
                                // 已持久化, DB 为最新权威), 不做交接避免残留旧实例
                                if (!screenModel.state.value.inBookshelf) {
                                    IntentData.chapterList = screenModel.loadedChapterList
                                }
                                scope.launch {
                                    withContext(IoDispatcher) {
                                        AppDbProviders.get().bookDao.update(b)
                                    }
                                    navigator.push(target, RouteResults.READER)
                                }
                            }
                            // 原版此处还有 `if (!inBookshelf) viewModel.delBook()` 回收临时书,
                            // 本分支不需要: 未入架的书从不落库 (详情页 saveBook/章节 insert 均有
                            // inBookshelf 门禁, 目录页的写库对无 books 行的书被 FK 约束挡掉),
                            // 无库可清; 删了反而会误弹确认框并退出详情页。
                        }

                        // 阅读器返回: 刷新阅读进度 + 书架状态 (对照 app 端 readBookResult launcher)
                        RouteResults.READER -> {
                            val b = screenModel.state.value.book ?: book
                            // 书籍可能在阅读中被加入书架/删除, 重新查询 DB 同步状态
                            scope.launch(IoDispatcher) {
                                val inShelf =
                                    AppDbProviders.get().bookDao.getBook(b.bookUrl) != null
                                screenModel.dispatch(BookInfoUiEvent.UpdateBookshelf(inShelf))
                                // 刷新目录文案为当前阅读章节 (对照 Activity upLoading(false, listOf()))
                                screenModel.dispatch(
                                    BookInfoUiEvent.UpdateToc(
                                        tocText = b.durChapterTitle ?: "",
                                        lastedTitle = null,
                                    )
                                )
                            }
                            // 阅读器返回 Deleted: 透传删除 (对照 Activity RESULT_DELETED)
                            if (result.payload is RouteResultPayload.Deleted) {
                                navigator.pop(RouteResultPayload.Deleted)
                            }
                        }
                    }
                }
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
        isWebDav = currentBook.origin.startsWith(BookType.webDavTag),
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

    // L3: 模糊封面背景 / 简介图依赖平台 Glide/AndroidView, 由平台通过 CompositionLocal 注入;
    // 封面统一走 BookInfoCover (内部委托 LocalBookCoverSlot 默认 SharedBookCover)
    val isEInkMode = AppConfigProviders.get().isEInkMode
    val blurCoverBgSlot = LocalBlurCoverBgSlot.current
    val introImageSlot = LocalIntroImageSlot.current
    BookInfoScreen(
        state = screenState,
        actions = actions,
        blurCoverBgSlot = { modifier, land ->
            // 适配 (Modifier,Boolean)->Unit 到 (Book?,Int,Boolean,Boolean,Modifier,Boolean)->Unit 签名
            blurCoverBgSlot(
                currentBook,
                state.coverTick,
                state.inBookshelf,
                isEInkMode,
                modifier,
                land
            )
        },
        coverSlot = { book, modifier ->
            BookInfoCover(book, state.coverTick, modifier)
        },
        introImageSlot = { src, onClick ->
            introImageSlot(src, onClick)
        },
    )

    // 整书换源弹窗 (对照原版长按来源 → ChangeBookSourceDialog 全高底部弹窗, 与阅读页换源同款)
    if (showChangeSourceDialog) {
        ChangeSourceDialogHost(
            book = state.book ?: book,
            onSourceChanged = { source, newBook, toc ->
                showChangeSourceDialog = false
                // bookSource 由 LaunchedEffect(book.origin) 按路由书籍加载, 换源后不会自动更新
                bookSource = source
                scope.launch(IoDispatcher) {
                    runCatching {
                        // 对照 master BookInfoActivity.changeTo → BaseReadViewModel.changeTo:
                        // 迁移进度/分组/在架位等字段, 在架则删旧插新 + 落目录,
                        // 否则仅内存交接 (未入架书不落库)
                        val oldBook = screenModel.state.value.book ?: book
                        oldBook.migrateTo(newBook, toc)
                        if (screenModel.state.value.inBookshelf) {
                            newBook.removeType(BookType.updateError)
                            val db = AppDbProviders.get()
                            db.bookDao.delete(oldBook)
                            db.bookDao.insert(newBook)
                            db.bookChapterDao.insert(*toc.toTypedArray())
                        }
                    }.onFailure {
                        AppLog.put("换源失败\n${it.message}", it, true)
                    }
                    screenModel.dispatch(
                        BookInfoUiEvent.ShowBook(newBook, screenModel.lastedTitleOf(newBook))
                    )
                    // 重新拉取新书信息 + 目录 (对照原版 changeTo 后 onSourceChanged 刷新)
                    screenModel.refresh(
                        newBook, source, errorLoadTocLabel,
                        isSearchBook = isSearchBook,
                    )
                }
            },
            onEditSource = { origin ->
                navigator.push(AppRoute.BookSourceEdit(origin), RouteResults.BOOK_SOURCE_EDIT)
            },
            onBookSourceManage = { navigator.push(AppRoute.BookSourceManage) },
            onDismiss = { showChangeSourceDialog = false },
        )
    }

    // 等待对话框 (对照 app 端 BookInfoActivity.upWaitDialogStatus, webFile 流程加载指示)
    val waitDialogVisible by screenModel.waitDialog.collectAsState()
    WaitDialog(
        visible = waitDialogVisible == true,
        onDismissRequest = { screenModel.upWaitDialog(false) },
    )
}
