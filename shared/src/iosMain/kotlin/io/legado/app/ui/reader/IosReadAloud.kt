package io.legado.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.legado.app.service.DefaultReadAloudNavigator
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.compose.platform.IosPreferenceStoreProvider

/**
 * iOS 端 TTS 朗读控制器 Compose 工厂 (KP5: 接入阅读菜单朗读功能)。
 *
 * # 职责
 *
 * 对照桌面端 `desktop/.../tts/TtsControlPanel.kt` 的 `rememberReadAloudController`,
 * 创建并持有 [ReadAloudControllerShared] 实例, 与传入的 [viewModel] 生命周期绑定:
 * - Navigator: 复用 commonMain 下沉的 [DefaultReadAloudNavigator] (与桌面端共用同一实现)
 * - 引擎: 默认走 `TtsEngineProvider`, iOS 端在 [io.legado.app.help.config.registerIosProviders]
 *   中已注册 `IosSystemTtsEngine` (AVSpeechSynthesizer)
 * - 持久化: 用 [IosPreferenceStoreProvider] (NSUserDefaults) 持久化朗读配置
 *   (speechRate + lastChapterIndex), 进程重启后可恢复
 *
 * # 与桌面端的差异
 *
 * - 桌面端用 `DesktopPreferenceStoreProvider` (内存 Map, 进程结束即丢失)
 * - iOS 端用 [IosPreferenceStoreProvider] (NSUserDefaults, 持久化到磁盘)
 * - key 用 `ios_tts_` 前缀 (与桌面端 `desktop_tts_` 前缀对齐, 互不干扰)
 *
 * # 简化项
 *
 * - iOS 端不接入浮动 TtsControlPanel (desktop 专属 Composable, 下沉范围大, 留后续 TODO)
 * - 朗读控制通过 shared/sharedUiMain 的 [io.legado.app.ui.book.read.config.ReadAloudDialog]
 *   (底栏朗读按钮长按触发) 提供
 *
 * @param viewModel IosReaderScreen 的 ViewModel
 * @return ReadAloudControllerShared 实例
 */
@Composable
fun rememberIosReadAloudController(viewModel: ReadBookViewModelShared): ReadAloudControllerShared {
    // 持久化朗读配置 (speechRate + chapterIndex), 用独立的 IosPreferenceStoreProvider 实例
    // (与桌面端 rememberReadAloudController 用独立 DesktopPreferenceStoreProvider 模式一致,
    //  隔离朗读配置 key, 不与全局 LocalPreferenceStoreProvider 混在一起)
    val prefs = remember { IosPreferenceStoreProvider() }

    val controller = remember(viewModel) {
        ReadAloudControllerShared(
            navigator = DefaultReadAloudNavigator(viewModel),
            // engineProvider 默认走 TtsEngineProvider, IosProviderRegistry 已注册 IosSystemTtsEngine
        )
    }

    // 启动时读取持久化配置 (speechRate + chapterIndex) 恢复到 controller
    LaunchedEffect(controller) {
        // 读取语速 (Float 存为 String 避免精度损失)
        prefs.getString(KEY_IOS_TTS_SPEECH_RATE, null)?.toFloatOrNull()?.let { rate ->
            controller.setSpeechRate(rate)
        }
        // 读取上次朗读章节索引 (仅设 _chapterIndex 用于 UI 显示, 不触发切章)
        val chapterIdx = prefs.getInt(KEY_IOS_TTS_LAST_CHAPTER_INDEX, -1)
        controller.restoreChapterIndex(chapterIdx)
    }

    // 退出 IosReaderScreen 时持久化朗读配置 + 停止朗读 (清理 AVSpeechSynthesizer 资源)
    DisposableEffect(controller) {
        onDispose {
            // 保存语速 ("%.2f".format 避免精度损失, 如 1.5 -> "1.50")
            prefs.putString(KEY_IOS_TTS_SPEECH_RATE, "%.2f".format(controller.speechRate.value))
            // 保存上次朗读章节索引 (controller.chapterIndex.value, -1 = 未朗读)
            prefs.putInt(KEY_IOS_TTS_LAST_CHAPTER_INDEX, controller.chapterIndex.value)
            // 停止朗读 (清理 AVSpeechSynthesizer 播放队列)
            controller.stop()
        }
    }
    return controller
}

/**
 * iOS 端 TTS 朗读配置持久化 key。
 *
 * iOS 端独立 key, 不复用 [io.legado.app.constant.PreferKey.ttsSpeechRate]
 * (app 端 ttsSpeechRate 是 Int 0-45 范围, iOS 端用 Float 0.5-2.0, 类型与语义均不同;
 *  与桌面端 `desktop_tts_*` key 对齐, 互不干扰)。
 */
private const val KEY_IOS_TTS_SPEECH_RATE = "ios_tts_speech_rate"
private const val KEY_IOS_TTS_LAST_CHAPTER_INDEX = "ios_tts_last_chapter_index"
