package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端朗读设置页入口 (包装 shared/sharedUiMain 的 [ReadAloudConfigScreen])。
 *
 * 阻塞点: TTS 引擎选择 (onTtsEngine) 与系统 TTS 配置 (onSysTtsConfig) 均依赖 iOS 平台 TTS 框架,
 * 本入口仅做 stub 让编译通过, 后续 KP6+ 接入 iOS AVSpeechSynthesizer / 第三方 TTS。
 *
 * @param onBack 返回回调
 */
@Composable
fun IosReadAloudConfigScreen(
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("aloud_config"),
            onBack = onBack,
        )
        // KP-iOS: TTS 引擎 stub, iOS 端 AVSpeechSynthesizer 待接入
        // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
        val ttsEngineText = rememberString("ios_tts_engine_not_implemented")
        val sysTtsConfigText = rememberString("ios_sys_tts_config_not_implemented")
        ReadAloudConfigScreen(
            pausePhoneCallsEnabled = false,
            speakEngineSummary = "",
            onTtsEngine = {
                // TODO: iOS 端 TTS 引擎选择 (AVSpeechSynthesizer / 第三方), KP6+ 接入
                Toasters.get().toast(ttsEngineText)
            },
            onSysTtsConfig = {
                // TODO: iOS 端系统 TTS 配置 (跳系统设置), KP6+ 接入
                Toasters.get().toast(sysTtsConfigText)
            },
        )
    }
}
