package io.legado.app.help.config

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.lib.theme.ThemeStorePrefKeys
import io.legado.app.ui.compose.platform.sharedStringTable
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FlowBus
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.toJson

/**
 * 文件持久化版 [ThemeConfigProvider] (commonMain, 桌面/iOS/鸿蒙可共用)。
 *
 * 对照 app 端原版 `ThemeConfig` object:
 * - configList 持久化到 `{filesDir}/themeConfig.json` (同名同格式, 备份/恢复互通);
 * - applyConfig/applyBuiltin 写 [PreferKey] 自定义色 + themeMode + [ThemeStorePrefKeys]
 *   主题色 (等价原版 applyConfigToPrefs + AppConfig.isNightTheme + applyTheme →
 *   ThemeStore.saveTheme), 再发 [EventBus.RECREATE] (等价 postEvent);
 * - 文件 IO 走 [AppFilesDirs] + [BackupFileOps] 跨平台抽象, 不依赖 JVM 专属 API。
 *
 * 所有磁盘/prefs 访问懒执行, 构造时不触碰 [AppFilesDirs] (注册顺序无关)。
 */
class FileThemeConfigProvider : ThemeConfigProvider {

    private val configFilePath: String
        get() = AppFilesDirs.get().filesDir + BackupFileOps.separator + CONFIG_FILE_NAME

    /** 存档库: 仅存储用户自定义/导入的主题 (对照原版 configList lazy 读盘) */
    private val configs: MutableList<ThemeConfigData> by lazy {
        loadFromDisk().toMutableList()
    }

    override fun getConfigList(): List<ThemeConfigData> = configs.toList()

    /** 对照原版 addConfig: 校验色值 + 按 themeName 同名覆盖/追加 + save */
    override fun addConfig(config: ThemeConfigData) {
        if (!validateConfig(config)) return
        val index = configs.indexOfFirst { it.themeName == config.themeName }
        if (index >= 0) configs[index] = config else configs.add(config)
        // 原版仅追加分支 save (覆盖依赖进程内存); 桌面/iOS 常重启, 覆盖也落盘避免恢复数据丢失
        save()
    }

    /** 对照原版 delConfig: removeAt + save (applyTheme 不改当前色, 非 Android 端省略) */
    override fun delConfig(index: Int) {
        if (index !in configs.indices) return
        configs.removeAt(index)
        save()
    }

    /** 对照原版 applyBuiltin: 清 6 个自定义 pref → 写 themeMode → 默认色写 ThemeStore 键 → RECREATE */
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
        prefs.putString(PreferKey.themeMode, if (isNight) "2" else "1")
        if (isNight) {
            saveThemeStore(NIGHT_ACCENT, NIGHT_BG, NIGHT_BBG)
        } else {
            saveThemeStore(DAY_ACCENT, DAY_BG, DAY_BBG)
        }
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
            prefs.putString(PreferKey.themeMode, if (config.isNightTheme) "2" else "1")
            saveThemeStore(accent, bg, bbg)
            FlowBus.with(EventBus.RECREATE).tryEmit("")
        }.onFailure { e ->
            AppLog.put("设置主题出错\n$e", e, true)
        }
    }

    /**
     * 对照原版 getBuiltinConfigs: 前置到主题列表最前的两个虚拟条目, 不写盘、不进 configList。
     * 色值从原版 arco_default_accent/bg/bbg (values / values-night) 硬编码搬。
     */
    override fun getBuiltinConfigs(): List<ThemeConfigData> = listOf(
        ThemeConfigData(
            themeName = sharedStringTable["default_day_theme"] ?: "默认·白天",
            isNightTheme = false,
            primaryColor = DAY_BG_HEX,
            accentColor = DAY_ACCENT_HEX,
            backgroundColor = DAY_BG_HEX,
            bottomBackground = DAY_BBG_HEX,
        ).also { it.isBuiltin = true },
        ThemeConfigData(
            themeName = sharedStringTable["default_night_theme"] ?: "默认·夜间",
            isNightTheme = true,
            primaryColor = NIGHT_BG_HEX,
            accentColor = NIGHT_ACCENT_HEX,
            backgroundColor = NIGHT_BG_HEX,
            bottomBackground = NIGHT_BBG_HEX,
        ).also { it.isBuiltin = true },
    )

    /** 对照原版 save: configList 序列化覆盖写 themeConfig.json */
    override fun save() {
        runCatching {
            BackupFileOps.writeText(configFilePath, GSON.toJson(configs))
        }.onFailure { e ->
            AppLog.put("保存 themeConfig.json 出错\n${e.message}", e)
        }
    }

    /** 等价原版 applyTheme → ThemeStore.saveTheme(bg, accent, bg, bbg); status/nav 同步写避免残留旧值 */
    private fun saveThemeStore(accent: Int, bg: Int, bbg: Int) {
        val prefs = PreferenceProviders.get()
        prefs.putInt(ThemeStorePrefKeys.KEY_PRIMARY_COLOR, bg)
        prefs.putInt(ThemeStorePrefKeys.KEY_ACCENT_COLOR, accent)
        prefs.putInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, bg)
        prefs.putInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, bbg)
        prefs.putInt(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR, bg)
        prefs.putInt(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR, bbg)
    }

    /** 对照原版 validateConfig: 4 个色值可解析才有效 */
    private fun validateConfig(config: ThemeConfigData): Boolean = runCatching {
        ColorUtils.parseColor(config.primaryColor)
        ColorUtils.parseColor(config.accentColor)
        ColorUtils.parseColor(config.backgroundColor)
        ColorUtils.parseColor(config.bottomBackground)
    }.isSuccess

    /** 对照原版 getConfigsFromDisk: 文件不存在/解析失败返回空列表 */
    private fun loadFromDisk(): List<ThemeConfigData> = runCatching {
        if (BackupFileOps.exists(configFilePath)) {
            GSON.fromJsonArray<ThemeConfigData>(BackupFileOps.readText(configFilePath)).getOrThrow()
        } else {
            emptyList()
        }
    }.getOrElse { e ->
        AppLog.putDebug("读取 themeConfig.json 出错\n${e.message}", e)
        emptyList()
    }

    private companion object {
        const val CONFIG_FILE_NAME = "themeConfig.json"

        // 原版 readDefaultColors 的 XML 实时值: values(日) / values-night(夜)
        // arco_default_accent=arco_primary, arco_default_bg=arco_bg_page/arco_fill_1, arco_default_bbg=arco_fill_2
        val DAY_ACCENT = 0xFF165DFF.toInt()
        val DAY_BG = 0xFFF8F8F8.toInt()
        val DAY_BBG = 0xFFF3F3F3.toInt()
        val NIGHT_ACCENT = 0xFF3C7EFF.toInt()
        val NIGHT_BG = 0xFF171717.toInt()
        val NIGHT_BBG = 0xFF232323.toInt()

        const val DAY_ACCENT_HEX = "#FF165DFF"
        const val DAY_BG_HEX = "#FFF8F8F8"
        const val DAY_BBG_HEX = "#FFF3F3F3"
        const val NIGHT_ACCENT_HEX = "#FF3C7EFF"
        const val NIGHT_BG_HEX = "#FF171717"
        const val NIGHT_BBG_HEX = "#FF232323"
    }
}
