package io.legado.app.ui.book.manga.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.manga_check_chapter
import legado.shared.generated.resources.manga_check_chapter_label
import legado.shared.generated.resources.manga_check_chapter_name
import legado.shared.generated.resources.manga_check_page_label
import legado.shared.generated.resources.manga_check_page_number
import legado.shared.generated.resources.manga_check_progress
import legado.shared.generated.resources.manga_check_progress_label
import legado.shared.generated.resources.manga_footer_config
import legado.shared.generated.resources.manga_header_chapter
import legado.shared.generated.resources.manga_header_footer
import legado.shared.generated.resources.manga_header_orientation
import legado.shared.generated.resources.manga_header_page
import legado.shared.generated.resources.manga_header_progress
import legado.shared.generated.resources.manga_radio_center
import legado.shared.generated.resources.manga_radio_left
import org.jetbrains.compose.resources.stringResource

// 信息条对齐常量 (对照 app 端 render.INFO_BAR_ALIGN_*)
private const val INFO_BAR_ALIGN_LEFT = 0
private const val INFO_BAR_ALIGN_CENTER = 1

/**
 * 漫画页脚信息条配置对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `MangaFooterSettingDialog`, 去掉 BaseComposeDialogFragment / AppConfig 依赖,
 * 改为纯 @Composable + 回调: 配置变更实时回调 [onConfigChange], 持久化由调用方完成。
 *
 * @param config 当前页脚配置 (作为初始值, 内部状态由 remember 持有)
 * @param onConfigChange 配置变化 (实时回调, 对照 app 端 upConfig + postEvent)
 * @param onDismiss 用户关闭对话框
 */
@Composable
fun MangaFooterSettingDialog(
    config: MangaFooterConfig,
    onConfigChange: (MangaFooterConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors

    var showFooter by remember { mutableStateOf(!config.hideFooter) }
    var chapterLabel by remember { mutableStateOf(!config.hideChapterLabel) }
    var chapter by remember { mutableStateOf(!config.hideChapter) }
    var chapterName by remember { mutableStateOf(!config.hideChapterName) }
    var pageNumberLabel by remember { mutableStateOf(!config.hidePageNumberLabel) }
    var pageNumber by remember { mutableStateOf(!config.hidePageNumber) }
    var progressRatioLabel by remember { mutableStateOf(!config.hideProgressRatioLabel) }
    var progressRatio by remember { mutableStateOf(!config.hideProgressRatio) }
    var orientation by remember { mutableIntStateOf(config.footerOrientation) }

    fun upConfig(block: MangaFooterConfig.() -> Unit) {
        onConfigChange(MangaFooterConfig().apply {
            hideFooter = !showFooter
            hideChapterLabel = !chapterLabel
            hideChapter = !chapter
            hideChapterName = !chapterName
            hidePageNumberLabel = !pageNumberLabel
            hidePageNumber = !pageNumber
            hideProgressRatioLabel = !progressRatioLabel
            hideProgressRatio = !progressRatio
            footerOrientation = orientation
            block()
        })
    }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = stringResource(Res.string.manga_footer_config),
                    onBack = onDismiss,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(DesignTokens.spacingLg)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.manga_header_footer),
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
                            header = stringResource(Res.string.manga_header_chapter),
                            checks = listOf(
                                Triple(
                                    stringResource(Res.string.manga_check_chapter_label),
                                    chapterLabel
                                ) {
                                    chapterLabel = it
                                    upConfig { hideChapterLabel = !it }
                                },
                                Triple(stringResource(Res.string.manga_check_chapter), chapter) {
                                    chapter = it
                                    upConfig { hideChapter = !it }
                                },
                                Triple(
                                    stringResource(Res.string.manga_check_chapter_name),
                                    chapterName
                                ) {
                                    chapterName = it
                                    upConfig { hideChapterName = !it }
                                },
                            ),
                        )
                        CheckRow(
                            header = stringResource(Res.string.manga_header_page),
                            checks = listOf(
                                Triple(
                                    stringResource(Res.string.manga_check_page_label),
                                    pageNumberLabel
                                ) {
                                    pageNumberLabel = it
                                    upConfig { hidePageNumberLabel = !it }
                                },
                                Triple(
                                    stringResource(Res.string.manga_check_page_number),
                                    pageNumber
                                ) {
                                    pageNumber = it
                                    upConfig { hidePageNumber = !it }
                                },
                            ),
                        )
                        CheckRow(
                            header = stringResource(Res.string.manga_header_progress),
                            checks = listOf(
                                Triple(
                                    stringResource(Res.string.manga_check_progress_label),
                                    progressRatioLabel
                                ) {
                                    progressRatioLabel = it
                                    upConfig { hideProgressRatioLabel = !it }
                                },
                                Triple(
                                    stringResource(Res.string.manga_check_progress),
                                    progressRatio
                                ) {
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
                                text = stringResource(Res.string.manga_header_orientation),
                                color = colors.primaryText,
                                fontSize = 14.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            RadioItem(
                                label = stringResource(Res.string.manga_radio_left),
                                value = INFO_BAR_ALIGN_LEFT,
                                selected = orientation == INFO_BAR_ALIGN_LEFT,
                            ) {
                                orientation = it
                                upConfig { footerOrientation = it }
                            }
                            Spacer(Modifier.width(12.dp))
                            RadioItem(
                                label = stringResource(Res.string.manga_radio_center),
                                value = INFO_BAR_ALIGN_CENTER,
                                selected = orientation == INFO_BAR_ALIGN_CENTER,
                            ) {
                                orientation = it
                                upConfig { footerOrientation = it }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
private fun RadioItem(
    label: String,
    value: Int,
    selected: Boolean,
    onSelect: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier.clickable { onSelect(value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppRadioButton(
            selected = selected,
            onClick = { onSelect(value) },
        )
        Text(text = label, color = colors.primaryText, fontSize = 14.sp)
    }
}
