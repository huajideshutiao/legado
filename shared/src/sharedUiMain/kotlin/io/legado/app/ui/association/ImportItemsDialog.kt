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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.ThemeConfigData
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add_to_group
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.diy_edit_source_group
import legado.shared.generated.resources.diy_source_group
import legado.shared.generated.resources.group_name
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.import_book_source
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
    val itemCount: Int
    val selectCount: Int
    val isSelectAll: Boolean
    fun itemLabel(index: Int): String
    fun itemState(index: Int): ImportItemState
    fun itemChecked(index: Int): Boolean
    fun setItemChecked(index: Int, checked: Boolean)
    fun toggleAll()
    fun importSelect(finally: () -> Unit)
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
    titleActions: @Composable RowScope.(invalidate: () -> Unit) -> Unit = {},
) {
    // selectStatus 是普通 ArrayList, 修改不触发重组, 用 version 自增驱动 (与 app 端一致)
    var version by remember { mutableIntStateOf(0) }
    var openIndex by remember { mutableStateOf<Int?>(null) }
    @Suppress("UNUSED_EXPRESSION")
    version
    val stateNew = stringResource(Res.string.import_state_new)
    val stateUpdate = stringResource(Res.string.import_state_update)
    val stateExisting = stringResource(Res.string.import_state_existing)

    Dialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = AppTheme.colors.background,
            modifier = surfaceModifier,
        ) {
            ImportListScaffold(
                title = title,
                loading = loading,
                errorText = errorText,
                itemCount = vm.itemCount,
                selectCount = vm.selectCount,
                isSelectAll = vm.isSelectAll,
                itemLabel = { vm.itemLabel(it) },
                itemState = {
                    when (vm.itemState(it)) {
                        ImportItemState.NEW -> stateNew
                        ImportItemState.UPDATE -> stateUpdate
                        ImportItemState.EXISTING -> stateExisting
                    }
                },
                itemChecked = { vm.itemChecked(it) },
                onItemChecked = { index, checked ->
                    vm.setItemChecked(index, checked)
                    version++
                },
                onOpen = { openIndex = it },
                onToggleAll = {
                    vm.toggleAll()
                    version++
                },
                onCancel = onDismiss,
                onOk = {
                    val count = vm.selectCount
                    vm.importSelect {
                        onImported(count)
                        onDismiss()
                    }
                },
                titleActions = { titleActions { version++ } },
            )
        }
    }

    openIndex?.let { index ->
        CodeDialog(
            code = vm.itemJson(index),
            disableEdit = false,
            onDismiss = { openIndex = null },
            onSave = { code ->
                if (vm.updateItemFromJson(index, code)) version++
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
) {
    val adapter = remember(vm) { ImportBookSourceItemsVm(vm) }
    val config = AppConfigProviders.get()
    var showGroupDialog by remember { mutableStateOf(false) }
    var keepName by remember { mutableStateOf(config.importKeepName) }
    var keepGroup by remember { mutableStateOf(config.importKeepGroup) }
    var keepEnable by remember { mutableStateOf(config.importKeepEnable) }
    ImportItemsDialog(
        title = stringResource(Res.string.import_book_source),
        vm = adapter,
        onDismiss = onDismiss,
        onImported = onImported,
        titleActions = { invalidate ->
            var showMenu by remember { mutableStateOf(false) }
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
                        showGroupDialog = true
                    }) {
                        val group = vm.groupName?.takeIf { it.isNotBlank() }
                        val title = if (group == null) {
                            stringResource(Res.string.diy_source_group)
                        } else if (vm.isAddGroup) {
                            "+${stringResource(Res.string.diy_edit_source_group)}: $group"
                        } else {
                            "${stringResource(Res.string.diy_edit_source_group)}: $group"
                        }
                        Text(title)
                    }
                    DropdownMenuItem(onClick = {
                        showMenu = false
                        adapter.selectNew()
                        invalidate()
                    }) { Text(stringResource(Res.string.select_new_source)) }
                    DropdownMenuItem(onClick = {
                        showMenu = false
                        adapter.selectUpdate()
                        invalidate()
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
            initialGroup = vm.groupName.orEmpty(),
            initialAddGroup = vm.isAddGroup,
            onConfirm = { groupName, addGroup ->
                vm.groupName = groupName.ifBlank { null }
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

@Composable
private fun ImportSourceGroupDialog(
    initialGroup: String,
    initialAddGroup: Boolean,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var groupName by remember(initialGroup) { mutableStateOf(initialGroup) }
    var addGroup by remember(initialAddGroup) { mutableStateOf(initialAddGroup) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.diy_source_group),
        okButton = AlertButton(stringResource(Res.string.ok)) {
            onConfirm(groupName.trim(), addGroup)
        },
        cancelButton = AlertButton(
            text = stringResource(Res.string.cancel),
            onClick = onDismiss,
        ),
        content = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                AppOutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = stringResource(Res.string.group_name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { addGroup = !addGroup }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCheckbox(checked = addGroup, onCheckedChange = null)
                    Text(
                        text = stringResource(Res.string.add_to_group),
                        color = AppTheme.colors.primaryText,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
    )
}

/** 书源适配器 (对照 app 端 ImportBookSourceDialog: lastUpdateTime 比对 + 选新增/选更新)。 */
class ImportBookSourceItemsVm(
    private val vm: ImportBookSourceViewModelShared,
) : ImportItemsVm {
    override val itemCount: Int get() = vm.allSources.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String = vm.allSources[index].bookSourceName

    override fun itemState(index: Int): ImportItemState {
        val local = vm.checkSources[index]
        return when {
            local == null -> ImportItemState.NEW
            vm.allSources[index].lastUpdateTime > local.lastUpdateTime -> ImportItemState.UPDATE
            else -> ImportItemState.EXISTING
        }
    }

    override fun itemChecked(index: Int): Boolean = vm.selectStatus[index]

    override fun setItemChecked(index: Int, checked: Boolean) {
        vm.selectStatus[index] = checked
    }

    override fun toggleAll() {
        val selectAll = vm.isSelectAll
        vm.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) vm.selectStatus[index] = !selectAll
        }
    }

    /** 对照 app 端 selectNew: 全选/取消全选新增项。 */
    fun selectNew() {
        val selectAllNew = vm.isSelectAllNew
        vm.newSourceStatus.forEachIndexed { index, b ->
            if (b) vm.selectStatus[index] = !selectAllNew
        }
    }

    /** 对照 app 端 selectUpdate: 全选/取消全选更新项。 */
    fun selectUpdate() {
        val selectAllUpdate = vm.isSelectAllUpdate
        vm.updateSourceStatus.forEachIndexed { index, b ->
            if (b) vm.selectStatus[index] = !selectAllUpdate
        }
    }

    override fun importSelect(finally: () -> Unit) = vm.importSelect(finally)

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
    override val itemCount: Int get() = vm.allRules.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

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

    override fun itemChecked(index: Int): Boolean = vm.selectStatus[index]

    override fun setItemChecked(index: Int, checked: Boolean) {
        vm.selectStatus[index] = checked
    }

    override fun toggleAll() {
        val selectAll = vm.isSelectAll
        vm.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) vm.selectStatus[index] = !selectAll
        }
    }

    override fun importSelect(finally: () -> Unit) = vm.importSelect(finally)

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
    override val itemCount: Int get() = vm.allSources.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String = vm.allSources[index].name

    override fun itemState(index: Int): ImportItemState {
        val local = vm.checkSources[index]
        return when {
            local == null -> ImportItemState.NEW
            vm.allSources[index] != local -> ImportItemState.UPDATE
            else -> ImportItemState.EXISTING
        }
    }

    override fun itemChecked(index: Int): Boolean = vm.selectStatus[index]

    override fun setItemChecked(index: Int, checked: Boolean) {
        vm.selectStatus[index] = checked
    }

    override fun toggleAll() {
        val selectAll = vm.isSelectAll
        vm.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) vm.selectStatus[index] = !selectAll
        }
    }

    override fun importSelect(finally: () -> Unit) = vm.importSelect(finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])
}

