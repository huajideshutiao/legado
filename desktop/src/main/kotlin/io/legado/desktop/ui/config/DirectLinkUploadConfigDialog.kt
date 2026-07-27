package io.legado.desktop.ui.config

import androidx.compose.foundation.layout.widthIn
import io.legado.desktop.ui.component.DialogSizes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.constant.AppLog
import io.legado.app.help.DirectLinkUploadRule
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import io.legado.desktop.help.DesktopDirectLinkUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * 桌面端"直链上传规则"配置 Compose Dialog (MY 页 → 其他设置 → 上传规则入口)。
 *
 * # 背景
 *
 * 对照 app 端 [io.legado.app.ui.config.DirectLinkUploadConfig] (继承
 * [io.legado.app.base.BaseComposeDialogFragment] 的全屏 Dialog Fragment), 桌面端无
 * Fragment/Activity, 改为纯 Compose [Dialog] (参考 shared/sharedUiMain
 * [io.legado.app.ui.widget.dialog.VariableDialog] 的 Dialog + Surface + Column 模式)。
 * 表单结构 (4 字段 + 溢出菜单 + 测试/取消/确定) 与校验/保存逻辑逐字保留, 仅做平台适配:
 *
 * # 平台适配 (与 app 端差异)
 *
 * - **配置读写**: app 端 `DirectLinkUpload.getRule/putConfig` → 桌面端
 *   [DesktopDirectLinkUpload.getRule]/[DesktopDirectLinkUpload.putConfig] (落 java.util.prefs)
 * - **默认规则**: app 端 `DirectLinkUpload.getDefaultRules` (读 assets) → 桌面端
 *   [DesktopDirectLinkUpload.getDefaultRules] (读 classpath)
 * - **测试上传**: app 端 `DirectLinkUpload.upLoad` → 桌面端
 *   [DesktopDirectLinkUpload.upLoad] (suspend, 走 shared AnalyzeUrlCore)
 * - **剪贴板**: app 端 `sendToClip/getClipText` (Android ClipboardManager) → 桌面端
 *   [Toolkit.getDefaultToolkit].systemClipboard (AWT Clipboard)
 * - **Toast**: app 端 `toastOnUi` → 桌面端 [Toasters.get].toast (SystemTray 通知)
 * - **选择器**: app 端 `requireContext().selector(...)` → 桌面端 [AppSelectorDialog]
 * - **结果弹窗**: app 端 `alert { ... }` DSL → 桌面端 [AppAlertDialog]
 * - **协程**: app 端 `execute { }` (BaseComposeDialogFragment 封装) → 桌面端
 *   `rememberCoroutineScope().launch { runCatching { } }`
 * - **文案**: app 端 `R.string.xxx` → 桌面端 [rememberString]`("xxx")`
 *   (jvmMain ResourceProvider 已注册 copy_rule/paste_rule/download_url_rule/ok/cancel,
 *   其余未注册 key 返回 key 本身, 不影响功能)
 *
 * # UI 结构 (对照 app 端, padding 值原样保留)
 *
 * - [DialogTitleBar] (标题空 + 返回 + 溢出菜单: 复制/粘贴/导入默认)
 * - 表单 [Column] (verticalScroll, padding horizontal=16.dp):
 *     [AppOutlinedTextField] 上传Url / 下载Url规则 / 注释 + [Row]+[AppCheckbox] 压缩
 * - 底部 [Row] (padding horizontal=8.dp vertical=4.dp): 测试 / (Spacer) 取消 / 确定
 *
 * @param onDismiss 关闭回调 (由 [DesktopOtherConfigScreen] 的 onUploadRule 触发显隐)
 */
