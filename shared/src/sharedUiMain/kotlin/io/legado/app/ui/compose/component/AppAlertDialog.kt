package io.legado.app.ui.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * alert DSL 的按钮槽：文案 + 点击回调。dismissOnClick=false 时点击不关闭对话框
 * （复刻 getButton().setOnClickListener 手动控制关闭的校验保留语义）。
 */
data class AlertButton(
    val text: String,
    val dismissOnClick: Boolean = true,
    // false 时按钮置灰不可点（复刻 getButton().isEnabled = false，如输入未填完时的确定键）
    val enabled: Boolean = true,
    val onClick: (() -> Unit)? = null,
)

/**
 * Compose 版 alert，槽位对齐 lib/dialogs alert DSL：
 * title/message/okButton/cancelButton/neutralButton/content(customView 槽)。
 * 颜色全部走 AppTheme.colors。命令式桥接层复用 [AppAlertDialogContent]。
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    message: String? = null,
    okButton: AlertButton? = null,
    cancelButton: AlertButton? = null,
    neutralButton: AlertButton? = null,
    properties: DialogProperties = AppDialogSizes.properties(),
    // 对话框宽度占窗口宽度的比例 (默认 1f 占满)。
    // 桌面端窗口较宽, 备份相关对话框传 0.8f 避免占满难看 (对齐用户反馈 "对话框宽度应占 0.8 窗口宽度")。
    // app 端手机屏幕窄, 保持 1f 默认值 (占满屏幕宽度符合移动端习惯)。
    widthFraction: Float = 1f,
    content: (@Composable () -> Unit)? = null,
) {
    AppDialog(onDismissRequest = onDismissRequest, properties = properties) {
        AppAlertDialogContent(
            onDismissRequest = onDismissRequest,
            title = title,
            message = message,
            okButton = okButton,
            cancelButton = cancelButton,
            neutralButton = neutralButton,
            // 窗口尺寸统一收口: 宽 0.9 上限 800dp, 高自适应但不超 0.8 屏高 (对齐原版 BaseComposeDialogFragment)
            modifier = Modifier.appDialogSize(widthFraction = widthFraction),
            content = content,
        )
    }
}

/**
 * alert 对话框正文（Surface + Column，不含窗口）。原生 [AppAlertDialog] 与命令式宿主
 * (base/ComposeDialog) 共用此正文，保证两条路径视觉一致。
 */
@Composable
fun AppAlertDialogContent(
    onDismissRequest: () -> Unit,
    title: String? = null,
    message: String? = null,
    okButton: AlertButton? = null,
    cancelButton: AlertButton? = null,
    neutralButton: AlertButton? = null,
    // 对话框宽度占窗口宽度的比例 (默认 1f 占满, 桌面端备份相关对话框传 0.8f)
    widthFraction: Float = 1f,
    // 窗口尺寸由调用方决定: [AppAlertDialog] 传 appDialogSize, 命令式宿主沿用窗口自身尺寸
    modifier: Modifier = Modifier.fillMaxWidth(widthFraction),
    content: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Surface(
        modifier = modifier,
        shape = DesignTokens.shapeDefault,
        color = colors.fillet,
    ) {
        Column(Modifier.padding(vertical = 16.dp)) {
            title?.let {
                Text(
                    text = it,
                    color = colors.primaryText,
                    // 对齐原版 Material AlertDialog 标题 (TextAppearance.MaterialComponents.Headline6 = 20sp)
                    fontSize = 20.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }
            // weight+scroll: 长消息(如错误堆栈)收进可滚区, 按钮行恒可见——对齐 AlertDialog 消息区 ScrollView+按钮钉底
            message?.let {
                Text(
                    text = it,
                    color = colors.secondaryText,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
            content?.let {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .clipToBounds()
                ) { it() }
            }
            if (okButton != null || cancelButton != null || neutralButton != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.fillet)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    // 布局对齐 AlertDialog：neutral 靠左，cancel/ok 靠右
                    neutralButton?.let {
                        AlertTextButton(it, onDismissRequest)
                    }
                    Spacer(Modifier.weight(1f))
                    cancelButton?.let {
                        AlertTextButton(it, onDismissRequest)
                    }
                    okButton?.let {
                        AlertTextButton(it, onDismissRequest)
                    }
                }
            }
        }
    }
}

