package io.legado.desktop.ui.dict

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import io.legado.app.ui.compose.component.Md2TextField
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.entities.DictRule
import io.legado.app.help.DefaultDataShared
import io.legado.app.ui.association.ImportDictRuleViewModelShared
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.dict.rule.DictRuleEditDialog
import io.legado.app.ui.dict.rule.DictRuleScreen as SharedDictRuleScreen
import io.legado.app.ui.dict.rule.DictRuleUiActions
import io.legado.app.ui.dict.rule.DictRuleUiState
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.desktop.ui.association.DesktopImportDialog
import io.legado.desktop.ui.association.ImportDictRuleVmAdapter
import io.legado.desktop.ui.association.ImportListScaffoldVm
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 字典规则管理 Screen 桌面端入口。
 *
 * 包装 shared/sharedUiMain 下沉的 [SharedDictRuleScreen], 注入桌面端 Compose
 * CompositionLocal Provider (ThemeStore/AppConfig/EventBus/PreferenceStore),
 * 并用 [AppTheme] 提供统一主题, 使 commonMain 的 RuleManageScaffold 骨架 +
 * 通用组件在桌面 JVM 上正常工作。
 *
 * # 路由回调 (由 DesktopApp 注入)
 * - [onBack]: 切回上一路由
 *
 * # 简化项 (依赖未下沉功能, 用 no-op + TODO 注释)
 * - 新建/编辑规则 (依赖 DictRuleEditDialog, 未下沉): onAddRule/onEditRule no-op
 * - 帮助页跳转 (依赖 showHelp, 未下沉): no-op
 *
 * # 已实现的核心功能
 * - 列表加载 (flowAll 订阅)
 * - 选中 / 全选 / 反选
 * - 单条删除 / 批量删除 (Screen 内 AppAlertDialog 确认后回调)
 * - 单条启用开关切换 / 批量启用/禁用
 * - 拖拽换位 + 松手落库 (重排 sortNumber)
 * - 本地导入 (FileDialog 读 JSON → DesktopImportDialog 勾选入库)
 * - 网络导入 (AlertDialog 输入 URL → DesktopImportDialog 勾选入库)
 * - 默认导入 (DefaultDataShared.importDefaultDictRules)
 * - 导出选中项 (FileDialog SAVE + GSON.toJson 写文件)
 *
 * @param onBack 返回回调 (由 DesktopApp 注入)
 */
@Composable
fun DictRuleScreen(onBack: () -> Unit) {
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
                DictRuleContent(onBack = onBack)
            }
        }
    }
}

