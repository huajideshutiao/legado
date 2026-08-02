package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ThemeConfigData
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.preference.ColorPickerDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FlowBus
import io.legado.app.utils.hexString
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.accent
import legado.shared.generated.resources.background_color
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.customize_day_theme
import legado.shared.generated.resources.customize_night_theme
import legado.shared.generated.resources.day
import legado.shared.generated.resources.day_background_too_dark
import legado.shared.generated.resources.navbar_color
import legado.shared.generated.resources.new_theme
import legado.shared.generated.resources.night
import legado.shared.generated.resources.night_background_too_light
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.theme_customize_title
import legado.shared.generated.resources.theme_name
import org.jetbrains.compose.resources.stringResource

/**
 * 主题定制对话框 (shared Compose 重建, 对照 app 端 ThemeCustomizeDialog)。
 *
 * 三模式逐项等价:
 * - [MODE_EDIT_PREFS]: 直接改日/夜自定义色 pref (含默认色回落);
 * - [MODE_EDIT_CONFIG]: 按索引替换配置 (主题名可改, 不改夜间标记);
 * - [MODE_NEW_CONFIG]: 按主题名新增/覆盖配置。
 *
 * 背景图/模糊字段不下沉 (桌面端无导入背景图 UI), 其余交互
 * (三色取色盘/日夜切换/保存前背景色校验/空名校验) 与 app 端一致。
 */
