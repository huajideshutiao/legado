package io.legado.app.ui.route

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.fleeksoft.ksoup.Ksoup
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.RssToolbarActions
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.showSourceLogin
import io.legado.app.help.toast.Toasters
import io.legado.app.help.tts.OneShotTts
import io.legado.app.model.rss.RssHelp
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.book.rss.ReadRssScreen
import io.legado.app.ui.book.rss.ReadRssScreenModel
import io.legado.app.ui.book.rss.ReadRssUiActions
import io.legado.app.ui.book.rss.ReadRssUiEvent
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.browser.WebViewCallbacks
import io.legado.app.ui.browser.WebViewConfig
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.rss.ReadRssViewModelShared
import io.legado.app.ui.rss.RssJsActions
import io.legado.app.ui.rss.createRssJsBinding
import io.legado.app.utils.EscapeUtils
import io.legado.app.utils.isTrue
import io.legado.app.utils.textArray
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.sure_del
import org.jetbrains.compose.resources.stringResource
import io.legado.app.ui.rss.ReadRssUiState as SharedReadRssUiState

/**
 * RSS 阅读 shared 路由入口, 对照 app 端原 [io.legado.app.ui.book.rss.ReadRssActivity]。
 *
 * 与原版的对应关系:
 * - 正文/地址加载 → [ReadRssViewModelShared] (内部 [RssHelp.loadRssContent]), 两种形态都交
 *   [LocalWebViewSlot] 注入的平台 WebView, 图片/视频/webJs/Cookie/UA 与原版一致;
 * - 收藏/取消收藏 → [RssHelp.addToBookshelf] / [RssHelp.removeFromBookshelf] 真实落库,
 *   删除前按 `bookInfoDeleteAlert` 弹确认 (对照原版 menu_rss_star 分支), 删完 pop 回传 Deleted;
 * - 朗读 → 抓 WebView 的 outerHTML → Ksoup 取文本 → [OneShotTts] (对照原版 readAloud);
 * - 跳转拦截 → 书源 `contentRule.shouldOverrideUrlLoading` JS, `java` 绑定含 searchBook/addBook
 *   (对照原版 RssJsExtensions);
 * - 标题跟随网页 title、视频全屏隐藏标题栏 → [WebViewCallbacks] 的对应回调。
 */