/** items 语义(alert DSL items 槽)：列表选择型 alert */
@Composable
fun AppSelectorDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    items: List<String>,
    onItemSelected: (index: Int) -> Unit,
    // 对话框宽度占窗口宽度的比例 (默认 1f 占满, 桌面端备份相关对话框传 0.8f)
    widthFraction: Float = 1f,
    // 点击对话框外部是否关闭, 长按弹出场景需传 false 避免释放事件误触
    dismissOnClickOutside: Boolean = true,
) {
    AppAlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        widthFraction = widthFraction,
        properties = AppDialogSizes.properties(dismissOnClickOutside = dismissOnClickOutside),
    ) {
        AppSelectorList(items = items) {
            onItemSelected(it)
            onDismissRequest()
        }
    }
}

/** 列表选择正文（供 AppSelectorDialog 与命令式 selector 复用） */
@Composable
fun AppSelectorList(items: List<String>, onItemClick: (index: Int) -> Unit) {
    val colors = AppTheme.colors
    LazyColumn {
        itemsIndexed(items) { index, item ->
            Text(
                text = item,
                color = colors.primaryText,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onItemClick(index) }
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun AlertTextButton(button: AlertButton, onDismissRequest: () -> Unit) {
    AppTextButton(text = button.text, enabled = button.enabled) {
        button.onClick?.invoke()
        if (button.dismissOnClick) onDismissRequest()
    }
}

// ===== @Preview 合并自 androidMain 的 compose/component/AppAlertDialogPreviews.kt =====

/**
 * [AppAlertDialog.kt] 中各 Composable 的 @Preview。
 * - [AppAlertDialog]: alert 对话框 (含 Dialog 窗口)
 * - [AppAlertDialogContent]: alert 正文 (不含窗口, 适合预览样式)
 * - [AppSelectorDialog]: 列表选择型 alert
 * - [AppSelectorList]: 列表正文
 *
 * 注: 含 Dialog/Popup 的 Composable 在 IDE Preview 中可能渲染受限,
 * 故优先 Preview 不含窗口的 [AppAlertDialogContent] / [AppSelectorList]。
 */

@Preview
@Composable
fun AppAlertDialogContentPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppAlertDialogContent(
            onDismissRequest = {},
            title = "对话框标题",
            message = "这是对话框的正文内容, 用于提示用户确认操作。",
            okButton = AlertButton(text = "确定"),
            cancelButton = AlertButton(text = "取消"),
            widthFraction = 0.9f,
        )
    }
}

@Preview
@Composable
fun AppAlertDialogContentDarkPreview() = LegadoThemePreview(dark = true) {
    Box(Modifier.padding(16.dp)) {
        AppAlertDialogContent(
            onDismissRequest = {},
            title = "深色对话框",
            message = "深色主题下的对话框内容。",
            okButton = AlertButton(text = "确定"),
            cancelButton = AlertButton(text = "取消"),
        )
    }
}

@Preview
@Composable
fun AppAlertDialogContentWithCustomViewPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppAlertDialogContent(
            onDismissRequest = {},
            title = "带自定义视图",
            okButton = AlertButton(text = "确定"),
            cancelButton = AlertButton(text = "取消"),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("自定义内容行 1")
                Spacer(Modifier.height(8.dp))
                Text("自定义内容行 2")
            }
        }
    }
}

@Preview
@Composable
fun AppAlertDialogPreview() = LegadoThemePreview {
    AppAlertDialog(
        onDismissRequest = {},
        title = "对话框标题",
        message = "完整对话框 (含 Dialog 窗口)。",
        okButton = AlertButton(text = "确定"),
        cancelButton = AlertButton(text = "取消"),
    )
}

@Preview
@Composable
fun AppSelectorListPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppSelectorList(
            items = listOf("选项一", "选项二", "选项三", "选项四"),
            onItemClick = {},
        )
    }
}

@Preview
@Composable
fun AppSelectorDialogPreview() = LegadoThemePreview {
    AppSelectorDialog(
        onDismissRequest = {},
        title = "选择一项",
        items = listOf("选项一", "选项二", "选项三"),
        onItemSelected = {},
    )
}
