package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端启动界面设置页入口 (包装 shared/sharedUiMain 的 [WelcomeConfigScreen])。
 *
 * 阻塞点: onPickImage 需 SAF 图片选取器 (iOS 端 stub)。
 *
 * @param onBack 返回回调
 */
@Composable
fun IosWelcomeConfigScreen(
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("welcome_style"),
            onBack = onBack,
        )
        // KP-iOS: SAF 图片选取 stub, 启动时长 NumberPicker stub
        // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
        val welcomeDurationText = rememberString("ios_welcome_duration_not_implemented")
        val welcomeBgImageText = rememberString("ios_welcome_bg_image_not_implemented")
        WelcomeConfigScreen(
            onShowTime = {
                Toasters.get().toast(welcomeDurationText)
            },
            onPickImage = { _ ->
                // TODO: iOS 端图片选取器 (pickImages), KP6+ 接入
                Toasters.get().toast(welcomeBgImageText)
            },
            showTimeSummary = "",
            imageSummary = "",
            imageDarkSummary = "",
        )
    }
}
