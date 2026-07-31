package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.model.BookCoverShared
import io.legado.app.ui.config.CoverConfigScreen
import io.legado.app.ui.config.CoverConfigScreenModel
import io.legado.app.ui.config.CoverConfigUiEvent
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.FlowBus
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookshelf_cover_height
import legado.shared.generated.resources.bookshelf_cover_height_summary
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.cover_config
import legado.shared.generated.resources.default_cover_count
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.select_image
import org.jetbrains.compose.resources.stringResource

/**
 * 封面配置 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [CoverConfigScreenModel], 渲染 [CoverConfigScreen]。
 *
 * 默认封面画廊/刷新走 [PlatformCapabilityProviders]; 封面高度用 Compose AlertDialog
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
            val count = BookCoverShared.listDefaultCovers(
                PreferenceProviders.get(), PreferKey.defaultCover
            ).size
            screenModel.dispatch(
                CoverConfigUiEvent.UpdateDayCoverSummary(
                    if (count == 0) selectImageStr else coverCountFormat.format(count)
                )
            )
        }
        if (state.nightCoverSummary.isEmpty()) {
            val count = BookCoverShared.listDefaultCovers(
                PreferenceProviders.get(), PreferKey.defaultCoverDark
            ).size
            screenModel.dispatch(
                CoverConfigUiEvent.UpdateNightCoverSummary(
                    if (count == 0) selectImageStr else coverCountFormat.format(count)
                )
            )
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

    // 封面高度选择对话框 (对照 app 端 showNumberPicker: 90..220, 默认 120)
    if (showHeightPicker) {
        val colors = AppTheme.colors
        var heightValue by remember {
            mutableStateOf(pref.getInt(PreferKey.bookshelfCoverHeight, 120).toString())
        }
        AlertDialog(
            onDismissRequest = { showHeightPicker = false },
            title = {
                Text(
                    stringResource(Res.string.bookshelf_cover_height),
                    color = colors.primaryText
                )
            },
            text = {
                OutlinedTextField(
                    value = heightValue,
                    onValueChange = { heightValue = it.filter { c -> c.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    heightValue.toIntOrNull()?.let { height ->
                        val clamped = height.coerceIn(90, 220)
                        pref.putInt(PreferKey.bookshelfCoverHeight, clamped)
                        screenModel.dispatch(
                            CoverConfigUiEvent.UpdateCoverHeightSummary(
                                summaryFormat.replace("%s", "${clamped}dp")
                            )
                        )
                        // 通知书架刷新 (对照 app 端 postEvent(EventBus.BOOKSHELF_REFRESH, ""))
                        FlowBus.with(EventBus.BOOKSHELF_REFRESH).tryEmit("")
                    }
                    showHeightPicker = false
                }) { Text(stringResource(Res.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showHeightPicker = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
            shape = AppTheme.DesignTokens.dialogShape,
            backgroundColor = MaterialTheme.colors.surface,
        )
    }
}
