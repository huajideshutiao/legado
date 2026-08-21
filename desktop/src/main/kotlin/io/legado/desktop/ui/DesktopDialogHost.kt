package io.legado.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.dialog.TextInputDialog
import io.legado.app.utils.verificationField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 桌面端命令式对话框请求 (非 Composable 上下文 → Compose 弹窗)。
 *
 * [DesktopPlatformCapabilities] 的同步方法拿不到 Composable 作用域, 改为往
 * [DesktopDialogs] 推请求, 由挂在 Compose 根上的 [DesktopDialogHost] 消费。
 * 模式与 shared 的 `DeepLinkImportHost` (StateFlow 待办) 同构。
 */
sealed interface DesktopDialogRequest {

    /** 单行文本输入 (校验关键词 / 分组名 / 文件名导入 js 等)。 */
    data class TextInput(
        val title: String,
        val message: String? = null,
        val initialValue: String = "",
        val hint: String? = null,
        val onConfirm: (String) -> Unit,
    ) : DesktopDialogRequest

    /**
     * 双按钮确认框 (替代 app 端 `activity.alert` 的 ok/no 弹窗)。
     *
     * 用于阅读页章节链接长按的“浏览器/应用内打开”选择 (对照 app 端
     * `AndroidReaderPlatformProvider.onChapterViewLongClick` 的 alert)。
     */
    data class Confirm(
        val title: String,
        val message: String? = null,
        val okText: String,
        val noText: String,
        val onOk: () -> Unit,
        val onNo: () -> Unit,
    ) : DesktopDialogRequest

    /**
     * 跳转确认 (对照 app 端 `OpenUrlConfirmDialog.display`)。
     *
     * 书源 JS 调 `java.openUrl` 拉起外部浏览器/程序前必须经用户确认, 顺带给出禁用/删除该源的出口。
     */
    data class OpenUrlConfirm(
        val url: String,
        val sourceKey: String?,
        val sourceName: String?,
        val sourceType: Int,
        val onConfirm: () -> Unit,
    ) : DesktopDialogRequest

    /**
     * 导出配置 (对照 app 端 showExportConfig / dialog_export_config.xml 全量字段)。
     *
     * @param currentType 0=txt 1=epub (与 app 端 AppConfig.exportType 取值一致);
     *    cbz 由图片书自动选择, 不在此配置
     * @param currentFileName 导出文件名 JS 规则 (对照 AppConfig.bookExportFileName)
     * @param currentCharset 导出编码 (对照 AppConfig.exportCharset)
     * @param currentNoChapterName TXT 不导出章节名 (对照 AppConfig.exportNoChapterName)
     */
    data class ExportConfig(
        val currentType: Int,
        val currentFileName: String,
        val currentCharset: String,
        val currentNoChapterName: Boolean,
        val onConfirm: (type: Int, fileName: String, charset: String, noChapterName: Boolean) -> Unit,
    ) : DesktopDialogRequest

    /**
     * 自定义导出章节配置 (对照 app 端 configExportSection / dialog_select_section_export.xml)。
     *
     * @param path 已选定的导出目录
     * @param books 待导出书籍
     * @param currentFileName 已保存的分卷文件名 JS 规则 (对照 AppConfig.episodeExportFileName 预填)
     * @param onConfirm all=true → 普通导出 (txt/epub 按导出配置); all=false → 按 scope/size 自定义 epub 导出。
     *    fileName 为 epub 分卷文件名 JS 规则 (仅在合法时由宿主持久化)
     */
    data class ExportSectionConfig(
        val path: String,
        val books: List<Book>,
        val currentFileName: String,
        val onConfirm: (all: Boolean, scope: String, size: Int, fileName: String) -> Unit,
    ) : DesktopDialogRequest
}

/** 对话框请求队列 (单槽, 后到的请求覆盖未消费的前一个)。 */
object DesktopDialogs {

    private val _request = MutableStateFlow<DesktopDialogRequest?>(null)
    val request: StateFlow<DesktopDialogRequest?> = _request.asStateFlow()

    fun show(request: DesktopDialogRequest) {
        _request.value = request
    }

    fun dismiss() {
        _request.value = null
    }
}

