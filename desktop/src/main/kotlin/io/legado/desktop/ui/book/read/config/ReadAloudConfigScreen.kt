package io.legado.desktop.ui.book.read.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.association.ImportHttpTtsViewModelShared
import io.legado.app.ui.book.read.config.ReadAloudConfigScreen as SharedReadAloudConfigScreen
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.SpeakEngineDialog
import io.legado.app.ui.compose.component.AppTitleBar
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
import io.legado.desktop.ui.association.DesktopImportDialog
import io.legado.desktop.ui.association.ImportHttpTtsVmAdapter
import io.legado.desktop.ui.association.ImportListScaffoldVm
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端"朗读配置" Screen 入口 (包装 shared/sharedUiMain 的 [SharedReadAloudConfigScreen])。
 *
 * # 职责
 *
 * - 在 [SharedReadAloudConfigScreen] 之上加 [AppTitleBar] (标题"朗读配置" + 返回按钮)
 * - 装配 pausePhoneCallsEnabled (从 [PreferenceProviders] 读 PreferKey.ignoreAudioFocus)
 * - 装配 speakEngineSummary (占位 rememberString("system_tts"), 加 TODO)
 * - 装配 onTtsEngine / onSysTtsConfig 回调 (接入 sharedUiMain 的 SpeakEngineDialog /
 *   ReadAloudDialog, 桌面端无 Android TTS 设置, onSysTtsConfig 改为弹朗读控制面板)
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] /
 *   [SharedReadAloudConfigScreen] 内部 PreferenceScreen 通过 LocalXxx 取依赖
 *
 * # 简化项 (依赖未下沉的功能用 TODO 注释 + no-op)
 *
 * - pausePhoneCallsEnabled: app 端用 AppConfig.ignoreAudioFocus (联动 prefs 变更监听刷新),
 *   桌面端从 [PreferenceProviders] 一次性读 PreferKey.ignoreAudioFocus (不监听变更,
 *   但因页面进入时读取, 短时不会失效)
 * - speakEngineSummary: app 端复杂逻辑 (ReadAloud.ttsEngine → httpTTSDao 名 / SelectItem.title
 *   / system_tts 兜底), 桌面端 ReadAloud / httpTTSDao / GSON.fromJsonObject 跨平台访问受限,
 *   暂用 rememberString("system_tts") 占位 (桌面端未注册 i18n 时返回 key 本身)
 * - onTtsEngine: app 端 showDialogFragment(SpeakEngineDialog()), 桌面端接入
 *   sharedUiMain 的 SpeakEngineDialog (engines 走 produceState 订阅 httpTTSDao.flowAll,
 *   onSelectEngine 写 PreferKey.ttsEngine, onDeleteEngine 调 httpTTSDao.delete)
 * - onSysTtsConfig: app 端 IntentHelp.openTTSSetting() (跳 Android 系统 TTS 设置),
 *   桌面端无 Android Intent, 改为弹 sharedUiMain 的 ReadAloudDialog 作为朗读控制入口
 *   (播放/定时/语速等回调中, 依赖 ReadAloud/ReadBook 的部分暂 TODO no-op)
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun ReadAloudConfigScreen(onBack: () -> Unit) {
    // 桌面端 Provider 注入: 供 commonMain 的 AppTheme / SharedReadAloudConfigScreen 取依赖
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
                Column(modifier = Modifier.fillMaxSize()) {
                    AppTitleBar(
                        title = rememberString("read_aloud_config"),
                        onBack = onBack,
                    )
                    ReadAloudConfigContent()
                }
            }
        }
    }
}

/**
 * 装配 pausePhoneCallsEnabled + speakEngineSummary + 2 个回调, 位置传参调用
 * [SharedReadAloudConfigScreen]。
 *
 * 与 app 端 ReadAloudConfigDialog 内 ReadAloudConfigScreen(...) 调用对齐, 差异见顶层 KDoc。
 */
