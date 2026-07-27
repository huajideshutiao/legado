package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.config.ReadAloudConfigScreen
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * 鸿蒙端阅读(朗读)设置页入口 (包装 shared/sharedUiMain 的 [ReadAloudConfigScreen])。
 *
 * 实现模式参考 iOS 端 [io.legado.app.ui.book.read.config.IosReadAloudConfigScreen]:
 * 复用 sharedUiMain 跨平台 Composable, 避免复制代码。
 *
 * 说明: 原 ArkTS ReadConfig.ets 已废弃无法读取原功能; iOS 端无独立 ReadConfigScreen,
 * 朗读设置 (ReadAloudConfigScreen) 为阅读相关设置的核心组成, 故本页面包装之。
 * 顶栏用 sharedUiMain 的 [AppTitleBar] (项目锁 MD2 视觉, 不用 material3 TopAppBar)。
 *
 * 阻塞点: TTS 引擎选择 (onTtsEngine) 与系统 TTS 配置 (onSysTtsConfig) 均依赖鸿蒙平台 TTS 框架,
 * 本入口仅做 stub 让编译通过, 后续接入鸿蒙 TTS 服务。
 *
 * @param onBack 返回回调
 */
@Composable
fun OhosReadConfigScreen(
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("aloud_config"),
            onBack = onBack,
        )
        // 鸿蒙端: TTS 引擎 stub, 鸿蒙端 TTS 服务待接入
        // 鸿蒙端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
        val ttsEngineText = rememberString("ohos_tts_engine_not_implemented")
        val sysTtsConfigText = rememberString("ohos_sys_tts_config_not_implemented")
        ReadAloudConfigScreen(
            pausePhoneCallsEnabled = false,
            speakEngineSummary = "",
            onTtsEngine = {
                // TODO: 鸿蒙端 TTS 引擎选择 (鸿蒙 TTS 服务), 后续接入
                Toasters.get().toast(ttsEngineText)
            },
            onSysTtsConfig = {
                // TODO: 鸿蒙端系统 TTS 配置 (跳系统设置), 后续接入
                Toasters.get().toast(sysTtsConfigText)
            },
        )
    }
}
