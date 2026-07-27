package io.legado.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.ThreadSafeDateFormat
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

// ===== state / actions =====

/**
 * 阅读记录页 UI 状态 (immutable)。
 *
 * app 端 [ReadRecordActivity] 在 `Content()` 内将自身 `mutableStateOf`/`mutableIntStateOf`/
 * `mutableLongStateOf` 字段打包为本数据类传入; 桌面端如需复用可自行构造。
 *
 * @param sortMode           当前排序模式 (0 名称 / 1 时长 / 2 时间)
 * @param heatmapYear        热力图当前显示年
 * @param heatmapMonth       热力图当前显示月
 * @param todayYear          今日年 (用于判断是否可越过当前月份)
 * @param todayMonth         今日月
 * @param filterDay          当前筛选的具体日期 yyyyMMdd; 0 表示未筛选
 * @param searchText         搜索框文本
 * @param searchFocused      搜索框是否聚焦 (列表滚动时收起焦点)
 * @param items              列表数据 (按时间排序时按 (bookName, day) 拆行)
 * @param bookMap            列表对应的书籍信息 (按 bookName 索引), 用于渲染封面/作者
 * @param todayTimeByBook    每本书当日累计阅读时长 (按 bookName 索引)
 * @param totalTimeByBook    每本书总阅读时长 (按 bookName 索引)
 * @param itemsPerDayMode    当前列表对应的展示模式 (perDayMode), 避免刷新途中口径错位
 * @param heatmapData        热力图当月数据 (day -> seconds)
 * @param heatmapSelectedDay 热力图选中日 (0 表示无选中)
 * @param summaryToday       今日阅读时长
 * @param summaryWeek        本周阅读时长
 * @param summaryMonth       本月阅读时长
 * @param summaryAll         总阅读时长
 * @param summaryBookCount   已读书籍数
 * @param summaryAvgRead     平均每本书阅读时长
 * @param enableReadRecord   是否启用阅读记录 (镜像 AppConfig.enableReadRecord)
 */
data class ReadRecordUiState(
    val sortMode: Int = 2,
    val heatmapYear: Int = 0,
    val heatmapMonth: Int = 0,
    val todayYear: Int = 0,
    val todayMonth: Int = 0,
    val filterDay: Int = 0,
    val searchText: String = "",
    val searchFocused: Boolean = false,
    val items: List<ReadRecordShow> = emptyList(),
    val bookMap: Map<String, Book> = emptyMap(),
    val todayTimeByBook: Map<String, Long> = emptyMap(),
    val totalTimeByBook: Map<String, Long> = emptyMap(),
    val itemsPerDayMode: Boolean = true,
    val heatmapData: Map<Int, Long> = emptyMap(),
    val heatmapSelectedDay: Int = 0,
    val summaryToday: Long = 0L,
    val summaryWeek: Long = 0L,
    val summaryMonth: Long = 0L,
    val summaryAll: Long = 0L,
    val summaryBookCount: Int = 0,
    val summaryAvgRead: Long = 0L,
    val enableReadRecord: Boolean = true,
)

/**
 * 阅读记录页用户交互回调。
 *
 * app 端 [ReadRecordActivity] 实现本接口, 平台依赖
 * (`alert` / `appDb` / `lifecycleScope` / `SearchActivity` / `startActivityForBook` /
 * `AppConfig` / `LocalConfig` 等) 通过对应方法桥接, shared 端不直接持有 Android Context。
 *
 * - [onBack]: 返回 (替代原 `finish()`, 保留搜索框聚焦时先收焦再返回的行为)
 * - [onSearchChange]: 搜索框文本变化 (节流 300ms 后刷新列表)
 * - [onSearch]: 搜索框 IME 搜索键回调 (立即刷新列表 + 收起焦点)
 * - [onSearchFocusChanged]: 搜索框焦点变化 (用于列表滚动时收起焦点)
 * - [onSortSelect]: 选择排序模式 (0 名称 / 1 时长 / 2 时间)
 * - [onToggleEnableRecord]: 切换是否启用阅读记录
 * - [onClearAll]: 清空全部阅读记录 (含确认弹窗)
 * - [onStepMonth]: 热力图月份切换 (+1 下月 / -1 上月, 不越过当前月份)
 * - [openBook]: 点击记录行打开书籍 (book 找不到则跳搜索)
 * - [sureDelAlert]: 长按记录行弹出删除确认 (按书名删)
 * - [clearSearchFocus]: 收起搜索框焦点
 */