@Composable
private fun DictRuleContent(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // state: 规则列表 + 选中集合 (主键为 name)
    val state = remember { mutableStateOf(DictRuleUiState()) }

    // 编辑对话框状态 (showEditDialog=false 隐藏, true 显示; editingRule=null 新增, 非空 编辑)
    var showEditDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<DictRule?>(null) }

    // 文案标签 (rememberString 是 @Composable, 顶层缓存后供 AlertDialog / suspend FileDialog 函数引用;
    // 桌面端 rememberString 对未识别 key 返回 key 本身, 接入桌面 i18n 资源后即恢复正常显示)
    val dictRuleNetImportTitleLabel = rememberString("import_on_line")
    val dictRuleInputUrlLabel = rememberString("input_url")
    val dictRuleSelectJsonFileLabel = rememberString("select_json_file")
    val dictRuleSaveJsonFileLabel = rememberString("dict_rule_save_json_file")
    val importDictRuleLabel = rememberString("import_dict_rule")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")

    // 网络导入 URL 输入对话框状态 (onImportOnline 触发显示, 末尾 AlertDialog 渲染分支读取,
    // 确认按钮新建 ImportDictRuleVmAdapter + 设置 importVm; 对照 ReplaceRuleScreen 同名状态)
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }
    // 导入 VM 适配器 (null=无导入任务, 非 null=渲染 DesktopImportDialog 让用户勾选比对);
    // 本地/网络导入均走 ImportDictRuleViewModelShared.import 路径 (URL 下载/JSON 解析/
    // comparisonSource 比对), 成功后弹 DesktopImportDialog 让用户勾选"新增/已有"项再 importSelect 入库,
    // 与 app 端 ImportDictRuleDialog 流程等价
    var importVm by remember { mutableStateOf<ImportListScaffoldVm?>(null) }
    // 导入初始文本 (URL 或 JSON), DesktopImportDialog 的 LaunchedEffect 用它调 vm.startImport
    var importInitialText by remember { mutableStateOf("") }

    // 收集全部字典规则 (按 sortNumber 排序, DAO SQL 已排序)
    LaunchedEffect(Unit) {
        AppDatabaseProviders.get().appDb.dictRuleDao.flowAll()
            .catch { AppLog.put(jvmGetString("dict_rule_load_failed", it.localizedMessage), it) }
            .flowOn(Dispatchers.IO)
            .conflate()
            .collectLatest { rules ->
                state.value = state.value.copy(dictRules = rules)
            }
    }

    // actions: 匿名 object 实现接口, 捕获 state(MutableState 引用稳定) + scope + onBack
    val actions = remember {
        object : DictRuleUiActions {
            override fun onBack() = onBack()

            override fun onAddRule() {
                // 触发 DictRuleEditDialog 显示 (rule=null 新增, Dialog 内部 save 已 delete+insert 落库)
                editingRule = null
                showEditDialog = true
            }

            override fun onEditRule(name: String) {
                // 按 name 查找规则后触发 DictRuleEditDialog 显示 (编辑场景)
                editingRule = state.value.dictRules.find { it.name == name }
                showEditDialog = true
            }

            override fun onImportLocal() {
                // 弹 FileDialog 选 JSON 文件 → 读文本 → ImportDictRuleViewModelShared.import
                // → 弹 DesktopImportDialog 让用户勾选比对 (新增/已有) 后 importSelect 入库
                // (与 app 端 ImportDictRuleDialog 完整流程等价, 不再简化为直接入库)
                scope.launch {
                    val json = importDictRulesFromLocalFile(dictRuleSelectJsonFileLabel) ?: return@launch
                    val vm = ImportDictRuleVmAdapter(ImportDictRuleViewModelShared(scope))
                    importInitialText = json
                    importVm = vm
                }
            }

            override fun onImportOnline() {
                // 弹 AlertDialog URL 输入 → 用户确认后新建 ImportDictRuleVmAdapter +
                // 设置 importInitialText/importVm, DesktopImportDialog 的 LaunchedEffect 调
                // vm.startImport(url) 触发下载 → JSON 解析 → comparisonSource 比对
                importOnlineUrlText = ""
                showImportOnlineDialog = true
            }

            override fun onImportDefault() {
                // 调用 shared 下沉的 DefaultDataShared.importDefaultDictRules (与 app 端
                // DictRuleViewModel.importDefault → DefaultData.importDefaultDictRules 等价),
                // 内部 runBlocking 批量 insert 默认规则; 在 IO 协程执行避免阻塞 UI。
                // 注: 依赖 DefaultDataResourceProvider (宿主注册), 桌面端未注册时
                // runCatching 捕获并记录日志, 不崩 UI (后续接入桌面端资源 provider 后即正常)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { DefaultDataShared.importDefaultDictRules() }
                            .onFailure { AppLog.put(jvmGetString("import_default_dict_rule_failed"), it) }
                    }
                }
            }

            override fun onHelp() {
                // TODO: 依赖 showHelp("dictRuleHelp"), 桌面端浏览器跳转待后续接入
            }

            override fun onToggleSelected(item: DictRule, checked: Boolean) {
                state.value = state.value.copy(
                    selected = if (checked) state.value.selected + item.name
                    else state.value.selected - item.name
                )
            }

            override fun onSelectAll(all: Boolean) {
                state.value = state.value.copy(
                    selected = if (all) state.value.dictRules.map { it.name }.toSet()
                    else emptySet()
                )
            }

            override fun onRevertSelection() {
                state.value = state.value.copy(
                    selected = state.value.dictRules.map { it.name }.toSet() - state.value.selected
                )
            }

            override fun onMoveItem(from: Int, to: Int) {
                state.value = state.value.copy(
                    dictRules = state.value.dictRules.toMutableList().apply { add(to, removeAt(from)) }
                )
            }

            override fun onPersistOrder() {
                // 松手落库: 按当前顺序重排 sortNumber 后整批 update
                val rules = state.value.dictRules
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val array = Array(rules.size) { i -> rules[i].copy(sortNumber = i + 1) }
                        AppDatabaseProviders.get().appDb.dictRuleDao.update(*array)
                    }
                }
            }

            override fun onUpdateRuleEnabled(item: DictRule, enabled: Boolean) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabaseProviders.get().appDb.dictRuleDao.update(item.copy(enabled = enabled))
                    }
                }
            }

            override fun onDeleteRule(rule: DictRule) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabaseProviders.get().appDb.dictRuleDao.delete(rule)
                    }
                }
            }

            override fun onDeleteSelection() {
                val sel = state.value.dictRules.filter { it.name in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabaseProviders.get().appDb.dictRuleDao.delete(*sel.toTypedArray())
                    }
                }
            }

            override fun onEnableSelection() {
                val sel = state.value.dictRules.filter { it.name in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val array = Array(sel.size) { i -> sel[i].copy(enabled = true) }
                        AppDatabaseProviders.get().appDb.dictRuleDao.update(*array)
                    }
                }
            }

            override fun onDisableSelection() {
                val sel = state.value.dictRules.filter { it.name in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val array = Array(sel.size) { i -> sel[i].copy(enabled = false) }
                        AppDatabaseProviders.get().appDb.dictRuleDao.update(*array)
                    }
                }
            }

            override fun onExportSelection() {
                // 导出选中项: 弹 FileDialog SAVE → GSON.toJson → 写文件
                val sel = state.value.dictRules.filter { it.name in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    exportDictRulesToLocalFile(sel, dictRuleSaveJsonFileLabel)
                }
            }
        }
    }

    SharedDictRuleScreen(state.value, actions)

    // ---- 字典规则编辑对话框 (onAddRule/onEditRule 触发) ----
    // 剪贴板桥接用 AWT Toolkit (替代 app 端 getClipText/sendToClip):
    // - clipTextProvider: 读系统剪贴板文本 (供"粘贴规则"菜单项用)
    // - clipTextSink: 写系统剪贴板文本 (供"复制规则"菜单项用)
    // onConfirm 空操作: DictRuleEditViewModelShared.save 内部已 delete+insert 落库,
    // flowAll 自动刷新列表, 无需调用方额外处理
    if (showEditDialog) {
        DictRuleEditDialog(
            rule = editingRule,
            onConfirm = {
                // Dialog 内部 save 已落库, flowAll 自动刷新
            },
            onDismiss = { showEditDialog = false },
            clipTextProvider = {
                runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
                }.getOrNull()
            },
            clipTextSink = { text ->
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            },
        )
    }

    // ---- AlertDialog 渲染 (网络导入 URL 输入对话框, onImportOnline 触发) ----
    // 确认按钮新建 ImportDictRuleVmAdapter + 设置 importVm 触发 DesktopImportDialog
    // 完成下载+解析+比对+入库 (对照 ReplaceRuleScreen 同名分支)
    if (showImportOnlineDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showImportOnlineDialog = false },
            title = { Text(dictRuleNetImportTitleLabel) },
            text = {
                Md2TextField(
                    value = importOnlineUrlText,
                    onValueChange = { importOnlineUrlText = it },
                    label = dictRuleInputUrlLabel,
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = importOnlineUrlText
                    showImportOnlineDialog = false
                    if (url.isNotBlank()) {
                        // 新建适配器 (包装 ImportDictRuleViewModelShared), 设置 importInitialText
                        // + importVm 触发 DesktopImportDialog 渲染; Dialog 的 LaunchedEffect(vm) 调
                        // vm.startImport(url) 触发下载 → JSON 解析 → comparisonSource 比对,
                        // 成功后让用户勾选"新增/已有"项再 importSelect 入库
                        val vm = ImportDictRuleVmAdapter(ImportDictRuleViewModelShared(scope))
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
    // 让用户勾选"新增/已有"项后确定 importSelect 入库, 与 app 端 ImportDictRuleDialog
    // 流程等价; 取消/确定均触发 onDismiss → importVm=null 关闭 Dialog) ----
    importVm?.let { vm ->
        DesktopImportDialog(
            title = importDictRuleLabel,
            vm = vm,
            initialText = importInitialText,
            onDismiss = { importVm = null },
        )
    }
}

/**
 * 从本地 JSON 文件读取字典规则文本 (对应 app 端 importDictRule 文件选择 + 读取)。
 *
 * 仅负责选文件 + 读文本, 不再解析+入库 (交给 ImportDictRuleViewModelShared.import 走完整比对
 * 流程, 让用户在 DesktopImportDialog 勾选"新增/已有"后 importSelect 入库)。
 *
 * 流程:
 * 1. 弹 [FileDialog] (LOAD 模式, 过滤 .json) 选择文件
 * 2. 读文件内容为 String (UTF-8) 返回给调用方
 *
 * 任何一步失败 (用户取消/IO 错) 都打印日志但不抛异常, 返回 null 让调用方跳过。
 *
 * @return JSON 文本, 用户取消/IO 错时返回 null
 */
private suspend fun importDictRulesFromLocalFile(dialogTitle: String): String? {
    val json = withContext(Dispatchers.IO) {
        val dialog = FileDialog(Frame(), dialogTitle, FileDialog.LOAD)
        dialog.setFile("*.json")
        dialog.isVisible = true
        val file = dialog.files?.firstOrNull() ?: return@withContext null
        file.readText()
    } ?: run {
        AppLog.put(jvmGetString("import_dict_rule_user_canceled"))
        return null
    }
    return json
}

/**
 * 导出字典规则到本地 JSON 文件 (对应 app 端 onExportSelection + HandleFileContract EXPORT)。
 *
 * 弹 FileDialog SAVE 选路径 → GSON.toJson 序列化 → 写文件 (UTF-8)。
 * 用户取消/IO 错打印日志不抛异常, 不中断主流程。
 */
private suspend fun exportDictRulesToLocalFile(rules: List<DictRule>, dialogTitle: String) {
    if (rules.isEmpty()) {
        AppLog.put(jvmGetString("export_dict_rule_no_selection"))
        return
    }
    val targetPath = withContext(Dispatchers.IO) {
        val dialog = FileDialog(Frame(), dialogTitle, FileDialog.SAVE)
        dialog.setFile("exportDictRule.json")
        dialog.isVisible = true
        val dir = dialog.directory ?: return@withContext null
        val file = dialog.file ?: return@withContext null
        dir + file
    } ?: run {
        AppLog.put(jvmGetString("export_dict_rule_user_canceled"))
        return
    }
    val targetFile = File(targetPath)
    runCatching {
        withContext(Dispatchers.IO) {
            val json = GSON.toJson(rules)
            targetFile.writeText(json)
        }
        AppLog.put(jvmGetString("export_dict_rule_done", rules.size, targetFile.absolutePath))
    }.onFailure {
        AppLog.put(jvmGetString("export_dict_rule_failed"), it)
    }
}
