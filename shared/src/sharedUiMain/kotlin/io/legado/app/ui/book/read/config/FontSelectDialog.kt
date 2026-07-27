package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/** 字体文件正则（对齐 app 端 `FontSelectDialog.fontRegex`：.ttf / .otf，大小写不敏感）。 */
val fontFileRegex: Regex = Regex("(?i).*\\.[ot]tf")

/**
 * 字体项：绝对路径 + 文件名（显示用）。
 *
 * 下沉到 shared/sharedUiMain 供 iOS / 桌面端复用（桌面端当前保留私有 FontItem，
 * 后续迁移可统一引用本类）。
 */
data class FontItem(val path: String, val name: String)

/**
 * 跨平台"字体选择"对话框（对照 app 端 `io.legado.app.ui.font.FontSelectDialog`）。
 *
 * # 职责
 *
 * - 单选（[RadioButton]）+ 字体文件名，点击即选定 + dismiss（对齐 app 端 `onFontSelect`）
 * - "默认字体"按钮：触发 [onSelectDefault] + dismiss
 *   （对齐 app 端 `onDefaultFontChange` → `callBack.selectFont("")`）
 *
 * # 简化项（与 app 端差异，平台特殊行为不入 sharedUiMain）
 *
 * - 字体扫描由调用方注入（[fontItems]）：app 端用 DocumentFile + externalFiles/font，
 *   iOS 端用 NSFileManager + Documents/font，桌面端用 java.io.File + 系统字体目录
 * - 不含"其它目录"按钮（iOS 端 PHPicker 不支持选文件夹；桌面端 JFileChooser 属平台特殊行为）
 * - 不含字体预览（FontFamily(Font(file=...)) 属 JVM 桌面特殊行为）
 * - 不含"系统内置字体样式"选择器（app 端 R.array.system_typefaces + AppConfig.systemTypefaces 未下沉）
 *
 * @param fontItems 字体文件列表（由调用方扫描后注入）
 * @param curFontPath 当前字体路径（用于 RadioButton 选中状态判定）
 * @param curFontName 当前字体名（已 URL 解码 + 取文件名，用于选中状态比对）
 * @param onSelectFont 选定字体回调（传入字体文件绝对路径）
 * @param onSelectDefault 默认字体回调（传入空串，对齐 app 端 selectFont("")）
 * @param onDismiss 关闭回调
 */
@Composable
fun FontSelectDialog(
    fontItems: List<FontItem>,
    curFontPath: String,
    curFontName: String,
    onSelectFont: (String) -> Unit,
    onSelectDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("select_font"),
        content = {
            // 顶部"默认字体"按钮（对齐 app 端 dialog_title_bar 的默认字体按钮）
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rememberString("default_font"),
                    color = colors.primaryText,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable {
                            // 默认字体：触发回调 + dismiss（对齐 app 端 onDefaultFontChange）
                            if (curFontPath.isNotEmpty()) {
                                onSelectDefault()
                            }
                            onDismiss()
                        }
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                )
                Spacer(Modifier.weight(1f))
            }

            // 字体列表（LazyColumn 限高，避免列表过长撑爆对话框）
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 400.dp),
            ) {
                items(fontItems, key = { it.path }) { item ->
                    FontRow(
                        item = item,
                        selected = item.name == curFontName,
                        onClick = {
                            // 选定 + dismiss（对齐 app 端 onFontSelect）
                            if (item.path != curFontPath) {
                                onSelectFont(item.path)
                            }
                            onDismiss()
                        },
                    )
                }
            }
        },
        okButton = AlertButton(text = rememberString("close")) { onDismiss() },
    )
}

/**
 * 字体列表行：[RadioButton] + 字体文件名。
 *
 * 不含字体预览（FontFamily 属 JVM 桌面特殊行为，各端按需在调用方扩展）。
 */
@Composable
private fun FontRow(item: FontItem, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.accent,
                unselectedColor = colors.secondaryText,
            ),
        )
        Text(
            text = item.name,
            color = colors.primaryText,
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
