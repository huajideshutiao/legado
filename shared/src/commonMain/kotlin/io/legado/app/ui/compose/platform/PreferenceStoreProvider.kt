package io.legado.app.ui.compose.platform

/**
 * 跨平台 Preferences provider。
 * Android actual 包装 app.utils.ContextExtensions 的 getPrefX/putPrefX
 * (底层 defaultSharedPreferences); 桌面/iOS/鸿蒙 actual 提供本地实现。
 *
 * 仅暴露 B 类 Composable (Preferences/ColorPicker 等) 在 commonMain 用到的
 * Boolean/Int/String 读写 API，其余类型 (Long/StringSet 等) 按需扩展，
 * 避免一次性抽空 ContextExtensions。
 *
 * 设计：与 [ThemeStoreProvider] 一致，用 interface 而非 expect/actual，
 * 各平台 actual 实例由各平台自行构造注入，避免 shared androidMain 反向依赖 app 模块。
 */
interface PreferenceStoreProvider {
    fun getBoolean(key: String, defValue: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean = false)

    fun getInt(key: String, defValue: Int = 0): Int
    fun putInt(key: String, value: Int)

    fun getString(key: String, defValue: String? = null): String?
    fun putString(key: String, value: String?)
}
