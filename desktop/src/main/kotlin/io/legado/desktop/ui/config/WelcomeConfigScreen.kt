package io.legado.desktop.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.config.WelcomeConfigScreen as SharedWelcomeConfigScreen
import io.legado.app.ui.dialog.NumberPickerDialog

/**
 * 桌面端"欢迎页设置" Screen 入口 (包装 shared/sharedUiMain 的 [SharedWelcomeConfigScreen])。
 *
 * # 职责
 *
 * - 在 [SharedWelcomeConfigScreen] 之上加 [AppTitleBar] (标题"欢迎页设置" + 返回按钮)
 * - 装配 3 个 summary (showTimeSummary / imageSummary / imageDarkSummary, 从
 *   [PreferenceProviders] 读 prefs 值)
 * - 装配 1 个 NumberPicker 弹窗: welcomeShowTime(0..3000 ms, 默认 3)
 * - 装配 1 个剩余 onClick 回调 (onPickImage: no-op + TODO, 桌面端无 SAF)
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] /
 *   [SharedWelcomeConfigScreen] 通过 LocalXxx 取依赖
 *
 * # 简化项
 *
 * - onShowTime: 范围 0..3000 (app 端原 max=3000 ms), 写 prefs.welcomeShowTime
 *   summary 沿用 desktop 现有 "X 秒" 格式 (与 desktop 其他 summary 风格一致)
 * - onPickImage: app 端用 SAF 选图片, 桌面端无 SAF, 后续可用 java.awt.FileDialog / JFileChooser 替代, 暂 no-op
 * - showTimeSummary / imageSummary / imageDarkSummary: 桌面端从 [PreferenceProviders]
 *   读 PreferKey.welcomeShowTime / welcomeImage / welcomeImageDark 值展示
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun WelcomeConfigScreen(onBack: () -> Unit) {
    // 桌面端 Provider 注入: 供 commonMain 的 AppTheme / SharedWelcomeConfigScreen 取依赖
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AppTitleBar(
                        title = rememberString("welcome_config"),
                        onBack = onBack,
                    )
                    WelcomeConfigContent()
                }
            }
        }
    }
}

/**
 * 装配 3 个 summary + 2 个回调, 位置传参调用 [SharedWelcomeConfigScreen]。
 *
 * 与 app 端 WelcomeConfigActivity.Content 内 SharedWelcomeConfigScreen(...) 调用对齐。
 */
@Composable
private fun WelcomeConfigContent() {
    val prefs = remember { PreferenceProviders.get() }
    // 文案标签 (rememberString 是 @Composable, 顶层缓存; remember 块内非 @Composable, 需预先缓存)
    val secondsUnitLabel = rememberString("seconds_unit")
    val welcomeShowTimeLabel = rememberString("welcome_show_time")
    val notSetLabel = rememberString("not_set")

    // welcomeShowTime 当前值 + 显隐状态 (mutableIntStateOf 让 summary 重组)
    var welcomeShowTime by remember {
        mutableIntStateOf(prefs.getInt(PreferKey.welcomeShowTime, 3))
    }
    var showTimeDialog by remember { mutableStateOf(false) }

    // 启动时长 summary (沿用 desktop 现有 "X 秒" 格式, 0 表示不显示欢迎页)
    val showTimeSummary = welcomeShowTime.toString() + " " + secondsUnitLabel
    // 日间背景图路径 summary (空则展示"未设置")
    val imageSummary = remember {
        val path = prefs.getString(PreferKey.welcomeImage)
        if (path.isBlank()) notSetLabel else path
    }
    // 夜间背景图路径 summary
    val imageDarkSummary = remember {
        val path = prefs.getString(PreferKey.welcomeImageDark)
        if (path.isBlank()) notSetLabel else path
    }

    SharedWelcomeConfigScreen(
        onShowTime = { showTimeDialog = true },
        onPickImage = { _ ->
            // TODO: 桌面端无 SAF, 后续可用 java.awt.FileDialog / JFileChooser 选图片
            // 选定后 prefs.putString(PreferKey.welcomeImage/welcomeImageDark, path)
            // isNight=true 写 welcomeImageDark, isNight=false 写 welcomeImage
        },
        showTimeSummary = showTimeSummary,
        imageSummary = imageSummary,
        imageDarkSummary = imageDarkSummary,
    )

    // 1 个 NumberPickerDialog (shared 共享, 替代 app 端 showNumberPicker)
    // app 端范围: welcomeShowTime 0..3000 ms, 默认 3
    if (showTimeDialog) {
        NumberPickerDialog(
            title = welcomeShowTimeLabel,
            value = welcomeShowTime,
            range = 0..3000,
            onConfirm = {
                welcomeShowTime = it
                prefs.putInt(PreferKey.welcomeShowTime, it)
                showTimeDialog = false
            },
            onDismiss = { showTimeDialog = false },
        )
    }
}
