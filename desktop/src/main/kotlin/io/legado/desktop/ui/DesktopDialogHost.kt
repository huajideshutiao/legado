package io.legado.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.StartupMaintenanceShared
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppSelectorList
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.dialog.TextInputDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
     * 列表选择 (标题 + 候选项, 回传选中下标)。
     *
     * 对照 app 端 `activity.selector(...)` 与 iOS 端 `presentSelectList`:
     * 选中回传下标, 取消 (点弹窗外 / ESC) 回传 null ("未选择")。
     */
    data class SelectList(
        val title: String,
        val items: List<String>,
        val onSelected: (Int?) -> Unit,
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
    // 恢复动作的作用域: 确认框的 onOk 在点按回调里执行 (不在本组合的协程里),
    // 而恢复是网络 + 解压 + 写库的阻塞 IO, 必须留在 IO 线程
    val restoreScope = rememberCoroutineScope()
    // 启动期“云端有新备份”提示 (对照 app 端 MainActivity.backupSync): 判定下沉在
    // StartupMaintenanceShared, 提示/恢复用本端已有的 DesktopDialogRequest.Confirm。
    // 挂在对话框宿主上 (而不是 DesktopCore.startupBackgroundTasks): DesktopDialogs 在 :desktop
    // 模块内, :desktop-core 看不到它 (依赖方向 :desktop → :desktop-core), 且
    // DesktopCore.startupBackgroundTasks 也被无 UI 的 :headless 入口调用 (那里无人消费弹框)。
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            // 判定前先补认证: checkNewWebDavBackup → lastBackUp 首行是 authorization ?: return,
            // 而本端 Authorization 只有 syncBookProgressOnStartup / 备份页调过 upConfig 后才有
            // (这里与启动任务并发, 不先补一次就可能拿到 null 而恒判无新备份)
            if (AppWebDavShared.authorization == null) {
                runCatching { AppWebDavShared.upConfig() }
                    .onFailure { AppLog.put("启动期 WebDav 认证失败\n${it.message}", it) }
            }
            StartupMaintenanceShared.checkNewWebDavBackup()
        }
        // 只在本函数内部回写过“已提示过的时间戳”且云端确实更新时才弹; 用户拒绝不会重复弹
        val newBackup = result as? StartupMaintenanceShared.NewBackupCheckResult.NewBackup
            ?: return@LaunchedEffect
        DesktopDialogs.show(
            DesktopDialogRequest.Confirm(
                // 标题/正文文案键与 app 端同源 (restore / webdav_after_local_restore_confirm)
                title = syncGetString("restore"),
                message = syncGetString("webdav_after_local_restore_confirm"),
                okText = syncGetString("ok"),
                noText = syncGetString("cancel"),
                onOk = {
                    // 恢复走共享恢复入口 (与 app 端 MainViewModel.restoreWebDav → AppWebDav.restoreWebDav 同一个)
                    restoreScope.launch {
                        try {
                            withContext(Dispatchers.IO) { AppWebDavShared.restoreWebDav(newBackup.name) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.put("WebDav恢复出错\n${e.message}", e)
                        }
                    }
                },
                onNo = {},
            )
        )
    }
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

        is DesktopDialogRequest.SelectList -> AppAlertDialog(
            // 取消与 app 端 selector 同口径: 回传"未选择" (点弹窗外/ESC 走平台关闭)
            onDismissRequest = {
                current.onSelected(null)
                DesktopDialogs.dismiss()
            },
            title = current.title,
            widthFraction = 0.8f,
        ) {
            AppSelectorList(items = current.items) { index ->
                // 先出队再回传: 回调里可能紧接着推下一个选择框 (压缩包内选书 / 不支持格式确认),
                // 单槽队列下后 dismiss 会把新请求一起清掉
                DesktopDialogs.dismiss()
                current.onSelected(index)
            }
        }

        is DesktopDialogRequest.OpenUrlConfirm -> OpenUrlConfirmDialog(
            request = current,
            onDismiss = { DesktopDialogs.dismiss() },
        )
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
        Column(Modifier.fillMaxWidth().padding(vertical = DesignTokens.spacingDefault)) {
            Text("$sourceName 正在请求跳转链接/应用，是否跳转？")
            Text(request.url, modifier = Modifier.padding(top = DesignTokens.spacingDefault))
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
