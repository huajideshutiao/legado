package io.legado.desktop.ui.about

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.about.ReadRecordScreen as SharedReadRecordScreen
import io.legado.app.ui.about.ReadRecordUiActions
import io.legado.app.ui.about.ReadRecordUiState
import io.legado.app.ui.about.formatDayKey
import io.legado.app.ui.about.rememberFormatDuring
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.desktop.ui.component.DesktopBookCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Calendar
import java.util.Locale

private const val SEARCH_DEBOUNCE_MS = 300L

/** 桌面端中文拼音排序 Collator (app 端 StringExtensions.cnCompare 依赖 android.icu 未下沉) */
private val cnCollator: Collator = Collator.getInstance(Locale.CHINA)

/**
 * 桌面端阅读记录 Screen 入口 (包装 shared/sharedUiMain 的 [SharedReadRecordScreen])。
 *
 * 职责对照 desktop [io.legado.desktop.ui.bookinfo.BookInfoScreen] 模式, 仅做桌面平台适配,
 * 业务展示与交互逻辑全部下沉到 shared/sharedUiMain 的 [SharedReadRecordScreen]:
 *
 * - 数据加载: [LaunchedEffect] + [AppDbProviders.get].readRecordDao.all() 拉取全部会话,
 *   单遍聚合 (平移 app 端 ReadRecordActivity.initData) 算出 items / bookMap /
 *   todayTimeByBook / totalTimeByBook / summaryToday/Week/Month/All/BookCount/AvgRead /
 *   heatmapData; 搜索框 300ms 节流, 翻月份/排序切换取消旧任务避免堆积
 * - UI state: 构造 [ReadRecordUiState], 各字段镜像本地 mutableStateOf
 * - actions: 实现 [ReadRecordUiActions] 全部 11 个方法, onBack/onSortSelect/
 *   onToggleEnableRecord/onClearAll/onStepMonth/sureDelAlert/onSearch* 接入真实逻辑;
 *   openBook 接入 onOpenBook 回调 (查 book → 调宿主注入回调跳阅读页, 找不到 toast)
 * - slots:
 *   - heatmapSlot: [DesktopHeatmapCanvas] (Canvas 绘制月度热力图网格占位,
 *     desktop 端无 MPAndroidChart/MonthHeatMapView, 用原生 Canvas 画 7 列网格;
 *     点击日显示阅读时长, 长按日弹删除确认对话框)
 *   - coverSlot: [DesktopBookCover.InfoCover] (复用详情页封面加载组件)
 *
 * 简化项:
 * - 排序 [String] 比较用 [Collator] (Locale.CHINA) 替代 app 端 cnCompare
 *   (app 端 StringExtensions.cnCompare 依赖 android.icu, 未下沉; java.text.Collator 等价)
 * - 热力图点击改为显示当日阅读时长 (app 端 onDayClick 切 filterDay 筛选, desktop 不筛选);
 *   长按接入 confirmDeleteDay (对齐 app 端 onDayLongClick)
 *
 * @param onBack 返回回调 (由 DesktopApp 注入)
 * @param onOpenBook 点击记录行打开书籍回调 (由 DesktopApp 注入, 默认 no-op)
 */
