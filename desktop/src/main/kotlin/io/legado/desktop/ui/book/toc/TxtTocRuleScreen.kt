package io.legado.desktop.ui.book.toc

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultDataShared
import io.legado.app.ui.association.ImportTxtTocRuleViewModelShared
import io.legado.app.ui.book.toc.rule.TxtTocRuleEditDialog

import io.legado.app.ui.book.toc.rule.TxtTocRuleUiActions
import io.legado.app.ui.book.toc.rule.TxtTocRuleUiState
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.dialog.OnlineImportUrlDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
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
import io.legado.desktop.ui.association.DesktopImportDialog
import io.legado.desktop.ui.association.DesktopImportVm
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
 * TXT 目录规则管理 Screen 桌面端入口。
 *
 * 包装 shared/sharedUiMain 下沉的 [io.legado.app.ui.book.toc.rule.TxtTocRuleScreen], 注入桌面端 Compose
 * CompositionLocal Provider (ThemeStore/AppConfig/EventBus/PreferenceStore),
 * 并用 [AppTheme] 提供统一主题, 使 commonMain 的 RuleManageScaffold 骨架 +
 * 通用组件在桌面 JVM 上正常工作。
 *
 * # 路由回调 (由 DesktopApp 注入)
 * - [onBack]: 切回上一路由
 *
 * # 简化项 (依赖未下沉功能, 用 no-op + TODO 注释)
 * - 新建/编辑规则 (依赖 TxtTocRuleEditDialog, 未下沉): onAddRule/onEditRule no-op
 * - 帮助页跳转 (依赖 showHelp, 未下沉): no-op
 *
 * # 已实现的核心功能
 * - 列表加载 (observeAll 订阅)
 * - 本地导入 (FileDialog 选 JSON → DesktopImportVm.txtTocRule → DesktopImportDialog 比对入库)
 * - 网络导入 (AlertDialog 输入 URL → DesktopImportVm.txtTocRule → DesktopImportDialog 比对入库)
 * - 默认导入 (直接调用 DefaultDataShared.importDefaultTocRules, 与 app 端行为等价)
 * - 选中 / 全选 / 反选
 * - 单条删除 / 批量删除 (Screen 内 AppAlertDialog 确认后回调)
 * - 单条启用开关切换 / 批量启用/禁用
 * - 拖拽换位 + 松手落库 (重排 serialNumber)
 * - 单项置顶 / 置底 (minOrder-1 / maxOrder+1)
 * - 导出选中项 (FileDialog SAVE + GSON.toJson 写文件)
 *
 * @param onBack 返回回调 (由 DesktopApp 注入)
 */
@Composable
fun TxtTocRuleScreen(onBack: () -> Unit) {
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
                TxtTocRuleContent(onBack = onBack)
            }
        }
    }
}

