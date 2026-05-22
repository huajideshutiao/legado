@file:Suppress("NOTHING_TO_INLINE", "unused", "DEPRECATION")

package io.legado.app.lib.dialogs

import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.utils.applyTint

fun Context.alert(
    title: CharSequence? = null,
    message: CharSequence? = null,
    init: (AlertDialog.Builder.() -> Unit)? = null
): AlertDialog {
    return AlertDialog.Builder(this).apply {
        title?.let { setTitle(it) }
        message?.let { setMessage(it) }
        init?.invoke(this)
    }.showWithTint()
}

inline fun Fragment.alert(
    title: CharSequence? = null,
    message: CharSequence? = null,
    noinline init: (AlertDialog.Builder.() -> Unit)? = null
) = requireActivity().alert(title, message, init)

fun Context.alert(
    titleResource: Int? = null,
    messageResource: Int? = null,
    init: (AlertDialog.Builder.() -> Unit)? = null
): AlertDialog {
    return AlertDialog.Builder(this).apply {
        titleResource?.let { setTitle(it) }
        messageResource?.let { setMessage(it) }
        init?.invoke(this)
    }.showWithTint()
}

inline fun Fragment.alert(
    titleResource: Int? = null,
    messageResource: Int? = null,
    noinline init: (AlertDialog.Builder.() -> Unit)? = null
) = requireActivity().alert(titleResource, messageResource, init)

fun Context.alert(init: AlertDialog.Builder.() -> Unit): AlertDialog =
    AlertDialog.Builder(this).apply(init).showWithTint()

inline fun Fragment.alert(noinline init: AlertDialog.Builder.() -> Unit) =
    requireContext().alert(init)

fun AlertDialog.Builder.showWithTint(): AlertDialog = show().applyTint()

// Extensions for AlertDialog.Builder to match legacy AlertBuilder DSL
fun AlertDialog.Builder.positiveButton(
    buttonText: String,
    onClicked: ((dialog: DialogInterface) -> Unit)? = null
) {
    setPositiveButton(buttonText) { dialog, _ -> onClicked?.invoke(dialog) }
}

fun AlertDialog.Builder.positiveButton(
    buttonTextResource: Int,
    onClicked: ((dialog: DialogInterface) -> Unit)? = null
) {
    setPositiveButton(buttonTextResource) { dialog, _ -> onClicked?.invoke(dialog) }
}

fun AlertDialog.Builder.negativeButton(
    buttonText: String,
    onClicked: ((dialog: DialogInterface) -> Unit)? = null
) {
    setNegativeButton(buttonText) { dialog, _ -> onClicked?.invoke(dialog) }
}

fun AlertDialog.Builder.negativeButton(
    buttonTextResource: Int,
    onClicked: ((dialog: DialogInterface) -> Unit)? = null
) {
    setNegativeButton(buttonTextResource) { dialog, _ -> onClicked?.invoke(dialog) }
}

fun AlertDialog.Builder.neutralButton(
    buttonText: String,
    onClicked: ((dialog: DialogInterface) -> Unit)? = null
) {
    setNeutralButton(buttonText) { dialog, _ -> onClicked?.invoke(dialog) }
}

fun AlertDialog.Builder.neutralButton(
    buttonTextResource: Int,
    onClicked: ((dialog: DialogInterface) -> Unit)? = null
) {
    setNeutralButton(buttonTextResource) { dialog, _ -> onClicked?.invoke(dialog) }
}

fun AlertDialog.Builder.okButton(onClicked: ((dialog: DialogInterface) -> Unit)? = null) =
    positiveButton(android.R.string.ok, onClicked)

fun AlertDialog.Builder.cancelButton(onClicked: ((dialog: DialogInterface) -> Unit)? = null) =
    negativeButton(android.R.string.cancel, onClicked)

fun AlertDialog.Builder.yesButton(onClicked: ((dialog: DialogInterface) -> Unit)? = null) =
    positiveButton(R.string.yes, onClicked)

fun AlertDialog.Builder.noButton(onClicked: ((dialog: DialogInterface) -> Unit)? = null) =
    negativeButton(R.string.no, onClicked)

