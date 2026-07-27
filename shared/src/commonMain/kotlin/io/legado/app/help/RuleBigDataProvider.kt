package io.legado.app.help

import kotlin.concurrent.Volatile

/**
 * RuleBigDataHelp 的注入接口 (K5-c Phase 4: 已从 jvmAndAndroidMain 下沉 commonMain)。
 *
 * app 端 RuleBigDataHelp 实现本接口, 在 App.onCreate 注册到 RuleBigDataProviders.impl。
 * shared 侧实体类 (BaseBook/BookChapter, commonMain) 通过本接口访问大变量存储。
 *
 * listBookDataDirs 返回 List<String> (路径字符串) 而非 List<java.io.File>,
 * 避免 commonMain 引用 JDK; app 端 BookHelp 调用时 `.map { File(it) }` 转换,
 * 行为与原 List<File> 完全一致。
 */
interface RuleBigDataProvider {
    fun putBookVariable(bookUrl: String, key: String, value: String?)
    fun getBookVariable(bookUrl: String, key: String?): String?
    fun hasBookVariable(bookUrl: String, key: String): Boolean
    fun putChapterVariable(bookUrl: String, chapterUrl: String, key: String, value: String?)
    fun getChapterVariable(bookUrl: String, chapterUrl: String, key: String): String?

    /** 供 BookHelp.clearCache 用, 返回 book 数据根目录下的子目录路径列表 */
    fun listBookDataDirs(): List<String>

    /**
     * 清理不在书架的大变量数据 (对照 app 端 `BookHelp.clearInvalidCache` 步骤 2)。
     *
     * 遍历 [listBookDataDirs] 返回的各 book 数据根目录下的子目录, 读取 `bookUrl.txt`
     * 还原原始 bookUrl, 若不在 [bookUrls] 集合中则删除整个子目录。
     */
    fun clearInvalidBookData(bookUrls: Set<String>)
}

object RuleBigDataProviders {
    @Volatile
    var impl: RuleBigDataProvider? = null
}
