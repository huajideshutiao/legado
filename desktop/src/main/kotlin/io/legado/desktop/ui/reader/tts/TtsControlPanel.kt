package io.legado.desktop.ui.reader.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.tts.ReadAloudQueue
import io.legado.app.service.DefaultReadAloudNavigator
import io.legado.app.service.ReadAloudChapterNavigator
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * 桌面端 TTS 控制 UI 入口 (KP2-D P0-10)。
 *
 * # 职责
 *
 * 本文件提供桌面端 ReaderScreen 接入 TTS 朗读所需的全部胶水代码:
 * 1. [DesktopReadAloudNavigator]: 桥接 [ReadBookViewModelShared] 到
 *    [ReadAloudChapterNavigator] (供 commonMain 的 [ReadAloudControllerShared] 使用)
 * 2. [rememberReadAloudController]: Compose remember 工厂, 创建并持有
 *    [ReadAloudControllerShared] 实例, 与 ViewModel 生命周期绑定
 * 3. [TtsControlPanel]: 朗读控制按钮组 Composable
 *
 * # 与 ReaderScreen.kt 的集成方式
 *
 * **本任务不修改 ReaderScreen.kt 的 topBar / 翻页回调区域** (避免与翻页子代理冲突),
 * 仅在独立文件提供 Composable + remember 工厂。主代理后续按以下方式整合到 ReaderScreen:
 *
 * ```kotlin
 * // 在 ReaderScreen 顶部 (Box 内) 添加:
 * val readAloudController = rememberReadAloudController(viewModel)
 *
 * // 在 ReaderScreen 的 Box 内 (与 ReaderTopBar/ReaderBottomBar 同级) 添加:
 * TtsControlPanel(
 *     controller = readAloudController,
 *     modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
 * )
 * ```
 *
 * # 引擎注册
 *
 * TTS 引擎的注册在 [io.legado.desktop.Main] 完成 (调用
 * `TtsEngineProvider.register(DesktopSystemTtsEngine())`), 与本文件无耦合 ——
 * 本文件仅消费 [TtsEngineProvider.get()]。
 *
 * # 简化项
 *
 * - 桌面端不接入睡眠定时 / 媒体通知 / 音频焦点 (这些是 app 端 Service 职责)
 * - 朗读速度滑杆已实现 (KP2-D P1, Material3 Slider 0.5-2.0x, 持久化到 PreferenceStore)
 * - 朗读进度仅显示当前段文本, 不显示段内字符进度 (桌面命令行 TTS 不提供)
 */

// region 1. Navigator 实现

/**
 * [ReadAloudChapterNavigator] 的桌面端别名。
 *
 * 原桌面端 actual 实现已下沉到 commonMain 的 [DefaultReadAloudNavigator]
 * (实现完全使用 commonMain API: ReadBookViewModelShared + BookStorageProviders +
 * ReadAloudQueue, 无 desktop 专属依赖), 桌面端保留 typealias 维持 import 兼容。
 *
 * 详见 [DefaultReadAloudNavigator] 文档。
 */
typealias DesktopReadAloudNavigator = DefaultReadAloudNavigator

// endregion

// region 2. Controller 工厂

/**
 * 创建并持有 [ReadAloudControllerShared] 实例, 与传入的 [viewModel] 生命周期绑定。
 *
 * # 内部实现
 *
 * 1. 创建 [DesktopReadAloudNavigator] (桥接 viewModel)
 * 2. 创建 [ReadAloudControllerShared] (注入 navigator, 引擎走默认 [io.legado.app.help.tts.TtsEngineProvider])
 * 3. KP2-D P1: 创建独立的 [DesktopPreferenceStoreProvider] 实例用于朗读配置持久化
 *    (speechRate + lastChapterIndex), 与 ReaderScreen 的 prefs 解耦
 * 4. LaunchedEffect 启动时: 从 PreferenceStore 读取 speechRate / chapterIndex 恢复到 controller
 * 5. DisposableEffect.onDispose: 保存 speechRate / chapterIndex 到 PreferenceStore,
 *    并调 controller.stop() 清理朗读进程 (避免 ReaderScreen 退出后 SAPI 进程残留)
 *
 * # 持久化说明
 *
 * - [DesktopPreferenceStoreProvider] 是内存 Map 实现, 进程结束即丢失
 *   (与 ReaderScreen 内的 prefs 行为一致, 后续接入文件持久化时替换 Provider 即可)
 * - key 用 `desktop_tts_*` 前缀, 与 app 端 PreferKey.ttsSpeechRate (Int 0-45) 解耦
 * - speechRate 存为 String ("%.2f".format), 避免 Int 转换精度损失
 * - chapterIndex 仅恢复用于 UI 显示 ("上次朗读 X 章"), 不触发 viewModel.loadChapter
 *
 * # 多次重组行为
 *
 * 用 [remember] + viewModel 作 key, 保证 viewModel 不变时 controller 实例稳定 (不重建)。
 *
 * @param viewModel ReaderScreen 的 ViewModel
 * @return ReadAloudControllerShared 实例
 */
