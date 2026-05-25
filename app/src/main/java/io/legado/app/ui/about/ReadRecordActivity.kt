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
import io.legado.app.utils.getInt
import io.legado.app.utils.putInt
import io.legado.app.utils.spToPx
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ReadRecordActivity : BaseActivity<ActivityReadRecordBinding>() {

    private val adapter by lazy { RecordAdapter(this) }
    private var sortMode
        get() = LocalConfig.getInt("readRecordSort")
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

    /** 每本书今日累计阅读时长（按 bookName 索引），用于条目展示「今日/总」 */
    private var todayTimeByBook: Map<String, Long> = emptyMap()

    @SuppressLint("SimpleDateFormat")
    private val minuteFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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
                        appDb.readRecordDao.clear()
                        invalidateRecords()
                        refreshSummary()
                        refreshHeatmap()
                        initData()
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

        adapter.addHeaderView { parent ->
            ViewReadRecordHeaderBinding.inflate(layoutInflater, parent, false).also {
                headerBinding = it
                bindHeader(it)
                refreshSummary()
                refreshHeatmap()
            }
        }
    }

    private fun bindHeader(header: ViewReadRecordHeaderBinding) {
        header.ivPrevMonth.setOnClickListener { stepMonth(-1) }
        header.ivNextMonth.setOnClickListener {
            if (!isAtCurrentMonth()) stepMonth(1)
        }
        header.heatMap.onDayClick = { day, _, selected ->
            val dayKey = heatmapYear * 10000 + heatmapMonth * 100 + day
            filterDay = if (selected) dayKey else 0
            initData()
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
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                initData(newText)
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

    private fun refreshSummary() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val today = ReadRecord.dayKey(now)
            val (weekStart, weekEnd) = weekRange(now)
            val (monthStart, monthEnd) = monthRange(now)
            val dao = appDb.readRecordDao
            val (todayTime, weekTime, monthTime, allTime) = withContext(IO) {
                listOf(
                    dao.getDayTime(today),
                    dao.getRangeTime(weekStart, weekEnd),
                    dao.getRangeTime(monthStart, monthEnd),
                    dao.allTime
                )
            }
            val header = headerBinding ?: return@launch
            header.tvTodayValue.text = formatDuring(todayTime)
            header.tvWeekValue.text = formatDuring(weekTime)
            header.tvMonthValue.text = formatDuring(monthTime)
            header.tvAllValue.text = formatDuring(allTime)
        }
    }

    private fun refreshHeatmap() {
        val header = headerBinding ?: return
        header.tvMonthLabel.text =
            getString(R.string.month_label_format, heatmapYear, heatmapMonth)
        updateNextMonthEnabled()
        val year = heatmapYear
        val month = heatmapMonth
        lifecycleScope.launch {
            val (start, end) = monthRange(year, month)
            val stats = withContext(IO) {
                appDb.readRecordDao.getRangeStats(start, end)
            }
            val data = HashMap<Int, Long>(stats.size)
            for (s in stats) {
                val day = s.day % 100
                data[day] = (data[day] ?: 0L) + s.readTime
            }
            // 防止月份切换过快出现错位
            if (year == heatmapYear && month == heatmapMonth) {
                val selectedDayOfMonth =
                    if (filterDay != 0 && filterDay / 10000 == year && (filterDay / 100) % 100 == month) {
                        filterDay % 100
                    } else 0
                headerBinding?.heatMap?.setMonth(year, month, data, selectedDayOfMonth)
            }
        }
    }

    private fun initData(searchKey: String? = lastSearchKey) {
        lastSearchKey = searchKey
        val day = filterDay
        val key = searchKey?.trim().orEmpty()
        val todayKey = ReadRecord.dayKey()
        lifecycleScope.launch {
            val (sorted, books, todayMap) = withContext(IO) {
                val records = allRecords ?: appDb.readRecordDao.all.also { allRecords = it }
                val filtered = records.filter {
                    (key.isEmpty() || it.bookName.contains(key, ignoreCase = true)) &&
                        (day == 0 || it.day == day)
                }
                val items = if (day != 0) {
                    filtered.map { ReadRecordShow(it.bookName, it.readTime, it.day) }
                } else {
                    filtered.groupBy { it.bookName }.map { (name, list) ->
                        ReadRecordShow(
                            bookName = name,
                            readTime = list.sumOf { it.readTime },
                            lastRead = list.maxOf { it.day }
                        )
                    }
                }
                val sortedItems = when (sortMode) {
                    1 -> items.sortedByDescending { it.readTime }
                    2 -> items.sortedByDescending { it.lastRead }
                    else -> items.sortedWith { o1, o2 ->
                        o1.bookName.cnCompare(o2.bookName)
                    }
                }
                val names = sortedItems.map { it.bookName }.distinct().toTypedArray()
                val bookList = if (names.isEmpty()) emptyList()
                else appDb.bookDao.findByName(*names)
                val todayPerBook = records.asSequence()
                    .filter { it.day == todayKey }
                    .associate { it.bookName to it.readTime }
                Triple(sortedItems, bookList.associateBy { it.name }, todayPerBook)
            }
            bookMap = books
            todayTimeByBook = todayMap
            adapter.setItems(sorted)
        }
    }

    private fun invalidateRecords() {
        allRecords = null
    }

    private fun weekRange(now: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val start = ReadRecord.dayKey(cal.timeInMillis)
        cal.add(Calendar.DAY_OF_MONTH, 6)
        val end = ReadRecord.dayKey(cal.timeInMillis)
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

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            if (isSectionStart(view, parent)) {
                outRect.top = sectionHeight.toInt()
            }
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (!isSectionStart(child, parent)) continue
                val pos = parent.getChildAdapterPosition(child)
                val dataIdx = pos - adapter.getHeaderCount()
                val item = adapter.getItem(dataIdx) ?: continue
                val bottom = child.top.toFloat()
                val top = bottom - sectionHeight
                c.drawRect(0f, top, parent.width.toFloat(), bottom, bgPaint)
                val textY = (top + bottom) / 2f -
                    (textPaint.descent() + textPaint.ascent()) / 2f
                c.drawText(formatDayKey(item.lastRead), paddingHorizontal, textY, textPaint)
            }
        }

        private fun isSectionStart(view: View, parent: RecyclerView): Boolean {
            if (sortMode != 2 || filterDay != 0) return false
            val pos = parent.getChildAdapterPosition(view)
            if (pos == RecyclerView.NO_POSITION) return false
            val dataIdx = pos - adapter.getHeaderCount()
            if (dataIdx < 0) return false
            val item = adapter.getItem(dataIdx) ?: return false
            if (dataIdx == 0) return true
            val prev = adapter.getItem(dataIdx - 1) ?: return true
            return prev.lastRead != item.lastRead
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
            tvRead.text = getString(
                R.string.read_record_today_total,
                formatDuring(todayTimeByBook[item.bookName] ?: 0L),
                formatDuring(item.readTime)
            )
            tvLast.text = if (book != null && book.durChapterTime > 0) {
                minuteFormat.format(book.durChapterTime)
            } else {
                formatDayKey(item.lastRead)
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
                    appDb.readRecordDao.deleteByName(item.bookName)
                    invalidateRecords()
                    refreshSummary()
                    refreshHeatmap()
                    initData()
                }
                noButton()
            }
        }

    }

    /**
     * 把毫秒数格式化为「X小时Y分钟」形式，不再按天换算。
     * 小于 1 分钟显示秒。
     */
    fun formatDuring(mss: Long): String {
        if (mss <= 0L) return "0 分钟"
        val totalSeconds = mss / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分钟"
            hours > 0 -> "$hours 小时"
            minutes > 0 -> "$minutes 分钟"
            else -> "$seconds 秒"
        }
    }

    private fun formatDayKey(day: Int): String {
        if (day <= 0) return ""
        val y = day / 10000
        val m = (day / 100) % 100
        val d = day % 100
        return "%d-%02d-%02d".format(y, m, d)
    }

}
