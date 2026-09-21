package io.legado.app.ui.book.read

/**
 * 阅读 ViewModel 跨模块契约 (切分后 :data 不能依赖 :ui 的 ReadBookViewModelShared 具体类)。
 *
 * ActiveReadBookRegistry 只持有/暴露本端口, 具体实现 (阅读页的 ReadBookViewModelShared)
 * 由 :ui 提供并 attach。当前仅暴露阅读器装载章节的能力 (CacheBook 等跨模块消费方)。
 */
interface ReadBookViewModelPort {
    /** 显式装载指定章节 (打开书/菜单跳章/后台落库后补载)。 */
    fun loadChapter(index: Int, chapterPos: Int? = null, keepScrollOffset: Boolean = false)
}
