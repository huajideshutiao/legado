package io.legado.desktop.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.ui.book.read.config.FontItem
import io.legado.app.ui.book.read.config.fontFileRegex
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import io.legado.desktop.ui.component.FileDialogs
import javax.swing.SwingUtilities
import io.legado.app.ui.book.read.config.FontSelectDialog as SharedFontSelectDialog

/**
 * 默认字体扫描目录（Windows 系统字体目录；其他平台后续可扩展）。
 *
 * 对齐 app 端 `AppConfig.fontFolder` 默认行为：app 端首次打开走 `openFolder()` 让用户选，
 * 桌面端为简化直接扫描 `C:\Windows\Fonts`（fontFolder 字段未下沉到 shared/commonMain）。
 */
private const val DEFAULT_FONT_DIR = "C:\\Windows\\Fonts"

/**
 * 桌面端"字体选择"对话框：消费 sharedUiMain 的 [SharedFontSelectDialog]
 * (对话框壳/默认字体按钮/单选列表均为共享件，与 iOS 端同源)，本文件仅保留桌面平台适配：
 *
 * - 字体扫描：`java.io.File` 扫 `.ttf`/`.otf`（共享 [fontFileRegex]，Windows 系统字体目录）
 * - "其它目录"按钮（topBarTrailing 槽）：[FileDialogs.pickDirectory] (JFileChooser) 切换扫描目录
 *   （对齐 app 端 overflow 菜单 `R.string.other_folder` → `openFolder()`）
 * - 字体预览（fontPreview 槽）：`FontFamily(Font(file = ...))` 异步加载渲染 "永和 ABCabc 123"
 *   （JVM 桌面特殊行为，加载失败回退默认字体）
 * - 配置写回：[ReadBookConfigShared.textFont]，选定/恢复默认后触发 [onPostConfig]
 *
 * # 简化项（与 app 端差异）
 *
 * - 不持久化用户选择的扫描目录（每次打开重置为系统字体目录；
 *   app 端 `AppConfig.fontFolder` 字段未下沉到 shared/commonMain）
 * - 不实现"系统内置字体样式"选择器（app 端 `R.array.system_typefaces` +
 *   `AppConfig.systemTypefaces`，desktop 端 `systemTypefaces` 字段未下沉）
 *
 * @param readBookConfig 阅读配置（用于读写 [ReadBookConfigShared.textFont] 字段）
 * @param onDismiss 关闭回调
 * @param onPostConfig 配置变更通知（选择字体后触发，由 [ReadStyleDialog] 注入）
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
                scanDir.listFiles { f -> f.isFile && f.name.matches(fontFileRegex) }
                    ?.map { FontItem(it.absolutePath, it.name) }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()
            }.getOrElse {
                loadError = it.localizedMessage
                emptyList()
            }
        }
    }

    SharedFontSelectDialog(
        fontItems = fontItems,
        curFontPath = curFontPath,
        curFontName = curName,
        onSelectFont = { path ->
            readBookConfig.textFont = path
            onPostConfig()
        },
        onSelectDefault = {
            // 默认字体：写空串（对齐 app 端 onDefaultFontChange → selectFont("")）
            readBookConfig.textFont = ""
            onPostConfig()
        },
        onDismiss = onDismiss,
        widthFraction = 0.8f,
        topBarTrailing = {
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
        },
        extraTopContent = {
            // 错误提示（扫描失败时显示）
            loadError?.let { err ->
                Text(
                    text = rememberString("load_font_failed", err),
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        },
        fontPreview = { item -> FontPreviewText(item) },
    )
}

/**
 * 字体预览文本：`FontFamily(Font(file = ...))` 异步加载（[produceState] + [Dispatchers.IO]），
 * 用对应字体渲染 "永和 ABCabc 123"，加载失败回退 `null`（默认字体渲染）。
 */
@Composable
private fun FontPreviewText(item: FontItem) {
    val colors = AppTheme.colors
    val fontFamily by produceState<FontFamily?>(null, item.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { FontFamily(Font(file = File(item.path))) }.getOrNull()
        }
    }
    Text(
        text = "永和 ABCabc 123",
        color = colors.secondaryText,
        fontSize = 14.sp,
        fontFamily = fontFamily,
    )
}
