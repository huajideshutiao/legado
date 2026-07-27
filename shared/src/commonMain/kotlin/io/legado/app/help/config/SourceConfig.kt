package io.legado.app.help.config

/**
 * 书源评分配置 (从 app 端下沉到 commonMain)。
 *
 * 原 app 端用独立 SharedPreferences 文件 "SourceConfig" 存储:
 * - `origin` (书源 URL) → 源累计评分
 * - `${origin}_${name}_${author}` → 单本书在某源的评分
 *
 * 下沉后改走 [PreferenceProviders.get] (Android 端走 defaultSharedPreferences,
 * desktop 端走 java.util.prefs.Preferences, iOS 端走 NSUserDefaults, 鸿蒙端走 OHOS Prefs)。
 *
 * # 保真说明
 *
 * 原 app 端 "SourceConfig" SP 文件中已累积的评分数据, 下沉后不会自动迁移到
 * PreferenceProviders 后端存储 (Android 端 defaultSharedPreferences), 评分会从 0 重新累积。
 * 评分数据为非关键运行时数据 (仅用于换源列表排序参考), 影响可控。
 *
 * # 跨模块合并
 *
 * 包名保持 `io.legado.app.help.config`, app 端调用方 import 不变
 * (跨模块同包名同签名 object 自动合并, app 端原文件已删除)。
 * 模式参考 [io.legado.app.help.CacheManager] / [io.legado.app.help.AppCacheManager]。
 *
 * @see PreferenceProviders
 */
object SourceConfig {
    private val prefs get() = PreferenceProviders.get()

    fun setBookScore(origin: String, name: String, author: String, score: Int) {
        // 对应原 sp.edit { putInt(origin, ...); putInt("${origin}_${name}_${author}", score) }。
        // 原走 androidx.core.content.edit 事务, 读旧值 → 算差值 → 写源累计 + 单本评分一次 apply。
        // PreferenceProvider 接口无事务, 拆成两次独立 put, 行为等价 (最终结果一致),
        // 仅失去原子性 (评分写通常在 UI 线程, 无并发, 影响可控)。
        val preScore = getBookScore(origin, name, author)
        val newScore = if (preScore != 0) score - preScore else score
        prefs.putInt(origin, getSourceScore(origin) + newScore)
        prefs.putInt("${origin}_${name}_${author}", score)
    }

    fun getBookScore(origin: String, name: String, author: String): Int {
        return prefs.getInt("${origin}_${name}_${author}", 0)
    }

    fun getSourceScore(origin: String): Int {
        return prefs.getInt(origin, 0)
    }

    fun removeSource(origin: String) {
        // 对应原 sp.all.keys.filter { it.startsWith(origin) }.forEach { remove(it) }。
        // getAll() 返回 snapshot, 遍历中调 remove 不影响快照, 与原行为一致。
        prefs.getAll().keys.filter { it.startsWith(origin) }.forEach { key ->
            prefs.remove(key)
        }
    }

    fun removeSources(origins: Collection<String>) {
        if (origins.isEmpty()) return
        val toRemove = prefs.getAll().keys.filter { fullKey ->
            origins.any { fullKey.startsWith(it) }
        }
        if (toRemove.isEmpty()) return
        toRemove.forEach { key -> prefs.remove(key) }
    }
}
