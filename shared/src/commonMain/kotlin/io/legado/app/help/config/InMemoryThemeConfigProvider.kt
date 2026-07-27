package io.legado.app.help.config

/**
 * 内存版 [ThemeConfigProvider] 抽象基类: 用内存 MutableList 简化实现,
 * 供无 Android ThemeConfig 单例的平台 (桌面/iOS/鸿蒙) 复用。
 *
 * 主题应用由各平台 ThemeStoreProvider.applyColors + EventBusProvider.emitRecreate
 * 组合实现, 故 applyBuiltin/applyConfig/save 为 no-op。
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

    override fun applyBuiltin(isNight: Boolean) { /* no-op */ }
    override fun applyConfig(config: ThemeConfigData) { /* no-op */ }
    override fun getBuiltinConfigs(): List<ThemeConfigData> = emptyList()
    override fun save() { /* no-op */ }
}
