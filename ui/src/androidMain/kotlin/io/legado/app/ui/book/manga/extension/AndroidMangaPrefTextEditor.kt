package io.legado.app.ui.book.manga.extension

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import io.legado.app.constant.AppLog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * 插件文本项编辑弹窗 (Android 真实控件承载, 对齐 androidx/Mihon 编辑对话框的同一对象语义):
 *
 * - 会话 ([MangaPrefTextEditorSession]) 持本次实际配置对象, 绑定监听、动态校验、确认拦截与
 *   落值全程针对它;
 * - 弹窗内以 [AndroidView] 塞真实 [EditText]: 先建完整同树 (隐藏确认键 + 输入框), 再按库
 *   弹窗顺序绑定 (填当前值 → 光标到末尾 → 插件监听器 → 附着后请求焦点), 插件在绑定内
 *   `rootView.findViewById(button1)` 当场可达, 单行/多行、hint、输入过滤与 TextWatcher 校验
 *   直接生效;
 * - 确认先 `callChangeListener` 通过才落值 (会话内, 主线程); 业务拦截关闭不落值, 实际故障
 *   记录日志后关闭; 取消不保存;
 * - 控件随组合弃置, 释放时取消输入框实际挂着的焦点回调, 无进程级注册。
 */
@Composable
fun AndroidMangaPrefTextEditor(request: MangaPrefTextEditorRequest) {
    val editorSession = request.session as MangaPrefTextEditorSession
    // 平台编辑器的状态与作用域整体属于本次会话: 换会话即整体弃置, 旧提交回调不会落到新会话
    key(editorSession) {
        AndroidPrefTextEditorSession(request, editorSession)
    }
}

@Composable
private fun AndroidPrefTextEditorSession(
    request: MangaPrefTextEditorRequest,
    editorSession: MangaPrefTextEditorSession,
) {
    var confirmEnabled by remember(editorSession) { mutableStateOf(true) }
    var editorRoot by remember(editorSession) { mutableStateOf<PrefTextEditorRoot?>(null) }
    val scope = rememberCoroutineScope()
    val okText = stringResource(Res.string.ok)
    val cancelText = stringResource(Res.string.cancel)

    AppAlertDialog(
        onDismissRequest = request.onDismiss,
        okButton = AlertButton(
            text = okText,
            enabled = confirmEnabled,
            // 提交实际结束后才关闭 (默认立即关会取消未完成的提交)
            dismissOnClick = false,
        ) {
            val value = editorRoot?.field?.text?.toString()
            if (value == null) {
                request.onSettled(false)
            } else {
                scope.launch {
                    // 业务拦截与真实故障区分: 故障记录日志后关闭, 不伪装成输入被拒
                    val committed = try {
                        editorSession.commit(value)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        AppLog.put("插件配置保存失败", e)
                        null
                    }
                    if (committed != null) request.onSettled(committed) else request.onDismiss()
                }
            }
        },
        cancelButton = AlertButton(text = cancelText) {},
    ) {
        // 对齐库弹窗: 弹窗打开即显示键盘
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        AndroidView(
            factory = { viewContext ->
                val root = PrefTextEditorRoot(viewContext)
                root.addView(
                    object : Button(viewContext) {
                        init {
                            // 插件动态校验按根视图查找的确认键 id, 初始可用态为平台默认
                            id = android.R.id.button1
                            visibility = View.GONE
                        }

                        override fun setEnabled(enabled: Boolean) {
                            super.setEnabled(enabled)
                            confirmEnabled = enabled
                        }
                    }
                )
                val field = EditText(viewContext)
                root.field = field
                root.addView(
                    field,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                // 完整同树后绑定: 插件绑定内 rootView.findViewById(button1) 当场可达
                try {
                    field.setText(editorSession.initialText())
                    field.setSelection(field.length())
                    editorSession.onBindEditText(field)
                    field.post(Runnable { field.requestFocus() }.also { root.focusRunnable = it })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.put("插件配置输入绑定失败", e)
                    request.onDismiss()
                }
                editorRoot = root
                root
            },
            onRelease = { released ->
                // 取消输入框实际 post 的焦点回调: 目标控件取自被释放的这个实际根视图
                released.focusRunnable?.let(released.field::removeCallbacks)
                released.focusRunnable = null
                if (editorRoot === released) editorRoot = null
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 弹窗输入根视图: 持本组合创建的实际输入框与其已挂的焦点回调。 */
private class PrefTextEditorRoot(context: Context) : LinearLayout(context) {

    lateinit var field: EditText

    var focusRunnable: Runnable? = null
}
