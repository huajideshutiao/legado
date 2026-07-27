package io.legado.desktop.ui.config

import androidx.compose.foundation.layout.widthIn
import io.legado.desktop.ui.component.DialogSizes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import io.legado.app.help.config.ThemeConfigData
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.preference.ColorPickerDialogContent
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.hexString
import io.legado.app.utils.toJson

/**
 * 桌面端"主题自定义" Compose Dialog (对照 app 端 [io.legado.app.ui.config.ThemeCustomizeDialog])。
 *
 * # 背景
 *
 * 对照 app 端 `ThemeCustomizeDialog : BaseComposeDialogFragment`, 桌面端无
 * Fragment/Activity, 改为纯 Compose [Dialog] (参考 [DirectLinkUploadConfigDialog] 模式)。
 * 三模式 + 字段 + 校验 + 保存逻辑与 app 端逐项对齐, 仅做平台适配。
 *
 * # 三模式 (与 app 端一致)
 *
 * - [ModeEditPrefs]: 编辑当前主题色 (从 prefs 读 `desktop.theme.current`),
 *   保存时写回 prefs + 应用到 [DesktopThemeStoreProvider] + emit recreate;
 * - [ModeEditConfig]: 编辑已保存的自定义主题 (从 prefs `desktop.theme.customList` 第 [configIndex] 项读),
 *   保存时替换该项;
 * - [ModeNewConfig]: 新建自定义主题, 保存时追加到 `desktop.theme.customList`。
 *
 * # 字段 (与 app 端一致, 桌面端去掉背景图/模糊)
 *
 * - accent / bg / bbg: 强调色 / 背景色 / 底栏色 (3 个色块, 点击进 [ColorPickerDialogContent])
 * - themeName: 主题名 (EDIT_CONFIG / NEW_CONFIG 显示, EDIT_PREFS 隐藏)
 * - isNight: 日/夜单选 (EDIT_CONFIG / NEW_CONFIG 显示, EDIT_PREFS 隐藏)
 *
 * # 校验 (与 app 端一致)
 *
 * - 日间背景不能太暗 ([ColorUtils.isColorLight] == false 时拒绝)
 * - 夜间背景不能太亮 ([ColorUtils.isColorLight] == true 时拒绝)
 * - EDIT_CONFIG / NEW_CONFIG 保存前 themeName 不能为空
 *
 * # 平台适配 (与 app 端差异)
 *
 * - **持久化**: app 端 `ThemeConfig.saveCustomTheme/save/addConfig` (走 SharedPreferences +
 *   themeConfig.json) → 桌面端 [LocalPreferenceStoreProvider] 内存 Map (key:
 *   `desktop.theme.current` 单对象 JSON / `desktop.theme.customList` 数组 JSON)
 * - **应用主题**: app 端 `ThemeConfig.applyTheme + postEvent(RECREATE)` → 桌面端
 *   [DesktopThemeStoreProvider.applyColors] + [DesktopEventBusProvider.emitRecreate]
 * - **取色对话框**: app 端命令式 `ComposeDialog + ColorPickerDialogContent` → 桌面端
 *   声明式 `var showColorPicker by mutableStateOf` + [ColorPickerDialogContent]
 *   (shared/sharedUiMain 已下沉, 复用 [ColorPickerDialogContent] 正文)
 * - **Toast**: app 端 `toastOnUi` → 桌面端 [Toasters.get].toast
 * - **文案**: app 端 `R.string.xxx` → 桌面端 [rememberString]`("xxx")`
 * - **背景图/模糊**: 桌面端不支持, 移除 (与 [DesktopThemeStoreProvider.bgImagePath] 恒 null 对齐)
 * - **primaryColor**: 与 app 端一致, 保存为 `"#${bg.hexString}"` (与 backgroundColor 相同)
 *
 * # UI 结构 (对照 app 端, padding 值原样保留)
 *
 * - [DialogTitleBar] (标题 + 返回)
 * - [Column] (verticalScroll, padding horizontal=16.dp):
 *     [AppOutlinedTextField] themeName (EDIT_CONFIG/NEW_CONFIG)
 *     + [Row] 日/夜单选 (EDIT_CONFIG/NEW_CONFIG)
 *     + 3 × [ColorRow] (accent / bg / bbg)
 * - 底部 [Row] (Spacer + 取消 + 确定)
 *
 * @param mode 模式 (EDIT_PREFS / EDIT_CONFIG / NEW_CONFIG)
 * @param isNight 初始日/夜 (EDIT_PREFS / NEW_CONFIG 用)
 * @param configIndex 编辑配置索引 (EDIT_CONFIG 用)
 * @param onDismiss 关闭回调
 */
