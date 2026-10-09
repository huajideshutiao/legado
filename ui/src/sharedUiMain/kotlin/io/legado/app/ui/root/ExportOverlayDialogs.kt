package io.legado.app.ui.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.getSummaryShared
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSelectorList
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import io.legado.app.utils.isAbsUrl
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.export
import legado.ui.generated.resources.export_charset
import legado.ui.generated.resources.export_config
import legado.ui.generated.resources.export_file_name
import legado.ui.generated.resources.export_no_chapter_name
import legado.ui.generated.resources.export_success
import legado.ui.generated.resources.export_type
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.path
import legado.ui.generated.resources.save_to_file
import legado.ui.generated.resources.upload_url
import org.jetbrains.compose.resources.stringResource

/**
 * 导出文件设置 (对照原版 BookshelfManageActivity.showExportConfig 与 dialog_export_config.xml
 * 全量字段, 与桌面端 ExportConfigDialog 同一形制): 导出文件名 JS 规则 / 导出格式 txt|epub /
 * 导出编码 / TXT 不导出章节名。cbz 由图片书自动选择 (对照 app 端 startExport 的
 * `if (book.isImage) "cbz"`), 不在此配置。
 */
@Composable
internal fun BookExportFormatDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val prefs = PreferenceProviders.get()
    // 初值即各键存储值 (缺省与 AppConfig 同口径: 文件名空串 / 格式 txt / 编码 UTF-8 / 导出章节名)
    var fileName by remember { mutableStateOf(prefs.getString(PreferKey.bookExportFileName, "")) }
    var type by remember {
        mutableIntStateOf(if (prefs.getInt(PreferKey.exportType, 0) == 1) 1 else 0)
    }
    var charset by remember { mutableStateOf(prefs.getString(PreferKey.exportCharset, "UTF-8")) }
    var noChapterName by remember {
        mutableStateOf(prefs.getBoolean(PreferKey.exportNoChapterName, false))
    }
    AppAlertDialog(
        title = stringResource(Res.string.export_config),
        onDismissRequest = { navigator.dismissOverlay(overlay.key) },
        okButton = AlertButton(stringResource(Res.string.ok)) {
            prefs.putString(PreferKey.bookExportFileName, fileName)
            prefs.putInt(PreferKey.exportType, type)
            // 空值按 UTF-8 落库 (对照 AppConfig.exportCharset 的 isNullOrBlank 兜底)
            prefs.putString(PreferKey.exportCharset, charset.takeIf { it.isNotBlank() } ?: "UTF-8")
            prefs.putBoolean(PreferKey.exportNoChapterName, noChapterName)
        },
        cancelButton = AlertButton(stringResource(Res.string.cancel)),
        content = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = DesignTokens.spacingXl,
                        vertical = DesignTokens.spacingDefault,
                    ),
            ) {
                // 导出文件名 JS 规则 (对照 et_file_name, 可用 name/author 变量, 留空用默认名)
                Text(
                    stringResource(Res.string.export_file_name),
                    color = AppTheme.colors.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = DesignTokens.spacingXs),
                )
                AppTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 可用变量提示 (对照原版 XML 的 app:helperText="Variable: name, author")
                Text(
                    "Variable: name, author",
                    color = AppTheme.colors.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = DesignTokens.spacingXs),
                )
                // 导出格式 (对照 rg_export_type)
                Text(
                    stringResource(Res.string.export_type),
                    color = AppTheme.colors.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(
                        top = DesignTokens.spacingMd,
                        bottom = DesignTokens.spacingXs,
                    ),
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
                                .padding(
                                    top = DesignTokens.spacingXs,
                                    end = DesignTokens.spacingLg,
                                    bottom = DesignTokens.spacingXs,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppRadioButton(selected = type == value, onClick = null)
                            Text(label, color = AppTheme.colors.primaryText, fontSize = 15.sp)
                        }
                    }
                }
                // 导出编码 (对照 et_charset: 常用编码作下拉候选, 也允许手输)
                Text(
                    stringResource(Res.string.export_charset),
                    color = AppTheme.colors.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(
                        top = DesignTokens.spacingDefault,
                        bottom = DesignTokens.spacingXs,
                    ),
                )
                AppAutoCompleteField(
                    value = charset,
                    onValueChange = { charset = it },
                    label = "charset",
                    values = AppConst.charsets,
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
                        .padding(vertical = DesignTokens.spacingDefault),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCheckbox(checked = noChapterName, onCheckedChange = null)
                    Text(
                        stringResource(Res.string.export_no_chapter_name),
                        color = AppTheme.colors.primaryText,
                        modifier = Modifier.padding(start = DesignTokens.spacingDefault),
                    )
                }
            }
        },
    )
}

