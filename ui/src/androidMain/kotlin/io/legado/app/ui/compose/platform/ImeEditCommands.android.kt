// InterceptPlatformTextInput / PlatformTextInputInterceptor 在 CPF CMP 1.9.2 与 androidx 1.11.2
// 仍带 @ExperimentalComposeUiApi (输入会话拦截通道), 本文件只参与 Android 编译。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.legado.app.ui.compose.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * [ProvidePlatformImeEditCommands] 的 Android actual: 把输入框的平台文本输入会话包一层,
 * 使输入连接的编辑命令 (复制/剪切/粘贴) 由本应用直接执行, 全选交回框架。
 *
 * 只包一层输入连接, 不改动输入会话本身: 输入法其余调用 (提交文本/取选中文字/光标更新/
 * 内容提交) 一律原样透传, 与不装本通道时的行为一致。
 */
@Composable
internal actual fun ProvidePlatformImeEditCommands(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val interceptor = remember(context) { ImeEditCommandInterceptor(context) }
    InterceptPlatformTextInput(interceptor) { content() }
}

private class ImeEditCommandInterceptor(
    private val context: Context,
) : PlatformTextInputInterceptor {

    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing = nextHandler.startInputMethod(
        object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                ImeEditCommandInputConnection(
                    delegate = request.createInputConnection(outAttributes),
                    context = context,
                    editorInfo = outAttributes,
                )
        }
    )
}

/**
 * 输入连接包装: 复制/剪切/粘贴由本应用执行 (框架对这三项只合成按键事件并恒报失败, 见
 * [ProvidePlatformImeEditCommands] 的说明), 其余命令 (全选/自动填充/未知项) 交回框架。
 *
 * 复制源取输入连接的选中文字 (与框架同一口径: 视觉文本), 粘贴按平台语义替换当前选区并落光标。
 * 口令输入框 (输入属性声明了密码变体) 与原生输入框一致地拒绝复制/剪切; 只读/禁用输入框
 * 不会建立输入会话 (框架在不可编辑时销毁连接), 故此处无需判定可编辑性。
 */
private class ImeEditCommandInputConnection(
    delegate: InputConnection,
    private val context: Context,
    editorInfo: EditorInfo,
) : InputConnectionWrapper(delegate, false) {

    private val copyAllowed = !editorInfo.isPasswordVariation()

    override fun performContextMenuAction(id: Int): Boolean = when (id) {
        android.R.id.copy -> copySelection(cut = false)
        android.R.id.cut -> copySelection(cut = true)
        android.R.id.paste -> pasteFromClipboard()
        else -> super.performContextMenuAction(id)
    }

    private fun copySelection(cut: Boolean): Boolean {
        if (!copyAllowed) return false
        val selected = getSelectedText(0)?.takeIf { it.isNotEmpty() } ?: return false
        clipboardManager.setPrimaryClip(ClipData.newPlainText(null, selected))
        // 剪切 = 复制后删除选区: 空串替换选区即删除 (对齐输入框对提交文本的语义)
        if (cut) commitText("", 1)
        return true
    }

    private fun pasteFromClipboard(): Boolean {
        val clip = clipboardManager.primaryClip ?: return false
        if (clip.itemCount == 0) return false
        val text = clip.getItemAt(0).coerceToText(context)?.takeIf { it.isNotEmpty() } ?: return false
        // 替换当前选区并落光标 (对齐框架粘贴语义)
        commitText(text, 1)
        return true
    }

    private val clipboardManager: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
}

/** 输入属性是否声明了密码变体 (对齐原生输入框拒绝复制/剪切的条件)。 */
private fun EditorInfo.isPasswordVariation(): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT ->
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}