@Composable
fun ThemeCustomizeDialog(
    mode: Int,
    isNight: Boolean = false,
    configIndex: Int = -1,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val pref = LocalPreferenceStoreProvider.current
    // themeStore / eventBus: 在 @Composable 上下文取一次, 供非 Composable 的 onSaveClicked 内
    // 调 applyThemeToStore (避免 applyThemeToStore 标 @Composable 导致只能在 Composable 调用)
    val (themeStore, eventBus) = rememberThemeStoreAndEventBus()

    // 文案 (rememberString 是 @Composable, 顶层缓存后供各 lambda 引用)
    val accentLabel = rememberString("accent")
    val bgLabel = rememberString("background_color")
    val bbgLabel = rememberString("navbar_color")
    val themeNameLabel = rememberString("theme_name")
    val dayLabel = rememberString("day")
    val nightLabel = rememberString("night")
    val cancelLabel = rememberString("cancel")
    val okLabel = rememberString("ok")
    val dayBgTooDarkLabel = rememberString("day_background_too_dark")
    val nightBgTooLightLabel = rememberString("night_background_too_light")

    // 标题 (对照 app 端 titleText())
    val titleText = when (mode) {
        ModeEditPrefs -> if (isNight) rememberString("customize_night_theme")
        else rememberString("customize_day_theme")
        ModeNewConfig -> rememberString("new_theme")
        else -> rememberString("theme_customize_title")
    }

    // 表单初始值 (用 remember 计算一次, 避免 mutableStateOf 初始值在重组时重置)
    // 对照 app 端 initState/loadFromPrefs/loadFromConfig/loadDefaults
    val initial = remember(mode, isNight, configIndex) {
        computeInitialState(mode, isNight, configIndex, pref)
    }

    // 表单 state (初始值取自 initial, 后续由用户交互修改)
    var nightMode by remember { mutableStateOf(initial.nightMode) }
    var accent by remember { mutableIntStateOf(initial.accent) }
    var bg by remember { mutableIntStateOf(initial.bg) }
    var bbg by remember { mutableIntStateOf(initial.bbg) }
    var themeName by remember { mutableStateOf(initial.themeName) }

    // 取色对话框显隐状态 (对照 app 端 showColorPicker)
    var colorPickerDialogId by remember { mutableIntStateOf(0) }
    var colorPickerSeed by remember { mutableIntStateOf(0) }

    // 校验: 日间背景不能太暗 / 夜间背景不能太亮 (对照 app 端 checkBgColor)
    fun checkBgColor(color: Int): Boolean {
        if (!nightMode && !ColorUtils.isColorLight(color)) {
            Toasters.get().toast(dayBgTooDarkLabel)
            return false
        }
        if (nightMode && ColorUtils.isColorLight(color)) {
            Toasters.get().toast(nightBgTooLightLabel)
            return false
        }
        return true
    }

    fun onColorSelected(dialogId: Int, color: Int) {
        val opaque = ColorUtils.withAlpha(color, 1f)
        when (dialogId) {
            DialogIdAccent -> accent = opaque
            DialogIdBg -> {
                if (!checkBgColor(opaque)) return
                bg = opaque
            }
            DialogIdBbg -> bbg = opaque
        }
    }

    fun onSaveClicked() {
        // 保存前根据模式再校验一次背景色 (对照 app 端 onSaveClicked)
        if (!checkBgColor(bg)) return
        when (mode) {
            ModeEditPrefs -> {
                val config = ThemeConfigData(
                    themeName = if (nightMode) "nightCustom" else "dayCustom",
                    isNightTheme = nightMode,
                    primaryColor = "#${bg.hexString}",
                    accentColor = "#${accent.hexString}",
                    backgroundColor = "#${bg.hexString}",
                    bottomBackground = "#${bbg.hexString}",
                )
                // 持久化到 prefs
                pref.putString(ThemePrefKeys.CURRENT, GSON.toJson(config))
                // 应用到 themeStore + emit recreate
                applyThemeToStore(themeStore, eventBus, config)
                onDismiss()
            }
            ModeEditConfig -> {
                val name = themeName.trim()
                if (name.isEmpty()) {
                    Toasters.get().toast(themeNameLabel)
                    return
                }
                val list = readCustomList(pref).toMutableList()
                if (configIndex !in list.indices) {
                    onDismiss()
                    return
                }
                val old = list[configIndex]
                list[configIndex] = ThemeConfigData(
                    themeName = name,
                    isNightTheme = old.isNightTheme,
                    primaryColor = "#${bg.hexString}",
                    accentColor = "#${accent.hexString}",
                    backgroundColor = "#${bg.hexString}",
                    bottomBackground = "#${bbg.hexString}",
                )
                pref.putString(ThemePrefKeys.CUSTOM_LIST, GSON.toJson(list))
                onDismiss()
            }
            ModeNewConfig -> {
                val name = themeName.trim()
                if (name.isEmpty()) {
                    Toasters.get().toast(themeNameLabel)
                    return
                }
                val list = readCustomList(pref).toMutableList()
                list.add(
                    ThemeConfigData(
                        themeName = name,
                        isNightTheme = nightMode,
                        primaryColor = "#${bg.hexString}",
                        accentColor = "#${accent.hexString}",
                        backgroundColor = "#${bg.hexString}",
                        bottomBackground = "#${bbg.hexString}",
                    )
                )
                pref.putString(ThemePrefKeys.CUSTOM_LIST, GSON.toJson(list))
                onDismiss()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.background,
            modifier = Modifier.fillMaxWidth().widthIn(max = DialogSizes.dialogMaxWidth()),
        ) {
            Column(Modifier.fillMaxWidth()) {
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
                    // themeName 字段 + 日/夜单选: EDIT_CONFIG / NEW_CONFIG 显示, EDIT_PREFS 隐藏
                    if (mode != ModeEditPrefs) {
                        AppOutlinedTextField(
                            value = themeName,
                            onValueChange = { themeName = it },
                            label = themeNameLabel,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            ModeRadio(
                                text = dayLabel,
                                selected = !nightMode,
                                modifier = Modifier.weight(1f),
                            ) {
                                // 切日夜时重新加载默认色 (对照 app 端 switchIsNight)
                                if (nightMode) {
                                    nightMode = false
                                    if (mode == ModeNewConfig) {
                                        accent = 0xFF165DFF.toInt()
                                        bg = 0xFFFFFFFF.toInt()
                                        bbg = 0xFFF7F8FA.toInt()
                                    }
                                }
                            }
                            ModeRadio(
                                text = nightLabel,
                                selected = nightMode,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (!nightMode) {
                                    nightMode = true
                                    if (mode == ModeNewConfig) {
                                        accent = 0xFF165DFF.toInt()
                                        bg = 0xFF121212.toInt()
                                        bbg = 0xFF1F1F1F.toInt()
                                    }
                                }
                            }
                        }
                    }
                    // 3 个色块行 (对照 app 端 ColorRow)
                    ColorRow(
                        label = accentLabel,
                        color = accent,
                    ) {
                        colorPickerDialogId = DialogIdAccent
                        colorPickerSeed = accent
                    }
                    ColorRow(
                        label = bgLabel,
                        color = bg,
                    ) {
                        colorPickerDialogId = DialogIdBg
                        colorPickerSeed = bg
                    }
                    ColorRow(
                        label = bbgLabel,
                        color = bbg,
                    ) {
                        colorPickerDialogId = DialogIdBbg
                        colorPickerSeed = bbg
                    }
                    // 底部按钮行 (对照 app 端)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        AppTextButton(text = cancelLabel) { onDismiss() }
                        Spacer(Modifier.width(8.dp))
                        AppTextButton(text = okLabel) { onSaveClicked() }
                    }
                }
            }
        }
    }

    // 取色对话框 (对照 app 端 showColorPicker, 声明式触发)
    if (colorPickerDialogId != 0) {
        val titleRes = when (colorPickerDialogId) {
            DialogIdAccent -> accentLabel
            DialogIdBg -> bgLabel
            else -> bbgLabel
        }
        ColorPickerDialogContent(
            initColor = colorPickerSeed,
            title = titleRes,
            onDismissRequest = { colorPickerDialogId = 0 },
            onConfirm = { color ->
                onColorSelected(colorPickerDialogId, color)
                colorPickerDialogId = 0
            },
        )
    }
}

