package io.legado.app.ui.book.changesource

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ScreenInfoProviders

/**
 * 换源标题栏：复刻 dialog_title_bar + change_source 菜单
 * (筛选 SearchView 展开态 / 启停 / 溢出菜单)。
 *
 * 下沉改动:
 * - painterResource(R.drawable.X) → rememberPainter("X")
 * - stringResource(R.string.X) → rememberString("X")
 * - colorResource(R.color.X) → rememberColor("X")
 * - 各 internal Composable 改为 public, 供 app 端 ChangeBookSourceDialog /
 *   ChangeChapterSourceDialog 跨模块调用
 */
@Composable
fun ChangeSourceTitleBar(
    title: String,
    subtitle: String?,
    searchMode: Boolean,
    screenKey: String,
    searching: Boolean,
    onBack: () -> Unit,
    onSearchModeChange: (Boolean) -> Unit,
    onScreen: (String) -> Unit,
    onStartStop: () -> Unit,
    menuContent: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val colors = AppTheme.colors
    val focusRequester = remember { FocusRequester() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bottomBackground)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            if (searchMode) {
                onScreen("")
                onSearchModeChange(false)
            } else {
                onBack()
            }
        }) {
            Icon(
                painter = rememberPainter("ic_arrow_back"),
                contentDescription = null,
                tint = colors.primaryText,
            )
        }
        if (searchMode) {
            AppSearchField(
                value = screenKey,
                onValueChange = onScreen,
                hint = rememberString("screen"),
                modifier = Modifier.weight(1f),
                textFieldModifier = Modifier.focusRequester(focusRequester),
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { onSearchModeChange(true) }) {
                Icon(
                    painter = rememberPainter("outline_filter_alt_24"),
                    contentDescription = rememberString("screen"),
                    tint = colors.primaryText,
                )
            }
        }
        IconButton(onClick = onStartStop) {
            Icon(
                painter = rememberPainter(
                    if (searching) "ic_stop_black_24dp" else "ic_refresh_black_24dp"
                ),
                contentDescription = rememberString(if (searching) "stop" else "refresh"),
                tint = colors.primaryText,
            )
        }
        OverflowMenu { dismiss -> menuContent(dismiss) }
    }
}

@Composable
fun TextMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}

/** 对照 View 菜单 checkable 项：右侧勾选框 */
@Composable
fun CheckMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = AppTheme.colors.primaryText)
        Spacer(Modifier.weight(1f))
        AppMenuCheckbox(checked = checked)
    }
}

/** 分组菜单项：点击关闭父菜单并触发独立分组选择对话框（替代嵌套 Popup） */
@Composable
fun GroupMenuItem(
    title: String,
    dismissParent: () -> Unit,
    onShowGroupPicker: () -> Unit,
) {
    val colors = AppTheme.colors
    DropdownMenuItem(
        onClick = {
            dismissParent()
            onShowGroupPicker()
        },
    ) {
        Text(title, color = colors.primaryText)
        Spacer(Modifier.weight(1f))
        Icon(
            painter = rememberPainter("ic_arrow_right"),
            contentDescription = null,
            tint = colors.secondaryText,
        )
    }
}

@Composable
private fun RadioMenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        AppRadioButton(selected = selected, onClick = null)
        Text(text, color = AppTheme.colors.primaryText)
    }
}

/** 对照 RefreshProgressBar：标题栏下 2dp 搜索中指示，E-Ink 用静态色条 */
@Composable
fun ChangeSourceRefreshBar(visible: Boolean) {
    if (!visible) return
    val colors = AppTheme.colors
    if (LocalEInk.current) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.accent),
        )
    } else {
        LinearProgressIndicator(
            color = colors.accent,
            backgroundColor = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
        )
    }
}

/** 底栏：当前源/进度文字 + 回顶 + 到底，对照 ll_bottom_bar */
@Composable
fun ChangeSourceBottomBar(
    durText: String,
    onDurClick: () -> Unit,
    onTop: () -> Unit,
    onBottom: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onDurClick)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = durText,
                color = colors.primaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        BottomBarIcon("ic_arrow_drop_up", rememberString("go_to_top"), onTop)
        BottomBarIcon("ic_arrow_drop_down", rememberString("go_to_bottom"), onBottom)
    }
}

@Composable
private fun BottomBarIcon(iconKey: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(36.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = description,
            tint = AppTheme.colors.primaryText,
        )
    }
}

