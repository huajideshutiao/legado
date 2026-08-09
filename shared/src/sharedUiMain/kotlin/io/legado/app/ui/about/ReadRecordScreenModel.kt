package io.legado.app.ui.about

import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.cnCompare
import io.legado.app.utils.prevDayKey
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.yearMonthDayFromMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读记录页 shared ScreenModel: 托管 [ReadRecordUiState], 迁移原 [ReadRecordActivity] 的
 * 单遍聚合算法 / 热力图计算 / 节流 / 删除确认事件, 通过 [dispatch] 处理可下沉事件。
 *
 * 平台相关依赖通过构造参数注入:
 * - [initialSortMode]: 宿主读 LocalConfig "readRecordSort" 传入 (LocalConfig 依赖 Android SP "local" 文件)
 * - [persistSortMode]: 宿主写 LocalConfig 传入 (与 [initialSortMode] 同一 SP 文件保真)
 *
 * enableReadRecord 读写: 读走 [AppConfigProviders] (default SP), 写走 [PreferenceProviders] (default SP),
 * 与原 AppConfig.enableReadRecord 行为一致。
 */
class ReadRecordScreenModel(
    private val initialSortMode: Int,
    private val persistSortMode: (Int) -> Unit,
) : ScreenModel {

    // 自管 scope (app 端无 ScreenModelStore 时由宿主 DisposableEffect 调 onCleared)
    private val scope = screenModelScope("阅读记录")

    private val _state = MutableStateFlow(ReadRecordUiState())
    val state: StateFlow<ReadRecordUiState> = _state.asStateFlow()

    // ---- 内部缓存 (非 UI 状态, 不暴露) ----

    /** 缓存全部记录, 避免每次过滤都查库 */
    private var allRecords: List<ReadRecord>? = null

    /** 进行中的列表刷新任务, 键入搜索/翻月份时取消上一次, 避免堆积 */
    private var initDataJob: Job? = null

    /** 搜索框节流任务, 连续输入 300ms 内只触发一次列表刷新 */
    private var searchDebounceJob: Job? = null

    private var lastSearchKey: String? = null

    init {
        updateState { it.copy(sortMode = initialSortMode) }
        initHeatmapMonth()
        initData()
    }

    private fun updateState(block: (ReadRecordUiState) -> ReadRecordUiState) {
        _state.value = block(_state.value)
    }

    fun dispatch(event: ReadRecordUiEvent) {
        when (event) {
            is ReadRecordUiEvent.SearchChanged -> {
                updateState { it.copy(searchText = event.text) }
                searchDebounceJob?.cancel()
                searchDebounceJob = scope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    initData(event.text)
                }
            }

            is ReadRecordUiEvent.SearchSubmitted -> {
                searchDebounceJob?.cancel()
                updateState { it.copy(searchText = event.text) }
                initData(event.text)
            }

            is ReadRecordUiEvent.ChangeSortMode -> {
                if (_state.value.sortMode != event.mode) {
                    updateState { it.copy(sortMode = event.mode) }
                    persistSortMode(event.mode)
                    initData()
                }
            }

            is ReadRecordUiEvent.StepMonth -> stepMonth(event.delta)

            is ReadRecordUiEvent.DeleteDay -> {
                scope.launch {
                    withContext(IoDispatcher) {
                        AppDbProviders.get().readRecordDao.deleteByDay(event.dayKey)
                    }
                    // 内存缓存直接同步删除, 避免重新查 DAO.all
                    allRecords = allRecords?.filterNot { it.day == event.dayKey }
                    refreshHeatmap()
                    initData()
                }
            }

            is ReadRecordUiEvent.DeleteByName -> {
                scope.launch {
                    withContext(IoDispatcher) {
                        AppDbProviders.get().readRecordDao.deleteByName(event.name)
                    }
                    allRecords = allRecords?.filterNot { it.bookName == event.name }
                    refreshHeatmap()
                    initData()
                }
            }

            ReadRecordUiEvent.ClearAll -> {
                scope.launch {
                    withContext(IoDispatcher) {
                        AppDbProviders.get().readRecordDao.clear()
                    }
                    // 直接清空内存缓存, initData 会基于空列表算出全 0 的 summary
                    allRecords = emptyList()
                    refreshHeatmap()
                    initData()
                }
            }

            ReadRecordUiEvent.ToggleEnableRecord -> {
                val newValue = !_state.value.enableReadRecord
                PreferenceProviders.get().putBoolean(PreferKey.enableReadRecord, newValue)
                updateState { it.copy(enableReadRecord = newValue) }
            }

            is ReadRecordUiEvent.SetSearchFocused -> {
                updateState { it.copy(searchFocused = event.focused) }
            }

            // 对照 app 端 clearSearchFocusFn?.invoke() / focusManager.clearFocus():
            // 置 searchFocused=false + 递增 clearFocusToken, Screen 内 LaunchedEffect 监听 token 调 focusManager.clearFocus()
            ReadRecordUiEvent.ClearSearchFocus -> {
                updateState {
                    it.copy(
                        searchFocused = false,
                        clearFocusToken = it.clearFocusToken + 1,
                    )
                }
            }

            is ReadRecordUiEvent.SelectHeatmapDay -> {
                updateState { it.copy(filterDay = event.dayKey) }
                initData()
            }
        }
    }

    // ---- 聚合 / 热力图 (从原 Activity 等价迁移, java.util.Calendar 改 KMP 算法) ----

    private fun initHeatmapMonth() {
        val now = systemCurrentTimeMillis()
        val (y, m, _) = yearMonthDayFromMillis(now)
        updateState {
            it.copy(
                heatmapYear = y,
                heatmapMonth = m,
                todayYear = y,
                todayMonth = m,
                enableReadRecord = AppConfigProviders.get().enableReadRecord,
            )
        }
    }

    private fun stepMonth(delta: Int) {
        val s = _state.value
        var newYear = s.heatmapYear
        var newMonth = s.heatmapMonth + delta
        while (newMonth > 12) {
            newMonth -= 12; newYear += 1
        }
        while (newMonth < 1) {
            newMonth += 12; newYear -= 1
        }
        // 不允许越过当前月份
        if (newYear > s.todayYear || (newYear == s.todayYear && newMonth > s.todayMonth)) {
            return
        }
        updateState { it.copy(heatmapYear = newYear, heatmapMonth = newMonth) }
        refreshHeatmap()
    }

    private fun refreshHeatmap() {
        val s = _state.value
        val year = s.heatmapYear
        val month = s.heatmapMonth
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
        val selectedDay =
            if (s.filterDay != 0 && s.filterDay / 10000 == year && (s.filterDay / 100) % 100 == month) {
                s.filterDay % 100
            } else 0
        updateState {
            it.copy(heatmapData = data, heatmapSelectedDay = selectedDay)
        }
    }

    private fun initData(searchKey: String? = lastSearchKey) {
        lastSearchKey = searchKey
        val s = _state.value
        val day = s.filterDay
        val key = searchKey?.trim().orEmpty()
        val now = systemCurrentTimeMillis()
        val todayKey = ReadRecord.dayKey(now / 1000)
        val (weekStart, weekEnd) = weekRange(now)
        val (monthStart, monthEnd) = monthRange(now)
        val currentSortMode = s.sortMode
        // 按时间排序且未筛选某日时, 列表展示每本书每天一行, readTime 即当天时长;
        // 其它情况按 bookName 聚合为一行, 配合 todayPerBook 显示「当日/总」
        val perDayMode = currentSortMode == 2 && day == 0
        initDataJob?.cancel()
        initDataJob = scope.launch {
            val result = withContext(IoDispatcher) {
                val records =
                    allRecords ?: AppDbProviders.get().readRecordDao.all().also { allRecords = it }
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
                else AppDbProviders.get().bookDao.findByName(*names)
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
            updateState {
                it.copy(
                    bookMap = result.books,
                    todayTimeByBook = result.todayMap,
                    totalTimeByBook = result.totalMap,
                    summaryToday = result.sumToday,
                    summaryWeek = result.sumWeek,
                    summaryMonth = result.sumMonth,
                    summaryAll = result.sumAll,
                    summaryBookCount = result.bookCount,
                    summaryAvgRead = result.avgRead,
                    itemsPerDayMode = perDayMode,
                    items = result.items,
                )
            }
            refreshHeatmap()
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

    // ---- 日期范围计算 (KMP 等价 java.util.Calendar, Howard Hinnant 算法) ----

    private fun weekRange(now: Long): Pair<Int, Int> {
        // 1970-01-01 是周四; firstDayOfWeek=MONDAY 下, 本周一 = today - offset
        val (y, m, d) = yearMonthDayFromMillis(now)
        val daysSinceEpoch = daysFromCivil(y, m, d)
        // Calendar.SUNDAY=1..SATURDAY=7; 1970-01-01 (days=0) 是周四 (dayOfWeek=5)
        val dayOfWeek = floorMod(daysSinceEpoch + 4L, 7L).toInt() + 1
        // 距周一的偏移: dayOfWeek - Calendar.MONDAY(=2) + 7, mod 7
        val offset = (dayOfWeek - 2 + 7) % 7
        val mondayDays = daysSinceEpoch - offset
        val sundayDays = mondayDays + 6
        val (my, mm, md) = civilFromDays(mondayDays)
        val (sy, sm, sd) = civilFromDays(sundayDays)
        return (my * 10000 + mm * 100 + md) to (sy * 10000 + sm * 100 + sd)
    }

    private fun monthRange(now: Long): Pair<Int, Int> {
        val (y, m, _) = yearMonthDayFromMillis(now)
        return monthRange(y, m)
    }

    private fun monthRange(year: Int, month: Int): Pair<Int, Int> {
        val start = year * 10000 + month * 100 + 1
        // 月末: 下个月 1 号的前一天 (与 cal.getActualMaximum(DAY_OF_MONTH) 等价)
        val (nextY, nextM) = if (month == 12) (year + 1) to 1 else year to (month + 1)
        val nextMonthStart = nextY * 10000 + nextM * 100 + 1
        val end = prevDayKey(nextMonthStart)
        return start to end
    }

    override fun onCleared() {
        scope.cancel()
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}

/** 阅读记录页可下沉到 shared 层处理的 UI 事件 (平台相关事件如弹窗/openBook 仍走 [ReadRecordUiActions])。 */
sealed interface ReadRecordUiEvent {
    /** 搜索框文本变化 (节流 300ms 后刷新列表) */
    data class SearchChanged(val text: String) : ReadRecordUiEvent

    /** 搜索框 IME 搜索键回调 (立即刷新列表) */
    data class SearchSubmitted(val text: String) : ReadRecordUiEvent

    /** 选择排序模式 (0 名称 / 1 时长 / 2 时间) */
    data class ChangeSortMode(val mode: Int) : ReadRecordUiEvent

    /** 热力图月份切换 (+1 下月 / -1 上月, 不越过当前月份) */
    data class StepMonth(val delta: Int) : ReadRecordUiEvent

    /** 删除某天记录 (热力图长按触发, 弹窗在 Activity 内) */
    data class DeleteDay(val dayKey: Int) : ReadRecordUiEvent

    /** 删除某本书全部记录 (列表长按触发, 弹窗在 Activity 内) */
    data class DeleteByName(val name: String) : ReadRecordUiEvent

    /** 清空全部阅读记录 (弹窗在 Activity 内) */
    data object ClearAll : ReadRecordUiEvent

    /** 切换是否启用阅读记录 */
    data object ToggleEnableRecord : ReadRecordUiEvent

    /** 搜索框焦点变化 (列表滚动时收起焦点) */
    data class SetSearchFocused(val focused: Boolean) : ReadRecordUiEvent

    /** 收起搜索框焦点 (对照 app 端 clearSearchFocusFn?.invoke() / focusManager.clearFocus()) */
    data object ClearSearchFocus : ReadRecordUiEvent

    /** 热力图点击某天 (dayKey=0 表示取消筛选) */
    data class SelectHeatmapDay(val dayKey: Int) : ReadRecordUiEvent
}

// ---- Howard Hinnant 日期算法 (纯 Kotlin, 与 jvmAndAndroidMain Calendar 等价) ----

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val m = if (month > 2) month else month + 9
    val era = if (y >= 0) y / 400 else (y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m - 3) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

private fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
    val z = daysSinceEpoch + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    // 算术传播使 year/m/d 为 Long; 调用方期望 Int (年月日均落在 Int 范围内)
    return Triple(year.toInt(), m.toInt(), d.toInt())
}

private fun floorMod(a: Long, b: Long): Long = ((a % b) + b) % b