@Composable
private fun TxtTocRuleContent(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // state: 规则列表 + 选中集合 (主键为 id: Long)
    val state = remember { mutableStateOf(TxtTocRuleUiState()) }

    // 编辑对话框状态 (showEditDialog=false 隐藏, true 显示; editingRule=null 新增, 非空 编辑)
    var showEditDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<TxtTocRule?>(null) }

    // ---- 导入相关状态 (参照 ReplaceRuleScreen/BookSourceScreen 模式) ----
    // 文案标签 (rememberString 是 @Composable, 顶层缓存后供 AlertDialog / suspend FileDialog 函数引用;
    //   未识别的 key 返回 key 本身, 与 ReplaceRuleScreen 保留原中文字面量策略一致)
    val txtTocRuleSelectJsonFileLabel = rememberString("txt_toc_rule_select_json_file")
    val txtTocRuleSaveJsonFileLabel = rememberString("txt_toc_rule_save_json_file")
    val importTxtTocRuleLabel = rememberString("import_txt_toc_rule")
    // 网络导入 URL 输入对话框状态 (onImportOnline 触发, 末尾 OnlineImportUrlDialog 渲染分支读取)
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    // 帮助文档对话框状态 (onHelp 触发)
    var showHelpDialog by remember { mutableStateOf(false) }
    // 导入 VM 适配器 (null=无导入任务, 非 null=渲染 DesktopImportDialog 让用户勾选比对);
    // 本地/网络导入均走 ImportTxtTocRuleViewModelShared.importSource 路径 (URL 下载/JSON 解析/
    // comparisonSource 比对), 成功后弹 DesktopImportDialog 让用户勾选"新增/更新/已有"项再 importSelect 入库,
    // 与 app 端 ImportTxtTocRuleDialog 流程等价
    var importVm by remember { mutableStateOf<DesktopImportVm?>(null) }
    // 导入初始文本 (URL 或 JSON), DesktopImportDialog 的 LaunchedEffect 用它调 vm.startImport
    var importInitialText by remember { mutableStateOf("") }

    // 收集全部 TXT 目录规则 (按 serialNumber 排序, DAO SQL 已排序)
    LaunchedEffect(Unit) {
        AppDatabaseProviders.get().appDb.txtTocRuleDao.observeAll()
            .catch { AppLog.put(jvmGetString("txt_toc_rule_load_data_failed_log", it.localizedMessage), it) }
            .flowOn(Dispatchers.IO)
            .conflate()
            .collectLatest { rules ->
                state.value = state.value.copy(tocRules = rules)
            }
    }

    // actions: 匿名 object 实现接口, 捕获 state(MutableState 引用稳定) + scope + onBack
    val actions = remember {
        object : TxtTocRuleUiActions {
            override fun onBack() = onBack()

            override fun onAddRule() {
                // 触发 TxtTocRuleEditDialog 显示 (rule=null 新增, onConfirm 走 dao.insert)
                editingRule = null
                showEditDialog = true
            }

            override fun onEditRule(item: TxtTocRule) {
                // 触发 TxtTocRuleEditDialog 显示 (编辑场景, onConfirm 走 dao.update)
                editingRule = item
                showEditDialog = true
            }

            override fun onImportLocal() {
                // 弹 FileDialog 选 JSON 文件 → 读文本 → ImportTxtTocRuleViewModelShared.importSource
                // → 弹 DesktopImportDialog 让用户勾选比对 (新增/更新/已有) 后 importSelect 入库
                // (与 app 端 ImportTxtTocRuleDialog 完整流程等价, 不再简化为 GSON.fromJsonArray 直接入库;
                //  local JSON 走 vm.importSource 路径以获得"新增/更新/已有"比对, 与网络导入一致)
                scope.launch {
                    val json = importTxtTocRulesFromLocalFile(txtTocRuleSelectJsonFileLabel) ?: return@launch
                    val vm = DesktopImportVm.txtTocRule(ImportTxtTocRuleViewModelShared(scope))
                    importInitialText = json
                    importVm = vm
                    // startImport 由 DesktopImportDialog 的 LaunchedEffect(vm) 触发
                }
            }

            override fun onImportOnline() {
                // 弹 OnlineImportUrlDialog (带 URL 历史) → 确认后走 DesktopImportDialog 勾选入库
                showImportOnlineDialog = true
            }

            override fun onImportDefault() {
                // 直接调用 shared 下沉的 DefaultDataShared.importDefaultTocRules (与 app 端
                // viewModel.importDefault() → DefaultData.importDefaultTocRules() 行为等价):
                // runBlocking { txtTocRuleDao.deleteDefault(); txtTocRuleDao.insert(*txtTocRules) }
                // 默认规则 JSON 走 DefaultDataResourceProviders 单一数据源 (app 端 assets 读取),
                // 不复制到 shared/commonMain/resources, 不走 DesktopImportDialog (与 app 端一致直接入库)
                scope.launch(Dispatchers.IO) {
                    runCatching { DefaultDataShared.importDefaultTocRules() }
                        .onFailure { AppLog.put(jvmGetString("import_default_txt_toc_rule_failed"), it) }
                }
            }

            override fun onHelp() {
                // 对应 app 端 showHelp("txtTocRuleHelp")
                showHelpDialog = true
            }

            override fun onToggleSelect(item: TxtTocRule, checked: Boolean) {
                state.value = state.value.copy(
                    selected = if (checked) state.value.selected + item.id
                    else state.value.selected - item.id
                )
            }

            override fun onSelectAll(all: Boolean) {
                state.value = state.value.copy(
                    selected = if (all) state.value.tocRules.map { it.id }.toSet()
                    else emptySet()
                )
            }

            override fun onRevertSelection() {
                state.value = state.value.copy(
                    selected = state.value.tocRules.map { it.id }.toSet() - state.value.selected
                )
            }

            override fun onMove(from: Int, to: Int) {
                state.value = state.value.copy(
                    tocRules = state.value.tocRules.toMutableList().apply { add(to, removeAt(from)) }
                )
            }

            override fun onPersistOrder() {
                // 松手落库: 按当前顺序重排 serialNumber 后整批 update
                val rules = state.value.tocRules
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val array = Array(rules.size) { i -> rules[i].copy(serialNumber = i + 1) }
                        AppDatabaseProviders.get().appDb.txtTocRuleDao.update(*array)
                    }
                }
            }

            override fun onToTop(item: TxtTocRule) {
                // 置顶: serialNumber 设为 minOrder - 1
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val minOrder = AppDatabaseProviders.get().appDb.txtTocRuleDao.minOrder()
                        AppDatabaseProviders.get().appDb.txtTocRuleDao.update(item.copy(serialNumber = minOrder - 1))
                    }
                }
            }

            override fun onToBottom(item: TxtTocRule) {
                // 置底: serialNumber 设为 maxOrder + 1
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val maxOrder = AppDatabaseProviders.get().appDb.txtTocRuleDao.maxOrder()
                        AppDatabaseProviders.get().appDb.txtTocRuleDao.update(item.copy(serialNumber = maxOrder + 1))
                    }
                }
            }

            override fun onEnableRule(item: TxtTocRule, enabled: Boolean) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabaseProviders.get().appDb.txtTocRuleDao.update(item.copy(enable = enabled))
                    }
                }
            }

            override fun onDel(item: TxtTocRule) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabaseProviders.get().appDb.txtTocRuleDao.delete(item)
                    }
                }
            }

            override fun onDelSelection() {
                val sel = state.value.tocRules.filter { it.id in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabaseProviders.get().appDb.txtTocRuleDao.delete(*sel.toTypedArray())
                    }
                }
            }

            override fun onEnableSelection(enabled: Boolean) {
                val sel = state.value.tocRules.filter { it.id in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val array = Array(sel.size) { i -> sel[i].copy(enable = enabled) }
                        AppDatabaseProviders.get().appDb.txtTocRuleDao.update(*array)
                    }
                }
            }

            override fun onExportSelection() {
                // 导出选中项: 弹 FileDialog SAVE → GSON.toJson → 写文件
                val sel = state.value.tocRules.filter { it.id in state.value.selected }
                if (sel.isEmpty()) return
                scope.launch {
                    exportTxtTocRulesToLocalFile(sel, txtTocRuleSaveJsonFileLabel)
                }
            }
        }
    }

    io.legado.app.ui.book.toc.rule.TxtTocRuleScreen(state.value, actions)

    // ---- 导入对话框 (本地/网络导入共用, importVm 非 null 时渲染 DesktopImportDialog
    // 让用户勾选"新增/更新/已有"项后确定 importSelect 入库, 与 app 端 ImportTxtTocRuleDialog
    // 流程等价; 取消/确定均触发 onDismiss → importVm=null 关闭 Dialog) ----
    importVm?.let { vm ->
        DesktopImportDialog(
            title = importTxtTocRuleLabel,
            vm = vm,
            initialText = importInitialText,
            onDismiss = { importVm = null },
        )
    }

    // ---- 网络导入 URL 输入对话框 (onImportOnline 触发, 替换原 javax.swing.JOptionPane.showInputDialog) ----
    // 用户确认后新建 DesktopImportVm.txtTocRule + 设置 importVm 触发 DesktopImportDialog 渲染,
    // Dialog 的 LaunchedEffect(vm) 调 vm.startImport(url) 触发下载 → 解析 → comparisonSource 比对,
    // 成功后让用户勾选"新增/更新/已有"项再 importSelect 入库 (与 app 端 ImportTxtTocRuleDialog 流程等价)
    // 网络导入 URL 输入对话框 (带历史下拉, 对照 app 端 TxtTocRuleActivity.showImportDialog:
    // 默认 URL 不在历史时插首位; 确认后新建 DesktopImportVm.txtTocRule 触发 DesktopImportDialog 勾选入库)
    if (showImportOnlineDialog) {
        OnlineImportUrlDialog(
            recordKey = "tocRuleUrl",
            defaultUrl = "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json",
            onConfirm = { url ->
                val vm = DesktopImportVm.txtTocRule(ImportTxtTocRuleViewModelShared(scope))
                importInitialText = url
                importVm = vm
            },
            onDismiss = { showImportOnlineDialog = false },
        )
    }

    // ---- 帮助文档对话框 (onHelp 触发, 渲染 txtTocRuleHelp.md) ----
    if (showHelpDialog) {
        HelpDialog(fileName = "txtTocRuleHelp", onDismiss = { showHelpDialog = false })
    }

    // ---- TXT 目录规则编辑对话框 (onAddRule/onEditRule 触发) ----
    // 剪贴板桥接用 AWT Toolkit (替代 app 端 getClipText/sendToClip):
    // - clipTextProvider: 读系统剪贴板文本 (供"粘贴规则"菜单项用)
    // - clipTextSink: 写系统剪贴板文本 (供"复制规则"菜单项用)
    // onConfirm 落库: 新增 (editingRule=null) 走 dao.insert, 编辑走 dao.update
    // (TxtTocRuleEditDialog 内部仅校验+组装, 不落库, 由调用方负责)
    if (showEditDialog) {
        // Dialog 外壳补遮罩/居中 (TxtTocRuleEditDialog 根是裸 Surface)
        Dialog(onDismissRequest = { showEditDialog = false }) {
            TxtTocRuleEditDialog(
                rule = editingRule,
                onConfirm = { newRule ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val txtDao = AppDatabaseProviders.get().appDb.txtTocRuleDao
                            if (editingRule == null) txtDao.insert(newRule) else txtDao.update(newRule)
                        }
                    }
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
    }
}

