package io.legado.app.ui.book.import.remote

/**
 * 远程书籍排序方式 (纯 Kotlin 枚举, 无平台依赖)。
 *
 * 放 commonMain 供 [RemoteBookViewModelShared] (commonMain) 与
 * sharedUiMain / iosMain / desktop 的 UI 层共同引用。
 */
enum class RemoteBookSort {
    Default, Name
}