@Composable
fun rememberReadAloudController(viewModel: ReadBookViewModelShared): ReadAloudControllerShared {
    // KP2-D P1: 持久化朗读配置 (speechRate + chapterIndex)
    // 用独立的 DesktopPreferenceStoreProvider 实例, 不依赖 LocalPreferenceStoreProvider
    // (ReaderScreen 未通过 LocalPreferenceStoreProvider 注入, 自行创建避免改动 ReaderScreen)
    val prefs = remember { DesktopPreferenceStoreProvider() }

    val controller = remember(viewModel) {
        ReadAloudControllerShared(
            navigator = DesktopReadAloudNavigator(viewModel),
            // engineProvider 默认走 TtsEngineProvider, Main.kt 已注册 DesktopSystemTtsEngine
            // KP2-D P0-9: ttsEngineConfigProvider 注入 Book.ttsEngine 优先, 否则 AppConfig.ttsEngine
            ttsEngineConfigProvider = {
                // Book.ttsEngine 优先, 否则 AppConfig.ttsEngine
                viewModel.book.value?.config?.ttsEngine?.takeIf { it.isNotBlank() }
                    ?: runCatching { AppConfigProviders.get().ttsEngine }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
            },
        )
    }

    // KP2-D P1: 启动时读取持久化配置 (speechRate + chapterIndex) 恢复到 controller
    // 用 controller 作 key, 保证 controller 重建时重新读取
    LaunchedEffect(controller) {
        // 读取语速 (Float 存为 String 避免精度损失)
        prefs.getString(KEY_TTS_SPEECH_RATE, null)?.toFloatOrNull()?.let { rate ->
            controller.setSpeechRate(rate)
        }
        // 读取上次朗读章节索引 (仅设 _chapterIndex 用于 UI 显示, 不触发切章)
        val chapterIdx = prefs.getInt(KEY_TTS_LAST_CHAPTER_INDEX, -1)
        controller.restoreChapterIndex(chapterIdx)
    }

    DisposableEffect(controller) {
        onDispose {
            // KP2-D P1: 退出 ReaderScreen 时持久化朗读配置
            // 保存语速 ("%.2f".format 避免精度损失, 如 1.5 -> "1.50")
            prefs.putString(KEY_TTS_SPEECH_RATE, "%.2f".format(controller.speechRate.value))
            // 保存上次朗读章节索引 (controller.chapterIndex.value, -1 = 未朗读)
            prefs.putInt(KEY_TTS_LAST_CHAPTER_INDEX, controller.chapterIndex.value)
            // 停止朗读 (清理后台 SAPI/espeak/say 进程)
            controller.stop()
        }
    }
    return controller
}

/**
 * TTS 朗读配置持久化 key (KP2-D P1)。
 *
 * 桌面端独立 key, 不复用 [io.legado.app.constant.PreferKey.ttsSpeechRate]
 * (app 端 ttsSpeechRate 是 Int 0-45 范围, 桌面端用 Float 0.5-2.0, 类型与语义均不同)。
 */
private const val KEY_TTS_SPEECH_RATE = "desktop_tts_speech_rate"
private const val KEY_TTS_LAST_CHAPTER_INDEX = "desktop_tts_last_chapter_index"

// endregion

// region 3. TtsControlPanel Composable

