package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.about.ReadRecordScreen
import io.legado.app.ui.about.ReadRecordScreenModel
import io.legado.app.ui.about.ReadRecordUiActions
import io.legado.app.ui.about.ReadRecordUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import kotlinx.coroutines.launch

/**
 * AppRoute.ReadRecord 路由下沉入口: 桥接 [ReadRecordScreenModel] 状态与 [ReadRecordScreen] 渲染。
 *
 * 排序模式经 [PreferenceProviders] 持久化 (key "readRecordSort"); 搜索/排序/月份切换等
 * 事件 dispatch 给 ScreenModel; openBook 经 bookMap → appDb 回退查书后按书籍类型分流阅读路由
 * (对照 startActivityForBook: Audio/Video/Manga/Rss/Reader), 未命中跳 Search。
 * 清空/单条删除确认弹窗用 [AppAlertDialog] 声明式实现, 确认后 dispatch 对应事件;
 * 平台专属 slot (heatmapSlot MonthHeatMapView / coverSlot ShelfCover) 待平台注入, 暂空实现占位。
 */
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

        // 对照 Activity.onSearch: cancel debounce + clearSearchFocus + initData;
        // ClearSearchFocus 同步置 searchFocused=false + 递增 clearFocusToken 触发 Screen 内 focusManager.clearFocus();
        // SearchSubmitted 内部 cancel debounce + initData
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

        // 弹出清空确认弹窗, 确认后 dispatch ClearAll (对照 app 端 onClearAll alert + clear)
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
                    navigator.push(AppRoute.Search())
                }
            }
        }

        // 弹出单条删除确认弹窗, 确认后 dispatch DeleteByName (对照 app 端 sureDelAlert)
        override fun sureDelAlert(item: ReadRecordShow) {
            pendingDeleteItem.value = item
        }

        override fun clearSearchFocus() {
            screenModel.dispatch(ReadRecordUiEvent.ClearSearchFocus)
        }
    }

    ReadRecordScreen(
        state = state,
        actions = actions,
        // 平台专属 slot (MonthHeatMapView) 待平台注入下沉, 暂空实现占位 (与 BookInfoRoute coverSlot 一致)
        heatmapSlot = { _ -> },
        // 平台专属 slot (ShelfCover) 待平台注入下沉, 暂空实现占位
        coverSlot = { _, _, _ -> },
    )

    // 清空全部阅读记录确认弹窗 (对照 app 端 onClearAll alert)
    if (pendingClearAll.value) {
        AppAlertDialog(
            onDismissRequest = { pendingClearAll.value = false },
            title = rememberString("delete"),
            message = rememberString("sure_del"),
            okButton = AlertButton(rememberString("ok")) {
                screenModel.dispatch(ReadRecordUiEvent.ClearAll)
            },
            cancelButton = AlertButton(rememberString("cancel")) {},
        )
    }
    // 单条删除确认弹窗 (对照 app 端 sureDelAlert alert + deleteByName)
    pendingDeleteItem.value?.let { item ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteItem.value = null },
            title = rememberString("delete"),
            message = rememberString("sure_del_any", item.bookName),
            okButton = AlertButton(rememberString("ok")) {
                screenModel.dispatch(ReadRecordUiEvent.DeleteByName(item.bookName))
            },
            cancelButton = AlertButton(rememberString("cancel")) {},
        )
    }
}
