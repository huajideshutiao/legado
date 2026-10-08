package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.config.HttpTtsEditDialog
import io.legado.app.ui.book.read.config.HttpTtsEditViewModelShared
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.ui.widget.text.EditEntity.CodePattern
import io.legado.app.ui.widget.text.EditEntity.ViewType
import io.legado.app.utils.KS_JSON

/** 与 Android HttpTtsEditDialog 共用表单和 VM，平台仅提供剪贴板和朗读刷新。 */
@Composable
internal fun HttpTtsEditOverlayDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    key(overlay.payload) {
        HttpTtsEditOverlayForm(overlay, navigator)
    }
}

@Composable
private fun HttpTtsEditOverlayForm(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val scope = rememberCoroutineScope()
    val capabilities = PlatformCapabilityProviders.get()
    val initialStackSize = remember { navigator.backStack.value.size }
    var suspended by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<HttpTTS?>(null) }
    var fields by remember { mutableStateOf<List<EditEntity>>(emptyList()) }
    var loginHeader by remember { mutableStateOf<String?>(null) }
    val vm = remember {
        HttpTtsEditViewModelShared(
            scope = scope,
            clipTextProvider = { capabilities.getClipboardText() },
            onTtsChanged = {},
        )
    }
    fun initForm(value: HttpTTS) {
        source = value.copy()
        fields = httpTtsEditFields(value)
    }
    fun formData(): HttpTTS = checkNotNull(source).copy(id = vm.id ?: checkNotNull(source).id).apply {
        fields.forEach { field ->
            when (field.key) {
                "name" -> name = field.text.orEmpty()
                "url" -> url = field.text.orEmpty()
                "contentType" -> contentType = field.text
                "concurrentRate" -> concurrentRate = field.text
                "loginUrl" -> loginUrl = field.text
                "loginUi" -> loginUi = field.text
                "loginCheckJs" -> loginCheckJs = field.text
                "header" -> header = field.text
            }
        }
    }
    fun save(value: HttpTTS, onSuccess: () -> Unit) {
        vm.save(value) {
            capabilities.onHttpTtsEdited(value)
            onSuccess()
        }
    }
    LaunchedEffect(overlay.payload) {
        vm.initData(overlay.payload?.toLongOrNull(), ::initForm)
    }
    // URL 登录进入 WebView 路由时隐藏编辑窗口，pop 回原栈时恢复，未保存的表单不丢。
    // 挂起标记同步给 navigator：窗口已隐藏期间返回键不应再作用于它（同
    // SourceLoginOverlayDialog 的机制）。
    LaunchedEffect(navigator) {
        navigator.backStack.collect { entries ->
            val newSuspended = entries.size > initialStackSize
            if (newSuspended != suspended) {
                suspended = newSuspended
                navigator.setOverlaySuspended(overlay.key, newSuspended)
            }
        }
    }
    // Overlay 关闭时清挂起标记，否则 key 留在集合里会让下次同 key 的 Overlay 被误判为挂起
    DisposableEffect(Unit) {
        onDispose { navigator.setOverlaySuspended(overlay.key, false) }
    }
    if (source == null || suspended) return
    val dismiss: () -> Unit = { navigator.dismissOverlay(overlay.key) }
    HttpTtsEditDialog(
        editEntities = fields,
        onBack = dismiss,
        onSave = { save(formData()) { Toasters.get().toast("保存成功") } },
        onLogin = {
            val value = formData()
            if (value.hasLogin()) {
                save(value) { value.showLoginDialog() }
            } else {
                Toasters.get().toast("没有登陆界面")
            }
        },
        onShowLoginHeader = { loginHeader = formData().getLoginHeader().orEmpty() },
        onDeleteLoginHeader = { formData().removeLoginHeader() },
        onCopySource = { capabilities.copyToClipboard(KS_JSON.encodeToString(HttpTTS.serializer(), formData())) },
        onPasteSource = { vm.importFromClip(::initForm) },
        onShowLog = { navigator.showOverlay(AppOverlay.Dialog("app_log")) },
        onShowHelp = { navigator.showOverlay(AppOverlay.Dialog("help", payload = "httpTTSHelp")) },
        onDismiss = dismiss,
    )
    loginHeader?.let { header ->
        TextDialog(
            title = syncGetString("login_header"),
            content = header,
            onDismiss = { loginHeader = null },
        )
    }
}

/** 字段与 Android 编辑器一致；没有表单入口的扩展字段由源对象副本保留。 */
private fun httpTtsEditFields(source: HttpTTS): List<EditEntity> = listOf(
    EditEntity("name", source.name, syncGetString("name")),
    EditEntity("url", source.url, "url", ViewType.code, codePatterns = CodePattern.all),
    EditEntity("contentType", source.contentType, "Content-Type"),
    EditEntity("concurrentRate", source.concurrentRate, syncGetString("concurrent_rate")),
    EditEntity("loginUrl", source.loginUrl, syncGetString("login_url"), ViewType.code, codePatterns = CodePattern.all),
    EditEntity("loginUi", source.loginUi, syncGetString("login_ui"), ViewType.code, codePatterns = CodePattern.json),
    EditEntity("loginCheckJs", source.loginCheckJs, syncGetString("login_check_js"), ViewType.code, codePatterns = CodePattern.js),
    EditEntity("header", source.header, syncGetString("source_http_header"), ViewType.code, codePatterns = CodePattern.all),
)
