package io.legado.app.ui.main.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.EventBus
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.utils.postEvent
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.home_tab_add
import legado.shared.generated.resources.home_tab_delete_confirm
import legado.shared.generated.resources.home_tab_delete_confirm_with_sections
import legado.shared.generated.resources.home_tab_edit
import legado.shared.generated.resources.home_tab_name_duplicate
import legado.shared.generated.resources.home_tab_title
import legado.shared.generated.resources.home_title_empty
import legado.shared.generated.resources.no
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.stringResource

/**
 * 主页分组新增/重命名对话框，下沉自 app 端 `showHomeTabEditDialog`。
 * 编辑模式多一个"删除"中性按钮（二次确认后删除）；校验失败不关闭对话框。
 */
@Composable
fun HomeTabEditDialog(
    oldTitle: String? = null,
    onDismiss: () -> Unit,
) {
    var title by remember(oldTitle) { mutableStateOf(oldTitle.orEmpty()) }
    // 中性按钮"删除" → 二次确认（原版先 dismiss 主对话框再弹确认框）
    var confirmDelete by remember { mutableStateOf(false) }

    val titleEmptyText = stringResource(Res.string.home_title_empty)
    val duplicateText = stringResource(Res.string.home_tab_name_duplicate)

    /** 对照原版 positiveButton 覆盖后的点击逻辑：空标题/重名时 toast 且保留对话框 */
    fun save(): Boolean {
        val newTitle = title.trim()
        if (newTitle.isBlank()) {
            Toasters.get().toast(titleEmptyText)
            return false
        }
        val ok = if (oldTitle == null) {
            HomeTabHelpShared.addTab(newTitle)
        } else {
            HomeTabHelpShared.renameTab(oldTitle, newTitle)
        }
        if (!ok) {
            Toasters.get().toast(duplicateText)
            return false
        }
        if (oldTitle == null) {
            postEvent(EventBus.HOME_TAB, HomeTabEvent(HomeTabEvent.ADD, newTitle = newTitle))
        } else {
            postEvent(
                EventBus.HOME_TAB,
                HomeTabEvent(HomeTabEvent.RENAME, oldTitle = oldTitle, newTitle = newTitle)
            )
        }
        return true
    }

    if (confirmDelete && oldTitle != null) {
        val sectionCount = remember(oldTitle) { HomeTabHelpShared.getSections(oldTitle).size }
        val message = if (sectionCount > 0) {
            stringResource(
                Res.string.home_tab_delete_confirm_with_sections,
                oldTitle,
                sectionCount,
            )
        } else {
            stringResource(Res.string.home_tab_delete_confirm, oldTitle)
        }
        AppAlertDialog(
            onDismissRequest = { confirmDelete = false; onDismiss() },
            title = stringResource(Res.string.delete),
            message = message,
            okButton = AlertButton(stringResource(Res.string.yes)) {
                HomeTabHelpShared.removeTab(oldTitle)
                postEvent(
                    EventBus.HOME_TAB,
                    HomeTabEvent(HomeTabEvent.REMOVE, oldTitle = oldTitle)
                )
            },
            cancelButton = AlertButton(stringResource(Res.string.no)),
        )
    } else {
        AppAlertDialog(
            onDismissRequest = onDismiss,
            title = stringResource(
                if (oldTitle == null) Res.string.home_tab_add else Res.string.home_tab_edit
            ),
            okButton = AlertButton(stringResource(Res.string.ok), dismissOnClick = false) {
                if (save()) onDismiss()
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)),
            neutralButton = if (oldTitle != null) {
                AlertButton(stringResource(Res.string.delete), dismissOnClick = false) {
                    confirmDelete = true
                }
            } else {
                null
            },
        ) {
            AppUnderlineTextField(
                value = title,
                onValueChange = { title = it },
                label = stringResource(Res.string.home_tab_title),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
