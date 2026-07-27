package io.legado.desktop.ui.replace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.Md2TextField
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.association.ImportReplaceRuleViewModelShared
import io.legado.app.ui.book.group.GroupManageDialog
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
import io.legado.app.ui.replace.ReplaceRuleListScreen
import io.legado.app.ui.replace.ReplaceRuleListViewModel
import io.legado.app.utils.GSON
import io.legado.app.utils.browseUrl
import io.legado.app.utils.toJson
import io.legado.desktop.ui.association.DesktopImportDialog
import io.legado.desktop.ui.association.ImportListScaffoldVm
import io.legado.desktop.ui.association.ImportReplaceRuleVmAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * 替换规则管理 Screen 桌面端入口 (KP-Replace)。
 *
 * 包装 shared/sharedUiMain 下沉的 [ReplaceRuleListScreen], 注入桌面端 Compose
 * CompositionLocal Provider (ThemeStore/AppConfig/EventBus/PreferenceStore),
 * 并用 [AppTheme] 提供统一主题, 使 commonMain 的 16 个通用组件 + RuleManageScaffold
 * 骨架在桌面 JVM 上正常工作。
 *
 * # 路由回调 (由 DesktopApp 注入)
 * - [onBack]: 切回书架路由
 * - [onAddRule]: 切到 REPLACE_EDIT 路由 (ruleId = -1, 新增)
 * - [onEditRule]: 切到 REPLACE_EDIT 路由 (ruleId = id, 编辑)
 *
 * # 平台适配回调 (本文件内实现)
 * - onImportLocal: 弹 [FileDialog] (LOAD 模式) 读 JSON 文本 → 新建 [ImportReplaceRuleVmAdapter]
 *   → DesktopImportDialog 让用户勾选"新增/更新/已有"项后 importSelect 入库
 * - onImportOnline: 弹 [AlertDialog] 输入 URL → 新建 [ImportReplaceRuleVmAdapter] +
 *   设置 importVm → DesktopImportDialog 的 LaunchedEffect 调 vm.startImport(url) 触发
 *   OkHttp 下载 + ReplaceAnalyzer 解析 + comparisonSource 比对, 用户勾选后 importSelect 入库
 * - onExport: 弹 [FileDialog] (SAVE 模式) → [GSON.toJson] 选中规则 → 写文件
 * - onHelp: [Desktop.browse] 跳转正则帮助页
 * - onGroupManage: 弹 [GroupManageDialog] (String 分组 → BookGroup 适配)
 *
 * # 资源占位
 * 桌面端 [io.legado.app.ui.compose.platform.rememberString] 对未识别 key 返回 key 本身,
 * 故标题/按钮文案会显示 string res name (如 "replace_purify"/"delete");
 * [io.legado.app.ui.compose.platform.rememberPainter] 对未注册的 ic_groups/ic_edit
 * 返回 Help 占位图标。接入桌面 i18n/图标资源后即恢复正常显示。
 *
 * @param onBack 返回回调 (切回书架路由)
 * @param onAddRule 新建规则回调 (切到 REPLACE_EDIT 路由, ruleId=-1)
 * @param onEditRule 编辑规则回调 (切到 REPLACE_EDIT 路由, ruleId=id)
 */
