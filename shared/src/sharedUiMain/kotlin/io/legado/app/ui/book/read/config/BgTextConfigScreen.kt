package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.component.AppDetailSeekBar
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.StrokeTextChip
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.preference.ColorPickerDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.ColorUtils
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bg_alpha
import legado.shared.generated.resources.bg_color
import legado.shared.generated.resources.bg_image
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.dark_status_icon
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.export_str
import legado.shared.generated.resources.ic_edit
import legado.shared.generated.resources.import_str
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.restore
import legado.shared.generated.resources.select_image
import legado.shared.generated.resources.style_name
import legado.shared.generated.resources.text_color
import legado.shared.generated.resources.text_underline
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 背景/文字样式配置控制器：把 app 端 `ReadBookConfig.durConfig` 的字段读写抽象为接口，
 * 由 app/desktop 端 thin wrapper 实现并桥接到各自配置存储。
 *
 * 字段命名与 app 端 `ReadBookConfig.Config` 完全一致，方便 wrapper 直接转发。
 */
interface BgTextConfigController {
    /** 样式名称（对应 `durConfig.name`，空时显示"文字"） */
    var name: String

    /** 当前是否暗色状态栏图标（对应 `durConfig.curStatusIconDark()`） */
    fun darkStatusIcon(): Boolean

    /** 设置当前暗色状态栏图标（对应 `durConfig.setCurStatusIconDark(it)`） */
    fun setCurStatusIconDark(value: Boolean)

    /** 是否文字下划线（对应 `durConfig.underline`） */
    fun underline(): Boolean

    /** 设置文字下划线（对应 `durConfig.underline = it`） */
    fun setUnderline(value: Boolean)

    /** 背景透明度（对应 `ReadBookConfig.bgAlpha`，0-100） */
    fun bgAlpha(): Int

    /** 设置背景透明度 */
    fun setBgAlpha(value: Int)

    /** 当前文字颜色 Int（对应 `durConfig.curTextColor()`） */
    fun curTextColor(): Int

    /** 当前背景类型 0:颜色 1:assets 图片 2:其它图片（对应 `durConfig.curBgType()`） */
    fun curBgType(): Int

    /** 当前背景字符串 hex 或文件名（对应 `durConfig.curBgStr()`） */
    fun curBgStr(): String

    /** 设置当前文字颜色（对应 `durConfig.setCurTextColor(it)`） */
    fun setCurTextColor(color: Int)

    /**
     * 设置当前背景（对应 `durConfig.setCurBg(type, value)`）
     * @param type 0:颜色 1:assets 图片 2:其它图片
     * @param value 颜色 hex 或图片文件名
     */
    fun setCurBg(type: Int, value: String)

    /** 删除当前主题（对应 `ReadBookConfig.deleteDur()`），返回是否删除成功 */
    fun deleteDur(): Boolean

    /** 持久化配置（对应 `ReadBookConfig.save()`） */
    fun save()

    /**
     * 恢复预设布局名称列表（对应 app 端 `DefaultData.readConfigs.map { it.name }`）。
     * shared/commonMain 无 DefaultData，由各平台 wrapper 提供预设名列表。
     */
    fun restorePresetNames(): List<String>

    /**
     * 恢复预设布局（对应 app 端 `ReadBookConfig.durConfig = defaultConfigs[i].copy()`）。
     * @param index 预设索引
     */
    fun restorePreset(index: Int)
}

/**
 * 背景文字配置动作回调：把 app 端 Android 专属操作（文件选择 / 网络导入 / 背景图列表 /
 * Bitmap 解码）抽象为接口，由各平台 wrapper 桥接到各自平台 API。
 *
 * app 端原实现：
 * - `selectBgImage` / `selectExportDir` / `selectImportDoc` 用 `registerHandleFile`（SAF）
 * - `RemoteAssetsUtils.getBgList()` / `getBgPreviewBytes()` 取 assets 背景图
 * - `BgTextConfigViewModel` 处理 zip 导入导出 + 网络下载
 *
 * shared/commonMain 无 SAF / RemoteAssetsUtils / ViewModel，由 wrapper 实现。
 */
