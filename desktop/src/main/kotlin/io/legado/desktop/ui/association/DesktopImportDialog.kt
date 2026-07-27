package io.legado.desktop.ui.association

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import io.legado.desktop.ui.component.DialogSizes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.config.ThemeConfigData
import io.legado.app.ui.association.ImportBookSourceViewModelShared
import io.legado.app.ui.association.ImportDictRuleViewModelShared
import io.legado.app.ui.association.ImportHttpTtsViewModelShared
import io.legado.app.ui.association.ImportListScaffold
import io.legado.app.ui.association.ImportReplaceRuleViewModelShared
import io.legado.app.ui.association.ImportSourceFilterRuleViewModelShared
import io.legado.app.ui.association.ImportThemeViewModelShared
import io.legado.app.ui.association.ImportTxtTocRuleViewModelShared
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.utils.toJson
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 桌面端统一导入对话框 (P2-3 子任务核心)。
 *
 * # 背景
 *
 * 对照 app 端 7 个 `ImportXxxDialog : BaseComposeDialogFragment` (书源/替换规则/TxtToc/
 * Dict/HttpTts/主题/SourceFilterRule), 桌面端无 Fragment, 改为纯 Compose [Dialog]
 * (参照 [io.legado.desktop.ui.config.ThemeListDialog] 模式: `androidx.compose.ui.window.Dialog`
 * + `Surface` 包裹内容)。
 *
 * # 复用 shared 基础设施
 *
 * - 列表骨架直接复用 shared/sharedUiMain 的 [ImportListScaffold] (标题栏+菜单槽+加载/错误态+
 *   LazyColumn 列表+底部全选/取消/确定), 各 ImportXxxDialog 数据结构不同 (allSources/allRules、
 *   checkSources/checkRules), 故列表项 label/state/checked 由 [ImportListScaffoldVm] 适配器
 *   统一暴露;
 * - 8 个 `ImportXxxViewModelShared` (shared commonMain) 通过 [ImportListScaffoldVm] 适配器注入,
 *   各 VM 的 `importSource`/`import` 方法名差异 (替换规则/SourceFilterRule 用 `import`,
 *   其余用 `importSource`) 由适配器 [ImportListScaffoldVm.startImport] 统一;
 * - `errorState`/`successState` 走 `StateFlow`, 在 [LaunchedEffect] 内 collect 触发 UI 状态
 *   (loading/error/version) 更新 (对照 app 端 `errorLiveData.observe { error = it }`)。
 *
 * # 与 app 端差异
 *
 * - **Provider 注入**: app 端 Fragment 天然在 Activity 主题上下文内; 桌面端 [Dialog] 是
 *   独立窗口, 需在内部注入 [DesktopThemeStoreProvider]/[DesktopAppConfigProvider]/
 *   [DesktopEventBusProvider] 让 [AppTheme]/[rememberString]/[ImportListScaffold] 正常工作
 *   (参照 [io.legado.desktop.ui.book.changesource.ChangeChapterSourceScreen] 第 96-104 行模式);
 * - **Dialog 容器**: app 端 `DialogFragment` + Window; 桌面端 `androidx.compose.ui.window.Dialog`
 *   + `Surface` (圆角 16.dp = ArcoRadiusLg, 无 elevation, 与 [ThemeListDialog] 一致);
 * - **onOpen**: app 端弹 `CodeDialog` (可编辑+回写 allSources[index]); `CodeDialog` 未下沉,
 *   桌面端用 [AppAlertDialog] 只读预览 JSON + TODO 标注 (不影响 importSelect 核心流程);
 * - **WaitDialog**: app 端 `onImport` 用 `WaitDialog` 显示导入进度; 桌面端未下沉 WaitDialog,
 *   importSelect 是 DB 批量 insert + adjustSortNumber (通常 <1s), 直接调 finally 关闭对话框;
 * - **error 文案**: app 端直接显示 `errorLiveData` 原值 (`"ImportError:xxx"`);
 *   桌面端清理 `ImportError:` 前缀让用户看到清晰错误 (与 BookSourceScreen 网络 toast 一致)。
 *
 * # 使用方式
 *
 * ```kotlin
 * val scope = rememberCoroutineScope()
 * val vm = remember { ImportBookSourceVmAdapter(ImportBookSourceViewModelShared(scope)) }
 * DesktopImportDialog(
 *     title = rememberString("import_book_source"),
 *     vm = vm,
 *     initialText = url,  // URL 或 JSON 文本
 *     onDismiss = { importDialogVm = null },
 * )
 * ```
 *
 * @param title 对话框标题 (如 "导入书源", 由调用方 rememberString 缓存)
 * @param vm 导入 VM 适配器 (持有 shared ImportXxxViewModelShared 实例)
 * @param initialText 初始导入文本 (URL/JSON), Dialog 显示时由 [LaunchedEffect] 自动触发 startImport
 * @param onDismiss 关闭回调 (取消/导入完成均触发, 调用方清空 vm 引用隐藏 Dialog)
 */
