package io.legado.desktop.ui.config

import androidx.compose.foundation.layout.widthIn
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.desktop.ui.component.DialogSizes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Surface
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
import androidx.compose.ui.window.Dialog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CheckSourceShared
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppNumberField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 桌面端"校验设置" Compose Dialog (MY 页 → 其他设置 → 校验设置入口)。
 *
 * 对照 app 端 [io.legado.app.ui.config.CheckSourceConfig] (继承
 * [io.legado.app.base.BaseComposeDialogFragment] 的全屏 Dialog Fragment), 桌面端无
 * Fragment/Activity, 改为纯 Compose [Dialog] (参考 [DirectLinkUploadConfigDialog])。
 * 表单结构 (1 个超时输入 + 5 个级联复选框 + 取消/确定) 与校验/保存逻辑逐字保留, 仅做平台适配。
 *
 * # 平台适配 (与 app 端差异)
 *
 * - **配置读写**: app 端 `CheckSource` object → 桌面端 [CheckSourceShared] (shared commonMain 下沉版)
 * - **summary 持久化**: app 端 `AppConfig.checkSource = CheckSource.summary` → 桌面端
 *   [PreferenceProviders].get().putString(PreferKey.checkSource, summary) (落 java.util.prefs)
 * - **Toast**: app 端 `toastOnUi` → 桌面端 [Toasters.get].toast
 * - **文案**: app 端 `R.string.xxx` → 桌面端 [rememberString]`("xxx")`
 * - **关闭**: app 端 `dismiss()` → 桌面端 `onDismiss()`
 *
 * @param onDismiss 关闭回调 (由 [DesktopOtherConfigScreen] 的 onCheckSource 触发显隐)
 */
@Composable
fun CheckSourceConfigDialog(onDismiss: () -> Unit) {
    val colors = AppTheme.colors

    val titleText = rememberString("check_source_config")
    val timeoutLabel = rememberString("check_source_timeout")
    val itemLabel = rememberString("check_source_item")
    val searchText = rememberString("search")
    val discoveryText = rememberString("discovery")
    val infoText = rememberString("source_tab_info")
    val categoryText = rememberString("chapter_list")
    val contentText = rememberString("main_body")
    val cancelText = rememberString("cancel")
    val okText = rememberString("ok")
    val timeoutTitleText = rememberString("timeout")
    val cannotEmptyText = rememberString("cannot_empty")
    val lessThanText = rememberString("less_than")
    val secondsText = rememberString("seconds")

    // 允许的最小超时时间, 秒 (对照 app 端 CheckSourceConfig.minTimeout)
    val minTimeout = 0L

    // state 初始化自 CheckSourceShared (对照 app 端 rememberSaveable)
    var timeoutText by remember { mutableStateOf((CheckSourceShared.timeout / 1000).toString()) }
    var checkSearch by remember { mutableStateOf(CheckSourceShared.checkSearch) }
    var checkDiscovery by remember { mutableStateOf(CheckSourceShared.checkDiscovery) }
    var checkInfo by remember { mutableStateOf(CheckSourceShared.checkInfo) }
    var checkCategory by remember { mutableStateOf(CheckSourceShared.checkCategory) }
    var checkContent by remember { mutableStateOf(CheckSourceShared.checkContent) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.fillMaxWidth().widthIn(max = DialogSizes.dialogMaxWidth()),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = titleText,
                    onBack = onDismiss,
                )
                AppNumberField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it },
                    label = timeoutLabel,
                    maxLength = 9,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(top = 8.dp),
                )
                Text(
                    text = itemLabel,
                    color = colors.accent,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 搜索/发现至少保留一项 (复刻 app 端 onClick 联动)
                    CheckItem(
                        text = searchText,
                        checked = checkSearch,
                        modifier = Modifier.weight(1f),
                    ) {
                        checkSearch = it
                        if (!checkSearch && !checkDiscovery) checkDiscovery = true
                    }
                    CheckItem(
                        text = discoveryText,
                        checked = checkDiscovery,
                        modifier = Modifier.weight(1f),
                    ) {
                        checkDiscovery = it
                        if (!checkSearch && !checkDiscovery) checkSearch = true
                    }
                    // 详情关闭时级联关闭并禁用 目录/正文; 目录关闭时级联关闭并禁用 正文
                    CheckItem(
                        text = infoText,
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
                        text = categoryText,
                        checked = checkCategory,
                        enabled = checkInfo,
                        modifier = Modifier.weight(1f),
                    ) {
                        checkCategory = it
                        if (!checkCategory) checkContent = false
                    }
                    CheckItem(
                        text = contentText,
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
                    AppTextButton(text = cancelText) { onDismiss() }
                    AppTextButton(text = okText) {
                        // 校验超时值 (对照 app 端 okButton onClick, AppNumberField 已过滤非数字)
                        val text = timeoutText
                        when {
                            text.isBlank() -> {
                                Toasters.get().toast("$timeoutTitleText$cannotEmptyText")
                                return@AppTextButton
                            }

                            text.toLong() <= minTimeout -> {
                                Toasters.get().toast(
                                    "$timeoutTitleText$lessThanText$minTimeout$secondsText"
                                )
                                return@AppTextButton
                            }

                            else -> CheckSourceShared.timeout = text.toLong() * 1000
                        }
                        CheckSourceShared.checkSearch = checkSearch
                        CheckSourceShared.checkDiscovery = checkDiscovery
                        CheckSourceShared.checkInfo = checkInfo
                        CheckSourceShared.checkCategory = checkCategory
                        CheckSourceShared.checkContent = checkContent
                        CheckSourceShared.putConfig()
                        // 写 summary 到 prefs (对照 app 端 AppConfig.checkSource = CheckSource.summary)
                        PreferenceProviders.get()
                            .putString(PreferKey.checkSource, CheckSourceShared.summary)
                        onDismiss()
                    }
                }
            }
        }
    }
}

/**
 * 校验项勾选行 (对照 app 端 CheckSourceConfig.CheckItem)。
 *
 * [AppCheckbox] onCheckedChange=null, toggle 由外层 Row 承接 (与 app 端一致)。
 */
@Composable
private fun CheckItem(
    text: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = AppTheme.colors
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
            color = if (enabled) colors.primaryText
            else colors.secondaryText.copy(alpha = 0.5f),
            fontSize = 12.sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
    }
}
