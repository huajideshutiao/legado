package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.model.BookCoverShared
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.config.CoverConfigScreen
import io.legado.app.ui.config.CoverConfigScreenModel
import io.legado.app.ui.config.CoverConfigUiEvent
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.FlowBus
import io.legado.app.utils.format
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookshelf_cover_height
import legado.shared.generated.resources.bookshelf_cover_height_summary
import legado.shared.generated.resources.cover_config
import legado.shared.generated.resources.default_cover_count
import legado.shared.generated.resources.select_image
import org.jetbrains.compose.resources.stringResource

/**
 * 封面配置 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [CoverConfigScreenModel], 渲染 [CoverConfigScreen]。
 *
 * 默认封面画廊/刷新走 [PlatformCapabilityProviders]; 封面高度用 [NumberPickerDialog]
 * (对照 app 端 showNumberPicker), 写 prefs 后 dispatch summary 刷新 + post 书架刷新事件。
 */
@Composable
fun CoverConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) { CoverConfigScreenModel() }
    val state by screenModel.state.collectAsState()

    val pref = LocalPreferenceStoreProvider.current
    val appConfig = remember { AppConfigProviders.get() }
    var showHeightPicker by remember { mutableStateOf(false) }
    // 预解析 summary 模板 (对照 app 端 R.string.bookshelf_cover_height_summary = "Current: %s")
    val summaryFormat = stringResource(Res.string.bookshelf_cover_height_summary)
    val selectImageStr = stringResource(Res.string.select_image)
    val coverCountFormat = stringResource(Res.string.default_cover_count)
    // 顶栏标题 (对照 app 端 R.string.cover_config)
    val titleStr = stringResource(Res.string.cover_config)

    // 对照 app 端 CoverConfigFragment.upPreferenceSummary: 重算日间默认封面数量 summary
    fun updateDayCoverSummary() {
        val count = BookCoverShared.listDefaultCovers(
            PreferenceProviders.get(), PreferKey.defaultCover
        ).size
        screenModel.dispatch(
            CoverConfigUiEvent.UpdateDayCoverSummary(
                if (count == 0) selectImageStr else coverCountFormat.format(count)
            )
        )
    }

    // 对照 app 端 CoverConfigFragment.upPreferenceSummary: 重算夜间默认封面数量 summary
    fun updateNightCoverSummary() {
        val count = BookCoverShared.listDefaultCovers(
            PreferenceProviders.get(), PreferKey.defaultCoverDark
        ).size
        screenModel.dispatch(
            CoverConfigUiEvent.UpdateNightCoverSummary(
                if (count == 0) selectImageStr else coverCountFormat.format(count)
            )
        )
    }

    // 图集增删后刷新 summary (对照 app 端 onSharedPreferenceChanged 监听 defaultCover/defaultCoverDark)
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.DEFAULT_COVER_CHANGED).collect {
            updateDayCoverSummary()
            updateNightCoverSummary()
        }
    }

    // 对照 app 端 init: 初始化 3 个动态 summary
    LaunchedEffect(Unit) {
        if (state.coverHeightSummary.isEmpty()) {
            screenModel.dispatch(
                CoverConfigUiEvent.UpdateCoverHeightSummary(
                    summaryFormat.replace("%s", "${appConfig.bookshelfCoverHeight}dp")
                )
            )
        }
        if (state.dayCoverSummary.isEmpty()) {
            updateDayCoverSummary()
        }
        if (state.nightCoverSummary.isEmpty()) {
            updateNightCoverSummary()
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleStr,
            onBack = { navigator.pop() },
        )
        CoverConfigScreen(
            onDefaultCover = { isNight ->
                PlatformCapabilityProviders.getOrNull()?.showDefaultCoverGallery(isNight)
            },
            onCoverHeight = { showHeightPicker = true },
            coverHeightSummary = state.coverHeightSummary,
            dayCoverSummary = state.dayCoverSummary,
            nightCoverSummary = state.nightCoverSummary,
            onRefreshCover = {
                PlatformCapabilityProviders.getOrNull()?.refreshDefaultCover()
            },
        )
    }

    // 封面高度选择对话框 (对照 app 端 showNumberPicker: 90..220, 默认 120;
    // 数值选择器 = 滑条+步进+点按数字键盘输入, 替代原自由文本输入)
    if (showHeightPicker) {
        NumberPickerDialog(
            title = stringResource(Res.string.bookshelf_cover_height),
            value = pref.getInt(PreferKey.bookshelfCoverHeight, 120),
            range = 90..220,
            onConfirm = { height ->
                val clamped = height.coerceIn(90, 220)
                pref.putInt(PreferKey.bookshelfCoverHeight, clamped)
                screenModel.dispatch(
                    CoverConfigUiEvent.UpdateCoverHeightSummary(
                        summaryFormat.replace("%s", "${clamped}dp")
                    )
                )
                // 通知书架刷新 (对照 app 端 postEvent(EventBus.BOOKSHELF_REFRESH, ""))
                FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
            },
            onDismiss = { showHeightPicker = false },
        )
    }
}
