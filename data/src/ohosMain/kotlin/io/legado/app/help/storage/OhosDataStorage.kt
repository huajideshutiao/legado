package io.legado.app.help.storage

/**
 * [DataStorage] 鸿蒙实现: 目录布局与 iOS 同源 ([NativeDataStorage], 全部挂在应用沙盒
 * `{filesDir}` 下), 覆盖两个"要用户自己找得到"的惯例目录 (导出书籍/备份 zip)。
 *
 * 两者为 null 的原因: 鸿蒙的应用沙盒 (`/data/storage/el2/base/haps/entry/files`)
 * 不是用户能在文件管理器里浏览的位置 (官方《应用沙箱目录》: "应用默认仅能看到自己的应用文件
 * 以及少量的系统文件", 只有"全量挂载的设备平台"上文件管理应用才能查看其他应用 el2/base
 * 下的数据), 而安卓/iOS 的惯例目录都是用户能找到的位置 (iOS 的 Documents 经"文件"App 可见)。
 * 产物要求用户自己找得到, 故与安卓同口径: 惯例目录为 null, 由用户经目录选择器选一个
 * 可见的公共目录 (导出取值顺序见 [effectiveBookExportDir] 第 3 分支, 备份见
 * [io.legado.app.ui.route.BackupConfigRoute] 的 backupPath 分支)。
 *
 * 规则/书架 json 的导出根目录 ([userExportDir]) 不在此列, 保持原值。
 */
class OhosDataStorage : DataStorage by NativeDataStorage() {

    override val defaultBookExportDir: String? get() = null

    /**
     * 备份 zip 必须落用户选定的可见目录 (对照安卓 [io.legado.app.help.storage.AndroidDataStorage]
     * 的同名 null: 原版 BackupConfigFragment.backup 未设 backupPath 时先 launch 目录选择器)。
     */
    override val defaultBackupDir: String? get() = null
}

/** 鸿蒙宿主启动早期注册 [DataStorage] (须在 AppFilesDirs 之后、BookStorage 之前)。 */
fun registerOhosDataStorage() {
    DataStorageProviders.register(OhosDataStorage())
}
