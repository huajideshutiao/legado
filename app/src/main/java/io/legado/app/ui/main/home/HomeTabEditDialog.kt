package io.legado.app.ui.main.home

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.HomeTabHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.negativeButton
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.positiveButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.ui.widget.text.AccentTextView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import splitties.views.onClick

fun showHomeTabEditDialog(
    context: Context,
    oldTitle: String? = null
) {
    val dp16 = 16.dpToPx()
    val dp8 = 8.dpToPx()
    val et = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        maxLines = 1
        oldTitle?.let { setText(it) }
    }
    val btnDelete = AccentTextView(context, null).apply {
        setText(R.string.delete)
        visibility = if (oldTitle == null) View.GONE else View.VISIBLE
        setPadding(dp8, dp8, dp8, dp8)
    }
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp16, dp16, dp16, 0)
        addView(TextInputLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            hint = context.getString(R.string.home_tab_title)
            addView(et)
        })
        addView(btnDelete)
    }
    val dialog = context.alert {
        setTitle(if (oldTitle == null) R.string.home_tab_add else R.string.home_tab_edit)
        customView { layout }
        positiveButton(R.string.ok) {
            val newTitle = et.text?.toString()?.trim().orEmpty()
            if (newTitle.isBlank()) {
                context.toastOnUi(R.string.home_title_empty)
                return@positiveButton
            }
            val ok = if (oldTitle == null) {
                HomeTabHelp.addTab(newTitle)
            } else {
                HomeTabHelp.renameTab(oldTitle, newTitle)
            }
            if (!ok) {
                context.toastOnUi(R.string.home_tab_name_duplicate)
                return@positiveButton
            }
            if (oldTitle == null) {
                postEvent(EventBus.HOME_TAB, HomeTabEvent(HomeTabEvent.ADD, newTitle = newTitle))
            } else {
                postEvent(
                    EventBus.HOME_TAB,
                    HomeTabEvent(HomeTabEvent.RENAME, oldTitle = oldTitle, newTitle = newTitle)
                )
            }
        }
        negativeButton(R.string.cancel)
    }
    btnDelete.onClick {
        val sectionCount = HomeTabHelp.getSections(oldTitle!!).size
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
