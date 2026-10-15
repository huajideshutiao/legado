package io.legado.app.ui.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.help.IntentData
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.verificationField
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.custom_export
import legado.ui.generated.resources.error_scope_input
import legado.ui.generated.resources.export_all
import legado.ui.generated.resources.export_chapter_index
import legado.ui.generated.resources.export_file_name
import legado.ui.generated.resources.file_contains_number
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.result_analyzed
import legado.ui.generated.resources.select_section_export
import org.jetbrains.compose.resources.stringResource

/**
 * 自定义章节导出配置 (四端共用; 对照原版 `BookshelfManageActivity.configExportSection`
 * 与 dialog_select_section_export.xml 全量字段): 导出全部 / 自定义导出 (互斥) +
 * 分卷文件名 JS 规则 + 分卷章节数 + 章节范围。
 *
 * 待导出书籍走 [IntentData] 进程内一次性通道 (选中可达上千本, 不进弹窗载荷):
 * 进程重建后取不到书籍时直接关框, 不弹空框。
 */

/** 自定义章节导出配置载荷 (进程内一次性通道的数据体)。 */
internal data class ExportSectionPayload(val books: List<Book>)

/** 构造自定义章节导出配置 Overlay (key="exportSectionConfig")。调用方须在主线程。 */
fun pushExportSectionConfig(books: List<Book>) {
    val key = IntentData.put(ExportSectionPayload(books))
    AppNavigatorProviders.get().showOverlay(
        AppOverlay.Dialog(key = "exportSectionConfig", payload = key)
    )
}

