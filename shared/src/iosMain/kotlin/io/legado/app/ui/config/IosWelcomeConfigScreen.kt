package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.image.pickImage
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.launch

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
        // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
        val welcomeDurationText = rememberString("ios_welcome_duration_not_implemented")
        val selectImageText = rememberString("select_image")
        val scope = rememberCoroutineScope()
        val prefs = remember { PreferenceProviders.get() }
        var dayImagePath by remember { mutableStateOf(prefs.getString(PreferKey.welcomeImage)) }
        var nightImagePath by remember { mutableStateOf(prefs.getString(PreferKey.welcomeImageDark)) }
        WelcomeConfigScreen(
            onShowTime = {
                Toasters.get().toast(welcomeDurationText)
            },
            onPickImage = { isNight ->
                // PHPicker 选图, 路径写回 PreferKey (对齐 IosReadStyleDialog.onSelectBgImage)
                scope.launch {
                    val url = pickImage() ?: return@launch
                    val path = url.path ?: return@launch
                    val key = if (isNight) PreferKey.welcomeImageDark else PreferKey.welcomeImage
                    prefs.putString(key, path)
                    if (isNight) nightImagePath = path else dayImagePath = path
                }
            },
            showTimeSummary = "",
            imageSummary = dayImagePath.ifEmpty { selectImageText },
            imageDarkSummary = nightImagePath.ifEmpty { selectImageText },
        )
    }
}
