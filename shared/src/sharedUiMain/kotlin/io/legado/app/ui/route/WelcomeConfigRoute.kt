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
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.resolveImagePath
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.deleteImageIfUnreferenced
import io.legado.app.ui.compose.component.AppTitleBar
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
    val pref = PreferenceProviders.get()
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
                        welcomeImage = pref.getStringOrNull(PreferKey.welcomeImage),
                        welcomeImageDark = pref.getStringOrNull(PreferKey.welcomeImageDark),
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
                        // 选图导入原图进图集 (备份链路) + 按本端启动界面尺寸烘焙产物写缓存
                        // (使用链路, 平台层实现, 见 FilePickerService.processWelcomeImage)；
                        // 返回**原图相对引用**作 pref 值; 导入失败回落原路径
                        val processed = withContext(IoDispatcher) {
                            val files = PlatformServiceProviders.getOrNull()?.files
                            val ref = files?.processWelcomeImage(
                                path,
                                pref.getStringOrNull(
                                    if (isNight) PreferKey.welcomeImageDark
                                    else PreferKey.welcomeImage
                                ),
                                isNight,
                            )
                            // 已复制进图集目录, 选图物化的临时副本不留在缓存里
                            if (ref != null) files.discardPickedFile(path)
                            ref
                        }
                        // 对照 app 端 putImagePref(key, path)
                        val key =
                            if (isNight) PreferKey.welcomeImageDark else PreferKey.welcomeImage
                        val finalPath = processed ?: path
                        pref.putString(key, finalPath)
                        screenModel.dispatch(WelcomeConfigUiEvent.ImagePicked(isNight, finalPath))
                    }
                }
            },
            // 长按背景图条目直接清除（原版点击已有图弹 selector 选删除/选图，此处交互简化）:
            // 对照原版删除分支 removePref(key) + file.delete(): 先清 pref 刷 UI, 再异步删落盘图;
            // 无图时长按不动作 (与原版 selector 仅在有图时出现一致); delete 结果与原版一样不检查
            onClearImage = { isNight ->
                val key =
                    if (isNight) PreferKey.welcomeImageDark else PreferKey.welcomeImage
                val current =
                    (if (isNight) state.welcomeImageDark else state.welcomeImage)
                        ?.takeIf { it.isNotBlank() }
                if (current != null) {
                    pref.putString(key, null)
                    screenModel.dispatch(WelcomeConfigUiEvent.ImageCleared(isNight))
                    scope.launch {
                        withContext(IoDispatcher) {
                            resolveImagePath(current)?.let { absPath ->
                                // 四键引用保护: 同图可能同时是启动封面/界面背景的日/夜引用,
                                // 其余键仍引用时不删 (本键已清 null, 排除仅为语义自洽)
                                deleteImageIfUnreferenced(
                                    absPath,
                                    withFile = true,
                                    excludeKey = key
                                )
                            }
                        }
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