@Composable
fun ReadRssRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.ReadRss
    // asBook() 每次 copy() 新实例, remember(route) 固定后 LaunchedEffect(book) 只在换路由时重启
    val book = remember(route) { route.book.asBook() }

    val screenModel = screenModelStore.getOrCreateTyped(entry) { ReadRssScreenModel() }
    val state by screenModel.state.collectAsState()

    val scope = rememberCoroutineScope()
    val shared = remember { ReadRssViewModelShared(scope) }
    val tts = remember { OneShotTts() }
    // 当前书源: 登录项可见性 + 跳转拦截 JS 都要它
    var source by remember { mutableStateOf<BookSource?>(null) }
    // 收藏态: 原版 inBookshelf = !book.isNotShelf
    var inShelf by remember(book) { mutableStateOf(!book.isNotShelf) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // WebView 事件桥: 标题/全屏/跳转拦截/朗读取文
    val callbacks = remember { WebViewCallbacks() }

    // 2026-08-07 桌面端 RSS 直开窗: 平台声明直开独立窗口时跳过页面外壳渲染,
    // 内容就绪后经 openRssReader 开窗, 收藏/朗读/分享/登录移入窗口工具栏
    val desktopDirect = PlatformCapabilityProviders.getOrNull()?.rssDirectWindow == true
    // 窗口工具栏动作: 闭包引用可变 state (inShelf/state/source), 始终读到最新值
    lateinit var rssActions: RssToolbarActions
    // 页面外壳: 桌面端初始不渲染 (避免中转界面); 平台未处理时回退外壳
    var showShell by remember { mutableStateOf(!desktopDirect) }
    var rssWindowOpened by remember { mutableStateOf(false) }

    LaunchedEffect(book) {
        screenModel.dispatch(ReadRssUiEvent.TitleChanged(book.name))
        val loaded = withContext(IoDispatcher) {
            AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
        }
        source = loaded
        // 对照原版 upStarMenu: 有书才显示收藏钮; onMenuOpened 里按 hasLogin 决定登录项
        screenModel.dispatch(
            ReadRssUiEvent.StarMenuUpdated(
                starVisible = true,
                inShelf = inShelf,
                hasLogin = loaded?.hasLogin() == true,
            )
        )
        launch {
            shared.state.collect { s ->
                when (s) {
                    SharedReadRssUiState.Loading ->
                        screenModel.dispatch(ReadRssUiEvent.Load)

                    is SharedReadRssUiState.Content -> screenModel.dispatch(
                        ReadRssUiEvent.WebContentReady(
                            config = WebViewConfig(
                                url = s.baseUrl,
                                html = s.html,
                                headerMap = s.userAgent
                                    ?.let { mapOf(AppConst.UA_NAME to it) } ?: emptyMap(),
                            ),
                            chapter = s.chapter,
                        )
                    )

                    is SharedReadRssUiState.Url -> screenModel.dispatch(
                        ReadRssUiEvent.WebContentReady(
                            config = WebViewConfig(url = s.url, headerMap = s.headerMap),
                            chapter = s.chapter,
                        )
                    )

                    is SharedReadRssUiState.Error ->
                        screenModel.dispatch(ReadRssUiEvent.LoadError(s.message, s.chapter))
                }
            }
        }
        shared.loadContent(book, book.durChapterIndex)
    }

    // 朗读状态 → 菜单文案 (对照原版 upTtsMenu)
    LaunchedEffect(tts) {
        tts.setStateListener { playing ->
            screenModel.dispatch(ReadRssUiEvent.TtsStateChanged(playing))
        }
    }

    // WebView 回调接线: 标题跟随 / 全屏 / 跳转拦截
    LaunchedEffect(source) {
        callbacks.onReceivedTitle = { title ->
            // 原版: 网页 title 非空且不等于 url 时才用它, 否则回退书名
            if (!title.isNullOrBlank() && !title.startsWith("http")) {
                screenModel.dispatch(ReadRssUiEvent.TitleChanged(title))
            }
        }
        callbacks.onFullScreenChanged = { full ->
            screenModel.dispatch(ReadRssUiEvent.VideoFullScreenChanged(full))
        }
        callbacks.shouldOverrideUrl = { url -> interceptUrl(url, source, scope) }
    }

    fun removeFromShelf() {
        scope.launch {
            runCatching { withContext(IoDispatcher) { RssHelp.removeFromBookshelf(book) } }
                .onSuccess {
                    // 同步窗口工具栏星图标 (桌面端直开窗)
                    rssActions.starred = false
                    rssActions.onStarChanged?.invoke(false)
                    navigator.pop(RouteResultPayload.Deleted)
                }
                .onFailure { Toasters.get().toast(it.message ?: "删除失败") }
        }
    }

    /**
     * 收藏/取消收藏 (页面外壳与窗口工具栏共用, 对照原版 menu_rss_star 分支)。
     * @param confirmDelete 删除收藏前是否弹确认框: 页面外壳路径保留 (原版语义),
     *   窗口工具栏路径跳过 (桌面端确认框弹在主窗口, RSS 窗口在前台会被遮挡, 2026-08-07)
     */
    fun toggleStar(confirmDelete: Boolean = true) {
        if (inShelf) {
            val alert = confirmDelete && PreferenceProviders.get()
                .getBoolean(PreferKey.bookInfoDeleteAlert, true)
            if (alert) showDeleteConfirm = true else removeFromShelf()
        } else {
            scope.launch {
                runCatching { withContext(IoDispatcher) { RssHelp.addToBookshelf(book) } }
                    .onSuccess {
                        inShelf = true
                        // 同步窗口工具栏星图标 (桌面端直开窗)
                        rssActions.starred = true
                        rssActions.onStarChanged?.invoke(true)
                        screenModel.dispatch(
                            ReadRssUiEvent.StarMenuUpdated(
                                starVisible = true,
                                inShelf = true,
                                hasLogin = source?.hasLogin() == true,
                            )
                        )
                    }
                    .onFailure { Toasters.get().toast(it.message ?: "收藏失败") }
            }
        }
    }

    // 窗口工具栏动作 (2026-08-07): 收藏/朗读/分享/登录, 经 openRssReader 注入窗口。
    // 窗口按钮在平台 UI 线程 (WebView2 loop/GTK 线程) 回调 → 统一 scope.launch 归队主线程
    // 再碰 Compose 状态 (reviewer 2026-08-07); onReadAloud 已自行 launch, 无需再包。
    rssActions = remember(book) {
        RssToolbarActions(
            starred = !book.isNotShelf,
            onStarToggle = { scope.launch { toggleStar(confirmDelete = false) } },
            onReadAloud = { htmlProvider ->
                scope.launch {
                    // 朗读中再点 = 停止 (对照原版 readAloud 分支)
                    if (tts.isSpeaking) {
                        tts.stop()
                        return@launch
                    }
                    val html = htmlProvider()
                    if (html.isNullOrBlank()) return@launch
                    tts.speak(Ksoup.parse(html).textArray().joinToString("\n"))
                }
            },
            onShare = {
                scope.launch {
                    val url = state.currArticle?.url ?: book.tocUrl
                    PlatformCapabilityProviders.getOrNull()?.shareText(url)
                }
            },
            onLogin = { scope.launch { showSourceLogin(book.origin, source) } },
        )
    }

    val actions = object : ReadRssUiActions {
        override fun onBack() {
            navigator.pop()
        }

        // 刷新: 对照原版 refresh — 有正文规则才重新拉, 否则让 WebView 自己 reload
        override fun onRefresh() {
            shared.loadContent(book, book.durChapterIndex)
        }

        override fun onToggleStar() {
            toggleStar()
        }

        // 分享: 优先当前文章地址, 回退 tocUrl (对照原版 menu_share_it)
        override fun onShare() {
            val url = state.currArticle?.url ?: book.tocUrl
            PlatformCapabilityProviders.getOrNull()?.shareText(url)
        }

        // 朗读: 抓 WebView 当前 DOM 文本交 TTS (对照原版 readAloud)
        override fun onReadAloud() {
            if (tts.isSpeaking) {
                tts.stop()
                return
            }
            val host = callbacks.host
            if (host == null) {
                Toasters.get().toast("当前平台暂不支持：朗读")
                return
            }
            host.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                val html = EscapeUtils.unescapeJson(raw.orEmpty()).replace("^\"|\"$".toRegex(), "")
                if (html.isBlank()) return@evaluateJavascript
                tts.speak(Ksoup.parse(html).textArray().joinToString("\n"))
            }
        }

        override fun onOpenInBrowser() {
            val url = state.currArticle?.url ?: book.tocUrl
            PlatformCapabilityProviders.getOrNull()?.openExternalUrl(url)
        }

        override fun onLogin() {
            // 统一登录入口: URL 登录桌面端直开登录窗口 (2026-08-07); 表单登录弹 Overlay
            // (源按 book.origin 查库, 已加载的 source 直接参与短路判断)
            showSourceLogin(book.origin, source)
        }
    }

    // 桌面端: 内容就绪后直开 RSS 窗口 (2026-08-07 用户拍板: 去外壳, 功能入窗口工具栏)
    LaunchedEffect(state.webConfig) {
        val config = state.webConfig ?: return@LaunchedEffect
        if (desktopDirect && !rssWindowOpened) {
            val opened = PlatformCapabilityProviders.getOrNull()?.openRssReader(
                book = book,
                chapter = state.currArticle,
                url = config.url,
                html = config.html,
                headerMap = config.headerMap,
                actions = rssActions,
            ) == true
            if (opened) {
                rssWindowOpened = true
            } else {
                // 平台未处理 (不应发生): 回退页面外壳
                showShell = true
            }
        }
    }

    // 桌面端无外壳: 加载失败直接 toast 提示 (移动端由外壳显示错误文本)
    LaunchedEffect(state.error) {
        state.error?.let { Toasters.get().toast(it) }
    }

    // 路由出栈 → 关闭 RSS 窗口 (桌面端直开窗, onDetach 由平台实现挂接; 移动端为 no-op)
    DisposableEffect(route) {
        onDispose { rssActions.onDetach?.invoke() }
    }

    if (showShell) {
        ReadRssScreen(
            state = state,
            actions = actions,
            platformWebViewSlot = { config ->
                LocalWebViewSlot.current(config, Modifier.fillMaxSize(), callbacks)
            },
        )
    }

    // 取消收藏二次确认 (对照原版 AppConfig.bookInfoDeleteAlert 分支)
    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del),
            okButton = AlertButton(stringResource(Res.string.ok)) { removeFromShelf() },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
}

/**
 * 书源 `contentRule.shouldOverrideUrlLoading` 跳转拦截 (对照原版 CustomWebViewClient.interceptUrl)。
 *
 * 返回 true = 已被 JS 处理, WebView 不再加载。jsbridge scheme 一律放行 (原版同)。
 * JS 在 WebView 线程同步执行, 与原版一致 (原版也是在 shouldOverrideUrlLoading 里同步跑)。
 */
private fun interceptUrl(
    url: String,
    source: BookSource?,
    scope: kotlinx.coroutines.CoroutineScope,
): Boolean {
    val js = source?.contentRule?.shouldOverrideUrlLoading
    if (source != null && !js.isNullOrBlank()) {
        val result = runCatching {
            runScriptWithContext(scope.coroutineContext) {
                source.evalJS(js) {
                    put("java", createRssJsBinding(source, RssJsActions(scope)))
                    put("url", url)
                }.toString()
            }
        }.onFailure {
            AppLog.put("${source.getTag()}: url跳转拦截js出错", it)
        }.getOrNull()
        if (result.isTrue()) return true
    }
    // jsbridge 交回 WebView 自己处理; 其余非 http scheme 由平台 slot 决定 (原版走 shouldOverrideUrl)
    return false
}
