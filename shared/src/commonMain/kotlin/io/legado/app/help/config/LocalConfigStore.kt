package io.legado.app.help.config

import kotlin.concurrent.Volatile

/**
 * LocalConfig 的跨平台存储抽象 (对应 app 端 `getSharedPreferences("local", MODE_PRIVATE)`)。
 *
 * # 背景
 * 原版各 help 引导版本标记 (backupHelpVersion/readHelpVersion 等) 存独立 "local" SharedPreferences
 * (app 端 [io.legado.app.help.config.LocalConfig] 委托), 与默认 prefs 分离。KMP 端
 * [LocalConfigShared.isLastVersion] 的 get/put 由调用方注入, 若无本抽象会退化为写默认 prefs,
 * 老用户从原版升级后版本标记读不到 → 帮助引导重复弹出。
 *
 * # 设计
 * - 仅暴露 [LocalConfigShared.isLastVersion] 所需的 Int/Boolean 读写, 其余字段保持 app 端原实现
 * - Android 注册委托 "local" prefs 的实现 ([io.legado.app.help.config.registerAndroidLocalConfigStore]);
 *   无独立 "local" 存储的平台 (桌面/iOS/鸿蒙) 不注册, [get] 回退 [PreferenceProviders]
 *   (帮助引导标记仍持久化, 语义一致)
 *
 * 模式参考 [io.legado.app.help.config.PreferenceProviders]。
 */
interface LocalConfigStore {
    fun getInt(key: String, defValue: Int = 0): Int
    fun getBoolean(key: String, defValue: Boolean = false): Boolean
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
}

/**
 * [LocalConfigStore] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate 的 [io.legado.app.help.config.registerAndroidLocalConfigStore])。
 * 未注册时回退 [PreferenceProviders] (桌面/iOS/鸿蒙无独立 "local" 存储)。
 */
object LocalConfigProviders {

    @Volatile
    private var impl: LocalConfigStore? = null

    /** 宿主启动早期注册一次 (Android: 委托 "local" prefs)。 */
    fun register(impl: LocalConfigStore) {
        this.impl = impl
    }

    /** 获取已注册实现; 未注册回退 [PreferenceProviders] 委托。 */
    fun get(): LocalConfigStore = impl ?: PreferenceProviderLocalConfigStore()

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}

/** 无独立 "local" 存储的平台 (桌面/iOS/鸿蒙): 委托 [PreferenceProviders] 保持持久化。 */
private class PreferenceProviderLocalConfigStore : LocalConfigStore {
    private val prefs get() = PreferenceProviders.get()

    override fun getInt(key: String, defValue: Int): Int = prefs.getInt(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)

    override fun putInt(key: String, value: Int) = prefs.putInt(key, value)

    override fun putBoolean(key: String, value: Boolean) = prefs.putBoolean(key, value)
}
