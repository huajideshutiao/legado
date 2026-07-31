package io.legado.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.AppDbProviders
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.dialog.TextInputDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