/** 复刻 app 端 ColorRow: 标签 + 圆形色块 (1dp 灰描边) */
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
                .border(1.dp, Color(0xFF6E6E6E), CircleShape),
        )
    }
}

/** 复刻 app 端 ModeRadio: 单选按钮 + 文本 */
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

// ---- 内部常量与工具函数 (与 ThemeListDialog 共享) ----

/** 模式: 编辑当前主题 / 编辑已保存配置 / 新建配置 (对照 app 端 MODE_*) */
internal const val ModeEditPrefs = 0
internal const val ModeEditConfig = 1
internal const val ModeNewConfig = 2

/** 取色对话框 ID (对照 app 端 DIALOG_ID_*) */
private const val DialogIdAccent = 1
private const val DialogIdBg = 2
private const val DialogIdBbg = 3

/** prefs key: 当前主题 (单对象 JSON) / 自定义主题列表 (数组 JSON) */
internal object ThemePrefKeys {
    const val CURRENT = "desktop.theme.current"
    const val CUSTOM_LIST = "desktop.theme.customList"
}

/** 从 prefs 读取自定义主题列表, 解析失败返回空列表 */
internal fun readCustomList(pref: PreferenceStoreProvider): List<ThemeConfigData> {
    val json = pref.getString(ThemePrefKeys.CUSTOM_LIST) ?: return emptyList()
    return runCatching {
        GSON.fromJsonArray<ThemeConfigData>(json).getOrThrow()
    }.getOrDefault(emptyList())
}

