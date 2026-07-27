package io.legado.desktop.ui.rss

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.compose.component.AppTitleBar
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
}
