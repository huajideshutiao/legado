package io.legado.app.ui.rss

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.ui.OpenUrlProviders
import io.legado.app.utils.HtmlFormatter

/**
 * 鸿蒙端 RSS 文章阅读 Screen (包装 commonMain 的 [ReadRssViewModelShared])。
 *
 * 对照 iOS `IosReadRssScreen`, 鸿蒙端在 OhosNavHost 的 READ_RSS 路由分支调用本入口。
 *
 * 复用 commonMain 的 [ReadRssViewModelShared] 加载流程, 行为与 iOS/desktop 端一致:
 * - 首次进入触发 loadContent (三态分支: intro / contentRule / 无规则)
 * - 顶栏刷新按钮重新触发拉取
 * - 顶栏 "浏览器打开" 按钮用 [OpenUrlProviders] 打开 chapter.url
 *   (替代 iOS 端 UIApplication.openURL / desktop 端 java.awt.Desktop.browse)
 * - 四态渲染: Loading 转圈 / Empty 提示浏览器打开 / Error 显示错误 / Content 显示正文
 * - 正文格式化: 用 [HtmlFormatter.format] 把 HTML 转纯文本 (鸿蒙端无 WebView, 与 iOS 一致)
 *
 * @param book RSS 源对应的 Book (type 含 BookType.rss 位)
 * @param chapterIndex 章节 index (对应 RssArticlesScreen 点击回调)
 * @param onBack 返回回调 (切回 RSS_ARTICLES 路由)
 */
@Composable
fun OhosReadRssScreen(
    book: Book,
    chapterIndex: Int,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    // 复用 commonMain ReadRssViewModelShared (加载流程 + 状态管理下沉 commonMain)
    val viewModel = remember { ReadRssViewModelShared(scope) }
    val state by viewModel.state.collectAsState()

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
        OhosRssTopBar(
            title = currentChapter?.title ?: book.name,
            onBack = onBack,
            actions = {
                // 刷新按钮: 重新触发联网拉取
                TextButton(onClick = { viewModel.loadContent(book, chapterIndex) }) {
                    Text("刷新")
                }
                // 浏览器打开按钮: chapter.url 可能为空, 空时不显示
                val url = currentChapter?.url
                if (!url.isNullOrBlank()) {
                    TextButton(onClick = { OpenUrlProviders.get().openUrl(url) }) {
                        Text("浏览器")
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
                        text = "无正文规则, 请用浏览器打开",
                        color = MaterialTheme.colors.onSurface,
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
                        color = MaterialTheme.colors.onSurface,
                        fontSize = 14.sp,
                    )
                }
            }
            is ReadRssUiState.Content -> {
                // 正文显示: 鸿蒙端无 WebView, 用 HtmlFormatter.format 把 HTML 转纯文本
                // (段落缩进由 HtmlFormatter.format 处理: 块级标签转 \n + 缩进)
                Text(
                    text = HtmlFormatter.format(s.body),
                    color = MaterialTheme.colors.onSurface,
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
