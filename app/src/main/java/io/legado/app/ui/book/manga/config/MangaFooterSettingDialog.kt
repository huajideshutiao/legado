package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.render.INFO_BAR_ALIGN_CENTER
import io.legado.app.ui.book.manga.render.INFO_BAR_ALIGN_LEFT
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent

class MangaFooterSettingDialog : BaseComposeDialogFragment() {

    val config = GSON.fromJsonObject<MangaFooterConfig>(AppConfig.mangaFooterConfig).getOrNull()
        ?: MangaFooterConfig()

    private var showFooter by mutableStateOf(!config.hideFooter)
    private var chapterLabel by mutableStateOf(!config.hideChapterLabel)
    private var chapter by mutableStateOf(!config.hideChapter)
    private var chapterName by mutableStateOf(!config.hideChapterName)
    private var pageNumberLabel by mutableStateOf(!config.hidePageNumberLabel)
    private var pageNumber by mutableStateOf(!config.hidePageNumber)
    private var progressRatioLabel by mutableStateOf(!config.hideProgressRatioLabel)
    private var progressRatio by mutableStateOf(!config.hideProgressRatio)
    private var orientation by mutableIntStateOf(config.footerOrientation)

    /** 改完配置立即广播，阅读页实时生效 */
    private fun upConfig(block: MangaFooterConfig.() -> Unit) {
        config.block()
        postEvent(EventBus.UP_MANGA_CONFIG, config)
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = stringResource(R.string.manga_footer_config),
                onBack = { dismissAllowingStateLoss() },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.manga_header_footer),
                        color = colors.primaryText,
                        modifier = Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = showFooter,
                        onCheckedChange = {
                            showFooter = it
                            upConfig { hideFooter = !it }
                        },
                    )
                }
                if (showFooter) {
                    CheckRow(
                        header = stringResource(R.string.manga_header_chapter),
                        checks = listOf(
                            Triple(stringResource(R.string.manga_check_chapter_label), chapterLabel) {
                                chapterLabel = it
                                upConfig { hideChapterLabel = !it }
                            },
                            Triple(stringResource(R.string.manga_check_chapter), chapter) {
                                chapter = it
                                upConfig { hideChapter = !it }
                            },
                            Triple(stringResource(R.string.manga_check_chapter_name), chapterName) {
                                chapterName = it
                                upConfig { hideChapterName = !it }
                            },
                        ),
                    )
                    CheckRow(
                        header = stringResource(R.string.manga_header_page),
                        checks = listOf(
                            Triple(stringResource(R.string.manga_check_page_label), pageNumberLabel) {
                                pageNumberLabel = it
                                upConfig { hidePageNumberLabel = !it }
                            },
                            Triple(stringResource(R.string.manga_check_page_number), pageNumber) {
                                pageNumber = it
                                upConfig { hidePageNumber = !it }
                            },
                        ),
                    )
                    CheckRow(
                        header = stringResource(R.string.manga_header_progress),
                        checks = listOf(
                            Triple(stringResource(R.string.manga_check_progress_label), progressRatioLabel) {
                                progressRatioLabel = it
                                upConfig { hideProgressRatioLabel = !it }
                            },
                            Triple(stringResource(R.string.manga_check_progress), progressRatio) {
                                progressRatio = it
                                upConfig { hideProgressRatio = !it }
                            },
                        ),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.manga_header_orientation),
                            color = colors.primaryText,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        RadioItem(stringResource(R.string.manga_radio_left), INFO_BAR_ALIGN_LEFT)
                        Spacer(Modifier.width(12.dp))
                        RadioItem(stringResource(R.string.manga_radio_center), INFO_BAR_ALIGN_CENTER)
                    }
                }
            }
        }
    }

    /** 一行「表头 + 若干带文字复选框」，对齐原 ConstraintLayout 行布局 */
    @Composable
    private fun CheckRow(
        header: String,
        checks: List<Triple<String, Boolean, (Boolean) -> Unit>>,
    ) {
        val colors = AppTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = header, color = colors.primaryText, fontSize = 14.sp)
            checks.forEach { (label, checked, onChange) ->
                Row(
                    Modifier
                        .padding(start = 8.dp)
                        .clickable { onChange(!checked) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCheckbox(checked = checked, onCheckedChange = onChange)
                    Text(text = label, color = colors.primaryText, fontSize = 14.sp)
                }
            }
        }
    }

    @Composable
    private fun RadioItem(label: String, value: Int) {
        val colors = AppTheme.colors
        Row(
            Modifier.clickable { selectOrientation(value) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppRadioButton(
                selected = orientation == value,
                onClick = { selectOrientation(value) },
            )
            Text(text = label, color = colors.primaryText, fontSize = 14.sp)
        }
    }

    private fun selectOrientation(value: Int) {
        orientation = value
        upConfig { footerOrientation = value }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        AppConfig.mangaFooterConfig = GSON.toJson(config)
    }
}
