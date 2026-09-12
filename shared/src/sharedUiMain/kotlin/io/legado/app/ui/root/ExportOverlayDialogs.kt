package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.help.getSummaryShared
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppSelectorList
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import io.legado.app.utils.isAbsUrl
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.export
import legado.shared.generated.resources.export_success
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.path
import legado.shared.generated.resources.save_to_file
import legado.shared.generated.resources.upload_url
import org.jetbrains.compose.resources.stringResource

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
