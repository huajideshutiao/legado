package io.legado.app.model.chapter

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

/**
 * 章节序号 → [BookChapter] 的统一解析: **内存目录优先, 库兜底**。
 *
 * 原版四模式各写一份 (`ReadBook.loadContent` 的 `chapterList?.get(index) ?: appDb...`、
 * `ReadMangaViewModel.loadContent`、`AudioPlay.upDurChapter`、`VideoViewModel.initData`),
 * KMP 化后一度散成 10 余处；此处收敛为单一实现。
 *
 * 越界与查库失败一律返回 null, 不抛：原版 `chapterList?.get(index)` 越界抛 IndexOutOfBounds
 * 是缺陷 (目录滞后于 chapterSize 时崩), 不复刻。
 *
 * @param chapters 内存目录 (null / 缺章时回查库)
 */
suspend fun resolveChapter(
    book: Book,
    index: Int,
    chapters: List<BookChapter>?,
): BookChapter? {
    if (index < 0) return null
    chapters?.getOrNull(index)?.let { return it }
    // 查库失败 (库已关闭 / 迁移中) 视作查不到: 章节解析是装载前置, 抛出会让整轮装载失败,
    // 而 null 分支已有正确处置 (越界 → upToc, 缺章 → upToc(force))
    return runCatching { AppDbProviders.get().bookChapterDao.getChapter(book.bookUrl, index) }
        .getOrNull()
}

/**
 * 资源直链回写 (音视频): 内容变化时写内存 [BookChapter.resourceUrl], 在架书同步 PATCH 落库。
 *
 * 收敛音频 `AudioPlayManager.loadPlayUrl`、视频 `VideoPlayViewModelShared.loadChapter`、
 * `ResourceUrlPreloader.preload` 三处同体逻辑。
 *
 * 只 PATCH resourceUrl 列而非整行 update: 整行会冲掉并发写入的其他章节字段。
 *
 * @param inBookshelf 在架书才落库 (非在架书目录不落库, 写库无处可留)
 * @return true = 本次发生了变化
 */
suspend fun BookChapter.updateResourceUrl(content: String, inBookshelf: Boolean): Boolean {
    if (resourceUrl == content) return false
    resourceUrl = content
    if (inBookshelf) {
        AppDbProviders.get().bookChapterDao.upResourceUrl(bookUrl, url, content)
    }
    return true
}
