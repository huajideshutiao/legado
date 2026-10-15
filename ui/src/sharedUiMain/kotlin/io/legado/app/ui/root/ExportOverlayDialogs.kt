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
import io.legado.app.help.IntentData
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.getSummaryShared
import io.legado.app.help.coroutine.Coroutine
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
import legado.ui.generated.resources.copy
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
                    .padding(vertical = DesignTokens.spacingDefault),
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
 * 四端导出的唯一入口: 导出落盘前弹「上传 URL / 保存到文件」, 选完弹导出成功框
 * (直链或保存位置 + 直链规则注释 + 复制/取消)。保存到文件由平台自己弹保存入口并写盘
 * (见 [io.legado.app.ui.root.FilePickerService.saveText])。
 *
 * 待导出内容不进弹窗载荷: 载荷会随导航栈进界面快照 (退后台/系统重建时交给系统),
 * 上千条书源/书架导出的整份 JSON 可达数 MB, 超系统 Bundle 预算会崩。内容走
 * [IntentData] 进程内一次性通道, 载荷只放键; 进程重建后内容已随进程消失, 内容组件直接关框。
 */

/** 导出分发载荷 (进程内一次性通道的数据体)。 */
internal data class ExportDispatchPayload(
    val fileName: String,
    val content: String,
    val contentType: String,
)

/** 构造导出分发 Overlay (key="exportDispatch")。调用方须在主线程 (showOverlay 是 UI 操作)。 */
fun pushExportDispatch(fileName: String, content: String, contentType: String) {
    val key = IntentData.put(ExportDispatchPayload(fileName, content, contentType))
    AppNavigatorProviders.get().showOverlay(
        AppOverlay.Dialog(
            key = "exportDispatch",
            payload = key,
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
                    // 保存到文件: 平台自己弹保存入口并写盘 (各端保存目标不同, 写盘归平台实现)。
                    // 用 [Coroutine.async] 而不是 rememberCoroutineScope: 上面已 onDismiss(),
                    // 组合子树随即 dispose, 挂在组合上的 scope 会把「选目标 + 写盘」整段取消
                    // → 文件不落盘、成功框也不弹。async 自带进程级 scope, IO 执行、主线程回调。
                    else -> {
                        onDismiss()
                        Coroutine.async {
                            PlatformServiceProviders.get().files.saveText(fileName, content)
                        }.onSuccess { saved ->
                            saved?.let(::pushExportSuccess)
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
    // 一次性通道取内容 (取走即删); 进程重建后取不到 → 直接关框, 不弹空框
    val payload = remember(overlay.payload) {
        IntentData.get<ExportDispatchPayload>(overlay.payload)
    }
    if (payload == null) {
        LaunchedEffect(overlay.key) { navigator.dismissOverlay(overlay.key) }
        return
    }
    ExportDispatchDialog(
        fileName = payload.fileName,
        content = payload.content,
        contentType = payload.contentType,
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

/** 导出成功框 Overlay 内容 (key="exportSuccess")。 */
@Composable
internal fun ExportSuccessDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val value = remember(overlay.payload) {
        decodeStringMapOrNull(overlay.payload.orEmpty())?.get("value").orEmpty()
    }
    AppAlertDialog(
        onDismissRequest = { navigator.dismissOverlay(overlay.key) },
        title = stringResource(Res.string.export_success),
        // 仅直链时消息区显示直链上传规则注释
        message = value.takeIf { it.isAbsUrl() }?.let { getSummaryShared() },
        okButton = AlertButton(text = stringResource(Res.string.copy)) {
            PlatformCapabilityProviders.get().copyToClipboard(value)
        },
        cancelButton = AlertButton(text = stringResource(Res.string.cancel)),
        content = {
            // 结果只作展示 (直链或本地路径, 可超长): 纵向滚动 + 自动换行
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(Res.string.path),
                    color = AppTheme.colors.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = DesignTokens.spacingXs),
                )
                Text(
                    value,
                    color = AppTheme.colors.primaryText,
                    fontSize = 15.sp,
                )
            }
        },
    )
}
