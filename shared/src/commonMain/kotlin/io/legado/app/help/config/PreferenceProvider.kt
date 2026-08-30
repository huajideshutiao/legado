package io.legado.app.help.config

import kotlin.concurrent.Volatile

/**
 * 跨平台 SharedPreferences 抽象。
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

    /**
     * 可空读取：key 不存在返回 null (对齐 Android getPrefString(key) 语义)。
     * UI 层"未设置"判断统一走本方法；[getString] 的非空默认值语义供帮助层使用。
     */
    fun getStringOrNull(key: String): String?
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
     * desktop / iOS / ohos 三端实现见 [PreferenceChangeNotifier]。
     */
    fun addPreferenceChangeListener(listener: (key: String) -> Unit): () -> Unit = {}
}

/**
 * 写入路径的同步自通知 (desktop / iOS / ohos 三端 [PreferenceProvider] 共用)。
 *
 * 平台自带的变更通知都是异步的 —— java.util.prefs 的事件由 JDK 后台线程分发,
 * iOS 的 NSUserDefaultsDidChangeNotification 经 mainQueue 投递 —— 只靠它会让"写完立刻读"
 * 读到旧缓存 (如设置项写 themeMode 后 applyThemeMode 读 isNightTheme)。各实现在每个
 * put/remove 末尾调 [notifyChanged] 同步通知, 平台异步通知仍保留以覆盖不经本实例的直写路径;
 * 两条通道都到时监听方重读两次, [CachedPrefValue.refresh] 幂等。
 *
 * 监听注册在启动早期, 之后不再增删。
 */
class PreferenceChangeNotifier {
    private val listeners = mutableListOf<(String) -> Unit>()

    fun add(listener: (key: String) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (key: String) -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged(key: String) {
        listeners.toList().forEach { it(key) }
    }
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
