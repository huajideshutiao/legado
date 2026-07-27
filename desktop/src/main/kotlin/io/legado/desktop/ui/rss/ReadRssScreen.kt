package io.legado.desktop.ui.rss

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.toast.Toasters
import io.legado.app.help.tts.ReadAloudQueue
import io.legado.app.model.rss.RssHelp
import io.legado.app.service.ReadAloudChapterNavigator
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.rss.ReadRssUiState
import io.legado.app.ui.rss.ReadRssViewModelShared
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.browseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 桌面端 RSS 文章阅读 Screen (对应 app 端 [io.legado.app.ui.book.rss.ReadRssActivity])。
 *
 * # 设计
 *
 * 业务逻辑 (取书源 / 取章节 / 三态分支拉取正文) 下沉到 shared
 * [ReadRssViewModelShared] (commonMain), 本 Screen 仅负责:
 * - UI 渲染 + 平台 Provider 注入
 * - 桌面端特定正文格式化 ([HtmlFormatter.format] 转 HTML 为纯文本, 因桌面端无 WebView)
 * - 桌面端特定外链打开 ([browseUrl] 走 java.awt.Desktop.browse)
 *
 * # 加载流程 (委托 [ReadRssViewModelShared.loadContent])
 *
 * 对照 app 端 [io.legado.app.ui.book.rss.ReadRssViewModel.initData] + `loadContent`:
 * 1. 取关联的 BookSource (`book.origin` = `bookSource.bookSourceUrl`)
 * 2. 取章节 BookChapter (`bookChapterDao.getChapter(bookUrl, chapterIndex)`)
 * 3. 分支 (由 [io.legado.app.model.rss.RssHelp.loadRssContent] 处理):
 *    - 若 `book.originName == "RSS" && !book.intro.isNullOrBlank()` → 直接显示 intro (RSS 源简介)
 *    - 否则若 `source.contentRule.content` 非空 → [io.legado.app.model.webBook.WebBook.getContentAwait] 拉取正文
 *    - 否则 (无正文规则) → UI 提示 "无正文规则, 请用浏览器打开"
 *
 * # 顶栏操作
 *
 * - 刷新: 重新触发拉取
 * - 浏览器打开: 用 [browseUrl] (java.awt.Desktop.browse) 启动系统浏览器打开 `chapter.url`
 *   (替代 app 端 WebView 直接渲染无规则正文的能力)
 * - 收藏/分享/朗读/登录: 对照 app 端 ReadRssActivity 菜单 (收藏走 [RssHelp.addToBookshelf],
 *   分享降级复制链接, 朗读走 [ReadAloudControllerShared] 单章模式, 登录走 [SourceLoginDialog])
 *
 * @param book RSS 源对应的 Book (type 含 BookType.rss 位)
 * @param chapterIndex 章节 index (对应 RssArticlesScreen 点击回调)
 * @param onBack 返回回调 (切回 RSS_ARTICLES 路由)
 */
@Composable
fun ReadRssScreen(
    book: Book,
    chapterIndex: Int,
    onBack: () -> Unit,
) {
    // 注入 desktop 平台 Provider (commonMain AppTheme 依赖)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            ReadRssContent(book = book, chapterIndex = chapterIndex, onBack = onBack)
        }
    }
}