/** 自定义章节导出配置 Overlay 内容 (key="exportSectionConfig")。 */
@Composable
internal fun ExportSectionConfigDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val payload = remember(overlay.payload) {
        IntentData.get<ExportSectionPayload>(overlay.payload)
    }
    if (payload == null) {
        LaunchedEffect(overlay.key) { navigator.dismissOverlay(overlay.key) }
        return
    }
    ExportSectionConfigDialog(
        books = payload.books,
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

@Composable
fun ExportSectionConfigDialog(books: List<Book>, onDismiss: () -> Unit) {
    val prefs = PreferenceProviders.get()
    // 默认选中自定义导出 (对照原版 cbSelectExport.callOnClick())
    var allExport by remember { mutableStateOf(false) }
    var customExport by remember { mutableStateOf(true) }
    var fileName by remember {
        mutableStateOf(prefs.getString(PreferKey.episodeExportFileName, "").orEmpty())
    }
    var size by remember { mutableStateOf("1") }
    var scope by remember { mutableStateOf("") }
    // 分卷文件名解析示例 (对照 lyEtEpubFilename.helperText)
    var fileNameHelper by remember { mutableStateOf("") }
    var scopeError by remember { mutableStateOf(false) }
    var sizeError by remember { mutableStateOf(false) }

    // 对照原版 verifyExportFileNameJsStr: 能解析且非空才持久化
    fun verifyFileName(js: String) = tryParesExportFileName(js) && js.isNotEmpty()
    // 解析示例前缀 (不能在点击回调里调 stringResource, 先取出)
    val resultAnalyzedText = stringResource(Res.string.result_analyzed)

    /** 确定: 返回是否可关闭 (对照原版 getButton(POSITIVE) 手动 hide 的校验保留语义)。 */
    fun confirm(): Boolean {
        if (allExport) {
            PlatformCapabilityProviders.get().exportAllBooks(books)
            return true
        }
        val scopeText = scope.trim()
        if (!verificationField(scopeText)) {
            scopeError = true
            return false
        }
        val epubSize = size.toIntOrNull() ?: 1
        if (epubSize < 1) {
            // 原版只把非数字回落成 1; 0 会按每卷 0 章切分, 导出中途报错, 故在此拦下
            sizeError = true
            return false
        }
        PlatformCapabilityProviders.get().exportCustomBooks(books, epubSize, scopeText)
        return true
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.select_section_export),
        okButton = AlertButton(
            text = stringResource(Res.string.ok),
            dismissOnClick = false,
            onClick = { if (confirm()) onDismiss() },
        ),
        cancelButton = AlertButton(text = stringResource(Res.string.cancel)),
        content = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = DesignTokens.spacingDefault),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 导出全部
                    Row(
                        Modifier
                            .weight(1f)
                            .toggleable(
                                value = allExport,
                                role = Role.Checkbox,
                                onValueChange = {
                                    allExport = it
                                    customExport = !it
                                    if (it) scopeError = false
                                },
                            )
                            .padding(vertical = DesignTokens.spacingDefault),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(checked = allExport, onCheckedChange = null)
                        Text(
                            stringResource(Res.string.export_all),
                            color = AppTheme.colors.primaryText,
                            modifier = Modifier.padding(start = DesignTokens.spacingDefault),
                        )
                    }
                    // 自定义导出
                    Row(
                        Modifier
                            .weight(1f)
                            .toggleable(
                                value = customExport,
                                role = Role.Checkbox,
                                onValueChange = {
                                    customExport = it
                                    allExport = !it
                                },
                            )
                            .padding(vertical = DesignTokens.spacingDefault),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(checked = customExport, onCheckedChange = null)
                        Text(
                            stringResource(Res.string.custom_export),
                            color = AppTheme.colors.primaryText,
                            modifier = Modifier.padding(start = DesignTokens.spacingDefault),
                        )
                    }
                }
                // 分卷文件名 JS 规则 (对照 lyEtEpubFilename/etEpubFilename)
                Text(
                    stringResource(Res.string.export_file_name),
                    color = AppTheme.colors.primaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(
                        top = DesignTokens.spacingDefault,
                        bottom = DesignTokens.spacingXs,
                    ),
                )
                AppTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    singleLine = true,
                    enabled = customExport,
                    textStyle = TextStyle(textAlign = TextAlign.Start),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            // 失焦校验并持久化 (对照原版 etEpubFilename 焦点监听)
                            if (!focus.isFocused && verifyFileName(fileName)) {
                                prefs.putString(PreferKey.episodeExportFileName, fileName)
                            }
                        },
                    trailingIcon = {
                        // 解析示例 (对照原版 lyEtEpubFilename 的 endIcon 点击)
                        IconButton(onClick = {
                            fileNameHelper = if (verifyFileName(fileName)) {
                                books.firstOrNull()?.let { book ->
                                    resultAnalyzedText + ": " +
                                        book.getExportFileName("epub", 1, fileName)
                                } ?: resultAnalyzedText
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
                )
                Text(
                    "Variable: name, author, epubIndex",
                    color = AppTheme.colors.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = DesignTokens.spacingXs),
                )
                if (fileNameHelper.isNotEmpty()) {
                    Text(
                        fileNameHelper,
                        color = AppTheme.colors.secondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = DesignTokens.spacingXs),
                    )
                }
                // 分卷章节数 (对照 lyEtEpubSize/etEpubSize)
                AppTextField(
                    value = size,
                    onValueChange = { new ->
                        if (new.length <= 6 && new.all { it.isDigit() }) {
                            size = new
                            sizeError = false
                        }
                    },
                    singleLine = true,
                    enabled = customExport,
                    label = stringResource(Res.string.file_contains_number),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(textAlign = TextAlign.Start),
                    isError = sizeError,
                    errorMessage = if (sizeError) "每份章节数必须大于 0" else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 章节范围 (对照 lyEtInputScope/etInputScope)
                AppTextField(
                    value = scope,
                    onValueChange = {
                        scope = it
                        scopeError = false
                    },
                    singleLine = true,
                    enabled = customExport,
                    label = stringResource(Res.string.export_chapter_index),
                    placeholder = "1-5,8,10-18",
                    isError = scopeError,
                    errorMessage = if (scopeError) stringResource(Res.string.error_scope_input) else null,
                    textStyle = TextStyle(textAlign = TextAlign.Start),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