/** 对话框宿主, 由 desktop Main.kt 挂在 Compose 根 (与 SourceUiEventBridgeHost 平级)。 */
@Composable
fun DesktopDialogHost() {
    val request by DesktopDialogs.request.collectAsState()
    when (val current = request) {
        null -> Unit

        is DesktopDialogRequest.TextInput -> TextInputDialog(
            title = current.title,
            message = current.message,
            initialValue = current.initialValue,
            hint = current.hint,
            onConfirm = {
                current.onConfirm(it)
                DesktopDialogs.dismiss()
            },
            onDismiss = { DesktopDialogs.dismiss() },
        )

        is DesktopDialogRequest.Confirm -> AppAlertDialog(
            onDismissRequest = {
                current.onNo()
                DesktopDialogs.dismiss()
            },
            title = current.title,
            message = current.message,
            okButton = AlertButton(text = current.okText) {
                current.onOk()
                DesktopDialogs.dismiss()
            },
            cancelButton = AlertButton(text = current.noText) {
                current.onNo()
                DesktopDialogs.dismiss()
            },
        )

        is DesktopDialogRequest.OpenUrlConfirm -> OpenUrlConfirmDialog(
            request = current,
            onDismiss = { DesktopDialogs.dismiss() },
        )

        is DesktopDialogRequest.ExportConfig -> ExportConfigDialog(
            request = current,
            onDismiss = { DesktopDialogs.dismiss() },
        )

        is DesktopDialogRequest.ExportSectionConfig -> ExportSectionConfigDialog(
            request = current,
            onDismiss = { DesktopDialogs.dismiss() },
        )
    }
}

/**
 * 导出文件设置 (对照 app 端 showExportConfig / dialog_export_config.xml 全量字段):
 * 导出文件名 JS 规则 / 导出格式 txt|epub / 导出编码 / TXT 不导出章节名。
 * 导出目录仍由导出时的文件夹选择器决定; 图片书自动走 cbz。
 */
