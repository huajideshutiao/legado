package io.legado.app.ui.rss

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
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * iOS 端 RSS 文章列表 Screen (包装 shared/commonMain 的 [RssArticlesViewModelShared])。
 *
 * # 职责
 *
 * 对照 desktop `RssArticlesScreen`, iOS 端在 [io.legado.app.ui.IosNavHost] 的 RSS_ARTICLES
 * 路由分支调用本入口。
 *
 * shared/sharedUiMain 未提供 RSS 文章列表 ScreenContent (仅有 commonMain 的 VM), 故 UI
 * 代码放在 iosMain, 复用 shared/commonMain 的 [RssArticlesViewModelShared] 加载流程,
 * 行为与 desktop 端一致:
 * - 首次进入触发 [RssArticlesViewModelShared.loadArticles] (先读缓存再联网, 失败回退缓存)
 * - 顶栏刷新按钮重新触发联网拉取
 * - 三态渲染: Loading 居中转圈 / Error 居中错误 / Data 渲染列表 (空列表显示 "暂无文章")
 * - 文章行显示标题 + 副标题 (tag 发布时间兜底 url)
 * - 点击触发 [onArticleClick] 切到 READ_RSS 路由 (携带章节 index)
 *
 * 平台 Provider (ThemeStore/AppConfig/EventBus) 由 [io.legado.app.MainViewController]
 * 根注入, 本文件不重复包装 CompositionLocalProvider/AppTheme。
 *
 * @param book RSS 源对应的 Book (type 含 BookType.rss 位)
 * @param onBack 返回回调 (切回 RSS_SOURCES 路由)
 * @param onArticleClick 文章点击回调 (切到 READ_RSS 路由, 携带章节 index)
 */
@Composable
fun IosRssArticlesScreen(
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