@Composable
fun DesktopImportDialog(
    title: String,
    vm: ImportListScaffoldVm,
    initialText: String,
    onDismiss: () -> Unit,
) {
    // 注入 desktop 平台 Provider (参照 ChangeChapterSourceScreen 第 96-104 行模式;
    // Dialog 是独立窗口, 即使外层已注入 Provider, 此处再注入保证一致性, 避免在某些无 Provider
    // 上下文调用时 NPE。重复 provides 不会报错, 仅覆盖外层引用)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            Dialog(onDismissRequest = onDismiss) {
                Surface(
                    // ArcoRadiusLg=16.dp, 无 elevation, 与 ThemeListDialog 一致 (Arco Design 规范)
                    shape = RoundedCornerShape(16.dp),
                    color = AppTheme.colors.background,
                    modifier = Modifier.widthIn(max = DialogSizes.dialogMaxWidth()).heightIn(max = DialogSizes.dialogFullHeight()),
                ) {
                    // ---- 导入状态 (对照 app 端 ImportBookSourceDialog.Content 的 loading/error/version) ----
                    var loading by remember { mutableStateOf(true) }
                    var error by remember { mutableStateOf<String?>(null) }
                    // version 自增触发重组刷新 selectStatus 变化 (VM 的 selectStatus 是 arrayListOf,
                    // 修改不触发 Compose 重组, 需 version++ 手动驱动, 与 app 端 version 机制一致)
                    var version by remember { mutableIntStateOf(0) }
                    // onOpen 显示的 JSON (CodeDialog 未下沉, 用 AppAlertDialog 只读预览)
                    var openJson by remember { mutableStateOf<String?>(null) }
                    @Suppress("UNUSED_EXPRESSION")
                    version // 订阅触发重组

                    // 文案 (rememberString 是 @Composable, 顶层 remember 后供 LaunchedEffect lambda 引用)
                    val wrongFormatText = rememberString("wrong_format")
                    val openLabel = rememberString("open")
                    val okLabel = rememberString("ok")

                    // 监听 VM errorState/successState, 触发 loading/error/version 更新
                    // (对照 app 端 `viewModel.errorLiveData.observe { loading=false; error=it }`)
                    LaunchedEffect(vm) {
                        // 触发导入 (URL 下载/JSON 解析/comparisonSource 比对), 与 app 端
                        // `if (source.isNullOrEmpty()) dismiss() else viewModel.importSource(source)` 一致
                        vm.startImport(initialText)
                        launch {
                            vm.errorState.collect { err ->
                                loading = false
                                // 清理 "ImportError:" 前缀 (app 端直接显示原值, 桌面端清理提升可读性,
                                // 与 BookSourceScreen 网络导入 toast 的 removePrefix 一致)
                                error = err?.removePrefix("ImportError:")
                            }
                        }
                        launch {
                            vm.successState.collect { size ->
                                loading = false
                                if (size != null) {
                                    if (size > 0) {
                                        // 解析成功, version++ 触发列表刷新显示 allSources
                                        version++
                                    } else {
                                        // size==0 表示解析无结果, 提示格式不对
                                        error = wrongFormatText
                                    }
                                }
                            }
                        }
                    }

                    ImportListScaffold(
                        title = title,
                        loading = loading,
                        errorText = error,
                        itemCount = vm.itemCount,
                        selectCount = vm.selectCount,
                        isSelectAll = vm.isSelectAll,
                        itemLabel = { vm.itemLabel(it) },
                        itemState = { vm.itemState(it) },
                        itemChecked = { vm.itemChecked(it) },
                        onItemChecked = { index, checked ->
                            vm.setItemChecked(index, checked)
                            version++
                        },
                        onOpen = { openJson = vm.itemJson(it) },
                        onToggleAll = {
                            vm.toggleAll()
                            version++
                        },
                        onCancel = onDismiss,
                        onOk = {
                            // 导入选中项 (对照 app 端 onImport: waitDialog.show + importSelect { dismiss });
                            // desktop 端无 WaitDialog, importSelect 的 finally 直接关闭对话框
                            vm.importSelect(onDismiss)
                        },
                    )

                    // ---- onOpen JSON 只读预览 (CodeDialog 未下沉, 桌面端简化为只读 AppAlertDialog) ----
                    // TODO: CodeDialog (可编辑+回写 allSources[index]) 下沉后替换为可编辑版本
                    openJson?.let { json ->
                        AppAlertDialog(
                            onDismissRequest = { openJson = null },
                            title = openLabel,
                            message = json,
                            okButton = AlertButton(text = okLabel) { openJson = null },
                        )
                    }
                }
            }
        }
    }
}

