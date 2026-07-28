package io.legado.desktop.ui.association

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.entities.RuleSub
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportBookSourceViewModelShared
import io.legado.app.ui.association.ImportDictRuleViewModelShared
import io.legado.app.ui.association.ImportHttpTtsViewModelShared
import io.legado.app.ui.association.ImportReplaceRuleViewModelShared
import io.legado.app.ui.association.ImportTxtTocRuleViewModelShared

import io.legado.app.ui.association.RuleSubUiActions
import io.legado.app.ui.association.RuleSubUiState
import io.legado.app.ui.association.RuleSubViewModelShared
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppOutlinedTextField
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
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

/**
 * 规则订阅管理 Screen 桌面端入口。
 *
 * 包装 shared/sharedUiMain 下沉的 [io.legado.app.ui.association.RuleSubScreen], 注入桌面端 Compose
 * CompositionLocal Provider (ThemeStore/AppConfig/EventBus/PreferenceStore),
 * 并用 [AppTheme] 提供统一主题, 使 commonMain 的 RuleManageScaffold 骨架 +
 * 通用组件在桌面 JVM 上正常工作。
 *
 * # 路由回调 (由 DesktopApp 注入)
 * - [onBack]: 切回上一路由
 *
 * # 已实现的核心功能
 * - 新增/编辑订阅 (DesktopRuleSubEditDialog: 基于 shared AppAlertDialog + 表单字段,
 *   对齐 app 端 RuleSubActivity.showEditDialog): onAdd/onEdit 弹窗
 * - 列表加载 (flowAll 订阅)
 * - 拖拽换位 + 松手落库 (重排 customOrder)
 * - 单项置顶 / 置底 (customOrder = 0 / maxOrder + 1)
 * - 单项删除
 * - 打开订阅 (onOpenSubscription): 按 ruleSub.type 0-5 分流到对应导入流程
 *   (P2-3 任务5: 0,1→书源 / 2→替换规则 / 3→TxtTocRule / 4→DictRule / 5→HttpTts),
 *   新建对应 ImportXxxViewModelShared + 适配器, 弹 [DesktopImportDialog] 让用户勾选后入库
 *
 * @param onBack 返回回调 (由 DesktopApp 注入)
 */
@Composable
fun RuleSubScreen(onBack: () -> Unit) {
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
                RuleSubContent(onBack = onBack)
            }
        }
    }
}

