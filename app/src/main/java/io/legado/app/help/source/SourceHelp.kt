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
        appDb.runInTransaction {
            sources.forEach {
                deleteBookSourceInternal(it.bookSourceUrl)
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    fun deleteBookSources(sources: List<BookSource>) {
        appDb.runInTransaction {
            sources.forEach {
                deleteBookSourceInternal(it.bookSourceUrl)
            }
        }
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
        if (
            appDb.bookSourceDao.maxOrder > 99999
            || appDb.bookSourceDao.minOrder < -99999
            || appDb.bookSourceDao.hasDuplicateOrder
        ) {
            val sources = appDb.bookSourceDao.allPart
            sources.forEachIndexed { index, bookSource ->
                bookSource.customOrder = index
            }
            appDb.bookSourceDao.upOrder(sources)
        }
    }

}