// =====================================================================================
// ImportListScaffoldVm 适配器接口
// =====================================================================================

/**
 * 导入对话框 VM 统一接口 (适配 shared 各 ImportXxxViewModelShared 的字段/方法名差异)。
 *
 * # 设计动机
 *
 * 各 ImportXxxViewModelShared 的字段名不一致 (BookSource/TxtToc/Dict/HttpTts/Theme 用
 * `allSources`/`checkSources`; ReplaceRule/SourceFilterRule 用 `allRules`/`checkRules`),
 * 入口方法名也不一致 (ReplaceRule/SourceFilterRule 用 `import(text)`, 其余用 `importSource(text)`)。
 * 用泛型 `<T>` 封装过于复杂 (需同时约束实体类型 + 列表字段 + 比对逻辑), 改用接口适配器模式:
 * 每个 shared VM 对应一个适配器 class, 持有 VM 引用, 把字段访问/方法调用统一映射到接口方法。
 *
 * # 对照 app 端
 *
 * app 端每个 ImportXxxDialog.Content() 直接访问 `viewModel.allSources`/`viewModel.selectStatus` 等,
 * 没有统一接口 (因为各 Dialog 是独立 Fragment)。desktop 端统一用 [DesktopImportDialog] 渲染,
 * 故需接口抹平差异。
 */
interface ImportListScaffoldVm {
    /** 导入错误信息流 (对照 shared VM `errorState: StateFlow<String?>`)。 */
    val errorState: StateFlow<String?>

    /** 导入成功信号流 (对照 shared VM `successState: StateFlow<Int?>`, 值为列表 size)。 */
    val successState: StateFlow<Int?>

    /** 待导入列表项数 (allSources.size 或 allRules.size)。 */
    val itemCount: Int

    /** 选中数量 (对照 shared VM `selectCount`)。 */
    val selectCount: Int

    /** 是否全部选中 (对照 shared VM `isSelectAll`)。 */
    val isSelectAll: Boolean

    /** 列表项显示文案 (书源名/规则名等, 对照 app 端 `itemLabel = { viewModel.allSources[it].bookSourceName }`)。 */
    fun itemLabel(index: Int): String

    /** 列表项状态文案 ("新增"/"更新"/"已有", 对照 app 端 `itemState = { ... when { ... } }`)。 */
    fun itemState(index: Int): String

    /** 列表项是否选中 (对照 `viewModel.selectStatus[it]`)。 */
    fun itemChecked(index: Int): Boolean

    /** 设置列表项选中状态 (对照 `viewModel.selectStatus[index] = checked`)。 */
    fun setItemChecked(index: Int, checked: Boolean)

    /** 全选/全不选切换 (对照 app 端 `toggleAll()`)。 */
    fun toggleAll()

    /** 导入选中项 (对照 shared VM `importSelect(finally)`, finally 在导入完成时回调)。 */
    fun importSelect(finally: () -> Unit)

    /** 触发导入 (URL 下载/JSON 解析/comparisonSource 比对; 对照 `viewModel.importSource(text)` 或 `import(text)`)。 */
    fun startImport(text: String)

    /** 取列表项 JSON (用于 onOpen 预览; app 端用 CodeDialog 显示+编辑, desktop 端只读预览)。 */
    fun itemJson(index: Int): String
}

// =====================================================================================
// 各 ImportXxxViewModelShared 的适配器
// (itemLabel/itemState 实现逐项对照 app 端各 ImportXxxDialog.Content 的同名 lambda)
// =====================================================================================

/**
 * 书源导入 VM 适配器 (对照 app 端 [io.legado.app.ui.association.ImportBookSourceDialog])。
 *
 * - itemLabel: `allSources[it].bookSourceName`
 * - itemState: 按 `checkSources[it]` 与 `allSources[it].lastUpdateTime` 比对 (新增/更新/已有)
 */