/**
 * TTS 朗读控制按钮组 Composable。
 *
 * # 布局
 *
 * 横向按钮组 + 当前段落文本:
 * ```
 * ┌──────────────────────────────────────────────────────────┐
 * │ [⏮上一段] [▶/⏸播放暂停] [⏹停止] [⏭下一段] │ 当前段落文本... │
 * │ [⏪上一章]                          [⏩下一章]              │
 * └──────────────────────────────────────────────────────────┘
 * ```
 *
 * # 状态联动
 *
 * - 订阅 [ReadAloudControllerShared.state] 决定按钮启用状态与图标
 * - 订阅 [ReadAloudControllerShared.lastError] 显示错误提示
 * - 订阅 [ReadAloudControllerShared.currentText] 显示当前朗读段
 *
 * # 样式
 *
 * 用 Material3 Surface + Row, 与 ReaderScreen 的 ReaderTopBar/ReaderBottomBar 视觉对齐
 * (48dp 高度, surface 95% alpha, 8dp 横向间距)。不引入 ArcoDesign / 新依赖。
 *
 * @param controller 朗读控制器实例 (由 [rememberReadAloudController] 创建)
 * @param modifier 外部布局修饰符 (主代理整合时指定对齐 / padding)
 */
@Composable
fun TtsControlPanel(
    controller: ReadAloudControllerShared,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val currentText by controller.currentText.collectAsState()
    val lastError by controller.lastError.collectAsState()
    val chapterIndex by controller.chapterIndex.collectAsState()
    val paragraphIndex by controller.paragraphIndex.collectAsState()
    // KP2-D P1: 订阅 speechRate, 滑杆拖动时实时重组显示新值
    val speechRate by controller.speechRate.collectAsState()

    // 文案标签 (rememberString 是 @Composable, 顶层缓存; contentDescription / stateText 用)
    val previousSegmentLabel = rememberString("previous_segment")
    val playLabel = rememberString("play")
    val pauseLabel = rememberString("pause")
    val stopLabel = rememberString("stop")
    val nextSegmentLabel = rememberString("next_segment")
    val previousChapterLabel = rememberString("previous_chapter")
    val nextChapterLabel = rememberString("next_chapter")
    val speechRateLabel = rememberString("speech_rate")
    val readingNotStartedLabel = rememberString("reading_not_started")
    val readingProgressFormatLabel = rememberString("reading_progress_format")
    val pausedLabel = rememberString("paused")
    val stoppedLabel = rememberString("stopped")
    val readingCompletedLabel = rememberString("reading_completed")
    val readingErrorLabel = rememberString("reading_error")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        color = AppTheme.colors.background.copy(alpha = 0.95f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 第一行: 段落控制 + 播放按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 上一段
                IconButton(
                    onClick = { controller.prevParagraph() },
                    enabled = state == ReadAloudControllerShared.ReadAloudState.PLAYING
                        || state == ReadAloudControllerShared.ReadAloudState.PAUSED,
                ) {
                    Icon(
                        painter = rememberPainter("ic_arrow_back"),
                        contentDescription = previousSegmentLabel,
                        tint = AppTheme.colors.primaryText,
                    )
                }

                // 播放 / 暂停 (按 state 切换图标)
                val isPlaying = state == ReadAloudControllerShared.ReadAloudState.PLAYING
                IconButton(
                    onClick = {
                        if (isPlaying) {
                            controller.pause()
                        } else {
                            // IDLE / PAUSED / STOPPED / ERROR / COMPLETED → (重新)开始
                            // 已有章节: resume; 否则: 从当前 ViewModel.durChapterIndex 开始
                            if (state == ReadAloudControllerShared.ReadAloudState.PAUSED) {
                                controller.resume()
                            } else {
                                val startIdx = chapterIndex.takeIf { it >= 0 } ?: 0
                                controller.start(startIdx)
                            }
                        }
                    },
                ) {
                    Icon(
                        painter = if (isPlaying) {
                            rememberPainter("ic_pause_24dp")
                        } else {
                            rememberPainter("ic_play_24dp")
                        },
                        contentDescription = if (isPlaying) pauseLabel else playLabel,
                        tint = AppTheme.colors.primaryText,
                    )
                }

                // 停止
                IconButton(
                    onClick = { controller.stop() },
                    enabled = state == ReadAloudControllerShared.ReadAloudState.PLAYING
                        || state == ReadAloudControllerShared.ReadAloudState.PAUSED,
                ) {
                    Icon(
                        painter = rememberPainter("ic_stop_black_24dp"),
                        contentDescription = stopLabel,
                        tint = AppTheme.colors.primaryText,
                    )
                }

                // 下一段
                IconButton(
                    onClick = { controller.nextParagraph() },
                    enabled = state == ReadAloudControllerShared.ReadAloudState.PLAYING
                        || state == ReadAloudControllerShared.ReadAloudState.PAUSED,
                ) {
                    Icon(
                        painter = rememberPainter("ic_arrow_forward"),
                        contentDescription = nextSegmentLabel,
                        tint = AppTheme.colors.primaryText,
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 上一章 / 下一章
                IconButton(
                    onClick = { controller.prevChapter() },
                    enabled = state == ReadAloudControllerShared.ReadAloudState.PLAYING
                        || state == ReadAloudControllerShared.ReadAloudState.PAUSED
                        || state == ReadAloudControllerShared.ReadAloudState.IDLE,
                ) {
                    Icon(
                        painter = rememberPainter("ic_skip_previous"),
                        contentDescription = previousChapterLabel,
                        tint = AppTheme.colors.primaryText,
                    )
                }
                IconButton(
                    onClick = { controller.nextChapter() },
                    enabled = state == ReadAloudControllerShared.ReadAloudState.PLAYING
                        || state == ReadAloudControllerShared.ReadAloudState.PAUSED
                        || state == ReadAloudControllerShared.ReadAloudState.IDLE,
                ) {
                    Icon(
                        painter = rememberPainter("ic_skip_next"),
                        contentDescription = nextChapterLabel,
                        tint = AppTheme.colors.primaryText,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 当前状态文字
                Text(
                    text = stateText(
                        state, chapterIndex, paragraphIndex,
                        readingNotStartedLabel, readingProgressFormatLabel,
                        pausedLabel, stoppedLabel, readingCompletedLabel, readingErrorLabel,
                    ),
                    color = AppTheme.colors.secondaryText,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 第二行: 当前朗读段文本 (含错误提示)
            val displayText = lastError ?: currentText.takeIf { it.isNotEmpty() }
            if (displayText != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = rememberPainter("ic_volume_up"),
                        contentDescription = null,
                        tint = if (lastError != null) {
                            AppTheme.DesignTokens.arcoDanger
                        } else {
                            AppTheme.colors.secondaryText
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = displayText,
                        color = if (lastError != null) {
                            AppTheme.DesignTokens.arcoDanger
                        } else {
                            AppTheme.colors.secondaryText
                        },
                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // KP2-D P1: 第三行 - 朗读语速滑杆 (0.5-2.0x, step 0.1)
            // 拖动时实时更新 controller.speechRate, 下一段 speak 即生效新速度
            // 滑杆语义: 0.5x 慢速 / 1.0x 正常 / 2.0x 快速
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = rememberPainter("ic_speed"),
                    contentDescription = null,
                    tint = AppTheme.colors.secondaryText,
                )
                Text(
                    text = speechRateLabel,
                    color = AppTheme.colors.secondaryText,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
                )
                // Material3 Slider: steps = 中间离散点数
                // 0.5..2.0 step 0.1 → 16 个值, steps = 16 - 2 = 14
                Slider(
                    value = speechRate,
                    onValueChange = { controller.setSpeechRate(it) },
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    modifier = Modifier.weight(1f),
                )
                // 显示当前语速值 (如 "1.0x"), 固定宽度避免拖动时宽度跳动
                Text(
                    text = "%.1fx".format(speechRate),
                    color = AppTheme.colors.secondaryText,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 构造状态文字 (如 "正在朗读 3/12 章 第 5 段")。
 */
private fun stateText(
    state: ReadAloudControllerShared.ReadAloudState,
    chapterIndex: Int,
    paragraphIndex: Int,
    readingNotStarted: String,
    readingProgressFormat: String,
    paused: String,
    stopped: String,
    readingCompleted: String,
    readingError: String,
): String {
    return when (state) {
        ReadAloudControllerShared.ReadAloudState.IDLE -> readingNotStarted
        ReadAloudControllerShared.ReadAloudState.PLAYING ->
            readingProgressFormat.format(chapterIndex + 1, paragraphIndex + 1)
        ReadAloudControllerShared.ReadAloudState.PAUSED -> paused
        ReadAloudControllerShared.ReadAloudState.STOPPED -> stopped
        ReadAloudControllerShared.ReadAloudState.COMPLETED -> readingCompleted
        ReadAloudControllerShared.ReadAloudState.ERROR -> readingError
    }
}

// endregion
