package io.legado.app.help.config

import kotlin.concurrent.Volatile

/**
 * 跨平台 SharedPreferences 抽象（KP1.4）。
 *
 * 安卓端 SharedPreferences 依赖 `android.content.Context`, 不能下沉 shared commonMain。
 * 桌面端需要读写配置（书源线程数、简繁转换类型等 AppConfig 项），通过本接口注入
 * 平台实现解耦。安卓端实现委托 `appCtx.defaultSharedPreferences`
 * (与设置界面同一文件, 旧 `legado_config` 文件已一次性并入),
 * 桌面端实现委托 `java.util.prefs.Preferences`。
 *
 * 注：[AppConfig] 主体仍直接用 SharedPreferences, 不强制改走 PreferenceProvider（保真红线）。
 * 本接口仅给桌面端用, Android 端可选用。
 *
 * 模式参考 `OkHttpClientProviders` / [PasswordProviders]。
 */
interface PreferenceProvider {
    fun getString(key: String, default: String = ""): String
    fun getInt(key: String, default: Int = 0): Int
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun getLong(key: String, default: Long = 0L): Long
    fun getFloat(key: String, default: Float = 0f): Float

    fun putString(key: String, value: String?)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
    fun putLong(key: String, value: Long)
    fun putFloat(key: String, value: Float)

    fun remove(key: String)
    fun contains(key: String): Boolean
    fun getAll(): Map<String, *>

    /**
     * 注册值变更监听: 任一 key 被 put/remove 时回调该 key (供 [CachedPrefValue] 等内存缓存刷新)。
     *
     * 返回注销函数。默认空实现 (Android 端 AppConfig 自带缓存监听, 不依赖本接口);
     * desktop (java.util.prefs 节点监听) / iOS (NSUserDefaults 变更通知, 通知不含 key,
     * 回调空串) / ohos (写入自通知) 各自实现。
     */
    fun addPreferenceChangeListener(listener: (key: String) -> Unit): () -> Unit = {}
}

/**
 * PreferenceProvider 容器。宿主启动早期注册一次。
 *
 * shared/commonMain 内访问点用 `PreferenceProviders.get().getString(key)` 等替代
 * 平台 SharedPreferences / java.util.prefs 调用, 行为完全一致, 仅多一层 provider 间接。
 */
object PreferenceProviders {
    @Volatile
    private var impl: PreferenceProvider? = null

    /** 宿主启动早期注册一次（任何 shared 调用之前）。 */
    fun register(impl: PreferenceProvider) {
        this.impl = impl
    }

    /** 取已注册实现；未注册抛出 IllegalStateException 帮助早期发现初始化遗漏。 */
    fun get(): PreferenceProvider =
        impl ?: error("PreferenceProviders.impl not registered; call registerAndroidPreferenceProvider() or registerDesktopConfig() first")
}
