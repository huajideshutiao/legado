package io.legado.app.ui.book.manga.render

import kotlin.math.roundToInt

/**
 * 下载进度文本 (四端共用；对照原版 MangaPageImageView.onProgress → 转圈环心百分比)。
 *
 * 有总长按百分比, 没有 (chunked / 服务端不给 Content-Length) 就按已下载量。
 */
fun mangaProgressText(bytesRead: Long, totalBytes: Long): String {
    if (totalBytes > 0) return "${(bytesRead * 100 / totalBytes).coerceIn(0, 100)}%"
    val kb = bytesRead / 1024.0
    if (kb < 1024) return "${kb.toInt()}KB"
    return "${(kb / 1024.0 * 10).roundToInt() / 10.0}MB"
}
