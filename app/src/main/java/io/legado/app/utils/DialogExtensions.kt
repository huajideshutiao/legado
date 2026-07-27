package io.legado.app.utils

import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import io.legado.app.R

/** Compose 对话框宿主：请求自动弹出软键盘（供 editTextView 的输入框获焦） */
fun io.legado.app.base.ComposeDialog.requestInputMethod() {
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
}

fun android.view.Window.setupAsBottomDialog(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setBackgroundDrawableResource(R.color.background)
    decorView.setPadding(0, 0, 0, 0)
    val attr = attributes
    attr.dimAmount = 0.0f
    attr.gravity = Gravity.BOTTOM
    attributes = attr
    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
}
