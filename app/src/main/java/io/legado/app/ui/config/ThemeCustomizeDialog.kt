package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.core.graphics.toColorInt
import androidx.core.os.bundleOf
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogThemeCustomizeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx
import java.io.FileOutputStream

class ThemeCustomizeDialog() : BaseDialogFragment(R.layout.dialog_theme_customize),
    ColorPickerDialogListener {

    companion object {
        const val RESULT_CONFIG_CHANGED = "themeConfigChanged"

        private const val ARG_MODE = "mode"
        private const val ARG_IS_NIGHT = "isNight"
        private const val ARG_CONFIG_INDEX = "configIndex"

        private const val MODE_EDIT_PREFS = 0
        private const val MODE_EDIT_CONFIG = 1
        private const val MODE_NEW_CONFIG = 2

        private const val DIALOG_ID_ACCENT = 1
        private const val DIALOG_ID_BG = 2
        private const val DIALOG_ID_BBG = 3

        private const val KEY_ACCENT = "s_accent"
        private const val KEY_BG = "s_bg"
        private const val KEY_BBG = "s_bbg"
        private const val KEY_BG_IMAGE = "s_bgImage"
        private const val KEY_BLUR = "s_blur"
        private const val KEY_THEME_NAME = "s_themeName"
        private const val KEY_IS_NIGHT = "s_isNight"

        fun editPrefs(isNight: Boolean) = ThemeCustomizeDialog().apply {
            arguments = bundleOf(ARG_MODE to MODE_EDIT_PREFS, ARG_IS_NIGHT to isNight)
        }

        fun editConfig(index: Int) = ThemeCustomizeDialog().apply {
            arguments = bundleOf(ARG_MODE to MODE_EDIT_CONFIG, ARG_CONFIG_INDEX to index)
        }

        fun newConfig(isNight: Boolean) = ThemeCustomizeDialog().apply {
            arguments = bundleOf(ARG_MODE to MODE_NEW_CONFIG, ARG_IS_NIGHT to isNight)
        }
    }

    private val binding by viewBinding(DialogThemeCustomizeBinding::bind)

    private val mode by lazy { requireArguments().getInt(ARG_MODE) }
    private val configIndex by lazy { requireArguments().getInt(ARG_CONFIG_INDEX, -1) }

    private var isNight: Boolean = false
    private var accent: Int = 0
    private var bg: Int = 0
    private var bbg: Int = 0
    private var bgImagePath: String? = null
    private var blur: Int = 0
    private var themeName: String = ""

    private val requestCodeBg = 3101

    private val selectImage by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                if (result.requestCode == requestCodeBg) {
                    setBgFromUri(uri) { path ->
                        bgImagePath = path
                        refreshBgImageViews()
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initState(savedInstanceState)
        initToolbar()
        initViews()
        initListeners()
        refreshSwatches()
    }

    private fun initState(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            isNight = savedInstanceState.getBoolean(KEY_IS_NIGHT)
            accent = savedInstanceState.getInt(KEY_ACCENT)
            bg = savedInstanceState.getInt(KEY_BG)
            bbg = savedInstanceState.getInt(KEY_BBG)
            bgImagePath = savedInstanceState.getString(KEY_BG_IMAGE)
            blur = savedInstanceState.getInt(KEY_BLUR)
            themeName = savedInstanceState.getString(KEY_THEME_NAME).orEmpty()
            return
        }
        isNight = if (mode == MODE_EDIT_CONFIG) {
            ThemeConfig.configList[configIndex].isNightTheme
        } else {
            requireArguments().getBoolean(ARG_IS_NIGHT)
        }
        when (mode) {
            MODE_EDIT_PREFS -> loadFromPrefs()
            MODE_EDIT_CONFIG -> loadFromConfig()
            MODE_NEW_CONFIG -> loadDefaults()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_IS_NIGHT, isNight)
        outState.putInt(KEY_ACCENT, accent)
        outState.putInt(KEY_BG, bg)
        outState.putInt(KEY_BBG, bbg)
        outState.putString(KEY_BG_IMAGE, bgImagePath)
        outState.putInt(KEY_BLUR, blur)
        outState.putString(KEY_THEME_NAME, themeName)
        super.onSaveInstanceState(outState)
    }

    private fun loadFromPrefs() = with(requireContext()) {
        val (defAccent, defBg, defBbg) = ThemeConfig.readDefaultColors(this, isNight)
        val accentKey = if (isNight) PreferKey.cNAccent else PreferKey.cAccent
        val bgKey = if (isNight) PreferKey.cNBackground else PreferKey.cBackground
        val bbgKey = if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground
        val bgImageKey = if (isNight) PreferKey.bgImageN else PreferKey.bgImage
        val blurKey = if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring
        accent = getPrefInt(accentKey).let { if (it == 0) defAccent else it }
        bg = getPrefInt(bgKey).let { if (it == 0) defBg else it }
        bbg = getPrefInt(bbgKey).let { if (it == 0) defBbg else it }
        bgImagePath = getPrefString(bgImageKey)
        blur = getPrefInt(blurKey, 0)
    }

    private fun loadFromConfig() {
        val config = ThemeConfig.configList[configIndex]
        accent = parseColor(config.accentColor)
        bg = parseColor(config.backgroundColor)
        bbg = parseColor(config.bottomBackground)
        themeName = config.themeName
    }

    private fun loadDefaults() {
        val (defAccent, defBg, defBbg) = ThemeConfig.readDefaultColors(requireContext(), isNight)
        accent = defAccent
        bg = defBg
        bbg = defBbg
    }

    private fun parseColor(hex: String): Int = try {
        hex.toColorInt()
    } catch (_: Exception) {
        0
    }

    private fun initToolbar() {
        titleBar!!.title = getString(
            when (mode) {
                MODE_EDIT_PREFS ->
                    if (isNight) R.string.customize_night_theme else R.string.customize_day_theme
                MODE_NEW_CONFIG -> R.string.new_theme
                else -> R.string.theme_customize_title
            }
        )
    }

    private fun initViews() {
        // 日/夜切换单选组：EDIT_PREFS 是从设置页明确入口进的, 不显示
        binding.rgMode.visibility =
            if (mode == MODE_EDIT_PREFS) View.GONE else View.VISIBLE
        binding.rgMode.check(if (isNight) R.id.rb_night else R.id.rb_day)

        // 主题名字段：EDIT_CONFIG / NEW_CONFIG 显示，EDIT_PREFS 隐藏
        binding.tilName.visibility =
            if (mode == MODE_EDIT_PREFS) View.GONE else View.VISIBLE
        binding.etName.setText(themeName)

        refreshBgImageViews()
    }

    private fun refreshBgImageViews() {
        // 背景图和模糊：仅 EDIT_PREFS 保留（Config 不含背景图字段）
        val showBgImage = mode == MODE_EDIT_PREFS
        binding.rowBgImage.visibility = if (showBgImage) View.VISIBLE else View.GONE
        binding.rowBlur.visibility = if (showBgImage) View.VISIBLE else View.GONE
        if (showBgImage) {
            val hasImage = !bgImagePath.isNullOrBlank()
            binding.tvBgImageSummary.text =
                if (hasImage) bgImagePath else getString(R.string.select_image)
            binding.ivBgImageClear.visibility =
                if (hasImage) View.VISIBLE else View.GONE
            binding.sbBlur.progress = blur
            binding.tvBlurValue.text = blur.toString()
        }
    }

    private fun initListeners() = binding.run {
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val newNight = checkedId == R.id.rb_night
            if (newNight != isNight) {
                onSwitchIsNight(newNight)
            }
        }
        rowAccent.setOnClickListener { showColorPicker(DIALOG_ID_ACCENT, accent) }
        rowBg.setOnClickListener { showColorPicker(DIALOG_ID_BG, bg) }
        rowBbg.setOnClickListener { showColorPicker(DIALOG_ID_BBG, bbg) }
        rowBgImage.setOnClickListener {
            selectImage.launch {
                requestCode = requestCodeBg
                this.mode = HandleFileContract.IMAGE
            }
        }
        ivBgImageClear.setOnClickListener {
            bgImagePath = null
            refreshBgImageViews()
        }
        sbBlur.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                blur = progress
                tvBlurValue.text = progress.toString()
            }
        })
        btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        btnOk.setOnClickListener { onSaveClicked() }
    }

    private fun onSwitchIsNight(newNight: Boolean) {
        isNight = newNight
        when (mode) {
            MODE_EDIT_PREFS -> {
                loadFromPrefs()
                refreshBgImageViews()
            }

            MODE_NEW_CONFIG -> {
                loadDefaults()
            }
            // EDIT_CONFIG 仅改 Config.isNightTheme, 颜色保留
        }
        refreshSwatches()
    }

    private fun refreshSwatches() = binding.run {
        cpvAccent.color = accent
        cpvBg.color = bg
        cpvBbg.color = bbg
    }

    private fun showColorPicker(dialogId: Int, seedColor: Int) {
        val dialog = ColorPickerDialog.newBuilder()
            .setDialogId(dialogId)
            .setColor(seedColor)
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_PRESETS)
            .create()
        dialog.setColorPickerDialogListener(this)
        childFragmentManager.beginTransaction()
            .add(dialog, "themeCustomize_$dialogId")
            .commitAllowingStateLoss()
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        val opaque = ColorUtils.withAlpha(color, 1f)
        when (dialogId) {
            DIALOG_ID_ACCENT -> {
                accent = opaque
            }

            DIALOG_ID_BG -> {
                if (!checkBgColor(opaque)) return
                bg = opaque
            }

            DIALOG_ID_BBG -> {
                bbg = opaque
            }
        }
        refreshSwatches()
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    private fun checkBgColor(color: Int): Boolean {
        if (!isNight && !ColorUtils.isColorLight(color)) {
            toastOnUi(R.string.day_background_too_dark)
            return false
        }
        if (isNight && ColorUtils.isColorLight(color)) {
            toastOnUi(R.string.night_background_too_light)
            return false
        }
        return true
    }

    @SuppressLint("SetTextI18n")
    private fun setBgFromUri(uri: Uri, success: (String) -> Unit) {
        val bgImageKey = if (isNight) PreferKey.bgImageN else PreferKey.bgImage
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = fileDoc.name.substringAfterLast(".")
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + ".$suffix"
                }
                file = FileUtils.createFileIfNotExist(file, bgImageKey, fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                success(file.absolutePath)
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun onSaveClicked() {
        // 保存前根据模式再校验一次背景色
        if (!checkBgColor(bg)) return
        when (mode) {
            MODE_EDIT_PREFS -> saveToPrefs()
            MODE_EDIT_CONFIG -> saveToConfig()
            MODE_NEW_CONFIG -> saveAsNewConfig()
        }
    }

    private fun saveToPrefs() = with(requireContext()) {
        val primaryKey = if (isNight) PreferKey.cNPrimary else PreferKey.cPrimary
        val accentKey = if (isNight) PreferKey.cNAccent else PreferKey.cAccent
        val bgKey = if (isNight) PreferKey.cNBackground else PreferKey.cBackground
        val bbgKey = if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground
        val bgImageKey = if (isNight) PreferKey.bgImageN else PreferKey.bgImage
        val blurKey = if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring
        putPrefInt(primaryKey, bg)
        putPrefInt(accentKey, accent)
        putPrefInt(bgKey, bg)
        putPrefInt(bbgKey, bbg)
        val bgPath = bgImagePath
        if (bgPath.isNullOrBlank()) {
            removePref(bgImageKey)
        } else {
            putPrefString(bgImageKey, bgPath)
        }
        putPrefInt(blurKey, blur)
        if (AppConfig.isNightTheme == isNight) {
            ThemeConfig.applyTheme(this)
            postEvent(EventBus.RECREATE, "")
        }
        dismissAllowingStateLoss()
    }

    private fun saveToConfig() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toastOnUi(R.string.theme_name)
            return
        }
        val list = ThemeConfig.configList
        if (configIndex !in list.indices) {
            dismissAllowingStateLoss()
            return
        }
        val old = list[configIndex]
        list[configIndex] = ThemeConfig.Config(
            themeName = name,
            isNightTheme = old.isNightTheme,
            primaryColor = "#${bg.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${bg.hexString}",
            bottomBackground = "#${bbg.hexString}"
        )
        ThemeConfig.save()
        dismissAllowingStateLoss()
    }

    private fun saveAsNewConfig() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toastOnUi(R.string.theme_name)
            return
        }
        ThemeConfig.addConfig(
            ThemeConfig.Config(
                themeName = name,
                isNightTheme = isNight,
                primaryColor = "#${bg.hexString}",
                accentColor = "#${accent.hexString}",
                backgroundColor = "#${bg.hexString}",
                bottomBackground = "#${bbg.hexString}"
            )
        )
        parentFragmentManager.setFragmentResult(RESULT_CONFIG_CHANGED, Bundle.EMPTY)
        dismissAllowingStateLoss()
    }
}
