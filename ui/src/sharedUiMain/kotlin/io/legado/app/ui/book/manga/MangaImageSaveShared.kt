package io.legado.app.ui.book.manga

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookImageDownloadShared
import io.legado.app.ui.root.imageExtension
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * 漫画页"保存图片"的共享编排 (四端一份): 取已缓存的图片字节 → 按实际格式命名 → 交 [save]
 * 写到本端位置。
 *
 * 字节只从图片缓存 (或本地书内嵌图) 取, 未命中即失败 —— 保存把已经看过的图导出到用户手上,
 * 不因导出重新下载 (对照 app 端 `ReadMangaActivity.saveImage` → `BaseReadViewModel.saveImage`
 * 的读取段)。
 *
 * [save] 是本端的落点原语 (各端由 [io.legado.app.ui.root.FilePickerService.saveImageToUser]
 * 决定: 落相册 / 记住的图片目录 / 沙盒导出目录), 与图像查看器长按保存同一入口。
 * 命名与异常收口在这里统一, 各端只提供自己的落点。
 *
 * @return true=已写入, false=取不到缓存或写入失败, null=用户取消选择位置 (调用方静默)
 */
suspend fun saveCachedMangaImage(
    url: String,
    book: Book?,
    save: (fileName: String, bytes: ByteArray) -> Boolean?,
): Boolean? {
    book ?: return false
    return runCatching {
        val bytes = BookImageDownloadShared.imageBytesToSave(book, url) ?: return@runCatching false
        save("manga-${systemCurrentTimeMillis()}${imageExtension(bytes, url)}", bytes)
    }.getOrElse {
        AppLog.put("保存图片出错\n${it.message}", it)
        false
    }
}