/** ThemeCustomizeDialog 表单初始值 (5 字段, 由 [computeInitialState] 计算) */
private data class ThemeCustomizeInitialState(
    val nightMode: Boolean,
    val accent: Int,
    val bg: Int,
    val bbg: Int,
    val themeName: String,
)

/**
 * 计算 ThemeCustomizeDialog 表单初始值 (对照 app 端 initState + loadFromPrefs/loadFromConfig/loadDefaults)。
 *
 * - [ModeEditPrefs]: 从 prefs `desktop.theme.current` 读当前主题; 未设置时用 arcoblue 浅色默认值
 * - [ModeEditConfig]: 从 prefs `desktop.theme.customList` 第 [configIndex] 项读
 * - [ModeNewConfig]: 浅色/深色 arcoblue 默认色 (对照 app 端 readDefaultColors)
 */
private fun computeInitialState(
    mode: Int,
    isNight: Boolean,
    configIndex: Int,
    pref: PreferenceStoreProvider,
): ThemeCustomizeInitialState {
    return when (mode) {
        ModeEditPrefs -> {
            // 从 prefs 读当前主题; 未设置时用 arcoblue 浅色默认值
            val current = pref.getString(ThemePrefKeys.CURRENT)
            val config = current?.let {
                runCatching { GSON.fromJsonObject<ThemeConfigData>(it).getOrThrow() }.getOrNull()
            }
            if (config != null) {
                ThemeCustomizeInitialState(
                    nightMode = config.isNightTheme,
                    accent = ColorUtils.parseColor(config.accentColor),
                    bg = ColorUtils.parseColor(config.backgroundColor),
                    bbg = ColorUtils.parseColor(config.bottomBackground),
                    themeName = "",
                )
            } else {
                // 默认值: 浅色 arcoblue (与 DesktopThemeStoreProvider 初始值对齐)
                ThemeCustomizeInitialState(
                    nightMode = false,
                    accent = 0xFF165DFF.toInt(),
                    bg = 0xFFFFFFFF.toInt(),
                    bbg = 0xFFF7F8FA.toInt(),
                    themeName = "",
                )
            }
        }
        ModeEditConfig -> {
            val list = readCustomList(pref)
            if (configIndex in list.indices) {
                val c = list[configIndex]
                ThemeCustomizeInitialState(
                    nightMode = c.isNightTheme,
                    accent = ColorUtils.parseColor(c.accentColor),
                    bg = ColorUtils.parseColor(c.backgroundColor),
                    bbg = ColorUtils.parseColor(c.bottomBackground),
                    themeName = c.themeName,
                )
            } else {
                // index 越界兜底 (理论上不会走到, 调用方应保证 index 有效)
                ThemeCustomizeInitialState(
                    nightMode = isNight,
                    accent = 0xFF165DFF.toInt(),
                    bg = if (isNight) 0xFF121212.toInt() else 0xFFFFFFFF.toInt(),
                    bbg = if (isNight) 0xFF1F1F1F.toInt() else 0xFFF7F8FA.toInt(),
                    themeName = "",
                )
            }
        }
        ModeNewConfig -> {
            // 默认值 (对照 app 端 readDefaultColors: 浅色 arcoblue / 深色 #1F1F1F)
            ThemeCustomizeInitialState(
                nightMode = isNight,
                accent = 0xFF165DFF.toInt(),
                bg = if (isNight) 0xFF121212.toInt() else 0xFFFFFFFF.toInt(),
                bbg = if (isNight) 0xFF1F1F1F.toInt() else 0xFFF7F8FA.toInt(),
                themeName = "",
            )
        }
        else -> ThemeCustomizeInitialState(false, 0, 0, 0, "")
    }
}

