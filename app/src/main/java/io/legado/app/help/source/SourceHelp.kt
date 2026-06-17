package io.legado.app.help.source

import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.AppCacheManager
import io.legado.app.help.config.SourceConfig
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook

object SourceHelp {

    fun getSource(key: String?): BaseSource? {
        key ?: return null
        if (ReadBook.bookSource?.bookSourceUrl == key) {
            return ReadBook.bookSource
        } else if (AudioPlay.bookSource?.bookSourceUrl == key) {
            return AudioPlay.bookSource
        }
        return appDb.bookSourceDao.getBookSource(key)
    }

    fun getSource(key: String?, @SourceType.Type type: Int): BaseSource? {
        key ?: return null
        return when (type) {
            SourceType.book, SourceType.rss -> appDb.bookSourceDao.getBookSource(key)
            SourceType.tts -> appDb.httpTTSDao.get(
                key.substringAfter("httpTts:").toLongOrNull() ?: -1
            )
            else -> null
        }
    }

    fun deleteSource(key: String, @SourceType.Type type: Int) {
        when (type) {
            SourceType.book, SourceType.rss -> deleteBookSource(key)
            SourceType.tts -> appDb.httpTTSDao.get(
                key.substringAfter("httpTts:").toLongOrNull() ?: -1
            )?.let {
                appDb.httpTTSDao.delete(it)
            }
        }
    }

    fun deleteBookSourceParts(sources: List<BookSourcePart>) {
        if (sources.isEmpty()) return
        val keys = sources.map { it.bookSourceUrl }
        deleteBookSourcesByKeys(sources = sources, keys = keys)
    }

    fun deleteBookSources(sources: List<BookSource>) {
        if (sources.isEmpty()) return
        val keys = sources.map { it.bookSourceUrl }
        deleteBookSourcesByKeys(keys = keys)
    }

    private fun deleteBookSourcesByKeys(
        sources: List<BookSourcePart>? = null,
        keys: List<String>
    ) {
        appDb.runInTransaction {
            if (sources != null) {
                appDb.bookSourceDao.delete(sources)
            } else {
                keys.chunked(999) { appDb.bookSourceDao.deleteIn(it) }
            }
            // deleteSourceVariables 含 LIKE 'v_KEY_%'，无法折叠成 IN 批量；逐键执行，但仍包在外层事务里
            keys.forEach { appDb.cacheDao.deleteSourceVariables(it) }
        }
        SourceConfig.removeSources(keys)
        AppCacheManager.clearSourceVariables()
    }

    private fun deleteBookSourceInternal(key: String) {
        appDb.bookSourceDao.delete(key)
        appDb.cacheDao.deleteSourceVariables(key)
        SourceConfig.removeSource(key)
    }

    fun deleteBookSource(key: String) {
        deleteBookSourceInternal(key)
        AppCacheManager.clearSourceVariables()
    }

    fun enableSource(key: String, @SourceType.Type type: Int, enable: Boolean) {
        when (type) {
            SourceType.book, SourceType.rss -> appDb.bookSourceDao.enable(key, enable)
            SourceType.tts -> Unit
        }
    }

    /**
     * 调整排序序号
     */
    fun adjustSortNumber() {
        val dao = appDb.bookSourceDao
        val max = dao.maxOrder
        val min = dao.minOrder
        val rangeOverflow = max > 99999 || min < -99999
        val hasDup = dao.hasDuplicateOrder
        if (!rangeOverflow && !hasDup) return

        // 快路径：仅绝对值越界，但 max-min 还能塞进 [-99999, 99999]。
        // 一条 UPDATE 整体平移就能修好，免去加载并回写每个 BookSourcePart。
        if (!hasDup && (max - min) <= 199998) {
            dao.shiftCustomOrder(-((max + min) / 2))
            return
        }

        // 兜底：全量重排为 0..N-1
        val sources = dao.allPart
        sources.forEachIndexed { index, bookSource ->
            bookSource.customOrder = index
        }
        dao.upOrder(sources)
    }

}