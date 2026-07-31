package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.DefaultData
import io.legado.app.help.book.isImage
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange.BG
import io.legado.app.ui.book.read.ReadConfigChange.BG_ALPHA
import io.legado.app.ui.book.read.ReadConfigChange.INVALIDATE_TEXT_PAGE
import io.legado.app.ui.book.read.ReadConfigChange.LOAD_CONTENT
import io.legado.app.ui.book.read.ReadConfigChange.PAGE_ANIM
import io.legado.app.ui.book.read.ReadConfigChange.RENDER_TASK
import io.legado.app.ui.book.read.ReadConfigChange.STYLE
import io.legado.app.ui.book.read.ReadConfigChange.UP_CONTENT
import io.legado.app.ui.compose.component.AppDetailSeekBar
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.StrokeTextChip
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.preference.ColorPickerDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.RemoteAssetsUtils
import io.legado.app.utils.hexString
import io.legado.app.utils.longToast
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import androidx.fragment.app.viewModels
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import splitties.init.appCtx

/** 背景/文字样式配置：命名、状态栏图标、下划线、取色、导入导出、背景图 */
class BgTextConfigDialog : BaseReadBottomComposeDialog() {

    private val viewModel by viewModels<BgTextConfigViewModel>()
    private val importFormNet = "网络导入"
    private val selectBgImage by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                viewModel.setBgFromUri(
                    uri,
                    onSuccess = {},
                    onError = { appCtx.toastOnUi(it) }
                )
            }
        }
    }
    private val selectExportDir by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                viewModel.exportConfig(
                    uri,
                    onSuccess = { exportFileName ->
                        toastOnUi("导出成功, 文件名为 $exportFileName")
                    },
                    onError = {
                        it.printOnDebug()
                        AppLog.put("导出失败:${it.localizedMessage}", it)
                        longToast("导出失败:${it.localizedMessage}")
                    }
                )
            }
        }
    }
    private val selectImportDoc by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                if (uri.path == "/$importFormNet") {
                    importNetConfigAlert()
                } else {
                    viewModel.importConfig(
                        uri,
                        onSuccess = { toastOnUi("导入成功") },
                        onError = {
                            it.printOnDebug()
                            longToast("导入失败:${it.localizedMessage}")
                        }
                    )
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val colors = rememberReadMenuColors()
        // 恢复预设布局后整体重读配置（对齐原 initData）
        var refresh by remember { mutableIntStateOf(0) }
        var name by remember(refresh) {
            mutableStateOf(ReadBookConfig.durConfig.name.ifBlank { "文字" })
        }
        var darkStatusIcon by remember(refresh) {
            mutableStateOf(ReadBookConfig.durConfig.curStatusIconDark())
        }
        var underline by remember(refresh) { mutableStateOf(ReadBookConfig.durConfig.underline) }
        var bgAlpha by remember(refresh) { mutableIntStateOf(ReadBookConfig.bgAlpha) }
        var showTextColorPicker by remember { mutableStateOf(false) }
        var showBgColorPicker by remember { mutableStateOf(false) }

        Column(Modifier
            .fillMaxWidth()
            .padding(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.style_name), color = colors.text, fontSize = 16.sp)
                Text(
                    name, color = colors.secondaryText,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Icon(
                    painter = rememberPainter("ic_edit"),
                    contentDescription = stringResource(R.string.edit),
                    tint = colors.secondaryText,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable {
                            alert(R.string.style_name) {
                                val getName = editTextView(
                                    hint = "name",
                                    text = ReadBookConfig.durConfig.name,
                                )
                                okButton {
                                    getName().let {
                                        name = it
                                        ReadBookConfig.durConfig.name = it
                                    }
                                }
                                cancelButton()
                            }
                        },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.restore), color = colors.text,
                    modifier = Modifier.clickable {
                        val defaultConfigs = DefaultData.readConfigs
                        val layoutNames = defaultConfigs.map { it.name }
                        context.selector("选择预设布局", layoutNames) { _, i ->
                            if (i >= 0) {
                                ReadBookConfig.durConfig = defaultConfigs[i].copy()
                                refresh++
                                ReadBookEvents.postConfig(BG, STYLE, LOAD_CONTENT)
                            }
                        }
                    },
                )
            }
            SwitchRow(stringResource(R.string.dark_status_icon), darkStatusIcon, colors) {
                darkStatusIcon = it
                ReadBookConfig.durConfig.setCurStatusIconDark(it)
                (activity as? ReadBookActivity)?.upSystemUiVisibility()
            }
            if (ReadBook.book?.isImage != true) {
                SwitchRow(stringResource(R.string.text_underline), underline, colors) {
                    underline = it
                    ReadBookConfig.durConfig.underline = it
                    ReadBookEvents.postConfig(UP_CONTENT, INVALIDATE_TEXT_PAGE, RENDER_TASK)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StrokeTextChip(
                    stringResource(R.string.text_color),
                    textColor = colors.secondaryText,
                    modifier = Modifier.weight(5f),
                ) { showTextColorPicker = true }
                StrokeTextChip(
                    stringResource(R.string.bg_color),
                    textColor = colors.secondaryText,
                    modifier = Modifier
                        .weight(5f)
                        .padding(start = 8.dp),
                ) { showBgColorPicker = true }
                ActionIcon("ic_import", stringResource(R.string.import_str), colors) {
                    selectImportDoc.launch {
                        mode = HandleFileContract.FILE
                        title = getString(R.string.import_str)
                        allowExtensions = arrayOf("zip")
                        otherActions = arrayListOf(SelectItem(importFormNet, -1))
                    }
                }
                ActionIcon("ic_export", stringResource(R.string.export_str), colors) {
                    selectExportDir.launch {
                        title = getString(R.string.export_str)
                    }
                }
                ActionIcon("ic_clear_all", stringResource(R.string.delete), colors) {
                    if (ReadBookConfig.deleteDur()) {
                        ReadBookEvents.postConfig(BG, STYLE, PAGE_ANIM, LOAD_CONTENT)
                        dismissAllowingStateLoss()
                    } else {
                        toastOnUi("数量已是最少,不能删除.")
                    }
                }
            }
            AppDetailSeekBar(
                title = stringResource(R.string.bg_alpha),
                value = bgAlpha, max = 100, textColor = colors.text,
                onChanged = {
                    bgAlpha = it
                    ReadBookConfig.bgAlpha = it
                    ReadBookEvents.postConfig(BG_ALPHA)
                },
            )
            Text(stringResource(R.string.bg_image), color = colors.text)
            LazyRow(Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(vertical = 8.dp)) {
                item {
                    BgItem(
                        label = stringResource(R.string.select_image),
                        colors = colors,
                        iconKey = "ic_image",
                    ) {
                        selectBgImage.launch {
                            mode = HandleFileContract.IMAGE
                        }
                    }
                }
                items(RemoteAssetsUtils.getBgList().size) { index ->
                    val fileName = RemoteAssetsUtils.getBgList()[index]
                    BgItem(
                        label = fileName.substringBeforeLast("."),
                        colors = colors,
                        previewName = fileName,
                    ) {
                        ReadBookConfig.durConfig.setCurBg(1, fileName)
                        ReadBookEvents.postConfig(BG)
                    }
                }
            }
        }

        if (showTextColorPicker) {
            ColorPickerDialog(
                initColor = ReadBookConfig.durConfig.curTextColor(),
                title = stringResource(R.string.text_color),
                showAlphaSlider = false,
                onDismissRequest = { showTextColorPicker = false },
                onConfirm = { color ->
                    // 原 ReadBookActivity TEXT_COLOR 分支
                    ReadBookConfig.durConfig.setCurTextColor(color)
                    ReadBookEvents.postConfig(STYLE, UP_CONTENT, INVALIDATE_TEXT_PAGE, RENDER_TASK)
                    ReadBookEvents.postActionBarChange()
                },
            )
        }
        if (showBgColorPicker) {
            ColorPickerDialog(
                initColor = if (ReadBookConfig.durConfig.curBgType() == 0) {
                    ReadBookConfig.durConfig.curBgStr().toColorInt()
                } else {
                    "#015A86".toColorInt()
                },
                title = stringResource(R.string.bg_color),
                showAlphaSlider = false,
                onDismissRequest = { showBgColorPicker = false },
                onConfirm = { color ->
                    // 原 ReadBookActivity BG_COLOR 分支
                    ReadBookConfig.durConfig.setCurBg(0, "#${color.hexString}")
                    ReadBookEvents.postConfig(BG)
                    ReadBookEvents.postActionBarChange()
                },
            )
        }
    }

    @Composable
    private fun SwitchRow(
        label: String,
        checked: Boolean,
        colors: ReadMenuColors,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = colors.text, modifier = Modifier.weight(1f))
            AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun ActionIcon(
        iconKey: String,
        description: String,
        colors: ReadMenuColors,
        onClick: () -> Unit,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = description,
            tint = colors.text,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(32.dp)
                .clickable(onClick = onClick),
        )
    }

    /** 背景图预览项 66x88：图 + 名称（复刻 BgAdapter 项与选图头） */
    @Composable
    private fun BgItem(
        label: String,
        colors: ReadMenuColors,
        iconKey: String? = null,
        previewName: String? = null,
        onClick: () -> Unit,
    ) {
        Column(
            Modifier
                .width(66.dp)
                .height(88.dp)
                .padding(2.dp)
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier
                .weight(1f)
                .fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (iconKey != null) {
                    Icon(
                        painter = rememberPainter(iconKey),
                        contentDescription = label,
                        tint = colors.text,
                    )
                } else if (previewName != null) {
                    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                        null, previewName
                    ) {
                        value = withContext(IO) {
                            RemoteAssetsUtils.getBgPreviewBytes(previewName)?.let {
                                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                            }
                        }
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
            }
            Text(label, color = colors.secondaryText, fontSize = 12.sp, maxLines = 1)
        }
    }

    private fun importNetConfigAlert() {
        alert("输入地址") {
            val getUrl = editTextView()
            okButton {
                getUrl().let { url ->
                    viewModel.importNetConfig(
                        url,
                        onSuccess = { toastOnUi("导入成功") },
                        onError = { longToast(it.stackTraceStr) }
                    )
                }
            }
            cancelButton()
        }
    }
}
