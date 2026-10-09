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

    override val backgroundsDir: String get() = "$base/bg"

    override val fontsDir: String get() = "$base/font"

    override val backupDir: String get() = "$base/backup"

    /**
     * iOS/鸿蒙的导出书籍惯例目录: 用户可见沙盒根 ([AppFilesDirs.filesDir], iOS/鸿蒙 即
     * 用户经"文件"App 可见的 Documents) 下的 `export`, 与规则 json 同根。
     *
     * 不取接口默认值 [DataStorage.bookExportDir] (`export/books`): 改动前
     * `exportAllBooks` 就写在 `filesDir/export` (硬编码), 保持落点不变 —— 本次只是把
     * 导出目录的取值改成统一的"偏好键 → 本属性"判定, 不顺便搬用户的文件。
     */
    override val defaultBookExportDir: String? get() = userExportDir
}

/** iOS / 鸿蒙宿主启动早期注册 [DataStorage] (须在 AppFilesDirs 之后、BookStorage 之前)。 */
fun registerNativeDataStorage() {
    DataStorageProviders.register(NativeDataStorage())
}
