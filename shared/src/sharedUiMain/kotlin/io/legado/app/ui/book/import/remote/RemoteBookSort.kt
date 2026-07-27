package io.legado.app.ui.book.import.remote

/**
 * 远程书籍排序方式。
 *
 * 下沉自 app 端原 `RemoteBookSort.kt` (纯 Kotlin 枚举, 无 Android 依赖)。
 * app 端 [io.legado.app.ui.book.import.remote.RemoteBookViewModel] 与
 * [io.legado.app.ui.book.import.remote.RemoteBookActivity] 通过同包跨模块
 * 直接引用本 shared 版本。
 */
enum class RemoteBookSort {
    Default, Name
}
