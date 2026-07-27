package io.legado.app.ui.rss

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
import io.legado.app.help.openURL
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.HtmlFormatter

/**
 * iOS 端 RSS 文章阅读 Screen (包装 shared/commonMain 的 [ReadRssViewModelShared])。
 *
 * # 职责
 *
 * 对照 desktop `ReadRssScreen`, iOS 端在 [io.legado.app.ui.IosNavHost] 的 READ_RSS
 * 路由分支调用本入口。
 *
 * shared/sharedUiMain 未提供 RSS 阅读 ScreenContent (仅有 commonMain 的 VM), 故 UI
 * 代码放在 iosMain, 复用 shared/commonMain 的 [ReadRssViewModelShared] 加载流程,
 * 行为与 desktop 端一致:
 * - 首次进入触发 [ReadRssViewModelShared.loadContent] (三态分支: intro / contentRule / 无规则)
 * - 顶栏刷新按钮重新触发拉取
 * - 顶栏 "浏览器打开" 按钮用 [openURL] (UIApplication.openURL) 打开 chapter.url
 *   (替代 desktop 端 [io.legado.app.utils.browseUrl] 的 java.awt.Desktop.browse)
 * - 四态渲染: Loading 转圈 / Empty 提示浏览器打开 / Error 显示错误 / Content 显示正文
 * - 正文格式化: 用 [HtmlFormatter.format] 把 HTML 转纯文本 (iOS 端无 WebView, 与 desktop 一致)
 *
 * 平台 Provider (ThemeStore/AppConfig/EventBus) 由 [io.legado.app.MainViewController]
 * 根注入, 本文件不重复包装 CompositionLocalProvider/AppTheme。
 *
 * @param book RSS 源对应的 Book (type 含 BookType.rss 位)
 * @param chapterIndex 章节 index (对应 RssArticlesScreen 点击回调)
 * @param onBack 返回回调 (切回 RSS_ARTICLES 路由)
 */
@Composable
fun IosReadRssScreen(
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
    // i18n 文案 (refresh / open_in_browser / rss_no_content_rule_hint 提示空正文)
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
                    IconButton(onClick = { openURL(url) }) {
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
                // 正文显示: iOS 端无 WebView, 用 HtmlFormatter.format 把 HTML 转纯文本
                // (段落缩进由 HtmlFormatter.format 处理: 块级标签转 \n + 　缩进)
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