/** 字典规则适配器 (对照 app 端 ImportDictRuleDialog: 仅 新增/已有 两态)。 */
class ImportDictRuleItemsVm(
    private val vm: ImportDictRuleViewModelShared,
) : ImportItemsVm {
    override val itemCount: Int get() = vm.allSources.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String = vm.allSources[index].name

    override fun itemState(index: Int): ImportItemState =
        if (vm.checkSources[index] == null) ImportItemState.NEW else ImportItemState.EXISTING

    override fun itemChecked(index: Int): Boolean = vm.selectStatus[index]

    override fun setItemChecked(index: Int, checked: Boolean) {
        vm.selectStatus[index] = checked
    }

    override fun toggleAll() {
        val selectAll = vm.isSelectAll
        vm.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) vm.selectStatus[index] = !selectAll
        }
    }

    override fun importSelect(finally: () -> Unit) = vm.importSelect(finally)

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
    override val itemCount: Int get() = vm.allSources.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String = vm.allSources[index].name

    override fun itemState(index: Int): ImportItemState {
        val local = vm.checkSources[index]
        return when {
            local == null -> ImportItemState.NEW
            vm.allSources[index].lastUpdateTime > local.lastUpdateTime -> ImportItemState.UPDATE
            else -> ImportItemState.EXISTING
        }
    }

    override fun itemChecked(index: Int): Boolean = vm.selectStatus[index]

    override fun setItemChecked(index: Int, checked: Boolean) {
        vm.selectStatus[index] = checked
    }

    override fun toggleAll() {
        val selectAll = vm.isSelectAll
        vm.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) vm.selectStatus[index] = !selectAll
        }
    }

    override fun importSelect(finally: () -> Unit) = vm.importSelect(finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])

    override fun updateItemFromJson(index: Int, json: String): Boolean =
        GSON.fromJsonObject<HttpTTS>(json).getOrNull()?.let {
            vm.allSources[index] = it
            true
        } ?: false
}

