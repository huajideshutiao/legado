package io.legado.app.ui.rss

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.Text
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
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * iOS 端 RSS 源列表 Screen (包装 shared/commonMain 的 [RssSourcesViewModelShared])。
 *
 * # 职责
 *
 * 对照 desktop `RssSourcesScreen`, iOS 端在 [io.legado.app.ui.IosNavHost] 的 RSS_SOURCES
 * 路由分支调用本入口。
 *
 * shared/sharedUiMain 未提供 RSS 源列表 ScreenContent (仅有 commonMain 的 VM), 故 UI
 * 代码放在 iosMain, 复用 shared/commonMain 的 [RssSourcesViewModelShared] 订阅数据流,
 * 行为与 desktop 端一致:
 * - 订阅 [io.legado.app.model.rss.RssHelp.flowRssSources] (bookDao.flowAll() 过滤 isRss)
 * - 列表行显示源名称 (book.name) + URL (book.tocUrl 兜底 bookUrl)
 * - 点击触发 [onRssSourceClick] 切到 RSS_ARTICLES 路由 (携带 Book)
 *
 * 平台 Provider (ThemeStore/AppConfig/EventBus) 由 [io.legado.app.MainViewController]
 * 根注入, 本文件不重复包装 CompositionLocalProvider/AppTheme。
 *
 * @param onBack 返回回调 (切回调用方路由, 由 IosNavHost 注入)
 * @param onRssSourceClick 点击 RSS 源回调 (切到 RSS_ARTICLES 路由, 携带 Book)
 */
@Composable
fun IosRssSourcesScreen(
    onBack: () -> Unit,
    onRssSourceClick: (Book) -> Unit,
) {
    val colors = AppTheme.colors
    // 复用 shared RssSourcesViewModelShared (订阅 bookDao.flowAll() 过滤 isRss,
    // RSS 源以 Book 形式存在, type 含 BookType.rss 位)
    val viewModel = remember { RssSourcesViewModelShared() }
    val rssBooks by viewModel.flowRssSources().collectAsState(emptyList())
    val rssSourcesEmptyLabel = rememberString("rss_sources_empty_hint")

    Column(modifier = Modifier.fillMaxSize()) {
        AppTitleBar(
            title = "RSS",
            onBack = onBack,
        )
        if (rssBooks.isEmpty()) {
            // 空态: 居中提示
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = rssSourcesEmptyLabel,
                    color = colors.secondaryText,
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

@Composable
private fun RssSourceRow(book: Book, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 源图标 (用 ic_web_outline 表示 RSS/web 来源)
        Icon(
            painter = rememberPainter("ic_web_outline"),
            contentDescription = null,
            tint = colors.primaryText,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 副标题: RSS feed URL (RSS Book 入库后 bookUrl="data:", 真实地址在 tocUrl;
            // 未入库或非 RSS 流程下 tocUrl 可能为空, 回退显示 bookUrl)
            val feedUrl = book.tocUrl.ifBlank { book.bookUrl }
            Text(
                text = feedUrl,
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
