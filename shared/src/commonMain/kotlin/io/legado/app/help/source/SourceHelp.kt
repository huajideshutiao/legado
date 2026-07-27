package io.legado.app.help.source

import io.legado.app.constant.SourceType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart

/**
 * 书源/音频源 CRUD 与排序维护 (shared commonMain 下沉版)。
 *
 * 原 app 端 SourceHelp 依赖 appDb/ReadBook/AudioPlay/SourceConfig/AppCacheManager,
 * 下沉后改走 provider 间接:
 * - `appDb.xxxDao` → `AppDbProviders.get().xxxDao`
 * - `appDb.runInTransaction { ... }` → `AppDbProviders.get().runInTransaction { ... }`
 * - `ReadBook.bookSource` / `AudioPlay.bookSource` 缓存命中 → [SourceHelpAccessors] provider
 * - `SourceConfig.removeSource(s)` + `AppCacheManager.clearSourceVariables()` 副作用
 *   → [SourceHelpAccessors] provider 的 onBookSourceDeleted/onBookSourcesDeleted
 *
 * 行为与原 app 端完全一致, 仅多一层 provider 间接。app 端调用方 import 不变
 * (跨模块同包名同签名 object 自动合并, 但本类整体下沉, app 端原文件已删除)。
 *
 * 注: 全部方法改为 suspend (DAO 调用为 suspend)。
 * commonMain 中禁止使用 runBlocking (Native 端死锁风险), 故同步入口需由调用方
 * 在协程作用域内调用, 或在 JVM 专属路径 (如 app 端 Glide 同步回调) 自行 runBlocking。
 */
object SourceHelp {

    suspend fun getSource(key: String?): BaseSource? {
        key ?: return null
        // 优先返回当前阅读/播放书籍的书源缓存实例 (对应原 ReadBook.bookSource / AudioPlay.bookSource)
        SourceHelpAccessors.get().getCachedReadingBookSource(key)?.let { return it }
        SourceHelpAccessors.get().getCachedAudioBookSource(key)?.let { return it }
        return AppDbProviders.get().bookSourceDao.getBookSource(key)
    }

    suspend fun getSource(key: String?, type: Int): BaseSource? {
        key ?: return null
        return when (type) {
            SourceType.book, SourceType.rss -> AppDbProviders.get().bookSourceDao.getBookSource(key)
            SourceType.tts -> AppDbProviders.get().httpTTSDao.get(
                key.substringAfter("httpTts:").toLongOrNull() ?: -1
            )
            else -> null
        }
    }

    suspend fun deleteSource(key: String, type: Int) {
        when (type) {
            SourceType.book, SourceType.rss -> deleteBookSource(key)
            SourceType.tts -> {
                AppDbProviders.get().httpTTSDao.get(
                    key.substringAfter("httpTts:").toLongOrNull() ?: -1
                )?.let {
                    AppDbProviders.get().httpTTSDao.delete(it)
                }
            }
        }
    }

    suspend fun deleteBookSourceParts(sources: List<BookSourcePart>) {
        if (sources.isEmpty()) return
        val keys = sources.map { it.bookSourceUrl }
        deleteBookSourcesByKeys(sources = sources, keys = keys)
    }

    suspend fun deleteBookSources(sources: List<BookSource>) {
        if (sources.isEmpty()) return
        val keys = sources.map { it.bookSourceUrl }
        deleteBookSourcesByKeys(keys = keys)
    }

    private suspend fun deleteBookSourcesByKeys(
        sources: List<BookSourcePart>? = null,
        keys: List<String>
    ) {
        AppDbProviders.get().runInTransactionSuspending {
            if (sources != null) {
                AppDbProviders.get().bookSourceDao.delete(sources)
            } else {
                // chunked 返回 List, forEach 是 inline 允许 suspend 调用 (chunked 的 transform lambda 非 suspend)
                keys.chunked(999).forEach { AppDbProviders.get().bookSourceDao.deleteIn(it) }
            }
            // deleteSourceVariables 含 LIKE 'v_KEY_%'，无法折叠成 IN 批量；逐键执行，但仍包在外层事务里
            keys.forEach { AppDbProviders.get().cacheDao.deleteSourceVariables(it) }
        }
        // 对应原 SourceConfig.removeSources(keys) + AppCacheManager.clearSourceVariables()
        SourceHelpAccessors.get().onBookSourcesDeleted(keys)
    }

    private suspend fun deleteBookSourceInternal(key: String) {
        AppDbProviders.get().bookSourceDao.delete(key)
        AppDbProviders.get().cacheDao.deleteSourceVariables(key)
        // 对应原 SourceConfig.removeSource(key)
        SourceHelpAccessors.get().onBookSourceDeleted(key)
    }

    suspend fun deleteBookSource(key: String) {
        deleteBookSourceInternal(key)
        // 对应原 AppCacheManager.clearSourceVariables() (onBookSourceDeleted 已含,
        // 但原 deleteBookSource 调用 Internal 后再调 clearSourceVariables, 与 deleteBookSourcesByKeys
        // 在事务后统一调 onBookSourcesDeleted 含 clearSourceVariables 的语义一致,
        // 这里 onBookSourceDeleted 已涵盖, 无需重复调用)
    }

    suspend fun enableSource(key: String, type: Int, enable: Boolean) {
        when (type) {
            SourceType.book, SourceType.rss -> AppDbProviders.get().bookSourceDao.enable(key, enable)
            SourceType.tts -> Unit
        }
    }

    /**
     * 调整排序序号
     */
    suspend fun adjustSortNumber() {
        val dao = AppDbProviders.get().bookSourceDao
        val max = dao.maxOrder()
        val min = dao.minOrder()
        val rangeOverflow = max > 99999 || min < -99999
        val hasDup = dao.hasDuplicateOrder()
        if (!rangeOverflow && !hasDup) return

        // 快路径：仅绝对值越界，但 max-min 还能塞进 [-99999, 99999]。
        // 一条 UPDATE 整体平移就能修好，免去加载并回写每个 BookSourcePart。
        if (!hasDup && (max - min) <= 199998) {
            dao.shiftCustomOrder(-((max + min) / 2))
            return
        }

        // 兜底：全量重排为 0..N-1
        val sources = dao.allPart()
        sources.forEachIndexed { index, bookSource ->
            bookSource.customOrder = index
        }
        dao.upOrder(sources)
    }

}
