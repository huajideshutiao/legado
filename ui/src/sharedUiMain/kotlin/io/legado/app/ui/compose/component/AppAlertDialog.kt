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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

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
            // 窗口尺寸统一收口: 宽 0.9 上限 800dp, 高自适应、封顶 0.8 锚点高
            modifier = Modifier.appDialogSize(widthFraction = widthFraction),
            content = content,
        )
    }
}

/**
 * alert 对话框正文（Surface + Column，不含窗口）。原生 [AppAlertDialog] 与命令式宿主
 * (app 端 alert DSL 的声明式宿主) 共用此正文，保证两条路径视觉一致。
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
    // 正文只负责填满宿主给的尺寸; 窗口尺寸由宿主 [AppDialog] 统一施加 (平台默认宽度已关闭,
    // 不依赖正文自己声明, 避免漏写时退化成"整窗宽 + 无高度上限")
    modifier: Modifier = Modifier.fillMaxWidth(widthFraction),
    content: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    AppDialogSurface(
        modifier = modifier,
    ) {
        // 壳体四边 8dp 是标题/正文/按钮共用的那一层留白; 标题与正文里的 8dp 是内容分块留白
        Column(Modifier.padding(DesignTokens.spacingDefault)) {
            title?.let {
                Text(
                    text = it,
                    color = colors.primaryText,
                    // 对齐原版 Material AlertDialog 标题 (TextAppearance.MaterialComponents.Headline6 = 20sp)
                    fontSize = 20.sp,
                    modifier = Modifier.padding(DesignTokens.spacingDefault)
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
                        .background(colors.fillet),
                    horizontalArrangement = Arrangement.End
                ) {
                    // 布局对齐 AlertDialog：neutral 靠左，cancel/ok 靠右
                    neutralButton?.let {
                        AlertTextButton(it, onDismissRequest)
                    }
                    Spacer(Modifier.weight(1f))
                    cancelButton?.let {
                        // 取消键次级文字色: 全仓对话框统一"取消灰、确定强调色"
                        AlertTextButton(it, onDismissRequest, colors.secondaryText)
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
                    // 左右不叠加额外边距: 内容槽已给 spacingDefault(8dp), 条目与标题同一边界规则
                    .padding(vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun AlertTextButton(
    button: AlertButton,
    onDismissRequest: () -> Unit,
    color: Color = AppTheme.colors.accent,
) {
    AppTextButton(text = button.text, enabled = button.enabled, color = color) {
        button.onClick?.invoke()
        if (button.dismissOnClick) onDismissRequest()
    }
}