class ImportBookSourceVmAdapter(
    private val vm: ImportBookSourceViewModelShared,
) : ImportListScaffoldVm {
    override val errorState: StateFlow<String?> = vm.errorState
    override val successState: StateFlow<Int?> = vm.successState
    override val itemCount: Int get() = vm.allSources.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String = vm.allSources[index].bookSourceName

    override fun itemState(index: Int): String {
        val local = vm.checkSources[index]
        return when {
            local == null -> "新增"
            vm.allSources[index].lastUpdateTime > local.lastUpdateTime -> "更新"
            else -> "已有"
        }
    }

    override fun itemChecked(index: Int): Boolean = vm.selectStatus[index]

    override fun setItemChecked(index: Int, checked: Boolean) {
        vm.selectStatus[index] = checked
    }

    override fun toggleAll() {
        // 与 app 端 ImportBookSourceDialog.toggleAll 完全一致 (含 if 条件, 不"偷懒"简化)
        val selectAll = vm.isSelectAll
        vm.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) vm.selectStatus[index] = !selectAll
        }
    }

    override fun importSelect(finally: () -> Unit) = vm.importSelect(finally)

    override fun startImport(text: String) = vm.importSource(text)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])
}

/**
 * 替换规则导入 VM 适配器 (对照 app 端 [io.legado.app.ui.association.ImportReplaceRuleDialog])。
 *
 * - 注意: ReplaceRule VM 用 `allRules`/`checkRules` (非 allSources/checkSources),
 *   入口方法是 `import(text)` (非 importSource)
 * - itemLabel: `allRules[it]` name + group (group 非空时显示 "name(group)")
 * - itemState: 按 pattern/replacement/isRegex/scope 比对 (新增/更新/已有)
 */
class ImportReplaceRuleVmAdapter(
    private val vm: ImportReplaceRuleViewModelShared,
) : ImportListScaffoldVm {
    override val errorState: StateFlow<String?> = vm.errorState
    override val successState: StateFlow<Int?> = vm.successState
    override val itemCount: Int get() = vm.allRules.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String {
        val item = vm.allRules[index]
        return if (item.group.isNullOrBlank()) item.name else "${item.name}(${item.group})"
    }

    override fun itemState(index: Int): String {
        val local = vm.checkRules[index]
        val item = vm.allRules[index]
        return when {
            local == null -> "新增"
            item.pattern != local.pattern
                || item.replacement != local.replacement
                || item.isRegex != local.isRegex
                || item.scope != local.scope -> "更新"
            else -> "已有"
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

    // 注意: ReplaceRule VM 入口方法是 import(text), 非 importSource
    override fun startImport(text: String) = vm.import(text)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allRules[index])
}

/**
 * TxtTocRule (目录规则) 导入 VM 适配器 (对照 app 端 [io.legado.app.ui.association.ImportTxtTocRuleDialog])。
 *
 * - itemLabel: `allSources[it].name`
 * - itemState: 按 `allSources[it] != checkSources[it]` 比对 (新增/更新/已有)
 */
class ImportTxtTocRuleVmAdapter(
    private val vm: ImportTxtTocRuleViewModelShared,
) : ImportListScaffoldVm {
    override val errorState: StateFlow<String?> = vm.errorState
    override val successState: StateFlow<Int?> = vm.successState
    override val itemCount: Int get() = vm.allSources.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String = vm.allSources[index].name

    override fun itemState(index: Int): String {
        val local = vm.checkSources[index]
        return when {
            local == null -> "新增"
            vm.allSources[index] != local -> "更新"
            else -> "已有"
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

    override fun startImport(text: String) = vm.importSource(text)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])
}

/**
 * DictRule (字典规则) 导入 VM 适配器 (对照 app 端 [io.legado.app.ui.association.ImportDictRuleDialog])。
 *
 * - itemLabel: `allSources[it].name`
 * - itemState: 简单按 `checkSources[it] == null` 判断 (新增/已有, 无"更新"概念)
 */
class ImportDictRuleVmAdapter(
    private val vm: ImportDictRuleViewModelShared,
) : ImportListScaffoldVm {
    override val errorState: StateFlow<String?> = vm.errorState
    override val successState: StateFlow<Int?> = vm.successState
    override val itemCount: Int get() = vm.allSources.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String = vm.allSources[index].name

    override fun itemState(index: Int): String =
        if (vm.checkSources[index] == null) "新增" else "已有"

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

    override fun startImport(text: String) = vm.importSource(text)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])
}

/**
 * HttpTTS (语音源) 导入 VM 适配器 (对照 app 端 [io.legado.app.ui.association.ImportHttpTtsDialog])。
 *
 * - itemLabel: `allSources[it].name`
 * - itemState: 按 `allSources[it].lastUpdateTime > checkSources[it].lastUpdateTime` 比对 (新增/更新/已有)
 */
