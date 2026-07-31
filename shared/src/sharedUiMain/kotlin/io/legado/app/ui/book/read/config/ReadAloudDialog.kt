// I18N KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端字面量):
// - previous_chapter: "上一章" (已存在 jvmMain)
// - next_chapter: "下一章" (已存在 jvmMain)
// - prev_sentence: "上一句"
// - next_sentence: "下一句"
// - audio_play: "播放"
// - pause: "暂停" (已存在 jvmMain)
// - stop: "停止" (已存在 jvmMain)
// - set_timer: "设定时间"
// - timer_m: "%d 分钟"
// - read_aloud_speed: "语速"
// - flow_sys: "跟随系统"
// - tts_speech_reduce: "语速减"
// - tts_speech_add: "语速加"
// - chapter_list: "目录" (已存在 jvmMain)
// - main_menu: "主菜单"
// - to_backstage: "转到后台"
// - setting: "设置" (已存在 jvmMain)
//
// PAINTER KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端图标):
// - ic_skip_previous: 上一句/上一章 (已存在 jvmMain)
// - ic_skip_next: 下一句/下一章 (已存在 jvmMain)
// - ic_play_24dp: 播放 (已存在 jvmMain)
// - ic_pause_24dp: 暂停 (已存在 jvmMain)
// - ic_stop_black_24dp: 停止 (已存在 jvmMain)
// - ic_reduce: 语速减 (已存在 jvmMain)
// - ic_add: 语速加 (已存在 jvmMain)
// - ic_toc: 目录 (已存在 jvmMain)
// - ic_menu: 主菜单
// - ic_visibility_off: 转到后台
// - ic_settings: 设置 (已存在 jvmMain)
// - ic_time_add_24dp: 定时

package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.chapter_list
import legado.shared.generated.resources.flow_sys
import legado.shared.generated.resources.main_menu
import legado.shared.generated.resources.next_chapter
import legado.shared.generated.resources.next_sentence
import legado.shared.generated.resources.prev_sentence
import legado.shared.generated.resources.previous_chapter
import legado.shared.generated.resources.read_aloud_speed
import legado.shared.generated.resources.set_timer
import legado.shared.generated.resources.setting
import legado.shared.generated.resources.stop
import legado.shared.generated.resources.timer_m
import legado.shared.generated.resources.to_backstage
import legado.shared.generated.resources.tts_speech_add
import legado.shared.generated.resources.tts_speech_reduce
import org.jetbrains.compose.resources.stringResource