/**
 * 应用主题到 [DesktopThemeStoreProvider] + emit recreate (供 ThemeCustomizeDialog /
 * ThemeListDialog 共用)。
 *
 * 非Composable: 调用方需在 @Composable 上下文中先通过 [LocalThemeStoreProvider] /
 * [LocalEventBusProvider] 取得 themeStore / eventBus, 再传入本函数。
 */
internal fun applyThemeToStore(
    themeStore: io.legado.app.ui.compose.platform.ThemeStoreProvider,
    eventBus: io.legado.app.ui.compose.platform.EventBusProvider,
    config: ThemeConfigData,
) {
    val accent = Color(ColorUtils.parseColor(config.accentColor))
    val bg = Color(ColorUtils.parseColor(config.backgroundColor))
    val bbg = Color(ColorUtils.parseColor(config.bottomBackground))
    (themeStore as? DesktopThemeStoreProvider)?.applyColors(accent, bg, bbg, config.isNightTheme)
    (eventBus as? DesktopEventBusProvider)?.emitRecreate()
}

/** 在 @Composable 上下文中取得 themeStore / eventBus, 供非 Composable 的 [applyThemeToStore] 使用 */
@Composable
internal fun rememberThemeStoreAndEventBus(): Pair<
        io.legado.app.ui.compose.platform.ThemeStoreProvider,
        io.legado.app.ui.compose.platform.EventBusProvider> {
    return LocalThemeStoreProvider.current to LocalEventBusProvider.current
}
