package io.legado.app.ui.association

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add_group
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.custom_group_summary
import legado.shared.generated.resources.diy_edit_source_group
import legado.shared.generated.resources.diy_edit_source_group_title
import legado.shared.generated.resources.diy_source_group
import legado.shared.generated.resources.group_name
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.import_book_source
import legado.shared.generated.resources.import_replace_rule
import legado.shared.generated.resources.import_state_existing
import legado.shared.generated.resources.import_state_new
import legado.shared.generated.resources.import_state_update
import legado.shared.generated.resources.keep_enable
import legado.shared.generated.resources.keep_group
import legado.shared.generated.resources.keep_original_name
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.select_new_source
import legado.shared.generated.resources.select_update_source
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 列表项与本地库的比对状态 (对照 app 端 Import*Dialog itemState 三态文案)。 */
enum class ImportItemState { NEW, UPDATE, EXISTING }

/**
 * 勾选导入对话框 VM 适配接口, 抹平各 Import*ViewModelShared 的字段名差异
 * (allSources/allRules、checkSources/checkRules), 供 [ImportItemsDialog] 统一渲染。
 */
interface ImportItemsVm {
    /** 解析时算出的默认勾选, 只作 UI 勾选状态的初值 (勾选是纯 UI 瞬态状态, VM 不持有)。 */
    val defaultChecked: List<Boolean>
    fun itemLabel(index: Int): String
    fun itemState(index: Int): ImportItemState
    fun importSelect(checked: List<Boolean>, finally: () -> Unit)
    fun itemJson(index: Int): String

    /** CodeDialog 保存回写, 默认不支持 (对照 app 端 ImportTxtTocRuleDialog 无 Callback)。 */
    fun updateItemFromJson(index: Int, json: String): Boolean = false
}

/**
 * 通用勾选导入对话框 (iOS/鸿蒙用): 逐条复选 + 新增/更新/已有标记 + 全选/取消全选 +
 * 单条 CodeDialog 源码预览/编辑 + 确认后仅入库勾选项。
 * 交互语义对照 app 端 Import*Dialog, 容器样式对照 CodeDialog/DesktopImportDialog (MD2+Arco)。
 */
@Composable
fun ImportItemsDialog(
    title: String,
    vm: ImportItemsVm,
    onDismiss: () -> Unit,
    onImported: (selectCount: Int) -> Unit = {},
    // 解析进行中/解析失败态 (desktop 在对话框内触发下载+解析, 需透传给 ImportListScaffold;
    // iOS/鸿蒙在调用点解析完成后才弹本对话框, 走默认值)
    loading: Boolean = false,
    errorText: String? = null,
    // 容器 Surface 尺寸约束 (默认统一窗口尺寸, 调用方可覆盖)
    surfaceModifier: Modifier = Modifier.appDialogSize(),
    titleActions: @Composable RowScope.(checked: MutableList<Boolean>) -> Unit = {},
) {
    // 勾选是纯 UI 瞬态状态, 用 snapshot list 承载, 初值取 VM 解析时算出的默认勾选。
    // key 带默认勾选长度: deep link/桌面在对话框内解析, 条目是后到的
    val checked = remember(vm, vm.defaultChecked.size) { vm.defaultChecked.toMutableStateList() }
    var openIndex by remember { mutableStateOf<Int?>(null) }
    // 入库进行中 (对照 app 端 tvOk 点击先 WaitDialog.show, importSelect 回调里 dismissSafe)
    var importing by remember { mutableStateOf(false) }
    val stateNew = stringResource(Res.string.import_state_new)
    val stateUpdate = stringResource(Res.string.import_state_update)
    val stateExisting = stringResource(Res.string.import_state_existing)

    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = AppTheme.colors.fillet,
            modifier = surfaceModifier,
        ) {
            ImportListScaffold(
                title = title,
                loading = loading,
                errorText = errorText,
                itemCount = checked.size,
                selectCount = checked.count { it },
                isSelectAll = checked.all { it },
                itemLabel = { vm.itemLabel(it) },
                itemState = {
                    when (vm.itemState(it)) {
                        ImportItemState.NEW -> stateNew
                        ImportItemState.UPDATE -> stateUpdate
                        ImportItemState.EXISTING -> stateExisting
                    }
                },
                itemChecked = { checked[it] },
                onItemChecked = { index, isChecked -> checked[index] = isChecked },
                onOpen = { openIndex = it },
                onToggleAll = {
                    val selectAll = checked.all { it }
                    checked.indices.forEach { checked[it] = !selectAll }
                },
                onCancel = onDismiss,
                onOk = {
                    val count = checked.count { it }
                    importing = true
                    vm.importSelect(checked.toList()) {
                        importing = false
                        onImported(count)
                        onDismiss()
                    }
                },
                titleActions = { titleActions(checked) },
            )
        }
    }

    // 入库转圈 (对照 app 端 WaitDialog.show + onFinally dismissSafe); 返回键只收起提示,
    // 不取消入库协程, 与原版 WaitDialog 未设 onCancelListener 一致
    WaitDialog(visible = importing, onDismissRequest = { importing = false })

    openIndex?.let { index ->
        CodeDialog(
            code = vm.itemJson(index),
            disableEdit = false,
            onDismiss = { openIndex = null },
            onSave = { code ->
                vm.updateItemFromJson(index, code)
                openIndex = null
            },
        )
    }
}

