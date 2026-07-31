package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.all_bookmark
import legado.shared.generated.resources.export
import legado.shared.generated.resources.export_md
import org.jetbrains.compose.resources.stringResource

// ===== state / actions =====

/**
 * 所有书签页 UI 状态 (immutable)。
 *
 * app 端 [AllBookmarkActivity] 在 `Content()` 内将自身 `var bookmarks` 打包为本数据类传入；
 * 桌面端如需复用可自行构造。
 */
data class AllBookmarkUiState(
    val bookmarks: List<Bookmark> = emptyList(),
)

/**
 * 所有书签页用户交互回调。
 *
 * app 端 [AllBookmarkActivity] 实现本接口，平台依赖
 * (showDialogFragment / exportDir.launch / startActivityForBook / toastOnUi 等)
 * 通过对应方法桥接，shared 端不直接持有 Android Context。
 */
interface AllBookmarkUiActions {
    /** 返回回调 (替代原 `finish()`)。 */
    fun onBack()
    /** 导出书签为 JSON (替代原 `exportDir.launch { requestCode = 1 }`)。 */
    fun export()
    /** 导出书签为 Markdown (替代原 `exportDir.launch { requestCode = 2 }`)。 */
    fun exportMd()
    /** 点击书签跳转阅读 (替代原 `openBookmark(bookmark)`)。 */
    fun openBookmark(bookmark: Bookmark)
    /** 长按书签弹出编辑对话框 (替代原 `showDialogFragment(BookmarkDialog(item, pos))`)。 */
    fun editBookmark(bookmark: Bookmark, pos: Int)
}

// ===== AllBookmarkScreen 完整版（app 端 AllBookmarkActivity 用）=====

/**
 * 所有书签页内容：标题栏 + 按书分组吸顶列表。
 *
 * 下沉自 app 端原 `AllBookmarkActivity.Content()` 及其私有 @Composable，
 * 将 `AllBookmarkActivity` 直接依赖拆为 [state] (展示状态) + [actions] (交互回调)，
 * 去除 Android `Context` / `showDialogFragment` / `appDb` 依赖：
 * - 字符串资源 `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)` (key-based, 跨平台)
 * - 平台依赖 (showDialogFragment / exportDir.launch / openBookmark 内的 appDb 查询)
 *   通过 [AllBookmarkUiActions] 回调桥接回 app 端
 * - AppTitleBar / OverflowMenu 等组件已在 shared, 直接复用
 * - 分组逻辑(按书名/作者连续分组 + 保留全局下标) 属纯展示逻辑, 下沉到 shared 内部
 *
 * @param state    列表状态 (书签数据)
 * @param actions  事件回调 (返回/导出/打开/编辑)
 * @param modifier 外部 modifier
 */
@Composable
fun AllBookmarkScreen(
    state: AllBookmarkUiState,
    actions: AllBookmarkUiActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        AppTitleBar(
            title = stringResource(Res.string.all_bookmark),
            onBack = { actions.onBack() },
        ) {
            OverflowMenu { dismiss ->
                MenuItem(stringResource(Res.string.export)) {
                    dismiss(); actions.export()
                }
                MenuItem(stringResource(Res.string.export_md)) {
                    dismiss(); actions.exportMd()
                }
            }
        }
        BookmarkList(state, actions)
    }
}

/**
 * 按书分组吸顶列表。
 *
 * 复刻原 Activity.BookmarkList：数据源已按书名/作者排序，连续相同 key 归为一组；
 * 保留全局下标(iv.index)供编辑对话框定位书签行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkList(state: AllBookmarkUiState, actions: AllBookmarkUiActions) {
    // 按书连续分组(数据源已按书名/作者排序)，保留全局下标供编辑对话框用
    val groups = remember(state.bookmarks) {
        val out = mutableListOf<Pair<Pair<String, String>, MutableList<IndexedValue<Bookmark>>>>()
        state.bookmarks.withIndex().forEach { iv ->
            val key = iv.value.bookName to iv.value.bookAuthor
            if (out.lastOrNull()?.first != key) out.add(key to mutableListOf())
            out.last().second.add(iv)
        }
        out
    }
    val navPad = WindowInsets.navigationBars.asPaddingValues()
    FastScrollLazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = navPad.calculateBottomPadding()),
    ) {
        groups.forEach { (key, items) ->
            stickyHeader(key = "header:${key.first}|${key.second}") {
                GroupHeader("${key.first}(${key.second})")
            }
            itemsIndexed(items, key = { _, iv -> iv.value.time }) { _, iv ->
                BookmarkItem(actions, iv.value, iv.index)
            }
        }
    }
}

/** 复刻 BookmarkDecoration 吸顶头：32dp 高、背景色填充、accent 16sp */
@Composable
private fun GroupHeader(text: String) {
    val colors = AppTheme.colors
    Text(
        text = text,
        color = colors.accent,
        fontSize = 16.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .height(32.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 16.dp),
    )
}

/** 单条书签：点击跳转/长按编辑，章节名 + 原文 + 摘要(空则隐藏) */
@Composable
private fun BookmarkItem(
    actions: AllBookmarkUiActions,
    item: Bookmark,
    pos: Int,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { actions.openBookmark(item) },
                onLongClick = { actions.editBookmark(item, pos) },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = item.chapterName,
            color = colors.primaryText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
        )
        if (item.bookText.isNotEmpty()) {
            Text(
                text = item.bookText,
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
            )
        }
        if (item.content.isNotEmpty()) {
            Text(
                text = item.content,
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}
