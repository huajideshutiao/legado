package io.legado.app.ui.association

// 下沉说明：JS 引擎 openUrl 动作的"跳转确认"对话框（原 app 端 OpenUrlConfirmDialog object +
// ComposeDialog 实现）下沉为 KMP 共享 overlay (key="openUrlConfirm")。
// 平台接口 OpenUrlProvider 已在 commonMain (help/ui/OpenUrlProvider.kt), 各端注册自己的实现;
// 本 overlay 渲染确认框本体, payload=IntentData key 携带 OpenUrlConfirmPayload。
// 对照 app 端 OpenUrlConfirmDialog: 标题"跳转确认" + 副标题 sourceTag, 溢出菜单=禁用/删除书源,
// 确定=打开链接 (经 PlatformCapabilities.openExternalUrl, Android 端实现带 mimeType 分支)。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.SourceType
import io.legado.app.help.IntentData
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.PlatformCapabilityProviders
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete_source
import legado.shared.generated.resources.disable_source
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.no
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.stringResource

/** 跳转确认 Overlay payload: 打开链接所需的全部输入 (对照 app 端 OpenUrlConfirmDialog.display 参数)。 */
@Serializable
data class OpenUrlConfirmPayload(
    val url: String,
    val mimeType: String? = null,
    val sourceKey: String? = null,
    val sourceTag: String? = null,
    val sourceType: Int = SourceType.book,
)

/**
 * 跳转确认 Overlay (key="openUrlConfirm", payload=IntentData key)。
 *
 * 对照 app 端 OpenUrlConfirmDialog.Content: 标题"跳转确认" + 副标题 sourceName(=sourceTag),
 * 正文 "xx 正在请求跳转链接/应用，是否跳转？", 底部 取消/确定, 标题栏溢出菜单=禁用源/删除源
 * (删除需二次确认, 确认后禁用/删除书源并关闭)。
 */
@Composable
internal fun OpenUrlConfirmOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val payload = remember(overlay.payload) { IntentData.get<OpenUrlConfirmPayload>(overlay.payload) }
    if (payload == null) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    val scope = rememberCoroutineScope()
    // 删除源二次确认 (对照 app 端 alert(draw) + sure_del)
    var pendingDelete by remember(overlay.key) { mutableStateOf(false) }
    val onDismiss: () -> Unit = { navigator.dismissOverlay(overlay.key) }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = AppTheme.colors.fillet,
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = "跳转确认",
                    subtitle = payload.sourceTag,
                    actions = {
                        OverflowMenu(
                            onDisableSource = {
                                payload.sourceKey?.let { key ->
                                    scope.launch(IoDispatcher) {
                                        SourceHelp.enableSource(key, payload.sourceType, false)
                                    }
                                }
                                onDismiss()
                            },
                            onDeleteSource = { pendingDelete = true },
                        )
                    },
                )
                Text(
                    text = "${payload.sourceTag} 正在请求跳转链接/应用，是否跳转？",
                    color = AppTheme.colors.primaryText,
                    modifier = Modifier.padding(24.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AppTextButton(
                        text = stringResource(Res.string.cancel),
                        color = AppTheme.colors.secondaryText,
                        onClick = onDismiss,
                    )
                    AppTextButton(text = stringResource(Res.string.ok), onClick = {
                        PlatformCapabilityProviders.get().openExternalUrl(payload.url, payload.mimeType)
                        onDismiss()
                    })
                }
            }
        }
    }

    if (pendingDelete) {
        AppAlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del) + "\n" + payload.sourceTag,
            okButton = AlertButton(stringResource(Res.string.yes)) {
                payload.sourceKey?.let { key ->
                    scope.launch(IoDispatcher) {
                        SourceHelp.deleteSource(key, payload.sourceType)
                    }
                }
                onDismiss()
            },
            cancelButton = AlertButton(stringResource(Res.string.no)) {},
        )
    }
}

/** 标题栏溢出菜单: 禁用源 / 删除源 (对照 app 端 OverflowMenu)。 */
@Composable
private fun RowScope.OverflowMenu(
    onDisableSource: () -> Unit,
    onDeleteSource: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = rememberPainter("ic_more_vert"),
                contentDescription = null,
                tint = AppTheme.colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                onClick = {
                    expanded = false
                    onDisableSource()
                },
            ) { Text(stringResource(Res.string.disable_source)) }
            DropdownMenuItem(
                onClick = {
                    expanded = false
                    onDeleteSource()
                },
            ) { Text(stringResource(Res.string.delete_source)) }
        }
    }
}