interface BgTextConfigActions {
    /** 弹文件选择导入配置 zip（对应 `selectImportDoc.launch`） */
    fun onImportConfig()

    /** 弹文件选择导出配置 zip（对应 `selectExportDir.launch`） */
    fun onExportConfig()

    /** 弹网络导入地址输入框（对应 `importNetConfigAlert`） */
    fun onImportNetConfig()

    /** 弹图片选择器选背景图（对应 `selectBgImage.launch`） */
    fun onSelectBgImage()

    /**
     * 选择 assets 背景图预设（对应 `ReadBookConfig.durConfig.setCurBg(1, fileName)`）。
     * @param fileName assets 内背景图文件名
     */
    fun onSelectBgPreset(fileName: String)

    /** 配置变更通知（对应 `ReadBookEvents.postConfig(...)`） */
    fun onPostConfig(changes: List<ReadConfigChange>)
}

/**
 * 背景图列表项数据，由 wrapper 提供（shared/commonMain 不依赖 RemoteAssetsUtils）。
 */
data class BgImageItem(
    /** 显示名（文件名去后缀） */
    val label: String,
    /** assets 内文件名或图片路径 */
    val fileName: String,
)

/**
 * 背景/文字样式配置 Screen 正文 Composable。
 *
 * 下沉自 app 端 `BgTextConfigDialog`，原 DialogFragment 宿主由各平台 thin wrapper 保留：
 * - `BaseReadBottomComposeDialog` 的 BottomSheet 形式（app 端）
 * - `AppAlertDialog` 的 content 槽（desktop 端）
 *
 * 资源访问替换：
 * - `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)`
 * - `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")`
 * - `ReadBookConfig.durConfig.xxx` → [controller.xxx]
 * - `selectXxx.launch` / `RemoteAssetsUtils.xxx` → [actions.xxx]
 *
 * 行为对齐原 BgTextConfigDialog：所有改动即时写 controller + postConfig 刷新渲染。
 *
 * # 颜色选择器
 * 直接复用 shared 的 [ColorPickerDialog]，无需 callback 桥接。
 *
 * # 背景图列表
 * 原实现依赖 `RemoteAssetsUtils.getBgList()` + `BitmapFactory.decodeByteArray`，
 * shared/commonMain 无对应 API，通过 [bgImageList] + [bgImagePreviewSlot] 桥接：
 * - [bgImageList] 提供背景图文件名列表（wrapper 实现）
 * - [bgImagePreviewSlot] 为单个背景图渲染预览（wrapper 实现，接收 item 与 onClick）
 *
 * @param controller 配置读写桥接
 * @param actions 动作回调桥接
 * @param isImageBook 是否图片书籍（控制是否显示下划线开关）
 * @param bgImageList 背景图列表（由 wrapper 提供，shared 不依赖 RemoteAssetsUtils）
 * @param bgImagePreviewSlot 单个背景图预览渲染（item, onClick）
 */
