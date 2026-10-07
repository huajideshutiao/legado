package io.legado.app.ui.book.manga.extension

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.manga_extension_pref_empty
import legado.ui.generated.resources.manga_extension_pref_error
import legado.ui.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * 插件自带配置对话框状态: 打开即 loading, 平台读完 shim PreferenceScreen 后回填 [items]。
 *
 * 状态持有方两个: 插件管理页 (MangaExtensionScreenModel.prefDialog) 与虚拟源登录直达
 * Overlay ([io.legado.app.ui.root.ExtensionPrefOverlayContent]) 各自自管, 共用本文件渲染。
 */
data class MangaPrefDialogState(
    val pkgName: String,
    val loading: Boolean = true,
    /** 平台读取配置失败 (区别于"确无配置项"), UI 显示错误文案而非空表 */
    val failed: Boolean = false,
    val items: List<MangaPrefItem> = emptyList(),
)

/**
 * 插件自带配置弹窗: 渲染平台侧从 shim `PreferenceScreen` 降级来的 [MangaPrefItem] 列表。
 *
 * 配置行交互按 androidx: 点击 → [onPreferenceClick] (平台侧 `Preference.performClick()`);
 * 开关拨动 → [onPreferenceChange] (平台侧 `callChangeListener` + `setChecked`); 文本行打开输入框时
 * [onBindEditText] (对应 `EditTextPreference.OnBindEditTextListener` 的触发时机)。
 * 选择/输入完成后的值写入经 [onSetPreference] 回平台 (平台侧走 shim setter, 持久化规则与 androidx 一致)。
 *
 * 管理页行内「设置」入口与虚拟源登录直达 ([io.legado.app.ui.root.ExtensionPrefOverlayContent])
 * 共用本弹窗; 两级输入/复选状态在弹窗内部持有, 调用方只供 [dialog] 快照与回调。
 */
