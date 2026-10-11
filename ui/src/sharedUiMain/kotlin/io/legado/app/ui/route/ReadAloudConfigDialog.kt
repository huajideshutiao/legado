package io.legado.app.ui.route

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.DefaultDataShared
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.tts.SpeakTest
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.config.ReadAloudConfigScreen
import io.legado.app.ui.book.read.config.SpeakEngineDialog
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppDialogSurface
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.association.DeepLinkImportType
import io.legado.app.ui.widget.dialog.OnlineImportUrlDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.aloud_config
import legado.ui.generated.resources.export_success
import legado.ui.generated.resources.system_tts
import org.jetbrains.compose.resources.stringResource

/**
 * 朗读设置对话框形态 (公共对话框基类: 0.9 宽居中 + fillet 底色 + dialogShape 圆角 +
 * DialogTitleBar 标题栏; 2026-10-11 用户拍板偏离原版 BasePrefDialogFragment 的
 * 无圆角/无标题形态, 对齐其他对话框)。由朗读控制面板"设置"按钮弹起。
 *
 * 暂无对应 ScreenModel, 电话暂停开关/朗读引擎摘要等状态用 remember { mutableStateOf } 暂存,
 * 待宿主下沉后替换为 ScreenModel 统一管理。
 *
 * onTtsEngine 弹出共享 [SpeakEngineDialog] (引擎列表来自 httpTTSDao.flowAll),
 * onSysTtsConfig 调用 [PlatformCapabilityProviders.get].openTtsSettings 跳转系统 TTS 设置。
 */
@Composable
fun ReadAloudConfigDialogHost(
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            AppDialogSurface(
                modifier = Modifier.appDialogSize(),
            ) {
                Column {
                    DialogTitleBar(
                        title = stringResource(Res.string.aloud_config),
                        onBack = onDismiss,
                    )
                    ReadAloudConfigContent(
                        onSysTtsConfig = { PlatformCapabilityProviders.get().openTtsSettings() },
                    )
                }
            }
        }
    }
}

/** 朗读设置正文 (Screen + 内嵌对话框), 路由/弹窗两形态共用 */
@Composable
private fun ReadAloudConfigContent(
    onSysTtsConfig: () -> Unit,
) {
    // 对照 app 端 pausePhoneCallsEnabled = AppConfig.ignoreAudioFocus
    val pref = PreferenceProviders.get()
    val pausePhoneCallsEnabled = remember { pref.getBoolean(PreferKey.ignoreAudioFocus) }

    val appConfig = remember { AppConfigProviders.get() }
    val appDb = remember { AppDbProviders.get() }
    val scope = rememberCoroutineScope()

    // 朗读引擎列表 (对照 app 端 SpeakEngineDialog.LaunchedEffect { flowAll().collect })
    var engines by remember { mutableStateOf(emptyList<HttpTTS>()) }
    LaunchedEffect(Unit) {
        appDb.httpTTSDao.flowAll()
            .catch { /* 对照 app 端 catch 记日志, 此处静默 */ }
            .flowOn(IoDispatcher)
            .conflate()
            .collect { engines = it }
    }

    // 当前选中的 TTS 引擎 id 字符串 (null=系统默认)
    var selectedEngineUrl by remember {
        mutableStateOf(appConfig.ttsEngine.ifBlank { null })
    }
    var showSpeakEngineDialog by remember { mutableStateOf(false) }
    var showTtsUrlInput by remember { mutableStateOf(false) }

    // 朗读试听器 (行/编辑器测试共用; 关闭对话框时停声)
    val speakTest = remember { SpeakTest() }
    DisposableEffect(Unit) {
        onDispose { speakTest.stop() }
    }
    // 试听文本 = 固定双语探针 (中文+英文, 先中文, 不随应用语言)
    val testSpeakText = SpeakTest.TEST_TEXT
    val exportSuccessText = stringResource(Res.string.export_success)

    // 统一 TTS 源导入入口: 文本/URL 交共享勾选导入对话框 (深链导入同一条链)
    fun showTtsImport(sourceText: String) {
        val payload = IntentData.put(sourceText)
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog(key = "*Import:" + DeepLinkImportType.HTTP_TTS.name, payload = payload)
        )
    }

    val systemTtsStr = stringResource(Res.string.system_tts)
    // 朗读引擎摘要 (对照 app 端 ReadAloudConfigDialog.speakEngineSummary)
    val speakEngineSummary = remember(engines, selectedEngineUrl, systemTtsStr) {
        val url = selectedEngineUrl
        when {
            url.isNullOrBlank() -> systemTtsStr
            // 对照 app 端 StringUtils.isNumeric(ttsEngine) → appDb.httpTTSDao.getName(id)
            url.toLongOrNull() != null ->
                engines.firstOrNull { it.id.toString() == url }?.name ?: systemTtsStr
            // 对照 app 端 GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
            else -> try {
                Json.parseToJsonElement(url).jsonObject["title"]?.jsonPrimitive?.content
                    ?: systemTtsStr
            } catch (e: Exception) {
                systemTtsStr
            }
        }
    }

    ReadAloudConfigScreen(
        pausePhoneCallsEnabled = pausePhoneCallsEnabled,
        speakEngineSummary = speakEngineSummary,
        onTtsEngine = { showSpeakEngineDialog = true },
        onSysTtsConfig = onSysTtsConfig,
    )

    if (showSpeakEngineDialog) {
        SpeakEngineDialog(
            engines = engines,
            selectedEngineUrl = selectedEngineUrl,
            onSelectEngine = { url ->
                selectedEngineUrl = url
                appConfig.setTtsEngine(url)
            },
            // 新增/编辑引擎走平台能力 (app 端 HttpTtsEditDialog, engine=null 新增)
            onEditEngines = { engine ->
                PlatformCapabilityProviders.get().showHttpTtsEditDialog(engine)
            },
            onDeleteEngine = { httpTTS ->
                scope.launch(IoDispatcher) {
                    appDb.httpTTSDao.delete(httpTTS)
                }
            },
            // 统一登录入口: URL 登录直开 WebView, 表单登录弹登录表单 (与 TTS 编辑器同一条链)
            onLogin = { engine -> engine.showLoginDialog() },
            onTestSpeak = { engine -> speakTest.speak(engine, testSpeakText) },
            onImportLocal = {
                scope.launch {
                    val path = withContext(IoDispatcher) {
                        PlatformServiceProviders.get().files.pickFile(FileFilter.Text)
                    } ?: return@launch
                    val text = withContext(IoDispatcher) { BackupFileOps.readText(path) }
                    showTtsImport(text)
                }
            },
            onImportOnline = { showTtsUrlInput = true },
            onImportDefault = {
                scope.launch(IoDispatcher) {
                    DefaultDataShared.importDefaultHttpTTS()
                }
            },
            onExport = {
                scope.launch {
                    val json = GSON.toJson(engines)
                    val path = withContext(IoDispatcher) {
                        PlatformServiceProviders.get().files.saveFile("httpTts.json")
                    } ?: return@launch
                    withContext(IoDispatcher) { BackupFileOps.writeText(path, json) }
                    Toasters.get().toast(exportSuccessText)
                }
            },
            onDismiss = { showSpeakEngineDialog = false },
        )
    }

    // 在线导入 URL 输入 (历史记录键对照原版 ttsUrlKey)
    if (showTtsUrlInput) {
        OnlineImportUrlDialog(
            recordKey = "ttsUrlKey",
            onConfirm = { url ->
                showTtsUrlInput = false
                if (url.isAbsUrl()) showTtsImport(url)
            },
            onDismiss = { showTtsUrlInput = false },
        )
    }
}
