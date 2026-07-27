package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.core.os.bundleOf
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.base.ComposeDialog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.preference.ColorPickerDialogContent
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.hexString
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 主题定制（迁 dialog_theme_customize.xml → Compose；取色正文原本已是 Compose）。
 * 三模式（改 prefs/改 Config/新建 Config）、日夜切换、色块/背景图/模糊、保存校验逐项等价。
 */
class ThemeCustomizeDialog() : BaseComposeDialogFragment() {

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

    private val mode by lazy { requireArguments().getInt(ARG_MODE) }
    private val configIndex by lazy { requireArguments().getInt(ARG_CONFIG_INDEX, -1) }

    private var isNight by mutableStateOf(false)
    private var accent by mutableIntStateOf(0)
    private var bg by mutableIntStateOf(0)
    private var bbg by mutableIntStateOf(0)
    private var bgImagePath by mutableStateOf<String?>(null)
    private var blur by mutableIntStateOf(0)
    private var themeName by mutableStateOf("")

    private val requestCodeBg = 3101

    private val selectImage by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                if (result.requestCode == requestCodeBg) {
                    setBgFromUri(uri) { path ->
                        bgImagePath = path
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initState(savedInstanceState)
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

    private fun loadFromPrefs() {
        val theme = ThemeConfig.readCustomTheme(requireContext(), isNight)
        accent = theme.accent
        bg = theme.background
        bbg = theme.bottomBackground
        bgImagePath = theme.bgImage
        blur = theme.bgImageBlur
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

    private fun titleText(): String = getString(
        when (mode) {
            MODE_EDIT_PREFS ->
                if (isNight) R.string.customize_night_theme else R.string.customize_day_theme

            MODE_NEW_CONFIG -> R.string.new_theme
            else -> R.string.theme_customize_title
        }
    )

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = titleText(),
                onBack = { dismissAllowingStateLoss() },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // 主题名字段：EDIT_CONFIG / NEW_CONFIG 显示，EDIT_PREFS 隐藏
                if (mode != MODE_EDIT_PREFS) {
                    AppOutlinedTextField(
                        value = themeName,
                        onValueChange = { themeName = it },
                        label = stringResource(R.string.theme_name),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 日/夜切换单选组：EDIT_PREFS 是从设置页明确入口进的, 不显示
                    Row(Modifier.fillMaxWidth()) {
                        ModeRadio(
                            text = stringResource(R.string.day),
                            selected = !isNight,
                            modifier = Modifier.weight(1f),
                        ) { switchIsNight(false) }
                        ModeRadio(
                            text = stringResource(R.string.night),
                            selected = isNight,
                            modifier = Modifier.weight(1f),
                        ) { switchIsNight(true) }
                    }
                }
                ColorRow(
                    label = stringResource(R.string.accent),
                    color = accent,
                ) { showColorPicker(DIALOG_ID_ACCENT, accent) }
                ColorRow(
                    label = stringResource(R.string.background_color),
                    color = bg,
                ) { showColorPicker(DIALOG_ID_BG, bg) }
                ColorRow(
                    label = stringResource(R.string.navbar_color),
                    color = bbg,
                ) { showColorPicker(DIALOG_ID_BBG, bbg) }
                // 背景图和模糊：仅 EDIT_PREFS 保留（Config 不含背景图字段）
                if (mode == MODE_EDIT_PREFS) {
                    val hasImage = !bgImagePath.isNullOrBlank()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                selectImage.launch {
                                    requestCode = requestCodeBg
                                    this.mode = HandleFileContract.IMAGE
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.background_image),
                            color = colors.primaryText,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (hasImage) bgImagePath.orEmpty()
                            else stringResource(R.string.select_image),
                            color = colors.secondaryText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.widthIn(max = 180.dp),
                        )
                        if (hasImage) {
                            IconButton(onClick = { bgImagePath = null }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_baseline_close),
                                    contentDescription = stringResource(R.string.delete),
                                    tint = colors.secondaryText,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.background_image_blurring),
                            color = colors.primaryText,
                            fontSize = 15.sp,
                        )
                        AppSlider(
                            value = blur,
                            max = 25,
                            onValueChange = { blur = it },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = blur.toString(),
                            color = colors.primaryText,
                            fontSize = 15.sp,
                            modifier = Modifier.widthIn(min = 32.dp),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = stringResource(R.string.cancel)) {
                        dismissAllowingStateLoss()
                    }
                    Spacer(Modifier.width(8.dp))
                    AppTextButton(text = stringResource(R.string.ok)) { onSaveClicked() }
                }
            }
        }
    }

    @Composable
    private fun ModeRadio(
        text: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Row(
            modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppRadioButton(selected = selected, onClick = null)
            Text(text, color = AppTheme.colors.primaryText, fontSize = 15.sp)
        }
    }

    /** 复刻 ColorPanelView circle 形态：色块圆 + 1dp 灰描边 */
    @Composable
    private fun ColorRow(label: String, color: Int, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = AppTheme.colors.primaryText,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(30.dp)
                    .background(Color(color), CircleShape)
                    .border(1.dp, Color(0xFF6E6E6E), CircleShape),
            )
        }
    }

    private fun switchIsNight(newNight: Boolean) {
        if (newNight == isNight) return
        isNight = newNight
        when (mode) {
            MODE_EDIT_PREFS -> {
                loadFromPrefs()
            }

            MODE_NEW_CONFIG -> {
                loadDefaults()
            }
            // EDIT_CONFIG 仅改 Config.isNightTheme, 颜色保留
        }
    }

    private fun showColorPicker(dialogId: Int, seedColor: Int) {
        val titleRes = when (dialogId) {
            DIALOG_ID_ACCENT -> R.string.accent
            DIALOG_ID_BG -> R.string.background_color
            else -> R.string.navbar_color
        }
        val dialog = ComposeDialog(requireContext(), applyFilletBackground = false)
        dialog.setComposeContent {
            ColorPickerDialogContent(
                initColor = seedColor,
                title = getString(titleRes),
                onDismissRequest = { dialog.dismiss() },
                onConfirm = { color -> onColorSelected(dialogId, color) },
            )
        }
        dialog.show()
    }

    private fun onColorSelected(dialogId: Int, color: Int) {
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
    }

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

    private fun saveToPrefs() {
        ThemeConfig.saveCustomTheme(
            requireContext(), isNight,
            ThemeConfig.CustomTheme(accent, bg, bbg, bgImagePath, blur)
        )
        if (AppConfig.isNightTheme == isNight) {
            ThemeConfig.applyTheme(requireContext())
            postEvent(EventBus.RECREATE, "")
        }
        dismissAllowingStateLoss()
    }

    private fun saveToConfig() {
        val name = themeName.trim()
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
        val name = themeName.trim()
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