interface ReadRecordUiActions {
    fun onBack()
    fun onSearchChange(text: String)
    fun onSearch(text: String)
    fun onSearchFocusChanged(focused: Boolean)
    fun onSortSelect(mode: Int)
    fun onToggleEnableRecord()
    fun onClearAll()
    fun onStepMonth(delta: Int)
    fun openBook(item: ReadRecordShow)
    fun sureDelAlert(item: ReadRecordShow)
    fun clearSearchFocus()
}

// ===== ReadRecordScreen =====

/**
 * 阅读记录 Screen (KMP 版, 替代 app 端 `ReadRecordActivity.Content` 下沉)。
 *
 * 三段式 API:
 * - [ReadRecordUiState]: 不可变展示状态, 由宿主端 (Activity/桌面) 持有 mutableStateOf 并 copy
 * - [ReadRecordUiActions]: 交互回调集合, 宿主端实现接口
 * - [ReadRecordScreen]: 纯 Composable 渲染入口, 仅依赖 state + actions + 平台 slot
 *
 * 下沉改动:
 * - 字符串资源 `stringResource(R.string.xxx)` → `rememberString("xxx")` (key-based, 跨平台)
 * - 图标资源 `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")` (key-based, 跨平台)
 * - `AndroidView` (MonthHeatMapView) → [heatmapSlot] 注入, app 端在 slot 内持有 AndroidView + 平台回调
 *   (factory 内 onDayClick/onDayLongClick 直接读 Activity 成员变量, 保持原动态读取行为)
 * - `ShelfCover` → [coverSlot] 注入 (item + book + modifier), app 端在 slot 内调 ShelfCover
 * - 平台依赖 (`alert` / `appDb` / `SearchActivity` / `startActivityForBook` / `AppConfig`)
 *   通过 [ReadRecordUiActions] 回调桥接回 app 端
 * - `SimpleDateFormat` → [ThreadSafeDateFormat] (commonMain expect, 各平台 actual)
 *
 * 注: 顶部统计卡 / 热力图卡 / 日期分段 / 记录行 渲染逻辑与 app 端原 Activity 完全一致,
 * 仅将平台依赖替换为 state + actions + slot; 宽高/边距/字号/圆角/颜色全部保持原值。
 *
 * 顶部统计卡与热力图卡布局基于可用宽度自适应 (BoxWithConstraints), 不做平台判断:
 * 宽度 >= [SUMMARY_HEATMAP_ROW_MIN_WIDTH] (1000dp) 时同行排列 (各占一半),
 * 否则纵向堆叠; 手机/平板竖屏堆叠、桌面宽屏同行。
 *
 * @param state       展示状态
 * @param actions     交互回调
 * @param modifier    外部 modifier
 * @param heatmapSlot 热力图 AndroidView slot, 接收 modifier; app 端实现内部包 MonthHeatMapView
 * @param coverSlot   封面 slot, 接收 (item, book, modifier); app 端实现内部调 ShelfCover
 */
@Composable
fun ReadRecordScreen(
    state: ReadRecordUiState,
    actions: ReadRecordUiActions,
    modifier: Modifier = Modifier,
    heatmapSlot: @Composable (Modifier) -> Unit,
    coverSlot: @Composable (ReadRecordShow, Book?, Modifier) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("read_record"),
            onBack = { actions.onBack() },
            titleContent = {
                AppSearchField(
                    value = state.searchText,
                    onValueChange = { actions.onSearchChange(it) },
                    hint = rememberString("search"),
                    onSearch = { actions.onSearch(state.searchText) },
                    textFieldModifier = Modifier.onFocusChanged {
                        actions.onSearchFocusChanged(it.isFocused)
                    },
                )
            },
            actions = { TitleActions(state, actions) },
        )
        RecordList(state, actions, heatmapSlot, coverSlot)
    }
}

// ---- 顶栏菜单 (排序单选子菜单 + 开关 + 清空) ----

/**
 * 顶栏菜单: 排序按钮 (单选子菜单) + 溢出菜单 (启用记录开关 + 清空全部)。
 *
 * 复刻原 `ReadRecordActivity.TitleActions`, 宽高/边距/字号/圆角/颜色逻辑全部保持一致,
 * 仅将 viewModel 直调 / AppConfig / alert 替换为外部回调。
 */
