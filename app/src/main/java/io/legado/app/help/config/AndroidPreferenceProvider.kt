package io.legado.app.help.config

import android.content.Context
import splitties.init.appCtx

/**
 * [PreferenceProvider] 安卓端实现（KP1.4）。
 *
 * 内部委托 `appCtx.getSharedPreferences("legado_config", Context.MODE_PRIVATE)`。
 *
 * 与 [AppConfig] 用的 `defaultSharedPreferences`（`<packageName>_preferences`）不同:
 * 本实现只给桌面端配置对称接口用, 不复用 AppConfig 的 SP 文件, 避免影响 AppConfig
 * 主体（保真红线, 不动 AppConfig.kt）。Android 端可选用, 通常仅用于测试 / 桌面端对称调用。
 *
 * 模式参考 [io.legado.app.model.webBook.WebBookProvidersImpl]。
 */
class AndroidPreferenceProvider : PreferenceProvider {

    private val prefs =
        appCtx.getSharedPreferences("legado_config", Context.MODE_PRIVATE)

    override fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    override fun getInt(key: String, default: Int): Int =
        prefs.getInt(key, default)

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun getLong(key: String, default: Long): Long =
        prefs.getLong(key, default)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean =
        prefs.contains(key)

    override fun getAll(): Map<String, *> =
        prefs.all
}

/**
 * 安卓宿主启动早期注册 [PreferenceProvider]。
 *
 * 调用时机: App.onCreate, 任何 shared 调用之前。
 * 模式参考 `registerAndroidWebBookProviders` / `registerAndroidPasswordProvider`。
 */
fun registerAndroidPreferenceProvider() {
    PreferenceProviders.register(AndroidPreferenceProvider())
}
