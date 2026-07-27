package io.legado.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CheckSource
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppNumberField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.toastOnUi

/**
 * 校验设置（迁 dialog_check_source_config.xml → Compose）。
 * 复选框联动逐条对齐：搜索/发现至少留一；详情关联章节/正文级联禁用；
 * 确定时校验超时值后写 CheckSource + prefs（key/summary 不变）。
 */
class CheckSourceConfig : BaseComposeDialogFragment() {

    //允许的最小超时时间，秒
    private val minTimeout = 0L

    @Composable
    override fun Content() {
        var timeoutText by rememberSaveable { mutableStateOf((CheckSource.timeout / 1000).toString()) }
        var checkSearch by rememberSaveable { mutableStateOf(CheckSource.checkSearch) }
        var checkDiscovery by rememberSaveable { mutableStateOf(CheckSource.checkDiscovery) }
        var checkInfo by rememberSaveable { mutableStateOf(CheckSource.checkInfo) }
        var checkCategory by rememberSaveable { mutableStateOf(CheckSource.checkCategory) }
        var checkContent by rememberSaveable { mutableStateOf(CheckSource.checkContent) }

        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = stringResource(R.string.check_source_config),
                onBack = { dismissAllowingStateLoss() },
            )
            AppNumberField(
                value = timeoutText,
                onValueChange = { timeoutText = it },
                label = stringResource(R.string.check_source_timeout),
                maxLength = 9,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.check_source_item),
                color = AppTheme.colors.accent,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 搜索/发现至少保留一项（复刻原 onClick 联动）
                CheckItem(
                    text = stringResource(R.string.search),
                    checked = checkSearch,
                    modifier = Modifier.weight(1f),
                ) {
                    checkSearch = it
                    if (!checkSearch && !checkDiscovery) checkDiscovery = true
                }
                CheckItem(
                    text = stringResource(R.string.discovery),
                    checked = checkDiscovery,
                    modifier = Modifier.weight(1f),
                ) {
                    checkDiscovery = it
                    if (!checkSearch && !checkDiscovery) checkSearch = true
                }
                // 详情关闭时级联关闭并禁用 目录/正文；目录关闭时级联关闭并禁用 正文
                CheckItem(
                    text = stringResource(R.string.source_tab_info),
                    checked = checkInfo,
                    modifier = Modifier.weight(1f),
                ) {
                    checkInfo = it
                    if (!checkInfo) {
                        checkCategory = false
                        checkContent = false
                    }
                }
                CheckItem(
                    text = stringResource(R.string.chapter_list),
                    checked = checkCategory,
                    enabled = checkInfo,
                    modifier = Modifier.weight(1f),
                ) {
                    checkCategory = it
                    if (!checkCategory) checkContent = false
                }
                CheckItem(
                    text = stringResource(R.string.main_body),
                    checked = checkContent,
                    enabled = checkCategory,
                    modifier = Modifier.weight(1f),
                ) {
                    checkContent = it
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(text = stringResource(R.string.cancel)) {
                    dismiss()
                }
                AppTextButton(text = stringResource(R.string.ok)) {
                    val text = timeoutText
                    when {
                        text.isBlank() -> {
                            toastOnUi("${getString(R.string.timeout)}${getString(R.string.cannot_empty)}")
                            return@AppTextButton
                        }

                        text.toLong() <= minTimeout -> {
                            toastOnUi(
                                "${getString(R.string.timeout)}${getString(R.string.less_than)}${minTimeout}${
                                    getString(R.string.seconds)
                                }"
                            )
                            return@AppTextButton
                        }

                        else -> CheckSource.timeout = text.toLong() * 1000
                    }
                    CheckSource.checkSearch = checkSearch
                    CheckSource.checkDiscovery = checkDiscovery
                    CheckSource.checkInfo = checkInfo
                    CheckSource.checkCategory = checkCategory
                    CheckSource.checkContent = checkContent
                    CheckSource.putConfig()
                    AppConfig.checkSource = CheckSource.summary
                    dismiss()
                }
            }
        }
    }

    @Composable
    private fun CheckItem(
        text: String,
        checked: Boolean,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier.toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppCheckbox(checked = checked, onCheckedChange = null, enabled = enabled)
            Text(
                text = text,
                color = if (enabled) AppTheme.colors.primaryText
                else AppTheme.colors.secondaryText.copy(alpha = 0.5f),
                fontSize = 12.sp,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}
