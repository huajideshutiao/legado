package io.legado.app.ui.compose.dialogs

import android.content.Context
import android.content.DialogInterface
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import io.legado.app.base.ComposeDialog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialogContent
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSelectorList
import io.legado.app.ui.compose.theme.AppTheme

/**
 * Compose 版 alert DSL，签名对齐 lib/dialogs 的 alert（供机械替换 import）。
 * 宿主用 [ComposeDialog]（ComponentDialog 挂 ComposeView），正文用 [AppAlertDialogContent]。
 * customView 槽由 View `()->View` 改为 `@Composable`——迁移时 customView 内容需 Compose 化。
 *
 * 返回 [ComposeDialog]（是 DialogInterface），dismiss()/isShowing 等与旧 AlertDialog 兼容。
 */

class AlertBuilder(val context: Context) {

    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var okButton: AlertButton? = null
    private var cancelButton: AlertButton? = null
    private var neutralButton: AlertButton? = null
    private var content: (@Composable () -> Unit)? = null
    private var cancelable: Boolean = true
    private var onCancelListener: ((DialogInterface) -> Unit)? = null
    private var onDismissListener: ((DialogInterface) -> Unit)? = null

    // 供命令式宿主回填，使按钮/列表回调里的 dialog 参数可用
    internal lateinit var dialog: ComposeDialog

    fun setTitle(title: CharSequence) {
        this.title = title
    }

    fun setTitle(@StringRes resId: Int) {
        this.title = context.getString(resId)
    }

    fun setMessage(message: CharSequence) {
        this.message = message
    }

    fun setMessage(@StringRes resId: Int) {
        this.message = context.getString(resId)
    }

    fun setCancelable(cancelable: Boolean) {
        this.cancelable = cancelable
    }

    // ---- 按钮：文案 String / 资源 Int，点击回调无参（旧 DSL 的 DialogInterface 参数在非 read 站点均未使用）----

    fun positiveButton(text: String, onClicked: (() -> Unit)? = null) {
        okButton = AlertButton(text, onClick = onClicked)
    }

    fun positiveButton(@StringRes textRes: Int, onClicked: (() -> Unit)? = null) {
        okButton = AlertButton(context.getString(textRes), onClick = onClicked)
    }

    fun negativeButton(text: String, onClicked: (() -> Unit)? = null) {
        cancelButton = AlertButton(text, onClick = onClicked)
    }

    fun negativeButton(@StringRes textRes: Int, onClicked: (() -> Unit)? = null) {
        cancelButton = AlertButton(context.getString(textRes), onClick = onClicked)
    }

    fun neutralButton(text: String, onClicked: (() -> Unit)? = null) {
        neutralButton = AlertButton(text, onClick = onClicked)
    }

    fun neutralButton(@StringRes textRes: Int, onClicked: (() -> Unit)? = null) {
        neutralButton = AlertButton(context.getString(textRes), onClick = onClicked)
    }

    /** 校验保留型中性按钮：点击不关闭对话框，复刻 getButton(BUTTON_NEUTRAL) 覆盖 dismiss 的语义。 */
    fun neutralButtonRetain(@StringRes textRes: Int, onClicked: () -> Unit) {
        neutralButton = AlertButton(context.getString(textRes), dismissOnClick = false, onClick = onClicked)
    }

    fun okButton(onClicked: (() -> Unit)? = null) =
        positiveButton(android.R.string.ok, onClicked)

    /**
     * 校验保留型确认按钮：onClicked 返回 true 才关闭对话框，
     * 复刻旧代码 getButton(BUTTON_POSITIVE).setOnClickListener 手动控制关闭的校验语义。
     */
    fun positiveButtonRetain(@StringRes textRes: Int = android.R.string.ok, onClicked: () -> Boolean) {
        okButton = AlertButton(context.getString(textRes), dismissOnClick = false, onClick = {
            if (onClicked()) dialog.dismiss()
        })
    }

    fun cancelButton(onClicked: (() -> Unit)? = null) =
        negativeButton(android.R.string.cancel, onClicked)

    fun yesButton(onClicked: (() -> Unit)? = null) =
        positiveButton(io.legado.app.R.string.yes, onClicked)

    fun noButton(onClicked: (() -> Unit)? = null) =
        negativeButton(io.legado.app.R.string.no, onClicked)

    // ---- 生命周期回调 ----

    fun onCancelled(handler: (DialogInterface) -> Unit) {
        onCancelListener = handler
    }

    fun onDismiss(handler: (DialogInterface) -> Unit) {
        onDismissListener = handler
    }

    // ---- customView / items ----

    /** customView 槽：内容改由 Compose 提供（旧 DSL 为 ()->View） */
    fun customView(view: @Composable () -> Unit) {
        content = view
    }