/**
 * 从本地 JSON 文件读取 TXT 目录规则文本 (对应 app 端 importDoc + ImportTxtTocRuleDialog 文件读取)。
 *
 * 仅负责选文件 + 读文本, 不再解析+入库 (交给 ImportTxtTocRuleViewModelShared.importSource 走完整比对
 * 流程, 让用户在 DesktopImportDialog 勾选"新增/更新/已有"后 importSelect 入库)。
 *
 * 流程:
 * 1. 弹 [FileDialog] (LOAD 模式, 过滤 .json) 选择文件
 * 2. 读文件内容为 String (UTF-8) 返回给调用方
 *
 * 任何一步失败 (用户取消/IO 错) 都打印日志但不抛异常, 返回 null 让调用方跳过。
 *
 * @param dialogTitle 文件选择对话框标题 (由调用方 rememberString 缓存传入)
 * @return JSON 文本, 用户取消/IO 错时返回 null
 */
private suspend fun importTxtTocRulesFromLocalFile(dialogTitle: String): String? {
    val json = withContext(Dispatchers.IO) {
        val dialog = FileDialog(Frame(), dialogTitle, FileDialog.LOAD)
        dialog.setFile("*.json")
        dialog.isVisible = true
        val file = dialog.files?.firstOrNull() ?: return@withContext null
        file.readText()
    } ?: run {
        AppLog.put(jvmGetString("import_txt_toc_rule_user_cancelled"))
        return null
    }
    return json
}

