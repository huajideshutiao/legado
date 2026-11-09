package io.legado.app.ui.book.manga.extension

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
 * 插件文本项编辑弹窗 (Android 真实控件承载, 对齐 Mihon `EditTextPreferenceDialog` 与
 * androidx `EditTextPreferenceDialogFragmentCompat` 的同一对象语义):
 *
 * - 会话 ([MangaPrefTextEditorSession]) 持本次实际配置对象: 编辑控件与该对象同属一个编辑
 *   会话, 绑定监听器、动态校验、确认拦截和落值全针对它;
 * - 弹窗内以 [AndroidView] 塞真实 [EditText]: 先建完整同树 (隐藏确认键 + 输入框), 再按库
 *   弹窗顺序绑定 (填当前值 → 光标到末尾 → 插件监听器 → 附着后请求焦点) —— 插件在绑定内
 *   `rootView.findViewById(button1)` 当场可达, 插件的单行/多行、hint、输入过滤与
 *   TextWatcher 校验直接生效;
 * - 确认先 `callChangeListener` 通过才落值 (会话内, 后台域); 提交异常与被插件拦截区分:
 *   异常记录日志且弹窗保持打开, 不伪装成输入被拒; 取消不保存;
 * - 释放时取消未执行的焦点回调, 控件与会话随组合弃置, 无进程级注册。
 */
@Composable
fun AndroidMangaPrefTextEditor(request: MangaPrefTextEditorRequest) {
    // 会话由同平台配置桥创建, 平台绑定面在此取回 (装配契约)
    val editorSession = request.session as MangaPrefTextEditorSession
    var confirmEnabled by remember(editorSession) { mutableStateOf(true) }
    var editTextRef by remember(editorSession) { mutableStateOf<EditText?>(null) }
    var focusRunnable by remember(editorSession) { mutableStateOf<Runnable?>(null) }
    val scope = rememberCoroutineScope()
    val okText = stringResource(Res.string.ok)
    val cancelText = stringResource(Res.string.cancel)

    AppAlertDialog(
        onDismissRequest = request.onDismiss,
        okButton = AlertButton(
            text = okText,
            enabled = confirmEnabled,
            // 显式关闭边界: 提交完成 (含业务拒绝/故障) 后按结果关闭, 不用默认立即关
            // (那会取消未完成的提交)
            dismissOnClick = false,
        ) {
            val value = editTextRef?.text?.toString()
            if (value == null) {
                // 对齐库弹窗: 无可读文本时确认即关闭
                request.onSettled(false)
            } else {
                scope.launch {
                    // 真实故障与插件拦截区分: 异常记录日志, 关闭边界一致 (失败反馈待另批),
                    // 不伪装成功/拒绝
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
                val root = LinearLayout(viewContext)
                val confirmButton = object : Button(viewContext) {
                    init {
                        id = android.R.id.button1
                        visibility = View.GONE
                    }

                    override fun setEnabled(enabled: Boolean) {
                        super.setEnabled(enabled)
                        confirmEnabled = enabled
                    }
                }
                root.addView(confirmButton)
                val field = EditText(viewContext)
                root.addView(
                    field,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                // 完整同树后再按库顺序绑定: 插件绑定内 rootView.findViewById(button1) 当场可达
                try {
                    field.setText(editorSession.initialText())
                    field.setSelection(field.length())
                    editorSession.onBindEditText(field)
                    val runnable = Runnable { field.requestFocus() }
                    focusRunnable = runnable
                    field.post(runnable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.put("插件配置输入绑定失败", e)
                    request.onDismiss()
                }
                editTextRef = field
                root
            },
            onRelease = { _ ->
                // 取消未执行的焦点回调: Runnable 实际 post 在子输入框上, 必须针对实际子控件;
                // 控件与会话随组合弃置 (插件 watcher 引用随 View 回收)
                val field = editTextRef
                focusRunnable?.let(field::removeCallbacks)
                focusRunnable = null
                editTextRef = null
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
