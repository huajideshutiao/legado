package io.legado.desktop.ui.rss

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.rss.RssSourcesViewModelShared

/**
 * 桌面端 RSS 源列表 Screen。
 *
 * # 设计
 *
 * app 端无独立 RssSource 实体 (shared 端仅有旧版 OldRssSource 用于迁移),
 * RSS 在 app 端通过 `Book(type |= BookType.rss)` + 关联的 `BookSource` 实现:
 * - `Book.isRss` 判断 `type and BookType.rss > 0` (扩展在 shared BookExtensionsShared.kt)
 * - `ReadRssActivity` 用 `Book + BookSource` 装载内容, `book.originName == "RSS"` 标识 RSS 来源
 * - 章节列表 (BookChapter) 即 RSS 文章列表
 *
 * 桌面端复用 shared 数据层 ([RssSourcesViewModelShared]):
 * - 订阅 [io.legado.app.model.rss.RssHelp.flowRssSources] (bookDao.flowAll() 过滤 isRss)
 * - 列表行显示源名称 (book.name) + URL (book.bookUrl)
 * - 点击触发 [onRssSourceClick] 切到 RSS_ARTICLES 路由 (携带 Book)
 *
 * # 简化项
 *
 * - 不接入分组管理 (RSS 源数量通常较少, 直接平铺列表)
 * - 不接入下拉刷新 (桌面端无下拉手势, 顶栏刷新按钮触发文章列表页拉取)
 * - 空态显示 "暂无 RSS 源, 请在书架中添加 type 含 rss 的书籍" 提示
 *
 * @param onBack 返回回调 (切回调用方路由)
 * @param onRssSourceClick 点击 RSS 源回调 (切到 RSS_ARTICLES 路由, 携带 Book)
 */
@Composable
fun RssSourcesScreen(
    onBack: () -> Unit,
    onRssSourceClick: (Book) -> Unit,
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
            RssSourcesContent(onBack = onBack, onRssSourceClick = onRssSourceClick)
        }
    }
}

@Composable
private fun RssSourcesContent(
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
