package io.legado.app.ui.rss

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 鸿蒙端 RSS 源列表 Screen (包装 commonMain 的 [RssSourcesViewModelShared])。
 *
 * 对照 iOS `IosRssSourcesScreen`, 鸿蒙端在 OhosNavHost 的 RSS_SOURCES 路由分支调用本入口。
 *
 * 复用 commonMain 的 [RssSourcesViewModelShared] 订阅数据流, 行为与 iOS/desktop 端一致:
 * - 订阅 RssHelp.flowRssSources (bookDao.flowAll() 过滤 isRss)
 * - 列表行显示源名称 (book.name) + URL (book.tocUrl 兜底 bookUrl)
 * - 点击触发 [onRssSourceClick] 切到 RSS_ARTICLES 路由 (携带 Book)
 *
 * 历史上 ohosMain 曾不继承 sharedUiMain, 本 Screen 未复用 AppTitleBar/rememberPainter 等 sharedUiMain 组件,
 * 用标准 Compose + material 组件实现等价 UI。
 *
 * @param onBack 返回回调 (由 OhosNavHost 注入)
 * @param onRssSourceClick 点击 RSS 源回调 (切到 RSS_ARTICLES 路由, 携带 Book)
 */
@Composable
fun OhosRssSourcesScreen(
    onBack: () -> Unit,
    onRssSourceClick: (Book) -> Unit,
) {
    // 复用 commonMain RssSourcesViewModelShared (订阅 bookDao.flowAll() 过滤 isRss)
    val viewModel = remember { RssSourcesViewModelShared() }
    val rssBooks by viewModel.flowRssSources().collectAsState(emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        OhosRssTopBar(title = "RSS", onBack = onBack)
        if (rssBooks.isEmpty()) {
            // 空态: 居中提示
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无 RSS 源",
                    color = AppTheme.colors.secondaryText,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rssBooks, key = { it.bookUrl }) { book ->
                    RssSourceRow(book = book, onClick = { onRssSourceClick(book) })
                }
            }
        }
    }
}

/** RSS 源列表行: 图标 + 源名称 + feed URL。 */
@Composable
private fun RssSourceRow(book: Book, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.name,
                color = AppTheme.colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 副标题: RSS feed URL (tocUrl 兜底 bookUrl, 与 iOS 端逻辑一致)
            val feedUrl = book.tocUrl.ifBlank { book.bookUrl }
            Text(
                text = feedUrl,
                color = AppTheme.colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 鸿蒙端 RSS 顶栏 (历史遗留自绘实现, 未复用 sharedUiMain AppTitleBar)。
 * 标准 Compose 实现: 返回箭头 + 标题 + 右侧 actions 区。
 */
@Composable
internal fun OhosRssTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 保留鸿蒙标题栏的文本返回按钮实现
            TextButton(onClick = onBack) {
                Text("←")
            }
            Text(
                text = title,
                fontSize = 20.sp,
                color = AppTheme.colors.primaryText,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
    }
}
