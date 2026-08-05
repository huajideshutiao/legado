package io.legado.app.ui.book.import.remote

// I18N KEYS (已注册于 ResourceProvider.jvm.kt / ios Localizable.strings):
//   "server_config" to "服务器配置",
//   "create"        to "新建",
//   "text_default"  to "默认",
//   "cancel"        to "取消",
//   "ok"            to "确定",
//   "edit"          to "编辑",
//   "delete"        to "删除",
//   "draw"          to "提醒",
//   "sure_del"      to "是否确认删除？"
// Painter key (drawable):
//   - ic_add         (新增按钮)
//   - ic_edit        (编辑按钮)
//   - ic_clear_all   (删除按钮)

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Server
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.create
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.ic_clear_all
import legado.shared.generated.resources.ic_edit
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.server_config
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.text_default
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewServers

/**
 * 服务器列表选择对话框 (KMP 共享, app/desktop/iOS 复用)。
 *
 * 对照 app 端 [io.legado.app.ui.book.import.remote.ServersDialog] (BaseComposeDialogFragment):
 * - 原版依赖 Fragment + viewModels + AppConfig.remoteServerId + activity Callback,
 *   含 Android 专属 API (Fragment / Context / DialogFragment)。
 * - 桌面/iOS 端无 Fragment 体系, 改为纯 @Composable + [Dialog], 由调用方注入数据与回调。
 *
 * # API 设计 (与 [GroupManageDialog] 同模式)
 *
 * 数据与持久化全部由调用方注入, 本组件仅负责 UI 与交互:
 * - [servers]: 服务器列表 (调用方订阅 `serverDao.observeAll()` flow 提供)
 * - [initialServerId]: 进入对话框时选中的服务器 ID (调用方读 `AppConfig.remoteServerId`),
 *   仅用于 [onConfirm] / [onDismiss] 时判断是否切换, 实际选中状态由本组件内部维护
 * - [onAddServer]: 新建服务器 (调用方弹 [ServerConfigDialog] 传 server=null)
 * - [onEditServer]: 编辑服务器 (调用方弹 [ServerConfigDialog] 传 server=按 id 加载)
 * - [onDeleteServer]: 删除服务器 (调用方调 `ServersViewModelShared.delete`)
 * - [onSelectDefault]: 切回默认 webdav (调用方设 `AppConfig.remoteServerId = DEFAULT_WEBDAV_ID`)
 * - [onConfirm]: 用户点确定 (参数为选中的 serverId; 调用方设 `AppConfig.remoteServerId` + 刷新 RemoteBook)
 * - [onDismiss]: 关闭对话框 (返回按钮 / 取消按钮 / 点击外部)
 *
 * # 视觉对齐 (对照 app 端原版)
 *
 * - 标题栏: [DialogTitleBar] + "服务器配置" + 右侧新增按钮 (ic_add)
 * - 列表项: 48dp 行高 + 单选钮 + 名称 + 编辑 + 删除, 与 app 端 ServerItem 完全一致
 * - 底部按钮行: 默认 / 取消 / 确定 (右对齐, 8dp 水平内边距), 与 app 端 Row 一致
 *
 * @param servers 服务器列表 (调用方负责订阅 `serverDao.observeAll()` flow)
 * @param initialServerId 进入对话框时选中的服务器 ID
 * @param onAddServer 新建服务器回调
 * @param onEditServer 编辑服务器回调, 参数为 serverId
 * @param onDeleteServer 删除服务器回调, 参数为待删除的 Server
 * @param onSelectDefault 切回默认 webdav 回调 (设置 remoteServerId = DEFAULT_WEBDAV_ID 并关闭对话框)
 * @param onConfirm 用户点确定回调, 参数为选中的 serverId (调用方设置并关闭对话框)
 * @param onDismiss 关闭对话框回调 (返回按钮 / 取消按钮 / 点击外部)
 */
