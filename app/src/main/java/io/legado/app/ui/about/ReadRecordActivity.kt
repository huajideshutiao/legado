package io.legado.app.ui.about

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.databinding.ViewReadRecordHeaderBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.putInt
import io.legado.app.utils.spToPx
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ReadRecordActivity : BaseActivity<ActivityReadRecordBinding>() {

    private val adapter by lazy { RecordAdapter(this) }
    private var sortMode
        get() = LocalConfig.getInt("readRecordSort", 2)
        set(value) {
            LocalConfig.putInt("readRecordSort", value)
        }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }

    private var headerBinding: ViewReadRecordHeaderBinding? = null

    private var heatmapYear: Int = 0
    private var heatmapMonth: Int = 0
    private var todayYear: Int = 0
    private var todayMonth: Int = 0

    /** 当前筛选的具体日期 yyyyMMdd；0 表示未筛选 */
    private var filterDay: Int = 0
    private var lastSearchKey: String? = null

    /** 缓存全部记录，避免每次过滤都查库 */
    private var allRecords: List<ReadRecord>? = null

    /** 当前列表对应的书籍信息（按 bookName 索引），用于渲染封面/作者 */
    private var bookMap: Map<String, Book> = emptyMap()

    /** 每本书当日累计阅读时长（按 bookName 索引），用于「按名称/时长」排序时展示「当日/总」；
     *  按时间排序且未筛选某日时不使用，此时列表已按 (bookName, day) 拆行，直接读 item.readTime */
    private var todayTimeByBook: Map<String, Long> = emptyMap()

    /** 每本书总阅读时长（按 bookName 索引），仅 perDayMode 下使用，配合 item.readTime 展示「当日/总」 */
    private var totalTimeByBook: Map<String, Long> = emptyMap()

    /** 进行中的列表刷新任务，键入搜索/翻月份时取消上一次，避免堆积 */
    private var initDataJob: Job? = null

    /** 搜索框节流任务，连续输入 300ms 内只触发一次列表刷新 */
    private var searchDebounceJob: Job? = null

    /** 顶部 4 个统计值的缓存，用于删除场景下增量更新，避免再查 DB */
    private var summaryToday = 0L
    private var summaryWeek = 0L
    private var summaryMonth = 0L
    private var summaryAll = 0L
    private var summaryBookCount = 0
    private var summaryAvgRead = 0L

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    @SuppressLint("SimpleDateFormat")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override val binding by viewBinding(ActivityReadRecordBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initHeatmapMonth()
        initView()
        initData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_read_record, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_enable_record)?.isChecked = AppConfig.enableReadRecord
        when (sortMode) {
            1 -> menu.findItem(R.id.menu_sort_read_long)?.isChecked = true
            2 -> menu.findItem(R.id.menu_sort_read_time)?.isChecked = true
            else -> menu.findItem(R.id.menu_sort_name)?.isChecked = true
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_sort_name -> {
                sortMode = 0
                item.isChecked = true
                binding.recyclerView.invalidateItemDecorations()
                initData()
            }

            R.id.menu_sort_read_long -> {
                sortMode = 1
                item.isChecked = true
                binding.recyclerView.invalidateItemDecorations()
                initData()
            }

            R.id.menu_sort_read_time -> {
                sortMode = 2
                item.isChecked = true
                binding.recyclerView.invalidateItemDecorations()
                initData()
            }

            R.id.menu_enable_record -> {
                AppConfig.enableReadRecord = !item.isChecked
            }

            R.id.menu_clear_all -> {
                alert(R.string.delete, R.string.sure_del) {
                    yesButton {
                        lifecycleScope.launch {
                            withContext(IO) { appDb.readRecordDao.clear() }
                            // 直接清空内存缓存，initData 会基于空列表算出全 0 的 summary
                            allRecords = emptyList()
                            refreshHeatmap()
                            initData()
                        }
                    }
                    noButton()
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        initSearchView()
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(DaySectionDecoration())
        binding.recyclerView.applyNavigationBarPadding()
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && searchView.hasFocus()) {
                    searchView.clearFocus()
                }
            }
        })

        adapter.addHeaderView { parent ->
            ViewReadRecordHeaderBinding.inflate(layoutInflater, parent, false).also {
                headerBinding = it
                bindHeader(it)
                // header 可能在 initData 完成后才被 inflate；用当前缓存字段先渲染一次，
                // 后续 initData 完成会再调 renderSummary 覆盖
                renderSummary()
                refreshHeatmap()
            }
        }
    }

    private fun bindHeader(header: ViewReadRecordHeaderBinding) {
        val bgColor = bottomBackground
        header.cvStats.setCardBackgroundColor(bgColor)
        header.cvHeatmap.setCardBackgroundColor(bgColor)
        header.ivPrevMonth.setOnClickListener { stepMonth(-1) }
        header.ivNextMonth.setOnClickListener {
            if (!isAtCurrentMonth()) stepMonth(1)
        }
        header.heatMap.onDayClick = { day, _, selected ->
            val dayKey = heatmapYear * 10000 + heatmapMonth * 100 + day
            filterDay = if (selected) dayKey else 0
            initData()
        }
        header.heatMap.onDayLongClick = { day, _ ->
            val dayKey = heatmapYear * 10000 + heatmapMonth * 100 + day
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
    }

    private fun isAtCurrentMonth(): Boolean {
        return heatmapYear == todayYear && heatmapMonth == todayMonth
    }

    private fun updateNextMonthEnabled() {
        val header = headerBinding ?: return
        val atCurrent = isAtCurrentMonth()
        header.ivNextMonth.alpha = if (atCurrent) 0.3f else 1f
        header.ivNextMonth.isClickable = !atCurrent
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                // 提交时立即执行，不再等待节流窗口
                searchDebounceJob?.cancel()
                initData(query)
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // 连续输入只在停顿 300ms 后才真正过一次 initData，
                // 避免每个按键都对 allRecords 做一遍单遍聚合
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    initData(newText)
                }
                return false
            }
        })
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

    private fun renderSummary() {
        val header = headerBinding ?: return
        header.tvTodayValue.text = formatDuring(summaryToday)
        header.tvWeekValue.text = formatDuring(summaryWeek)
        header.tvMonthValue.text = formatDuring(summaryMonth)
        header.tvAllValue.text = formatDuring(summaryAll)
        header.tvBookCountValue.text = summaryBookCount.toString()
        header.tvAvgReadValue.text = formatDuring(summaryAvgRead)
    }

    private fun refreshHeatmap() {
        val header = headerBinding ?: return
        header.tvMonthLabel.text =
            getString(R.string.month_label_format, heatmapYear, heatmapMonth)
        updateNextMonthEnabled()
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
        val selectedDayOfMonth =
            if (filterDay != 0 && filterDay / 10000 == year && (filterDay / 100) % 100 == month) {
                filterDay % 100
            } else 0
        header.heatMap.setMonth(year, month, data, selectedDayOfMonth)
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
        // 按时间排序且未筛选某日时，列表展示每本书每天一行，readTime 即当天时长；
        // 其它情况按 bookName 聚合为一行，配合 todayPerBook 显示「当日/总」
        val perDayMode = currentSortMode == 2 && day == 0
        initDataJob?.cancel()
        initDataJob = lifecycleScope.launch {
            val result = withContext(IO) {
                val records = allRecords ?: appDb.readRecordDao.all.also { allRecords = it }
                // 单遍聚合：搜索过滤 + 总时长/最近阅读时间 + 当日时长 + 筛选日存在性
                // + 顺手把 today/week/month/all 4 个 summary 也算了，省掉 4 次 DAO 查询
                // 注：DAO.all 自带 readTime >= 60000 过滤，<1 分钟的零碎记录不计入；
                // 与列表展示口径一致，summary 与列表总和自洽
                val dayForToday = if (day != 0) day else todayKey
                val expected = records.size.coerceAtMost(256).coerceAtLeast(16)
                val readTimeByBook = if (perDayMode) null else HashMap<String, Long>(expected)
                val lastReadByBook = if (perDayMode) null else HashMap<String, Long>(expected)
                val todayPerBook = if (perDayMode) null else HashMap<String, Long>(expected)
                val perDayMap =
                    if (perDayMode) HashMap<Long, ReadRecordShow>(expected) else null
                // perDayMode 下每行代表某本书某天，仍需每本书的总时长展示「当日/总」
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
                    // summary 不受搜索词/筛选影响，先算
                    sumAll += rt
                    if (d == todayKey) sumToday += rt
                    if (d in weekStart..weekEnd) sumWeek += rt
                    if (d in monthStart..monthEnd) sumMonth += rt
                    val name = r.bookName
                    if (!keyEmpty && !name.contains(key, ignoreCase = true)) continue
                    if (perDayMode) {
                        val mapKey = name.hashCode().toLong().shl(32) or d.toLong()
                        val existing = perDayMap!![mapKey]
                        if (existing == null) {
                            perDayMap[mapKey] = ReadRecordShow(name, rt, lastRead)
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
            // 取消后会抛 CancellationException，不会跑到这里覆盖更新的状态
            bookMap = result.books
            todayTimeByBook = result.todayMap
            totalTimeByBook = result.totalMap
            summaryToday = result.sumToday
            summaryWeek = result.sumWeek
            summaryMonth = result.sumMonth
            summaryAll = result.sumAll
            summaryBookCount = result.bookCount
            summaryAvgRead = result.avgRead
            renderSummary()
            adapter.setItems(result.items)
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

    inner class DaySectionDecoration : RecyclerView.ItemDecoration() {

        private val sectionHeight = 26f.dpToPx()
        private val paddingHorizontal = 14f.dpToPx()
        private val bgPaint = Paint().apply {
            color = this@ReadRecordActivity.bottomBackground
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@ReadRecordActivity.secondaryTextColor
            textSize = 12f.spToPx()
        }
        private val baselineOffset = -(textPaint.descent() + textPaint.ascent()) / 2f

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            // 入口早退：仅在按时间排序且未做单日筛选时才显示分段
            if (sortMode != 2 || filterDay != 0) return
            if (isSectionStart(view, parent)) {
                outRect.top = sectionHeight.toInt()
            }
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            if (sortMode != 2 || filterDay != 0) return
            val childCount = parent.childCount
            if (childCount == 0) return
            // 循环不变量在外面算好：父视图宽度、文本基线偏移、headerCount
            val width = parent.width.toFloat()
            val headerCount = adapter.getHeaderCount()
            for (i in 0 until childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) continue
                val dataIdx = pos - headerCount
                if (dataIdx < 0) continue
                val item = adapter.getItem(dataIdx) ?: continue
                val itemDayKey = ReadRecord.dayKey(item.lastRead)
                val isStart = if (dataIdx == 0) {
                    true
                } else {
                    val prev = adapter.getItem(dataIdx - 1)
                    prev == null || ReadRecord.dayKey(prev.lastRead) != itemDayKey
                }
                if (!isStart) continue
                val bottom = child.top.toFloat()
                val top = bottom - sectionHeight
                c.drawRect(0f, top, width, bottom, bgPaint)
                val textY = (top + bottom) / 2f + baselineOffset
                c.drawText(formatDayKey(itemDayKey), paddingHorizontal, textY, textPaint)
            }
        }

        private fun isSectionStart(view: View, parent: RecyclerView): Boolean {
            // sortMode/filterDay 早退由调用方负责
            val pos = parent.getChildAdapterPosition(view)
            if (pos == RecyclerView.NO_POSITION) return false
            val dataIdx = pos - adapter.getHeaderCount()
            if (dataIdx < 0) return false
            val item = adapter.getItem(dataIdx) ?: return false
            if (dataIdx == 0) return true
            val prev = adapter.getItem(dataIdx - 1) ?: return true
            return ReadRecord.dayKey(prev.lastRead) != ReadRecord.dayKey(item.lastRead)
        }
    }

    inner class RecordAdapter(context: Context) :
        RecyclerAdapter<ReadRecordShow, ItemBookshelfListBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding {
            return ItemBookshelfListBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemBookshelfListBinding,
            item: ReadRecordShow,
            payloads: MutableList<Any>,
        ) = binding.run {
            val book = bookMap[item.bookName]
            tvName.text = item.bookName
            tvAuthor.text = book?.author.orEmpty()
            // perDayMode 下 item.readTime 就是当行那天的时长，「总」需要查 totalTimeByBook；
            // 其它模式 item.readTime 已是全部累计，「当日」查 todayTimeByBook
            val (dayTime, totalTime) = if (sortMode == 2 && filterDay == 0) {
                item.readTime to (totalTimeByBook[item.bookName] ?: item.readTime)
            } else {
                (todayTimeByBook[item.bookName] ?: 0L) to item.readTime
            }
            tvRead.text = getString(
                R.string.read_record_today_total,
                formatDuring(dayTime),
                formatDuring(totalTime)
            )
            tvLast.text = when {
                item.lastRead > 0 -> dateFormat.format(item.lastRead * 1000L)
                book != null && book.durChapterTime > 0 -> dateFormat.format(book.durChapterTime / 1000L * 1000L)
                else -> ""
            }
            tvLastUpdateTime.text = ""
            flHasNew.visibility = View.GONE
            ivCover.load(
                book?.getDisplayCover(),
                item.bookName,
                book?.author,
                false,
                book?.origin,
                inBookshelf = book != null
            )
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfListBinding) {
            holder.itemView.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition)
                    ?: return@setOnClickListener
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
            holder.itemView.setOnLongClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { item ->
                    sureDelAlert(item)
                }
                true
            }
        }

        private fun sureDelAlert(item: ReadRecordShow) {
            alert(R.string.delete) {
                setMessage(getString(R.string.sure_del_any, item.bookName))
                yesButton {
                    val name = item.bookName
                    lifecycleScope.launch {
                        withContext(IO) { appDb.readRecordDao.deleteByName(name) }
                        // 内存缓存直接同步删除，避免重新查 DAO.all
                        allRecords = allRecords?.filterNot { it.bookName == name }
                        refreshHeatmap()
                        initData()
                    }
                }
                noButton()
            }
        }

    }

    override fun finish() {
        if (searchView.hasFocus()) {
            searchView.clearFocus()
            return
        }
        super.finish()
    }

    /**
     * 把毫秒数格式化为「X小时Y分钟」形式，不再按天换算。
     * 小于 1 分钟显示秒。
     */
    fun formatDuring(seconds: Long): String {
        if (seconds <= 0L) return "0 分钟"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分钟"
            hours > 0 -> "$hours 小时"
            minutes > 0 -> "$minutes 分钟"
            else -> "$secs 秒"
        }
    }

    private fun formatDayKey(dayKey: Int): String {
        if (dayKey <= 0) return ""
        val y = dayKey / 10000
        val m = (dayKey / 100) % 100
        val d = dayKey % 100
        return "%d-%02d-%02d".format(y, m, d)
    }

}