@Composable
private fun TitleActions(state: ReadRecordUiState, actions: ReadRecordUiActions) {
    val colors = AppTheme.colors
    var sortExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { sortExpanded = true }) {
            Icon(
                painter = rememberPainter("ic_baseline_sort_24"),
                contentDescription = rememberString("sort"),
                tint = colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
            SortItem(rememberString("sort_by_name"), 0, state.sortMode) {
                sortExpanded = false
                actions.onSortSelect(0)
            }
            SortItem(rememberString("reading_time_sort"), 1, state.sortMode) {
                sortExpanded = false
                actions.onSortSelect(1)
            }
            SortItem(rememberString("last_read_time_sort"), 2, state.sortMode) {
                sortExpanded = false
                actions.onSortSelect(2)
            }
        }
    }
    OverflowMenu { dismiss ->
        Row(
            Modifier
                .clickable {
                    dismiss()
                    actions.onToggleEnableRecord()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppMenuCheckbox(checked = state.enableReadRecord)
            Text(
                rememberString("enable_record"),
                color = AppTheme.colors.primaryText,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        DropdownMenuItem(
            onClick = {
                dismiss()
                actions.onClearAll()
            },
        ) {
            Text(rememberString("delete_all"), color = AppTheme.colors.primaryText)
        }
    }
}

/**
 * 排序菜单项: 单选按钮 + 文案; 点击触发 [onSelect] (由调用方负责关菜单)。
 */
@Composable
private fun SortItem(
    text: String,
    mode: Int,
    currentMode: Int,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppRadioButton(selected = currentMode == mode, onClick = null)
        Text(
            text,
            color = AppTheme.colors.primaryText,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

// ---- 列表 (header 统计卡/热力图卡 + 记录行 + 日期分段) ----

/**
 * 顶部统计卡与热力图卡同行排列的最小可用宽度阈值 (基于 BoxWithConstraints.maxWidth)。
 *
 * 不做平台判断, 仅按宽度自适应: 手机/平板竖屏 (<1000dp, 约束下纵向堆叠) / 桌面宽屏
 * (>=1000dp, 同行各占一半)。1000dp 下两卡各 500dp, 统计卡 2 列与热力图 7 列网格均可正常显示。
 */
private val SUMMARY_HEATMAP_ROW_MIN_WIDTH = 1000.dp

/**
 * 列表: 头部统计卡 + 热力图卡 + 记录行 (perDayMode 时按天分段)。
 *
 * 复刻原 `ReadRecordActivity.RecordList`, 开始拖动列表时收起搜索框焦点
 * (对齐原 OnScrollListener)。
 */
@Composable
private fun RecordList(
    state: ReadRecordUiState,
    actions: ReadRecordUiActions,
    heatmapSlot: @Composable (Modifier) -> Unit,
    coverSlot: @Composable (ReadRecordShow, Book?, Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    // 开始拖动列表时收起搜索框焦点 (对齐原 OnScrollListener)
    val focusManager = LocalFocusManager.current
    // rememberUpdatedState 保证 LaunchedEffect 内读到最新的 searchFocused
    // (LaunchedEffect key=listState 不变时不重启, 直接捕获 state 会停留在首次值)
    val currentSearchFocused by rememberUpdatedState(state.searchFocused)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && currentSearchFocused) focusManager.clearFocus()
        }
    }
    val showSections = state.itemsPerDayMode
    // dateFormat 在列表层 remember 一次, 所有 RecordRow 共享 (对齐原 Activity 单成员实例行为)
    val dateFormat = remember { ThreadSafeDateFormat("yyyy-MM-dd HH:mm") }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
    ) {
        // 顶部统计卡与热力图卡: 基于可用宽度自适应, 不做平台判断
        // (>=1000dp 同行各占一半, <1000dp 纵向堆叠; HeatMapCard 同行布局下补 12dp top padding
        //  与 SummaryCard 顶部对齐, 纵向布局下由 SummaryCard bottom 提供间距, 保持原值)
        item(key = "header") {
            BoxWithConstraints {
                if (maxWidth >= SUMMARY_HEATMAP_ROW_MIN_WIDTH) {
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) {
                            SummaryCard(state)
                        }
                        Box(Modifier.weight(1f).padding(top = 12.dp)) {
                            HeatMapCard(state, actions, heatmapSlot)
                        }
                    }
                } else {
                    Column {
                        SummaryCard(state)
                        HeatMapCard(state, actions, heatmapSlot)
                    }
                }
            }
        }
        if (showSections) {
            // 按时间排序且未筛选某日: 每本书每天一行, 天与天之间插日期分段条
            var lastDay = -1
            state.items.forEachIndexed { idx, record ->
                if (record.day != lastDay) {
                    lastDay = record.day
                    val day = record.day
                    item(key = "day_${day}_$idx") { DaySection(formatDayKey(day)) }
                }
                item(key = "${record.bookName}|${record.day}") {
                    RecordRow(record, state, actions, dateFormat, coverSlot)
                }
            }
        } else {
            items(state.items, key = { it.bookName }) { record ->
                RecordRow(record, state, actions, dateFormat, coverSlot)
            }
        }
    }
}

