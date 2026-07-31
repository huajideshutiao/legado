package io.legado.app.ui.book.read.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.constant.PageAnim
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [ReadStyleScreen.kt] / [BgTextConfigScreen.kt] / [AutoReadPanel.kt] 的 @Preview。
 *
 * Controller / Actions 接口用内存 stub 实现, 字段返回常见默认值 (与 AppConfig 真实默认对齐)。
 */

// ===== ReadStyleScreen =====

/**
 * Preview 期 [ReadStyleController] stub。
 *
 * 字段用 mutableStateOf 兜底, 让 Compose 可重组; 内部不持久化, set 时仅更新内存。
 */
private class PreviewReadStyleController : ReadStyleController {
    override var textBold: Int = 0
    override var chineseType: Int = 0
    override var pageAnim: Int = PageAnim.coverPageAnim
    override var shareLayout: Boolean = false
    override var textSize: Int = 20
    override var letterSpacing: Float = 0.1f
    override var lineSpacingExtra: Int = 10
    override var paragraphSpacing: Int = 0
    override var styleSelect: Int = 0
    override var paragraphIndent: String = ""
    override val configList: List<ReadStyleConfig> = listOf(
        ReadStyleConfig(name = "默认").apply {
            textColor = 0xFF3E3D3B.toInt()
            bgMeanColor = 0xFFFFFFFF.toInt()
        },
        ReadStyleConfig(name = "护眼").apply {
            bgStr = "#C7EDCC"
            textColorStr = "#3E3D3B"
            textColor = 0xFF3E3D3B.toInt()
            bgMeanColor = 0xFFC7EDCC.toInt()
        },
        ReadStyleConfig(name = "夜间").apply {
            bgStr = "#000000"
            textColorStr = "#ADADAD"
            textColor = 0xFFADADAD.toInt()
            bgMeanColor = 0xFF000000.toInt()
        },
    )

    override fun curTextColor(): Int = configList[styleSelect].textColor
    override fun addStyle() {}
    override fun save() {}
}

/** Preview 期 [ReadStyleActions] stub, 所有回调空实现。 */
private object NoopReadStyleActions : ReadStyleActions {
    override fun showFontSelect() {}
    override fun showChineseConverter() {}
    override fun showPaddingConfig() {}
    override fun showTipConfig() {}
    override fun showBgTextConfig(index: Int) {}
    override fun onUpPageAnim() {}
    override fun onPostConfig(changes: List<ReadConfigChange>) {}
}

/** bgPreviewSlot 占位: 简单色块 + 选中描边, 不渲染真实 Bitmap。 */
@Composable
private fun previewBgPreviewSlot(
    config: ReadStyleConfig,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .size(100.dp, 150.dp)
            .background(Color(config.bgMeanColor)),
    )
}

@Preview
@Composable
fun ReadStyleScreenPreview() = LegadoThemePreview {
    ReadStyleScreen(
        controller = PreviewReadStyleController(),
        actions = NoopReadStyleActions,
        bgPreviewSlot = { config, selected, onClick, onLongClick ->
            previewBgPreviewSlot(config, selected, onClick, onLongClick)
        },
    )
}

@Preview
@Composable
fun ReadStyleScreenDarkPreview() = LegadoThemePreview(dark = true) {
    ReadStyleScreen(
        controller = PreviewReadStyleController().apply { styleSelect = 2 },
        actions = NoopReadStyleActions,
        bgPreviewSlot = { config, selected, onClick, onLongClick ->
            previewBgPreviewSlot(config, selected, onClick, onLongClick)
        },
    )
}

// ===== BgTextConfigScreen =====

/** Preview 期 [BgTextConfigController] stub。 */
private class PreviewBgTextConfigController : BgTextConfigController {
    override var name: String = "默认"
    private var darkStatus: Boolean = true
    private var underline: Boolean = false
    private var bgAlpha: Int = 100
    private var textColor: Int = 0xFF3E3D3B.toInt()
    private var bgType: Int = 0
    private var bgStr: String = "#FFFFFF"

