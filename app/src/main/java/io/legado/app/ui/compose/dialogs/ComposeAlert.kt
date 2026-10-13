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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.help.i18n.androidAppString
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialogContent
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSelectorList
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * Compose 版 alert DSL，签名对齐 lib/dialogs 的 alert（供机械替换 import）。
 * 渲染宿主为 ComposeAlertDialogHost (挂主界面), 正文用 [AppAlertDialogContent]。
 * customView 槽由 View `()->View` 改为 `@Composable`——迁移时 customView 内容需 Compose 化。
 *
 * 返回 [AlertDialogHandle]（是 DialogInterface），dismiss()/cancel() 与旧 AlertDialog 兼容。
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

    // 供按钮回调 dismiss 用 (原 ComposeDialog 实例, 现为声明式句柄)
    internal lateinit var dialog: AlertDialogHandle

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
    fun neutralButtonRetain(text: String, onClicked: () -> Unit) {
        neutralButton = AlertButton(text, dismissOnClick = false, onClick = onClicked)
    }

    fun neutralButtonRetain(@StringRes textRes: Int, onClicked: () -> Unit) {
        neutralButton = AlertButton(context.getString(textRes), dismissOnClick = false, onClick = onClicked)
    }

    fun okButton(onClicked: (() -> Unit)? = null) =
        positiveButton(android.R.string.ok, onClicked)

    /**
     * 校验保留型确认按钮：onClicked 返回 true 才关闭对话框，
     * 复刻旧代码 getButton(BUTTON_POSITIVE).setOnClickListener 手动控制关闭的校验语义。
     */
    fun positiveButtonRetain(text: String, onClicked: () -> Boolean) {
        okButton = AlertButton(text, dismissOnClick = false, onClick = {
            if (onClicked()) dialog.dismiss()
        })
    }

    fun positiveButtonRetain(@StringRes textRes: Int = android.R.string.ok, onClicked: () -> Boolean) {
        okButton = AlertButton(context.getString(textRes), dismissOnClick = false, onClick = {
            if (onClicked()) dialog.dismiss()
        })
    }

    fun cancelButton(onClicked: (() -> Unit)? = null) =
        negativeButton(android.R.string.cancel, onClicked)

    fun yesButton(onClicked: (() -> Unit)? = null) =
        positiveButton(androidAppString("yes"), onClicked)

    fun noButton(onClicked: (() -> Unit)? = null) =
        negativeButton(androidAppString("no"), onClicked)

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
        val state = mutableStateOf(text)
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
                    // 左右不叠加额外边距: AppAlertDialogContent 内容槽已给 spacingDefault(8dp),
                    // 输入框与标题/正文同一边界 (再加 24dp 会让字段比正文多缩进 24dp)
                    .padding(vertical = DesignTokens.spacingDefault),
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
                            .padding(vertical = DesignTokens.spacingMd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacingMd),
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
                    var checked by remember { mutableStateOf(states[i]) }
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
                            .padding(vertical = DesignTokens.spacingDefault),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacingMd),
                    ) {
                        AppCheckbox(checked = checked, onCheckedChange = null)
                        Text(label, color = colors.primaryText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    internal fun build(): AlertDialogHandle {
        val handle = AlertDialogHandle()
        dialog = handle
        val pending = AlertDialogPending(
            title = title?.toString(),
            message = message?.toString(),
            okButton = okButton,
            cancelButton = cancelButton,
            neutralButton = neutralButton,
            content = content,
            handle = handle,
            cancelable = cancelable,
        )
        handle.dequeue = { AlertDialogRequests.remove(pending) }
        handle.onCancelled = onCancelListener?.let { l -> { l(handle) } }
        handle.onDismissed = onDismissListener?.let { l -> { l(handle) } }
        AlertDialogRequests.show(pending)
        return handle
    }
}

fun Context.alert(
    title: CharSequence? = null,
    message: CharSequence? = null,
    init: (AlertBuilder.() -> Unit)? = null
): AlertDialogHandle {
    val builder = AlertBuilder(this).apply {
        title?.let { setTitle(it) }
        message?.let { setMessage(it) }
        init?.invoke(this)
    }
    return builder.build()
}

fun Context.alert(
    titleResource: Int? = null,
    messageResource: Int? = null,
    init: (AlertBuilder.() -> Unit)? = null
): AlertDialogHandle {
    val builder = AlertBuilder(this).apply {
        titleResource?.let { setTitle(it) }
        messageResource?.let { setMessage(it) }
        init?.invoke(this)
    }
    return builder.build()
}

fun Context.alert(init: AlertBuilder.() -> Unit): AlertDialogHandle {
    val builder = AlertBuilder(this).apply(init)
    return builder.build()
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


/**
 * alert DSL 的声明式句柄 (DialogInterface 兼容): 出队即关闭。
 *
 * 请求队列是显示的唯一来源, 故 dismiss/cancel 先出队再回调监听 (对照 DialogInterface 语义:
 * dismiss = onDismiss, cancel = onCancel + onDismiss)。
 */
class AlertDialogHandle internal constructor() : DialogInterface {
    internal var dequeue: () -> Unit = {}
    internal var onDismissed: (() -> Unit)? = null
    internal var onCancelled: (() -> Unit)? = null

    override fun dismiss() {
        dequeue()
        onDismissed?.invoke()
    }

    override fun cancel() {
        dequeue()
        onCancelled?.invoke()
        onDismissed?.invoke()
    }
}

internal class AlertDialogPending(
    val title: String?,
    val message: String?,
    val okButton: AlertButton?,
    val cancelButton: AlertButton?,
    val neutralButton: AlertButton?,
    val content: (@Composable () -> Unit)?,
    val handle: AlertDialogHandle,
    /** 对照 setCancelable: false = 返回键与点外部都不关闭 */
    val cancelable: Boolean,
)

/** alert DSL 请求队列: 调用方即时入队, [ComposeAlertDialogHost] 挂在主界面渲染。 */
object AlertDialogRequests {

    /** 在队请求: 旧机制每个 alert 是独立窗口、可叠放, 故用列表而非单槽。 */
    internal val pending = mutableStateListOf<AlertDialogPending>()

    internal fun show(pending: AlertDialogPending) {
        this.pending.add(pending)
    }

    internal fun remove(pending: AlertDialogPending) {
        this.pending.remove(pending)
    }
}

/**
 * alert DSL 请求的渲染宿主 (挂各 Activity Content 尾部: 主界面 / 权限页 / 透明壳)。
 *
 * 只在当前界面处于前台时渲染。请求队列是进程级共享的, 各界面各挂一份宿主, 缺此前台判据时
 * 同一请求会被多份宿主同时画成多个窗口; 判据必须取 RESUMED: 权限页与透明壳都是半透明窗口,
 * 其下层界面只走 ON_PAUSE 不走 ON_STOP, 按 STARTED 判据时下层仍算前台而照旧重复。
 */
@Composable
fun ComposeAlertDialogHost() {
    if (AlertDialogRequests.pending.isEmpty()) return
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!resumed) return
    // 在队请求逐条渲染, 各成独立窗口 (与旧机制每请求一窗一致); 关闭只走句柄出队
    AlertDialogRequests.pending.forEach { pending ->
        AppDialog(
            onDismissRequest = { pending.handle.cancel() },
            properties = AppDialogSizes.properties(
                dismissOnBackPress = pending.cancelable,
                dismissOnClickOutside = pending.cancelable,
            ),
        ) {
            AppAlertDialogContent(
                onDismissRequest = { pending.handle.dismiss() },
                title = pending.title,
                message = pending.message,
                okButton = pending.okButton,
                cancelButton = pending.cancelButton,
                neutralButton = pending.neutralButton,
                content = pending.content,
            )
        }
    }
}
