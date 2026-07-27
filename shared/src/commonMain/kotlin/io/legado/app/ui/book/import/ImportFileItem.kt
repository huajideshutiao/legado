package io.legado.app.ui.book.import

/**
 * 导入家族(本地/远程)统一条目接口。
 *
 * app 端 [io.legado.app.ui.book.import.local.ImportBook] 与
 * [io.legado.app.model.remote.RemoteBook] 各自实现本接口,
 * 供 shared 端 [ImportFileRow] / [ImportBookScreen] / [RemoteBookScreen] 泛型复用。
 *
 * 字段语义对照两类原模型:
 * - [name] / [isDir] / [isUpDir] / [isOnBookShelf] / [size]: 两类模型同名字段
 * - [lastModified]: ImportBook.lastModified / RemoteBook.lastModify (统一接口)
 * - [tag]: ImportBook 取 name 后缀, RemoteBook 取 contentType (由各模型自行计算)
 * - [itemKey]: LazyColumn items() 的 key (ImportBook=file.toString / RemoteBook=path)
 *
 * # 下沉说明
 * 原与 ImportScaffold/ImportFileRow 等 Composable 同处 sharedUiMain/ImportUi.kt。
 * [io.legado.app.model.remote.RemoteBook] 下沉 commonMain 需实现本接口, 而 commonMain 不可见
 * sharedUiMain, 故抽取本接口到 commonMain (纯接口无 Compose 依赖), Composable 仍留 sharedUiMain。
 */
interface ImportFileItem {
    val name: String
    val isDir: Boolean
    val isUpDir: Boolean
    val isOnBookShelf: Boolean
    val size: Long
    val lastModified: Long
    val tag: String
    /** LazyColumn items() key 参数 (唯一标识, 对照原 ImportBook.file.toString / RemoteBook.path) */
    val itemKey: Any
}
