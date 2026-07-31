package io.legado.app.help.storage

import kotlin.concurrent.Volatile

/**
 * 应用存储路径集中 provider: 调用方只问"要哪类目录", 不问"落在哪"。
 *
 * 各端路径映射见实现 (Android `AndroidDataStorage` / 桌面 [JvmDataStorage] /
 * iOS+鸿蒙 `NativeDataStorage`)。一律返回绝对路径字符串, 不保证目录已创建。
 */
interface DataStorage {

    /** 章节正文缓存根目录 (Android `externalFiles/book_cache`, 桌面 `~/.legado/book_cache`)。 */
    val chapterCacheDir: String

    /** 默认封面图集烘焙目录 (Android `externalFiles/covers/default`)。 */
    val coversDir: String

    /** 阅读背景图目录 (Android `externalFiles/bg`)。 */
    val backgroundsDir: String

    /** 阅读字体目录 (Android `externalFiles/font`)。 */
    val fontsDir: String

    /** 备份工作目录 (各端均为 `filesDir/backup`)。 */
    val backupDir: String
}

/**
 * [DataStorage] provider 容器。宿主启动早期注册一次。
 *
 * 模式同 [io.legado.app.help.book.BookStorageProviders] /
 * [io.legado.app.help.config.AppConfigProviders]。
 */
object DataStorageProviders {

    @Volatile
    private var impl: DataStorage? = null

    /** 宿主启动早期注册一次 (任何目录访问之前)。 */
    fun register(impl: DataStorage) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): DataStorage = impl ?: error("DataStorageProviders not registered")

    /** 获取已注册实现, 未注册返回 null (供未接线平台安全回退)。 */
    fun getOrNull(): DataStorage? = impl
}