@Composable
fun ServersDialog(
    servers: List<Server>,
    initialServerId: Long,
    onAddServer: () -> Unit,
    onEditServer: (Long) -> Unit,
    onDeleteServer: (Server) -> Unit,
    onSelectDefault: () -> Unit,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // 当前选中的服务器 ID (内部状态, 初始值 = 调用方传入的 initialServerId)
    var selectServerId by remember { mutableStateOf(initialServerId) }
    // 待删除的服务器 (非 null 时弹出二次确认 alert, 与 app 端 deleteServer(server) 弹 alert 对齐)
    var deletingServer by remember { mutableStateOf<Server?>(null) }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column {
                DialogTitleBar(
                    title = stringResource(Res.string.server_config),
                    onBack = onDismiss,
                    actions = {
                        IconButton(onClick = onAddServer) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_add),
                                contentDescription = stringResource(Res.string.create),
                                tint = colors.primaryText,
                            )
                        }
                    },
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(items = servers, key = { it.id }) { item ->
                        ServerItem(
                            item = item,
                            selected = item.id == selectServerId,
                            onSelect = { selectServerId = item.id },
                            onEdit = { onEditServer(item.id) },
                            onDelete = { deletingServer = item },
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTextButton(
                        text = stringResource(Res.string.text_default),
                        onClick = onSelectDefault
                    )
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = stringResource(Res.string.cancel), onClick = onDismiss)
                    AppTextButton(
                        text = stringResource(Res.string.ok),
                        onClick = { onConfirm(selectServerId) })
                }
            }
        }
    }

    // 删除二次确认 (对照 app 端 alert(R.string.draw, R.string.sure_del) { yesButton { viewModel.delete(server) } })
    deletingServer?.let { server ->
        AppAlertDialog(
            onDismissRequest = { deletingServer = null },
            title = stringResource(Res.string.draw),
            message = stringResource(Res.string.sure_del),
            okButton = AlertButton(
                text = stringResource(Res.string.ok),
                onClick = {
                    onDeleteServer(server)
                    deletingServer = null
                },
            ),
            cancelButton = AlertButton(
                text = stringResource(Res.string.cancel),
            ),
        )
    }
}

/**
 * 单条服务器列表项 (对照 app 端 ServersDialog.ServerItem)。
 *
 * 行高 48dp + 单选钮 + 名称 (weight) + 编辑 + 删除, 与 app 端原版完全一致。
 */
@Composable
private fun ServerItem(
    item: Server,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.viewHeightXl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppRadioButton(
                selected = selected,
                onClick = onSelect,
            )
            Text(
                text = item.name,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                painter = painterResource(Res.drawable.ic_edit),
                contentDescription = stringResource(Res.string.edit),
                tint = colors.primaryText,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                painter = painterResource(Res.drawable.ic_clear_all),
                contentDescription = stringResource(Res.string.delete),
                tint = colors.primaryText,
            )
        }
    }
}

// ===== @Preview 合并自 androidMain 的 book/import/remote/ServerDialogsPreviews.kt (ServersDialog) =====

// ---- ServersDialog ----

/** [ServersDialog] / [ServerConfigDialog] 的 @Preview (WebDav 服务器管理)。 */

@Preview
@Composable
fun ServersDialogPreview() = LegadoThemePreview {
    ServersDialog(
        servers = previewServers,
        initialServerId = previewServers.first().id,
        onAddServer = {},
        onEditServer = {},
        onDeleteServer = {},
        onSelectDefault = {},
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ServersDialogEmptyPreview() = LegadoThemePreview {
    ServersDialog(
        servers = emptyList(),
        initialServerId = 0L,
        onAddServer = {},
        onEditServer = {},
        onDeleteServer = {},
        onSelectDefault = {},
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ServersDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ServersDialog(
        servers = previewServers,
        initialServerId = previewServers[1].id,
        onAddServer = {},
        onEditServer = {},
        onDeleteServer = {},
        onSelectDefault = {},
        onConfirm = {},
        onDismiss = {},
    )
}

