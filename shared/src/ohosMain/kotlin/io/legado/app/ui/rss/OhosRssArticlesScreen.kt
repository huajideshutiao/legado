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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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

/**
 * 鸿蒙端 RSS 文章列表 Screen (包装 commonMain 的 [RssArticlesViewModelShared])。
 *
 * 对照 iOS `IosRssArticlesScreen`, 鸿蒙端在 OhosNavHost 的 RSS_ARTICLES 路由分支调用本入口。
 *
 * 复用 commonMain 的 [RssArticlesViewModelShared] 加载流程, 行为与 iOS/desktop 端一致:
 * - 首次进入触发 loadArticles (先读缓存再联网, 失败回退缓存)
 * - 顶栏刷新按钮重新触发联网拉取
 * - 三态渲染: Loading 居中转圈 / Error 居中错误 / Data 渲染列表 (空列表显示 "暂无文章")
 * - 文章行显示标题 + 副标题 (tag 发布时间兜底 url)
 * - 点击触发 [onArticleClick] 切到 READ_RSS 路由 (携带章节 index)
 *
 * @param book RSS 源对应的 Book (type 含 BookType.rss 位)
 * @param onBack 返回回调 (切回 RSS_SOURCES 路由)
 * @param onArticleClick 文章点击回调 (切到 READ_RSS 路由, 携带章节 index)
 */
@Composable
fun OhosRssArticlesScreen(
    book: Book,
    onBack: () -> Unit,
    onArticleClick: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 复用 commonMain RssArticlesViewModelShared (加载流程 + 状态管理下沉 commonMain)
    val viewModel = remember { RssArticlesViewModelShared(scope) }
    val state by viewModel.state.collectAsState()

    // 首次进入触发加载 (ViewModelShared 内部先读缓存再联网, 失败回退缓存)
    LaunchedEffect(book.bookUrl) {
        viewModel.loadArticles(book)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OhosRssTopBar(
            title = book.name,
            onBack = onBack,
            actions = {
                // 刷新按钮: 重新触发联网拉取
                TextButton(onClick = { viewModel.loadArticles(book) }) {
                    Text("刷新")
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
                        color = MaterialTheme.colors.onSurface,
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
                            text = "暂无文章",
                            color = MaterialTheme.colors.onSurface,
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

/** RSS 文章列表行: 标题 + 副标题 (tag 兜底 url)。 */
@Composable
private fun RssArticleRow(chapter: BookChapter, onClick: () -> Unit) {
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
                color = MaterialTheme.colors.onSurface,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 副标题: 文章链接或 tag (发布时间等附加信息)
            val subtitle = chapter.tag?.ifBlank { null } ?: chapter.url
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colors.onSurface,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
