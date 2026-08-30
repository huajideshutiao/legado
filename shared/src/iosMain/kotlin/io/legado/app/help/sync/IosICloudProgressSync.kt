package io.legado.app.help.sync

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUbiquitousKeyValueStore
import platform.Foundation.NSUbiquitousKeyValueStoreChangedKeysKey
import platform.Foundation.NSUbiquitousKeyValueStoreDidChangeExternallyNotification

/**
 * 阅读进度的 iCloud KV 通道 (对照 [io.legado.app.help.AppWebDavShared] 的进度同步三件套)。
 *
 * 用 [NSUbiquitousKeyValueStore] 而非 iCloud Documents: 进度是高频小数据 (每本一条
 * [BookProgress] JSON), KV 存储由系统合并、秒级推送、无文件冲突处理成本; 代价是总容量
 * 1MB / 1024 key 上限, 超限的写入会被系统丢弃 (故 [MAX_VALUE_BYTES] 兜底跳过异常大值)。
 *
 * # 与 WebDav 的关系
 * - 数据模型完全复用 [BookProgress] (name/author/durChapterIndex/durChapterPos/
 *   durChapterTime/durChapterTitle), JSON 也是同一份, 两条通道可互导。
 * - key 用 `bp.` + md5(`${name}_${author}`) 前 16 位: 对应 WebDav 的
 *   `${name}_${author}.json` 文件名 (KV key 限 ASCII 且不宜过长, 故取哈希)。
 * - 冲突策略对齐 WebDav [io.legado.app.help.AppWebDavShared.downloadAllBookProgress]:
 *   云端进度更靠后 (章节序号大, 或同章位置更靠后) 才覆盖本地。WebDav 靠文件
 *   lastModify 与 `book.syncTime` 做前置过滤, KV 没有 per-key 修改时间, 改用
 *   `durChapterTime` 比较, 语义等价 (都是"云端比本地新才覆盖")。
 *
 * 启用前置条件见 [IosICloud] 的四步清单; 未启用时所有入口直接 return。
 */
object IosICloudProgressSync {

    private const val KEY_PREFIX = "bp."

    /** 单值上限, 超出直接跳过 (KV 总容量 1MB, 单本进度实际只有几十字节)。 */
    private const val MAX_VALUE_BYTES = 4 * 1024

    private val store: NSUbiquitousKeyValueStore
        get() = NSUbiquitousKeyValueStore.defaultStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var changeObserver: Any? = null

    /** iCloud 已启用且用户开了进度同步 (三个入口共用的前置判断)。 */
    private val syncEnabled: Boolean
        get() = IosICloud.enabled &&
            runCatching { AppConfigProviders.get().syncBookProgress }.getOrDefault(false)

    /** 上传单本进度 (对照 [io.legado.app.help.AppWebDavShared.uploadBookProgress])。 */
    fun uploadBookProgress(book: Book) {
        uploadBookProgress(BookProgress(book))
    }

    /** 上传预制 [BookProgress] 实体。 */
    fun uploadBookProgress(progress: BookProgress) {
        if (!syncEnabled) return
        runCatching {
            val json = GSON.toJson(progress)
            if (json.encodeToByteArray().size > MAX_VALUE_BYTES) {
                AppLog.put("iCloud 进度过大已跳过: ${progress.name}")
                return
            }
            store.setObject(json, forKey = progressKey(progress.name, progress.author))
            store.synchronize()
        }.onFailure {
            AppLog.put("iCloud 上传进度失败\n${it.message}", it)
        }
    }

    /** 读取单本云端进度, 无记录返回 null。 */
    fun getBookProgress(book: Book): BookProgress? {
        if (!IosICloud.enabled) return null
        val json = store.stringForKey(progressKey(book.name, book.author)) ?: return null
        return GSON.fromJsonObject<BookProgress>(json).getOrNull()
    }

    /**
     * 拉取全部云端进度写回本地库 (对照 [io.legado.app.help.AppWebDavShared.downloadAllBookProgress])。
     */
    suspend fun downloadAllBookProgress() {
        if (!syncEnabled) return
        store.synchronize()
        AppDbProviders.get().bookDao.all().forEach { book ->
            applyRemoteProgress(book.bookUrl, getBookProgress(book))
        }
    }

    /**
     * 挂 [NSUbiquitousKeyValueStoreDidChangeExternallyNotification] 观察者:
     * 其他设备改了进度, 系统推送变更 key 列表, 这里按 key 反查本地书籍写回。
     */
    fun startObserving() {
        if (changeObserver != null) return
        changeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            NSUbiquitousKeyValueStoreDidChangeExternallyNotification,
            `object` = store,
            queue = NSOperationQueue.mainQueue,
        ) { notification ->
            val keys = notification?.userInfo
                ?.get(NSUbiquitousKeyValueStoreChangedKeysKey) as? List<*>
            val changed = keys.orEmpty().filterIsInstance<String>()
                .filter { it.startsWith(KEY_PREFIX) }
            if (changed.isNotEmpty()) {
                scope.launch { onExternalChange(changed.toSet()) }
            }
        }
        store.synchronize()
    }

    /** 摘掉观察者 (与 [IosICloud.disable] 配套)。 */
    fun stopObserving() {
        changeObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        changeObserver = null
    }

    /** KV key 无法反解出书名, 遍历本地书籍算 key 反查命中项。 */
    private suspend fun onExternalChange(changedKeys: Set<String>) {
        if (!syncEnabled) return
        runCatching {
            AppDbProviders.get().bookDao.all().forEach { book ->
                if (progressKey(book.name, book.author) !in changedKeys) return@forEach
                applyRemoteProgress(book.bookUrl, getBookProgress(book))
            }
        }.onFailure {
            AppLog.put("iCloud 进度变更处理失败\n${it.message}", it)
        }
    }

    /**
     * 云端进度写回单本书。
     *
     * 从 DB 重查最新行再写, 避免拿入队时的旧快照整行覆盖用户并发修改
     * (与 AppWebDavShared 的比较条件一致: 章节更靠后才覆盖)。
     */
    private suspend fun applyRemoteProgress(bookUrl: String, progress: BookProgress?) {
        progress ?: return
        val dao = AppDbProviders.get().bookDao
        val book = dao.getBook(bookUrl) ?: return
        if (progress.durChapterTime <= book.durChapterTime) return
        val ahead = progress.durChapterIndex > book.durChapterIndex ||
            (progress.durChapterIndex == book.durChapterIndex &&
                progress.durChapterPos > book.durChapterPos)
        if (!ahead) return
        book.durChapterIndex = progress.durChapterIndex
        book.durChapterPos = progress.durChapterPos
        book.durChapterTitle = progress.durChapterTitle
        book.durChapterTime = progress.durChapterTime
        dao.update(book)
    }

    private fun progressKey(name: String, author: String): String {
        return KEY_PREFIX + MD5Utils.md5Encode16("${name}_${author}")
    }
}