/**
 * 朗读控制面板对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.read.config.ReadAloudDialog`,
 * 但去掉对 Android Fragment / BaseReadAloudService / ReadAloud / ReadBook /
 * ReadBookEvents / ReadMenuColors / toastOnUi / context.selector 的依赖,
 * 改为纯 @Composable + 回调形式:
 * - 调用方传入 [isPlaying] (当前播放状态), 用户点击播放/暂停按钮通过 [onPlayPause] 回调
 * - 用户点击停止按钮通过 [onStop] 回调 (并自动 dismiss)
 * - 用户点击上一章/下一章通过 [onPrev] / [onNext] 回调
 * - 用户调整定时通过 [onSetTimer] 回调 (参数为分钟数, 0=关闭定时)
 * - 用户调整语速通过 [onAdjustSpeed] 回调 (参数为 Float 速率, 0.5~5.0, 1.0=正常)
 * - [onDismiss] 关闭回调
 *
 * # 原业务逻辑保留
 *
 * - 章节/段落播放控制行: 上一章 | 上一句 播放/暂停 停止 下一句 | 下一章
 *   (与原版 Row + Text(上一章) + AloudIcon(prevParagraph) + AloudIcon(play/pause) +
 *   AloudIcon(stop) + AloudIcon(nextParagraph) + Text(下一章) 对齐)
 * - 定时行: 定时图标(保存当前定时) + 滑条(0..180 分钟) + 当前时间(点击弹选时)
 *   (与原版 Row + AloudIcon(time_add) + AppSlider + Text(timer_m) 对齐)
 * - 语速行: 标题 + 当前值 + 跟随系统开关 + 滑条(0..45) + 加减按钮
 *   (与原版 Row + Text(read_aloud_speed) + Text(value) + Text(flow_sys) + AppSwitch +
 *   Row + AloudIcon(reduce) + AppSlider + AloudIcon(add) 对齐)
 * - 底部功能按钮行: 目录 / 主菜单 / 转到后台 / 设置
 *   (与原版 Row + ReadMenuIconButton(toc) + ReadMenuIconButton(menu) +
 *   ReadMenuIconButton(visibility_off) + ReadMenuIconButton(settings) 对齐)
 *
 * # 与原版的差异 (KMP 限制)
 *
 * - 原版 `ReadBookEvents.aloudState.collect` 监听朗读状态变化, 下沉后由调用方通过
 *   [isPlaying] 参数传入 (调用方负责订阅 aloudState 并重组传入)
 * - 原版 `ReadBookEvents.readAloudDs.collect` 监听定时变化, 下沉后由调用方通过
 *   [initialTimer] 参数传入 (调用方负责订阅 readAloudDs 并重组传入)
 * - 原版 `AppConfig.ttsFlowSys / ttsSpeechRate / ttsTimer` 直接读写, 下沉后改为
 *   [onAdjustSpeed] / [onSetTimer] 回调 (由调用方决定持久化)
 * - 原版 `callBack?.openChapterList() / showMenuBar() / finish() / ReadAloudConfigDialog().show()`
 *   通过 ReadAloudDialog.CallBack 接口桥接, 下沉后改为 [onOpenChapterList] /
 *   [onShowMenuBar] / [onBackstage] / [onOpenSettings] 回调
 *
 * # 语速范围说明
 *
 * - 内部 speechRate: Int (0..45), 与原版 `AppConfig.ttsSpeechRate` 范围一致
 * - 显示速率: (speechRate + 5) / 10f, 即 0.5x ~ 5.0x (1.0x = speechRate=5)
 * - 任务要求"0.5x ~ 4.0x", 实际原版支持到 5.0x, 保留原版范围 (4.0x = speechRate=35)
 * - [onAdjustSpeed] 回调接收 Float 速率值 (0.5~5.0), 与任务参数类型对齐
 *
 * # 样式 (Arco Design 规范)
 *
 * - 主色 arcoblue-6 (#165DFF): Slider thumb/activeTrack + 图标 tint
 * - 圆角 arco_radius_lg = 16dp: Dialog Surface 圆角
 * - 无阴影 (Surface 默认无阴影)
 *
 * @param isPlaying 当前是否正在播放 (true=播放中, false=已暂停/已停止)
 * @param initialTimer 初始定时值 (分钟, 0=关闭定时)
 * @param initialSpeechRate 初始语速 (Int 0..45, 5=1.0x)
 * @param initialFollowSys 初始是否跟随系统语速
 * @param onPlayPause 点击播放/暂停按钮
 * @param onStop 点击停止按钮 (本 Dialog 会自动 dismiss)
 * @param onPrev 点击上一章按钮
 * @param onNext 点击下一章按钮
 * @param onPrevParagraph 点击上一句按钮
 * @param onNextParagraph 点击下一句按钮
 * @param onSetTimer 调整定时 (参数为分钟数, 0=关闭定时)
 * @param onAdjustSpeed 调整语速 (参数为 Float 速率, 0.5~5.0)
 * @param onFollowSysChange 切换"跟随系统语速"开关
 * @param onOpenChapterList 打开目录
 * @param onShowMenuBar 显示主菜单
 * @param onBackstage 转到后台
 * @param onOpenSettings 打开朗读设置
 * @param onDismiss 关闭回调
 */