@Composable
internal fun ExtensionPrefDialog(
    dialog: MangaPrefDialogState,
    settingText: String,
    onSetPreference: (String, MangaPrefValue) -> Unit,
    onPreferenceClick: (MangaPrefItem) -> Unit = {},
    onPreferenceChange: (MangaPrefItem, Boolean) -> Unit = { _, _ -> },
    onBindEditText: (MangaPrefItem) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val emptyText = stringResource(Res.string.manga_extension_pref_empty)
    val errorText = stringResource(Res.string.manga_extension_pref_error)
    val okText = stringResource(Res.string.ok)
    val cancelText = stringResource(Res.string.cancel)

    // EditText 二级输入框待写项 (key + 初始文本)
    var editing by remember(dialog.pkgName) { mutableStateOf<Pair<String, String>?>(null) }
    // MultiSelectListPreference 二级复选待写项
    var multiSelect by remember(dialog.pkgName) { mutableStateOf<MangaPrefItem?>(null) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = settingText,
        okButton = AlertButton(text = okText, onClick = onDismiss),
    ) {
        when {
            dialog.loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(DesignTokens.spacingXl),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            dialog.failed -> Text(
                text = errorText,
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(DesignTokens.spacingLg),
            )

            dialog.items.isEmpty() -> Text(
                text = emptyText,
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(DesignTokens.spacingLg),
            )

            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                dialog.items.forEach { item ->
                    PrefItemRow(
                        item = item,
                        onPreferenceClick = { onPreferenceClick(item) },
                        onPreferenceChange = { checked -> onPreferenceChange(item, checked) },
                        onBindEditText = { onBindEditText(item) },
                        onPickChoice = { value ->
                            item.key?.let { onSetPreference(it, MangaPrefValue.Choice(value)) }
                        },
                        onEditText = { current ->
                            item.key?.let { editing = it to current }
                        },
                        onEditMulti = { multiSelect = item },
                    )
                }
            }
        }
    }

    editing?.let { (key, initial) ->
        var text by remember(key) { mutableStateOf(initial) }
        AppAlertDialog(
            onDismissRequest = { editing = null },
            okButton = AlertButton(text = okText) {
                onSetPreference(key, MangaPrefValue.Text(text))
                editing = null
            },
            cancelButton = AlertButton(text = cancelText) { editing = null },
        ) {
            AppTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    multiSelect?.let { item ->
        val current = (item.value as? MangaPrefValue.MultiChoice)?.values.orEmpty()
        var picked by remember(item.key) { mutableStateOf(current) }
        AppAlertDialog(
            onDismissRequest = { multiSelect = null },
            title = item.title,
            okButton = AlertButton(text = okText) {
                item.key?.let { onSetPreference(it, MangaPrefValue.MultiChoice(picked)) }
                multiSelect = null
            },
            cancelButton = AlertButton(text = cancelText) { multiSelect = null },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                item.entries.forEachIndexed { index, entry ->
                    val value = item.entryValues.getOrNull(index) ?: entry
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                picked = if (value in picked) picked - value else picked + value
                            }
                            .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(
                            checked = value in picked,
                            onCheckedChange = { checked ->
                                picked = if (checked) picked + value else picked - value
                            },
                        )
                        Spacer(Modifier.width(DesignTokens.spacingMd))
                        Text(entry, color = colors.primaryText, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/**
 * 配置项单行: 开关行标题/摘要居左、开关居右; 其余类型标题/摘要在上、当前值或控件在下
 * (长摘要与长值左右排会互相挤压)。整行可点, 点击上报 [onPreferenceClick] (平台侧
 * `performClick()`), 并按值类型打开对应的二级弹窗; 开关拨动上报 [onPreferenceChange]。
 */
@Composable
internal fun PrefItemRow(
    item: MangaPrefItem,
    onPreferenceClick: () -> Unit,
    onPreferenceChange: (Boolean) -> Unit,
    onBindEditText: () -> Unit,
    onPickChoice: (String) -> Unit,
    onEditText: (String) -> Unit,
    onEditMulti: () -> Unit,
) {
    val colors = AppTheme.colors
    var choiceExpanded by remember(item.key) { mutableStateOf(false) }
    val enabled = item.enabled
    val summary = item.displaySummary()

    // 标题 + 摘要 (开关行居左, 其余行居上, 同一段渲染)
    val header: @Composable ColumnScope.() -> Unit = {
        Text(
            text = item.title,
            color = colors.primaryText,
            fontSize = 15.sp,
        )
        summary?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, color = colors.secondaryText, fontSize = 12.sp)
        }
    }

    val flag = item.value as? MangaPrefValue.Flag
    if (flag != null) {
        // 对齐 androidx: 点击行 → onClickListener 或默认 onClick (开关类即切换);
        // 拨动开关 → callChangeListener(newValue), 通过才落值
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onPreferenceClick() }
                .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), content = header)
            Spacer(Modifier.width(DesignTokens.spacingMd))
            AppSwitch(
                checked = flag.value,
                onCheckedChange = { checked -> onPreferenceChange(checked) },
                enabled = enabled,
            )
        }
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                // 先上报点击 (平台侧 performClick), 再按值类型打开宿主侧的二级弹窗
                onPreferenceClick()
                when (val v = item.value) {
                    is MangaPrefValue.Choice -> choiceExpanded = true
                    is MangaPrefValue.MultiChoice -> onEditMulti()
                    is MangaPrefValue.Text -> {
                        // 输入框对话框绑定时机 = androidx 的 OnBindEditTextListener
                        onBindEditText()
                        onEditText(v.value)
                    }

                    else -> {}
                }
            }
            .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
    ) {
        header()
        when (val value = item.value) {
            is MangaPrefValue.Choice -> {
                Text(
                    text = item.selectedEntryText() ?: value.value,
                    color = if (enabled) colors.accent else colors.textDisabled,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = DesignTokens.spacingXs),
                )
                if (choiceExpanded) {
                    PrefChoiceDialog(
                        item = item,
                        onDismiss = { choiceExpanded = false },
                        onPick = { picked ->
                            onPickChoice(picked)
                            choiceExpanded = false
                        },
                    )
                }
            }

            is MangaPrefValue.MultiChoice -> Text(
                text = (item.selectedEntryText() ?: "").ifBlank { "—" },
                color = if (enabled) colors.accent else colors.textDisabled,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = DesignTokens.spacingXs),
            )

            is MangaPrefValue.Text -> Text(
                text = value.value.ifBlank { "—" },
                color = if (enabled) colors.accent else colors.textDisabled,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = DesignTokens.spacingXs),
            )

            else -> {}
        }
    }
}

/** ListPreference 单选弹窗 (radio 列表)。 */
@Composable
internal fun PrefChoiceDialog(
    item: MangaPrefItem,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val selected = (item.value as? MangaPrefValue.Choice)?.value
    AppAlertDialog(onDismissRequest = onDismiss, title = item.title) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            item.entries.forEachIndexed { index, entry ->
                val value = item.entryValues.getOrNull(index) ?: entry
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(value) }
                        .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppRadioButton(selected = value == selected, onClick = null)
                    Spacer(Modifier.width(DesignTokens.spacingMd))
                    Text(entry, color = colors.primaryText, fontSize = 15.sp)
                }
            }
        }
    }
}