/**
 * 导出 TXT 目录规则到本地 JSON 文件 (对应 app 端 onExportSelection + HandleFileContract EXPORT)。
 *
 * 弹 FileDialog SAVE 选路径 → GSON.toJson 序列化 → 写文件 (UTF-8)。
 * 用户取消/IO 错打印日志不抛异常, 不中断主流程。
 */
private suspend fun exportTxtTocRulesToLocalFile(rules: List<TxtTocRule>, dialogTitle: String) {
    if (rules.isEmpty()) {
        AppLog.put(jvmGetString("export_txt_toc_rule_no_selection"))
        return
    }
    val targetPath = withContext(Dispatchers.IO) {
        val dialog = FileDialog(Frame(), dialogTitle, FileDialog.SAVE)
        dialog.setFile("exportTxtTocRule.json")
        dialog.isVisible = true
        val dir = dialog.directory ?: return@withContext null
        val file = dialog.file ?: return@withContext null
        dir + file
    } ?: run {
        AppLog.put(jvmGetString("export_txt_toc_rule_user_cancelled"))
        return
    }
    val targetFile = File(targetPath)
    runCatching {
        withContext(Dispatchers.IO) {
            val json = GSON.toJson(rules)
            targetFile.writeText(json)
        }
        AppLog.put(jvmGetString("export_txt_toc_rule_done_log", rules.size, targetFile.absolutePath))
    }.onFailure {
        AppLog.put(jvmGetString("export_txt_toc_rule_failed"), it)
    }
}
