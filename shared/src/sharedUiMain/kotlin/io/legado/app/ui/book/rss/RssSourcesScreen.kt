package io.legado.app.ui.book.rss

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.rss_sources
import legado.shared.generated.resources.rss_sources_empty_hint
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * RSS 源管理 Screen (KMP 版, 替代 app/desktop 端 RSS 源列表页)。
 *
 * 数据订阅由 [RssSourcesScreenModel] 触发, 本组件仅做展示 + 点击跳转文章列表。
 * 长按源项弹出操作菜单: 编辑源 (跳 BookSourceEdit) / 删除源 (走 Bookshelf 删除流程)。
 *
 * @param state    列表状态 (RSS 源列表, 以 Book 形式存在)
 * @param actions  事件回调 (返回/添加源/打开源/编辑源/删除源)
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
            title = stringResource(Res.string.rss_sources),
            onBack = { actions.onBack() },
        ) {
            IconButton(onClick = { actions.onAddSource() }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_add),
                    contentDescription = stringResource(Res.string.add),
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
 */
interface RssSourcesUiActions {
    /** 返回回调。 */
    fun onBack()

    /** 添加 RSS 源 (走书架 Book 流程)。 */
    fun onAddSource()

    /** 点击源打开文章列表 (对应 AppRoute.RssArticles)。 */
    fun onOpenSource(book: Book)

    /** 编辑源 (跳 BookSourceEdit, 编辑关联 BookSource)。 */
    fun onEditSource(book: Book)

    /** 删除源 (走 RssHelp.removeFromBookshelf, 含确认弹窗)。 */
    fun onDeleteSource(book: Book)
}

/** 空态提示。 */
@Composable
private fun EmptyHint() {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.rss_sources_empty_hint),
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

/** 单条 RSS 源: Card + 源名 + feed URL, 点击打开文章列表, 长按弹操作菜单 */
@Composable
private fun RssSourceItem(actions: RssSourcesUiActions, book: Book) {
    val colors = AppTheme.colors
    // 长按操作菜单状态
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = { actions.onOpenSource(book) },
                onLongClick = { menuExpanded = true },
            ),
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

        // 长按操作菜单: 编辑源 / 删除源
        AppDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                onClick = {
                    menuExpanded = false
                    actions.onEditSource(book)
                },
            ) {
                Text(stringResource(Res.string.edit), color = colors.primaryText)
            }
            DropdownMenuItem(
                onClick = {
                    menuExpanded = false
                    actions.onDeleteSource(book)
                },
            ) {
                Text(stringResource(Res.string.delete), color = colors.primaryText)
            }
        }
    }
}