@Composable
private fun ExportConfigDialog(
    request: DesktopDialogRequest.ExportConfig,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf(request.currentType) }
    var fileName by remember { mutableStateOf(request.currentFileName) }
    var charset by remember { mutableStateOf(request.currentCharset) }
    var noChapterName by remember { mutableStateOf(request.currentNoChapterName) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = jvmGetString("export_config"),
        okButton = AlertButton(text = "确认") {
            request.onConfirm(type, fileName, charset, noChapterName)
            onDismiss()
        },
        cancelButton = AlertButton(text = "取消") { onDismiss() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            // 导出文件名 (对照 et_file_name, Variable: name, author)
            Text(
                jvmGetString("export_file_name"),
                color = AppTheme.colors.primaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            AppTextField(
                value = fileName,
                onValueChange = { fileName = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // 导出格式 (对照 rg_export_type)
            Text(
                jvmGetString("export_type"),
                color = AppTheme.colors.primaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("txt" to 0, "epub" to 1).forEach { (label, value) ->
                    Row(
                        Modifier
                            .selectable(
                                selected = type == value,
                                role = Role.RadioButton,
                                onClick = { type = value },
                            )
                            .padding(top = 4.dp, end = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppRadioButton(selected = type == value, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            // 导出编码 (对照 et_charset)
            Text(
                jvmGetString("export_charset"),
                color = AppTheme.colors.primaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            AppTextField(
                value = charset,
                onValueChange = { charset = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // TXT 不导出章节名 (对照 cb_no_chapter_name)
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = noChapterName,
                        role = Role.Checkbox,
                        onValueChange = { noChapterName = it },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppCheckbox(checked = noChapterName, onCheckedChange = null)
                Text(
                    jvmGetString("export_no_chapter_name"),
                    color = AppTheme.colors.primaryText,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * 自定义导出章节配置 (对照 app 端 configExportSection / dialog_select_section_export.xml):
 * 导出所有 / 自定义导出 (epub 文件名 JS 规则 + 分卷大小 + 章节范围)。
 * 范围非法时点确定不关闭对话框 (对照 getButton(POSITIVE) 手动 hide 语义)。
 */
@Composable
private fun ExportSectionConfigDialog(
    request: DesktopDialogRequest.ExportSectionConfig,
    onDismiss: () -> Unit,
) {
    // 默认选中自定义导出 (对照 cbSelectExport.callOnClick())
    var all by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(true) }
    var fileName by remember { mutableStateOf(request.currentFileName) }
    var size by remember { mutableStateOf("1") }
    var scope by remember { mutableStateOf("") }
    var scopeError by remember { mutableStateOf<String?>(null) }
    var fileNameHelper by remember { mutableStateOf("") }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = jvmGetString("select_section_export"),
        okButton = AlertButton(text = "确认") {
            if (all) {
                request.onConfirm(true, "", 1, fileName)
                onDismiss()
            } else {
                val scopeText = scope.trim()
                if (!verificationField(scopeText)) {
                    scopeError = jvmGetString("error_scope_input")
                } else {
                    request.onConfirm(false, scopeText, size.toIntOrNull() ?: 1, fileName)
                    onDismiss()
                }
            }
        },
        cancelButton = AlertButton(text = "取消") { onDismiss() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .toggleable(
                            value = all,
                            role = Role.Checkbox,
                            onValueChange = {
                                all = it
                                custom = !it
                                if (it) scopeError = null
                            },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCheckbox(checked = all, onCheckedChange = null)
                    Text(
                        jvmGetString("export_all"),
                        color = AppTheme.colors.primaryText,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Row(
                    Modifier
                        .weight(1f)
                        .toggleable(
                            value = custom,
                            role = Role.Checkbox,
                            onValueChange = {
                                custom = it
                                all = !it
                            },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCheckbox(checked = custom, onCheckedChange = null)
                    Text(
                        jvmGetString("custom_export"),
                        color = AppTheme.colors.primaryText,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            // epub 文件名 JS 规则 (分卷, 对照 ly_et_epub_filename / et_epub_filename)
            Text(
                jvmGetString("export_file_name"),
                color = AppTheme.colors.primaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            AppTextField(
                value = fileName,
                onValueChange = { fileName = it },
                enabled = custom,
                singleLine = true,
                placeholder = "Variable: name, author, epubIndex",
                trailingIcon = {
                    // 解析示例按钮 (对照 ly_et_epub_filename 的 endIcon 点击)
                    IconButton(onClick = {
                        fileNameHelper = if (tryParesExportFileName(fileName)) {
                            request.books.firstOrNull()?.let { book ->
                                jvmGetString("result_analyzed") + ": " +
                                    book.getExportFileName("epub", 1, fileName)
                            } ?: jvmGetString("result_analyzed")
                        } else {
                            "Error"
                        }
                    }) {
                        Icon(
                            painter = rememberPainter("ic_play_24dp"),
                            contentDescription = "Execute script",
                            tint = AppTheme.colors.primaryText,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (fileNameHelper.isNotEmpty()) {
                Text(
                    fileNameHelper,
                    color = AppTheme.colors.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 分卷大小 (对照 ly_et_epub_size / et_epub_size, 默认 1)
            AppTextField(
                value = size,
                onValueChange = { new ->
                    if (new.length <= 6 && new.all { it.isDigit() }) size = new
                },
                enabled = custom,
                singleLine = true,
                label = jvmGetString("file_contains_number"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            // 章节范围 (对照 ly_et_input_scope / et_input_scope, 占位 "1-5,8,10-18")
            AppTextField(
                value = scope,
                onValueChange = {
                    scope = it
                    scopeError = null
                },
                enabled = custom,
                singleLine = true,
                label = jvmGetString("export_chapter_index"),
                placeholder = "1-5,8,10-18",
                isError = scopeError != null,
                errorMessage = scopeError,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 跳转确认对话框 (对照 app 端 `OpenUrlConfirmDialog`)。
 *
 * 标题/文案/按钮与原版一致; 原版 Toolbar 菜单的"禁用书源/删除书源"改为正文两个文字按钮,
 * 删除同样带二次确认。
 */
@Composable
private fun OpenUrlConfirmDialog(
    request: DesktopDialogRequest.OpenUrlConfirm,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sourceName = request.sourceName ?: request.sourceKey.orEmpty()
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AppAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = "删除",
            message = "确定删除吗?\n$sourceName",
            okButton = AlertButton(text = "是") {
                request.sourceKey?.let { key ->
                    scope.launch { runCatching { SourceHelp.deleteSource(key, request.sourceType) } }
                }
                onDismiss()
            },
            cancelButton = AlertButton(text = "否") { confirmDelete = false },
        )
        return
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = "跳转确认",
        okButton = AlertButton(text = "确认") {
            request.onConfirm()
            onDismiss()
        },
        cancelButton = AlertButton(text = "取消") { onDismiss() },
        widthFraction = 0.8f,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("$sourceName 正在请求跳转链接/应用，是否跳转？")
            Text(request.url, modifier = Modifier.padding(top = 8.dp))
            AppTextButton(text = "禁用书源", onClick = {
                request.sourceKey?.let { key ->
                    scope.launch {
                        runCatching { SourceHelp.enableSource(key, request.sourceType, false) }
                    }
                }
                onDismiss()
            })
            AppTextButton(text = "删除书源", onClick = { confirmDelete = true })
        }
    }
}
