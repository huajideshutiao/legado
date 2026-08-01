package io.legado.app.help.config

import kotlin.concurrent.Volatile

/**
 * 配置热路径字段的内存缓存 (对齐原版 AppConfig.cachedBoolPref/cachedIntPref 语义)。
 *
 * 原版 AppConfig 用委托属性把 pref 读缓存在内存字段, 由 OnSharedPreferenceChangeListener
 * 刷新; 桌面/iOS/鸿蒙端 Accessor 曾每次直读底层 pref (java.util.prefs / NSUserDefaults /
 * JSON 文件), 书架封面/侧栏/主题等组合期热路径每读穿透。
 *
 * 本类构造时读一次底层 pref, [get] 只读内存字段; pref 变更由宿主经
 * [PreferenceProvider.addPreferenceChangeListener] 回调 [refresh] 重读。
 * 变更频率远低于读取频率 (仅用户改设置), 全量重读开销可忽略。
 */
class CachedPrefValue<T : Any>(
    prefs: PreferenceProvider,
    private val read: (PreferenceProvider) -> T,
) {
    @Volatile
    private var cached: T = read(prefs)

    fun get(): T = cached

    /** pref 变更后重读 (监听回调调用)。 */
    fun refresh(prefs: PreferenceProvider) {
        cached = read(prefs)
    }
}
