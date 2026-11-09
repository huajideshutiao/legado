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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlin.concurrent.Volatile
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.coroutines.launch
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
    /** 本次打开的稳定身份 (仅用于 UI 状态/作用域隔离, 不持久化; 刷新 copy 保持同值) */
    val requestId: Long = 0,
    val loading: Boolean = true,
    /** 平台读取配置失败 (区别于"确无配置项"), UI 显示错误文案而非空表 */
    val failed: Boolean = false,
    val items: List<MangaPrefItem> = emptyList(),
)

/**
 * 插件自带配置弹窗: 渲染平台侧从 shim `PreferenceScreen` 降级来的 [MangaPrefItem] 列表。
 *
 * 配置行交互按 androidx: 点击 → [onPreferenceClick] (平台侧 `Preference.performClick()`);
 * 开关拨动 → [onPreferenceChange] (平台侧 `callChangeListener` + `setChecked`); 真实文本项
 * ([MangaPrefItem.editableText]) 走单一点击链 → [onTextItemClick] (平台侧完成 performClick
 * 并返回活编辑会话), 编辑弹窗由平台槽承载 (Android 真实控件/桌面兼容层活适配, 插件的单行/
 * 多行声明、hint、动态校验与提交拦截都在活会话内生效); 其余文本项与无 key 项保持既有点击
 * 转发与快照编辑; 选择/输入完成后的值写入经 [onSetPreference] 回平台 (平台侧走 shim setter,
 * 持久化规则与 androidx 一致)。
 *
 * 管理页行内「设置」入口与虚拟源登录直达 ([io.legado.app.ui.root.ExtensionPrefOverlayContent])
 * 共用本弹窗; 两级输入/复选状态在弹窗内部持有, 调用方只供 [dialog] 快照与回调。
 */
