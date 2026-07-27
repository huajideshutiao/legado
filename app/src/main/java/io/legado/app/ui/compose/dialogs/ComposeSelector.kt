package io.legado.app.ui.compose.dialogs

import android.content.Context
import android.content.DialogInterface

/**
 * Compose 版 selector DSL，签名对齐 lib/dialogs 的 selector（供机械替换 import）。
 * 内部走 [alert] 的 items 槽，回调携带 [DialogInterface] 与旧 DSL 一致。
 */

fun Context.selector(
    items: List<CharSequence>,
    onClick: (DialogInterface, Int) -> Unit
) {
    alert {
        items(items, onClick)
    }
}

fun <T> Context.selector(
    items: List<T>,
    onClick: (DialogInterface, T, Int) -> Unit
) {
    alert {
        items(items, onClick)
    }
}

fun Context.selector(
    title: CharSequence,
    items: List<CharSequence>,
    onClick: (DialogInterface, Int) -> Unit
) {
    alert {
        setTitle(title)
        items(items, onClick)
    }
}

fun <T> Context.selector(
    title: CharSequence,
    items: List<T>,
    onClick: (DialogInterface, T, Int) -> Unit
) {
    alert {
        setTitle(title)
        items(items, onClick)
    }
}

fun Context.selector(
    titleSource: Int,
    items: List<CharSequence>,
    onClick: (DialogInterface, Int) -> Unit
) {
    alert {
        setTitle(titleSource)
        items(items, onClick)
    }
}

fun <T> Context.selector(
    titleSource: Int,
    items: List<T>,
    onClick: (DialogInterface, T, Int) -> Unit
) {
    alert {
        setTitle(titleSource)
        items(items, onClick)
    }
}
