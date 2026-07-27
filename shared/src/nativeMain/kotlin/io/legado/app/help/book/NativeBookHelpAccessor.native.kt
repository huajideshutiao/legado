package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource

/**
 * iOS/鸿蒙 (Native target) 共用 [BookHelpAccessor] 实现:
 * 委托 [BookStorageProviders.get].saveText 落盘章节正文,
 * 供 shared commonMain 中下沉的 webBook 编排层 (BookContent.kt) 通过 [BookHelpProviders]
 * 间接调用 saveContent。
 *
 * # 共用原因
 * iOS 与鸿蒙两端 BookHelpAccessor 主体实现完全一致 (均委托 `BookStorageProviders.get().saveText`
 * 写文本缓存, bookSource 参数忽略不下载图片), 仅类名 (IosBookHelpAccessor / OhosBookHelpAccessor)
 * 与注册函数不同, 故下沉到 nativeMain 共用, 平台源集用 typealias 别名 + 各自 register 函数。
 *
 * # 与桌面端 [io.legado.desktop.help.book.DesktopBookHelpAccessor] 区别
 *
 * 行为完全一致 (均委托 `BookStorageProviders.get().saveText` 写文本缓存):
 * - app 端 `saveContent` 委托 `BookHelp.saveContent`, 内部做 saveText + saveImages
 *   + postEvent(EventBus.SAVE_CONTENT)
 * - Native 端无 EventBus (Android 专属) / 无图片下载能力 (iOS UIImage / 鸿蒙 @ohos.multimedia.image
 *   桥接未下沉到 BookHelpAccessor), 仅委托 `BookStorageProviders.get().saveText` 写文本缓存,
 *   与桌面端阅读流 (ReadBookViewModelShared.loadChapter → BookStorageProviders.get().getContent) 对齐
 *
 * # bookSource 参数
 *
 * Native 端不下载图片, [bookSource] 参数忽略 (仅文本落盘); 保留接口签名一致性
 * 供未来扩展 (如 Native 端引入图片缓存后补 saveImages 逻辑)。
 *
 * 模式参考 desktop `DesktopBookHelpAccessor` 实现。
 */
class NativeBookHelpAccessor : BookHelpAccessor {

    /**
     * 保存章节正文到 Native 端缓存文件
     * (`{AppFilesDirs.filesDir}/book_cache/{bookFolderName}/{chapterFileName}`)。
     *
     * 实际落盘由 [NativeBookStorage.saveText] 完成 (kotlin.io.File.writeText, UTF-8 编码),
     * 行为与 app 端 BookHelp.saveText (FileUtils.createFileIfNotExist + writeText) 等价。
     */
    override fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        // bookSource 在 Native 端忽略: 不下载图片, 仅落盘文本
        BookStorageProviders.get().saveText(book, bookChapter, content)
    }
}

/**
 * 注册 [NativeBookHelpAccessor] 到 [BookHelpProviders] (iOS/鸿蒙共用)。
 *
 * 前置依赖: [BookStorageProviders] 已注册 (saveContent 委托 BookStorageProviders.get().saveText 落盘)。
 */
fun registerNativeBookHelpAccessor() {
    BookHelpProviders.register(NativeBookHelpAccessor())
}
