package io.legado.app.help.config

/**
 * 内存版 [ThemeConfigProvider] 抽象基类: 用内存 MutableList 简化实现,
 * 供无 Android ThemeConfig 单例的平台 (桌面/iOS/鸿蒙) 复用。
 *
 * 本基类只管列表存取, applyBuiltin/applyConfig/save 为 no-op —— 真正应用主题的实现见
 * [FileThemeConfigProvider] (写 ThemeStore pref + emit RECREATE)。
 * clearBg 走接口 default 实现 (基于 AppFilesDirs + BackupFileOps 跨平台抽象)。
 */
open class InMemoryThemeConfigProvider : ThemeConfigProvider {

    protected val configs: MutableList<ThemeConfigData> = mutableListOf()

    override fun getConfigList(): List<ThemeConfigData> = configs.toList()

    override fun addConfig(config: ThemeConfigData) {
        val index = configs.indexOfFirst { it.themeName == config.themeName }
        if (index >= 0) configs[index] = config else configs.add(config)
    }

    override fun delConfig(index: Int) {
        if (index in configs.indices) configs.removeAt(index)
    }

    /** 对照 ThemeCustomizeDialog.saveToConfig: 按索引替换 (主题改名不残留旧条目) */
    override fun replaceConfig(index: Int, config: ThemeConfigData) {
        if (index in configs.indices) configs[index] = config
    }

    override fun applyBuiltin(isNight: Boolean) { /* no-op */ }
    override fun applyConfig(config: ThemeConfigData) { /* no-op */ }
    override fun getBuiltinConfigs(): List<ThemeConfigData> = emptyList()
    override fun save() { /* no-op */ }
}
