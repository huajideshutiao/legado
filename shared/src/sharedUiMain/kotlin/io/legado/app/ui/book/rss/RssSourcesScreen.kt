package io.legado.app.ui.book.rss

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * RSS 源管理 Screen (KMP 版, 替代 app/desktop 端 RSS 源列表页)。
 *
 * 下沉改动:
 * - 数据订阅由 [RssSourcesScreenModel] 触发, 本组件仅做展示 + 点击跳转文章列表
 * - 字符串资源 `stringResource(R.string.xxx)` → `rememberString("xxx")` (key-based, 跨平台)
 * - 图标资源 `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - RSS 源的增删走书架 Book 流程 (见 RssSourcesViewModelShared 说明), 故无内置编辑/删除入口
 *
 * UI 结构:
 * - 顶部: AppTitleBar (返回 + 标题 + 添加源按钮)
 * - 中部: FastScrollLazyColumn 源列表 (Card + 源名 + feed URL)
 * - 空态: 居中提示 rss_sources_empty_hint
 *
 * @param state    列表状态 (RSS 源列表, 以 Book 形式存在)
 * @param actions  事件回调 (返回/添加源/打开源)
 * @param modifier 外部 modifier
 */
@Composable
fun RssSourcesScreen(
    state: RssSourcesUiState,
    actions: RssSourcesUiActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("rss_sources"),
            onBack = { actions.onBack() },
        ) {
            IconButton(onClick = { actions.onAddSource() }) {
                Icon(
                    painter = rememberPainter("ic_add"),
                    contentDescription = rememberString("add"),
                    tint = AppTheme.colors.primaryText,
                )
            }
        }
        if (state.sources.isEmpty()) {
            EmptyHint()
        } else {
            RssSourceList(state, actions)
        }
    }
}

/**
 * RSS 源管理页用户交互回调。
 *
 * 平台依赖 (添加源弹窗 / 跳转文章列表等) 通过本接口桥接, shared 端不直接持有 Android Context。
 */
interface RssSourcesUiActions {
    /** 返回回调。 */
    fun onBack()

    /** 添加 RSS 源 (走书架 Book 流程, 待书架添加流程下沉后接入)。 */
    fun onAddSource()

    /** 点击源打开文章列表 (对应 AppRoute.RssArticles)。 */
    fun onOpenSource(book: Book)
}

/** 空态提示 (对照 rss_sources_empty_hint)。 */
@Composable
private fun EmptyHint() {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = rememberString("rss_sources_empty_hint"),
            color = colors.secondaryText,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun RssSourceList(state: RssSourcesUiState, actions: RssSourcesUiActions) {
    val navPad = WindowInsets.navigationBars.asPaddingValues()
    FastScrollLazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = navPad.calculateBottomPadding()),
    ) {
        items(state.sources, key = { it.bookUrl }) { book ->
            RssSourceItem(actions, book)
        }
    }
}

/** 单条 RSS 源: Card + 源名 + feed URL, 点击打开文章列表 */
@Composable
private fun RssSourceItem(actions: RssSourcesUiActions, book: Book) {
    val colors = AppTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { actions.onOpenSource(book) },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = book.name,
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.bookUrl.isNotEmpty()) {
                Text(
                    text = book.bookUrl,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