@Composable
fun ReadRecordScreen(
    onBack: () -> Unit,
    onOpenBook: (book: Book) -> Unit = {},
) {
    // ---- Compose 状态 (镜像 app 端 ReadRecordActivity 字段) ----
    var sortMode by remember {
        mutableIntStateOf(PreferenceProviders.get().getInt("readRecordSort", 2))
    }
    var heatmapYear by remember { mutableIntStateOf(0) }
    var heatmapMonth by remember { mutableIntStateOf(0) }
    var todayYear by remember { mutableIntStateOf(0) }
    var todayMonth by remember { mutableIntStateOf(0) }
    var filterDay by remember { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }

    // 缓存全部记录, 避免每次过滤都查库 (对齐 app 端 allRecords)
    var allRecords by remember { mutableStateOf<List<ReadRecord>?>(null) }

    var items by remember { mutableStateOf<List<ReadRecordShow>>(emptyList()) }
    var bookMap by remember { mutableStateOf<Map<String, Book>>(emptyMap()) }
    var todayTimeByBook by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var totalTimeByBook by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var itemsPerDayMode by remember { mutableStateOf(true) }

    var heatmapData by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    var heatmapSelectedDay by remember { mutableIntStateOf(0) }

    var summaryToday by remember { mutableLongStateOf(0L) }
    var summaryWeek by remember { mutableLongStateOf(0L) }
    var summaryMonth by remember { mutableLongStateOf(0L) }
    var summaryAll by remember { mutableLongStateOf(0L) }
    var summaryBookCount by remember { mutableIntStateOf(0) }
    var summaryAvgRead by remember { mutableLongStateOf(0L) }

    var enableReadRecord by remember { mutableStateOf(AppConfigProviders.get().enableReadRecord) }

    // 列表刷新任务 + 搜索节流任务 (取消旧任务避免堆积)
    var initDataJob by remember { mutableStateOf<Job?>(null) }
    var searchDebounceJob by remember { mutableStateOf<Job?>(null) }
    var lastSearchKey: String? by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // 确认弹窗状态 (onClearAll / sureDelAlert 触发, 提升到 Composable 层渲染)
    var clearAllDialog by remember { mutableStateOf(false) }
    var delBookItem by remember { mutableStateOf<ReadRecordShow?>(null) }
    // 删除某天阅读记录的确认弹窗 (热力图长按日触发, 对齐 app 端 confirmDeleteDay)
    var delDayKey by remember { mutableStateOf<Int?>(null) }
    // 热力图点击选中的日 (0 无选中; 点击显示当日时长, 对齐 app 端 onDayClick 改为展示)
    var heatmapClickedDay by remember { mutableIntStateOf(0) }

    // 外部 onBack 参数与 actions.onBack 方法名冲突, 中转一份避免递归
    val backCallback: () -> Unit = onBack

    // ---- 月份/周范围 (平移 app 端, java.util.Calendar 桌面端可用) ----

    fun monthRange(year: Int, month: Int): Pair<Int, Int> {
        val start = year * 10000 + month * 100 + 1
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, 1)
        val end = year * 10000 + month * 100 + cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return start to end
    }

    fun weekRange(now: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val start = ReadRecord.dayKey(cal.timeInMillis / 1000)
        cal.add(Calendar.DAY_OF_MONTH, 6)
        val end = ReadRecord.dayKey(cal.timeInMillis / 1000)
        return start to end
    }

    fun refreshHeatmap() {
        val year = heatmapYear
        val month = heatmapMonth
        val (start, end) = monthRange(year, month)
        val daySeconds = LongArray(32)
        allRecords?.forEach { r ->
            if (r.day in start..end) {
                val d = r.day % 100
                daySeconds[d] += r.endSec - r.startSec
            }
        }
        val data = HashMap<Int, Long>(31)
        for (d in 1..31) {
            if (daySeconds[d] > 0) data[d] = daySeconds[d]
        }
        heatmapData = data
        heatmapSelectedDay =
            if (filterDay != 0 && filterDay / 10000 == year && (filterDay / 100) % 100 == month) {
                filterDay % 100
            } else 0
    }

    // 单遍聚合: 搜索过滤 + 总时长/最近阅读时间 + 当日时长 + summary 全部一次算完
    // (平移 app 端 ReadRecordActivity.initData, 逻辑保持一致)
    fun initData(searchKey: String? = lastSearchKey) {
        lastSearchKey = searchKey
        val day = filterDay
        val key = searchKey?.trim().orEmpty()
        val now = System.currentTimeMillis()
        val todayKey = ReadRecord.dayKey(now / 1000)
        val (weekStart, weekEnd) = weekRange(now)
        val nowCal = Calendar.getInstance().apply { timeInMillis = now }
        val (monthStart, monthEnd) =
            monthRange(nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH) + 1)
        val currentSortMode = sortMode
        // 按时间排序且未筛选某日时, 列表展示每本书每天一行, readTime 即当天时长;
        // 其它情况按 bookName 聚合为一行, 配合 todayPerBook 显示「当日/总」
        val perDayMode = currentSortMode == 2 && day == 0
        initDataJob?.cancel()
        initDataJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                val records =
                    allRecords ?: AppDbProviders.get().readRecordDao.all().also { allRecords = it }
                // 单遍聚合: 搜索过滤 + 总时长/最近阅读时间 + 当日时长 + 筛选日存在性
                // + 顺手把 today/week/month/all 4 个 summary 也算了, 省掉 4 次 DAO 查询
                val dayForToday = if (day != 0) day else todayKey
                val expected = records.size.coerceAtMost(256).coerceAtLeast(16)
                val readTimeByBook = if (perDayMode) null else HashMap<String, Long>(expected)
                val lastReadByBook = if (perDayMode) null else HashMap<String, Long>(expected)
                val todayPerBook = if (perDayMode) null else HashMap<String, Long>(expected)
                val perDayMap =
                    if (perDayMode) HashMap<String, ReadRecordShow>(expected) else null
                // perDayMode 下每行代表某本书某天, 仍需每本书的总时长展示「当日/总」
                val totalByBook = if (perDayMode) HashMap<String, Long>(expected) else null
                val dayBookNames = if (day != 0) HashSet<String>() else null
                val keyEmpty = key.isEmpty()
                var sumToday = 0L
                var sumWeek = 0L
                var sumMonth = 0L
                var sumAll = 0L
                var bookCount = 0
                val seenBooks = HashSet<String>()
                for (r in records) {
                    val rt = r.endSec - r.startSec
                    if (seenBooks.add(r.bookName)) bookCount++
                    val lastRead = r.endSec
                    val d = r.day
                    // summary 不受搜索词/筛选影响, 先算
                    sumAll += rt
                    if (d == todayKey) sumToday += rt
                    if (d in weekStart..weekEnd) sumWeek += rt
                    if (d in monthStart..monthEnd) sumMonth += rt
                    val name = r.bookName
                    if (!keyEmpty && !name.contains(key, ignoreCase = true)) continue
                    if (perDayMode) {
                        val mapKey = "$name|$d"
                        val existing = perDayMap!![mapKey]
                        if (existing == null) {
                            perDayMap[mapKey] = ReadRecordShow(name, rt, lastRead, d)
                        } else {
                            existing.readTime += rt
                            if (lastRead > existing.lastRead) existing.lastRead = lastRead
                        }
                        totalByBook!![name] = (totalByBook[name] ?: 0L) + rt
                    } else {
                        readTimeByBook!![name] = (readTimeByBook[name] ?: 0L) + rt
                        val prevLast = lastReadByBook!![name]
                        if (prevLast == null || lastRead > prevLast) {
                            lastReadByBook[name] = lastRead
                        }
                        if (d == dayForToday) {
                            todayPerBook!![name] = (todayPerBook[name] ?: 0L) + rt
                        }
                    }
                    if (dayBookNames != null && d == day) {
                        dayBookNames.add(name)
                    }
                }
                val itemsList: ArrayList<ReadRecordShow> = if (perDayMode) {
                    ArrayList(perDayMap!!.values)
                } else {
                    val out = ArrayList<ReadRecordShow>(readTimeByBook!!.size)
                    if (dayBookNames != null) {
                        for ((name, total) in readTimeByBook) {
                            if (name in dayBookNames) {
                                out.add(
                                    ReadRecordShow(name, total, lastReadByBook!![name] ?: 0L)
                                )
                            }
                        }
                    } else {
                        for ((name, total) in readTimeByBook) {
                            out.add(
                                ReadRecordShow(name, total, lastReadByBook!![name] ?: 0L)
                            )
                        }
                    }
                    out
                }
                when (currentSortMode) {
                    1 -> itemsList.apply { sortByDescending { it.readTime } }
                    2 -> itemsList.apply { sortByDescending { it.lastRead } }
                    else -> itemsList.apply {
                        // app 端 cnCompare 依赖 android.icu 未下沉, 用 java.text.Collator
                        // (Locale.CHINA) 等价实现中文拼音排序
                        sortWith { o1, o2 -> cnCollator.compare(o1.bookName, o2.bookName) }
                    }
                }
                val names =
                    itemsList.mapTo(LinkedHashSet(itemsList.size)) { it.bookName }.toTypedArray()
                val bookList = if (names.isEmpty()) emptyList()
                else AppDbProviders.get().bookDao.findByName(*names)
                val avgRead = if (bookCount > 0) sumAll / bookCount else 0L
                InitResult(
                    itemsList,
                    bookList.associateBy { it.name },
                    todayPerBook ?: emptyMap(),
                    totalByBook ?: emptyMap(),
                    sumToday, sumWeek, sumMonth, sumAll, bookCount, avgRead
                )
            }
            // 取消后会抛 CancellationException, 不会跑到这里覆盖更新的状态
            bookMap = result.books
            todayTimeByBook = result.todayMap
            totalTimeByBook = result.totalMap
            summaryToday = result.sumToday
            summaryWeek = result.sumWeek
            summaryMonth = result.sumMonth
            summaryAll = result.sumAll
            summaryBookCount = result.bookCount
            summaryAvgRead = result.avgRead
            refreshHeatmap()
            itemsPerDayMode = perDayMode
            items = result.items
        }
    }

    fun stepMonth(delta: Int) {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(heatmapYear, heatmapMonth - 1, 1)
        cal.add(Calendar.MONTH, delta)
        val newYear = cal.get(Calendar.YEAR)
        val newMonth = cal.get(Calendar.MONTH) + 1
        // 不允许越过当前月份
        if (newYear > todayYear || (newYear == todayYear && newMonth > todayMonth)) {
            return
        }
        heatmapYear = newYear
        heatmapMonth = newMonth
        refreshHeatmap()
    }

    // 热力图长按日触发删除确认 (对齐 app 端 confirmDeleteDay, 弹窗由 Composable 层渲染)
    fun confirmDeleteDay(dayKey: Int) {
        delDayKey = dayKey
    }

    // ---- 初始化 (对齐 app 端 onActivityCreated) ----
    LaunchedEffect(Unit) {
        val cal = Calendar.getInstance()
        todayYear = cal.get(Calendar.YEAR)
        todayMonth = cal.get(Calendar.MONTH) + 1
        heatmapYear = todayYear
        heatmapMonth = todayMonth
        initData(null)
    }

    // ---- UI state ----
    val state = remember(
        sortMode, heatmapYear, heatmapMonth, todayYear, todayMonth, filterDay,
        searchText, searchFocused, items, bookMap, todayTimeByBook, totalTimeByBook,
        itemsPerDayMode, heatmapData, heatmapSelectedDay,
        summaryToday, summaryWeek, summaryMonth, summaryAll, summaryBookCount,
        summaryAvgRead, enableReadRecord,
    ) {
        ReadRecordUiState(
            sortMode = sortMode,
            heatmapYear = heatmapYear,
            heatmapMonth = heatmapMonth,
            todayYear = todayYear,
            todayMonth = todayMonth,
            filterDay = filterDay,
            searchText = searchText,
            searchFocused = searchFocused,
            items = items,
            bookMap = bookMap,
            todayTimeByBook = todayTimeByBook,
            totalTimeByBook = totalTimeByBook,
            itemsPerDayMode = itemsPerDayMode,
            heatmapData = heatmapData,
            heatmapSelectedDay = heatmapSelectedDay,
            summaryToday = summaryToday,
            summaryWeek = summaryWeek,
            summaryMonth = summaryMonth,
            summaryAll = summaryAll,
            summaryBookCount = summaryBookCount,
            summaryAvgRead = summaryAvgRead,
            enableReadRecord = enableReadRecord,
        )
    }

    // ---- actions (直接构造, 捕获最新状态; 11 个方法, 无需 remember 优化) ----
    val actions = object : ReadRecordUiActions {
        override fun onBack() {
            // 搜索框聚焦时先收焦再返回 (对齐 app 端 finish 行为)
            if (searchFocused) {
                focusManager.clearFocus()
                return
            }
            backCallback()
        }

        override fun onSearchChange(text: String) {
            searchText = text
            // 连续输入 300ms 节流, 避免每次按键都跑单遍聚合
            searchDebounceJob?.cancel()
            searchDebounceJob = scope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                initData(text)
            }
        }

        override fun onSearch(text: String) {
            searchDebounceJob?.cancel()
            focusManager.clearFocus()
            initData(text)
        }

        override fun onSearchFocusChanged(focused: Boolean) {
            searchFocused = focused
        }

        override fun onSortSelect(mode: Int) {
            if (sortMode != mode) {
                sortMode = mode
                PreferenceProviders.get().putInt("readRecordSort", mode)
                initData()
            }
        }

        override fun onToggleEnableRecord() {
            enableReadRecord = !enableReadRecord
            // desktop AppConfigAccessor 为只读, 直接写 PreferenceProvider
            // (对齐 app 端 AppConfig.enableReadRecord 赋值)
            PreferenceProviders.get().putBoolean(PreferKey.enableReadRecord, enableReadRecord)
        }

        override fun onClearAll() {
            clearAllDialog = true
        }

        override fun onStepMonth(delta: Int) = stepMonth(delta)

        override fun openBook(item: ReadRecordShow) {
            // 查 book → 调宿主注入的 onOpenBook 跳阅读页; 找不到 toast
            // (对照 app 端 ReadRecordActivity.openBook: bookMap 命中 → startActivityForBook,
            //  未命中 → bookDao.findByName → SearchActivity; desktop 无搜索路由, toast)
            scope.launch {
                val book = bookMap[item.bookName] ?: withContext(Dispatchers.IO) {
                    AppDbProviders.get().bookDao.findByName(item.bookName).firstOrNull()
                }
                if (book == null) {
                    Toasters.get().toast("没有书籍")
                } else {
                    onOpenBook(book)
                }
            }
        }

        override fun sureDelAlert(item: ReadRecordShow) {
            delBookItem = item
        }

        override fun clearSearchFocus() {
            focusManager.clearFocus()
        }
    }

    // ---- 渲染 (位置参数调用 shared Screen; 函数类型参数用位置传) ----
    SharedReadRecordScreen(
        state,
        actions,
        Modifier,
        { modifier ->
            Column(modifier) {
                DesktopHeatmapCanvas(
                    state.heatmapYear,
                    state.heatmapMonth,
                    state.heatmapData,
                    heatmapClickedDay,
                    Modifier.fillMaxWidth(),
                    onDayClick = { day ->
                        // 点击日: 切换选中, 显示当日阅读时长
                        // (app 端 onDayClick 切 filterDay 筛选, desktop 改为展示)
                        heatmapClickedDay = if (heatmapClickedDay == day) 0 else day
                    },
                    onDayLongClick = { day ->
                        // 长按日: 弹删除确认 (对齐 app 端 onDayLongClick → confirmDeleteDay)
                        val dayKey = state.heatmapYear * 10000 + state.heatmapMonth * 100 + day
                        confirmDeleteDay(dayKey)
                    },
                )
                // 显示点击日的阅读时长
                if (heatmapClickedDay != 0) {
                    val dayKey =
                        state.heatmapYear * 10000 + state.heatmapMonth * 100 + heatmapClickedDay
                    val dur = state.heatmapData[heatmapClickedDay] ?: 0L
                    Text(
                        text = formatDayKey(dayKey) + "  " + rememberFormatDuring(dur),
                        color = AppTheme.colors.secondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        { _, book, modifier ->
            // item 暂未使用 (DesktopBookCover.InfoCover 仅需 book;
            //  book 为 null 时占位显示首字 "?", 与 app 端 ShelfCover 用 item.bookName 略有差异)
            DesktopBookCover.InfoCover(book, modifier)
        },
    )

    // ---- 确认弹窗 (material3 AlertDialog, desktop 端无 shared alert) ----

    if (clearAllDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { clearAllDialog = false },
            title = { Text(rememberString("delete")) },
            text = { Text(rememberString("sure_del")) },
            confirmButton = {
                TextButton(onClick = {
                    clearAllDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppDbProviders.get().readRecordDao.clear()
                        }
                        // 直接清空内存缓存, initData 会基于空列表算出全 0 summary
                        allRecords = emptyList()
                        refreshHeatmap()
                        initData()
                    }
                }) { Text(rememberString("ok")) }
            },
            dismissButton = {
                TextButton(onClick = { clearAllDialog = false }) {
                    Text(rememberString("cancel"))
                }
            },
        )
    }

    delBookItem?.let { item ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { delBookItem = null },
            title = { Text(rememberString("delete")) },
            text = { Text(rememberString("sure_del_any", item.bookName)) },
            confirmButton = {
                TextButton(onClick = {
                    delBookItem = null
                    val name = item.bookName
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppDbProviders.get().readRecordDao.deleteByName(name)
                        }
                        // 内存缓存同步删除, 避免重新查 DAO.all
                        allRecords = allRecords?.filterNot { it.bookName == name }
                        refreshHeatmap()
                        initData()
                    }
                }) { Text(rememberString("ok")) }
            },
            dismissButton = {
                TextButton(onClick = { delBookItem = null }) {
                    Text(rememberString("cancel"))
                }
            },
        )
    }

    // 删除某天阅读记录确认弹窗 (热力图长按日触发, 对齐 app 端 confirmDeleteDay)
    delDayKey?.let { dayKey ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { delDayKey = null },
            title = { Text(rememberString("delete")) },
            text = { Text(rememberString("sure_del_any", formatDayKey(dayKey))) },
            confirmButton = {
                TextButton(onClick = {
                    delDayKey = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppDbProviders.get().readRecordDao.deleteByDay(dayKey)
                        }
                        // 内存缓存同步删除, 避免重新查 DAO.all
                        allRecords = allRecords?.filterNot { it.day == dayKey }
                        refreshHeatmap()
                        initData()
                    }
                }) { Text(rememberString("ok")) }
            },
            dismissButton = {
                TextButton(onClick = { delDayKey = null }) {
                    Text(rememberString("cancel"))
                }
            },
        )
    }
}

