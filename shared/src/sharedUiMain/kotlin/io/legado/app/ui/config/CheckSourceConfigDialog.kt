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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppNumberField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.cannot_empty
import legado.shared.generated.resources.chapter_list
import legado.shared.generated.resources.check_source_config
import legado.shared.generated.resources.check_source_item
import legado.shared.generated.resources.check_source_timeout
import legado.shared.generated.resources.discovery
import legado.shared.generated.resources.less_than
import legado.shared.generated.resources.main_body
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.search
import legado.shared.generated.resources.seconds
import legado.shared.generated.resources.source_tab_info
import legado.shared.generated.resources.timeout
import org.jetbrains.compose.resources.stringResource
import io.legado.app.model.CheckSourceShared as CheckSource

/**
 * 校验设置对话框 (KMP 共享, 对照 app 端 CheckSourceConfig Fragment)。
 *
 * 复选框联动逐条对齐：搜索/发现至少留一；详情关联章节/正文级联禁用；
 * 确定时校验超时值后写 CheckSource + prefs。
 *
 * @param onDismiss 关闭回调
 * @param onToast 显示 toast (平台专属, app 用 toastOnUi, desktop 用 Toasters)
 */
@Composable
fun CheckSourceConfigDialog(
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    var timeoutText by remember { mutableStateOf((CheckSource.timeout / 1000).toString()) }
    var checkSearch by remember { mutableStateOf(CheckSource.checkSearch) }
    var checkDiscovery by remember { mutableStateOf(CheckSource.checkDiscovery) }
    var checkInfo by remember { mutableStateOf(CheckSource.checkInfo) }
    var checkCategory by remember { mutableStateOf(CheckSource.checkCategory) }
    var checkContent by remember { mutableStateOf(CheckSource.checkContent) }

    // 预取 string resource (stringResource 不能在 lambda 中调用)
    val timeoutStr = stringResource(Res.string.timeout)
    val cannotEmptyStr = stringResource(Res.string.cannot_empty)
    val lessThanStr = stringResource(Res.string.less_than)
    val secondsStr = stringResource(Res.string.seconds)

    Column(Modifier.fillMaxWidth()) {
        DialogTitleBar(
            title = stringResource(Res.string.check_source_config),
            onBack = onDismiss,
        )
        AppNumberField(
            value = timeoutText,
            onValueChange = { timeoutText = it },
            label = stringResource(Res.string.check_source_timeout),
            maxLength = 9,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spacingDefault)
                .padding(top = 8.dp),
        )
        Text(
            text = stringResource(Res.string.check_source_item),
            color = AppTheme.colors.accent,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = DesignTokens.spacingDefault, top = 8.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spacingDefault),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckItem(
                text = stringResource(Res.string.search),
                checked = checkSearch,
                modifier = Modifier.weight(1f),
            ) {
                checkSearch = it
                if (!checkSearch && !checkDiscovery) checkDiscovery = true
            }
            CheckItem(
                text = stringResource(Res.string.discovery),
                checked = checkDiscovery,
                modifier = Modifier.weight(1f),
            ) {
                checkDiscovery = it
                if (!checkSearch && !checkDiscovery) checkSearch = true
            }
            CheckItem(
                text = stringResource(Res.string.source_tab_info),
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
                text = stringResource(Res.string.chapter_list),
                checked = checkCategory,
                enabled = checkInfo,
                modifier = Modifier.weight(1f),
            ) {
                checkCategory = it
                if (!checkCategory) checkContent = false
            }
            CheckItem(
                text = stringResource(Res.string.main_body),
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
                .padding(horizontal = DesignTokens.spacingDefault),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextButton(text = stringResource(Res.string.cancel)) {
                onDismiss()
            }
            AppTextButton(text = stringResource(Res.string.ok)) {
                val text = timeoutText
                val minTimeout = 0L
                when {
                    text.isBlank() -> {
                        onToast("$timeoutStr$cannotEmptyStr")
                        return@AppTextButton
                    }

                    text.toLong() <= minTimeout -> {
                        onToast("$timeoutStr$lessThanStr${minTimeout}$secondsStr")
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
                PreferenceProviders.get().putString(PreferKey.checkSource, CheckSource.summary)
                onDismiss()
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
