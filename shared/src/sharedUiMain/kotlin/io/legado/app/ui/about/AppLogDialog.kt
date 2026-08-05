package io.legado.app.ui.about

// I18N KEYS (已注册于 ResourceProvider.jvm.kt):
//   "log" to "日志",
//   "clear" to "清空"

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.linkifyText
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.format
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.clear
import legado.shared.generated.resources.log
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 应用日志对话框内容 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.about.AppLogDialog` 的 Content，去掉对 BaseComposeDialogFragment /
 * showDialogFragment / LogUtils / autoLinkText 的依赖，改为纯 @Composable:
 * - 标题栏: 返回 + "日志" + 清空按钮
 * - 列表: LazyColumn，行=时间+消息，带异常的行可点击查看堆栈
 * - 实时刷新: 订阅 [AppLog.logsFlow]，日志变化时立即重组
 * - URL 自动链接: 消息中的 URL 可点击跳转 (对齐 origin autoLinkMask)
 *
 * @param onDismiss 用户取消 (返回按钮)
 */
@Composable
fun AppLogDialogContent(
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val logs by AppLog.logsFlow.collectAsState()

    // 选中的堆栈日志 (非 null 时弹出堆栈对话框)
    var stackTraceItem by remember { mutableStateOf<Triple<Long, String, Throwable?>?>(null) }

    Column(Modifier.fillMaxWidth()) {
        DialogTitleBar(
            title = stringResource(Res.string.log),
            onBack = onDismiss,
            actions = {
                AppTextButton(text = stringResource(Res.string.clear), onClick = AppLog::clear)
            },
        )
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            itemsIndexed(logs) { _, item ->
                LogItem(item) { stackTraceItem = item }
            }
        }
    }

    // 堆栈信息弹窗 (点击带异常的日志行触发)
    stackTraceItem?.let { item ->
        TextDialog(
            title = stringResource(Res.string.log),
            content = item.third?.stackTraceToString() ?: "",
            onConfirm = { stackTraceItem = null },
            onDismiss = { stackTraceItem = null },
        )
    }
}

/**
 * 单条日志行: 时间 + 消息 (URL 可点击), 带 throwable 时可点击查看堆栈。
 */
@Composable
private fun LogItem(
    item: Triple<Long, String, Throwable?>,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val (time, message, throwable) = item
    // 读取平台时区偏移, 将 UTC epoch 转为本地时间
    val tzOffset = remember { AppLog.timeZoneOffsetMillis() }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = throwable != null, onClick = onClick)
            .padding(8.dp),
    ) {
        Text(
            text = formatLogTime(time, tzOffset),
            color = colors.primaryText,
        )
        SelectionContainer {
            Text(
                text = remember(message, colors.accent) { linkifyText(message, colors.accent) },
                color = colors.primaryText,
            )
        }
    }
}


/**
 * 应用日志对话框 (带 Dialog 窗口, 供桌面 / iOS 端直接使用)。
 *
 * app 端使用 [AppLogDialogContent] 嵌入自身 DialogFragment，不调用本函数 (避免双层窗口)。
 *
 * @param onDismiss 用户取消 (返回按钮 / 点击对话框外部)
 */
@Composable
fun AppLogDialog(
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        // 圆角/底色对齐 BaseComposeDialogFragment.filletBackground + alert DSL AppAlertDialogContent
        Surface(
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(fullHeight = true),
        ) {
            AppLogDialogContent(onDismiss)
        }
    }
}

/**
 * 将 epoch 毫秒格式化为 "yy-MM-dd HH:mm:ss.SSS" (本地时间)。
 *
 * 使用 Howard Hinnant 的 civil_from_days 算法 + [tzOffsetMs] 时区偏移,
 * 纯 Kotlin 实现, 不依赖 java.util.Date / SimpleDateFormat (KMP 安全)。
 *
 * @param epochMillis UTC epoch 毫秒
 * @param tzOffsetMs 时区偏移量 (毫秒), 由 [AppLog.timeZoneOffsetMillis] 提供
 */
private fun formatLogTime(epochMillis: Long, tzOffsetMs: Long): String {
    // 将 UTC epoch 转为本地 epoch
    val localMillis = epochMillis + tzOffsetMs
    val totalSeconds = localMillis / 1000
    val millis = (localMillis % 1000).toInt().let { if (it < 0) it + 1000 else it }
    val secs = (totalSeconds % 60).toInt().let { if (it < 0) it + 60 else it }
    val totalMinutes = totalSeconds / 60
    val mins = (totalMinutes % 60).toInt().let { if (it < 0) it + 60 else it }
    val totalHours = totalMinutes / 60
    val hrs = (totalHours % 24).toInt().let { if (it < 0) it + 24 else it }
    val totalDays = totalHours / 24

    // Howard Hinnant civil_from_days (local days since 1970-01-01 → y/m/d)
    val z = totalDays.toInt() + 719468
    val era = if (z >= 0) z / 146097 else (z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    val yy = year % 100

    return "%02d-%02d %02d:%02d:%02d.%03d".format(yy, m, d, hrs, mins, secs, millis)
}

// ===== @Preview 合并自 androidMain 的 about/AppLogDialogPreviews.kt =====

/**
 * [AppLogDialogContent] 的 @Preview。
 *
 * 组件直接读 AppLog.logs 单例 (纯内存环形列表), Preview 前用 putNotSave 播种几条;
 * host 未注册时副作用 no-op, 不落盘不弹 toast。
 */

@Preview
@Composable
fun AppLogDialogContentPreview() = LegadoThemePreview {
    remember {
        AppLog.putNotSave("书源[示例书源]校验通过")
        AppLog.putNotSave("换源: 三体 找到 12 个可用源")
        AppLog.putNotSave("章节解析失败", IllegalStateException("preview: 规则 content 返回空"))
    }
    AppLogDialogContent(onDismiss = {})
}

@Preview
@Composable
fun AppLogDialogContentDarkPreview() = LegadoThemePreview(dark = true) {
    remember {
        AppLog.putNotSave("WebDav 备份完成")
    }
    AppLogDialogContent(onDismiss = {})
}
