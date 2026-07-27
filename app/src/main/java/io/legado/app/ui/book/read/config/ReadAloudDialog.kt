package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.utils.toastOnUi

/** 朗读控制：章节/段落切换、定时、语速、目录/菜单/后台/设置 */
class ReadAloudDialog : BaseReadBottomComposeDialog() {

    override val dismissWhenOtherBottomDialogShowing = true
    private val callBack: CallBack? get() = activity as? CallBack

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val colors = rememberReadMenuColors()
        var paused by remember { mutableStateOf(BaseReadAloudService.pause) }
        var timer by remember {
            mutableIntStateOf(
                if (BaseReadAloudService.timeMinute > 0) BaseReadAloudService.timeMinute
                else AppConfig.ttsTimer
            )
        }
        var followSys by remember { mutableStateOf(AppConfig.ttsFlowSys) }
        var speechRate by remember { mutableIntStateOf(AppConfig.ttsSpeechRate) }

        LaunchedEffect(Unit) {
            ReadBookEvents.aloudState.collect { paused = BaseReadAloudService.pause }
        }
        LaunchedEffect(Unit) {
            ReadBookEvents.readAloudDs.collect { timer = it }
        }

        fun upTtsSpeechRate() {
            ReadAloud.upTtsSpeechRate(context)
            if (!BaseReadAloudService.pause) {
                ReadAloud.pause(context)
                ReadAloud.resume(context)
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // 章节/段落播放控制行
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.previous_chapter),
                    color = colors.text, fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
                Spacer(Modifier.weight(1f))
                AloudIcon("ic_skip_previous", stringResource(R.string.prev_sentence), colors) {
                    ReadAloud.prevParagraph(context)
                }
                AloudIcon(
                    if (paused) "ic_play_24dp" else "ic_pause_24dp",
                    stringResource(if (paused) R.string.audio_play else R.string.pause),
                    colors,
                ) { callBack?.onClickReadAloud() }
                AloudIcon("ic_stop_black_24dp", stringResource(R.string.stop), colors) {
                    ReadAloud.stop(context)
                    dismissAllowingStateLoss()
                }
                AloudIcon("ic_skip_next", stringResource(R.string.next_sentence), colors) {
                    ReadAloud.nextParagraph(context)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.next_chapter),
                    color = colors.text, fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { ReadBook.moveToNextChapter(true) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            // 定时行：存默认 + 滑条 + 弹选时间
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AloudIcon("ic_time_add_24dp", stringResource(R.string.set_timer), colors) {
                    AppConfig.ttsTimer = timer
                    toastOnUi("保存设定时间成功！")
                }
                AppSlider(
                    value = timer,
                    max = 180,
                    onValueChange = { timer = it },
                    onValueChangeFinished = { ReadAloud.setTimer(context, timer) },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.timer_m, timer.coerceAtLeast(0)),
                    color = colors.text,
                    modifier = Modifier.clickable {
                        val times = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
                        val timeKeys = times.map { "$it 分钟" }
                        context.selector("设定时间", timeKeys) { _, index ->
                            ReadAloud.setTimer(context, times[index])
                        }
                    },
                )
            }
            // 语速：标题/值 + 跟随系统开关；滑条 + 加减
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.read_aloud_speed), color = colors.text, fontSize = 14.sp)
                if (!followSys) {
                    Text(
                        ((speechRate + 5) / 10f).toString(),
                        color = colors.text, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.flow_sys), color = colors.text, fontSize = 14.sp)
                AppSwitch(
                    checked = followSys,
                    onCheckedChange = {
                        followSys = it
                        AppConfig.ttsFlowSys = it
                        upTtsSpeechRate()
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AloudIcon(
                    "ic_reduce", stringResource(R.string.tts_speech_reduce), colors,
                    enabled = !followSys,
                ) {
                    speechRate = (speechRate - 1).coerceIn(0, 45)
                    AppConfig.ttsSpeechRate = speechRate
                    upTtsSpeechRate()
                }
                AppSlider(
                    value = speechRate,
                    max = 45,
                    enabled = !followSys,
                    onValueChange = { speechRate = it },
                    onValueChangeFinished = {
                        AppConfig.ttsSpeechRate = speechRate
                        upTtsSpeechRate()
                    },
                    modifier = Modifier.weight(1f),
                )
                AloudIcon(
                    "ic_add", stringResource(R.string.tts_speech_add), colors,
                    enabled = !followSys,
                ) {
                    speechRate = (speechRate + 1).coerceIn(0, 45)
                    AppConfig.ttsSpeechRate = speechRate
                    upTtsSpeechRate()
                }
            }
            // 底部功能按钮行
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ReadMenuIconButton("ic_toc", stringResource(R.string.chapter_list), colors.text) {
                    callBack?.openChapterList()
                }
                ReadMenuIconButton("ic_menu", stringResource(R.string.main_menu), colors.text) {
                    callBack?.showMenuBar()
                    dismissAllowingStateLoss()
                }
                ReadMenuIconButton(
                    "ic_visibility_off",
                    stringResource(R.string.to_backstage),
                    colors.text,
                ) { callBack?.finish() }
                ReadMenuIconButton("ic_settings", stringResource(R.string.setting), colors.text) {
                    ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
                }
            }
        }
    }

    @Composable
    private fun AloudIcon(
        iconKey: String,
        description: String,
        colors: ReadMenuColors,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = description,
            tint = if (enabled) colors.text else colors.secondaryText,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(30.dp)
                .clickable(enabled = enabled, onClick = onClick),
        )
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun finish()
    }
}
