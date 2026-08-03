package io.legado.app.utils

import android.view.WindowManager

/** Compose 对话框宿主：请求自动弹出软键盘（供 editTextView 的输入框获焦） */
fun io.legado.app.base.ComposeDialog.requestInputMethod() {
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
}
