package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

/**
 * 章节换源 ViewModel (Android 端, KMP 化后薄壳)。
 *
 * # KMP 化重构说明
 *
 * 原本此类自行实现 [getContent] (调 `appDb.bookSourceDao.getBookSource` +
 * `WebBook.getContentAwait`) 并持有 [chapterIndex] / [chapterTitle] 字段,
 * 与父类 [ChangeBookSourceViewModel] 重复访问 appDb / WebBook。
 *
 * 现章节换源专属能力已下沉到 shared commonMain [ChangeBookSourceViewModelShared]:
 * - [chapterIndex] / [chapterTitle] 字段 → [ChangeBookSourceViewModelShared.chapterIndex] /
 *   [ChangeBookSourceViewModelShared.chapterTitle];
 * - [getContent] → [ChangeBookSourceViewModelShared.getContent]
 *   (内部走 `appDb.bookSourceDao.getBookSource` + `WebBook.getContentAwait`,
 *   与原实现等价);
 * - [initData] 解析 Bundle 中的 chapterIndex / chapterTitle 后,
 *   转发到 [ChangeBookSourceViewModelShared.initData] 6 参数重载。
 *
 * 本类仅做 Bundle 解析 + 转发, 不再直接访问 appDb / WebBook, 父类 [shared]
 * 已改为 `protected` 供本类访问。
 *
 * # 调用方兼容
 *
 * [ChangeChapterSourceDialog] 调用方式保持不变:
 * - `viewModel.chapterTitle` / `viewModel.chapterIndex` (字段转发);
 * - `viewModel.getContent(book, chapter, nextChapterUrl, success, error)` (方法转发);
 * - `viewModel.initData(arguments, oldBook, fromReadBookActivity)` (Bundle 解析在本类做)。
 *
 * @param application Android Application (BaseViewModel 需要)
 */
class ChangeChapterSourceViewModel(application: Application) :
    ChangeBookSourceViewModel(application) {

    /**
     * 章节序号, 转发到 [shared.chapterIndex] (已下沉到 commonMain)。
     *
     * ChangeChapterSourceDialog 用此值传给
     * `BookHelp.getDurChapter(chapterIndex, chapterTitle, toc)` 定位章节。
     */
    var chapterIndex: Int
        get() = shared.chapterIndex
        set(value) {
            shared.chapterIndex = value
        }

    /**
     * 章节标题, 转发到 [shared.chapterTitle] (已下沉到 commonMain)。
     *
     * ChangeChapterSourceDialog 用此值作标题栏显示。
     */
    var chapterTitle: String
        get() = shared.chapterTitle
        set(value) {
            shared.chapterTitle = value
        }

    /**
     * 初始化数据 (覆盖, 解析 Bundle 中的 chapterIndex / chapterTitle)。
     *
     * 1. 调 [super.initData] 解析 name / author 并转发到 [shared.initData] 4 参数版本
     *    (保留原 @CallSuper 语义);
     * 2. 解析 Bundle 中的 chapterIndex / chapterTitle;
     * 3. 转发到 [shared.initData] 6 参数重载 (内部复用 4 参数版本 + 设置 chapterIndex /
     *    chapterTitle, 重复赋值 name/author 无害)。
     *
     * @param arguments Bundle (含 name / author / chapterIndex / chapterTitle)
     * @param book 旧书
     * @param fromReadBookActivity 是否从阅读页进入
     */
    override fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        super.initData(arguments, book, fromReadBookActivity)
        arguments?.let { bundle ->
            val chapterIndex = bundle.getInt("chapterIndex")
            val chapterTitle = bundle.getString("chapterTitle") ?: ""
            // 转发到 shared.initData 6 参数重载 (章节换源专属字段已下沉到 commonMain)
            shared.initData(name, author, fromReadBookActivity, book, chapterIndex, chapterTitle)
        }
    }

    /**
     * 获取正文, 转发到 [shared.getContent] (已下沉到 commonMain)。
     *
     * 章节换源场景: 选中源 + 章节后, 取该源章节正文供阅读页替换
     * (ChangeChapterSourceDialog.clickChapter 调本方法后
     * callBack.replaceContent(content) 替换当前阅读页正文)。
     */
    fun getContent(
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String?,
        success: (content: String) -> Unit,
        error: (msg: String) -> Unit,
    ) = shared.getContent(book, chapter, nextChapterUrl, success, error)

}
