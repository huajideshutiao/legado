package io.legado.app.help.config

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.ThemeStorePrefKeys
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FlowBus

/**
 * 文件持久化版 [ThemeConfigProvider] (commonMain, 桌面/iOS/鸿蒙可共用)。
 *
 * 对照 app 端原版 `ThemeConfig` object:
 * - configList 持久化经 [ThemeConfigStore] (与 app 端同一份实现, 路径/格式互通);
 * - applyConfig/applyBuiltin 等价原版 applyConfigToPrefs + `AppConfig.isNightTheme = x` +
 *   applyTheme (→ ThemeStore.saveTheme) + postEvent(RECREATE)。
 *
 * 所有磁盘/prefs 访问懒执行, 构造时不触碰 [ThemeConfigStore] (注册顺序无关)。
 */
class FileThemeConfigProvider : ThemeConfigProvider {

    /** 存档库: 仅存储用户自定义/导入的主题 (对照原版 configList lazy 读盘) */
    private val configs: MutableList<ThemeConfigData> by lazy {
        ThemeConfigStore.load().toMutableList()
    }

    override fun getConfigList(): List<ThemeConfigData> = configs.toList()

    /** 对照原版 addConfig: 校验色值 + 同名覆盖(只改内存) / 追加后 save */
    override fun addConfig(config: ThemeConfigData) {
        if (!ThemeConfigStore.validate(config)) return
        val index = configs.indexOfFirst { it.themeName == config.themeName }
        if (index >= 0) {
            configs[index] = config
            save()
            return
        }
        configs.add(config)
        save()
    }

    /** 对照原版 delConfig: removeAt + save (原版随后的 applyTheme 不改当前色, 此处省略) */
    override fun delConfig(index: Int) {
        if (index !in configs.indices) return
        configs.removeAt(index)
        save()
    }

    /** 对照 ThemeCustomizeDialog.saveToConfig: 按索引替换 + save (主题改名不残留旧条目) */
    override fun replaceConfig(index: Int, config: ThemeConfigData) {
        if (index !in configs.indices) return
        if (!ThemeConfigStore.validate(config)) return
        configs[index] = config
        save()
    }

    /** 对照原版 applyBuiltin: 清 6 个自定义 pref → isNightTheme → applyTheme → RECREATE */
    override fun applyBuiltin(isNight: Boolean) {
        val prefs = PreferenceProviders.get()
        if (isNight) {
            prefs.remove(PreferKey.cNAccent)
            prefs.remove(PreferKey.cNBackground)
            prefs.remove(PreferKey.cNBBackground)
            prefs.remove(PreferKey.cNPrimary)
            prefs.remove(PreferKey.bgImageN)
            prefs.remove(PreferKey.bgImageNBlurring)
        } else {
            prefs.remove(PreferKey.cAccent)
            prefs.remove(PreferKey.cBackground)
            prefs.remove(PreferKey.cBBackground)
            prefs.remove(PreferKey.cPrimary)
            prefs.remove(PreferKey.bgImage)
            prefs.remove(PreferKey.bgImageBlurring)
        }
        setNightTheme(isNight)
        applyTheme()
        FlowBus.with(EventBus.RECREATE).tryEmit("")
    }

