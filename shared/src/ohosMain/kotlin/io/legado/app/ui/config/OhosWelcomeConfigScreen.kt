package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.dialog.NumberPickerDialog

/**
 * 鸿蒙端启动界面设置页入口 (包装 shared/sharedUiMain 的 [WelcomeConfigScreen])。
 *
 * 阻塞点: onPickImage 需 SAF 图片选取器 (鸿蒙端 stub)。
 *
 * @param onBack 返回回调
 */
@Composable
fun OhosWelcomeConfigScreen(
    onBack: () -> Unit,
) {
    // 鸿蒙端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val welcomeBgImageText = rememberString("ohos_welcome_bg_image_not_implemented")
    val welcomeShowTimeLabel = rememberString("welcome_show_time")
    val msSuffix = "ms"

    // welcomeShowTime 当前值 + 显隐状态 (mutableIntStateOf 让 summary 重组)
    val prefs = remember { PreferenceProviders.get() }
    var welcomeShowTime by remember {
        mutableIntStateOf(prefs.getInt(PreferKey.welcomeShowTime, 3000))
    }
    var showTimeDialog by remember { mutableStateOf(false) }
    val showTimeSummary = welcomeShowTime.toString() + " " + msSuffix

    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("welcome_style"),
            onBack = onBack,
        )
        // KP-ohos: SAF 图片选取 stub, 启动时长 NumberPicker 已接入
        WelcomeConfigScreen(
            onShowTime = { showTimeDialog = true },
            onPickImage = { _ ->
                // TODO: 鸿蒙端图片选取器 (文档管理器), 后续接入
                Toasters.get().toast(welcomeBgImageText)
            },
            showTimeSummary = showTimeSummary,
            imageSummary = "",
            imageDarkSummary = "",
        )
    }

    // welcomeShowTime NumberPicker (范围 0..10000 ms, 默认 3000)
    if (showTimeDialog) {
        NumberPickerDialog(
            title = welcomeShowTimeLabel,
            value = welcomeShowTime,
            range = 0..10000,
            onConfirm = {
                welcomeShowTime = it
                prefs.putInt(PreferKey.welcomeShowTime, it)
                showTimeDialog = false
            },
            onDismiss = { showTimeDialog = false },
        )
    }
}