@Composable
fun BgTextConfigScreen(
    controller: BgTextConfigController,
    actions: BgTextConfigActions,
    isImageBook: Boolean,
    bgImageList: List<BgImageItem>,
    bgImagePreviewSlot: @Composable (item: BgImageItem, onClick: () -> Unit) -> Unit,
) {
    val colors = AppTheme.colors
    // 资源 key 在 Composable 顶层一次性求值（avoid calling @Composable in remember initializer）
    val styleNameStr = stringResource(Res.string.style_name)
    val darkStatusIconStr = stringResource(Res.string.dark_status_icon)
    val textUnderlineStr = stringResource(Res.string.text_underline)
    val textColorStr = stringResource(Res.string.text_color)
    val bgColorStr = stringResource(Res.string.bg_color)
    val importStr = stringResource(Res.string.import_str)
    val exportStr = stringResource(Res.string.export_str)
    val deleteStr = stringResource(Res.string.delete)
    val bgAlphaStr = stringResource(Res.string.bg_alpha)
    val bgImageStr = stringResource(Res.string.bg_image)
    val selectImageStr = stringResource(Res.string.select_image)
    val restoreStr = stringResource(Res.string.restore)
    val editStr = stringResource(Res.string.edit)
    val okStr = stringResource(Res.string.ok)
    val cancelStr = stringResource(Res.string.cancel)

    // 恢复预设布局后整体重读配置（对齐原 initData）
    var refresh by remember { mutableIntStateOf(0) }
    var name by remember(refresh, styleNameStr) {
        mutableStateOf(controller.name.ifBlank { styleNameStr })
    }
    var darkStatusIcon by remember(refresh) { mutableStateOf(controller.darkStatusIcon()) }
    var underline by remember(refresh) { mutableStateOf(controller.underline()) }
    var bgAlpha by remember(refresh) { mutableIntStateOf(controller.bgAlpha()) }
    var showTextColorPicker by remember { mutableStateOf(false) }
    var showBgColorPicker by remember { mutableStateOf(false) }
    var showNameEditDialog by remember { mutableStateOf(false) }
    var showRestorePresetDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        // 样式名行：名称 + 编辑 + 恢复预设
        Row(
            Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(styleNameStr, color = colors.primaryText, fontSize = 16.sp)
            Text(
                name, color = colors.secondaryText,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Icon(
                painter = painterResource(Res.drawable.ic_edit),
                contentDescription = editStr,
                tint = colors.secondaryText,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { showNameEditDialog = true },
            )
            Spacer(Modifier.weight(1f))
            Text(
                restoreStr, color = colors.primaryText,
                modifier = Modifier.clickable { showRestorePresetDialog = true },
            )
        }
        // 暗色状态栏图标开关
        SwitchRow(darkStatusIconStr, darkStatusIcon) {
            darkStatusIcon = it
            controller.setCurStatusIconDark(it)
        }
        // 下划线开关（图片书籍不显示）
        if (!isImageBook) {
            SwitchRow(textUnderlineStr, underline) {
                underline = it
                controller.setUnderline(it)
                actions.onPostConfig(
                    listOf(
                        ReadConfigChange.UP_CONTENT,
                        ReadConfigChange.INVALIDATE_TEXT_PAGE,
                        ReadConfigChange.RENDER_TASK,
                    )
                )
            }
        }
        // 文字颜色 / 背景颜色 / 导入 / 导出 / 删除
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StrokeTextChip(
                textColorStr,
                textColor = colors.secondaryText,
                modifier = Modifier.weight(5f),
            ) { showTextColorPicker = true }
            StrokeTextChip(
                bgColorStr,
                textColor = colors.secondaryText,
                modifier = Modifier.weight(5f).padding(start = 8.dp),
            ) { showBgColorPicker = true }
            ActionIcon("ic_import", importStr, colors.primaryText) {
                actions.onImportConfig()
            }
            ActionIcon("ic_export", exportStr, colors.primaryText) {
                actions.onExportConfig()
            }
            ActionIcon("ic_clear_all", deleteStr, colors.primaryText) {
                if (controller.deleteDur()) {
                    actions.onPostConfig(
                        listOf(
                            ReadConfigChange.BG,
                            ReadConfigChange.STYLE,
                            ReadConfigChange.PAGE_ANIM,
                            ReadConfigChange.LOAD_CONTENT,
                        )
                    )
                }
            }
        }
        // 背景透明度滑条
        AppDetailSeekBar(
            title = bgAlphaStr,
            value = bgAlpha, max = 100, textColor = colors.primaryText,
            onChanged = {
                bgAlpha = it
                controller.setBgAlpha(it)
                actions.onPostConfig(listOf(ReadConfigChange.BG_ALPHA))
            },
        )
        // 背景图列表
        Text(bgImageStr, color = colors.primaryText)
        // 渲染"选择图片"按钮 + wrapper 提供的背景图列表
        // 对齐 app 端 LazyRow(Modifier.fillMaxWidth().height(100.dp).padding(vertical = 8.dp))
        // 每个 BgItem 66x88dp, 由 wrapper 通过 bgImagePreviewSlot 渲染
        androidx.compose.foundation.lazy.LazyRow(
            Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(vertical = 8.dp)
        ) {
            // "选择图片"按钮 (对应 app 端 BgItem(icon = ic_image))
            item {
                bgImagePreviewSlot(
                    BgImageItem(label = selectImageStr, fileName = ""),
                ) {
                    actions.onSelectBgImage()
                }
            }
            // wrapper 提供的背景图列表
            items(bgImageList.size) { index ->
                val item = bgImageList[index]
                bgImagePreviewSlot(item) {
                    actions.onSelectBgPreset(item.fileName)
                }
            }
        }
    }

    // 文字颜色选择器
    if (showTextColorPicker) {
        ColorPickerDialog(
            initColor = controller.curTextColor(),
            title = textColorStr,
            showAlphaSlider = false,
            onDismissRequest = { showTextColorPicker = false },
            onConfirm = { color ->
                controller.setCurTextColor(color)
                actions.onPostConfig(
                    listOf(
                        ReadConfigChange.STYLE,
                        ReadConfigChange.UP_CONTENT,
                        ReadConfigChange.INVALIDATE_TEXT_PAGE,
                        ReadConfigChange.RENDER_TASK,
                    )
                )
            },
        )
    }
    // 背景颜色选择器
    if (showBgColorPicker) {
        val initBgColor = if (controller.curBgType() == 0) {
            runCatching { ColorUtils.parseColor(controller.curBgStr()) }.getOrDefault(0xFF015A86.toInt())
        } else {
            0xFF015A86.toInt()
        }
        ColorPickerDialog(
            initColor = initBgColor,
            title = bgColorStr,
            showAlphaSlider = false,
            onDismissRequest = { showBgColorPicker = false },
            onConfirm = { color ->
                controller.setCurBg(0, ColorUtils.intToString(color))
                actions.onPostConfig(listOf(ReadConfigChange.BG))
            },
        )
    }

    // 名称编辑简易对话框 (对齐 app 端 alert + editTextView)
    if (showNameEditDialog) {
        var editName by remember { mutableStateOf(controller.name) }
        AppAlertDialog(
            onDismissRequest = { showNameEditDialog = false },
            title = styleNameStr,
            okButton = AlertButton(text = okStr) {
                name = editName
                controller.name = editName
            },
            cancelButton = AlertButton(text = cancelStr),
        ) {
            AppOutlinedTextField(
                value = editName,
                onValueChange = { editName = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }

    // 恢复预设布局选择对话框 (对齐 app 端 context.selector)
    if (showRestorePresetDialog) {
        val presetNames = controller.restorePresetNames()
        AppSelectorDialog(
            onDismissRequest = { showRestorePresetDialog = false },
            title = restoreStr,
            items = presetNames,
            onItemSelected = { index ->
                controller.restorePreset(index)
                refresh++
                actions.onPostConfig(
                    listOf(
                        ReadConfigChange.BG,
                        ReadConfigChange.STYLE,
                        ReadConfigChange.LOAD_CONTENT,
                    )
                )
            },
        )
    }
}

/**
 * 开关行：标签 + Switch（复刻 app 端 SwitchRow，宽高对齐 height=40dp）。
 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AppTheme.colors.primaryText, modifier = Modifier.weight(1f))
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 动作图标：32dp 大小，左 8dp padding（复刻 app 端 ActionIcon）。
 */
@Composable
private fun ActionIcon(
    iconKey: String,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Icon(
        painter = rememberPainter(iconKey),
        contentDescription = description,
        tint = tint,
        modifier = Modifier
            .padding(start = 8.dp)
            .size(32.dp)
            .clickable(onClick = onClick),
    )
}
