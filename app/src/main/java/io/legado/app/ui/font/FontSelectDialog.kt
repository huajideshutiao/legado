package io.legado.app.ui.font

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.items
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.dpToPx
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.list
import io.legado.app.utils.listFileDocs
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder

class FontSelectDialog : BaseDialogFragment(0),
    Toolbar.OnMenuItemClickListener {

    override val isFullHeight: Boolean = true
    private val fontRegex = Regex("(?i).*\\.[ot]tf")
    private lateinit var rgFonts: RadioGroup
    private var curName: String? = null
    private val selectFontDir by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                if (uri.isContentScheme()) {
                    putPrefString(PreferKey.fontFolder, uri.toString())
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
                        putPrefString(PreferKey.fontFolder, path)
                        loadFontFilesByPermission(path)
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp16 = 16.dpToPx()
        return LinearLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            addView(TitleBar(ctx).apply {
                id = R.id.title_bar
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0
                ).apply { weight = 1f }
                clipToPadding = false
                addView(RadioGroup(ctx).apply {
                    rgFonts = this
                    id = View.generateViewId()
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    orientation = RadioGroup.VERTICAL
                    setPadding(dp16, 0, dp16, 0)
                })
            })
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setupTitleBar(
            title = getString(R.string.select_font),
            menuRes = R.menu.font_select,
            onMenuClick = ::onMenuItemClick
        )
        curName = kotlin.runCatching {
            URLDecoder.decode(callBack?.curFontPath ?: "", "utf-8")
        }.getOrNull()?.substringAfterLast(File.separator)

        val fontPath = getPrefString(PreferKey.fontFolder)
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

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_default -> {
                val requireContext = requireContext()
                alert(titleResource = R.string.system_typeface) {
                    items(
                        requireContext.resources.getStringArray(R.array.system_typefaces).toList()
                    ) { _, i ->
                        AppConfig.systemTypefaces = i
                        onDefaultFontChange()
                        dismissAllowingStateLoss()
                    }
                }
            }
            R.id.menu_other -> {
                openFolder()
            }
        }
        return true
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
            val fontItems = fileDoc.list {
                it.name.matches(fontRegex)
            } ?: ArrayList()
            mergeFontItems(fontItems, getLocalFonts())
        }.onSuccess {
            showFontList(it)
        }.onError {
            AppLog.put("加载字体文件失败\n${it.localizedMessage}", it)
            toastOnUi("getFontFiles:${it.localizedMessage}")
        }
    }

    private fun showFontList(items: List<FileDoc>) {
        val ctx = requireContext()
        val vPad = 4.dpToPx()
        items.forEachIndexed { index, item ->
            val rb = RadioButton(ctx).apply {
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, vPad, 0, vPad)
                text = item.name
                tag = item
            }
            rgFonts.addView(rb)
            if (item.name == curName) rgFonts.check(rb.id)
        }
        rgFonts.setOnCheckedChangeListener { _, checkedId ->
            (rgFonts.findViewById<View>(checkedId)?.tag as? FileDoc)?.let(::onFontSelect)
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