@Composable
fun ThemeCustomizeDialog(
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
    mode: Int,
    configIndex: Int = -1,
    initIsNight: Boolean = false,
) {
    val prefs = remember { PreferenceProviders.get() }
    val appConfig = remember { AppConfigProviders.get() }
    val themeStore = LocalThemeStoreProvider.current
    val eventBus = LocalEventBusProvider.current
    val colors = AppTheme.colors

    // 初始状态 (对照 app 端 initState 无 savedInstanceState 分支)
    data class Init(
        val isNight: Boolean,
        val accent: Int,
        val bg: Int,
        val bbg: Int,
        val themeName: String,
    )

    val initial = remember(mode, configIndex, initIsNight) {
        when (mode) {
            MODE_EDIT_PREFS -> {
                val (a, b, bb) = readCustomColors(prefs, initIsNight)
                Init(initIsNight, a, b, bb, "")
            }

            MODE_EDIT_CONFIG -> {
                val list = ThemeConfigProviders.get().getConfigList()
                if (configIndex !in list.indices) null
                else list[configIndex].let { c ->
                    Init(
                        isNight = c.isNightTheme,
                        accent = parseColor(c.accentColor),
                        bg = parseColor(c.backgroundColor),
                        bbg = parseColor(c.bottomBackground),
                        themeName = c.themeName,
                    )
                }
            }

            else -> {
                val (a, b, bb) = defaultColors(initIsNight)
                Init(initIsNight, a, b, bb, "")
            }
        }
    }
    if (initial == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var isNight by remember { mutableStateOf(initial.isNight) }
    var accent by remember { mutableIntStateOf(initial.accent) }
    var bg by remember { mutableIntStateOf(initial.bg) }
    var bbg by remember { mutableIntStateOf(initial.bbg) }
    var themeName by remember { mutableStateOf(initial.themeName) }
    // 取色盘目标: 1=accent 2=bg 3=bbg (对照 app 端 DIALOG_ID_*)
    var pickerDialogId by remember { mutableStateOf<Int?>(null) }

    // 校验提示文案: 非 Composable 局部函数里不能调 stringResource, 先在此取好
    val themeNameMsg = stringResource(Res.string.theme_name)
    val dayTooDarkMsg = stringResource(Res.string.day_background_too_dark)
    val nightTooLightMsg = stringResource(Res.string.night_background_too_light)

    // 对照 app 端 saveAsNewConfig 的 setFragmentResult(RESULT_CONFIG_CHANGED):
    // 通知下方主题列表刷新 (桌面 overlay 场景 ThemeListDialog collect 后重取列表)
    fun notifyConfigChanged() {
        FlowBus.with(EventBus.THEME_CONFIG_CHANGED).tryEmit("")
    }

    fun loadFromPrefs() {
        val (a, b, bb) = readCustomColors(prefs, isNight)
        accent = a; bg = b; bbg = bb
    }

    fun loadDefaults() {
        val (a, b, bb) = defaultColors(isNight)
        accent = a; bg = b; bbg = bb
    }

    fun switchIsNight(newNight: Boolean) {
        if (newNight == isNight) return
        isNight = newNight
        when (mode) {
            MODE_EDIT_PREFS -> loadFromPrefs()
            MODE_NEW_CONFIG -> loadDefaults()
            // EDIT_CONFIG 仅改 Config.isNightTheme, 颜色保留
        }
    }

    fun checkBgColor(color: Int): Boolean {
        if (!isNight && !ColorUtils.isColorLight(color)) {
            onToast(dayTooDarkMsg)
            return false
        }
        if (isNight && ColorUtils.isColorLight(color)) {
            onToast(nightTooLightMsg)
            return false
        }
        return true
    }

    fun onColorSelected(dialogId: Int, color: Int) {
        val opaque = ColorUtils.withAlpha(color, 1f)
        when (dialogId) {
            DIALOG_ID_ACCENT -> accent = opaque
            DIALOG_ID_BG -> {
                if (checkBgColor(opaque)) bg = opaque
            }

            DIALOG_ID_BBG -> bbg = opaque
        }
    }

    fun saveToPrefs() {
        if (isNight) {
            prefs.putInt(PreferKey.cNPrimary, bg)
            prefs.putInt(PreferKey.cNAccent, accent)
            prefs.putInt(PreferKey.cNBackground, bg)
            prefs.putInt(PreferKey.cNBBackground, bbg)
        } else {
            prefs.putInt(PreferKey.cPrimary, bg)
            prefs.putInt(PreferKey.cAccent, accent)
            prefs.putInt(PreferKey.cBackground, bg)
            prefs.putInt(PreferKey.cBBackground, bbg)
        }
        // 对照原版: 仅当编辑的是当前生效模式才应用 + 重建
        if (appConfig.isNightTheme == isNight) {
            themeStore.applyColors(Color(accent), Color(bg), Color(bbg), isNight)
            eventBus.emitRecreate()
        }
        onDismiss()
    }

    fun saveToConfig() {
        val name = themeName.trim()
        if (name.isEmpty()) {
            onToast(themeNameMsg)
            return
        }
        val list = ThemeConfigProviders.get().getConfigList()
        if (configIndex !in list.indices) {
            onDismiss()
            return
        }
        // 对照原版 saveToConfig: 保留 old.isNightTheme, 仅更新名字与三色
        ThemeConfigProviders.get().replaceConfig(
            configIndex,
            ThemeConfigData(
                themeName = name,
                isNightTheme = list[configIndex].isNightTheme,
                primaryColor = "#${bg.hexString}",
                accentColor = "#${accent.hexString}",
                backgroundColor = "#${bg.hexString}",
                bottomBackground = "#${bbg.hexString}",
            ),
        )
        notifyConfigChanged()
        onDismiss()
    }

    fun saveAsNewConfig() {
        val name = themeName.trim()
        if (name.isEmpty()) {
            onToast(themeNameMsg)
            return
        }
        ThemeConfigProviders.get().addConfig(
            ThemeConfigData(
                themeName = name,
                isNightTheme = isNight,
                primaryColor = "#${bg.hexString}",
                accentColor = "#${accent.hexString}",
                backgroundColor = "#${bg.hexString}",
                bottomBackground = "#${bbg.hexString}",
            ),
        )
        notifyConfigChanged()
        onDismiss()
    }

    val titleText = stringResource(
        when (mode) {
            MODE_EDIT_PREFS ->
                if (isNight) Res.string.customize_night_theme else Res.string.customize_day_theme

            MODE_NEW_CONFIG -> Res.string.new_theme
            else -> Res.string.theme_customize_title
        }
    )

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Column(Modifier.appDialogSize(fullHeight = true)) {
            DialogTitleBar(
                title = titleText,
                onBack = onDismiss,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // 主题名字段: EDIT_CONFIG / NEW_CONFIG 显示, EDIT_PREFS 隐藏
                if (mode != MODE_EDIT_PREFS) {
                    AppOutlinedTextField(
                        value = themeName,
                        onValueChange = { themeName = it },
                        label = stringResource(Res.string.theme_name),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 日/夜切换单选组: EDIT_PREFS 是从设置页明确入口进的, 不显示
                    Row(Modifier.fillMaxWidth()) {
                        ModeRadio(
                            text = stringResource(Res.string.day),
                            selected = !isNight,
                            modifier = Modifier.weight(1f),
                        ) { switchIsNight(false) }
                        ModeRadio(
                            text = stringResource(Res.string.night),
                            selected = isNight,
                            modifier = Modifier.weight(1f),
                        ) { switchIsNight(true) }
                    }
                }
                ColorRow(
                    label = stringResource(Res.string.accent),
                    color = accent,
                ) { pickerDialogId = DIALOG_ID_ACCENT }
                ColorRow(
                    label = stringResource(Res.string.background_color),
                    color = bg,
                ) { pickerDialogId = DIALOG_ID_BG }
                ColorRow(
                    label = stringResource(Res.string.navbar_color),
                    color = bbg,
                ) { pickerDialogId = DIALOG_ID_BBG }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = stringResource(Res.string.cancel), onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    AppTextButton(text = stringResource(Res.string.ok)) {
                        when (mode) {
                            MODE_EDIT_PREFS -> saveToPrefs()
                            MODE_EDIT_CONFIG -> saveToConfig()
                            else -> saveAsNewConfig()
                        }
                    }
                }
            }
        }
    }

    // 取色盘 (对照 app 端 showColorPicker: 标题按目标取色名称)
    val pickerTitle = when (pickerDialogId) {
        DIALOG_ID_ACCENT -> stringResource(Res.string.accent)
        DIALOG_ID_BG -> stringResource(Res.string.background_color)
        else -> stringResource(Res.string.navbar_color)
    }
    pickerDialogId?.let { dialogId ->
        val seed = when (dialogId) {
            DIALOG_ID_ACCENT -> accent
            DIALOG_ID_BG -> bg
            else -> bbg
        }
        ColorPickerDialog(
            initColor = seed,
            title = pickerTitle,
            onDismissRequest = { pickerDialogId = null },
            onConfirm = { color -> onColorSelected(dialogId, color) },
        )
    }
}