    /** 对照原版 applyConfig: applyConfigToPrefs + isNightTheme + applyTheme + RECREATE */
    override fun applyConfig(config: ThemeConfigData) {
        runCatching {
            val accent = ColorUtils.parseColor(config.accentColor)
            val bg = ColorUtils.parseColor(config.backgroundColor)
            val bbg = ColorUtils.parseColor(config.bottomBackground)
            val prefs = PreferenceProviders.get()
            if (config.isNightTheme) {
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
            setNightTheme(config.isNightTheme)
            applyTheme()
            FlowBus.with(EventBus.RECREATE).tryEmit("")
        }.onFailure { e ->
            AppLog.put("设置主题出错\n$e", e, true)
        }
    }

    /**
     * 切换日/夜模式并应用: 写 themeMode + 按目标模式读已配置色 (未配置回落默认) 写 ThemeStore +
     * 触发全局重组。不清自定义色 pref (区别于 [applyBuiltin])。
     *
     * 颜色计算直接以参数 [isNight] 为准, 不读 AppConfigProviders (iOS 端 pref 变更监听异步,
     * 写 themeMode 后缓存可能未刷新)。
     */
    override fun applyDayNight(isNight: Boolean) {
        PreferenceProviders.get().putString(PreferKey.themeMode, if (isNight) "2" else "1")
        val prefs = PreferenceProviders.get()
        val accent = prefs.getInt(if (isNight) PreferKey.cNAccent else PreferKey.cAccent)
            .let { if (it == 0) (if (isNight) NIGHT_ACCENT else DAY_ACCENT) else it }
        val bg = prefs.getInt(if (isNight) PreferKey.cNBackground else PreferKey.cBackground)
            .let { if (it == 0) (if (isNight) NIGHT_BG else DAY_BG) else it }
        val bbg = prefs.getInt(if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground)
            .let { if (it == 0) (if (isNight) NIGHT_BBG else DAY_BBG) else it }
        saveThemeStore(accent, bg, bbg)
        FlowBus.with(EventBus.RECREATE).tryEmit("")
    }

    /**
     * 对照原版 getBuiltinConfigs: 前置到主题列表最前的两个虚拟条目, 不写盘、不进 configList。
     * 色值搬自原版 arco_default_accent/bg/bbg (values / values-night), 小写十六进制与
     * 原版 `"#${Int.hexString}"` 输出一致。
     */
    override fun getBuiltinConfigs(): List<ThemeConfigData> = listOf(
        ThemeConfigData(
            themeName = syncGetString("default_day_theme"),
            isNightTheme = false,
            primaryColor = DAY_BG_HEX,
            accentColor = DAY_ACCENT_HEX,
            backgroundColor = DAY_BG_HEX,
            bottomBackground = DAY_BBG_HEX,
        ).also { it.isBuiltin = true },
        ThemeConfigData(
            themeName = syncGetString("default_night_theme"),
            isNightTheme = true,
            primaryColor = NIGHT_BG_HEX,
            accentColor = NIGHT_ACCENT_HEX,
            backgroundColor = NIGHT_BG_HEX,
            bottomBackground = NIGHT_BBG_HEX,
        ).also { it.isBuiltin = true },
    )

    /** 对照原版 save: configList 序列化覆盖写 themeConfig.json */
    override fun save() = ThemeConfigStore.save(configs)

    /** 对照原版 upConfig: 清空内存列表后从磁盘重读 (恢复备份覆盖文件后调用) */
    override fun upConfig() {
        configs.clear()
        configs.addAll(ThemeConfigStore.load())
    }

    /**
     * 等价原版 `AppConfig.isNightTheme = value`: 仅当生效值变化时才写 themeMode,
     * 否则 E-Ink("3") / 跟随系统("0") 会被误改。
     */
    private fun setNightTheme(value: Boolean) {
        if (AppConfigProviders.get().isNightTheme != value) {
            PreferenceProviders.get().putString(PreferKey.themeMode, if (value) "2" else "1")
        }
    }

    /**
     * 等价原版 applyTheme: E-Ink 强制黑白, 否则按当前日夜模式读自定义色
     * (0 视为未设置, 回落 XML 默认色), 再 ThemeStore.saveTheme(bg, accent, bg, bbg)。
     */
    private fun applyTheme() {
        val appConfig = AppConfigProviders.get()
        if (appConfig.isEInkMode) {
            saveThemeStore(accent = BLACK, bg = WHITE, bbg = WHITE)
            return
        }
        val prefs = PreferenceProviders.get()
        val isNight = appConfig.isNightTheme
        val accent = prefs.getInt(if (isNight) PreferKey.cNAccent else PreferKey.cAccent)
            .let { if (it == 0) (if (isNight) NIGHT_ACCENT else DAY_ACCENT) else it }
        val bg = prefs.getInt(if (isNight) PreferKey.cNBackground else PreferKey.cBackground)
            .let { if (it == 0) (if (isNight) NIGHT_BG else DAY_BG) else it }
        val bbg = prefs.getInt(if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground)
            .let { if (it == 0) (if (isNight) NIGHT_BBG else DAY_BBG) else it }
        saveThemeStore(accent, bg, bbg)
    }

    /**
     * 等价原版 ThemeStore.saveTheme(bg, accent, bg, bbg)。
     * status/nav 两键为非 Android 端专属: 桌面/iOS/鸿蒙 applyColors 会写它们,
     * 不同步刷新会残留旧值 (Android 端从不写这两键, 故原版无此步)。
     */
    private fun saveThemeStore(accent: Int, bg: Int, bbg: Int) {
        val prefs = PreferenceProviders.get()
        prefs.putInt(ThemeStorePrefKeys.KEY_PRIMARY_COLOR, bg)
        prefs.putInt(ThemeStorePrefKeys.KEY_ACCENT_COLOR, accent)
        prefs.putInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, bg)
        prefs.putInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, bbg)
        prefs.putInt(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR, bg)
        prefs.putInt(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR, bbg)
    }

    private companion object {
        // 原版 readDefaultColors 的 XML 实时值: values(日) / values-night(夜)
        // arco_default_accent=arco_primary, arco_default_bg=arco_bg_page/arco_fill_1, arco_default_bbg=arco_fill_2
        val DAY_ACCENT = 0xFF165DFF.toInt()
        val DAY_BG = 0xFFF8F8F8.toInt()
        val DAY_BBG = 0xFFF3F3F3.toInt()
        val NIGHT_ACCENT = 0xFF3C7EFF.toInt()
        val NIGHT_BG = 0xFF171717.toInt()
        val NIGHT_BBG = 0xFF232323.toInt()

        /** 原版 E-Ink 分支 ThemeStore.saveTheme(WHITE, BLACK, WHITE, WHITE) 的黑白 */
        val WHITE = 0xFFFFFFFF.toInt()
        val BLACK = 0xFF000000.toInt()

        const val DAY_ACCENT_HEX = "#ff165dff"
        const val DAY_BG_HEX = "#fff8f8f8"
        const val DAY_BBG_HEX = "#fff3f3f3"
        const val NIGHT_ACCENT_HEX = "#ff3c7eff"
        const val NIGHT_BG_HEX = "#ff171717"
        const val NIGHT_BBG_HEX = "#ff232323"
    }
}
