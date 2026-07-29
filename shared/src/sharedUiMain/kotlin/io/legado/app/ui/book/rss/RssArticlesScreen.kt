package io.legado.app.ui.book.rss

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * RSS 文章列表交互回调 (Route 层桥接到 navigator + ViewModelShared)。
 */
interface RssArticlesUiActions {
    fun onBack()
    fun onRefresh()
    fun onArticleClick(article: BookChapter)
}

/**
 * RSS 文章列表 Screen: 标题栏 + LazyColumn 文章列表项 (Card + Text 基本布局)。
 * 加载/错误/空态居中提示; 列表项显示标题 + 更新时间 (BookChapter.tag), 点击跳转阅读。
 */
@Composable
fun RssArticlesScreen(
    title: String,
    state: RssArticlesUiState,
    actions: RssArticlesUiActions,
) {
    val colors = AppTheme.colors
    // book 未加载完时用通用 RSS 文案兜底
    val fallbackTitle = rememberString("rss_source")
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = title.ifEmpty { fallbackTitle },
            onBack = actions::onBack,
            actions = {
                IconButton(onClick = actions::onRefresh) {
                    Icon(
                        painter = rememberPainter("ic_refresh_black_24dp"),
                        contentDescription = rememberString("refresh"),
                        tint = colors.primaryText,
                    )
                }
            },
        )
        when {
            state.isLoading && state.articles.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }

            state.error != null && state.articles.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error, color = colors.secondaryText, fontSize = 14.sp)
                }
            }

            state.articles.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(rememberString("empty"), color = colors.secondaryText, fontSize = 14.sp)
                }
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.articles, key = { it.url }) { article ->
                        ArticleCard(article) { actions.onArticleClick(article) }
                    }
                }
            }
        }
    }
}

/** 文章列表项: Card + 标题 + 更新时间 (tag 为空则只显示标题)。 */
@Composable
private fun ArticleCard(article: BookChapter, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { onClick() },
        elevation = 0.dp,
        backgroundColor = colors.background,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = article.title,
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val tag = article.tag
            if (!tag.isNullOrBlank()) {
                Text(
                    text = tag,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
