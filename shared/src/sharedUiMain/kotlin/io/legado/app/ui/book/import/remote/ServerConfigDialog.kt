package io.legado.app.ui.book.import.remote

// I18N KEYS (已注册于 ResourceProvider.jvm.kt / ios Localizable.strings):
//   "action_save" to "保存",
//   "name"        to "名称"
// Painter key (drawable):
//   - ic_save (保存按钮)

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Server
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.previewServers
import io.legado.app.utils.KS_JSON
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_save
import legado.shared.generated.resources.ic_save
import legado.shared.generated.resources.name
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 服务器配置编辑对话框 (KMP 共享, app/desktop/iOS 复用)。
 *
 * 对照 app 端 [io.legado.app.ui.book.import.remote.ServerConfigDialog] (BaseComposeDialogFragment):
 * - 原版依赖 Fragment + viewModels + Bundle + savedInstanceState + GSON.toJson(HashMap),
 *   含 Android 专属 API (Fragment / Bundle / Context)。
 * - 桌面/iOS 端无 Fragment 体系, 改为纯 @Composable + [Dialog], 由调用方注入数据与回调。
 *
 * # API 设计 (与 [DictRuleEditDialog] 同模式)
 *
 * 数据与持久化全部由调用方注入, 本组件仅负责 UI 与交互:
 * - [server]: 待编辑的服务器 (null=新增), 调用方按 id 异步加载后传入
 * - [onSave]: 保存回调, 参数为组装后的 [Server] (调用方调 `ServerConfigViewModelShared.save`)
 * - [onDismiss]: 关闭对话框回调 (返回按钮 / 保存成功 / 点击外部)
 *
 * # 字段对齐 (对照 app 端原版)
 *
 * - name: 服务器名称 (对应 app `name`)
 * - url / username / password: WebDav 配置三字段 (对应 app `getWebDavConfig()` 返回的 HashMap)
 * - TYPE 行: 固定显示 "WEBDAV" (原版 TYPE spinner 仅有 WEBDAV 单项, 退化为固定展示)
 *
 * # 序列化
 *
 * 原版用 `GSON.toJson(HashMap<String, String>)` 序列化 config 字段;
 * 下沉后用 [KS_JSON] + [Server.WebDavConfig] serializer 序列化,
 * 输出 JSON `{"url":"...","username":"...","password":"..."}` 与原版完全兼容
 * ([Server.getWebDavConfig] 用 `decodeOrNull<WebDavConfig>(config)` 反序列化, 双向兼容)。
 *
 * @param server 待编辑的服务器 (null=新增), 调用方按 id 异步加载后传入
 * @param onSave 保存回调, 参数为组装后的 Server (调用方调 `ServerConfigViewModelShared.save` 并关闭对话框)
 * @param onDismiss 关闭对话框回调 (返回按钮 / 点击外部)
 */
@Composable
fun ServerConfigDialog(
    server: Server?,
    onSave: (Server) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // 表单字段 (keyed by server, 切换编辑目标时重置, 与 DictRuleEditDialog 同模式)
    var name by remember(server) { mutableStateOf(server?.name.orEmpty()) }
    val initConfig = remember(server) { server?.getWebDavConfig() }
    var url by remember(server) { mutableStateOf(initConfig?.url.orEmpty()) }
    var username by remember(server) { mutableStateOf(initConfig?.username.orEmpty()) }
    var password by remember(server) { mutableStateOf(initConfig?.password.orEmpty()) }

    /**
     * 组装当前输入框值为 Server, 与 app 端 getServer() 完全等价:
     * - 编辑已有服务器时 server?.copy() (保留原 id 等字段, 不污染传入实例);
     * - 新增时 new Server()。
     * - type 固定为 WEBDAV (与 app 端一致)。
     * - config 用 KS_JSON 序列化 WebDavConfig, 与 app 端 GSON.toJson(HashMap) 输出兼容。
     */
    fun getServer(): Server {
        val newServer = server?.copy() ?: Server()
        newServer.name = name
        newServer.type = Server.TYPE.WEBDAV
        newServer.config = KS_JSON.encodeToString(
            Server.WebDavConfig.serializer(),
            Server.WebDavConfig(url = url, username = username, password = password),
        )
        return newServer
    }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            modifier = Modifier.appDialogSize(),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = "",
                    onBack = onDismiss,
                    actions = {
                        IconButton(onClick = { onSave(getServer()) }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_save),
                                contentDescription = stringResource(Res.string.action_save),
                                tint = colors.primaryText,
                            )
                        }
                    },
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    AppOutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(Res.string.name),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 原 TYPE spinner 仅 WEBDAV 单项, 退化为固定展示 (与 app 端原版一致)
                    Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("TYPE", color = colors.accent, modifier = Modifier.padding(8.dp))
                        Text("WEBDAV", color = colors.primaryText)
                    }
                    AppOutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = "url",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppOutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = "username",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppOutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "password",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ===== @Preview 合并自 androidMain 的 book/import/remote/ServerDialogsPreviews.kt (ServerConfigDialog) =====

// ---- ServerConfigDialog ----

@Preview
@Composable
fun ServerConfigDialogPreview() = LegadoThemePreview {
    ServerConfigDialog(
        server = previewServers.first(),
        onSave = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ServerConfigDialogNewPreview() = LegadoThemePreview {
    ServerConfigDialog(
        server = null,
        onSave = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun ServerConfigDialogDarkPreview() = LegadoThemePreview(dark = true) {
    ServerConfigDialog(
        server = previewServers.first(),
        onSave = {},
        onDismiss = {},
    )
}