/** 复刻 view_read_record_header 统计卡: 左右两列 3 组「值+标签」 */
@Composable
private fun SummaryCard(state: ReadRecordUiState) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(DesignTokens.shapeDefault)
            .background(colors.bottomBackground)
            .padding(vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            SummaryItem(rememberFormatDuring(state.summaryToday), rememberString("today_read_time"))
            SummaryItem(
                rememberFormatDuring(state.summaryMonth),
                rememberString("month_read_time"),
                topPadding = 16.dp,
            )
            SummaryItem(
                state.summaryBookCount.toString(),
                rememberString("read_book_count"),
                topPadding = 16.dp,
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            SummaryItem(rememberFormatDuring(state.summaryWeek), rememberString("week_read_time"))
            SummaryItem(
                rememberFormatDuring(state.summaryAll),
                rememberString("all_read_time"),
                topPadding = 16.dp,
            )
            SummaryItem(
                rememberFormatDuring(state.summaryAvgRead),
                rememberString("avg_book_read_time"),
                topPadding = 16.dp,
            )
        }
    }
}

@Composable
private fun SummaryItem(value: String, label: String, topPadding: Dp = 0.dp) {
    val colors = AppTheme.colors
    Text(
        text = value,
        color = colors.accent,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = topPadding),
    )
    Text(
        text = label,
        color = colors.secondaryText,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * 热力图卡: 标题 + 上/下月切换 + [heatmapSlot] (app 端注入 MonthHeatMapView)。
 *
 * 复刻原 `ReadRecordActivity.HeatMapCard`, 宽高/边距/字号/圆角/颜色逻辑全部保持一致,
 * 仅将 AndroidView 替换为 [heatmapSlot] 注入。
 */
@Composable
private fun HeatMapCard(
    state: ReadRecordUiState,
    actions: ReadRecordUiActions,
    heatmapSlot: @Composable (Modifier) -> Unit,
) {
    val colors = AppTheme.colors
    val atCurrent = state.heatmapYear == state.todayYear && state.heatmapMonth == state.todayMonth
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .clip(DesignTokens.shapeDefault)
            .background(colors.bottomBackground)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rememberString("read_heatmap_title"),
                color = colors.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { actions.onStepMonth(-1) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painter = rememberPainter("ic_arrow_back"),
                    contentDescription = rememberString("prev_month"),
                    tint = colors.primaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = rememberString("month_label_format", state.heatmapYear, state.heatmapMonth),
                color = colors.primaryText,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(
                onClick = { if (!atCurrent) actions.onStepMonth(1) },
                enabled = !atCurrent,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer {
                        scaleX = -1f
                        alpha = if (atCurrent) 0.3f else 1f
                    },
            ) {
                Icon(
                    painter = rememberPainter("ic_arrow_back"),
                    contentDescription = rememberString("next_month"),
                    tint = colors.primaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        heatmapSlot(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

/** 复刻 DaySectionDecoration: 26dp 高、底栏色底、12sp 次要文字 */
@Composable
private fun DaySection(text: String) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(colors.bottomBackground),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = colors.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/**
 * 复刻 RecordAdapter.convert: 封面 + 书名/作者 + 「当日/总」时长 + 最后阅读时间。
 *
 * perDayMode 下 item.readTime 就是当行那天的时长, 「总」需要查 totalTimeByBook;
 * 其它模式 item.readTime 已是全部累计, 「当日」查 todayTimeByBook。
 *
 * 封面通过 [coverSlot] 注入, 由 app 端调 ShelfCover 实现。
 */
@Composable
private fun RecordRow(
    item: ReadRecordShow,
    state: ReadRecordUiState,
    actions: ReadRecordUiActions,
    dateFormat: ThreadSafeDateFormat,
    coverSlot: @Composable (ReadRecordShow, Book?, Modifier) -> Unit,
) {
    val colors = AppTheme.colors
    val book = state.bookMap[item.bookName]
    // perDayMode 下 item.readTime 就是当行那天的时长, 「总」需要查 totalTimeByBook;
    // 其它模式 item.readTime 已是全部累计, 「当日」查 todayTimeByBook
    val (dayTime, totalTime) = if (state.itemsPerDayMode) {
        item.readTime to (state.totalTimeByBook[item.bookName] ?: item.readTime)
    } else {
        (state.todayTimeByBook[item.bookName] ?: 0L) to item.readTime
    }
    val lastText = when {
        item.lastRead > 0 -> dateFormat.format(item.lastRead * 1000L)
        book != null && book.durChapterTime > 0 ->
            dateFormat.format(book.durChapterTime / 1000L * 1000L)

        else -> ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { actions.openBook(item) },
                onLongClick = { actions.sureDelAlert(item) },
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        coverSlot(
            item,
            book,
            Modifier
                .width(90.dp)
                .height(120.dp)
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = item.bookName,
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            InfoLine(rememberPainter("ic_author"), book?.getRealAuthor().orEmpty())
            InfoLine(
                rememberPainter("ic_history"),
                rememberString(
                    "read_record_today_total",
                    rememberFormatDuring(dayTime),
                    rememberFormatDuring(totalTime),
                ),
            )
            InfoLine(rememberPainter("ic_book_last"), lastText)
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.painter.Painter, text: String) {
    val colors = AppTheme.colors
    Row(
        Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = colors.secondaryText,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            color = colors.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

// ---- 纯函数 (从原 Activity 平移, 供 shared 内部 + app 端 confirmDeleteDay 共用) ----

/**
 * 把秒数格式化为「X小时Y分钟」形式, 不再按天换算。小于 1 分钟显示秒。
 *
 * 平移自原 `ReadRecordActivity.formatDuring`, 逻辑不变。
 *
 * @param zeroMinutes 0 分钟时的展示文案 (模板 key: time_format_minutes)
 * @param hoursMinutes 「X小时Y分钟」模板 (key: time_format_hours_minutes, 占位符 %1$d %2$d)
 * @param hoursOnly 「X小时」模板 (key: time_format_hours, 占位符 %1$d)
 * @param minutesOnly 「X分钟」模板 (key: time_format_minutes_only, 占位符 %1$d)
 * @param secondsOnly 「X秒」模板 (key: time_format_seconds, 占位符 %1$d)
 */
fun formatDuring(
    seconds: Long,
    zeroMinutes: String,
    hoursMinutes: String,
    hoursOnly: String,
    minutesOnly: String,
    secondsOnly: String,
): String {
    if (seconds <= 0L) return zeroMinutes
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 && minutes > 0 -> hoursMinutes.format(hours, minutes)
        hours > 0 -> hoursOnly.format(hours)
        minutes > 0 -> minutesOnly.format(minutes)
        else -> secondsOnly.format(secs)
    }
}

/**
 * [formatDuring] 的 @Composable 包装: 通过 [rememberString] 缓存 5 个时间格式化模板,
 * 供 Composable 调用方直接传入秒数获取本地化文案。
 */
@Composable
fun rememberFormatDuring(seconds: Long): String {
    return formatDuring(
        seconds = seconds,
        zeroMinutes = rememberString("time_format_minutes"),
        hoursMinutes = rememberString("time_format_hours_minutes"),
        hoursOnly = rememberString("time_format_hours"),
        minutesOnly = rememberString("time_format_minutes_only"),
        secondsOnly = rememberString("time_format_seconds"),
    )
}

/**
 * 把 yyyyMMdd 整数格式化为「yyyy-MM-dd」字符串。
 *
 * 平移自原 `ReadRecordActivity.formatDayKey`, 逻辑不变; 供 app 端 confirmDeleteDay 复用。
 */
fun formatDayKey(dayKey: Int): String {
    if (dayKey <= 0) return ""
    val y = dayKey / 10000
    val m = (dayKey / 100) % 100
    val d = dayKey % 100
    return "%d-%02d-%02d".format(y, m, d)
}