@Composable
private fun ReadRssContent(
    book: Book,
    chapterIndex: Int,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    // 复用 shared ReadRssViewModelShared (加载流程 + 状态管理下沉 commonMain)
    val viewModel = remember { ReadRssViewModelShared(scope) }
    val state by viewModel.state.collectAsState()
    // i18n 文案 (refresh / open_in_browser 为桌面端 key, rss_no_content_rule 提示空正文)
    val refreshLabel = rememberString("refresh")
    val openInBrowserLabel = rememberString("open_in_browser")
    val noContentRuleHintLabel = rememberString("rss_no_content_rule_hint")
    val inFavoritesLabel = rememberString("in_favorites")
    val outFavoritesLabel = rememberString("out_favorites")
    val shareLabel = rememberString("share")
    val readAloudLabel = rememberString("read_aloud")
    val aloudStopLabel = rememberString("aloud_stop")
    val loginLabel = rememberString("login")
    val copiedLabel = rememberString("copied_to_clipboard")

    // 收藏状态 (对照 app 端 inShelf = !book.isNotShelf) + 书源 (登录菜单项判断用)
    var inShelf by remember(book.bookUrl) { mutableStateOf(!book.isNotShelf) }
    var source by remember { mutableStateOf<BookSource?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    LaunchedEffect(book.origin) {
        source = withContext(Dispatchers.IO) {
            AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
        }
    }

    // 朗读: 复用 shared 段级朗读控制器, RSS 正文当单章处理 (对照 app 端 readAloud → TTS.speak)
    val readAloud = remember {
        ReadAloudControllerShared(object : ReadAloudChapterNavigator {
            override val chapterCount get() = 1
            override fun loadChapterParagraphs(chapterIndex: Int): List<String> {
                val s = viewModel.state.value as? ReadRssUiState.Content ?: return emptyList()
                return ReadAloudQueue.splitParagraphs(HtmlFormatter.format(s.body))
            }

            override fun moveToChapter(chapterIndex: Int) = Unit
            override fun moveToNextChapter() = Unit
            override fun moveToPrevChapter() = Unit
        })
    }
    val aloudState by readAloud.state.collectAsState()
    val aloudPlaying = aloudState == ReadAloudControllerShared.ReadAloudState.PLAYING
    DisposableEffect(Unit) {
        onDispose { readAloud.stop() }
    }

    // 从 state 提取当前章节 (供顶栏标题 + "浏览器打开" 按钮使用)
    val currentChapter: BookChapter? = when (val s = state) {
        is ReadRssUiState.Content -> s.chapter
        is ReadRssUiState.Empty -> s.chapter
        is ReadRssUiState.Error -> s.chapter
        ReadRssUiState.Loading -> null
    }

    // 首次进入触发加载
    LaunchedEffect(book.bookUrl, chapterIndex) {
        viewModel.loadContent(book, chapterIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppTitleBar(
            title = currentChapter?.title ?: book.name,
            onBack = onBack,
            actions = {
                // 刷新按钮: 重新触发联网拉取
                IconButton(onClick = { viewModel.loadContent(book, chapterIndex) }) {
                    Icon(
                        painter = rememberPainter("ic_refresh_black_24dp"),
                        contentDescription = refreshLabel,
                        tint = colors.primaryText,
                    )
                }
                // 收藏/取消收藏 (对照 app 端 toggleStar: 取消收藏后返回上级)
                IconButton(onClick = {
                    scope.launch {
                        if (inShelf) {
                            withContext(Dispatchers.IO) { RssHelp.removeFromBookshelf(book) }
                            onBack()
                        } else {
                            withContext(Dispatchers.IO) { RssHelp.addToBookshelf(book) }
                            inShelf = true
                        }
                    }
                }) {
                    Icon(
                        painter = rememberPainter(if (inShelf) "ic_star" else "ic_star_border"),
                        contentDescription = if (inShelf) inFavoritesLabel else outFavoritesLabel,
                        tint = colors.primaryText,
                    )
                }
                // 分享: 桌面端复制链接到剪贴板替代 (对照 app 端 shareUrl)
                IconButton(onClick = {
                    val shareUrl = currentChapter?.url?.takeIf { it.isNotBlank() }
                        ?: book.tocUrl.ifBlank { book.bookUrl }
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(shareUrl), null)
                    Toasters.get().toast(copiedLabel)
                }) {
                    Icon(
                        painter = rememberPainter("ic_share"),
                        contentDescription = shareLabel,
                        tint = colors.primaryText,
                    )
                }
                // 朗读/停止 (对照 app 端 readAloud 切换)
                IconButton(onClick = {
                    if (aloudPlaying) readAloud.stop() else readAloud.start(0)
                }) {
                    Icon(
                        painter = rememberPainter(
                            if (aloudPlaying) "ic_stop_black_24dp" else "ic_volume_up"
                        ),
                        contentDescription = if (aloudPlaying) aloudStopLabel else readAloudLabel,
                        tint = colors.primaryText,
                    )
                }
                // 浏览器打开按钮: chapter.url 可能为空, 空时不显示
                val url = currentChapter?.url
                if (!url.isNullOrBlank()) {
                    IconButton(onClick = { browseUrl(url) }) {
                        Icon(
                            painter = rememberPainter("ic_web_outline"),
                            contentDescription = openInBrowserLabel,
                            tint = colors.primaryText,
                        )
                    }
                }
                // 登录 (对照 app 端溢出菜单项, 仅源配置 loginUrl/loginUi 时显示)
                if (source?.hasLogin() == true) {
                    OverflowMenu { dismiss ->
                        DropdownMenuItem(
                            onClick = {
                                dismiss()
                                showLogin = true
                            },
                        ) {
                            Text(loginLabel, color = colors.primaryText)
                        }
                    }
                }
            },
        )
        when (val s = state) {
            is ReadRssUiState.Loading -> {
                // 加载中: 居中转圈
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is ReadRssUiState.Empty -> {
                // 无正文规则/内容为空: 提示用浏览器打开
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = noContentRuleHintLabel,
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                    )
                }
            }
            is ReadRssUiState.Error -> {
                // 加载失败: 居中显示错误
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.message,
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                    )
                }
            }
            is ReadRssUiState.Content -> {
                // 正文显示: 桌面端无 WebView, 用 HtmlFormatter.format 把 HTML 转纯文本
                // (段落缩进由 HtmlFormatter.format 处理: 块级标签转 \n + 　缩进)
                // app 端对照: clHtml(body) 包 HTML 给 WebView 渲染 (含 webJs/style 注入)
                Text(
                    text = HtmlFormatter.format(s.body),
                    color = colors.primaryText,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }

    // 书源登录对话框 (对照 app 端 showLoginDialog, 复用 sharedUiMain SourceLoginDialog)
    if (showLogin) {
        source?.let { src ->
            SourceLoginDialog(
                source = src,
                onDismiss = { showLogin = false },
                onOpenUrl = { url -> browseUrl(url) },
            )
        }
    }
}