/**
 * 单遍聚合结果 (对齐 app 端 ReadRecordActivity.InitResult)。
 */
private data class InitResult(
    val items: List<ReadRecordShow>,
    val books: Map<String, Book>,
    val todayMap: Map<String, Long>,
    val totalMap: Map<String, Long>,
    val sumToday: Long,
    val sumWeek: Long,
    val sumMonth: Long,
    val sumAll: Long,
    val bookCount: Int,
    val avgRead: Long,
)

/**
 * 桌面端月度热力图 Canvas 占位 (替代 app 端 MonthHeatMapView + AndroidView)。
 *
 * desktop 端无 MPAndroidChart, 用原生 [Canvas] 画 7 列 (周一开始) 网格:
 * - 有数据日: [AppTheme.colors] 的 accent 按时长比例 (0.2~1.0 alpha) 填充
 * - 无数据日: bottomBackground 浅底
 * - 选中日: accent 描边
 * - 点击日: [onDayClick] 回调 (显示当日阅读时长)
 * - 长按日: [onDayLongClick] 回调 (弹删除确认对话框 confirmDeleteDay)
 *
 * 简化项:
 * - 不画星期/日期数字标签, 仅色块网格
 *
 * @param year           热力图年
 * @param month          热力图月
 * @param data           day -> seconds 当月数据
 * @param selectedDay    选中日 (0 无选中, accent 描边)
 * @param modifier       外部尺寸约束 (shared heatmapSlot 传入 fillMaxWidth + padding)
 * @param onDayClick     点击日回调 (day, 1..31)
 * @param onDayLongClick 长按日回调 (day, 1..31)
 */