@Composable
private fun RuleSubContent(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // state: 订阅规则列表 (无选中集合, 主键 id: Long)
    val state = remember { mutableStateOf(RuleSubUiState()) }
    // 共享核心 VM (KMP), 注入 Compose 协程作用域, 转发 5 个 DAO 写方法
    // (替代原 actions 内直接 scope.launch + withContext + AppDatabaseProviders 调用,
    //  与 app 端 RuleSubViewModel 走同一份下沉实现, 行为对齐)
    val shared = remember { RuleSubViewModelShared(scope = scope) }

    // ---- 导入对话框状态 (P2-3 任务5: onOpenSubscription 按 type 0-5 分流) ----
    // importVm: null=无导入任务, 非 null=渲染 DesktopImportDialog 让用户勾选比对后入库;
    // importInitialText: 订阅 URL, DesktopImportDialog 的 LaunchedEffect 调 vm.startImport(url);
    // importTitle: 导入对话框标题 (随 type 变化, 对照 app 端 ImportXxxDialog 的 R.string.import_xxx)
    val importVm = remember { mutableStateOf<DesktopImportVm?>(null) }
    val importInitialText = remember { mutableStateOf("") }
    val importTitle = remember { mutableStateOf("") }
    // 导入对话框标题文案 (rememberString 是 @Composable, 顶层 remember 后供 onOpenSubscription lambda 引用;
    // key 对齐 app 端各 ImportXxxDialog 的 R.string.import_xxx, 未注册 key 返回 key 本身)
    val importBookSourceLabel = rememberString("import_book_source")
    val importReplaceRuleLabel = rememberString("import_replace_rule")
    val importTxtTocRuleLabel = rememberString("import_txt_toc_rule")
    val importDictRuleLabel = rememberString("import_dict_rule")
    val importHttpTtsLabel = rememberString("import_http_tts")

    // ---- 编辑对话框状态 (onAdd/onEdit 触发, editRuleSub 非 null 时渲染 DesktopRuleSubEditDialog
    // 让用户编辑 type/name/url 后确定保存, 对齐 app 端 RuleSubActivity.showEditDialog) ----
    val editRuleSub = remember { mutableStateOf<RuleSub?>(null) }

    // 收集全部规则订阅 (按 customOrder 排序, DAO SQL 已排序)
    LaunchedEffect(Unit) {
        AppDatabaseProviders.get().appDb.ruleSubDao.flowAll()
            .catch { AppLog.put(jvmGetString("rule_sub_load_failed", it.localizedMessage), it) }
            .flowOn(Dispatchers.IO)
            .conflate()
            .collectLatest { ruleSubs ->
                state.value = state.value.copy(ruleSubs = ruleSubs)
            }
    }

    // actions: 匿名 object 实现接口, 捕获 state(MutableState 引用稳定) + shared + onBack +
    // importVm/importInitialText/importTitle + 标题 labels (均为稳定引用, remember{} 无需 keys)
    val actions = remember {
        object : RuleSubUiActions {
            override fun onBack() = onBack()

            override fun onAdd() {
                // 新增: 空白 RuleSub 触发编辑弹窗 (对齐 app 端 showEditDialog(RuleSub()))
                editRuleSub.value = RuleSub()
            }

            override fun onEdit(ruleSub: RuleSub) {
                // 编辑: 传入既有 ruleSub 触发编辑弹窗 (对齐 app 端 showEditDialog(ruleSub))
                editRuleSub.value = ruleSub
            }

            override fun onOpenSubscription(ruleSub: RuleSub) {
                // 按 ruleSub.type 0-5 分流到对应导入流程 (对照 app 端 RuleSubActivity.openSubscription):
                //   0, 1 → ImportBookSourceDialog (书源, type 0=默认/type 1=RSS 也走书源导入)
                //   2 → ImportReplaceRuleDialog (替换规则)
                //   3 → ImportTxtTocRuleDialog (TXT 目录规则)
                //   4 → ImportDictRuleDialog (字典规则)
                //   5 → ImportHttpTtsDialog (语音源)
                //   else → toast 错误 (与 app 端 toastOnUi(R.string.error) 一致)
                // URL 校验: 非 http(s) 绝对 URL 时 toast 提示 (与 app 端 !url.isAbsUrl() → toast 一致)
                if (!ruleSub.url.isAbsUrl()) {
                    Toasters.get().toast(jvmGetString("input_valid_url"))
                    return
                }
                // 按 type 分流: 新建对应 ImportXxxViewModelShared + 适配器, 设置 importVm/importInitialText/
                // importTitle 触发 DesktopImportDialog 渲染, LaunchedEffect(vm) 调 vm.startImport(url)
                // 触发下载+解析+comparisonSource 比对, 用户勾选后 importSelect 入库
                when (ruleSub.type) {
                    0, 1 -> {
                        importTitle.value = importBookSourceLabel
                        importInitialText.value = ruleSub.url
                        importVm.value = DesktopImportVm.bookSource(ImportBookSourceViewModelShared(scope))
                    }
                    2 -> {
                        importTitle.value = importReplaceRuleLabel
                        importInitialText.value = ruleSub.url
                        importVm.value = DesktopImportVm.replaceRule(ImportReplaceRuleViewModelShared(scope))
                    }
                    3 -> {
                        importTitle.value = importTxtTocRuleLabel
                        importInitialText.value = ruleSub.url
                        importVm.value = DesktopImportVm.txtTocRule(ImportTxtTocRuleViewModelShared(scope))
                    }
                    4 -> {
                        importTitle.value = importDictRuleLabel
                        importInitialText.value = ruleSub.url
                        importVm.value = DesktopImportVm.dictRule(ImportDictRuleViewModelShared(scope))
                    }
                    5 -> {
                        importTitle.value = importHttpTtsLabel
                        importInitialText.value = ruleSub.url
                        importVm.value = DesktopImportVm.httpTts(ImportHttpTtsViewModelShared(scope))
                    }
                    else -> {
                        Toasters.get().toast("error")
                    }
                }
            }

            override fun onMove(from: Int, to: Int) {
                state.value = state.value.copy(
                    ruleSubs = state.value.ruleSubs.toMutableList().apply { add(to, removeAt(from)) }
                )
            }

            override fun onPersistOrder() {
                // 松手落库: 按当前顺序重排 customOrder 后整批 update (转发到 shared.upOrder)
                shared.upOrder(state.value.ruleSubs)
            }

            override fun onToTop(ruleSub: RuleSub) {
                // 置顶: customOrder 设为 minOrder - 1 (转发到 shared.toTop, 与 app 端行为一致)
                shared.toTop(ruleSub)
            }

            override fun onToBottom(ruleSub: RuleSub) {
                // 置底: customOrder 设为 maxOrder + 1 (转发到 shared.toBottom, 与 app 端行为一致)
                shared.toBottom(ruleSub)
            }

            override fun onDelete(ruleSub: RuleSub) {
                shared.delete(ruleSub)
            }
        }
    }

    io.legado.app.ui.association.RuleSubScreen(state.value, actions)

    // ---- 导入对话框 (onOpenSubscription 触发, importVm 非 null 时渲染 DesktopImportDialog
    // 让用户勾选"新增/更新/已有"项后确定 importSelect 入库, 与 app 端各 ImportXxxDialog 流程等价;
    // 取消/确定均触发 onDismiss → importVm=null 关闭 Dialog) ----
    importVm.value?.let { vm ->
        DesktopImportDialog(
            title = importTitle.value,
            vm = vm,
            initialText = importInitialText.value,
            onDismiss = { importVm.value = null },
        )
    }

    // ---- 编辑对话框 (onAdd/onEdit 触发, editRuleSub 非 null 时渲染 DesktopRuleSubEditDialog
    // 让用户编辑 type/name/url, 确定后校验通过则 shared.save 入库, 对齐 app 端 showEditDialog) ----
    editRuleSub.value?.let { rs ->
        DesktopRuleSubEditDialog(
            ruleSub = rs,
            onDismiss = { editRuleSub.value = null },
            onSave = {
                shared.save(it)
                editRuleSub.value = null
            },
        )
    }
}

