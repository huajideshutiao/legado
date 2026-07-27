package io.legado.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.legado.app.constant.PreferKey
import io.legado.app.constant.ThreadSafeDateFormat
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS 端阅读记录 Screen 入口 (包装 shared/sharedUiMain 的 [ReadRecordScreen])。
 *
 * # 职责
 *
 * 对照 app 端 [ReadRecordActivity] 的薄壳模式, iOS 端在 [io.legado.app.ui.IosNavHost]
 * 的 READ_RECORD 路由分支调用本入口。
 *
 * 业务展示逻辑全部下沉到 shared/sharedUiMain, 本文件仅做 iOS 平台适配:
 * - **数据订阅**: `produceState` 一次性拉取 `appDb.readRecordDao.all()` (DAO 无 flow 接口),
 *   按 sortMode 在 IO 重组为 [ReadRecordShow] 列表 + 计算 summary 统计;
 * - **summary 统计**: 今日 / 本周 / 本月 / 总 / 已读书籍数 / 平均每本时长, 全部在 IO 计算;
 * - **热力图 slot**: iOS 端暂用占位 [Box] (MonthHeatMapView 是 Android 专属 View, 未下沉);
 * - **封面 slot**: iOS 端暂用占位 [Box] (ShelfCover 依赖 Android Coil, 未下沉);
 * - **打开书籍**: 通过 [onOpenBook] 回调切到 READER 路由 (异步查 book → 携带 book 跳转);
 * - **删除确认**: 用 [AlertDialog] (替代 app 端 alert 扩展);
 * - **清空全部**: 用 [AlertDialog] 二次确认。
 *
 * # 简化项 (iOS 端 KP5 阶段)
 *
 * - 热力图 / 封面 用占位 Box (Android 专属组件未下沉, KP6+ 接入 CMP 等价组件)
 * - 日期分段逻辑简化: perDayMode 按 (bookName, day) 拆行展示, 跨书同天不合并 (与 app 端一致)
 * - 拖拽排序 留 TODO (ReadRecordDao 无顺序字段, 排序走 sortMode)
 *
 * @param onBack 返回回调 (切回调用方路由, 由 IosNavHost 注入)
 * @param onOpenBook 点击记录行打开书籍回调 (携带 Book), 由 IosNavHost 注入切到 READER 路由
 */