/** 主题配置适配器 (对照 app 端 ImportThemeDialog: 整体 != 比对, CodeDialog 可编辑回写)。 */
class ImportThemeItemsVm(
    private val vm: ImportThemeViewModelShared,
) : ImportItemsVm {
    override val itemCount: Int get() = vm.allSources.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String = vm.allSources[index].themeName

    override fun itemState(index: Int): ImportItemState {
        val local = vm.checkSources[index]
        return when {
            local == null -> ImportItemState.NEW
            vm.allSources[index] != local -> ImportItemState.UPDATE
            else -> ImportItemState.EXISTING
        }
    }

    override fun itemChecked(index: Int): Boolean = vm.selectStatus[index]

    override fun setItemChecked(index: Int, checked: Boolean) {
        vm.selectStatus[index] = checked
    }

    override fun toggleAll() {
        val selectAll = vm.isSelectAll
        vm.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) vm.selectStatus[index] = !selectAll
        }
    }

    override fun importSelect(finally: () -> Unit) = vm.importSelect(finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])

    override fun updateItemFromJson(index: Int, json: String): Boolean =
        GSON.fromJsonObject<ThemeConfigData>(json).getOrNull()?.let {
            vm.allSources[index] = it
            true
        } ?: false
}

/** 屏蔽规则适配器 (对照 app 端 ImportSourceFilterRuleDialog: pattern/fields/scope 比对)。 */
class ImportSourceFilterRuleItemsVm(
    private val vm: ImportSourceFilterRuleViewModelShared,
) : ImportItemsVm {
    override val itemCount: Int get() = vm.allRules.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

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

    override fun itemChecked(index: Int): Boolean = vm.selectStatus[index]

    override fun setItemChecked(index: Int, checked: Boolean) {
        vm.selectStatus[index] = checked
    }

    override fun toggleAll() {
        val selectAll = vm.isSelectAll
        vm.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) vm.selectStatus[index] = !selectAll
        }
    }

    override fun importSelect(finally: () -> Unit) = vm.importSelect(finally)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allRules[index])

    override fun updateItemFromJson(index: Int, json: String): Boolean =
        GSON.fromJsonObject<SourceFilterRule>(json).getOrNull()?.let {
            vm.allRules[index] = it
            true
        } ?: false
}
