package io.legado.app.ui.route

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.about.ReadRecordScreen
import io.legado.app.ui.about.ReadRecordScreenModel
import io.legado.app.ui.about.ReadRecordUiActions
import io.legado.app.ui.about.ReadRecordUiEvent
import io.legado.app.ui.about.SharedMonthHeatMap
import io.legado.app.ui.bookshelf.CoverNameAuthorOverlay
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.toReadRoute
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.yearMonthDayFromMillis
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.image_cover_default
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.sure_del_any
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * AppRoute.ReadRecord 路由下沉入口: 桥接 [ReadRecordScreenModel] 状态与 [ReadRecordScreen] 渲染。
 *
 * 排序模式经 [PreferenceProviders] 持久化 (key "readRecordSort"); 搜索/排序/月份切换等
 * 事件 dispatch 给 ScreenModel; openBook 经 bookMap → appDb 回退查书后按书籍类型分流阅读路由
 * (对照 startActivityForBook: Audio/Video/Manga/Rss/Reader), 未命中跳 Search。
 * 清空/单条删除/热力图按日删除确认弹窗用 [AppAlertDialog] 声明式实现。
 *
 * 热力图 slot 由 shared 纯 Compose [SharedMonthHeatMap] 渲染 (替代 app 端 AndroidView);
 * 封面 slot 取 [LocalBookCoverSlot] (app 端注入 ShelfCover, 其他端兜底 SharedBookCover)。
 */

/**
 * 记录条目无 [Book] 时的默认封面: 内置 image_cover_default + 竖排书名/作者。
 *
 * 对照原版 ReadRecordActivity 的 `ivCover.load(book?.getDisplayCover(), ...)`:
 * 封面为空/书不在书架时 CoverImageView 走默认封面链 (内置图 + 书名), 条目封面从不隐藏。
 * 与 BookshelfScreen 默认封面分支重复, 待合并。
 */
