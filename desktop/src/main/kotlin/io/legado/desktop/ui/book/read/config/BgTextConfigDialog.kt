package io.legado.desktop.ui.book.read.config

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.config.BgImageItem
import io.legado.app.ui.book.read.config.BgTextConfigActions
import io.legado.app.ui.book.read.config.BgTextConfigController

import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.ColorUtils
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.SwingUtilities

/**
 * 桌面端"背景文字配置"对话框入口（包装 shared/sharedUiMain 的 [io.legado.app.ui.book.read.config.BgTextConfigScreen]）。
 *
 * # 职责
 *
 * - 用 [AppAlertDialog] 包裹 [io.legado.app.ui.book.read.config.BgTextConfigScreen]，提供标题"背景与文字" + 关闭按钮
 * - 装配桌面版 [BgTextConfigController]（桥接到 [ReadBookConfigShared.durConfig] 各字段）
 * - 装配桌面版 [BgTextConfigActions]（桥接到 AWT FileDialog 选图 / 配置变更通知）
 * - 装配 [bgImageList] / [bgImagePreviewSlot]：桌面端无 RemoteAssetsUtils，无 assets 预设背景图，
 *   bgImageList 返回空列表；bgImagePreviewSlot 用 Box+Icon 渲染占位
 *
 * # 简化项（依赖未下沉的功能用 TODO 注释 + no-op）
 *
 * - onImportConfig / onExportConfig: app 端用 SAF 选 zip + BgTextConfigViewModel 解压/打包，
 *   桌面端 zip 解压逻辑未下沉，暂 no-op（TODO）
 * - onImportNetConfig: app 端用 RemoteAssetsUtils 下载配置，桌面端未下沉，暂 no-op（TODO）
 * - onSelectBgImage: 桌面端用 AWT FileDialog 选图片文件，写入 [ReadStyleConfig.bgType]=2 +
 *   [ReadStyleConfig.bgStr]=文件路径
 * - onSelectBgPreset: 桌面端无 assets 预设背景图，列表为空，此回调不会被触发（no-op）
 *
 * @param readBookConfig 阅读配置（由 ReaderScreen 注入，桥接到 durConfig / bgAlpha / underline）
 * @param isImageBook 是否图片书籍（控制是否显示下划线开关，由 ReaderScreen 注入）
 * @param onDismiss 关闭回调
 */
@Composable
fun BgTextConfigDialog(
    readBookConfig: ReadBookConfigShared,
    isImageBook: Boolean,
    onDismiss: () -> Unit,
) {
    val controller = remember(readBookConfig) { DesktopBgTextConfigController(readBookConfig) }
    val actions = remember { DesktopBgTextConfigActions(readBookConfig) }
    val bgImageList: List<BgImageItem> = remember { emptyList() }
    // 对齐 app 端 BgTextConfigDialog.onDismiss：关闭时落盘 configList / shareConfig
    val dismissAndSave = {
        readBookConfig.save()
        onDismiss()
    }

    AppAlertDialog(
        onDismissRequest = dismissAndSave,
        title = rememberString("text_bg_style"),
        widthFraction = 0.8f,
        content = {
            io.legado.app.ui.book.read.config.BgTextConfigScreen(
                controller = controller,
                actions = actions,
                isImageBook = isImageBook,
                bgImageList = bgImageList,
                bgImagePreviewSlot = { item, onClick ->
                    DesktopBgImagePreview(item, onClick)
                },
            )
        },
        okButton = AlertButton(text = rememberString("close")) { dismissAndSave() },
    )
}

/**
 * 桌面版背景图预览项：Box + 占位图标 + 标签。
 *
 * 桌面端无 RemoteAssetsUtils 解码 assets 图片为 Bitmap，用 Box + Icon 占位渲染。
 * 尺寸严格对齐 app 端 BgItem：66x88dp。
 *
 * @param item 背景图项（fileName 为空时为"选择图片"按钮）
 * @param onClick 点击回调
 */
