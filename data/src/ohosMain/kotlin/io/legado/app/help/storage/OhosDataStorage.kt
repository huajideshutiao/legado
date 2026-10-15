package io.legado.app.help.storage

/**
 * [DataStorage] 鸿蒙实现: 目录布局与 iOS 同源 ([NativeDataStorage], 全部挂在应用沙盒
 * `{filesDir}` 下)。
 *
 * 规则/书架 json 的导出根目录 ([userExportDir]) 与 iOS 一致保持原值。
 */
class OhosDataStorage : DataStorage by NativeDataStorage()

/** 鸿蒙宿主启动早期注册 [DataStorage] (须在 AppFilesDirs 之后、BookStorage 之前)。 */
fun registerOhosDataStorage() {
    DataStorageProviders.register(OhosDataStorage())
}