/**
 * 书源勾选导入对话框，完整保留原版的选择新增/更新、自定义分组和保留属性选项。
 */
@Composable
fun ImportBookSourceItemsDialog(
    vm: ImportBookSourceViewModelShared,
    onDismiss: () -> Unit,
    onImported: (selectCount: Int) -> Unit = {},
    loading: Boolean = false,
    errorText: String? = null,
) {
    val adapter = remember(vm) { ImportBookSourceItemsVm(vm) }
    val config = AppConfigProviders.get()
    var showGroupDialog by remember { mutableStateOf(false) }
    // vm.groupName/isAddGroup 是普通 var, 读它们不会触发重组, 故镜像一份 Compose 状态驱动标题回显
    var groupName by remember(vm) { mutableStateOf(vm.groupName) }
    var isAddGroup by remember(vm) { mutableStateOf(vm.isAddGroup) }
    var keepName by remember { mutableStateOf(config.importKeepName) }
    var keepGroup by remember { mutableStateOf(config.importKeepGroup) }
    var keepEnable by remember { mutableStateOf(config.importKeepEnable) }
    ImportItemsDialog(
        title = stringResource(Res.string.import_book_source),
        vm = adapter,
        onDismiss = onDismiss,
        onImported = onImported,
        loading = loading,
        errorText = errorText,
        titleActions = { checked ->
            var showMenu by remember { mutableStateOf(false) }
            // 自定义源分组: 常显文本按钮 + 分组回显 (对照原版 import_source.xml showAsAction="always")
            val group = groupName?.takeIf { it.isNotBlank() }
            val groupTitle = if (group == null) {
                stringResource(Res.string.diy_source_group)
            } else {
                val name = stringResource(Res.string.diy_edit_source_group_title, group)
                if (isAddGroup) "+$name" else name
            }
            Text(
                text = groupTitle,
                color = AppTheme.colors.primaryText,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clickable { showGroupDialog = true }
                    .padding(horizontal = 8.dp),
            )
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_more_vert),
                        contentDescription = null,
                        tint = AppTheme.colors.primaryText,
                    )
                }
                AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(onClick = {
                        showMenu = false
                        adapter.selectNew(checked)
                    }) { Text(stringResource(Res.string.select_new_source)) }
                    DropdownMenuItem(onClick = {
                        showMenu = false
                        adapter.selectUpdate(checked)
                    }) { Text(stringResource(Res.string.select_update_source)) }
                    ImportOptionMenuItem(
                        text = stringResource(Res.string.keep_original_name),
                        checked = keepName,
                    ) {
                        keepName = !keepName
                        config.importKeepName = keepName
                    }
                    ImportOptionMenuItem(
                        text = stringResource(Res.string.keep_group),
                        checked = keepGroup,
                    ) {
                        keepGroup = !keepGroup
                        config.importKeepGroup = keepGroup
                    }
                    ImportOptionMenuItem(
                        text = stringResource(Res.string.keep_enable),
                        checked = keepEnable,
                    ) {
                        keepEnable = !keepEnable
                        config.importKeepEnable = keepEnable
                    }
                }
            }
        },
    )
    if (showGroupDialog) {
        ImportSourceGroupDialog(
            initialGroup = groupName.orEmpty(),
            initialAddGroup = isAddGroup,
            groupsProvider = { AppDbProviders.get().bookSourceDao.allGroups() },
            onConfirm = { group, addGroup ->
                groupName = group.ifBlank { null }
                isAddGroup = addGroup
                vm.groupName = groupName
                vm.isAddGroup = addGroup
                showGroupDialog = false
            },
            onDismiss = { showGroupDialog = false },
        )
    }
}

