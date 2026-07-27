package io.legado.app.ui.font

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import io.legado.app.ui.compose.component.AppDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.externalFiles
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.list
import io.legado.app.utils.listFileDocs
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder

class FontSelectDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true
    private val fontRegex = Regex("(?i).*\\.[ot]tf")
    private var curName: String? = null
    private var fontItems by mutableStateOf(listOf<FileDoc>())
    private val selectFontDir by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                if (uri.isContentScheme()) {
                    AppConfig.fontFolder = uri.toString()
                    val doc = DocumentFile.fromTreeUri(requireContext(), uri)
                    if (doc != null) {
                        loadFontFiles(FileDoc.fromDocumentFile(doc))
                    } else {
                        RealPathUtil.getPath(requireContext(), uri)?.let { path ->
                            loadFontFilesByPermission(path)
                        }
                    }
                } else {
                    uri.path?.let { path ->
                        AppConfig.fontFolder = path
                        loadFontFilesByPermission(path)
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        curName = kotlin.runCatching {
            URLDecoder.decode(callBack?.curFontPath ?: "", "utf-8")
        }.getOrNull()?.substringAfterLast(File.separator)

        val fontPath = AppConfig.fontFolder
        if (fontPath.isNullOrEmpty()) {
            openFolder()
        } else {
            if (fontPath.isContentScheme()) {
                val doc = DocumentFile.fromTreeUri(requireContext(), fontPath.toUri())
                if (doc?.canRead() == true) {
                    loadFontFiles(FileDoc.fromDocumentFile(doc))
                } else {
                    openFolder()
                }
            } else {
                loadFontFilesByPermission(fontPath)
            }
        }
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        var showTypefaceDialog by remember { mutableStateOf(false) }
        var showOverflow by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            // 标题栏对齐 dialog_title_bar + font_select 菜单(默认字体 ifRoom / 其他文件夹 overflow)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.select_font),
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.default_font),
                    color = colors.primaryText,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable { showTypefaceDialog = true }
                        .padding(12.dp)
                )
                Box {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(
                            painter = rememberPainter("ic_more_vert"),
                            contentDescription = null,
                            tint = colors.primaryText
                        )
                    }
                    AppDropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.other_folder),
                                    color = colors.primaryText
                                )
                            },
                            onClick = {
                                showOverflow = false
                                openFolder()
                            }
                        )
                    }
                }
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(fontItems, key = { it.toString() }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onFontSelect(item) }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = item.name == curName,
                            onClick = { onFontSelect(item) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.accent,
                                unselectedColor = colors.secondaryText
                            )
                        )
                        Text(
                            text = item.name,
                            color = colors.primaryText,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
        if (showTypefaceDialog) {
            AppSelectorDialog(
                onDismissRequest = { showTypefaceDialog = false },
                title = stringResource(R.string.system_typeface),
                items = stringArrayResource(R.array.system_typefaces).toList(),
                onItemSelected = { i ->
                    AppConfig.systemTypefaces = i
                    onDefaultFontChange()
                    dismissAllowingStateLoss()
                },
            )
        }
    }

    private fun openFolder() {
        lifecycleScope.launch {
            val defaultPath = "SD${File.separator}Fonts"
            selectFontDir.launch {
                otherActions = arrayListOf(SelectItem(defaultPath, -1))
            }
        }
    }

    private fun getLocalFonts(): ArrayList<FileDoc> {
        val path = FileUtils.getPath(requireContext().externalFiles, "font")
        return File(path).listFileDocs {
            it.name.matches(fontRegex)
        }
    }

    private fun loadFontFilesByPermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                loadFontFiles(
                    FileDoc.fromFile(File(path))
                )
            }
            .request()
    }

    private fun loadFontFiles(fileDoc: FileDoc) {
        execute {
            val items = fileDoc.list {
                it.name.matches(fontRegex)
            } ?: ArrayList()
            mergeFontItems(items, getLocalFonts())
        }.onSuccess {
            fontItems = it
        }.onError {
            AppLog.put("加载字体文件失败\n${it.localizedMessage}", it)
            toastOnUi("getFontFiles:${it.localizedMessage}")
        }
    }

    private fun mergeFontItems(
        items1: List<FileDoc>,
        items2: List<FileDoc>
    ): List<FileDoc> {
        val names = items1.mapTo(mutableSetOf()) { it.name }
        return (items1 + items2.filter { it.name !in names }).sortedBy { it.name }
    }

    private fun onFontSelect(docItem: FileDoc) {
        execute {
            callBack?.selectFont(docItem.toString())
        }.onSuccess {
            dismissAllowingStateLoss()
        }
    }

    private fun onDefaultFontChange() {
        callBack?.selectFont("")
    }

    private val callBack: CallBack?
        get() = (parentFragment as? CallBack) ?: (activity as? CallBack)

    interface CallBack {
        fun selectFont(path: String)
        val curFontPath: String
    }
}
