package io.legado.app.ui.route

import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.book.read.config.ReadAloudConfigScreen
import io.legado.app.ui.book.read.config.SpeakEngineDialog
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.PlatformCapabilityProviders
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.system_tts
import org.jetbrains.compose.resources.stringResource

/**
 * 朗读设置对话框形态 (对照原版 ReadAloudConfigDialog: BasePrefDialogFragment 居中对话框,
 * 背景用 backgroundColor, 无标题栏)。由朗读控制面板"设置"按钮弹起。
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
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.background,
                modifier = Modifier.appDialogSize().padding(16.dp),
            ) {
                ReadAloudConfigContent(
                    onSysTtsConfig = { PlatformCapabilityProviders.get().openTtsSettings() },
                )
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
    val pref = LocalPreferenceStoreProvider.current
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
            onDismiss = { showSpeakEngineDialog = false },
        )
    }
}