@Composable
internal fun ExtensionPrefDialog(
    dialog: MangaPrefDialogState,
    settingText: String,
    onSetPreference: (MangaPrefItem, MangaPrefValue) -> Unit,
    onPreferenceClick: (MangaPrefItem) -> Unit = {},
    onPreferenceChange: (MangaPrefItem, Boolean) -> Unit = { _, _ -> },
    /** 真实文本项点击: 平台完成 performClick 并返回活编辑会话 (null = 无可用会话)。 */
    onTextItemClick: suspend (MangaPrefItem) -> MangaPrefTextSession? = { null },
    /** 活编辑会话落值后重读刷新弹窗当前值 (入口各自实现)。 */
    onReloadPrefItems: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    // 一次打开一个稳定身份: 重开/换包名即作废旧的编辑状态与协程作用域
    key(dialog.requestId, dialog.pkgName) {
        ExtensionPrefDialogContent(
            dialog = dialog,
            settingText = settingText,
            onSetPreference = onSetPreference,
            onPreferenceClick = onPreferenceClick,
            onPreferenceChange = onPreferenceChange,
            onTextItemClick = onTextItemClick,
            onReloadPrefItems = onReloadPrefItems,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ExtensionPrefDialogContent(
    dialog: MangaPrefDialogState,
    settingText: String,
    onSetPreference: (MangaPrefItem, MangaPrefValue) -> Unit,
    onPreferenceClick: (MangaPrefItem) -> Unit,
    onPreferenceChange: (MangaPrefItem, Boolean) -> Unit,
    onTextItemClick: suspend (MangaPrefItem) -> MangaPrefTextSession?,
    onReloadPrefItems: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val emptyText = stringResource(Res.string.manga_extension_pref_empty)
    val errorText = stringResource(Res.string.manga_extension_pref_error)
    val okText = stringResource(Res.string.ok)
    val cancelText = stringResource(Res.string.cancel)

    // 真实文本项的活编辑会话 (点击路径取得, 持本次实际配置对象)
    var textSession by remember(dialog.pkgName) { mutableStateOf<MangaPrefTextSession?>(null) }
    // 通用降级项的快照编辑目标 (既有交互原状)
    var fallbackEditing by remember(dialog.pkgName) { mutableStateOf<MangaPrefItem?>(null) }
    // MultiSelectListPreference 二级复选待写项
    var multiSelect by remember(dialog.pkgName) { mutableStateOf<MangaPrefItem?>(null) }
    val scope = rememberCoroutineScope()

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = settingText,
        okButton = AlertButton(text = okText, onClick = onDismiss),
    ) {
        when {
            dialog.loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = DesignTokens.spacingXl),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            dialog.failed -> Text(
                text = errorText,
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = DesignTokens.spacingLg),
            )

            dialog.items.isEmpty() -> Text(
                text = emptyText,
                color = colors.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = DesignTokens.spacingLg),
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
                        onPickChoice = { value ->
                            if (item.key != null) onSetPreference(item, MangaPrefValue.Choice(value))
                        },
                        onTextItemClick = { item ->
                            scope.launch {
                                val session = onTextItemClick(item)
                                if (session != null) textSession = session
                            }
                        },
                        onFallbackEdit = { fallbackEditing = it },
                        onEditMulti = { multiSelect = item },
                    )
                }
            }
        }
    }

    textSession?.let { session ->
        // 槽随桥在同端装配序列注册, 会话在则槽在 (缺失即宿主装配错误, 不伪回落)
        val editorSlot = requireNotNull(MangaPrefTextEditorSlot.editor)
        editorSlot(
            MangaPrefTextEditorRequest(
                session = session,
                onSettled = { committed ->
                    if (committed) onReloadPrefItems()
                    textSession = null
                },
                onDismiss = { textSession = null },
            )
        )
    }

    fallbackEditing?.let { item ->
        val initial = (item.value as? MangaPrefValue.Text)?.value.orEmpty()
        var text by remember(item) { mutableStateOf(initial) }
        AppAlertDialog(
            onDismissRequest = { fallbackEditing = null },
            okButton = AlertButton(text = okText) {
                onSetPreference(item, MangaPrefValue.Text(text))
                fallbackEditing = null
            },
            cancelButton = AlertButton(text = cancelText) { fallbackEditing = null },
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
                if (item.key != null) onSetPreference(item, MangaPrefValue.MultiChoice(picked))
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
                            .padding(vertical = DesignTokens.spacingDefault),
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
 * 配置项单行: 开关行标题/摘要居左、开关居右; 其余类型标题/摘要在上。
 *
 * 行内不渲染当前值 (Mihon 的插件设置页即 androidx `Preference` 列表, 只渲染 title/summary;
 * 当前值只能由扩展自设 summary 的表达承载)。整行可点: 真实文本项走活编辑会话, 其余类型
 * 上报 [onPreferenceClick] (平台侧 `performClick()`) 并按值类型打开对应的二级弹窗 (弹窗内才
 * 显示已存值); 开关拨动上报 [onPreferenceChange]。
 */
@Composable
internal fun PrefItemRow(
    item: MangaPrefItem,
    onPreferenceClick: () -> Unit,
    onPreferenceChange: (Boolean) -> Unit,
    onPickChoice: (String) -> Unit,
    onTextItemClick: (MangaPrefItem) -> Unit,
    onFallbackEdit: (MangaPrefItem) -> Unit,
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
                .padding(vertical = DesignTokens.spacingDefault),
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
                when (val v = item.value) {
                    is MangaPrefValue.Text -> when {
                        item.key != null && item.editableText -> onTextItemClick(item)
                        item.key != null -> {
                            onPreferenceClick()
                            onFallbackEdit(item)
                        }
                        else -> onPreferenceClick()
                    }

                    else -> {
                        onPreferenceClick()
                        when (v) {
                            is MangaPrefValue.Choice -> choiceExpanded = true
                            is MangaPrefValue.MultiChoice -> onEditMulti()
                            else -> {}
                        }
                    }
                }
            }
            .padding(vertical = DesignTokens.spacingDefault),
    ) {
        header()
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
                        .padding(vertical = DesignTokens.spacingDefault),
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

/**
 * 插件文本项编辑弹窗的平台槽: Android 注入真实控件承载 (Mihon 同款), 桌面注入兼容层活适配
 * 承载; 槽随桥在同端装配序列注册, 未装配端 (iOS/鸿蒙, 「设置」入口本就隐藏) 不会产生会话。
 *
 * request 只带活会话与回调 (会话已持实际配置对象身份); 平台控件、插件绑定监听与提交细节
 * 全部留在槽实现内, 跨端 UI 不接触平台 View/控件类型。
 */
object MangaPrefTextEditorSlot {

    @Volatile
    var editor: (@Composable (MangaPrefTextEditorRequest) -> Unit)? = null
}

/**
 * 编辑弹窗请求 (shared 数据面)。
 *
 * @param session 点击路径取得的活编辑会话 (持本次实际配置对象)
 * @param onSettled 确认提交完成回调 (committed = 会话落值成功; 被插件拦截为 false), 入口据此
 *              重读刷新; 提交异常记录日志后直接走 [onDismiss] 关闭
 * @param onDismiss 关闭回调 (取消按钮/点外/提交故障), 会话与控件随之弃置
 */
class MangaPrefTextEditorRequest(
    val session: MangaPrefTextSession,
    val onSettled: (committed: Boolean) -> Unit,
    val onDismiss: () -> Unit,
)