@Composable
private fun ImportOptionMenuItem(
    text: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppCheckbox(checked = checked, onCheckedChange = null)
            Text(
                text = text,
                color = AppTheme.colors.primaryText,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 替换规则勾选导入对话框, 保留原版的自定义分组菜单 (对照 app 端 ImportReplaceRuleDialog)。
 */
@Composable
fun ImportReplaceRuleItemsDialog(
    vm: ImportReplaceRuleViewModelShared,
    onDismiss: () -> Unit,
    onImported: (selectCount: Int) -> Unit = {},
    loading: Boolean = false,
    errorText: String? = null,
) {
    val adapter = remember(vm) { ImportReplaceRuleItemsVm(vm) }
    var showGroupDialog by remember { mutableStateOf(false) }
    // 同书源侧: vm 的普通 var 不触发重组, 镜像 Compose 状态驱动标题回显
    var groupName by remember(vm) { mutableStateOf(vm.groupName) }
    var isAddGroup by remember(vm) { mutableStateOf(vm.isAddGroup) }
    ImportItemsDialog(
        title = stringResource(Res.string.import_replace_rule),
        vm = adapter,
        onDismiss = onDismiss,
        onImported = onImported,
        loading = loading,
        errorText = errorText,
        titleActions = {
            val group = groupName?.takeIf { it.isNotBlank() }
            val groupTitle = if (group == null) {
                stringResource(Res.string.diy_source_group)
            } else {
                val name = stringResource(Res.string.diy_edit_source_group_title, group)
                if (isAddGroup) "+$name" else name
            }
            Text(
                text = groupTitle,
                color = AppTheme.colors.primaryText,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clickable { showGroupDialog = true }
                    .padding(horizontal = 8.dp),
            )
        },
    )
    if (showGroupDialog) {
        ImportSourceGroupDialog(
            initialGroup = groupName.orEmpty(),
            initialAddGroup = isAddGroup,
            groupsProvider = { AppDbProviders.get().replaceRuleDao.allGroups() },
            onConfirm = { group, addGroup ->
                groupName = group.ifBlank { null }
                isAddGroup = addGroup
                vm.groupName = groupName
                vm.isAddGroup = addGroup
                showGroupDialog = false
            },
            onDismiss = { showGroupDialog = false },
        )
    }
}

@Composable
internal fun ImportSourceGroupDialog(
    initialGroup: String,
    initialAddGroup: Boolean,
    groupsProvider: suspend () -> List<String>,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var groupName by remember(initialGroup) { mutableStateOf(initialGroup) }
    var addGroup by remember(initialAddGroup) { mutableStateOf(initialAddGroup) }
    // 已有分组候选 (对照 master alertCustomGroup 的 setFilterValues(allGroups())):
    // 书源取 bookSourceDao.allGroups(), 替换规则取 replaceRuleDao.allGroups(), 由调用方注入
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { groups = groupsProvider() }
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.diy_edit_source_group),
        okButton = AlertButton(stringResource(Res.string.ok)) {
            onConfirm(groupName.trim(), addGroup)
        },
        cancelButton = AlertButton(
            text = stringResource(Res.string.cancel),
            onClick = onDismiss,
        ),
        content = {
            Column(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.spacingDefault)) {
                // 开关块在输入框之上, 标题/副标题竖排占左 + 开关靠右
                // (对照原版 dialog_import_custom_group.xml 的 ConstraintLayout)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { addGroup = !addGroup }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.add_group),
                            color = AppTheme.colors.primaryText,
                        )
                        // 开关含义说明 (对照原版 tv_add_group_s)
                        Text(
                            text = stringResource(Res.string.custom_group_summary),
                            color = AppTheme.colors.secondaryText,
                        )
                    }
                    AppSwitch(
                        checked = addGroup,
                        onCheckedChange = null,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                // 自动补全输入框: 聚焦/输入时弹已有分组候选下拉 (对照 master editView.setFilterValues)
                AppAutoCompleteField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = stringResource(Res.string.group_name),
                    values = groups,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** 书源适配器 (对照 app 端 ImportBookSourceDialog: lastUpdateTime 比对 + 选新增/选更新)。 */
class ImportBookSourceItemsVm(
    private val vm: ImportBookSourceViewModelShared,
) : ImportItemsVm {
    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun itemLabel(index: Int): String = vm.allSources[index].bookSourceName

    override fun itemState(index: Int): ImportItemState {
        val local = vm.checkSources[index]
        return when {
            local == null -> ImportItemState.NEW
            vm.allSources[index].lastUpdateTime > local.lastUpdateTime -> ImportItemState.UPDATE
            else -> ImportItemState.EXISTING
        }
    }

    /** 对照 app 端 selectNew: 全选/取消全选新增项。 */
    fun selectNew(checked: MutableList<Boolean>) {
        val status = vm.newSourceStatus
        val selectAllNew = status.indices.all { !status[it] || checked[it] }
        status.forEachIndexed { index, b ->
            if (b) checked[index] = !selectAllNew
        }
    }

    /** 对照 app 端 selectUpdate: 全选/取消全选更新项。 */
    fun selectUpdate(checked: MutableList<Boolean>) {
        val status = vm.updateSourceStatus
        val selectAllUpdate = status.indices.all { !status[it] || checked[it] }
        status.forEachIndexed { index, b ->
            if (b) checked[index] = !selectAllUpdate
        }
    }

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])

    override fun updateItemFromJson(index: Int, json: String): Boolean =
        GSON.fromJsonObject<BookSource>(json).getOrNull()?.let {
            vm.allSources[index] = it
            true
        } ?: false
}

