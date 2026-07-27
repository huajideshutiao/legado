package io.legado.app.ui.about

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.CoverRatio
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.main.bookshelf.ShelfCover
import io.legado.app.utils.cnCompare
import io.legado.app.utils.putInt
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 阅读记录 (薄壳)。
 *
 * Composable 渲染已下沉到 shared/sharedUiMain 的 [ReadRecordScreen], 本 Activity 仅:
 * - 持有 [ReadRecordUiState] 各字段 (mutableStateOf / mutableIntStateOf / mutableLongStateOf)
 * - 实现 [ReadRecordUiActions] 接口, 在回调内桥接平台依赖
 *   (`alert` / `appDb` / `lifecycleScope` / `SearchActivity` / `startActivityForBook` /
 *   `AppConfig` / `LocalConfig`)
 * - [Content] 内构造 state, 调用 [ReadRecordScreen] 渲染, 提供 [heatmapSlot] (MonthHeatMapView)
 *   与 [coverSlot] (ShelfCover) 平台注入
 *
 * 聚合逻辑 (单遍统计 / 缓存 / 节流) 原样保留; MonthHeatMapView 自绘控件经 AndroidView 承载
 * (附录 D 登记债)。
 */
class ReadRecordActivity : BaseComposeActivity(), ReadRecordUiActions {

    private var sortModePref
        get() = LocalConfig.getInt("readRecordSort", 2)
        set(value) {
            LocalConfig.putInt("readRecordSort", value)
        }

    // ---- Compose 状态 ----
    private var sortMode by mutableIntStateOf(2)
    private var heatmapYear by mutableIntStateOf(0)
    private var heatmapMonth by mutableIntStateOf(0)
    private var todayYear: Int = 0
    private var todayMonth: Int = 0

    /** 当前筛选的具体日期 yyyyMMdd; 0 表示未筛选 */
    private var filterDay by mutableIntStateOf(0)
    private var searchText by mutableStateOf("")
    private var lastSearchKey: String? = null

    /** 缓存全部记录, 避免每次过滤都查库 */
    private var allRecords: List<ReadRecord>? = null

    private var items by mutableStateOf<List<ReadRecordShow>>(emptyList())

    /** 当前列表对应的书籍信息 (按 bookName 索引), 用于渲染封面/作者 */
    private var bookMap by mutableStateOf<Map<String, Book>>(emptyMap())

    /** 每本书当日累计阅读时长 (按 bookName 索引), 用于「按名称/时长」排序时展示「当日/总」;
     *  按时间排序且未筛选某日时不使用, 此时列表已按 (bookName, day) 拆行, 直接读 item.readTime */
    private var todayTimeByBook: Map<String, Long> by mutableStateOf(emptyMap())

    /** 每本书总阅读时长 (按 bookName 索引), 仅 perDayMode 下使用, 配合 item.readTime 展示「当日/总」 */
    private var totalTimeByBook: Map<String, Long> by mutableStateOf(emptyMap())

    /** 列表快照对应的展示模式, 避免刷新途中 sortMode 已变造成「当日/总」口径错位 */
    private var itemsPerDayMode by mutableStateOf(true)

    /** 热力图当月数据 + 选中日 */
    private var heatmapData by mutableStateOf<Map<Int, Long>>(emptyMap())
    private var heatmapSelectedDay by mutableIntStateOf(0)

    /** 进行中的列表刷新任务, 键入搜索/翻月份时取消上一次, 避免堆积 */
    private var initDataJob: Job? = null

    /** 搜索框节流任务, 连续输入 300ms 内只触发一次列表刷新 */
    private var searchDebounceJob: Job? = null

    /** 顶部 4 个统计值的缓存, 用于删除场景下增量更新, 避免再查 DB */
    private var summaryToday by mutableLongStateOf(0L)
    private var summaryWeek by mutableLongStateOf(0L)
    private var summaryMonth by mutableLongStateOf(0L)
    private var summaryAll by mutableLongStateOf(0L)
    private var summaryBookCount by mutableIntStateOf(0)
    private var summaryAvgRead by mutableLongStateOf(0L)