@Composable
private fun DesktopHeatmapCanvas(
    year: Int,
    month: Int,
    data: Map<Int, Long>,
    selectedDay: Int,
    modifier: Modifier = Modifier,
    onDayClick: (Int) -> Unit = {},
    onDayLongClick: (Int) -> Unit = {},
) {
    val colors = AppTheme.colors
    val cal = remember(year, month) {
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
        }
    }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Calendar.DAY_OF_WEEK: 1=Sunday..7=Saturday; 转为周一开始的列索引 (0=Monday..6=Sunday)
    val firstCol = (cal.get(Calendar.DAY_OF_WEEK) - 2 + 7) % 7
    val rows = (firstCol + daysInMonth + 6) / 7
    val maxSec = data.values.maxOrNull() ?: 0L
    Canvas(
        modifier
            .height((rows * 24 + (rows - 1) * 4).dp)
            .pointerInput(year, month) {
                // 点击/长按命中: 由 offset 反算 (row, col) → day
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                detectTapGestures(
                    onTap = { offset ->
                        val day = hitTestDay(offset, w, h, rows, firstCol, daysInMonth)
                        if (day != 0) onDayClick(day)
                    },
                    onLongPress = { offset ->
                        val day = hitTestDay(offset, w, h, rows, firstCol, daysInMonth)
                        if (day != 0) onDayLongClick(day)
                    },
                )
            }
    ) {
        val cellW = size.width / 7f
        val cellH = size.height / rows
        val gap = 2f
        for (day in 1..daysInMonth) {
            val idx = firstCol + (day - 1)
            val row = idx / 7
            val col = idx % 7
            val x = col * cellW
            val y = row * cellH
            val sec = data[day] ?: 0L
            val color = if (sec > 0 && maxSec > 0) {
                colors.accent.copy(alpha = 0.2f + 0.8f * (sec.toFloat() / maxSec))
            } else {
                colors.bottomBackground
            }
            drawRect(
                color = color,
                topLeft = Offset(x + gap, y + gap),
                size = Size(cellW - gap * 2, cellH - gap * 2),
            )
            if (day == selectedDay) {
                drawRect(
                    color = colors.accent,
                    topLeft = Offset(x + gap, y + gap),
                    size = Size(cellW - gap * 2, cellH - gap * 2),
                    style = Stroke(width = 2f),
                )
            }
        }
    }
}

/**
 * 热力图点击命中测试: 由 offset 反算 (row, col) → day (1..31), 0 表示未命中空白格。
 */
private fun hitTestDay(
    offset: Offset,
    width: Float,
    height: Float,
    rows: Int,
    firstCol: Int,
    daysInMonth: Int,
): Int {
    val cellW = width / 7f
    val cellH = height / rows
    val col = (offset.x / cellW).toInt()
    val row = (offset.y / cellH).toInt()
    if (col !in 0..6 || row !in 0 until rows) return 0
    val idx = row * 7 + col
    val day = idx - firstCol + 1
    return if (day in 1..daysInMonth) day else 0
}