/** 替换规则适配器 (对照 app 端 ImportReplaceRuleDialog: pattern/replacement/isRegex/scope 比对)。 */
class ImportReplaceRuleItemsVm(
    private val vm: ImportReplaceRuleViewModelShared,
) : ImportItemsVm {
    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun itemLabel(index: Int): String {
        val item = vm.allRules[index]
        return if (item.group.isNullOrBlank()) item.name else "${item.name}(${item.group})"
    }

    override fun itemState(index: Int): ImportItemState {
        val local = vm.checkRules[index]
        val item = vm.allRules[index]
        return when {
            local == null -> ImportItemState.NEW
            item.pattern != local.pattern
                || item.replacement != local.replacement
                || item.isRegex != local.isRegex
                || item.scope != local.scope -> ImportItemState.UPDATE

            else -> ImportItemState.EXISTING
        }
    }

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allRules[index])

    override fun updateItemFromJson(index: Int, json: String): Boolean =
        GSON.fromJsonObject<ReplaceRule>(json).getOrNull()?.let {
            vm.allRules[index] = it
            true
        } ?: false
}

/** TXT 目录规则适配器 (对照 app 端 ImportTxtTocRuleDialog: 整体 != 比对, 无保存回写)。 */
class ImportTxtTocRuleItemsVm(
    private val vm: ImportTxtTocRuleViewModelShared,
) : ImportItemsVm {
    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun itemLabel(index: Int): String = vm.allSources[index].name

    override fun itemState(index: Int): ImportItemState {
        val local = vm.checkSources[index]
        return when {
            local == null -> ImportItemState.NEW
            vm.allSources[index] != local -> ImportItemState.UPDATE
            else -> ImportItemState.EXISTING
        }
    }

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])
}

