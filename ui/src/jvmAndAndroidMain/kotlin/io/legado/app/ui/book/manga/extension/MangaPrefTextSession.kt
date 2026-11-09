package io.legado.app.ui.book.manga.extension

import android.widget.EditText

/**
 * 平台编辑控件绑定面 (共享会话的平台子类型): 槽实现创建编辑控件 (Android 真实 EditText /
 * 桌面兼容层活适配输入框) 后取回本子类型, 把插件挂的 `OnBindEditTextListener` 跑在该控件上
 * (对齐 androidx `EditTextPreferenceDialogFragmentCompat.onBindDialogView` 的触发时机,
 * 主线程)。单行/多行、hint、过滤器、动态校验监听都经它落到该控件。
 */
interface MangaPrefTextEditorSession : MangaPrefTextSession {

    fun onBindEditText(editText: EditText)
}