fun AlertDialog.Builder.onCancelled(handler: (DialogInterface) -> Unit) {
    setOnCancelListener(handler)
}

fun AlertDialog.Builder.onKeyPressed(handler: (dialog: DialogInterface, keyCode: Int, e: KeyEvent) -> Boolean) {
    setOnKeyListener(handler)
}

fun AlertDialog.Builder.onDismiss(handler: (dialog: DialogInterface) -> Unit) {
    setOnDismissListener(handler)
}

fun AlertDialog.Builder.customTitle(view: () -> View) {
    setCustomTitle(view())
}

fun AlertDialog.Builder.customView(view: () -> View) {
    setView(view())
}

fun AlertDialog.Builder.items(
    items: List<CharSequence>,
    onItemSelected: (dialog: DialogInterface, index: Int) -> Unit
) {
    setItems(items.toTypedArray()) { dialog, which ->
        onItemSelected(dialog, which)
    }
}

fun <T> AlertDialog.Builder.items(
    items: List<T>,
    onItemSelected: (dialog: DialogInterface, item: T, index: Int) -> Unit
) {
    setItems(items.map { it.toString() }.toTypedArray()) { dialog, which ->
        onItemSelected(dialog, items[which], which)
    }
}

fun AlertDialog.Builder.multiChoiceItems(
    items: Array<String>,
    checkedItems: BooleanArray,
    onClick: (dialog: DialogInterface, which: Int, isChecked: Boolean) -> Unit
) {
    setMultiChoiceItems(items, checkedItems) { dialog, which, isChecked ->
        onClick(dialog, which, isChecked)
    }
}

fun AlertDialog.Builder.singleChoiceItems(
    items: Array<String>,
    checkedItem: Int = 0,
    onClick: ((dialog: DialogInterface, which: Int) -> Unit)? = null
) {
    setSingleChoiceItems(items, checkedItem) { dialog, which ->
        onClick?.invoke(dialog, which)
    }
}

inline fun Fragment.progressDialog(
    title: Int? = null,
    message: Int? = null,
    noinline init: (ProgressDialog.() -> Unit)? = null
) = requireActivity().progressDialog(title, message, init)

fun Context.progressDialog(
    title: Int? = null,
    message: Int? = null,
    init: (ProgressDialog.() -> Unit)? = null
) = progressDialog(title?.let { getString(it) }, message?.let { getString(it) }, false, init)


inline fun Fragment.indeterminateProgressDialog(
    title: Int? = null,
    message: Int? = null,
    noinline init: (ProgressDialog.() -> Unit)? = null
) = requireActivity().indeterminateProgressDialog(title, message, init)

fun Context.indeterminateProgressDialog(
    title: Int? = null,
    message: Int? = null,
    init: (ProgressDialog.() -> Unit)? = null
) = progressDialog(title?.let { getString(it) }, message?.let { getString(it) }, true, init)

inline fun Fragment.progressDialog(
    title: CharSequence? = null,
    message: CharSequence? = null,
    noinline init: (ProgressDialog.() -> Unit)? = null
) = requireActivity().progressDialog(title, message, init)

fun Context.progressDialog(
    title: CharSequence? = null,
    message: CharSequence? = null,
    init: (ProgressDialog.() -> Unit)? = null
) = progressDialog(title, message, false, init)


inline fun Fragment.indeterminateProgressDialog(
    title: CharSequence? = null,
    message: CharSequence? = null,
    noinline init: (ProgressDialog.() -> Unit)? = null
) = requireActivity().indeterminateProgressDialog(title, message, init)

fun Context.indeterminateProgressDialog(
    title: CharSequence? = null,
    message: CharSequence? = null,
    init: (ProgressDialog.() -> Unit)? = null
) = progressDialog(title, message, true, init)


private fun Context.progressDialog(
    title: CharSequence? = null,
    message: CharSequence? = null,
    indeterminate: Boolean,
    init: (ProgressDialog.() -> Unit)? = null
) = ProgressDialog(this).apply {
    isIndeterminate = indeterminate
    if (!indeterminate) setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
    if (message != null) setMessage(message)
    if (title != null) setTitle(title)
    if (init != null) init()
    show()
}