@Composable
private fun ReadAloudConfigContent() {
    val prefs = remember { PreferenceProviders.get() }
    val scope = rememberCoroutineScope()
    // pausePhoneCallsEnabled: 联动 ignoreAudioFocus (true 时 pauseReadAloudWhilePhoneCalls 启用)
    val pausePhoneCallsEnabled = remember {
        prefs.getBoolean(PreferKey.ignoreAudioFocus, false)
    }
    // TODO: 接入 ReadAloud.ttsEngine / httpTTSDao / SelectItem.title 后补全 speakEngineSummary
    val speakEngineSummary = rememberString("system_tts")

    // ---- SpeakEngineDialog 数据 ----
    // 引擎列表 (produceState 订阅 httpTTSDao.flowAll, 生命周期绑定 Composable, 退出自动取消订阅)
    val engines by produceState<List<HttpTTS>>(emptyList()) {
        AppDbProviders.get().httpTTSDao.flowAll()
            .catch { AppLog.put("加载 HttpTTS 列表失败", it) }
            .collect { value = it }
    }
    // 当前选中引擎 id (null=系统默认, 与 app 端 ReadAloud.ttsEngine 为空兜底 system_tts 对齐)
    val selectedEngineUrl = remember {
        prefs.getString(PreferKey.ttsEngine).takeIf { it.isNotEmpty() }
    }

    // ---- ReadAloudDialog 数据 ----
    // 朗读初始值: 定时 / 语速 (从 prefs 读, 与 app 端 AppConfig.ttsTimer / ttsSpeechRate 对齐)
    val initialTimer = remember { prefs.getInt(PreferKey.ttsTimer, 0) }
    val initialSpeechRate = remember { prefs.getInt(PreferKey.ttsSpeechRate, 5) }

    // Dialog 显隐状态 (onTtsEngine / onSysTtsConfig 原为 TODO no-op, 现接入真实 Dialog)
    var showSpeakEngineDialog by remember { mutableStateOf(false) }
    var showReadAloudDialog by remember { mutableStateOf(false) }

    // HttpTtsEditDialog 显隐状态 + 待编辑的 HttpTTS 实例
    // editingTts=null 新增 (对应 SpeakEngineDialog 的"+"按钮), 非空=编辑 (对应行"编辑"按钮)
    var showHttpTtsEditDialog by remember { mutableStateOf(false) }
    var editingTts by remember { mutableStateOf<HttpTTS?>(null) }

    // ---- HttpTts 导入状态 (对照 desktop ReplaceRuleScreen 的 importVm/importInitialText 模式) ----
    // 因桌面端 SpeakEngineDialog (shared/sharedUiMain) 的标题栏 actions 仅"+"按钮不可改,
    // HttpTts 列表导入入口下放到 HttpTtsEditDialog 的 OverflowMenu (onImportLocal/onImportOnline
    // 非 null 时显示"本地导入/网络导入"项); 用户点击后先关闭 EditDialog 再触发外部回调,
    // 由本 Composable 弹 FileDialog/AlertDialog → 新建 ImportHttpTtsVmAdapter → 渲染
    // DesktopImportDialog 完成下载+解析+比对+入库 (与 app 端 SpeakEngineDialog OverflowMenu 流程等价)
    // 网络导入 URL 输入对话框开关 (onImportOnline 触发, 末尾 AlertDialog 渲染分支读取)
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }
    // 导入 VM 适配器 (null=无导入任务, 非 null=渲染 DesktopImportDialog 让用户勾选比对)
    var importVm by remember { mutableStateOf<ImportListScaffoldVm?>(null) }
    // 导入初始文本 (URL 或 JSON), DesktopImportDialog 的 LaunchedEffect 用它调 vm.startImport
    var importInitialText by remember { mutableStateOf("") }

    // ---- 导入相关文案 (rememberString 是 @Composable, 顶层缓存后供 AlertDialog /
    // suspend FileDialog 函数引用, 与 ReplaceRuleScreen 模式一致) ----
    // DesktopImportDialog 标题 (对照 app 端 getString(R.string.import_tts))
    val importTtsLabel = rememberString("import_tts")
    // 网络导入 URL 输入对话框标题 + 输入框 label + FileDialog 标题
    // (与 ReplaceRuleScreen 的 replace_rule_net_import_title/replace_rule_input_url/
    //  replace_rule_select_json_file 命名风格对齐, 字面量在 ResourceProvider.jvm.kt)
    val httpTtsNetImportTitleLabel = rememberString("http_tts_net_import_title")
    val httpTtsInputUrlLabel = rememberString("http_tts_input_url")
    val httpTtsSelectJsonFileLabel = rememberString("http_tts_select_json_file")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")

    SharedReadAloudConfigScreen(
        pausePhoneCallsEnabled = pausePhoneCallsEnabled,
        speakEngineSummary = speakEngineSummary,
        onTtsEngine = { showSpeakEngineDialog = true },
        onSysTtsConfig = { showReadAloudDialog = true },
    )

    // 朗读引擎选择对话框 (对应 app 端 showDialogFragment(SpeakEngineDialog()))
    if (showSpeakEngineDialog) {
        SpeakEngineDialog(
            engines = engines,
            selectedEngineUrl = selectedEngineUrl,
            onSelectEngine = { id ->
                // id: null=系统默认, 否则为 HttpTTS.id 字符串 (与 app 端 upTts 对齐)
                prefs.putString(PreferKey.ttsEngine, id)
            },
            onEditEngines = { httpTTS ->
                // 接入桌面端 HttpTtsEditDialog:
                // - httpTTS=null: 新增 (对应"+"按钮)
                // - httpTTS 非空: 编辑已有引擎 (对应行"编辑"按钮, 替代 app 端 showDialogFragment(HttpTtsEditDialog(httpTTS.id)))
                editingTts = httpTTS
                showHttpTtsEditDialog = true
            },
            onDeleteEngine = { httpTTS ->
                scope.launch { AppDbProviders.get().httpTTSDao.delete(httpTTS) }
            },
            onDismiss = { showSpeakEngineDialog = false },
        )
    }

    // HTTP TTS 引擎编辑对话框 (替代原 TODO no-op)
    // SpeakEngineDialog 关闭后由本对话框接管编辑, 关闭后回到 SpeakEngineDialog (若仍打开)
    // 注入 onImportLocal/onImportOnline: 因桌面端 SpeakEngineDialog (shared) 的标题栏 actions
    // 仅"+"按钮不可改, HttpTts 列表导入入口下放到 EditDialog 的 OverflowMenu; 用户点击导入项后
    // EditDialog 已先自行 dismiss (HttpTtsEditDialog 内部 onClick 顺序: dismissMenu → onDismiss → 回调),
    // 本 Composable 接收回调后弹 FileDialog/AlertDialog → 新建 ImportHttpTtsVmAdapter → 设置 importVm
    // → 末尾 DesktopImportDialog 渲染分支接管比对+入库 (与 ReplaceRuleScreen onImportLocal/Online 等价)
    if (showHttpTtsEditDialog) {
        HttpTtsEditDialog(
            httpTTS = editingTts,
            onDismiss = { showHttpTtsEditDialog = false },
            onImportLocal = {
                // 弹 FileDialog 选 JSON 文件 → 读文本 → 新建 ImportHttpTtsVmAdapter
                // → 设置 importVm + importInitialText 触发 DesktopImportDialog 渲染
                // (与 ReplaceRuleScreen onImportLocal 流程一致)
                scope.launch {
                    val json = importHttpTtsFromLocalFile(httpTtsSelectJsonFileLabel) ?: return@launch
                    val vm = ImportHttpTtsVmAdapter(ImportHttpTtsViewModelShared(scope))
                    importInitialText = json
                    importVm = vm
                }
            },
            onImportOnline = {
                // 弹 AlertDialog 输入 URL → 用户确认后新建 ImportHttpTtsVmAdapter + 设置
                // importInitialText/importVm, DesktopImportDialog 的 LaunchedEffect 调
                // vm.startImport(url) 触发下载 → 解析 → comparisonSource 比对 → 用户勾选入库
                // (与 ReplaceRuleScreen onImportOnline 流程一致, 替代原 javax.swing.JOptionPane)
                importOnlineUrlText = ""
                showImportOnlineDialog = true
            },
        )
    }

    // 朗读控制面板对话框
    // (桌面端无 Android 系统 TTS 设置 Intent, onSysTtsConfig 改为弹朗读控制面板作为朗读控制入口)
    if (showReadAloudDialog) {
        ReadAloudDialog(
            isPlaying = false, // TODO: 桌面端无 ReadAloud 服务, 暂固定 false
            initialTimer = initialTimer,
            initialSpeechRate = initialSpeechRate,
            initialFollowSys = false, // TODO: PreferKey 无 ttsFlowSys, 暂固定 false
            onPlayPause = { /* TODO: 桌面端无 ReadAloud 服务 */ },
            onStop = { /* TODO: 桌面端无 ReadAloud 服务 */ },
            onPrev = { /* TODO: 桌面端无 ReadBook */ },
            onNext = { /* TODO: 桌面端无 ReadBook */ },
            onPrevParagraph = { /* TODO: 桌面端无 ReadAloud 服务 */ },
            onNextParagraph = { /* TODO: 桌面端无 ReadAloud 服务 */ },
            onSetTimer = { mins -> prefs.putInt(PreferKey.ttsTimer, mins) },
            onAdjustSpeed = { rate ->
                // rate(Float, 0.5~5.0) → speechRate(Int, 0~45): rate=(sr+5)/10, sr=(rate*10-5)
                prefs.putInt(
                    PreferKey.ttsSpeechRate,
                    ((rate * 10) - 5).toInt().coerceIn(0, 45),
                )
            },
            onFollowSysChange = { /* TODO: PreferKey 无 ttsFlowSys */ },
            onOpenChapterList = { /* TODO: 桌面端无 ReadBook */ },
            onShowMenuBar = { /* TODO: 桌面端无 ReadBook */ },
            onBackstage = { /* TODO: 桌面端无 ReadBook */ },
            onOpenSettings = { showReadAloudDialog = false },
            onDismiss = { showReadAloudDialog = false },
        )
    }

    // ---- AlertDialog 渲染 (替换原 javax.swing.JOptionPane.showInputDialog) ----
    // 网络导入 HttpTts URL 输入对话框 (onImportOnline 触发 showImportOnlineDialog=true;
    //   确认按钮新建 ImportHttpTtsVmAdapter + 设置 importVm 触发 DesktopImportDialog
    //   完成下载+解析+比对+入库, 与 ReplaceRuleScreen 末尾 AlertDialog 渲染分支模式一致)
    if (showImportOnlineDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showImportOnlineDialog = false },
            title = { Text(httpTtsNetImportTitleLabel) },
            text = {
                OutlinedTextField(
                    value = importOnlineUrlText,
                    onValueChange = { importOnlineUrlText = it },
                    label = { Text(httpTtsInputUrlLabel) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = importOnlineUrlText
                    showImportOnlineDialog = false
                    if (url.isNotBlank()) {
                        // 新建适配器 (包装 ImportHttpTtsViewModelShared), 设置 importInitialText
                        // + importVm 触发 DesktopImportDialog 渲染; Dialog 的 LaunchedEffect(vm) 调
                        // vm.startImport(url) 触发下载 → 解析 → comparisonSource 比对,
                        // 成功后让用户勾选"新增/更新/已有"项再 importSelect 入库
                        // (与 app 端 ImportHttpTtsDialog 流程等价)
                        val vm = ImportHttpTtsVmAdapter(ImportHttpTtsViewModelShared(scope))
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
    // 让用户勾选"新增/更新/已有"项后确定 importSelect 入库, 与 app 端 ImportHttpTtsDialog
    // 流程等价; 取消/确定均触发 onDismiss → importVm=null 关闭 Dialog) ----
    importVm?.let { vm ->
        DesktopImportDialog(
            title = importTtsLabel,
            vm = vm,
            initialText = importInitialText,
            onDismiss = { importVm = null },
        )
    }
}

/**
 * 从本地 JSON 文件读取 HttpTTS 文本 (对应 app 端 SpeakEngineDialog importDocResult 文件选择 + 读取)。
 *
 * 仅负责选文件 + 读文本, 不再解析+入库 (交给 ImportHttpTtsViewModelShared.import 走完整比对
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
private suspend fun importHttpTtsFromLocalFile(dialogTitle: String): String? {
    val json = withContext(Dispatchers.IO) {
        val dialog = FileDialog(Frame(), dialogTitle, FileDialog.LOAD)
        dialog.setFile("*.json")
        dialog.isVisible = true
        val file = dialog.files?.firstOrNull() ?: return@withContext null
        file.readText()
    } ?: run {
        AppLog.put("导入 HttpTTS: 用户取消选择")
        return null
    }
    return json
}
