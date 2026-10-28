package io.legado.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.help.toast.Toasters
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面端命令式对话框请求 (非 Composable 上下文 → Compose 弹窗)。
 *
 * [DesktopPlatformCapabilities] 的同步方法拿不到 Composable 作用域, 改为往
 * [DesktopDialogs] 推请求, 由挂在 Compose 根上的 [DesktopDialogHost] 按提交顺序逐条消费。
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

/**
 * 队列中的一条对话框请求。
 *
 * [id] 由 [DesktopDialogs.show] 分配, 宿主凭它关闭并回传本条请求的结果; 队列中其余请求
 * 不受影响。
 */
internal class DesktopDialogRequestEntry(
    val id: Long,
    val request: DesktopDialogRequest,
    /** 宿主离开组合时结束本条请求的等待; 无等待者时为 null。 */
    val onAbandon: (() -> Unit)?,
)

/** 对话框请求队列: 队首即当前显示的弹窗, 其余按提交顺序排队等待。 */
object DesktopDialogs {

    private val _pending = MutableStateFlow<List<DesktopDialogRequestEntry>>(emptyList())
    internal val pending: StateFlow<List<DesktopDialogRequestEntry>> = _pending.asStateFlow()
    private val nextId = AtomicLong(1)

    /**
     * 提交一条对话框请求, 返回它的身份。
     *
     * @param onAbandon 宿主离开组合时调用, 用于结束等待本条请求结果的调用方 (无等待者时不传)。
     */
    fun show(request: DesktopDialogRequest, onAbandon: (() -> Unit)? = null): Long {
        val entry = DesktopDialogRequestEntry(nextId.getAndIncrement(), request, onAbandon)
        _pending.update { it + entry }
        return entry.id
    }

    /**
     * 按身份关闭请求: 先从队列移出, 再执行 [deliverResult] 回传结果。
     *
     * 该请求已不在队列 (已被关闭或宿主已撤销) 时不再回传结果。
     */
    fun dismiss(id: Long, deliverResult: () -> Unit = {}) {
        val before = _pending.getAndUpdate { list -> list.filterNot { it.id == id } }
        if (before.none { it.id == id }) return
        deliverResult()
    }

    /** 宿主离开组合: 清空待显示请求, 并结束仍在等待结果的调用方。 */
    internal fun abandonAll() {
        _pending.getAndUpdate { emptyList() }.forEach { it.onAbandon?.invoke() }
    }
}

/** 对话框宿主, 由 desktop Main.kt 挂在 Compose 根 (与 SourceUiEventBridgeHost 平级)。 */
@Composable
fun DesktopDialogHost() {
    val pending by DesktopDialogs.pending.collectAsState()
    // 恢复动作的作用域: 确认框的 onOk 在点按回调里执行 (不在本组合的协程里),
    // 而恢复是网络 + 解压 + 写库的阻塞 IO, 必须留在 IO 线程
    val restoreScope = rememberCoroutineScope()
    // 宿主离开组合 (窗口销毁) 时撤销待显示请求: 可见框随组合消失, 等待结果的调用方同时结束等待
    DisposableEffect(Unit) {
        onDispose { DesktopDialogs.abandonAll() }
    }
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
    val current = pending.firstOrNull() ?: return
    // 每条请求独立组合子树: 队列逐条交付时, 相邻的同类请求不共用弹窗内部状态 (输入框文本 / 删除二次确认)
    key(current.id) {
        when (val request = current.request) {
            is DesktopDialogRequest.TextInput -> TextInputDialog(
                title = request.title,
                message = request.message,
                initialValue = request.initialValue,
                hint = request.hint,
                onConfirm = { value -> DesktopDialogs.dismiss(current.id) { request.onConfirm(value) } },
                onDismiss = { DesktopDialogs.dismiss(current.id) },
            )

            is DesktopDialogRequest.Confirm -> AppAlertDialog(
                onDismissRequest = { DesktopDialogs.dismiss(current.id) { request.onNo() } },
                title = request.title,
                message = request.message,
                okButton = AlertButton(text = request.okText) {
                    DesktopDialogs.dismiss(current.id) { request.onOk() }
                },
                cancelButton = AlertButton(text = request.noText) {
                    DesktopDialogs.dismiss(current.id) { request.onNo() }
                },
            )

            is DesktopDialogRequest.SelectList -> AppAlertDialog(
                // 取消与 app 端 selector 同口径: 回传"未选择" (点弹窗外/ESC 走平台关闭)
                onDismissRequest = { DesktopDialogs.dismiss(current.id) { request.onSelected(null) } },
                title = request.title,
                widthFraction = 0.8f,
            ) {
                AppSelectorList(items = request.items) { index ->
                    // 先出队再回传: 回调里会紧接着推下一个选择框 (压缩包内选书 / 不支持格式确认),
                    // 后到请求排在已出队本条之后
                    DesktopDialogs.dismiss(current.id) { request.onSelected(index) }
                }
            }

            is DesktopDialogRequest.OpenUrlConfirm -> OpenUrlConfirmDialog(
                request = request,
                commitScope = restoreScope,
                onConfirm = { DesktopDialogs.dismiss(current.id) { request.onConfirm() } },
                onDismiss = { DesktopDialogs.dismiss(current.id) },
                onFinished = { DesktopDialogs.dismiss(current.id) },
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
    commitScope: CoroutineScope,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onFinished: () -> Unit,
) {
    val sourceName = request.sourceName ?: request.sourceKey.orEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }

    // 辅助动作先接纳一次, 放到宿主提交任务 (不随显示子树出队而取消), 完成后才结束本请求
    fun submit(action: suspend () -> Unit) {
        if (submitted) return
        submitted = true
        commitScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                // 真正取消原样向上传播, 不伪报操作失败也不继续收尾
                throw e
            } catch (e: Throwable) {
                AppLog.put("书源操作失败", e)
                Toasters.get().toast("操作失败: ${e.message}")
            }
            onFinished()
        }
    }

    if (confirmDelete) {
        AppAlertDialog(
            onDismissRequest = { if (!submitted) confirmDelete = false },
            title = "删除",
            message = "确定删除吗?\n$sourceName",
            okButton = AlertButton(text = "是") {
                val key = request.sourceKey
                if (key != null) submit { SourceHelp.deleteSource(key, request.sourceType) }
                else onFinished()
            },
            cancelButton = AlertButton(text = "否") { if (!submitted) confirmDelete = false },
        )
        return
    }

    AppAlertDialog(
        onDismissRequest = { if (!submitted) onDismiss() },
        title = "跳转确认",
        okButton = AlertButton(text = "确认") { if (!submitted) onConfirm() },
        cancelButton = AlertButton(text = "取消") { if (!submitted) onDismiss() },
        widthFraction = 0.8f,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = DesignTokens.spacingDefault)) {
            Text("$sourceName 正在请求跳转链接/应用，是否跳转？")
            Text(request.url, modifier = Modifier.padding(top = DesignTokens.spacingDefault))
            AppTextButton(text = "禁用书源", onClick = {
                val key = request.sourceKey
                if (key != null) submit { SourceHelp.enableSource(key, request.sourceType, false) }
                else onFinished()
            })
            AppTextButton(text = "删除书源", onClick = { confirmDelete = true })
        }
    }
}