@Composable
private fun DesktopBgImagePreview(item: BgImageItem, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .size(66.dp, 88.dp)
            .clip(DesignTokens.shapeSm)
            .border(DesignTokens.strokeThin, colors.secondaryText, DesignTokens.shapeSm)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(58.dp, 58.dp),
            contentAlignment = Alignment.Center,
        ) {
            // fileName 为空表示"选择图片"按钮（对齐 app 端 BgItem(icon = ic_image)）
            val iconKey = if (item.fileName.isEmpty()) "ic_image" else "ic_image"
            Icon(
                painter = rememberPainter(iconKey),
                contentDescription = item.label,
                tint = colors.secondaryText,
            )
        }
        Text(
            text = item.label,
            color = colors.secondaryText,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

/**
 * 桌面版 [BgTextConfigController]：桥接到 [ReadBookConfigShared]。
 *
 * 字段映射（app 端 → 桌面端）：
 * - `durConfig.name` → [ReadStyleConfig.name]
 * - `durConfig.curStatusIconDark()` / `setCurStatusIconDark(it)` → [ReadStyleConfig.darkStatusIcon]
 * - `durConfig.underline()` / `underline = it` → [ReadBookConfigShared.underline]
 * - `ReadBookConfig.bgAlpha` → [ReadBookConfigShared.bgAlpha]
 * - `durConfig.curTextColor()` → [ReadStyleConfig.textColor]（默认 0 时从 textColorStr 解析）
 * - `durConfig.curBgType()` → [ReadStyleConfig.bgType]
 * - `durConfig.curBgStr()` → [ReadStyleConfig.bgStr]
 * - `durConfig.setCurTextColor(it)` → 同步更新 textColor + textColorStr
 * - `durConfig.setCurBg(type, value)` → 同步更新 bgType + bgStr
 * - `ReadBookConfig.deleteDur()` → [ReadBookConfigShared.deleteDur]
 * - `ReadBookConfig.save()` → [ReadBookConfigShared.save]（写 readConfig.json / shareReadConfig.json）
 * - `DefaultData.readConfigs.map { it.name }` → 单一预设名（桌面端无 DefaultData 下沉）
 * - `defaultConfigs[i].copy()` → 重置为默认 [ReadStyleConfig]
 */
private class DesktopBgTextConfigController(
    private val readBookConfig: ReadBookConfigShared,
) : BgTextConfigController {

    override var name: String
        get() = readBookConfig.durConfig.name
        set(value) {
            readBookConfig.durConfig.name = value
        }

    override fun darkStatusIcon(): Boolean = readBookConfig.durConfig.darkStatusIcon

    override fun setCurStatusIconDark(value: Boolean) {
        readBookConfig.durConfig.darkStatusIcon = value
    }

    override fun underline(): Boolean = readBookConfig.underline

    override fun setUnderline(value: Boolean) {
        readBookConfig.underline = value
    }

    override fun bgAlpha(): Int = readBookConfig.bgAlpha

    override fun setBgAlpha(value: Int) {
        readBookConfig.bgAlpha = value
    }

    override fun curTextColor(): Int {
        val cfg = readBookConfig.durConfig
        // textColor 默认 0 时从 textColorStr 解析（对齐 app 端 curTextColor 逻辑）
        return if (cfg.textColor != 0) cfg.textColor
        else runCatching { ColorUtils.parseColor(cfg.textColorStr) }.getOrDefault(0xFF3E3D3B.toInt())
    }

    override fun curBgType(): Int = readBookConfig.durConfig.bgType

    override fun curBgStr(): String = readBookConfig.durConfig.bgStr

    override fun setCurTextColor(color: Int) {
        val cfg = readBookConfig.durConfig
        cfg.textColor = color
        cfg.textColorStr = ColorUtils.intToString(color)
    }

    override fun setCurBg(type: Int, value: String) {
        val cfg = readBookConfig.durConfig
        cfg.bgType = type
        cfg.bgStr = value
    }

    override fun deleteDur(): Boolean = readBookConfig.deleteDur()

    override fun save() {
        readBookConfig.save()
    }

    override fun restorePresetNames(): List<String> = listOf("Default")

    override fun restorePreset(index: Int) {
        // 桌面端无 DefaultData 下沉，仅重置当前 durConfig 为默认值（与单一预设名对应）
        val cfg = readBookConfig.durConfig
        cfg.name = ""
        cfg.bgStr = "#EEEEEE"
        cfg.bgStrNight = "#000000"
        cfg.bgStrEInk = "#FFFFFF"
        cfg.bgType = 0
        cfg.bgTypeNight = 0
        cfg.bgTypeEInk = 0
        cfg.darkStatusIcon = true
        cfg.darkStatusIconNight = false
        cfg.darkStatusIconEInk = true
        cfg.textColorStr = "#3E3D3B"
        cfg.textColorStrNight = "#ADADAD"
        cfg.textColorStrEInk = "#000000"
        cfg.textColor = 0
        cfg.bgMeanColor = 0
    }
}

/**
 * 桌面版 [BgTextConfigActions]：桥接到 AWT FileDialog 选图 / 配置变更通知。
 *
 * # 简化项
 *
 * - onImportConfig / onExportConfig: zip 解压/打包逻辑未下沉，暂 no-op（TODO）
 * - onImportNetConfig: RemoteAssetsUtils 未下沉，暂 no-op（TODO）
 * - onSelectBgImage: AWT FileDialog 选图片文件，写入 [ReadStyleConfig.bgType]=2 + bgStr=文件路径
 * - onSelectBgPreset: bgImageList 为空，此回调不会被触发（no-op）
 */
private class DesktopBgTextConfigActions(
    private val readBookConfig: ReadBookConfigShared,
) : BgTextConfigActions {

    override fun onImportConfig() {
        // TODO: 桌面端 zip 解压逻辑未下沉，暂 no-op
    }

    override fun onExportConfig() {
        // TODO: 桌面端 zip 打包逻辑未下沉，暂 no-op
    }

    override fun onImportNetConfig() {
        // TODO: 桌面端 RemoteAssetsUtils 未下沉，暂 no-op
    }

    override fun onSelectBgImage() {
        // AWT FileDialog 选图片文件，需在 EDT 调用
        SwingUtilities.invokeLater {
            val dialog = FileDialog(Frame(), "Select Image", FileDialog.LOAD)
            dialog.filenameFilter = FileFilter
            dialog.isVisible = true
            val dir = dialog.directory ?: return@invokeLater
            val file = dialog.file ?: return@invokeLater
            val path = "$dir$file"
            // 写入 bgType=2（其它图片）+ bgStr=文件路径
            readBookConfig.durConfig.bgType = 2
            readBookConfig.durConfig.bgStr = path
        }
    }

    override fun onSelectBgPreset(fileName: String) {
        // bgImageList 为空，此回调不会被触发（no-op）
    }

    override fun onPostConfig(changes: List<ReadConfigChange>) {
    }

    /** 图片文件过滤器（png/jpg/jpeg/bmp/webp） */
    private object FileFilter : java.io.FilenameFilter {
        override fun accept(dir: java.io.File?, name: String?): Boolean {
            val n = name?.lowercase() ?: return false
            return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                n.endsWith(".bmp") || n.endsWith(".webp")
        }
    }
}