/** 对照 app 端 ThemeCustomizeDialog.MODE_* */
const val MODE_EDIT_PREFS = 0
const val MODE_EDIT_CONFIG = 1
const val MODE_NEW_CONFIG = 2

private const val DIALOG_ID_ACCENT = 1
private const val DIALOG_ID_BG = 2
private const val DIALOG_ID_BBG = 3

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

/** 复刻 ColorPanelView circle 形态: 色块圆 + 1dp 灰描边 */
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
        Box(
            Modifier
                .size(30.dp)
                .background(Color(color), CircleShape)
                .border(DesignTokens.strokeThin, Color(0xFF6E6E6E), CircleShape),
        )
    }
}

/**
 * 读日/夜自定义色 pref, 未设置 (0) 回落默认色
 * (对照 app 端 ThemeConfig.readCustomTheme 的 getPrefInt 回落 readDefaultColors)。
 */
private fun readCustomColors(prefs: io.legado.app.help.config.PreferenceProvider, isNight: Boolean):
    Triple<Int, Int, Int> {
    val (defAccent, defBg, defBbg) = defaultColors(isNight)
    val accent = prefs.getInt(if (isNight) PreferKey.cNAccent else PreferKey.cAccent)
        .let { if (it == 0) defAccent else it }
    val bg = prefs.getInt(if (isNight) PreferKey.cNBackground else PreferKey.cBackground)
        .let { if (it == 0) defBg else it }
    val bbg = prefs.getInt(if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground)
        .let { if (it == 0) defBbg else it }
    return Triple(accent, bg, bbg)
}

/** 默认三色 (对照 app 端 ThemeConfig.readDefaultColors, 取内置配置的日/夜条目色值) */
private fun defaultColors(isNight: Boolean): Triple<Int, Int, Int> {
    val builtin = runCatching { ThemeConfigProviders.get().getBuiltinConfigs() }
        .getOrDefault(emptyList())
    val config = builtin.firstOrNull { it.isNightTheme == isNight }
    return if (config != null) {
        Triple(
            parseColor(config.accentColor),
            parseColor(config.backgroundColor),
            parseColor(config.bottomBackground),
        )
    } else {
        Triple(0, 0, 0)
    }
}

private fun parseColor(hex: String): Int = runCatching { ColorUtils.parseColor(hex) }.getOrDefault(0)
