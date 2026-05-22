package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isGone
import androidx.fragment.app.viewModels
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.base.BaseBottomDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogReadBgTextBinding
import io.legado.app.databinding.ItemBgImageBinding
import io.legado.app.help.DefaultData
import io.legado.app.help.book.isImage
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.RemoteAssetsUtils
import io.legado.app.utils.longToast
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx

class BgTextConfigDialog : BaseBottomDialogFragment(R.layout.dialog_read_bg_text) {

    companion object {
        const val TEXT_COLOR = 121
        const val BG_COLOR = 122
    }

    private val binding by viewBinding(DialogReadBgTextBinding::bind)
    private val viewModel by viewModels<BgTextConfigViewModel>()
    private val adapter by lazy { BgAdapter(requireContext(), secondaryTextColor) }
    private var primaryTextColor = 0
    private var secondaryTextColor = 0
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

    override fun onBottomDialogCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
        initEvent()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
    }

    private fun initView() = binding.run {
        val theme = createReadMenuTheme(requireContext())
        primaryTextColor = theme.textColor
        secondaryTextColor = theme.secondaryTextColor
        rootView.applyMenuTheme(theme)
        tvNameTitle.applyMenuThemeTextColor(theme)
        tvName.applyMenuThemeSecondaryTextColor(theme)
        ivEdit.applyMenuThemeSecondaryColorFilter(theme, PorterDuff.Mode.SRC_IN)
        tvRestore.applyMenuThemeTextColor(theme)
        swDarkStatusIcon.applyMenuThemeTextColor(theme)
        swUnderline.applyMenuThemeTextColor(theme)
        ivImport.applyMenuThemeColorFilter(theme, PorterDuff.Mode.SRC_IN)
        ivExport.applyMenuThemeColorFilter(theme, PorterDuff.Mode.SRC_IN)
        ivDelete.applyMenuThemeColorFilter(theme, PorterDuff.Mode.SRC_IN)
        tvBgAlpha.applyMenuThemeTextColor(theme)
        tvBgImage.applyMenuThemeTextColor(theme)
        swUnderline.isGone = ReadBook.book?.isImage == true
        recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemBgImageBinding.inflate(layoutInflater, it, false).apply {
                tvName.applyMenuThemeSecondaryTextColor(theme)
                tvName.text = getString(R.string.select_image)
                ivBg.setImageResource(R.drawable.ic_image)
                ivBg.applyMenuThemeColorFilter(theme, PorterDuff.Mode.SRC_IN)
                root.setOnClickListener {
                    selectBgImage.launch {
                        mode = HandleFileContract.IMAGE
                    }
                }
            }
        }
        adapter.setItems(RemoteAssetsUtils.getBgList())
    }

    @SuppressLint("InflateParams")
    private fun initData() = with(ReadBookConfig.durConfig) {
        binding.tvName.text = name.ifBlank { "文字" }
        binding.swDarkStatusIcon.isChecked = curStatusIconDark()
        binding.swUnderline.isChecked = underline
        binding.sbBgAlpha.progress = bgAlpha
    }

    @SuppressLint("InflateParams")
    private fun initEvent() = with(ReadBookConfig.durConfig) {
        binding.ivEdit.setOnClickListener {
            alert(R.string.style_name) {
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = "name"
                    editView.setText(ReadBookConfig.durConfig.name)
                }
                customView { alertBinding.root }
                okButton {
                    alertBinding.editView.text?.toString()?.let {
                        binding.tvName.text = it
                        ReadBookConfig.durConfig.name = it
                    }
                }
                cancelButton()
            }
        }
        binding.tvRestore.setOnClickListener {
            val defaultConfigs = DefaultData.readConfigs
            val layoutNames = defaultConfigs.map { it.name }
            context?.selector("选择预设布局", layoutNames) { _, i ->
                if (i >= 0) {
                    ReadBookConfig.durConfig = defaultConfigs[i].copy()
                    initData()
                    postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
                }
            }
        }
        binding.swDarkStatusIcon.setOnCheckedChangeListener { _, isChecked ->
            setCurStatusIconDark(isChecked)
            (activity as? ReadBookActivity)?.upSystemUiVisibility()
        }
        binding.swUnderline.setOnCheckedChangeListener { _, isChecked ->
            underline = isChecked
            postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
        }
        binding.tvTextColor.setOnClickListener {
            ColorPickerDialog.newBuilder()
                .setColor(curTextColor())
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(TEXT_COLOR)
                .show(requireActivity())
        }
        binding.tvBgColor.setOnClickListener {
            val bgColor =
                if (curBgType() == 0) curBgStr().toColorInt()
                else "#015A86".toColorInt()
            ColorPickerDialog.newBuilder()
                .setColor(bgColor)
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(BG_COLOR)
                .show(requireActivity())
        }
        binding.tvBgColor.apply {
            TooltipCompat.setTooltipText(this, text)
        }
        binding.ivImport.setOnClickListener {
            selectImportDoc.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.import_str)
                allowExtensions = arrayOf("zip")
                otherActions = arrayListOf(SelectItem(importFormNet, -1))
            }
        }
        binding.ivExport.setOnClickListener {
            selectExportDir.launch {
                title = getString(R.string.export_str)
            }
        }
        binding.ivDelete.setOnClickListener {
            if (ReadBookConfig.deleteDur()) {
                postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 12, 5))
                dismissAllowingStateLoss()
            } else {
                toastOnUi("数量已是最少,不能删除.")
            }
        }
        binding.sbBgAlpha.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                ReadBookConfig.bgAlpha = progress
                postEvent(EventBus.UP_CONFIG, arrayListOf(3))
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                postEvent(EventBus.UP_CONFIG, arrayListOf(3))
            }
        })
    }

    @SuppressLint("InflateParams")
    private fun importNetConfigAlert() {
        alert("输入地址") {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater)
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { url ->
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
