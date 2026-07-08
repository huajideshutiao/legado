package io.legado.app.ui.main.home

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.HomeTabHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.negativeButton
import io.legado.app.lib.dialogs.neutralButton
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.positiveButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.lib.theme.space
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi

fun showHomeTabEditDialog(
    context: Context,
    oldTitle: String? = null
) {
    val dp16 = context.space.lg
    val et = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        maxLines = 1
        oldTitle?.let { setText(it) }
    }
    // customView 仅承载输入框, 操作按钮交给 alert DSL 标准底栏(水平排布)
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp16, 0, dp16, 0)
        addView(TextInputLayout(context).apply {
            hint = context.getString(R.string.home_tab_title)
            addView(et)
        })
    }
    val dialog = context.alert {
        setTitle(if (oldTitle == null) R.string.home_tab_add else R.string.home_tab_edit)
        customView { layout }
        // 编辑模式提供"删除"中性按钮(左侧); alert DSL 标准底栏天然水平排布
        if (oldTitle != null) {
            neutralButton(R.string.delete)
        }
        negativeButton(R.string.cancel)
        // positiveButton 传空 listener 占位, 实际点击逻辑在 show 之后通过 getButton 覆盖,
        // 以实现校验失败(空标题/重名)时不 dismiss
        positiveButton(R.string.ok)
    }
    // 覆盖 positive 默认 dismiss 行为: 校验失败保留对话框
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val newTitle = et.text?.toString()?.trim().orEmpty()
        if (newTitle.isBlank()) {
            context.toastOnUi(R.string.home_title_empty)
            return@setOnClickListener
        }
        val ok = if (oldTitle == null) {
            HomeTabHelp.addTab(newTitle)
        } else {
            HomeTabHelp.renameTab(oldTitle, newTitle)
        }
        if (!ok) {
            context.toastOnUi(R.string.home_tab_name_duplicate)
            return@setOnClickListener
        }
        if (oldTitle == null) {
            postEvent(EventBus.HOME_TAB, HomeTabEvent(HomeTabEvent.ADD, newTitle = newTitle))
        } else {
            postEvent(
                EventBus.HOME_TAB,
                HomeTabEvent(HomeTabEvent.RENAME, oldTitle = oldTitle, newTitle = newTitle)
            )
        }
        dialog.dismiss()
    }
    // 编辑模式覆盖 neutral(删除)按钮: 弹二次确认
    if (oldTitle != null) {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
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
}
