package io.legado.app.ui.main.home

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.HomeTabHelp
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi

fun showHomeTabEditDialog(
    context: Context,
    oldTitle: String? = null
) {
    context.alert {
        setTitle(if (oldTitle == null) R.string.home_tab_add else R.string.home_tab_edit)
        val title = mutableStateOf(oldTitle ?: "")
        customView {
            AppOutlinedTextField(
                value = title.value,
                onValueChange = { title.value = it },
                label = stringResource(R.string.home_tab_title),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        // 编辑模式提供"删除"中性按钮(左侧, dismissOnClick=false 由二次确认接管关闭)
        if (oldTitle != null) {
            neutralButton(R.string.delete) {
                val sectionCount = HomeTabHelp.getSections(oldTitle).size
                val msg = if (sectionCount > 0) {
                    context.getString(
                        R.string.home_tab_delete_confirm_with_sections,
                        oldTitle,
                        sectionCount
                    )
                } else {
                    context.getString(R.string.home_tab_delete_confirm, oldTitle)
                }
                dialog.dismiss()
                context.alert {
                    setTitle(R.string.delete)
                    setMessage(msg)
                    yesButton {
                        HomeTabHelp.removeTab(oldTitle)
                        postEvent(EventBus.HOME_TAB, HomeTabEvent(HomeTabEvent.REMOVE, oldTitle = oldTitle))
                    }
                    noButton()
                }
            }
        }
        negativeButton(R.string.cancel)
        // 校验失败(空标题/重名)时不关闭对话框
        positiveButtonRetain {
            val newTitle = title.value.trim()
            if (newTitle.isBlank()) {
                context.toastOnUi(R.string.home_title_empty)
                return@positiveButtonRetain false
            }
            val ok = if (oldTitle == null) {
                HomeTabHelp.addTab(newTitle)
            } else {
                HomeTabHelp.renameTab(oldTitle, newTitle)
            }
            if (!ok) {
                context.toastOnUi(R.string.home_tab_name_duplicate)
                return@positiveButtonRetain false
            }
            if (oldTitle == null) {
                postEvent(EventBus.HOME_TAB, HomeTabEvent(HomeTabEvent.ADD, newTitle = newTitle))
            } else {
                postEvent(
                    EventBus.HOME_TAB,
                    HomeTabEvent(HomeTabEvent.RENAME, oldTitle = oldTitle, newTitle = newTitle)
                )
            }
            true
        }
    }
}
