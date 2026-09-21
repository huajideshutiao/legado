package io.legado.app.help.config

/**
 * Android / 桌面共用的 [ReadConfigProviders] 具名实现, 类体一致, 上提共用。
 *
 * 偏好读写统一走 [PreferenceProviders] 单例 (Android 端为 AndroidPreferenceProvider,
 * 包装 defaultSharedPreferences; 桌面端为 DesktopPreferenceProvider, java.util.prefs),
 * 内部构造 [ReadBookConfigShared] 与 [ReadTipConfigShared], 与端侧无依赖。
 * 须在宿主注册 PreferenceProvider 之后构造。
 *
 * 两个类名分别被两端入口引用 (app App.kt 构造、桌面文档), 保留具名类不合并。
 */
class AndroidReadConfigProviders() : ReadConfigProviders {

    override val readBookConfig: ReadBookConfigShared =
        ReadBookConfigShared(PreferenceProviders.get())

    override val readTipConfig: ReadTipConfigShared =
        ReadTipConfigShared(readBookConfig)
}

/**
 * 桌面 JVM 端 [ReadConfigProviders] 具名实现, 与 [AndroidReadConfigProviders] 类体一致。
 *
 * 桌面端当前在 desktop Main.kt 用匿名 object 注入, 本类保留供显式构造使用。
 */
class DesktopReadConfigProviders() : ReadConfigProviders {

    override val readBookConfig: ReadBookConfigShared =
        ReadBookConfigShared(PreferenceProviders.get())

    override val readTipConfig: ReadTipConfigShared =
        ReadTipConfigShared(readBookConfig)
}