/**
 * 规则订阅新增/编辑对话框 (桌面端)。
 *
 * 对照 app 端 [io.legado.app.ui.association.RuleSubActivity.showEditDialog]: app 端用
 * lib/dialogs 的 `alert { customView {} }` DSL 内联构建表单, 桌面端无该 DSL, 改用
 * shared/sharedUiMain 的 [AppAlertDialog] (content 槽 = customView) + [AppDropdownMenu] +
 * [AppOutlinedTextField] 等价实现。
 *
 * # 表单字段 (与 app 端 customView 完全一致)
 * - type: 0-5 下拉 (typeName 映射复用 shared RuleSubScreen 同款 rememberString key, 保证
 *   与列表显示一致)
 * - name: 单行输入
 * - url: 单行输入
 *
 * # 校验 (对齐 app 端 okButton)
 * - name 非空 && url 是绝对 URL, 否则 toast 提示且不关闭对话框 (okButton.dismissOnClick=false,
 *   对齐 app 端 `return@okButton` 保留对话框语义)
 *
 * @param ruleSub 待编辑订阅 (onAdd 传空白 RuleSub, onEdit 传既有项)
 * @param onDismiss 关闭回调 (取消/保存完成均触发, 调用方清空 editRuleSub 隐藏 Dialog)
 * @param onSave 保存回调 (校验通过后调用, 调用方内部 shared.save(ruleSub) + 关闭)
 */
@Composable
private fun DesktopRuleSubEditDialog(
    ruleSub: RuleSub,
    onDismiss: () -> Unit,
    onSave: (RuleSub) -> Unit,
) {
    // 表单状态 (初始化自 ruleSub; type 限制 0-5, 对齐 app 端 coerceIn(0, 5))
    var type by remember { mutableStateOf(ruleSub.type.coerceIn(0, 5)) }
    var name by remember { mutableStateOf(ruleSub.name) }
    var url by remember { mutableStateOf(ruleSub.url) }

    // 文案 (rememberString key 对齐 app 端 R.string.xxx 与 shared typeName)
    val addLabel = rememberString("add")
    val editLabel = rememberString("edit")
    val bookTypeLabel = rememberString("book_type")
    val nameFieldLabel = rememberString("name")
    val nonNullNameUrlLabel = rememberString("non_null_name_url")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")

    AppAlertDialog(
        onDismissRequest = onDismiss,
        // name 空=新增, 否则编辑 (对齐 app 端 if (ruleSub.name.isEmpty()) R.string.add else R.string.edit)
        title = if (ruleSub.name.isEmpty()) addLabel else editLabel,
        okButton = AlertButton(
            text = okLabel,
            // 手动控制关闭: 校验失败时不关闭 (对齐 app 端 return@okButton)
            dismissOnClick = false,
            onClick = {
                val n = name.trim()
                val u = url.trim()
                if (n.isEmpty() || !u.isAbsUrl()) {
                    Toasters.get().toast(nonNullNameUrlLabel)
                } else {
                    ruleSub.name = n
                    ruleSub.url = u
                    ruleSub.type = type
                    onSave(ruleSub)
                }
            },
        ),
        cancelButton = AlertButton(text = cancelLabel),
        content = {
            // 表单 (与 app 端 customView Column 结构一致: 类型行 + name + url)
            Column(Modifier.padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bookTypeLabel,
                        color = AppTheme.colors.accent,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        Text(
                            typeName(type),
                            color = AppTheme.colors.primaryText,
                            modifier = Modifier
                                .clickable { expanded = true }
                                .padding(8.dp),
                        )
                        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            (0..5).forEach { t ->
                                DropdownMenuItem(
                                    onClick = { type = t; expanded = false },
                                ) {
                                    Text(typeName(t), color = AppTheme.colors.primaryText)
                                }
                            }
                        }
                    }
                }
                AppOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = nameFieldLabel,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppOutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "Url",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/**
 * 订阅类型 → 显示名称 (复用 shared RuleSubScreen.typeName 同款 rememberString key,
 * 保证编辑弹窗下拉项与列表 typeName 显示一致)。
 */
@Composable
private fun typeName(type: Int): String = when (type) {
    1 -> rememberString("rss_source")
    2 -> rememberString("replace_rule")
    3 -> rememberString("txt_toc_rule")
    4 -> rememberString("dict_rule")
    5 -> rememberString("tts")
    else -> rememberString("book_source")
}
