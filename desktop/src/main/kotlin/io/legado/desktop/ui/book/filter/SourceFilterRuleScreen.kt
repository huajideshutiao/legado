package io.legado.desktop.ui.book.filter

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.ui.association.ImportSourceFilterRuleViewModelShared
import io.legado.app.ui.book.filter.SourceFilterEditDialog
import io.legado.app.ui.book.filter.SourceFilterRuleScreen as SharedSourceFilterRuleScreen
import io.legado.app.ui.book.filter.SourceFilterRuleUiActions
import io.legado.app.ui.book.filter.SourceFilterRuleUiState
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.desktop.ui.association.DesktopImportDialog
import io.legado.desktop.ui.association.ImportListScaffoldVm
import io.legado.desktop.ui.association.ImportSourceFilterRuleVmAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * 书源过滤规则管理 Screen 桌面端入口。
 *
 * 包装 shared/sharedUiMain 下沉的 [SharedSourceFilterRuleScreen], 注入桌面端 Compose
 * CompositionLocal Provider (ThemeStore/AppConfig/EventBus/PreferenceStore),
 * 并用 [AppTheme] 提供统一主题, 使 commonMain 的 RuleManageScaffold 骨架 +
 * 通用组件 (AppSearchField/AppDropdownMenu 等) 在桌面 JVM 上正常工作。
 *
 * # 路由回调 (由 DesktopApp 注入)
 * - [onBack]: 切回上一路由
 *
 * # 已实现的核心功能
 * - 列表加载 + 搜索 (searchKey 为空走 flowAll, 非空走 flowSearch)
 * - 选中 / 全选 / 反选
 * - 单条删除 / 批量删除 / 删除全部 (Screen 内 AppAlertDialog 确认后回调)
 * - 单条启用开关切换 / 批量启用/禁用
 * - 拖拽换位 + 松手落库 (重排 order)
 * - 单项置顶 / 置底 (minOrder-1 / maxOrder+1)
 * - 新建/编辑规则 (SourceFilterEditDialog 已下沉 sharedUiMain)
 * - 本地/在线导入 (FileDialog 选文件 / AlertDialog 输入 URL, 走 ImportSourceFilterRuleViewModelShared)
 * - 导出选中项 (FileDialog SAVE + GSON.toJson 写文件)
 * - 批量置顶/置底 (按 minOrder - size / maxOrder + 1 整批重排, 对照 SourceFilterRuleViewModelShared)
 *
 * @param onBack 返回回调 (由 DesktopApp 注入)
 */
@Composable
fun SourceFilterRuleScreen(onBack: () -> Unit) {
    // 桌面端 Provider: 全部用 jvmMain/DesktopProviders.kt 的内存实现
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                SourceFilterRuleContent(onBack = onBack)
            }
        }
    }
}

