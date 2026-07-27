package io.legado.desktop.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import io.legado.desktop.ui.component.FileDialogs
import javax.swing.SwingUtilities

/**
 * 字体项：绝对路径 + 文件名（显示用）。
 */
private data class FontItem(val path: String, val name: String)

/** 字体文件正则（对齐 app 端 `FontSelectDialog.fontRegex`：.ttf / .otf，大小写不敏感）。 */
private val fontRegex = Regex("(?i).*\\.[ot]tf")

/**
 * 默认字体扫描目录（Windows 系统字体目录；其他平台后续可扩展）。
 *
 * 对齐 app 端 `AppConfig.fontFolder` 默认行为：app 端首次打开走 `openFolder()` 让用户选，
 * 桌面端为简化直接扫描 `C:\Windows\Fonts`（fontFolder 字段未下沉到 shared/commonMain）。
 */
private const val DEFAULT_FONT_DIR = "C:\\Windows\\Fonts"

/**
 * 桌面端"字体选择"对话框（对照 app 端 `io.legado.app.ui.font.FontSelectDialog`）。
 *
 * # 职责
 *
 * - 扫描系统字体目录（Windows: `C:\Windows\Fonts`）列出 `.ttf` / `.otf` 字体文件
 * - 单选（[RadioButton]）+ 字体文件名 + 字体预览（用对应字体渲染 "永和 ABCabc 123"）
 * - 点击 [RadioButton] 即选定 + dismiss（对齐 app 端 `onFontSelect` 行为）
 * - "默认字体"按钮：写入空串到 [ReadBookConfigShared.textFont] + dismiss
 *   （对齐 app 端 `onDefaultFontChange` → `callBack.selectFont("")`）
 * - "其它目录"按钮：弹 [JFileChooser] 选目录，切换扫描目录
 *   （对齐 app 端 overflow 菜单 `R.string.other_folder` → `openFolder()`）
 *
 * # 简化项（与 app 端差异）
 *
 * - 系统字体目录写死 `C:\Windows\Fonts`（其他平台后续可扩展）
 * - 不持久化用户选择的扫描目录（每次打开重置为系统字体目录；
 *   app 端 `AppConfig.fontFolder` 字段未下沉到 shared/commonMain）
 * - 字体预览用 `FontFamily(Font(file = ...))` 异步加载，加载失败回退默认字体
 * - 不实现"系统内置字体样式"选择器（app 端 `R.array.system_typefaces` +
 *   `AppConfig.systemTypefaces`，desktop 端 `systemTypefaces` 字段未下沉）
 *
 * @param readBookConfig 阅读配置（用于读写 [ReadBookConfigShared.textFont] 字段）
 * @param onDismiss 关闭回调
 * @param onPostConfig 配置变更通知（选择字体后触发，由 [ReadStyleDialog] 注入；
 *   desktop 端 `ReadBookEvents.postConfig` 未下沉，实际为 no-op）
 */
@Composable
fun FontSelectDialog(
    readBookConfig: ReadBookConfigShared,
    onDismiss: () -> Unit,
    onPostConfig: () -> Unit,
) {
    val colors = AppTheme.colors
    // 当前字体路径（用于 RadioButton 选中状态判定）
    val curFontPath = readBookConfig.textFont
    // 当前字体名（URL 解码 + 取文件名，对齐 app 端 `curName` 逻辑）
    val curName = remember(curFontPath) {
        runCatching { java.net.URLDecoder.decode(curFontPath, "utf-8") }
            .getOrDefault(curFontPath)
            .substringAfterLast(File.separator)
    }

    // 当前扫描目录（默认系统字体目录；用户通过"其它目录"切换）
    var scanDir by remember { mutableStateOf(File(DEFAULT_FONT_DIR)) }
    var fontItems by remember { mutableStateOf<List<FontItem>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // 异步扫描字体目录（避免阻塞 UI 线程）
    LaunchedEffect(scanDir) {
        loadError = null
        fontItems = withContext(Dispatchers.IO) {
            runCatching {
                scanDir.listFiles { f -> f.isFile && f.name.matches(fontRegex) }
                    ?.map { FontItem(it.absolutePath, it.name) }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()
            }.getOrElse {
                loadError = it.localizedMessage
                emptyList()
            }
        }
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("select_font"),
        widthFraction = 0.8f,
        content = {
            // 顶部按钮行：对齐 app 端 dialog_title_bar 的 "默认字体" 按钮 + overflow "其它目录"
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
                            // 默认字体：写空串 + dismiss（对齐 app 端 onDefaultFontChange）
                            if (curFontPath.isNotEmpty()) {
                                readBookConfig.textFont = ""
                                onPostConfig()
                            }
                            onDismiss()
                        }
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = rememberString("other_folder"),
                    color = colors.primaryText,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable {
                            // 弹 JFileChooser 选目录（须在 EDT 调用，与 WebDavConfigScreen 一致）
                            SwingUtilities.invokeLater {
                                val selected = FileDialogs.pickDirectory()
                                if (selected != null && selected.isDirectory) {
                                    scanDir = selected
                                }
                            }
                        }
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                )
            }

            // 错误提示（扫描失败时显示）
            loadError?.let { err ->
                Text(
                    text = rememberString("load_font_failed", err),
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            // 字体列表（LazyColumn 限高，避免列表过长撑爆对话框）
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 400.dp),
            ) {
                items(fontItems, key = { it.path }) { item ->
                    FontRow(
                        item = item,
                        selected = item.name == curName,
                        onClick = {
                            // 选定 + dismiss（对齐 app 端 onFontSelect）
                            if (item.path != curFontPath) {
                                readBookConfig.textFont = item.path
                                onPostConfig()
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
 * 字体列表行：[RadioButton] + 字体文件名 + 字体预览。
 *
 * 字体预览用 `FontFamily(Font(file = ...))` 异步加载（[produceState] + [Dispatchers.IO]），
 * 加载失败回退 `null`（[Text] 用默认字体渲染）。
 *
 * @param item 字体项
 * @param selected 是否选中（决定 RadioButton 状态）
 * @param onClick 点击回调（RadioButton / 行点击均触发）
 */
@Composable
private fun FontRow(item: FontItem, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    // 异步加载字体用于预览（失败回退 null，Text 用默认字体）
    val fontFamily by produceState<FontFamily?>(null, item.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { FontFamily(Font(file = File(item.path))) }.getOrNull()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
            // 字体文件名
            Text(
                text = item.name,
                color = colors.primaryText,
                fontSize = 16.sp,
            )
            // 字体预览（用对应字体渲染"永和 ABCabc 123"）
            Text(
                text = "永和 ABCabc 123",
                color = colors.secondaryText,
                fontSize = 14.sp,
                fontFamily = fontFamily,
            )
        }
    }
}
