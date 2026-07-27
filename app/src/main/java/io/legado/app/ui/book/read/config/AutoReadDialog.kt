package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.BaseReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.compose.component.AppSlider
import java.util.Locale

/** 自动翻页控制：速度滑条 + 目录/菜单/停止/设置 */
class AutoReadDialog : BaseReadBottomComposeDialog() {

    override val dismissWhenOtherBottomDialogShowing = true
    private val callBack: CallBack? get() = activity as? CallBack

    @Composable
    override fun Content() {
        val colors = rememberReadMenuColors()
        var speed by remember {
            mutableIntStateOf(ReadBookConfig.autoReadSpeed.coerceAtLeast(1))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.auto_page_speed),
                    color = colors.text, fontSize = 14.sp,
                    modifier = Modifier.weight(1f).padding(8.dp),
                )
                Text(
                    String.format(Locale.ROOT, "%ds", speed),
                    color = colors.text, fontSize = 14.sp,
                    modifier = Modifier.padding(8.dp),
                )
            }
            AppSlider(
                value = speed,
                min = 1,
                max = 120,
                onValueChange = { speed = it.coerceAtLeast(1) },
                onValueChangeFinished = {
                    ReadBookConfig.autoReadSpeed = speed
                    upTtsSpeechRate()
                },
                modifier = Modifier.fillMaxWidth(),
            )
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
                ReadMenuIconButton("ic_auto_page_stop", stringResource(R.string.stop), colors.text) {
                    callBack?.autoPageStop()
                    dismissAllowingStateLoss()
                }
                ReadMenuIconButton("ic_settings", stringResource(R.string.setting), colors.text) {
                    (activity as BaseReadBookActivity).showPageAnimConfig {
                        (activity as ReadBookActivity).upPageAnim()
                        ReadBook.loadContent(false)
                    }
                }
            }
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun autoPageStop()
    }
}
