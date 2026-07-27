package io.legado.app.ui.book.read

// I18N KEYS (need to register in ResourceProvider.jvm.kt):
//   "content_edit_reset" to "重置",
//   "content_edit_copy_all" to "复制全部",
//   "content_edit_copy_success" to "已复制"

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * Arco Design 主色 arcoblue-6 (#165DFF)。
 *
 * 用作对话框标题栏保存图标 + 底部确认按钮的强调色。
 * 不复用 AppTheme.accent, 避免不同主题下颜色漂移导致与原 app 端 arco 规范不一致。
 */
private val ArcoBlue6 = Color(0xFF165DFF)

/**
 * 章节正文编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.read.ContentEditDialog` (BaseComposeDialogFragment),
 * 但去掉对 Android Fragment / Bundle / ContentEditViewModel / ReadBook 模型 / BookHelp /
 * appDb / AndroidView + AppCompatEditText / alert DSL 的依赖, 改为纯 @Composable + 回调形式:
 * - 调用方传入 [chapterName] + [content], [onSubmit] / [onDismiss] 回调;
 * - [onReset] 可选注入"重置"业务 (从源重新获取正文覆盖本地修改), null 时不渲染重置菜单项;
 * - [clipTextSink] 可选注入剪贴板写入能力, null 时不渲染复制全部菜单项。
 *
 * # 业务对齐 (对照 app 端原版)
 *
 * - 标题栏: 标题 = 章节名, 返回 (dismiss) + 保存 (ic_save) + OverflowMenu (重置 / 复制全部);
 * - 正文区: 多行 OutlinedTextField, maxLines = 10 (替代 app 端 AndroidView + AppCompatEditText,
 *   原版用 View EditText 是为了 layout.getLineForOffset 按阅读进度滚动定位, KMP 版用 Compose
 *   原生 OutlinedTextField, 滚动定位由 Compose 自动处理, 不再需要 View 互操作);
 * - 保存: 校验非空 (与 app 端 save() 中 `contentView?.text?.toString() ?: return` 等价,
 *   空内容直接 return 不回调), 通过则回调 [onSubmit] + [onDismiss];
 * - 重置: 委托 [onReset] (调用方负责从源重新拉取正文并更新 [content] 参数触发重组);
 * - 复制全部: "$chapterName\n$content" + [clipTextSink] 写剪贴板 + toast "已复制"
 *   (与 app 端 `requireContext().sendToClip("$title\n${contentView?.text}")` 等价)。
 *
 * # 与 app 端的差异
 *
 * - 不实现"标题点击编辑章节名" (依赖 appDb.bookChapterDao + ReadBook 模型, 桌面端未下沉,
 *   章节名编辑由调用方在外部实现);
 * - 不实现 onCancel 时自动 save (原版 onCancel(dialog) { save() } 是因为 View EditText
 *   退出时保存进度, KMP 版用 onSubmit 显式提交, dismiss 即丢弃, 与"返回取消"语义对齐);
 * - 不实现 applyContent 按阅读进度滚动定位 (依赖 AppCompatEditText.layout.getLineForOffset,
 *   Compose OutlinedTextField 不暴露此 API, 滚动定位由用户手动操作)。
 *
 * @param chapterName 章节名 (用于标题 + 复制全部前缀)
 * @param content 章节正文 (用户可编辑)
 * @param onSubmit 用户点击保存且内容非空, 参数为编辑后的正文
 * @param onDismiss 用户取消 (返回按钮 / 点击对话框外部)
 * @param onReset 重置回调 (从源重新获取正文), null 时不显示重置菜单项
 * @param clipTextSink 剪贴板文本写入器 (替代 `context.sendToClip(text)`), null 时不显示复制全部菜单项
 */
@Composable
fun ContentEditDialog(
    chapterName: String,
    content: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
    clipTextSink: ((String) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    // 所有字符串一次性在 @Composable 主体内 rememberString, 避免 onClick 中误用 @Composable
    val saveDescText = rememberString("action_save")
    val resetText = rememberString("content_edit_reset")
    val copyAllText = rememberString("content_edit_copy_all")
    val copySuccessText = rememberString("content_edit_copy_success")
    val cancelText = rememberString("cancel")
    val okText = rememberString("ok")

    // 本地编辑 state: content 参数变化时 (如 reset 后调用方更新 content) 重新初始化
    var contentState by remember(content) { mutableStateOf(content) }
    // content 参数变化时同步 state (用于 onReset 后调用方更新 content 触发重组)
    LaunchedEffect(content) {
        contentState = content
    }

    /**
     * 保存, 与 app 端 save() 完全等价:
     * - 内容为空时直接 return (与 app 端 `contentView?.text?.toString() ?: return` 等价);
     * - 通过则回调 [onSubmit] + [onDismiss]。
     */
    fun save() {
        val text = contentState
        if (text.isEmpty()) return
        onSubmit(text)
        onDismiss()
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.background,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = chapterName,
                onBack = onDismiss,
                actions = {
                    IconButton(onClick = { save() }) {
                        Icon(
                            painter = rememberPainter("ic_save"),
                            contentDescription = saveDescText,
                            tint = ArcoBlue6,
                        )
                    }
                    OverflowMenu { dismissMenu ->
                        // 重置: 仅当调用方提供 onReset 时渲染 (依赖 ContentEditViewModel.reset, 调用方注入)
                        if (onReset != null) {
                            DropdownMenuItem(
                                text = { Text(resetText, color = colors.primaryText) },
                                onClick = {
                                    dismissMenu()
                                    onReset()
                                },
                            )
                        }
                        // 复制全部: 仅当调用方提供 clipTextSink 时渲染 (与 app 端 sendToClip 等价)
                        if (clipTextSink != null) {
                            DropdownMenuItem(
                                text = { Text(copyAllText, color = colors.primaryText) },
                                onClick = {
                                    dismissMenu()
                                    // 与 app 端 sendToClip("$title\n${contentView?.text}") 等价
                                    clipTextSink("$chapterName\n$contentState")
                                    Toasters.get().toast(copySuccessText)
                                },
                            )
                        }
                    }
                },
            )
            // 正文区: Box 包裹留作未来扩展 (原 app 端有 CircularProgressIndicator loading 指示器);
            // 不用 weight (Column 无固定高度时 weight 不生效), 让 TextField 按 maxLines=10 自然计算高度
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                // 多行 OutlinedTextField, maxLines = 10 (任务硬要求)
                // 不走 AppOutlinedTextField (其未暴露 maxLines 参数), 直接用 M3 OutlinedTextField +
                // 手动复制 AppOutlinedTextField 的颜色逻辑 (accent 聚焦 / secondaryText 未聚焦),
                // 保持与项目其它输入框视觉一致; maxLines=10 时 TextField 内部自动滚动, 无需外层 verticalScroll
                OutlinedTextField(
                    value = contentState,
                    onValueChange = { contentState = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        color = colors.primaryText,
                        fontSize = 16.sp,
                    ),
                    maxLines = 10,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.primaryText,
                        unfocusedTextColor = colors.primaryText,
                        cursorColor = colors.accent,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.secondaryText,
                    ),
                )
            }
            // 底部按钮栏 (与 SourceFilterEditDialog 风格对齐: 取消左 + 确定右)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(cancelText, color = colors.secondaryText)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { save() }) {
                    Text(okText, color = ArcoBlue6)
                }
            }
        }
    }
}