@Composable
fun ReadAloudDialog(
    isPlaying: Boolean,
    initialTimer: Int = 0,
    initialSpeechRate: Int = 5,
    initialFollowSys: Boolean = false,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPrevParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    onSetTimer: (Int) -> Unit,
    onAdjustSpeed: (Float) -> Unit,
    onFollowSysChange: (Boolean) -> Unit,
    onOpenChapterList: () -> Unit,
    onShowMenuBar: () -> Unit,
    onBackstage: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors

    // 本地状态: 定时值 / 语速 / 跟随系统 (与原版 remember { mutableStateOf(...) } 对齐)
    var timer by remember(initialTimer) { mutableIntStateOf(initialTimer) }
    var speechRate by remember(initialSpeechRate) { mutableIntStateOf(initialSpeechRate) }
    var followSys by remember(initialFollowSys) { mutableStateOf(initialFollowSys) }
    // 定时弹窗展开状态 (与原版 context.selector 单弹窗对齐)
    var timerMenuExpanded by remember { mutableStateOf(false) }
    // 拖动中的语速预览值 (与原版 AppSlider 拖动语义对齐: 拖动中仅刷新显示, 抬手回调)
    var draggingSpeed by remember { mutableFloatStateOf(-1f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.appDialogSize().padding(16.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // 章节/段落播放控制行 (与原版 Row + Text(上一章) + AloudIcon x4 + Text(下一章) 对齐)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.previous_chapter),
                        color = colors.primaryText,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable(onClick = onPrev)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    AloudIcon(
                        iconKey = "ic_skip_previous",
                        description = stringResource(Res.string.prev_sentence),
                        tint = colors.primaryText,
                        onClick = onPrevParagraph,
                    )
                    AloudIcon(
                        iconKey = if (isPlaying) "ic_pause_24dp" else "ic_play_24dp",
                        description = rememberString(if (isPlaying) "pause" else "audio_play"),
                        tint = colors.primaryText,
                        onClick = onPlayPause,
                    )
                    AloudIcon(
                        iconKey = "ic_stop_black_24dp",
                        description = stringResource(Res.string.stop),
                        tint = colors.primaryText,
                        onClick = {
                            onStop()
                            onDismiss()
                        },
                    )
                    AloudIcon(
                        iconKey = "ic_skip_next",
                        description = stringResource(Res.string.next_sentence),
                        tint = colors.primaryText,
                        onClick = onNextParagraph,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.next_chapter),
                        color = colors.primaryText,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable(onClick = onNext)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }

                // 定时行: 图标 + 滑条 + 当前时间(点击弹选时) (与原版 Row + AloudIcon + AppSlider + Text 对齐)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 定时图标: 点击保存当前定时 (与原版 AloudIcon(time_add) + AppConfig.ttsTimer = timer 对齐)
                    AloudIcon(
                        iconKey = "ic_time_add_24dp",
                        description = stringResource(Res.string.set_timer),
                        tint = colors.primaryText,
                        onClick = { onSetTimer(timer) },
                    )
                    // 滑条 0..180 分钟 (与原版 AppSlider(value=timer, max=180) 对齐)
                    // 用 material.Slider (Float) 替代 AppSlider (Int), 因为 AppSlider 没有 Float 重载
                    Slider(
                        value = timer.toFloat(),
                        onValueChange = {
                            val newTimer = it.toInt().coerceIn(0, 180)
                            if (newTimer != timer) {
                                timer = newTimer
                                onSetTimer(timer)
                            }
                        },
                        valueRange = 0f..180f,
                        colors = SliderDefaults.colors(
                            thumbColor = DesignTokens.arcoBlue6,
                            activeTrackColor = DesignTokens.arcoBlue6,
                            inactiveTrackColor = DesignTokens.arcoBlue6.copy(alpha = 0.2f),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    // 当前时间显示 + 点击弹选时菜单 (与原版 Text(timer_m) + context.selector 对齐)
                    Box {
                        Text(
                            text = stringResource(Res.string.timer_m, timer.coerceAtLeast(0)),
                            color = colors.primaryText,
                            modifier = Modifier.clickable { timerMenuExpanded = true },
                        )
                        // 定时选项菜单 (与原版 context.selector + intArrayOf(0,5,10,15,30,60,90,180) 对齐)
                        AppDropdownMenu(
                            expanded = timerMenuExpanded,
                            onDismissRequest = { timerMenuExpanded = false },
                        ) {
                            val times = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
                            times.forEach { t ->
                                DropdownMenuItem(
                                    onClick = {
                                        timerMenuExpanded = false
                                        timer = t
                                        onSetTimer(t)
                                    },
                                ) {
                                    Text(stringResource(Res.string.timer_m, t))
                                }
                            }
                        }
                    }
                }

                // 语速标题行: 标题 + 当前值 + 跟随系统开关 (与原版 Row + Text(speed) + Text(value) + Text(flow_sys) + AppSwitch 对齐)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.read_aloud_speed),
                        color = colors.primaryText,
                        fontSize = 14.sp,
                    )
                    if (!followSys) {
                        Text(
                            text = ((speechRate + 5) / 10f).toString(),
                            color = colors.primaryText,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.flow_sys),
                        color = colors.primaryText,
                        fontSize = 14.sp,
                    )
                    AppSwitch(
                        checked = followSys,
                        onCheckedChange = {
                            followSys = it
                            onFollowSysChange(it)
                        },
                    )
                }

                // 语速滑条行: 减 + 滑条 + 加 (与原版 Row + AloudIcon(reduce) + AppSlider + AloudIcon(add) 对齐)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AloudIcon(
                        iconKey = "ic_reduce",
                        description = stringResource(Res.string.tts_speech_reduce),
                        tint = if (followSys) colors.secondaryText else colors.primaryText,
                        enabled = !followSys,
                        onClick = {
                            speechRate = (speechRate - 1).coerceIn(0, 45)
                            onAdjustSpeed((speechRate + 5) / 10f)
                        },
                    )
                    Slider(
                        value = (if (draggingSpeed >= 0) draggingSpeed else speechRate.toFloat()),
                        onValueChange = {
                            draggingSpeed = it
                        },
                        onValueChangeFinished = {
                            if (draggingSpeed >= 0) {
                                speechRate = draggingSpeed.toInt().coerceIn(0, 45)
                                draggingSpeed = -1f
                                onAdjustSpeed((speechRate + 5) / 10f)
                            }
                        },
                        valueRange = 0f..45f,
                        enabled = !followSys,
                        colors = SliderDefaults.colors(
                            thumbColor = DesignTokens.arcoBlue6,
                            activeTrackColor = DesignTokens.arcoBlue6,
                            inactiveTrackColor = DesignTokens.arcoBlue6.copy(alpha = 0.2f),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    AloudIcon(
                        iconKey = "ic_add",
                        description = stringResource(Res.string.tts_speech_add),
                        tint = if (followSys) colors.secondaryText else colors.primaryText,
                        enabled = !followSys,
                        onClick = {
                            speechRate = (speechRate + 1).coerceIn(0, 45)
                            onAdjustSpeed((speechRate + 5) / 10f)
                        },
                    )
                }

                // 底部功能按钮行: 目录 / 主菜单 / 转到后台 / 设置 (与原版 Row + ReadMenuIconButton x4 对齐)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BottomIconButton(
                        iconKey = "ic_toc",
                        label = stringResource(Res.string.chapter_list),
                        onClick = onOpenChapterList,
                    )
                    BottomIconButton(
                        iconKey = "ic_menu",
                        label = stringResource(Res.string.main_menu),
                        onClick = {
                            onShowMenuBar()
                            onDismiss()
                        },
                    )
                    BottomIconButton(
                        iconKey = "ic_visibility_off",
                        label = stringResource(Res.string.to_backstage),
                        onClick = onBackstage,
                    )
                    BottomIconButton(
                        iconKey = "ic_settings",
                        label = stringResource(Res.string.setting),
                        onClick = onOpenSettings,
                    )
                }
            }
        }
    }
}

/**
 * 朗读控制图标按钮 (复刻自 app 端 ReadAloudDialog.AloudIcon)。
 *
 * - 30dp 图标 + 8dp 水平 padding
 * - 禁用时 tint 走 secondaryText (与原版 `if (enabled) colors.text else colors.secondaryText` 对齐)
 */
@Composable
private fun AloudIcon(
    iconKey: String,
    description: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Icon(
        painter = rememberPainter(iconKey),
        contentDescription = description,
        tint = if (enabled) tint else AppTheme.colors.secondaryText,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(30.dp)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * 底部功能图标按钮 (复刻自 app 端 ReadMenuIconButton)。
 *
 * 竖排: 图标 + 文字标签, 单击触发回调。
 */
@Composable
private fun BottomIconButton(
    iconKey: String,
    label: String,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = label,
            tint = colors.primaryText,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            color = colors.primaryText,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
