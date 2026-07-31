package io.legado.app.help.storage

import io.legado.app.help.file.AppFilesDirs

/**
 * [DataStorage] iOS / 鸿蒙共用实现: 全部目录挂在应用沙盒 [AppFilesDirs.filesDir] 下,
 * 与 [io.legado.app.help.book.NativeBookStorage] 同根 (路径分隔符恒为 "/")。
 */
class NativeDataStorage : DataStorage {

    private val base: String
        get() = AppFilesDirs.get().filesDir.trimEnd('/')

    override val chapterCacheDir: String get() = "$base/book_cache"

    override val coversDir: String get() = "$base/covers/default"

    override val backgroundsDir: String get() = "$base/bg"

    override val fontsDir: String get() = "$base/font"

    override val backupDir: String get() = "$base/backup"
}

/** iOS / 鸿蒙宿主启动早期注册 [DataStorage] (须在 AppFilesDirs 之后、BookStorage 之前)。 */
fun registerNativeDataStorage() {
    DataStorageProviders.register(NativeDataStorage())
}
