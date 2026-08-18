package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigRanges
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.config.WelcomeConfigScreen
import io.legado.app.ui.config.WelcomeConfigScreenModel
import io.legado.app.ui.config.WelcomeConfigUiEvent
import io.legado.app.ui.config.WelcomeConfigUiState
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.select_image
import legado.shared.generated.resources.welcome_show_time
import legado.shared.generated.resources.welcome_style
import org.jetbrains.compose.resources.stringResource

/**
 * 启动闪屏配置 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [WelcomeConfigScreenModel], 渲染 [WelcomeConfigScreen]。
 */
@Composable
fun WelcomeConfigRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val pref = LocalPreferenceStoreProvider.current
    val scope = rememberCoroutineScope()
    val selectImageStr = stringResource(Res.string.select_image)
    // 顶栏标题 (对照 app 端 R.string.welcome_style)
    val titleStr = stringResource(Res.string.welcome_style)

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        // 对照 app 端 init: 从 prefs 读取初始状态
        WelcomeConfigScreenModel().also { model ->
            model.dispatch(
                WelcomeConfigUiEvent.Update(
                    WelcomeConfigUiState(
                        welcomeShowTime = pref.getInt(PreferKey.welcomeShowTime, 600),
                        welcomeImage = pref.getString(PreferKey.welcomeImage),
                        welcomeImageDark = pref.getString(PreferKey.welcomeImageDark),
                    )
                )
            )
        }
    }
    val state by screenModel.state.collectAsState()

    var showTimePicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = titleStr,
            onBack = { navigator.pop() },
        )
        WelcomeConfigScreen(
            onShowTime = { showTimePicker = true },
            onPickImage = { isNight ->
                // 平台文件选择器选图, 实际裁剪/落盘由平台层接管
                // 选择器是阻塞式的 (Android 端 runBlocking 等 SAF 回调), 必须切 IO
                scope.launch {
                    val path = withContext(IoDispatcher) {
                        PlatformServiceProviders.getOrNull()?.files?.pickFile(FileFilter.Images)
                    }
                    if (path != null) {
                        // 对照 app 端 putImagePref(key, path)
                        val key =
                            if (isNight) PreferKey.welcomeImageDark else PreferKey.welcomeImage
                        pref.putString(key, path)
                        screenModel.dispatch(WelcomeConfigUiEvent.ImagePicked(isNight, path))
                    }
                }
            },
            // 对照 app 端 showTimeSummary = "${AppConfig.welcomeShowTime}ms"
            showTimeSummary = "${state.welcomeShowTime}ms",
            // 对照 app 端 imagePathSummary: 空时显示 "选择图片"
            imageSummary = state.welcomeImage?.ifBlank { null } ?: selectImageStr,
            imageDarkSummary = state.welcomeImageDark?.ifBlank { null } ?: selectImageStr,
        )
    }

    // 启动时长选择对话框 (对照 app 端 showNumberPicker): 数值选择器 600..3000,
    // 范围由 NumberPickerDialog 内部钳制, 不再手动 coerceIn
    if (showTimePicker) {
        NumberPickerDialog(
            title = stringResource(Res.string.welcome_show_time),
            value = state.welcomeShowTime,
            range = AppConfigRanges.welcomeShowTime,
            onConfirm = { value ->
                // 对照 app 端 AppConfig.welcomeShowTime = it
                pref.putInt(PreferKey.welcomeShowTime, value)
                screenModel.dispatch(WelcomeConfigUiEvent.ShowTimeChange(value))
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}