@Composable
fun IosReadRecordScreen(
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val prefStore = LocalPreferenceStoreProvider.current

    // 排序模式 (0 名称 / 1 时长 / 2 时间)
    var sortMode by remember { mutableIntStateOf(2) }
    // 搜索文本
    var searchText by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    // 启用阅读记录开关 (镜像 AppConfig.enableReadRecord)
    var enableReadRecord by remember {
        mutableStateOf(AppConfigProviders.get().enableReadRecord)
    }

    // 删除确认 Dialog 状态 (长按记录行触发)
    var pendingDelete by remember { mutableStateOf<ReadRecordShow?>(null) }
    // 清空全部确认 Dialog 状态 (顶栏溢出菜单触发)
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // 一次性拉取全部阅读记录 (DAO 无 flow 接口, 用 produceState 触发刷新)
    val records by produceState<List<ReadRecord>>(emptyList()) {
        value = withContext(Dispatchers.Default) {
            AppDbProviders.get().readRecordDao.all()
        }
    }

    // 同步拉取书籍列表 (供封面 / 作者 / 跳转阅读使用)
    val books by produceState<List<Book>>(emptyList()) {
        value = withContext(Dispatchers.Default) {
            AppDbProviders.get().bookDao.all()
        }
    }

    // 派生: bookMap (bookName -> Book)
    val bookMap = remember(books) { books.associateBy { it.name } }

    // 当前日期 yyyyMMdd (用 ThreadSafeDateFormat 格式化当前时间, 与 app 端 todayKey 计算等价)
    val dateFormatYmd = remember { ThreadSafeDateFormat("yyyyMMdd") }
    val todayKey = remember {
        dateFormatYmd.format(systemCurrentTimeMillis()).toIntOrNull() ?: 0
    }
    val todayYear = remember { todayKey / 10000 }
    val todayMonth = remember { (todayKey / 100) % 100 }
    val todayDay = remember { todayKey % 100 }

    // 热力图当前年月 (可切换, 不越过当前月份)
    var heatmapYear by remember { mutableIntStateOf(todayYear) }
    var heatmapMonth by remember { mutableIntStateOf(todayMonth) }

    // 预先 remember 字符串资源 (actions lambda 非 @Composable, 不能直接调 rememberString)
    val noBookText = rememberString("no_book")
    val deleteText = rememberString("draw")
    val sureDelText = rememberString("sure_del")
    val deleteAllText = rememberString("delete_all")
    val okText = rememberString("ok")
    val cancelText = rememberString("cancel")

    // 派生: ReadRecordShow 列表 (按 sortMode + searchText 过滤, 同步计算)
    // ReadRecordShow 构造顺序: (bookName, readTime, lastRead, day)
    // ReadRecord 字段: bookName, day, startSec, endSec → readTime = endSec - startSec, lastRead = endSec
    val items = remember(records, sortMode, searchText) {
        val filtered = if (searchText.isBlank()) records else {
            records.filter { it.bookName.contains(searchText, ignoreCase = true) }
        }
        // perDayMode (sortMode=2) 按 (bookName, day) 聚合; 其他模式按 bookName 聚合
        if (sortMode == 2) {
            // 按 (bookName, day) 拆行, 同 bookName+day 的多段记录累加 readTime, 取最大 lastRead
            val perDayMap = HashMap<String, ReadRecordShow>()
            filtered.forEach { r ->
                val rt = r.endSec - r.startSec
                val lastRead = r.endSec
                val key = "${r.bookName}|${r.day}"
                val existing = perDayMap[key]
                if (existing == null) {
                    perDayMap[key] = ReadRecordShow(r.bookName, rt, lastRead, r.day)
                } else {
                    existing.readTime += rt
                    if (lastRead > existing.lastRead) existing.lastRead = lastRead
                }
            }
            perDayMap.values.sortedWith(compareByDescending { it.lastRead })
        } else {
            // 按 bookName 聚合
            val readTimeByBook = HashMap<String, Long>()
            val lastReadByBook = HashMap<String, Long>()
            filtered.forEach { r ->
                val rt = r.endSec - r.startSec
                val lastRead = r.endSec
                readTimeByBook[r.bookName] = (readTimeByBook[r.bookName] ?: 0L) + rt
                val prev = lastReadByBook[r.bookName]
                if (prev == null || lastRead > prev) lastReadByBook[r.bookName] = lastRead
            }
            readTimeByBook.map { (name, total) ->
                ReadRecordShow(name, total, lastReadByBook[name] ?: 0L, 0)
            }.sortedWith(
                when (sortMode) {
                    0 -> compareBy { it.bookName } // 名称
                    1 -> compareByDescending { it.readTime } // 时长
                    else -> compareByDescending { it.lastRead } // 时间 (兜底)
                },
            )
        }
    }

    // 派生: 统计 summary (同步计算)
    val summaryToday = remember(records, todayKey) {
        records.filter { it.day == todayKey }.sumOf { it.endSec - it.startSec }
    }
    val summaryAll = remember(records) {
        records.sumOf { it.endSec - it.startSec }
    }
    val summaryBookCount = remember(records) { records.map { it.bookName }.distinct().size }
    val summaryAvgRead = remember(summaryAll, summaryBookCount) {
        if (summaryBookCount == 0) 0L else summaryAll / summaryBookCount
    }
    val summaryMonth = remember(records, todayYear, todayMonth) {
        records.filter { it.day / 10000 == todayYear && (it.day / 100) % 100 == todayMonth }
            .sumOf { it.endSec - it.startSec }
    }
    // 近 7 天统计 (简化: todayKey-6 ~ todayKey, 跨月时 dayKey 不连续, 估算误差可接受)
    val summaryWeek = remember(records, todayKey) {
        val minKey = todayKey - 6
        records.filter { it.day in minKey..todayKey }.sumOf { it.endSec - it.startSec }
    }

    // 派生: 今日 / 总时长 按书名索引 (供 RecordRow 显示)
    val todayTimeByBook = remember(records, todayKey) {
        records.filter { it.day == todayKey }
            .groupBy { it.bookName }
            .mapValues { (_, list) -> list.sumOf { it.endSec - it.startSec } }
    }
    val totalTimeByBook = remember(records) {
        records.groupBy { it.bookName }
            .mapValues { (_, list) -> list.sumOf { it.endSec - it.startSec } }
    }

    // 派生: 热力图当月数据 (day -> seconds)
    val heatmapData = remember(records, heatmapYear, heatmapMonth) {
        records.filter { it.day / 10000 == heatmapYear && (it.day / 100) % 100 == heatmapMonth }
            .groupBy { it.day % 100 }
            .mapValues { (_, list) -> list.sumOf { it.endSec - it.startSec } }
    }

    val state = remember(
        items, bookMap, todayTimeByBook, totalTimeByBook, heatmapData,
        sortMode, heatmapYear, heatmapMonth, todayYear, todayMonth,
        searchText, searchFocused, enableReadRecord,
        summaryToday, summaryWeek, summaryMonth, summaryAll, summaryBookCount, summaryAvgRead,
    ) {
        ReadRecordUiState(
            sortMode = sortMode,
            heatmapYear = heatmapYear,
            heatmapMonth = heatmapMonth,
            todayYear = todayYear,
            todayMonth = todayMonth,
            filterDay = 0,
            searchText = searchText,
            searchFocused = searchFocused,
            items = items,
            bookMap = bookMap,
            todayTimeByBook = todayTimeByBook,
            totalTimeByBook = totalTimeByBook,
            // sortMode=2 (时间) 时按 (bookName, day) 拆行, 其余模式按 bookName 聚合
            itemsPerDayMode = sortMode == 2,
            heatmapData = heatmapData,
            heatmapSelectedDay = 0,
            summaryToday = summaryToday,
            summaryWeek = summaryWeek,
            summaryMonth = summaryMonth,
            summaryAll = summaryAll,
            summaryBookCount = summaryBookCount,
            summaryAvgRead = summaryAvgRead,
            enableReadRecord = enableReadRecord,
        )
    }

    val actions = remember(scope, onBack, onOpenBook, noBookText) {
        object : ReadRecordUiActions {
            override fun onBack() = onBack()
            override fun onSearchChange(text: String) { searchText = text }
            override fun onSearch(text: String) { searchFocused = false }
            override fun onSearchFocusChanged(focused: Boolean) { searchFocused = focused }
            override fun onSortSelect(mode: Int) { sortMode = mode }
            override fun onToggleEnableRecord() {
                // 切换 AppConfig.enableReadRecord (写 PreferKey.enableReadRecord)
                val newValue = !enableReadRecord
                prefStore.putBoolean(PreferKey.enableReadRecord, newValue)
                enableReadRecord = newValue
            }
            override fun onClearAll() { showClearAllConfirm = true }
            override fun onStepMonth(delta: Int) {
                // 月份切换: 不越过当前月份
                var newY = heatmapYear
                var newM = heatmapMonth + delta
                if (newM > 12) { newM = 1; newY += 1 }
                if (newM < 1) { newM = 12; newY -= 1 }
                // 不越过当前月份
                if (newY > todayYear || (newY == todayYear && newM > todayMonth)) return
                heatmapYear = newY
                heatmapMonth = newM
            }
            override fun openBook(item: ReadRecordShow) {
                // 异步查 book → 跳转阅读 (对照 app 端 ReadRecordActivity.openBook)
                scope.launch {
                    val book = bookMap[item.bookName] ?: withContext(Dispatchers.Default) {
                        AppDbProviders.get().bookDao.findByName(item.bookName).firstOrNull()
                    }
                    if (book == null) {
                        Toasters.get().toast(noBookText)
                        return@launch
                    }
                    onOpenBook(book)
                }
            }
            override fun sureDelAlert(item: ReadRecordShow) { pendingDelete = item }
            override fun clearSearchFocus() { searchFocused = false }
        }
    }

    ReadRecordScreen(
        state = state,
        actions = actions,
        // 热力图 slot: iOS 端暂用占位 Box (MonthHeatMapView 是 Android 专属, 未下沉)
        // TODO: KP6+ 接入 CMP 等价热力图组件
        heatmapSlot = { modifier ->
            Box(
                modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppTheme.colors.bottomBackground)
            )
        },
        // 封面 slot: iOS 端暂用占位 Box (ShelfCover 依赖 Android Coil, 未下沉)
        // TODO: KP6+ 接入 CMP 图片加载后回填
        coverSlot = { _, _, modifier ->
            Box(
                modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppTheme.colors.bottomBackground)
            )
        },
    )

    // 单条删除确认 Dialog (长按记录行触发)
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(deleteText) },
            text = { Text("$sureDelText\n${item.bookName}") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.Default) {
                        AppDbProviders.get().readRecordDao.deleteByName(item.bookName)
                    }
                    pendingDelete = null
                }) { Text(okText) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(cancelText)
                }
            },
        )
    }

    // 清空全部确认 Dialog
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(deleteAllText) },
            text = { Text(sureDelText) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.Default) {
                        AppDbProviders.get().readRecordDao.clear()
                    }
                    showClearAllConfirm = false
                }) { Text(okText) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text(cancelText)
                }
            },
        )
    }
}
