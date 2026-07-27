package io.legado.app.help.config

import platform.Foundation.NSUserDefaults

/**
 * [PreferenceProvider] 的 iOS 真实实现 (基于 NSUserDefaults)。
 *
 * KP3: 用 iOS Foundation NSUserDefaults 替代 SharedPreferences / java.util.prefs:
 * - 标准用户默认 ([NSUserDefaults.standardUserDefaults]) 自动持久化到
 *   `~/Library/Preferences/{bundle-id}.plist` (iOS 沙盒内, 系统管理)
 * - 类型映射: String/Int/Boolean/Long 直接映射到 NSUserDefaults 原生类型
 *
 * # 行为对齐
 * - 安卓端 SharedPreferences: 同步持久化, 进程间共享 (MODE_PRIVATE)
 * - 桌面端 java.util.prefs.Preferences: 同步持久化到注册表/文件
 * - iOS 端 NSUserDefaults: 同步持久化到 plist 文件
 *
 * 三端行为等价: 写入即持久化 (原子), 读取即从持久化加载, 跨进程共享 (iOS 沙盒内单进程, 无并发问题)。
 *
 * # 类型映射 (与 [OhosPreferenceProvider] JSON 持久化策略对齐)
 * - String: 直接 setObject/stringForKey (NSString)
 * - Int: setObject(NSInteger) / integerForKey (NSInteger 在 64 位平台为 Long, 截断为 Int)
 * - Boolean: setBool/boolForKey
 * - Long: setObject(NSNumber) / integerForKey (NSInteger 64 位 = Long)
 *
 * 注: NSUserDefaults 不需要显式 synchronize (iOS 12+ 自动定时同步),
 * 但桌面端 java.util.prefs 需要显式 flush; 这里为对齐行为, 写入后显式 synchronize 确保立即落盘
 * (与 Android commit() 同步落盘行为对齐)。
 *
 * 模式参考 [io.legado.app.help.config.OhosPreferenceProvider] / `DesktopPreferenceStoreProvider`。
 */
class IosPreferenceProvider(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : PreferenceProvider {

    override fun getString(key: String, default: String): String {
        return defaults.stringForKey(key) ?: default
    }

    override fun getInt(key: String, default: Int): Int {
        // integerForKey 返回 NSInteger (Long), 截断为 Int; 不存在的 key 返回 0, 与 default 比较回退
        val value = defaults.integerForKey(key)
        return if (value == 0L && !defaults.objectHasKey(key)) default else value.toInt()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean {
        // boolForKey 不存在的 key 返回 false, 需用 objectForKey 判断 key 是否存在再回退 default
        if (!defaults.objectHasKey(key)) return default
        return defaults.boolForKey(key)
    }

    override fun getLong(key: String, default: Long): Long {
        val value = defaults.integerForKey(key)
        return if (value == 0L && !defaults.objectHasKey(key)) default else value
    }

    override fun getFloat(key: String, default: Float): Float {
        if (!defaults.objectHasKey(key)) return default
        return defaults.floatForKey(key)
    }

    override fun putString(key: String, value: String?) {
        if (value == null) {
            // null 等价于移除 (与 SharedPreferences.Editor.remove 行为一致)
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(value, forKey = key)
        }
        defaults.synchronize()
    }

    override fun putInt(key: String, value: Int) {
        // NSUserDefaults 无 setIntegerForKey (在 Kotlin/Native 中通过 setObject(NSNumber) 传整数)
        // 但标准 API 有 `setInteger(value, forKey)`; 这里用 setObject 兼容 (NSInteger 自动装箱)
        defaults.setObject(value, forKey = key)
        defaults.synchronize()
    }

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
        defaults.synchronize()
    }

    override fun putLong(key: String, value: Long) {
        defaults.setObject(value, forKey = key)
        defaults.synchronize()
    }

    override fun putFloat(key: String, value: Float) {
        defaults.setFloat(value, forKey = key)
        defaults.synchronize()
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
        defaults.synchronize()
    }

    override fun contains(key: String): Boolean {
        return defaults.objectHasKey(key)
    }

    override fun getAll(): Map<String, *> {
        // NSUserDefaults.dictionaryRepresentation 返回所有键值对 (含系统默认键, 如 AppleLanguages 等)
        // 过滤出本应用持久化的键 (非系统键); 简化实现: 返回全部, 调用方自行过滤
        // 与 jvmAndAndroidMain PreferenceProvider.getAll 行为基本一致
        val dict = defaults.dictionaryRepresentation()
        // NSDictionary 转 Kotlin Map: 用 keys 遍历, 跳过 nil 值
        val result = mutableMapOf<String, Any?>()
        for (key in dict.keys) {
            result[key as String] = dict[key]
        }
        return result
    }

    /**
     * 判断 key 是否存在于 NSUserDefaults (非系统默认键)。
     *
     * NSUserDefaults 无 containsKey API, 用 objectForKey 返回 nil 判断。
     * 注意: boolForKey/integerForKey 对不存在的 key 返回 false/0, 无法区分 "key 不存在" 与 "值为 0",
     * 需用 objectForKey 判断后再读取, 避免 default 参数失效。
     */
    private fun NSUserDefaults.objectHasKey(key: String): Boolean {
        return this.objectForKey(key) != null
    }
}

/**
 * iOS 宿主启动早期注册 [PreferenceProvider] 的 NSUserDefaults 真实实现。
 *
 * 调用时机: iOS app 启动早期, 在任何 commonMain 代码访问 `PreferenceProviders.get()` 之前。
 *
 * 模式参考 `registerOhosPreferenceProvider`。
 */
fun registerIosPreferenceProvider() {
    PreferenceProviders.register(IosPreferenceProvider())
}