@Composable
private fun SourceFilterRuleContent(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // state: 规则列表 + 选中集合 + 搜索关键词 (主键 id: String)
    val state = remember { mutableStateOf(SourceFilterRuleUiState()) }

    // 编辑对话框状态 (showEditDialog=false 隐藏, true 显示; editingRule=null 新增, 非空 编辑)
    var showEditDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<SourceFilterRule?>(null) }

    // 网络导入 URL 输入对话框状态 (onImportOnline 触发显示, 末尾 AlertDialog 渲染分支读取,
    // 确认按钮新建 ImportSourceFilterRuleVmAdapter + 设置 importVm 触发 DesktopImportDialog)
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }
    // 导入 VM 适配器 (null=无导入任务, 非 null=渲染 DesktopImportDialog 让用户勾选比对);
    // 本地/网络导入均走 ImportSourceFilterRuleViewModelShared.import 路径 (URL 下载/JSON 解析/
    // comparisonSource 比对), 成功后弹 DesktopImportDialog 让用户勾选"新增/更新/已有"项再 importSelect 入库,
    // 与 app 端 ImportSourceFilterRuleDialog 流程等价
    var importVm by remember { mutableStateOf<ImportListScaffoldVm?>(null) }
    // 导入初始文本 (URL 或 JSON), DesktopImportDialog 的 LaunchedEffect 用它调 vm.startImport
    var importInitialText by remember { mutableStateOf("") }

    // 文案标签 (rememberString 是 @Composable, 顶层缓存后供 AlertDialog / suspend FileDialog 函数引用)
    val importOnlineTitleLabel = rememberString("import_on_line")
    val selectJsonFileLabel = rememberString("source_filter_rule_select_json_file")
    // 导出对话框标题 (FileDialog SAVE 标题, 对照 ReplaceRuleScreen 的 replace_rule_save_json_file)
    val saveJsonFileLabel = rememberString("source_filter_rule_save_json_file")
    // 导入对话框标题 (ImportListScaffold.title, 对照 app 端 getString(R.string.import_source_filter_rule))
    val importSourceFilterRuleLabel = rememberString("import_source_filter_rule")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")

    // DAO: sourceFilterRuleDao 已通过 AppDbAccessor 暴露 (SearchBookFilter 用)
    val dao = remember { AppDbProviders.get().sourceFilterRuleDao }

    // 收集规则 (searchKey 变化时重启): 空关键词走 flowAll, 非空走 flowSearch (LIKE %key%)
    val searchKey = state.value.searchKey
    LaunchedEffect(searchKey) {
        val flow = if (searchKey.isBlank()) dao.flowAll()
        else dao.flowSearch("%$searchKey%")
        flow
            .catch { AppLog.put("书源过滤规则界面获取数据失败\n${it.localizedMessage}", it) }
            .flowOn(Dispatchers.IO)
            .conflate()
            .collectLatest { rules ->
                state.value = state.value.copy(rules = rules)
            }
    }

    // actions: 匿名 object 实现接口, 捕获 state(MutableState 引用稳定) + scope + dao + onBack
    val actions = remember {
        object : SourceFilterRuleUiActions {
            override fun onBack() = onBack()

            override fun onSearchKeyChange(key: String) {
                state.value = state.value.copy(searchKey = key)
            }

            override fun onToggleSelected(item: SourceFilterRule, checked: Boolean) {
                state.value = state.value.copy(
                    selected = if (checked) state.value.selected + item.id
                    else state.value.selected - item.id
                )
            }

            override fun onSelectAll(all: Boolean) {
                state.value = state.value.copy(
                    selected = if (all) state.value.rules.map { it.id }.toSet()
                    else emptySet()
                )
            }

            override fun onRevertSelection() {
                state.value = state.value.copy(
                    selected = state.value.rules.map { it.id }.toSet() - state.value.selected
                )
            }

            override fun onMoveItem(from: Int, to: Int) {
                state.value = state.value.copy(
                    rules = state.value.rules.toMutableList().apply { add(to, removeAt(from)) }
                )
            }

            override fun onPersistOrder() {
                // 松手落库: 按当前顺序重排 order 后整批 update
                val rules = state.value.rules
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val array = Array(rules.size) { i -> rules[i].copy(order = i + 1) }
                        dao.update(*array)
                    }
                }
            }

            override fun onDeleteSelection() {
                val sel = state.value.rules.filter { it.id in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    withContext(Dispatchers.IO) {
                        dao.delete(*sel.toTypedArray())
                    }
                }
            }

            override fun onDeleteRule(rule: SourceFilterRule) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        dao.delete(rule)
                    }
                }
            }

            override fun onDeleteAll() {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        dao.deleteAll()
                    }
                }
            }

            override fun onEnableSelection() {
                val sel = state.value.rules.filter { it.id in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val array = Array(sel.size) { i -> sel[i].copy(enabled = true) }
                        dao.update(*array)
                    }
                }
            }

            override fun onDisableSelection() {
                val sel = state.value.rules.filter { it.id in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val array = Array(sel.size) { i -> sel[i].copy(enabled = false) }
                        dao.update(*array)
                    }
                }
            }

            override fun onTopSelect() {
                // 批量置顶: 对照 SourceFilterRuleViewModelShared.topSelect
                // 起始 order = minOrder - size, 逐条 ++ 落到 [minOrder-size+1, minOrder]
                // 使选中项整体上移到当前最小值之上, 保持相对顺序
                val sel = state.value.rules.filter { it.id in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val startOrder = dao.minOrder() - sel.size
                        val array = Array(sel.size) { i -> sel[i].copy(order = startOrder + i + 1) }
                        dao.update(*array)
                    }
                }
            }

            override fun onBottomSelect() {
                // 批量置底: 对照 SourceFilterRuleViewModelShared.bottomSelect
                // 起始 order = maxOrder, 逐条 ++ 落到 [maxOrder+1, maxOrder+size]
                // 使选中项整体下移到当前最大值之下, 保持相对顺序
                val sel = state.value.rules.filter { it.id in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val startOrder = dao.maxOrder()
                        val array = Array(sel.size) { i -> sel[i].copy(order = startOrder + i + 1) }
                        dao.update(*array)
                    }
                }
            }

            override fun onExportSelection() {
                // 导出选中项: 弹 FileDialog SAVE → GSON.toJson → 写文件
                // 对照 app 端 onExportSelection (HandleFileContract EXPORT + GSON.toJson)
                val sel = state.value.rules.filter { it.id in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    exportSourceFilterRulesToLocalFile(sel, saveJsonFileLabel)
                }
            }

            override fun onEditRule(rule: SourceFilterRule) {
                // 触发 SourceFilterEditDialog 显示 (编辑场景, onConfirm 走 dao.update)
                editingRule = rule
                showEditDialog = true
            }

            override fun onToTop(rule: SourceFilterRule) {
                // 置顶: order 设为 minOrder - 1
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val minOrder = dao.minOrder()
                        dao.update(rule.copy(order = minOrder - 1))
                    }
                }
            }

            override fun onToBottom(rule: SourceFilterRule) {
                // 置底: order 设为 maxOrder + 1
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val maxOrder = dao.maxOrder()
                        dao.update(rule.copy(order = maxOrder + 1))
                    }
                }
            }

            override fun onToggleEnabled(rule: SourceFilterRule, enabled: Boolean) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        dao.update(rule.copy(enabled = enabled))
                    }
                }
            }

            override fun onAddRule() {
                // 触发 SourceFilterEditDialog 显示 (rule=null 新增, onConfirm 走 dao.insert)
                editingRule = null
                showEditDialog = true
            }

            override fun onImportLocal() {
                // 弹 FileDialog 选 JSON 文件 → 读文本 → ImportSourceFilterRuleViewModelShared.import
                // → 弹 DesktopImportDialog 让用户勾选比对 (新增/更新/已有) 后 importSelect 入库
                // (与 app 端 ImportSourceFilterRuleDialog 完整流程等价)
                scope.launch {
                    val json = importSourceFilterRulesFromLocalFile(selectJsonFileLabel) ?: return@launch
                    val vm = ImportSourceFilterRuleVmAdapter(ImportSourceFilterRuleViewModelShared(scope))
                    importInitialText = json
                    importVm = vm
                }
            }

            override fun onImportOnline() {
                // 弹 AlertDialog URL 输入 → 用户确认后新建 ImportSourceFilterRuleVmAdapter +
                // 设置 importInitialText/importVm, DesktopImportDialog 的 LaunchedEffect 调
                // vm.startImport(url) 触发下载 → JSON 解析 → comparisonSource 比对
                importOnlineUrlText = ""
                showImportOnlineDialog = true
            }
        }
    }

    SharedSourceFilterRuleScreen(state.value, actions)

    // ---- 书源屏蔽规则编辑对话框 (onAddRule/onEditRule 触发) ----
    // onConfirm 落库: 新增 (editingRule=null) 走 dao.insert, 编辑走 dao.update
    // (SourceFilterEditDialog 内部仅校验+组装, 不落库, 由调用方负责)
    if (showEditDialog) {
        SourceFilterEditDialog(
            rule = editingRule,
            onConfirm = { newRule ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        if (editingRule == null) dao.insert(newRule) else dao.update(newRule)
                    }
                }
            },
            onDismiss = { showEditDialog = false },
        )
    }

    // ---- 网络导入 URL 输入对话框 (onImportOnline 触发 showImportOnlineDialog=true;
    //   确认按钮新建 ImportSourceFilterRuleVmAdapter + 设置 importVm 触发 DesktopImportDialog
    //   完成下载+解析+比对+入库, 与 app 端 ImportSourceFilterRuleDialog 流程等价) ----
    if (showImportOnlineDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showImportOnlineDialog = false },
            title = { Text(importOnlineTitleLabel) },
            text = {
                OutlinedTextField(
                    value = importOnlineUrlText,
                    onValueChange = { importOnlineUrlText = it },
                    label = { Text(importOnlineTitleLabel) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = importOnlineUrlText
                    showImportOnlineDialog = false
                    if (url.isNotBlank()) {
                        // 新建适配器 (包装 ImportSourceFilterRuleViewModelShared), 设置 importInitialText
                        // + importVm 触发 DesktopImportDialog 渲染; Dialog 的 LaunchedEffect(vm) 调
                        // vm.startImport(url) 触发下载 → JSON 解析 → comparisonSource 比对,
                        // 成功后让用户勾选"新增/更新/已有"项再 importSelect 入库
                        val vm = ImportSourceFilterRuleVmAdapter(ImportSourceFilterRuleViewModelShared(scope))
                        importInitialText = url
                        importVm = vm
                    }
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showImportOnlineDialog = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    // ---- 导入对话框 (本地/网络导入共用, importVm 非 null 时渲染 DesktopImportDialog
    // 让用户勾选"新增/更新/已有"项后确定 importSelect 入库, 与 app 端 ImportSourceFilterRuleDialog
    // 流程等价; 取消/确定均触发 onDismiss → importVm=null 关闭 Dialog) ----
    importVm?.let { vm ->
        DesktopImportDialog(
            title = importSourceFilterRuleLabel,
            vm = vm,
            initialText = importInitialText,
            onDismiss = { importVm = null },
        )
    }
}

/**
 * 从本地 JSON 文件读取书源过滤规则文本 (对应 app 端 importSourceFilterRule 文件选择 + 读取)。
 *
 * 仅负责选文件 + 读文本, 不再解析+入库 (交给 ImportSourceFilterRuleViewModelShared.import 走完整比对
 * 流程, 让用户在 DesktopImportDialog 勾选"新增/更新/已有"后 importSelect 入库)。
 *
 * 流程:
 * 1. 弹 [FileDialog] (LOAD 模式, 过滤 .json) 选择文件
 * 2. 读文件内容为 String (UTF-8) 返回给调用方
 *
 * 任何一步失败 (用户取消/IO 错) 都打印日志但不抛异常, 返回 null 让调用方跳过。
 *
 * @return JSON 文本, 用户取消/IO 错时返回 null
 */
private suspend fun importSourceFilterRulesFromLocalFile(dialogTitle: String): String? {
    val json = withContext(Dispatchers.IO) {
        val dialog = FileDialog(Frame(), dialogTitle, FileDialog.LOAD)
        dialog.setFile("*.json")
        dialog.isVisible = true
        val file = dialog.files?.firstOrNull() ?: return@withContext null
        file.readText()
    } ?: run {
        AppLog.put("导入书源过滤规则: 用户取消选择")
        return null
    }
    return json
}

/**
 * 导出书源过滤规则到本地 JSON 文件 (对应 app 端 onExportSelection +
 * HandleFileContract EXPORT + GSON.toJson 的桌面端等价实现)。
 *
 * 流程:
 * 1. 弹 [FileDialog] (SAVE 模式, 默认文件名 source_filter_rules.json) 选择保存路径
 * 2. 用 [GSON.toJson] 序列化选中规则为 JSON
 * 3. 写入文件 (UTF-8)
 *
 * 任何一步失败 (用户取消/IO 错) 都打印日志但不抛异常, 不中断主流程。
 * 对照 desktop 端 [io.legado.desktop.ui.replace.ReplaceRuleScreen] 的
 * exportReplaceRulesToLocalFile 实现。
 */
private suspend fun exportSourceFilterRulesToLocalFile(rules: List<SourceFilterRule>, dialogTitle: String) {
    if (rules.isEmpty()) {
        AppLog.put("导出书源过滤规则: 未选中任何规则")
        return
    }
    val targetPath = withContext(Dispatchers.IO) {
        val dialog = FileDialog(Frame(), dialogTitle, FileDialog.SAVE)
        dialog.setFile("source_filter_rules.json")
        dialog.isVisible = true
        val dir = dialog.directory ?: return@withContext null
        val file = dialog.file ?: return@withContext null
        dir + file
    } ?: run {
        AppLog.put("导出书源过滤规则: 用户取消选择")
        return
    }
    val targetFile = File(targetPath)
    runCatching {
        withContext(Dispatchers.IO) {
            val json = GSON.toJson(rules)
            targetFile.writeText(json)
        }
        AppLog.put("导出书源过滤规则完成, 共 ${rules.size} 条 → ${targetFile.absolutePath}")
    }.onFailure {
        AppLog.put("导出书源过滤规则失败", it)
    }
}