    override fun darkStatusIcon(): Boolean = darkStatus
    override fun setCurStatusIconDark(value: Boolean) { darkStatus = value }
    override fun underline(): Boolean = underline
    override fun setUnderline(value: Boolean) { underline = value }
    override fun bgAlpha(): Int = bgAlpha
    override fun setBgAlpha(value: Int) { bgAlpha = value }
    override fun curTextColor(): Int = textColor
    override fun curBgType(): Int = bgType
    override fun curBgStr(): String = bgStr
    override fun setCurTextColor(color: Int) { textColor = color }
    override fun setCurBg(type: Int, value: String) {
        bgType = type
        bgStr = value
    }
    override fun deleteDur(): Boolean = true
    override fun save() {}
    override fun restorePresetNames(): List<String> = listOf("默认", "护眼", "夜间", "羊皮纸")
    override fun restorePreset(index: Int) {}
}

/** Preview 期 [BgTextConfigActions] stub。 */
private object NoopBgTextConfigActions : BgTextConfigActions {
    override fun onImportConfig() {}
    override fun onExportConfig() {}
    override fun onImportNetConfig() {}
    override fun onSelectBgImage() {}
    override fun onSelectBgPreset(fileName: String) {}
    override fun onPostConfig(changes: List<ReadConfigChange>) {}
}

/** 背景图预览占位: 66x88 色块。 */
@Composable
private fun previewBgImageSlot(item: BgImageItem, onClick: () -> Unit) {
    Box(
        Modifier
            .size(66.dp, 88.dp)
            .background(Color(0xFFEEEEEE)),
    )
}

private val previewBgImageList = listOf(
    BgImageItem(label = "纸纹 01", fileName = "paper01.jpg"),
    BgImageItem(label = "纸纹 02", fileName = "paper02.jpg"),
    BgImageItem(label = "山水", fileName = "landscape.png"),
)

@Preview
@Composable
fun BgTextConfigScreenPreview() = LegadoThemePreview {
    BgTextConfigScreen(
        controller = PreviewBgTextConfigController(),
        actions = NoopBgTextConfigActions,
        isImageBook = false,
        bgImageList = previewBgImageList,
        bgImagePreviewSlot = { item, onClick -> previewBgImageSlot(item, onClick) },
    )
}

@Preview
@Composable
fun BgTextConfigScreenImageBookPreview() = LegadoThemePreview {
    // 图片书籍不显示下划线开关
    BgTextConfigScreen(
        controller = PreviewBgTextConfigController(),
        actions = NoopBgTextConfigActions,
        isImageBook = true,
        bgImageList = previewBgImageList,
        bgImagePreviewSlot = { item, onClick -> previewBgImageSlot(item, onClick) },
    )
}

@Preview
@Composable
fun BgTextConfigScreenDarkPreview() = LegadoThemePreview(dark = true) {
    BgTextConfigScreen(
        controller = PreviewBgTextConfigController(),
        actions = NoopBgTextConfigActions,
        isImageBook = false,
        bgImageList = previewBgImageList,
        bgImagePreviewSlot = { item, onClick -> previewBgImageSlot(item, onClick) },
    )
}

// ===== AutoReadPanel =====

/** Preview 期 [AutoReadController] stub。 */
private class PreviewAutoReadController(
    override var autoReadSpeed: Int = 10,
) : AutoReadController

/** Preview 期 [AutoReadActions] stub。 */
private object NoopAutoReadActions : AutoReadActions {
    override fun openChapterList() {}
    override fun showMenuBar() {}
    override fun autoPageStop() {}
    override fun showPageAnimConfig() {}
    override fun upTtsSpeechRate() {}
}

@Preview
@Composable
fun AutoReadPanelPreview() = LegadoThemePreview {
    AutoReadPanel(
        controller = PreviewAutoReadController(autoReadSpeed = 10),
        actions = NoopAutoReadActions,
    )
}

@Preview
@Composable
fun AutoReadPanelFastPreview() = LegadoThemePreview {
    AutoReadPanel(
        controller = PreviewAutoReadController(autoReadSpeed = 60),
        actions = NoopAutoReadActions,
    )
}

@Preview
@Composable
fun AutoReadPanelDarkPreview() = LegadoThemePreview(dark = true) {
    AutoReadPanel(
        controller = PreviewAutoReadController(autoReadSpeed = 30),
        actions = NoopAutoReadActions,
    )
}