class ImportHttpTtsVmAdapter(
    private val vm: ImportHttpTtsViewModelShared,
) : ImportListScaffoldVm {
    override val errorState: StateFlow<String?> = vm.errorState
    override val successState: StateFlow<Int?> = vm.successState
    override val itemCount: Int get() = vm.allSources.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String = vm.allSources[index].name

    override fun itemState(index: Int): String {
        val local = vm.checkSources[index]
        return when {
            local == null -> "新增"
            vm.allSources[index].lastUpdateTime > local.lastUpdateTime -> "更新"
            else -> "已有"
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

    override fun startImport(text: String) = vm.importSource(text)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])
}

/**
 * 主题配置导入 VM 适配器 (对照 app 端 [io.legado.app.ui.association.ImportThemeDialog])。
 *
 * - itemLabel: `allSources[it].themeName`
 * - itemState: 按 `allSources[it] != checkSources[it]` 比对 (新增/更新/已有)
 *
 * 注: 主题数据源在桌面端有特殊性 ([DesktopThemeConfigProvider] 是内存版不持久化到 prefStore,
 * 与 [io.legado.desktop.ui.config.ThemeListDialog] 用的 `desktop.theme.customList` prefStore 不一致),
 * 故主题导入在 [io.legado.desktop.ui.config.ThemeListDialog] 内走简化路径 (不调 importSelect 写
 * 内存 Provider, 而是直接读 vm.allSources 合并到 prefStore 保持数据一致)。此适配器仅供其他场景
 * (如 RuleSub type=6 主题订阅, 当前任务 type 0-5 未涉及) 复用。
 */
class ImportThemeVmAdapter(
    private val vm: ImportThemeViewModelShared,
) : ImportListScaffoldVm {
    override val errorState: StateFlow<String?> = vm.errorState
    override val successState: StateFlow<Int?> = vm.successState
    override val itemCount: Int get() = vm.allSources.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    override fun itemLabel(index: Int): String = vm.allSources[index].themeName

    override fun itemState(index: Int): String {
        val local = vm.checkSources[index]
        return when {
            local == null -> "新增"
            vm.allSources[index] != local -> "更新"
            else -> "已有"
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

    override fun startImport(text: String) = vm.importSource(text)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allSources[index])
}

/**
 * 书源过滤规则导入 VM 适配器 (对照 app 端 [io.legado.app.ui.association.ImportSourceFilterRuleDialog])。
 *
 * - 注意: SourceFilterRule VM 用 `allRules`/`checkRules` (非 allSources/checkSources),
 *   入口方法是 `import(text)` (非 importSource), 与 ReplaceRule VM 一致
 * - itemLabel: `allRules[it].name` 为空时显示 `pattern` (对照 app 端 ImportSourceFilterRuleDialog)
 * - itemState: 按 pattern/fields/scope 比对 (新增/更新/已有)
 */
class ImportSourceFilterRuleVmAdapter(
    private val vm: ImportSourceFilterRuleViewModelShared,
) : ImportListScaffoldVm {
    override val errorState: StateFlow<String?> = vm.errorState
    override val successState: StateFlow<Int?> = vm.successState
    override val itemCount: Int get() = vm.allRules.size
    override val selectCount: Int get() = vm.selectCount
    override val isSelectAll: Boolean get() = vm.isSelectAll

    // 对照 app 端 ImportSourceFilterRuleDialog: name 为空时显示 pattern
    override fun itemLabel(index: Int): String {
        val item = vm.allRules[index]
        return item.name.ifEmpty { item.pattern }
    }

    // 对照 app 端 ImportSourceFilterRuleDialog: 按 pattern/fields/scope 比对 (新增/更新/已有)
    override fun itemState(index: Int): String {
        val local = vm.checkRules[index]
        val item = vm.allRules[index]
        return when {
            local == null -> "新增"
            item.pattern != local.pattern
                || item.fields != local.fields
                || item.scope != local.scope -> "更新"

            else -> "已有"
        }
    }

    override fun itemChecked(index: Int): Boolean = vm.selectStatus[index]

    override fun setItemChecked(index: Int, checked: Boolean) {
        vm.selectStatus[index] = checked
    }

    override fun toggleAll() {
        // 与 app 端 ImportSourceFilterRuleDialog.toggleAll 完全一致 (含 if 条件, 不"偷懒"简化)
        val selectAll = vm.isSelectAll
        vm.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) vm.selectStatus[index] = !selectAll
        }
    }

    override fun importSelect(finally: () -> Unit) = vm.importSelect(finally)

    // 注意: SourceFilterRule VM 入口方法是 import(text), 非 importSource (与 ReplaceRule 一致)
    override fun startImport(text: String) = vm.import(text)

    override fun itemJson(index: Int): String = GSON.toJson(vm.allRules[index])
}
