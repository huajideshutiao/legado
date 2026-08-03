package io.legado.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.data.AppDbProviders
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.dialog.TextInputDialog
import io.legado.app.ui.widget.dialog.VariableDialog
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

    /** 变量编辑 (书源变量 / 书籍变量, 复用 shared [VariableDialog] 的双 Tab Map 编辑器)。 */
    data class Variable(
        val sourceVariables: Map<String, String>,
        val bookVariables: Map<String, String>,
        val onConfirm: (Map<String, String>, Map<String, String>) -> Unit,
    ) : DesktopDialogRequest

    /** 单行文本输入 (校验关键词 / 分组名 / 文件名导入 js 等)。 */
    data class TextInput(
        val title: String,
        val message: String? = null,
        val initialValue: String = "",
        val hint: String? = null,
        val onConfirm: (String) -> Unit,
    ) : DesktopDialogRequest

    /** 书源分组管理 (增/改名/删, 对照 app 端已删除的 source/manage/GroupManageDialog)。 */
    data object BookSourceGroupManage : DesktopDialogRequest

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
     * 导出配置 (对照 app 端 showExportConfig 的导出类型单选 txt|epub)。
     *
     * @param currentType 0=txt 1=epub (与 app 端 AppConfig.exportType 取值一致);
     *    cbz 由图片书自动选择, 不在此配置
     */
    data class ExportConfig(
        val currentType: Int,
        val onConfirm: (Int) -> Unit,
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

        is DesktopDialogRequest.Variable -> VariableDialog(
            sourceVariables = current.sourceVariables,
            bookVariables = current.bookVariables,
            onConfirm = { sourceVars, bookVars ->
                current.onConfirm(sourceVars, bookVars)
                DesktopDialogs.dismiss()
            },
            onDismiss = { DesktopDialogs.dismiss() },
        )

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

        DesktopDialogRequest.BookSourceGroupManage -> BookSourceGroupManageDialog(
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
    }
}

/**
 * 导出类型选择 (对照 app 端 showExportConfig 的 txt|epub 单选)。
 * 只存类型, 导出目录仍由导出时的文件夹选择器决定; 图片书自动走 cbz。
 */
@Composable
private fun ExportConfigDialog(
    request: DesktopDialogRequest.ExportConfig,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf(request.currentType) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = "导出配置",
        okButton = AlertButton(text = "确定") {
            request.onConfirm(type)
            onDismiss()
        },
        cancelButton = AlertButton(text = "取消") { onDismiss() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .selectableGroup(),
        ) {
            listOf("txt" to 0, "epub" to 1).forEach { (label, value) ->
                Row(
                    Modifier
                        .selectable(
                            selected = type == value,
                            role = Role.RadioButton,
                            onClick = { type = value },
                        )
                        .padding(top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppRadioButton(selected = type == value, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
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
        okButton = AlertButton(text = "确定") {
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

/**
 * 书源分组管理: 列出全部分组, 每项可改名/删除。
 *
 * 分组在 DB 里是 `book_sources.bookSourceGroup` 的逗号分隔字符串, 增删改走
 * shared [io.legado.app.ui.book.source.manage.BookSourceViewModelShared]
 * (见 [DesktopPlatformCapabilities.bookSourceViewModel])。
 */
@Composable
private fun BookSourceGroupManageDialog(onDismiss: () -> Unit) {
    // 刷新计数: 改完分组重新查一次 DB (分组是拼接字段, 无现成 Flow 可靠反映改名结果)
    var tick by remember { mutableStateOf(0) }
    val groups by produceState(initialValue = emptyList<String>(), tick) {
        value = runCatching { AppDbProviders.get().bookSourceDao.allGroups() }.getOrDefault(emptyList())
    }
    var renaming by remember { mutableStateOf<String?>(null) }

    renaming?.let { old ->
        TextInputDialog(
            title = "重命名分组",
            initialValue = old,
            onConfirm = { newName ->
                DesktopPlatformCapabilities.bookSourceViewModel.upGroup(old, newName.trim())
                renaming = null
                tick++
            },
            onDismiss = { renaming = null },
        )
        return
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = "管理书源分组",
        message = if (groups.isEmpty()) "暂无分组" else null,
        okButton = AlertButton(text = "关闭") { onDismiss() },
        widthFraction = 0.8f,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
        ) {
            groups.forEach { group ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(group)
                    AppTextButton(text = "重命名", onClick = { renaming = group })
                    AppTextButton(text = "删除", onClick = {
                        DesktopPlatformCapabilities.bookSourceViewModel.delGroup(group)
                        tick++
                    })
                }
            }
        }
    }
}
