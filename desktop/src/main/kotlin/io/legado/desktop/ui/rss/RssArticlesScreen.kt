package io.legado.desktop.ui.rss

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
import io.legado.app.ui.rss.ArticlesUiState
import io.legado.app.ui.rss.RssArticlesViewModelShared

/**
 * 桌面端 RSS 文章列表 Screen (对应 app 端 ReadRssActivity 的章节列表加载逻辑)。
 *
 * # 设计
 *
 * 业务逻辑下沉到 shared [RssArticlesViewModelShared] (commonMain), 本 Screen 仅负责
 * UI 渲染 + 平台 Provider 注入, 不再独立重写加载逻辑。
 *
 * RSS 文章列表对应 Book 的章节列表 (BookChapter):
 * - 每篇文章 = 一个 BookChapter (title = 文章标题, url = 文章链接, tag = 发布时间)
 * - 章节列表通过 [io.legado.app.model.rss.RssHelp.loadRssArticles] 拉取
 *   (内部走 WebBook.getChapterListAwait, 使用关联的 BookSource 的 tocRule 解析, 落库 bookChapterDao)
 *
 * RSS Book 特殊处理 (对照 app 端 [io.legado.app.base.BaseReadViewModel.upBook]):
 * - 入库后 RSS Book: bookUrl="data:", tocUrl=RSS feed URL
 * - 未入库: bookUrl=RSS feed URL, tocUrl 空 (此时需手动设 tocUrl=bookUrl 才能请求)
 * - 上述兜底由 [io.legado.app.model.rss.RssHelp.loadRssArticles] 内部处理
 *
 * # 加载流程 (委托 [RssArticlesViewModelShared.loadArticles])
 *
 * 1. 先用本地缓存快速填充 ([io.legado.app.model.rss.RssHelp.getCachedArticles])
 * 2. 异步联网拉取最新文章列表, 落库 + 刷新 UI ([io.legado.app.model.rss.RssHelp.loadRssArticles])
 * 3. 失败时保留本地缓存; 缓存为空进入 [ArticlesUiState.Error]
 *
 * # 简化项
 *
 * - 不接入分页 (RSS 通常单页返回全部文章)
 * - 不接入搜索过滤 (RSS 文章数量通常较少)
 * - 不接入下拉刷新 (顶栏刷新按钮重新触发拉取)
 *
 * @param book RSS 源对应的 Book (type 含 BookType.rss 位)
 * @param onBack 返回回调 (切回 RSS_SOURCES 路由)
 * @param onArticleClick 文章点击回调 (切到 READ_RSS 路由, 携带章节 index)
 */
@Composable
fun RssArticlesScreen(
    book: Book,
    onBack: () -> Unit,
    onArticleClick: (Int) -> Unit,
) {
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            RssArticlesContent(book = book, onBack = onBack, onArticleClick = onArticleClick)
        }
    }
}

@Composable
private fun RssArticlesContent(
    book: Book,
    onBack: () -> Unit,
    onArticleClick: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 复用 shared RssArticlesViewModelShared (加载流程 + 状态管理下沉 commonMain)
    val viewModel = remember { RssArticlesViewModelShared(scope) }
    val state by viewModel.state.collectAsState()
    val refreshLabel = rememberString("refresh")
    val rssArticlesEmptyLabel = rememberString("rss_articles_empty")

    // 首次进入触发加载 (ViewModelShared 内部先读缓存再联网, 失败回退缓存)
    LaunchedEffect(book.bookUrl) {
        viewModel.loadArticles(book)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppTitleBar(
            title = book.name,
            onBack = onBack,
            actions = {
                // 刷新按钮: 重新触发联网拉取
                IconButton(onClick = { viewModel.loadArticles(book) }) {
                    Icon(
                        painter = rememberPainter("ic_refresh_black_24dp"),
                        contentDescription = refreshLabel,
                        tint = colors.primaryText,
                    )
                }
            },
        )
        when (val s = state) {
            is ArticlesUiState.Loading -> {
                // 加载中且无缓存: 居中转圈
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is ArticlesUiState.Error -> {
                // 加载失败且无缓存: 居中显示错误
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
            is ArticlesUiState.Data -> {
                if (s.articles.isEmpty()) {
                    // 空列表
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = rssArticlesEmptyLabel,
                            color = colors.secondaryText,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(s.articles, key = { it.url }) { chapter ->
                            RssArticleRow(chapter = chapter, onClick = { onArticleClick(chapter.index) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RssArticleRow(chapter: BookChapter, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                color = colors.primaryText,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 副标题: 文章链接或 tag (发布时间等附加信息)
            val subtitle = chapter.tag?.ifBlank { null } ?: chapter.url
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