/** 字典规则适配器 (对照 app 端 ImportDictRuleDialog: 仅 新增/已有 两态)。 */
class ImportDictRuleItemsVm(
    private val vm: ImportDictRuleViewModelShared,
) : ImportItemsVm {
    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun itemLabel(index: Int): String = vm.allSources[index].name

    override fun itemState(index: Int): ImportItemState =
        if (vm.checkSources[index] == null) ImportItemState.NEW else ImportItemState.EXISTING

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])

    override fun updateItemFromJson(index: Int, json: String): Boolean =
        GSON.fromJsonObject<DictRule>(json).getOrNull()?.let {
            vm.allSources[index] = it
            true
        } ?: false
}

/** 语音源适配器 (对照 app 端 ImportHttpTtsDialog: lastUpdateTime 比对, CodeDialog 可编辑回写)。 */
class ImportHttpTtsItemsVm(
    private val vm: ImportHttpTtsViewModelShared,
) : ImportItemsVm {
    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun itemLabel(index: Int): String = vm.allSources[index].name

    override fun itemState(index: Int): ImportItemState {
        val local = vm.checkSources[index]
        return when {
            local == null -> ImportItemState.NEW
            vm.allSources[index].lastUpdateTime > local.lastUpdateTime -> ImportItemState.UPDATE
            else -> ImportItemState.EXISTING
        }
    }

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])

    override fun updateItemFromJson(index: Int, json: String): Boolean =
        GSON.fromJsonObject<HttpTTS>(json).getOrNull()?.let {
            vm.allSources[index] = it
            true
        } ?: false
}

/** 主题配置适配器 (对照 app 端 ImportThemeDialog: 整体 != 比对, 无 CodeDialog.Callback 不回写)。 */
class ImportThemeItemsVm(
    private val vm: ImportThemeViewModelShared,
) : ImportItemsVm {
    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun itemLabel(index: Int): String = vm.allSources[index].themeName

    override fun itemState(index: Int): ImportItemState {
        val local = vm.checkSources[index]
        return when {
            local == null -> ImportItemState.NEW
            vm.allSources[index] != local -> ImportItemState.UPDATE
            else -> ImportItemState.EXISTING
        }
    }

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])
}

/** 屏蔽规则适配器 (对照 app 端 ImportSourceFilterRuleDialog: pattern/fields/scope 比对)。 */
class ImportSourceFilterRuleItemsVm(
    private val vm: ImportSourceFilterRuleViewModelShared,
) : ImportItemsVm {
    override val defaultChecked: List<Boolean> get() = vm.defaultChecked

    override fun itemLabel(index: Int): String {
        val item = vm.allRules[index]
        return item.name.ifEmpty { item.pattern }
    }

    override fun itemState(index: Int): ImportItemState {
        val local = vm.checkRules[index]
        val item = vm.allRules[index]
        return when {
            local == null -> ImportItemState.NEW
            item.pattern != local.pattern
                || item.fields != local.fields
                || item.scope != local.scope -> ImportItemState.UPDATE

            else -> ImportItemState.EXISTING
        }
    }

    override fun importSelect(checked: List<Boolean>, finally: () -> Unit) =
        vm.importSelect(checked, finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allRules[index])

    override fun updateItemFromJson(index: Int, json: String): Boolean =
        GSON.fromJsonObject<SourceFilterRule>(json).getOrNull()?.let {
            vm.allRules[index] = it
            true
        } ?: false
}