@Composable
fun ReplaceRuleScreen(
    onBack: () -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (Long) -> Unit,
) {
    // 桌面端 Provider: 全部用 jvmMain/DesktopProviders.kt 的内存实现
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }
    val scope = rememberCoroutineScope()
    // 文案标签 (rememberString 是 @Composable, 顶层缓存后供 AlertDialog / suspend FileDialog 函数引用)
    val replaceRuleNetImportTitleLabel = rememberString("replace_rule_net_import_title")
    val replaceRuleInputUrlLabel = rememberString("replace_rule_input_url")
    val replaceRuleSelectJsonFileLabel = rememberString("replace_rule_select_json_file")
    val replaceRuleSaveJsonFileLabel = rememberString("replace_rule_save_json_file")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    // 导入对话框标题 (ImportListScaffold.title, 对照 app 端 getString(R.string.import_replace_rule))
    val importReplaceRuleLabel = rememberString("import_replace_rule")
    // VM 在 Composable 作用域内 remember, 窗口关闭时 onCleared (DisposableEffect)
    val viewModel = remember { ReplaceRuleListViewModel() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onCleared() }
    }

    // 网络导入 URL 输入对话框状态 (替换原 javax.swing.JOptionPane.showInputDialog 同步阻塞,
    // 与 BookSourceEditScreen emptyUrlNameDialog 模式一致; onImportOnline 触发显示,
    // 末尾 AlertDialog 渲染分支读取, 确认按钮新建 ImportReplaceRuleVmAdapter + 设置 importVm)
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }
    // 导入 VM 适配器 (null=无导入任务, 非 null=渲染 DesktopImportDialog 让用户勾选比对);
    // 本地/网络导入均走 ImportReplaceRuleViewModelShared.import 路径 (URL 下载/ReplaceAnalyzer 解析/
    // comparisonSource 比对), 成功后弹 DesktopImportDialog 让用户勾选"新增/更新/已有"项再 importSelect 入库,
    // 与 app 端 ImportReplaceRuleDialog 流程等价 (不再简化为 ReplaceAnalyzer.jsonToReplaceRules 直接入库)
    var importVm by remember { mutableStateOf<ImportListScaffoldVm?>(null) }
    // 导入初始文本 (URL 或 JSON), DesktopImportDialog 的 LaunchedEffect 用它调 vm.startImport
    var importInitialText by remember { mutableStateOf("") }

    // 分组管理对话框状态 (false=隐藏, true=显示; onGroupManage 触发, 末尾 GroupManageDialog 渲染)
    // ReplaceRule 分组是 String (逗号分隔分组名), shared GroupManageDialog 期望 List<BookGroup>,
    // 用 groupEntities 做 String → BookGroup 适配 (groupId 用递增索引, 避免与系统分组 <=0 冲突)
    var showGroupManage by remember { mutableStateOf(false) }
    val groupNames by produceState<List<String>>(emptyList()) {
        AppDbProviders.get().replaceRuleDao.flowGroups().collect { value = it }
    }
    val groupEntities = remember(groupNames) {
        groupNames.mapIndexed { index, name ->
            BookGroup(groupId = (index + 1).toLong(), groupName = name)
        }
    }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                ReplaceRuleListScreen(
                    viewModel = viewModel,
                    onBack = onBack,
                    onAddRule = onAddRule,
                    onEditRule = onEditRule,
                    onImportLocal = {
                        // 弹 FileDialog 选 JSON 文件 → 读文本 → ImportReplaceRuleViewModelShared.import
                        // → 弹 DesktopImportDialog 让用户勾选比对 (新增/更新/已有) 后 importSelect 入库
                        // (与 app 端 ImportReplaceRuleDialog 完整流程等价, 不再简化为
                        //  ReplaceAnalyzer.jsonToReplaceRules 直接入库)
                        scope.launch {
                            val json = importReplaceRulesFromLocalFile(replaceRuleSelectJsonFileLabel) ?: return@launch
                            val vm = ImportReplaceRuleVmAdapter(ImportReplaceRuleViewModelShared(scope))
                            importInitialText = json
                            importVm = vm
                        }
                    },
                    onImportOnline = {
                        // 弹 AlertDialog URL 输入 → 用户确认后新建 ImportReplaceRuleVmAdapter +
                        // 设置 importInitialText/importVm, DesktopImportDialog 的 LaunchedEffect 调
                        // vm.startImport(url) 触发下载 → ReplaceAnalyzer 解析 → comparisonSource 比对
                        // (替换原 javax.swing.JOptionPane.showInputDialog 同步阻塞 + 直接入库)
                        importOnlineUrlText = ""
                        showImportOnlineDialog = true
                    },
                    onHelp = {
                        // 打开浏览器跳转正则帮助页 (对应 app 端 showHelp("regexHelp"))
                        browseUrl("https://www.runoob.com/regexp/regexp-tutorial.html")
                    },
                    onGroupManage = {
                        // 触发 GroupManageDialog 显示 (末尾渲染分支读取 showGroupManage)
                        showGroupManage = true
                    },
                    onExport = { rules: List<ReplaceRule> ->
                        // 弹 FileDialog SAVE → GSON.toJson → 写文件
                        scope.launch { exportReplaceRulesToLocalFile(rules, replaceRuleSaveJsonFileLabel) }
                    },
                )
            }

            // ---- AlertDialog 渲染 (替换原 javax.swing.JOptionPane.showInputDialog) ----
            // 网络导入替换规则 URL 输入对话框 (onImportOnline 触发 showImportOnlineDialog=true;
            //   确认按钮新建 ImportReplaceRuleVmAdapter + 设置 importVm 触发 DesktopImportDialog
            //   完成下载+解析+比对+入库, 保留原中文字面量因 ResourceProvider.jvm.kt 无对应 key)
            if (showImportOnlineDialog) {
                AlertDialog(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    onDismissRequest = { showImportOnlineDialog = false },
                    title = { Text(replaceRuleNetImportTitleLabel) },
                    text = {
                        Md2TextField(
                            value = importOnlineUrlText,
                            onValueChange = { importOnlineUrlText = it },
                            label = replaceRuleInputUrlLabel,
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val url = importOnlineUrlText
                            showImportOnlineDialog = false
                            if (url.isNotBlank()) {
                                // 新建适配器 (包装 ImportReplaceRuleViewModelShared), 设置 importInitialText
                                // + importVm 触发 DesktopImportDialog 渲染; Dialog 的 LaunchedEffect(vm) 调
                                // vm.startImport(url) 触发下载 → ReplaceAnalyzer 解析 → comparisonSource 比对,
                                // 成功后让用户勾选"新增/更新/已有"项再 importSelect 入库
                                // (与 app 端 ImportReplaceRuleDialog 流程等价, 不再简化为直接入库)
                                val vm = ImportReplaceRuleVmAdapter(ImportReplaceRuleViewModelShared(scope))
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
            // 让用户勾选"新增/更新/已有"项后确定 importSelect 入库, 与 app 端 ImportReplaceRuleDialog
            // 流程等价; 取消/确定均触发 onDismiss → importVm=null 关闭 Dialog) ----
            importVm?.let { vm ->
                DesktopImportDialog(
                    title = importReplaceRuleLabel,
                    vm = vm,
                    initialText = importInitialText,
                    onDismiss = { importVm = null },
                )
            }

            // ---- 分组管理对话框 (onGroupManage 触发, 调用 shared/sharedUiMain 下沉的 GroupManageDialog) ----
            // ReplaceRule 分组是 String (逗号分隔), shared GroupManageDialog 期望 List<BookGroup>,
            // 用 groupEntities 做 String → BookGroup 适配 (groupId 用递增索引, 避免与系统分组 <=0 冲突);
            // onAddGroup/onRenameGroup/onDeleteGroup 委托 ReplaceRuleListViewModel.addGroup/upGroup/delGroup (String 签名)
            if (showGroupManage) {
                GroupManageDialog(
                    groups = groupEntities,
                    onAddGroup = { name -> viewModel.addGroup(name) },
                    onRenameGroup = { groupId, newName ->
                        groupEntities.find { it.groupId == groupId.toLong() }?.groupName?.let { oldName ->
                            viewModel.upGroup(oldName, newName)
                        }
                    },
                    onDeleteGroup = { groupId ->
                        groupEntities.find { it.groupId == groupId.toLong() }?.groupName?.let { name ->
                            viewModel.delGroup(name)
                        }
                    },
                    onDismiss = { showGroupManage = false },
                )
            }
        }
    }
}

/**
 * 从本地 JSON 文件读取替换规则文本 (对应 app 端 importReplaceRule 文件选择 + 读取)。
 *
 * 仅负责选文件 + 读文本, 不再解析+入库 (交给 ImportReplaceRuleViewModelShared.import 走完整比对
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
private suspend fun importReplaceRulesFromLocalFile(dialogTitle: String): String? {
    val json = withContext(Dispatchers.IO) {
        val dialog = FileDialog(Frame(), dialogTitle, FileDialog.LOAD)
        dialog.setFile("*.json")
        dialog.isVisible = true
        val file = dialog.files?.firstOrNull() ?: return@withContext null
        file.readText()
    } ?: run {
        AppLog.put(jvmGetString("import_replace_rule_user_canceled"))
        return null
    }
    return json
}

/**
 * 从网络 URL 导入替换规则 (对应 app 端 importReplaceRule 网络 URL 输入 + 下载 + 解析 + 入库)。
 *
 * 流程 (KP-Replace 改造后):
 * 1. URL 输入已改为 Compose AlertDialog (ReplaceRuleScreen 末尾渲染分支,
 *    替换原 [javax.swing.JOptionPane.showInputDialog])
 * 2. 用户确认后新建 [ImportReplaceRuleVmAdapter] (包装 [ImportReplaceRuleViewModelShared]),
 *    设置 importInitialText/importVm 触发 [DesktopImportDialog] 渲染
 * 3. DesktopImportDialog 的 LaunchedEffect 调 vm.startImport(url) 触发
 *    OkHttp 下载 → ReplaceAnalyzer 解析 → comparisonSource 比对
 * 4. 用户在 DesktopImportDialog 勾选"新增/更新/已有"项后 importSelect 入库
 *
 * (本函数已弃用, 实际导入逻辑由 ImportReplaceRuleViewModelShared.import +
 *  DesktopImportDialog 接管, 保留函数签名注释说明改造后的等价流程)
 */
/**
 * 导出替换规则到本地 JSON 文件 (对应 app 端 exportReplaceRule 文件保存)。
 *
 * 流程:
 * 1. 弹 [FileDialog] (SAVE 模式, 默认文件名 replace_rules.json) 选择保存路径
 * 2. 用 [GSON.toJson] 序列化选中规则为 JSON
 * 3. 写入文件 (UTF-8)
 *
 * 任何一步失败 (用户取消/IO 错) 都打印日志但不抛异常, 不中断主流程。
 */
private suspend fun exportReplaceRulesToLocalFile(rules: List<ReplaceRule>, dialogTitle: String) {
    if (rules.isEmpty()) {
        AppLog.put(jvmGetString("export_replace_rule_no_selection"))
        return
    }
    val targetPath = withContext(Dispatchers.IO) {
        val dialog = FileDialog(Frame(), dialogTitle, FileDialog.SAVE)
        dialog.setFile("replace_rules.json")
        dialog.isVisible = true
        val dir = dialog.directory ?: return@withContext null
        val file = dialog.file ?: return@withContext null
        dir + file
    } ?: run {
        AppLog.put("导出替换规则: 用户取消选择")
        return
    }
    val targetFile = File(targetPath)
    runCatching {
        withContext(Dispatchers.IO) {
            val json = GSON.toJson(rules)
            targetFile.writeText(json)
        }
        AppLog.put("导出替换规则完成, 共 ${rules.size} 条 → ${targetFile.absolutePath}")
    }.onFailure {
        AppLog.put("导出替换规则失败", it)
    }
}