/**
 * 搜索结果条目，对照 item_change_source：
 * 左侧点赞/点踩列、源名+作者+最新章节+字数+响应时间、右侧当前源勾选；长按弹源操作菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchBookItem(
    book: SearchBook,
    isCurSource: Boolean,
    loadWordCount: Boolean,
    getScore: () -> Int,
    setScore: (Int) -> Unit,
    onClick: () -> Unit,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    onEdit: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    var score by remember(book.bookUrl) { mutableIntStateOf(getScore()) }
    var menuExpanded by remember { mutableStateOf(false) }
    // 预取格式化串: rememberString 是 @Composable, 不能在 lambda 里调
    val strRespondTime = rememberString("respondTime")
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (score >= 0) {
                    ScoreIcon(
                        tintColor = rememberColor(if (score > 0) "md_red_A200" else "md_red_100"),
                        description = rememberString("like_source"),
                        flip = false,
                    ) {
                        val new = if (score > 0) 0 else 1
                        score = new
                        setScore(new)
                    }
                }
                if (score <= 0) {
                    ScoreIcon(
                        tintColor = rememberColor(if (score < 0) "md_blue_A200" else "md_blue_100"),
                        description = rememberString("not_like_source"),
                        flip = true,
                    ) {
                        val new = if (score < 0) 0 else -1
                        score = new
                        setScore(new)
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.originName,
                        color = colors.primaryText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = book.getRealAuthor(),
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
                Text(
                    text = book.getDisplayLastChapterTitle(),
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (loadWordCount && !book.chapterWordCountText.isNullOrBlank()) {
                    Text(
                        text = book.chapterWordCountText!!,
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                    )
                }
                if (loadWordCount && book.respondTime >= 0) {
                    Text(
                        text = strRespondTime.format(book.respondTime),
                        color = colors.secondaryText,
                        fontSize = 14.sp,
                    )
                }
            }
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (isCurSource) {
                    Icon(
                        painter = rememberPainter("ic_check"),
                        contentDescription = null,
                        tint = colors.primaryText,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        // 长按菜单，对照 change_source_item PopupMenu
        AppDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            TextMenuItem(rememberString("to_top")) { menuExpanded = false; onTop() }
            TextMenuItem(rememberString("to_bottom")) { menuExpanded = false; onBottom() }
            TextMenuItem(rememberString("edit_source")) { menuExpanded = false; onEdit() }
            TextMenuItem(rememberString("disable_source")) { menuExpanded = false; onDisable() }
            TextMenuItem(rememberString("delete_source")) { menuExpanded = false; onDelete() }
        }
    }
}

@Composable
private fun ScoreIcon(tintColor: Color, description: String, flip: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter("ic_praise"),
            contentDescription = description,
            tint = tintColor,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { if (flip) rotationX = 180f },
        )
    }
}

/**
 * 章节换源 toc 预览覆盖层，对照 cl_toc：收起条 + 章节列表 + 加载圈。
 */
@Composable
fun ChapterTocPanel(
    toc: List<BookChapter>?,
    durChapterIndex: Int,
    loading: Boolean,
    onHide: () -> Unit,
    onClickChapter: (BookChapter, String?) -> Unit,
) {
    val colors = AppTheme.colors
    val listState = rememberLazyListState()
    // 目录载入后定位到当前章节上方 5 条，对照 scrollToPosition(durChapterIndex - 5)
    LaunchedEffect(toc) {
        if (!toc.isNullOrEmpty()) {
            listState.scrollToItem((durChapterIndex - 5).coerceAtLeast(0))
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // 空点击仅拦截触摸穿透(原 cl_toc 面板 clickable), 无按压反馈是有意的
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clickable(onClick = onHide),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberPainter("ic_arrow_down"),
                contentDescription = rememberString("close"),
                tint = colors.primaryText,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (toc != null) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(toc, key = { index, _ -> index }) { index, chapter ->
                        TocItemRow(chapter, chapter.index == durChapterIndex) {
                            onClickChapter(chapter, toc.getOrNull(index + 1)?.url)
                        }
                    }
                }
            }
            if (loading) {
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                )
            }
        }
    }
}

/** 目录条目，对照 item_chapter_list：卷名 btn_bg 底、当前章节 accent + 勾选 */
@Composable
private fun TocItemRow(chapter: BookChapter, isDur: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val volumeBg = if (chapter.isVolume) {
        Modifier.background(rememberColor("btn_bg"))
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(volumeBg)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                color = if (isDur) colors.accent else colors.primaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!chapter.tag.isNullOrEmpty() && !chapter.isVolume) {
                Text(
                    text = chapter.tag!!,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isDur) {
                Icon(
                    painter = rememberPainter("ic_check"),
                    contentDescription = rememberString("success"),
                    tint = colors.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 分组选择对话框：独立 Dialog 替代原 GroupMenuItem 的嵌套 AppDropdownMenu，
 * 解决 Compose 嵌套 Popup 位置错乱问题。宽度对齐项目对话框规范 0.9 屏宽 / 上限 800dp。
 */
@Composable
fun GroupPickerDialog(
    groups: List<String>,
    selectedGroup: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val dialogWidth = with(LocalDensity.current) {
        (ScreenInfoProviders.get().screenWidthPx * 0.9f).toDp().coerceAtMost(800.dp)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AppTheme.DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.width(dialogWidth),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = rememberString("group"),
                    onBack = onDismiss,
                )
                LazyColumn(Modifier.fillMaxWidth()) {
                    val hasSelected = selectedGroup.isNotEmpty() && groups.contains(selectedGroup)
                    item {
                        RadioMenuItem(rememberString("all_source"), !hasSelected) {
                            onSelect("")
                        }
                    }
                    items(groups) { group ->
                        RadioMenuItem(group, group == selectedGroup) {
                            onSelect(group)
                        }
                    }
                }
            }
        }
    }
}