    /**
     * 便捷 customView：复刻 DialogEditTextBinding 的历史下拉输入框，
     * 返回读取当前文本的 getter，供 okButton 回调取值。
     */
    fun editTextView(
        hint: String? = null,
        text: String = "",
        filterValues: List<String>? = null,
        onDelete: ((String) -> Unit)? = null,
        autoFocus: Boolean = false,
    ): () -> String {
        val state = androidx.compose.runtime.mutableStateOf(text)
        content = {
            AppAutoCompleteField(
                value = state.value,
                onValueChange = { state.value = it },
                label = hint,
                values = filterValues ?: emptyList(),
                onDelete = onDelete,
                autoFocus = autoFocus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        return { state.value }
    }

    fun items(items: List<CharSequence>, onItemSelected: (dialog: DialogInterface, index: Int) -> Unit) {
        val labels = items.map { it.toString() }
        content = {
            AppSelectorList(labels) { index ->
                onItemSelected(dialog, index)
                dialog.dismiss()
            }
        }
    }

    fun <T> items(items: List<T>, onItemSelected: (dialog: DialogInterface, item: T, index: Int) -> Unit) {
        val labels = items.map { it.toString() }
        content = {
            AppSelectorList(labels) { index ->
                onItemSelected(dialog, items[index], index)
                dialog.dismiss()
            }
        }
    }

    fun singleChoiceItems(
        items: Array<String>,
        checkedItem: Int = 0,
        onClick: ((dialog: DialogInterface, which: Int) -> Unit)? = null,
    ) {
        content = {
            var selected by remember { mutableIntStateOf(checkedItem) }
            val colors = AppTheme.colors
            Column(Modifier.selectableGroup()) {
                items.forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = i == selected,
                                onClick = { selected = i; onClick?.invoke(dialog, i) },
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppRadioButton(
                            selected = i == selected,
                            onClick = null,
                        )
                        Text(label, color = colors.primaryText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    fun multiChoiceItems(
        items: Array<String>,
        checkedItems: BooleanArray,
        onClick: (dialog: DialogInterface, which: Int, isChecked: Boolean) -> Unit,
    ) {
        content = {
            val states = remember { checkedItems.toMutableList() }
            val colors = AppTheme.colors
            Column {
                items.forEachIndexed { i, label ->
                    var checked by remember { androidx.compose.runtime.mutableStateOf(states[i]) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = {
                                    checked = it
                                    states[i] = it
                                    onClick(dialog, i, it)
                                },
                            )
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppCheckbox(checked = checked, onCheckedChange = null)
                        Text(label, color = colors.primaryText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    internal fun build(): ComposeDialog {
        val d = ComposeDialog(context, applyFilletBackground = false)
        dialog = d
        d.setComposeContent {
            AppAlertDialogContent(
                onDismissRequest = { d.dismiss() },
                title = title?.toString(),
                message = message?.toString(),
                okButton = okButton,
                cancelButton = cancelButton,
                neutralButton = neutralButton,
                content = content,
            )
        }
        d.setCancelable(cancelable)
        onCancelListener?.let { l -> d.setOnCancelListener { l(it) } }
        onDismissListener?.let { l -> d.setOnDismissListener { l(it) } }
        return d
    }
}

fun Context.alert(
    title: CharSequence? = null,
    message: CharSequence? = null,
    init: (AlertBuilder.() -> Unit)? = null
): ComposeDialog {
    val builder = AlertBuilder(this).apply {
        title?.let { setTitle(it) }
        message?.let { setMessage(it) }
        init?.invoke(this)
    }
    return builder.build().also { it.show() }
}

fun Context.alert(
    titleResource: Int? = null,
    messageResource: Int? = null,
    init: (AlertBuilder.() -> Unit)? = null
): ComposeDialog {
    val builder = AlertBuilder(this).apply {
        titleResource?.let { setTitle(it) }
        messageResource?.let { setMessage(it) }
        init?.invoke(this)
    }
    return builder.build().also { it.show() }
}

fun Context.alert(init: AlertBuilder.() -> Unit): ComposeDialog {
    val builder = AlertBuilder(this).apply(init)
    return builder.build().also { it.show() }
}

fun Fragment.alert(
    title: CharSequence? = null,
    message: CharSequence? = null,
    init: (AlertBuilder.() -> Unit)? = null
) = requireActivity().alert(title, message, init)

fun Fragment.alert(
    titleResource: Int? = null,
    messageResource: Int? = null,
    init: (AlertBuilder.() -> Unit)? = null
) = requireActivity().alert(titleResource, messageResource, init)

fun Fragment.alert(init: AlertBuilder.() -> Unit) = requireContext().alert(init)
