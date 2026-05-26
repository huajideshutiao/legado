package io.legado.app.model

import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine

/**
 * 阅读时长记录器: 以"开始 / 结束"为唯一触发点统一管理读时长落盘.
 *
 * - [start] 在阅读真正开始或恢复时调用 (Activity onResume / 朗读 play / 音频 play 等)
 * - [end] 在阅读暂停或结束时调用 (Activity onPause / 朗读 pause、stop / 音频 pause、stop / Service onDestroy)
 * - [setBook] 在 source 的当前书名变化时通知; 仅在 source 已经 start 过 (active 或 pending) 时才生效,
 *   避免无 reader 上下文的路径 (如 MediaButtonReceiver 调 ReadBook.initData) 误启动会话.
 *
 * 每本书一个独立会话, 多源 (READ_BOOK、READ_ALOUD、AUDIO、MANGA、VIDEO) 用引用计数:
 * - 同一本书的多个源 (例如阅读页 + TTS 朗读同一本书) 共用计时, 不会双计
 * - 不同书的源 (例如后台音频 A + 前台阅读 B) 各算各的, 同时累计互不干扰
 *
 * 同一 source 切换书籍时, 先把旧书已累计的时长结算掉, 再为新书开启计时.
 */
object ReadTimeRecorder {

    object Source {
        const val READ_BOOK = "read_book"
        const val READ_ALOUD = "read_aloud"
        const val AUDIO = "audio"
        const val MANGA = "manga"
        const val VIDEO = "video"
    }

    private class BookSession(var refCount: Int, var startTime: Long)

    /** source -> bookName. 空字符串表示已 start 但书名待补 (pending) */
    private val sourceBook = HashMap<String, String>()
    private val bookSessions = HashMap<String, BookSession>()
    private val lock = Any()

    /**
     * 标记 source 开始活跃. bookName 为空时仅占位 (pending), 等待 [setBook] 补齐书名后再正式计时.
     * 同一 source 切换到新书会先结算旧书.
     */
    fun start(source: String, bookName: String) {
        synchronized(lock) {
            val oldBook = sourceBook[source]
            if (oldBook == bookName) return
            if (!oldBook.isNullOrEmpty()) {
                endBookSession(oldBook)
            }
            sourceBook[source] = bookName
            if (bookName.isNotEmpty()) {
                startBookSession(bookName)
            }
        }
    }

    /** 标记 source 结束活跃. pending 状态也会一并清理. */
    fun end(source: String) {
        synchronized(lock) {
            val book = sourceBook.remove(source) ?: return
            if (book.isNotEmpty()) endBookSession(book)
        }
    }

    /**
     * 通知 source 当前的书名. 仅当 source 已经 start 过 (active 或 pending) 时才生效.
     * 用于 "异步加载书完成" 或 "切换书源" 等场景, 对未 start 的 source 无副作用.
     */
    fun setBook(source: String, bookName: String) {
        if (bookName.isEmpty()) return
        synchronized(lock) {
            val oldBook = sourceBook[source] ?: return
            if (oldBook == bookName) return
            if (oldBook.isNotEmpty()) {
                endBookSession(oldBook)
            }
            sourceBook[source] = bookName
            startBookSession(bookName)
        }
    }

    private fun startBookSession(bookName: String) {
        val session = bookSessions[bookName]
        if (session == null) {
            bookSessions[bookName] = BookSession(1, System.currentTimeMillis())
        } else {
            session.refCount++
        }
    }

    private fun endBookSession(bookName: String) {
        val session = bookSessions[bookName] ?: return
        session.refCount--
        if (session.refCount <= 0) {
            flushSession(bookName, session.startTime)
            bookSessions.remove(bookName)
        }
    }

    private fun flushSession(bookName: String, startTime: Long) {
        if (!AppConfig.enableReadRecord) return
        val now = System.currentTimeMillis()
        val delta = now - startTime
        if (delta <= 0) return
        Coroutine.async {
            appDb.readRecordDao.addReadTime(bookName, ReadRecord.dayKey(now), now, delta)
        }
    }
}
