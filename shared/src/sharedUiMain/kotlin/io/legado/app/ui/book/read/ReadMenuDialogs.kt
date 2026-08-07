package io.legado.app.ui.book.read

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.LocalDate
import io.legado.app.data.entities.localDateNow
import io.legado.app.data.entities.localDateOf
import io.legado.app.data.entities.toYearMonthDay
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppNumberField
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 阅读菜单选择器对话框集 (iOS/鸿蒙/desktop 共用, 对照 app 端 ReadMenu 的
 * activity.selector / activity.alert 弹窗):
 * - [SimulatedReadingDialog]: 模拟追读配置 (开关 + 起始日期 + 起始章节 + 每日章节)
 * - [ImageStyleDialog]: 图片样式 4 项选择器
 * - [DownloadDialog]: 离线缓存起止章节号
 * - [CharsetDialog]: 文本编码选择器
 *
 * 对话框只负责收集输入 + 改 [Book.config], 落库/重载由调用方 (ReaderRoute) 的
 * onApply 回调执行 (需要 viewModel/DAO 上下文)。
 */

/** 模拟追读配置对话框 (对照 app 端 ReadMenu.showSimulatedReading 的 customView)。 */
@Composable
fun SimulatedReadingDialog(
    book: Book,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val config = book.config
    var enabled by remember { mutableStateOf(config.readSimulating) }
    // 日期预填: 已配置用现有值, 否则今天 (yyyy-MM-dd)
    var dateText by remember {
        mutableStateOf(
            config.startDate?.let { date ->
                val (y, m, d) = date.toYearMonthDay()
                "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${
                    d.toString().padStart(2, '0')
                }"
            } ?: ""
        )
    }
    var startChapter by remember { mutableStateOf(book.getStartChapter().toString()) }
    var dailyChapters by remember { mutableStateOf(config.dailyChapters.toString()) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = "模拟追读",
        okButton = io.legado.app.ui.compose.component.AlertButton(
            text = "确认",
            onClick = {
                val date = parseDateOrToday(dateText)
                config.readSimulating = enabled
                config.startDate = date
                config.startChapter = startChapter.toIntOrNull() ?: 0
                config.dailyChapters = dailyChapters.toIntOrNull() ?: book.totalChapterNum
                onApply()
            },
        ),
        cancelButton = io.legado.app.ui.compose.component.AlertButton(text = "取消"),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "启用",
                    color = AppTheme.colors.primaryText,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                AppSwitch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Text("起始日期 (yyyy-MM-dd)", color = AppTheme.colors.secondaryText, fontSize = 13.sp)
            AppTextField(
                value = dateText,
                onValueChange = { dateText = it },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "起始章节",
                    color = AppTheme.colors.primaryText,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                AppNumberField(
                    value = startChapter,
                    onValueChange = { startChapter = it },
                    modifier = Modifier.width(96.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "每日章节",
                    color = AppTheme.colors.primaryText,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                AppNumberField(
                    value = dailyChapters,
                    onValueChange = { dailyChapters = it },
                    modifier = Modifier.width(96.dp),
                )
            }
        }
    }
}

/** 图片样式选择器 (对照 app 端 menu_image_style 的 4 项 selector)。 */
@Composable
fun ImageStyleDialog(
    book: Book,
    onApply: (imageStyle: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val styles = arrayListOf(
        Book.imgStyleDefault, Book.imgStyleFull, Book.imgStyleText, Book.imgStyleSingle
    )
    val labels = arrayListOf("默认", "充满", "文字", "单页")
    val current = styles.indexOf(book.config.imageStyle).coerceAtLeast(0)
    AppSelectorDialog(
        onDismissRequest = onDismiss,
        title = "图片样式",
        items = labels,
        onItemSelected = { index ->
            onApply(styles[index])
        },
    )
}

/** 离线缓存对话框 (对照 app 端 showDownloadDialog: 起始/结束章节号, 章节号从 1 起)。 */
@Composable
fun DownloadDialog(
    book: Book,
    onApply: (start: Int, end: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var startText by remember { mutableStateOf((book.durChapterIndex + 1).toString()) }
    var endText by remember { mutableStateOf(book.totalChapterNum.toString()) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = "离线缓存",
        okButton = io.legado.app.ui.compose.component.AlertButton(
            text = "确认",
            onClick = {
                val start = startText.toIntOrNull() ?: 0
                val end = endText.toIntOrNull() ?: book.totalChapterNum
                onApply(start, end)
            },
        ),
        cancelButton = io.legado.app.ui.compose.component.AlertButton(text = "取消"),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "起始章节",
                    color = AppTheme.colors.primaryText,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                AppNumberField(
                    value = startText,
                    onValueChange = { startText = it },
                    modifier = Modifier.width(96.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "结束章节",
                    color = AppTheme.colors.primaryText,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                AppNumberField(
                    value = endText,
                    onValueChange = { endText = it },
                    modifier = Modifier.width(96.dp),
                )
            }
        }
    }
}

/** 文本编码选择器 (对照 app 端 showCharsetConfig 的 charset 列表)。 */
@Composable
fun CharsetDialog(
    book: Book,
    onApply: (charset: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = AppConst.charsets.indexOf(book.charset).coerceAtLeast(0)
    AppSelectorDialog(
        onDismissRequest = onDismiss,
        title = "设置编码",
        items = AppConst.charsets,
        onItemSelected = { index ->
            onApply(AppConst.charsets[index])
        },
    )
}

/** 解析 yyyy-MM-dd, 非法/空回退今天。 */
private fun parseDateOrToday(text: String): LocalDate {
    val parts = text.trim().split("-")
    if (parts.size == 3) {
        val y = parts[0].toIntOrNull()
        val m = parts[1].toIntOrNull()
        val d = parts[2].toIntOrNull()
        if (y != null && m != null && d != null && m in 1..12 && d in 1..31) {
            return localDateOf(y, m, d)
        }
    }
    return localDateNow()
}