    /** 镜像 AppConfig.enableReadRecord, 切换时同步两端; mutableStateOf 触发 UI 重组 */
    private var enableReadRecord by mutableStateOf(AppConfig.enableReadRecord)

    /** 搜索框聚焦状态; mutableStateOf 触发 Content 重组, 让 state.searchFocused 同步更新 */
    private var searchFocused by mutableStateOf(false)
    private var clearSearchFocusFn: (() -> Unit)? = null

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        sortMode = sortModePref
        initHeatmapMonth()
        initData()
    }

    @Composable
    override fun Content() {
        val focusManager = LocalFocusManager.current
        clearSearchFocusFn = { focusManager.clearFocus() }
        val state = ReadRecordUiState(
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
        ReadRecordScreen(
            state = state,
            actions = this,
            heatmapSlot = { modifier ->
                AndroidView(
                    factory = { ctx ->
                        MonthHeatMapView(ctx).apply {
                            onDayClick = { day, _, selected ->
                                val dayKey = heatmapYear * 10000 + heatmapMonth * 100 + day
                                filterDay = if (selected) dayKey else 0
                                initData()
                            }
                            onDayLongClick = { day, _ ->
                                val dayKey = heatmapYear * 10000 + heatmapMonth * 100 + day
                                confirmDeleteDay(dayKey)
                            }
                        }
                    },
                    update = { view ->
                        view.setMonth(heatmapYear, heatmapMonth, heatmapData, heatmapSelectedDay)
                    },
                    modifier = modifier,
                )
            },
            coverSlot = { item, book, modifier ->
                ShelfCover(
                    path = book?.getDisplayCover(),
                    name = item.bookName,
                    author = book?.author,
                    origin = book?.origin,
                    ratio = CoverRatio.NOVEL,
                    reloadKey = 0,
                    inBookshelf = book != null,
                    modifier = modifier,
                )
            },
        )
    }

    // ===== ReadRecordUiActions 适配 =====

    override fun onBack() = finish()

    override fun onSearchChange(text: String) {
        searchText = text
        // 连续输入只在停顿 300ms 后才真正过一次 initData,
        // 避免每个按键都对 allRecords 做一遍单遍聚合
        searchDebounceJob?.cancel()
        searchDebounceJob = lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            initData(text)
        }
    }

    override fun onSearch(text: String) {
        searchDebounceJob?.cancel()
        clearSearchFocusFn?.invoke()
        initData(text)
    }

    override fun onSearchFocusChanged(focused: Boolean) {
        searchFocused = focused
    }

    override fun onSortSelect(mode: Int) {
        if (sortMode != mode) {
            sortMode = mode
            sortModePref = mode
            initData()
        }
    }

    override fun onToggleEnableRecord() {
        enableReadRecord = !enableReadRecord
        AppConfig.enableReadRecord = enableReadRecord
    }

    override fun onClearAll() {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) { appDb.readRecordDao.clear() }
                    // 直接清空内存缓存, initData 会基于空列表算出全 0 的 summary
                    allRecords = emptyList()
                    refreshHeatmap()
                    initData()
                }
            }
            noButton()
        }
    }

    override fun onStepMonth(delta: Int) = stepMonth(delta)

    override fun openBook(item: ReadRecordShow) {
        lifecycleScope.launch {
            val book = bookMap[item.bookName] ?: withContext(IO) {
                appDb.bookDao.findByName(item.bookName).firstOrNull()
            }
            if (book == null) {
                SearchActivity.start(this@ReadRecordActivity, item.bookName)
            } else {
                startActivityForBook(book)
            }
        }
    }

    override fun sureDelAlert(item: ReadRecordShow) {
        alert(R.string.delete) {
            setMessage(getString(R.string.sure_del_any, item.bookName))
            yesButton {
                val name = item.bookName
                lifecycleScope.launch {
                    withContext(IO) { appDb.readRecordDao.deleteByName(name) }
                    // 内存缓存直接同步删除, 避免重新查 DAO.all
                    allRecords = allRecords?.filterNot { it.bookName == name }
                    refreshHeatmap()
                    initData()
                }
            }
            noButton()
        }
    }

    override fun clearSearchFocus() {
        clearSearchFocusFn?.invoke()
    }

    // ---- 平台相关方法 (依赖 alert / appDb / Calendar / LocalConfig, 不下沉) ----

    private fun confirmDeleteDay(dayKey: Int) {
        alert(R.string.delete) {
            setMessage(getString(R.string.sure_del_any, formatDayKey(dayKey)))
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) { appDb.readRecordDao.deleteByDay(dayKey) }
                    allRecords = allRecords?.filterNot { it.day == dayKey }
                    refreshHeatmap()
                    initData()
                }
            }
            noButton()
        }
    }

    private fun initHeatmapMonth() {
        val cal = Calendar.getInstance()
        todayYear = cal.get(Calendar.YEAR)
        todayMonth = cal.get(Calendar.MONTH) + 1
        heatmapYear = todayYear
        heatmapMonth = todayMonth
    }

    private fun stepMonth(delta: Int) {
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

    private fun refreshHeatmap() {
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

    private fun initData(searchKey: String? = lastSearchKey) {
        lastSearchKey = searchKey
        val day = filterDay
        val key = searchKey?.trim().orEmpty()
        val now = System.currentTimeMillis()
        val todayKey = ReadRecord.dayKey(now / 1000)
        val (weekStart, weekEnd) = weekRange(now)
        val (monthStart, monthEnd) = monthRange(now)
        val currentSortMode = sortMode
        // 按时间排序且未筛选某日时, 列表展示每本书每天一行, readTime 即当天时长;
        // 其它情况按 bookName 聚合为一行, 配合 todayPerBook 显示「当日/总」
        val perDayMode = currentSortMode == 2 && day == 0
        initDataJob?.cancel()
        initDataJob = lifecycleScope.launch {
            val result = withContext(IO) {
                val records = allRecords ?: appDb.readRecordDao.all().also { allRecords = it }
                // 单遍聚合: 搜索过滤 + 总时长/最近阅读时间 + 当日时长 + 筛选日存在性
                // + 顺手把 today/week/month/all 4 个 summary 也算了, 省掉 4 次 DAO 查询
                // 注: DAO.all 自带 readTime >= 60000 过滤, <1 分钟的零碎记录不计入;
                // 与列表展示口径一致, summary 与列表总和自洽
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
                val items: ArrayList<ReadRecordShow> = if (perDayMode) {
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
                val sortedItems = when (currentSortMode) {
                    1 -> items.apply { sortByDescending { it.readTime } }
                    2 -> items.apply { sortByDescending { it.lastRead } }
                    else -> items.apply {
                        sortWith { o1, o2 -> o1.bookName.cnCompare(o2.bookName) }
                    }
                }
                val names = sortedItems.mapTo(LinkedHashSet(sortedItems.size)) { it.bookName }
                    .toTypedArray()
                val bookList = if (names.isEmpty()) emptyList()
                else appDb.bookDao.findByName(*names)
                val avgRead = if (bookCount > 0) sumAll / bookCount else 0L
                InitResult(
                    sortedItems,
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

    private fun weekRange(now: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val start = ReadRecord.dayKey(cal.timeInMillis / 1000)
        cal.add(Calendar.DAY_OF_MONTH, 6)
        val end = ReadRecord.dayKey(cal.timeInMillis / 1000)
        return start to end
    }

    private fun monthRange(now: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        return monthRange(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    private fun monthRange(year: Int, month: Int): Pair<Int, Int> {
        val start = year * 10000 + month * 100 + 1
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, 1)
        val end = year * 10000 + month * 100 + cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return start to end
    }

    override fun finish() {
        if (searchFocused) {
            clearSearchFocusFn?.invoke()
            return
        }
        super.finish()
    }

}
