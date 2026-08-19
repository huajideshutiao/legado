package io.legado.app.help.config

import android.content.Context
import androidx.core.content.edit
import io.legado.app.App

/**
 * Android 端 [LocalConfigStore] 实现: 委托 `getSharedPreferences("local", MODE_PRIVATE)`,
 * 与 app 端 [io.legado.app.help.config.LocalConfig] 同一存储, 保证 help 引导版本标记
 * 与原版互通 (老用户升级不重复弹帮助引导)。
 */
private class AndroidLocalConfigStore : LocalConfigStore {
    private val prefs by lazy {
        App.instance.getSharedPreferences("local", Context.MODE_PRIVATE)
    }

    override fun getInt(key: String, defValue: Int): Int = prefs.getInt(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)

    override fun putInt(key: String, value: Int) {
        prefs.edit { putInt(key, value) }
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit { putBoolean(key, value) }
    }
}

/**
 * 安卓宿主启动早期注册 [LocalConfigStore]。
 *
 * 调用时机: App.onCreate, 任何 shared 调用之前。
 * 模式参考 `registerAndroidPreferenceProvider`。
 */
fun registerAndroidLocalConfigStore() {
    LocalConfigProviders.register(AndroidLocalConfigStore())
}
