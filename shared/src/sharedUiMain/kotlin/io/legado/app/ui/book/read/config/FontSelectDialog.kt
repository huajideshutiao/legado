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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.close
import legado.shared.generated.resources.default_font
import legado.shared.generated.resources.select_font
import legado.shared.generated.resources.system_typeface
import legado.shared.generated.resources.system_typefaces
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** 字体文件正则（对齐 app 端 `FontSelectDialog.fontRegex`：.ttf / .otf，大小写不敏感）。 */
val fontFileRegex: Regex = Regex("(?i).*\\.[ot]tf")

// FontItem 数据类已下沉 commonMain (同包名 FontItem.kt), 供 PlatformCapabilities 引用。

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
 *   → 已恢复：点"默认字体"弹 [AppSelectorDialog] 枚举 [Res.array.system_typefaces]，选后写
 *     [PreferKey.systemTypefaces] + 触发 [onSelectDefault]（对齐 app 端
 *     `AppConfig.systemTypefaces = i; onDefaultFontChange()`）
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
    // 系统内置字体样式列表 (对照 app 端 R.array.system_typefaces)
    val systemTypefaces = stringArrayResource(Res.array.system_typefaces)
    // 系统内置字体样式选择器开关 (对照 app 端 menu_default → alert(system_typefaces))
    var showTypefaceDialog by remember { mutableStateOf(false) }
    // 组合期捕获，供事件回调内写 systemTypefaces（CompositionLocal 只能在组合期读取）
    val prefs = LocalPreferenceStoreProvider.current

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
                            if (systemTypefaces.isEmpty()) {
                                // 无系统字体样式列表的平台: 保持原行为直接选默认字体
                                // 默认字体：触发回调 + dismiss（对齐 app 端 onDefaultFontChange）
                                if (curFontPath.isNotEmpty()) {
                                    onSelectDefault()
                                }
                                onDismiss()
                            } else {
                                // 弹系统内置字体样式选择器（对齐 app 端 menu_default）
                                showTypefaceDialog = true
                            }
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

    // 系统内置字体样式选择器（对齐 app 端 alert(system_typefaces)：选后存 AppConfig.systemTypefaces
    // + onDefaultFontChange → selectFont("") + dismissAllowingStateLoss）
    if (showTypefaceDialog) {
        AppSelectorDialog(
            onDismissRequest = { showTypefaceDialog = false },
            title = stringResource(Res.string.system_typeface),
            items = systemTypefaces,
            onItemSelected = { i ->
                prefs.putInt(PreferKey.systemTypefaces, i)
                onSelectDefault()
                onDismiss()
            },
        )
    }
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
