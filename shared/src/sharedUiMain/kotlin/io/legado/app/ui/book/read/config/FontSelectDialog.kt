package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.close
import legado.shared.generated.resources.default_font
import legado.shared.generated.resources.select_font
import org.jetbrains.compose.resources.stringResource

/** 字体文件正则（对齐 app 端 `FontSelectDialog.fontRegex`：.ttf / .otf，大小写不敏感）。 */
val fontFileRegex: Regex = Regex("(?i).*\\.[ot]tf")

/**
 * 字体项：绝对路径 + 文件名（显示用）。
 *
 * 下沉到 shared/sharedUiMain 供 iOS / 桌面端复用。
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
 * # 简化项（与 app 端差异，平台特殊行为经槽位注入，不入 sharedUiMain）
 *
 * - 字体扫描由调用方注入（[fontItems]）：app 端用 DocumentFile + externalFiles/font，
 *   iOS 端用 NSFileManager + Documents/font，桌面端用 java.io.File + 系统字体目录
 * - "其它目录"按钮走 [topBarTrailing] 槽（iOS 端 PHPicker 不支持选文件夹不注入；
 *   桌面端注入 JFileChooser 入口）
 * - 字体预览走 [fontPreview] 槽（FontFamily(Font(file=...)) 属 JVM 桌面特殊行为，桌面端注入）
 * - 不含"系统内置字体样式"选择器（app 端 R.array.system_typefaces + AppConfig.systemTypefaces 未下沉）
 *
 * @param fontItems 字体文件列表（由调用方扫描后注入）
 * @param curFontPath 当前字体路径（用于 RadioButton 选中状态判定）
 * @param curFontName 当前字体名（已 URL 解码 + 取文件名，用于选中状态比对）
 * @param onSelectFont 选定字体回调（传入字体文件绝对路径）
 * @param onSelectDefault 默认字体回调（传入空串，对齐 app 端 selectFont("")）
 * @param onDismiss 关闭回调
 * @param widthFraction 对话框宽度占比（透传 AppAlertDialog；桌面端传 0.8f 避免占满）
 * @param topBarTrailing 顶部按钮行尾部槽（桌面端注入"其它目录"按钮）
 * @param extraTopContent 顶部按钮行与列表之间的附加内容槽（桌面端注入扫描失败提示）
 * @param fontPreview 字体行预览槽（桌面端注入 FontFamily(Font(file)) 预览文本）
 */
@Composable
fun FontSelectDialog(
    fontItems: List<FontItem>,
    curFontPath: String,
    curFontName: String,
    onSelectFont: (String) -> Unit,
    onSelectDefault: () -> Unit,
    onDismiss: () -> Unit,
    widthFraction: Float = 1f,
    topBarTrailing: (@Composable () -> Unit)? = null,
    extraTopContent: (@Composable () -> Unit)? = null,
    fontPreview: (@Composable (FontItem) -> Unit)? = null,
) {
    val colors = AppTheme.colors

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.select_font),
        widthFraction = widthFraction,
        content = {
            // 顶部"默认字体"按钮（对齐 app 端 dialog_title_bar 的默认字体按钮）
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.default_font),
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
                topBarTrailing?.invoke()
            }

            extraTopContent?.invoke()

            // 字体列表限高 0.8 屏高 (原版 isFullHeight = true, 长列表效果等同全高)
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = AppDialogSizes.fullHeight()),
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
                        preview = fontPreview,
                    )
                }
            }
        },
        okButton = AlertButton(text = stringResource(Res.string.close)) { onDismiss() },
    )
}

/**
 * 字体列表行：[RadioButton] + 字体文件名 + 可选预览槽。
 *
 * 预览渲染经 [preview] 槽注入（FontFamily 属 JVM 桌面特殊行为，桌面端注入）。
 */
@Composable
private fun FontRow(
    item: FontItem,
    selected: Boolean,
    onClick: () -> Unit,
    preview: (@Composable (FontItem) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (preview != null) 4.dp else 8.dp),
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
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                text = item.name,
                color = colors.primaryText,
                fontSize = 16.sp,
            )
            preview?.invoke(item)
        }
    }
}