/**
 * 导出分发 Overlay (key="exportDispatch") + 导出成功 Overlay (key="exportSuccess")。
 *
 * 对照 app 端 `HandleFileContract.EXPORT → HandleFileDialog`: 导出落盘前弹「上传 URL / 保存到文件」,
 * 上传成功后 `showExportSuccess` (直链 + 规则注释 + 确定复制)。桌面端书源/书架导出与规则类导出
 * 经此统一; 安卓书源/书架 3 处仍走原版 HandleFileDialog, 本对话框不改变其行为。
 *
 * payload 格式: [encodeStringMap] 编码的 map
 * (分发 = {fileName, content, contentType}, 成功 = {value})。
 */

/** 构造导出分发 Overlay (key="exportDispatch")。调用方须在主线程 (showOverlay 是 UI 操作)。 */
fun pushExportDispatch(fileName: String, content: String, contentType: String) {
    AppNavigatorProviders.get().showOverlay(
        AppOverlay.Dialog(
            key = "exportDispatch",
            payload = encodeStringMap(
                buildMap {
                    put("fileName", fileName)
                    put("content", content)
                    put("contentType", contentType)
                }
            ),
        )
    )
}

fun pushExportSuccess(value: String) {
    AppNavigatorProviders.get().showOverlay(
        AppOverlay.Dialog(
            key = "exportSuccess",
            payload = encodeStringMap(mapOf("value" to value)),
        )
    )
}

/** 导出分发对话框核心 Composable (选项: 上传 URL / 保存到文件), 供 Overlay 内容及规则导出复用。 */
@Composable
fun ExportDispatchDialog(
    fileName: String,
    content: String,
    contentType: String,
    onDismiss: () -> Unit,
) {
    // 上传等待期防重复点击 (对照原版 HandleFileDialog: 点击不自动关闭, 等结果回传后 dismiss)
    var busy by remember { mutableStateOf(false) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.export),
        content = {
            AppSelectorList(
                items = listOf(
                    stringResource(Res.string.upload_url),
                    stringResource(Res.string.save_to_file),
                )
            ) { index ->
                if (busy) return@AppSelectorList
                busy = true
                when (index) {
                    // 上传 URL: 直链上传规则接口上传后解析直链, 成功弹导出成功框, 失败 toast (实现内)
                    0 -> PlatformCapabilityProviders.get().upLoadFile(
                        fileName, content, contentType
                    ) { url ->
                        onDismiss()
                        url?.let(::pushExportSuccess)
                    }
                    // 保存到文件: 平台 files.saveFile + 写盘 (对照原版目录分支落盘)。
                    // 用 [Coroutine.async] 而不是 rememberCoroutineScope: 上面已 onDismiss(),
                    // 组合子树随即 dispose, 挂在组合上的 scope 会把「选路径 + 写盘」整段取消
                    // → 文件不落盘、成功框也不弹。async 自带进程级 scope, IO 执行、主线程回调。
                    else -> {
                        onDismiss()
                        Coroutine.async {
                            PlatformServiceProviders.get().files.saveFile(fileName)
                                ?.let { path ->
                                    BackupFileOps.writeText(path, content)
                                    path
                                }
                        }.onSuccess { path ->
                            path?.let(::pushExportSuccess)
                        }.onError { error ->
                            AppLog.put("导出失败\n${error.message}", error, true)
                        }
                    }
                }
            }
        },
    )
}

/** 导出分发框 Overlay 内容 (key="exportDispatch")。 */
@Composable
internal fun ExportDispatchDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val payload = remember(overlay.payload) { decodeStringMapOrNull(overlay.payload.orEmpty()) }
    if (payload == null) {
        LaunchedEffect(overlay.key) { navigator.dismissOverlay(overlay.key) }
        return
    }
    ExportDispatchDialog(
        fileName = payload["fileName"].orEmpty(),
        content = payload["content"].orEmpty(),
        contentType = payload["contentType"].orEmpty(),
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

/** 导出成功框 Overlay 内容 (key="exportSuccess", 对照 app 端 showExportSuccess)。 */
@Composable
internal fun ExportSuccessDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val value = remember(overlay.payload) {
        decodeStringMapOrNull(overlay.payload.orEmpty())?.get("value").orEmpty()
    }
    var text by remember { mutableStateOf(value) }
    AppAlertDialog(
        onDismissRequest = { navigator.dismissOverlay(overlay.key) },
        title = stringResource(Res.string.export_success),
        // 对照原版: 仅直链时消息区显示直链上传规则注释 (DirectLinkUpload.getSummary)
        message = value.takeIf { it.isAbsUrl() }?.let { getSummaryShared() },
        okButton = AlertButton(text = stringResource(Res.string.ok)) {
            // 原版 sendToClip(uri) 复制原始值, 编辑框可编辑却编辑无效 (原版缺陷);
            // 修正为复制编辑框当前文本
            PlatformCapabilityProviders.get().copyToClipboard(text)
        },
        content = {
            AppTextField(
                value = text,
                onValueChange = { text = it },
                label = stringResource(Res.string.path),
            )
        },
    )
}