@Composable
fun DirectLinkUploadConfigDialog(onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()

    // 文案 (rememberString 是 @Composable, 顶层缓存后供各 lambda 引用)
    val copyRuleText = rememberString("copy_rule")
    val pasteRuleText = rememberString("paste_rule")
    val importDefaultRuleText = rememberString("import_default_rule")
    val uploadUrlLabel = rememberString("upload_url")
    val downloadUrlRuleLabel = rememberString("download_url_rule")
    val summaryLabel = rememberString("summary")
    val isCompressLabel = rememberString("is_compress")
    val testText = rememberString("test")
    val cancelText = rememberString("cancel")
    val okText = rememberString("ok")
    val copyTextText = rememberString("copy_text")

    // 表单 state (初始化自当前规则, 对照 app 端 upView(DirectLinkUpload.getRule()))
    val initialRule = remember { DesktopDirectLinkUpload.getRule() }
    var uploadUrl by remember { mutableStateOf(initialRule.uploadUrl) }
    var downloadUrlRule by remember { mutableStateOf(initialRule.downloadUrlRule) }
    var summary by remember { mutableStateOf(initialRule.summary) }
    var compress by remember { mutableStateOf(initialRule.compress) }

    // 测试结果对话框状态 (对照 app 端 alertTestResult)
    var testResult by remember { mutableStateOf<String?>(null) }
    // 导入默认规则选择器显隐 (对照 app 端 importDefault 的 selector)
    var showImportSelector by remember { mutableStateOf(false) }

    fun upView(rule: DirectLinkUploadRule) {
        uploadUrl = rule.uploadUrl
        downloadUrlRule = rule.downloadUrlRule
        summary = rule.summary
        compress = rule.compress
    }

    // 校验 (对照 app 端 getRule, 错误提示改 Toasters)
    fun getRule(): DirectLinkUploadRule? {
        if (uploadUrl.isBlank()) {
            Toasters.get().toast("上传Url不能为空")
            return null
        }
        if (downloadUrlRule.isBlank()) {
            Toasters.get().toast("下载Url规则不能为空")
            return null
        }
        if (summary.isBlank()) {
            Toasters.get().toast("注释不能为空")
            return null
        }
        return DirectLinkUploadRule(uploadUrl, downloadUrlRule, summary, compress)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.background,
            modifier = Modifier.fillMaxWidth().widthIn(max = DialogSizes.dialogMaxWidth()),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 标题栏 (原 View 版 setupTitleBar 未设标题, 保持空标题等价)
                DialogTitleBar(
                    title = "",
                    onBack = onDismiss,
                    actions = {
                        OverflowMenu { dismissMenu ->
                            DropdownMenuItem(
                                text = {
                                    Text(copyRuleText, color = colors.primaryText)
                                },
                                onClick = {
                                    dismissMenu()
                                    getRule()?.let { rule ->
                                        // 序列化规则写系统剪贴板 (替代 app 端 sendToClip)
                                        runCatching {
                                            val json = GSON.toJson(rule)
                                            val clipboard =
                                                Toolkit.getDefaultToolkit().systemClipboard
                                            clipboard.setContents(StringSelection(json), null)
                                        }.onFailure { AppLog.put("复制规则失败", it) }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(pasteRuleText, color = colors.primaryText)
                                },
                                onClick = {
                                    dismissMenu()
                                    // 读剪贴板可能在 AWT 主线程阻塞, 切 IO (替代 app 端 getClipText)
                                    scope.launch {
                                        val text = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val clipboard =
                                                    Toolkit.getDefaultToolkit().systemClipboard
                                                clipboard.getData(DataFlavor.stringFlavor) as? String
                                            }.getOrNull()
                                        }
                                        if (text.isNullOrBlank()) {
                                            Toasters.get().toast("剪贴板为空或格式不对")
                                            return@launch
                                        }
                                        runCatching {
                                            GSON.fromJsonObject<DirectLinkUploadRule>(text)
                                                .getOrThrow()
                                        }.onSuccess { upView(it) }
                                            .onFailure {
                                                Toasters.get().toast("剪贴板为空或格式不对")
                                            }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        importDefaultRuleText,
                                        color = colors.primaryText
                                    )
                                },
                                onClick = { dismissMenu(); showImportSelector = true },
                            )
                        }
                    },
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    AppOutlinedTextField(
                        value = uploadUrl,
                        onValueChange = { uploadUrl = it },
                        label = uploadUrlLabel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppOutlinedTextField(
                        value = downloadUrlRule,
                        onValueChange = { downloadUrlRule = it },
                        label = downloadUrlRuleLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    AppOutlinedTextField(
                        value = summary,
                        onValueChange = { summary = it },
                        label = summaryLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .toggleable(
                                value = compress,
                                role = Role.Checkbox,
                                onValueChange = { compress = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(checked = compress, onCheckedChange = null)
                        Text(isCompressLabel, color = colors.primaryText)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTextButton(text = testText) {
                        val rule = getRule() ?: return@AppTextButton
                        // 协程执行上传测试 (替代 app 端 execute { DirectLinkUpload.upLoad(...) })
                        scope.launch {
                            runCatching {
                                DesktopDirectLinkUpload.upLoad(
                                    "test.json", "{}", "application/json", rule
                                )
                            }.onSuccess { testResult = it }
                                .onFailure { testResult = it.localizedMessage ?: "ERROR" }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = cancelText) { onDismiss() }
                    AppTextButton(text = okText) {
                        getRule()?.let { rule ->
                            DesktopDirectLinkUpload.putConfig(rule)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }

    // 测试结果对话框 (对照 app 端 alertTestResult: title="result" + message + ok + copy_text)
    testResult?.let { result ->
        AppAlertDialog(
            onDismissRequest = { testResult = null },
            title = "result",
            message = result,
            okButton = AlertButton(text = okText),
            // negativeButton(copy_text) → cancelButton: 复制结果到剪贴板 (dismissOnClick 默认 true, 复制后关闭)
            cancelButton = AlertButton(text = copyTextText) {
                runCatching {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(result), null)
                }
            },
        )
    }

    // 导入默认规则选择器 (对照 app 端 importDefault 的 selector)
    if (showImportSelector) {
        val defaultRules = remember { DesktopDirectLinkUpload.getDefaultRules() }
        AppSelectorDialog(
            onDismissRequest = { showImportSelector = false },
            items = defaultRules.map { it.summary },
            onItemSelected = { index ->
                if (index in defaultRules.indices) {
                    upView(defaultRules[index])
                }
            },
        )
    }
}