@Composable
private fun ReadRecordDefaultCover(name: String, author: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(DesignTokens.shapeSm)
            .background(AppTheme.colors.background),
    ) {
        Image(
            painter = painterResource(Res.drawable.image_cover_default),
            contentDescription = name,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
        )
        CoverNameAuthorOverlay(
            name = name,
            author = author,
            accent = AppTheme.colors.accent,
            modifier = Modifier.matchParentSize(),
        )
    }
}
@Composable
fun ReadRecordRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        ReadRecordScreenModel(
            initialSortMode = PreferenceProviders.get().getInt("readRecordSort", 2),
            persistSortMode = { mode ->
                PreferenceProviders.get().putInt("readRecordSort", mode)
            },
        )
    }
    val state by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 删除/清空确认弹窗状态 (对照 app 端 alert R.string.delete / R.string.sure_del)
    val pendingClearAll = remember { mutableStateOf(false) }
    val pendingDeleteItem = remember { mutableStateOf<ReadRecordShow?>(null) }
    val pendingDeleteDay = remember { mutableStateOf<Int?>(null) }

    // 今日日期 (供热力图 today 高亮; 年/月复用 state 中的 todayYear/todayMonth)
    val todayDay = remember {
        yearMonthDayFromMillis(systemCurrentTimeMillis()).third
    }

    val actions = object : ReadRecordUiActions {
        // 对照 Activity.finish(): 搜索框聚焦时先收焦再返回, 否则直接 pop
        override fun onBack() {
            if (state.searchFocused) {
                screenModel.dispatch(ReadRecordUiEvent.ClearSearchFocus)
                return
            }
            navigator.pop()
        }

        override fun onSearchChange(text: String) {
            screenModel.dispatch(ReadRecordUiEvent.SearchChanged(text))
        }

        // 对照 Activity.onSearch: cancel debounce + clearSearchFocus + initData
        override fun onSearch(text: String) {
            screenModel.dispatch(ReadRecordUiEvent.ClearSearchFocus)
            screenModel.dispatch(ReadRecordUiEvent.SearchSubmitted(text))
        }

        override fun onSearchFocusChanged(focused: Boolean) {
            screenModel.dispatch(ReadRecordUiEvent.SetSearchFocused(focused))
        }

        override fun onSortSelect(mode: Int) {
            screenModel.dispatch(ReadRecordUiEvent.ChangeSortMode(mode))
        }

        override fun onToggleEnableRecord() {
            screenModel.dispatch(ReadRecordUiEvent.ToggleEnableRecord)
        }

        // 弹出清空确认弹窗, 确认后 dispatch ClearAll
        override fun onClearAll() {
            pendingClearAll.value = true
        }

        override fun onStepMonth(delta: Int) {
            screenModel.dispatch(ReadRecordUiEvent.StepMonth(delta))
        }

        // 对照 app 端 openBook + startActivityForBook: bookMap 命中按书籍类型分流阅读路由;
        // 否则回退 appDb 查询; 仍未命中跳搜索页
        override fun openBook(item: ReadRecordShow) {
            val book = state.bookMap[item.bookName]
            if (book != null) {
                navigator.push(book.toReadRoute())
                return
            }
            scope.launch {
                val found = AppDbProviders.get().bookDao.findByName(item.bookName).firstOrNull()
                if (found != null) {
                    navigator.push(found.toReadRoute())
                } else {
                    // 未命中跳搜索页并自动搜索书名 (对照原版跳 Search 带 searchKey;
                    // submit=true 默认自动提交, 之前漏传 key 导致搜索页空白不搜索)
                    navigator.push(AppRoute.Search(key = item.bookName))
                }
            }
        }

        // 弹出单条删除确认弹窗, 确认后 dispatch DeleteByName
        override fun sureDelAlert(item: ReadRecordShow) {
            pendingDeleteItem.value = item
        }

        override fun clearSearchFocus() {
            screenModel.dispatch(ReadRecordUiEvent.ClearSearchFocus)
        }
    }

    // 封面 slot: 取 LocalBookCoverSlot (app 端注入 ShelfCover, 其他端兜底 SharedBookCover)
    val bookCoverSlot = LocalBookCoverSlot.current

    ReadRecordScreen(
        state = state,
        actions = actions,
        // 热力图: 纯 Compose 渲染, 点击切选/长按删日
        heatmapSlot = { modifier ->
            SharedMonthHeatMap(
                year = state.heatmapYear,
                month = state.heatmapMonth,
                data = state.heatmapData,
                selectedDay = state.heatmapSelectedDay,
                todayYear = state.todayYear,
                todayMonth = state.todayMonth,
                todayDay = todayDay,
                onDayClick = { day, _, _ ->
                    // 切换选中: 已选中再点同日则取消 (dayKey=0)
                    val dayKey = if (state.heatmapSelectedDay == day) 0
                    else state.heatmapYear * 10000 + state.heatmapMonth * 100 + day
                    screenModel.dispatch(ReadRecordUiEvent.SelectHeatmapDay(dayKey))
                },
                onDayLongClick = { day, _ ->
                    val dayKey = state.heatmapYear * 10000 + state.heatmapMonth * 100 + day
                    pendingDeleteDay.value = dayKey
                },
                modifier = modifier,
            )
        },
        // 封面: book 非空调 bookCoverSlot (平台注入); book 为空 (书已不在书架/从未入库)
        // 显示默认封面 + 书名, 不隐藏 (对照原版 RecordAdapter: ivCover.load(null 封面)
        // 走 ShelfCover 默认封面链, 空封面/加载失败同样落默认封面)
        coverSlot = { item, book, modifier ->
            if (book != null) {
                bookCoverSlot(book, modifier, false, 0)
            } else {
                // ReadRecordShow 无作者字段, 默认封面不画作者
                ReadRecordDefaultCover(item.bookName, null, modifier)
            }
        },
    )

    // 清空全部阅读记录确认弹窗
    if (pendingClearAll.value) {
        AppAlertDialog(
            onDismissRequest = { pendingClearAll.value = false },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.sure_del),
            okButton = AlertButton(stringResource(Res.string.ok)) {
                screenModel.dispatch(ReadRecordUiEvent.ClearAll)
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
    // 单条删除确认弹窗
    pendingDeleteItem.value?.let { item ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteItem.value = null },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.sure_del_any, item.bookName),
            okButton = AlertButton(stringResource(Res.string.ok)) {
                screenModel.dispatch(ReadRecordUiEvent.DeleteByName(item.bookName))
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
    // 热力图长按按日删除确认弹窗 (对照 app 端 sureDelAlert + deleteByDay)
    pendingDeleteDay.value?.let { dayKey ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteDay.value = null },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.sure_del),
            okButton = AlertButton(stringResource(Res.string.ok)) {
                screenModel.dispatch(ReadRecordUiEvent.DeleteDay(dayKey))
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
}
