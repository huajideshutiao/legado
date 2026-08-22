package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.OldRssSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.entities.toBookSource
import io.legado.app.help.DirectLinkUploadStoreProviders
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.ruleFileName
import io.legado.app.help.storage.RestoreShared.restoreLocked
import io.legado.app.help.storage.RestoreShared.restoreOldRecord
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.GSON
import io.legado.app.utils.decodeAnyMapOrNull
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.midnightSecFromDayKey
import io.legado.app.utils.prevDayKey
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 恢复流程 (KMP 共享版, 全平台唯一实现)。
 *
 * # 职责
 * 解压后的 JSON 读取 / 批量写库 / 配置文件回写 / prefs 写回全部在这里,
 * app 端 [io.legado.app.help.storage.Restore] 只剩 Android 钩子 (SAF content://
 * 解压、config.xml 旧格式解析、主题重载、恢复后 toast/图标/日夜间刷新),
 * 经 [BackupRestoreHooks] 注入, 保证 WebDav 与本地恢复走同一条核心路径。
 *
 * # 与原 app 端实现的对应
 * - 恢复顺序、忽略项判断、servers.json 解密、prefs 类型映射均与原版逐项一致
 * - java.util.Calendar 的旧格式阅读记录迁移算法改用 [prevDayKey] / [midnightSecFromDayKey]
 */
object RestoreShared {

    private const val TAG = "Restore"

    /** 互斥锁, 防止并发恢复 (与原版 mutex 同语义)。 */
    private val mutex = Mutex()

    /**
     * 恢复入口 (互斥锁保护)。
     *
     * @param path 已解压的备份目录绝对路径 (对应 [BackupShared.backupPath])
     */
    suspend fun restoreLocked(path: String) {
        mutex.withLock {
            restore(path)
        }
    }

    /**
     * 从本地 zip 文件恢复 (解压 → [restoreLocked])。
     *
     * 与原版 `Restore.restore(context, uri)` 同语义: 先清空备份工作目录残留, 解压 zip 到
     * [BackupShared.backupPath] 再恢复; Android 的 content:// 由
     * [BackupRestoreHook.unZipBackup] 处理。
     *
     * @param zipPath 本地 zip 文件路径 (Android 可为 content:// uri)
     */
    suspend fun restoreFromZip(zipPath: String) {
        val hooks = BackupRestoreHooks.get()
        val destPath = BackupShared.backupPath
        runCatching {
            BackupFileOps.delete(destPath)
            if (!hooks.unZipBackup(zipPath, destPath)) {
                BackupFileOps.unZipToPath(zipPath, destPath)
            }
        }.onFailure {
            AppLog.put("复制解压文件出错\n${it.message}", it, tag = TAG)
            return
        }
        runCatching {
            restoreLocked(destPath)
            hooks.onRestoreFromZipFinished()
        }.onFailure {
            AppLog.put("恢复备份出错\n${it.message}", it, toast = true, tag = TAG)
        }
    }

    /**
     * 实际恢复逻辑 (不加锁, 由 [restoreLocked] 包装)。
     *
     * 步骤与原版逐项对应:
     * 1. 逐个 JSON 文件 → DAO 批量 insert (含阅读记录新旧格式)
     * 2. servers.json 解密 → serverDao
     * 3. 直链上传规则 / 主题配置 / 阅读界面配置 (忽略项生效)
     * 4. config.json (无则 config.xml) → prefs 写回
     * 5. 阅读配置项从 prefs 刷新 + 宿主 UI 钩子
     */
    private suspend fun restore(path: String) {
        val hooks = BackupRestoreHooks.get()
        val aes = BackupAES()
        val appDb = AppDbProviders.get()
        val sep = BackupFileOps.separator

        // 1. DAO 数据恢复 (与原版顺序一致)
        fileToListT<Book>(path, "bookshelf.json")?.let { books ->
            books.forEach { book -> book.upType() }
            books.filter { book -> book.isLocal }
                .forEach { book -> book.coverUrl = FileBook.getCoverPath(book.bookUrl) }
            val newBooks = arrayListOf<Book>()
            val ignoreLocalBook = BackupConfigShared.ignoreLocalBook
            books.forEach { book ->
                if (ignoreLocalBook && book.isLocal) {
                    return@forEach
                }
                if (appDb.bookDao.has(book.bookUrl)) {
                    // 原版捕获 SQLiteConstraintException 后改 insert; commonMain 无该类型, 按异常回退
                    // onFailure 首行 ensureActive: update 是挂起取消点, 别把取消当成约束冲突再去 insert
                    runCatching { appDb.bookDao.update(book) }
                        .onFailure {
                            currentCoroutineContext().ensureActive()
                            appDb.bookDao.insert(book)
                        }
                } else {
                    newBooks.add(book)
                }
            }
            appDb.bookDao.insert(*newBooks.toTypedArray())
        }
        fileToListT<Bookmark>(path, "bookmark.json")?.let {
            appDb.bookmarkDao.insert(*it.toTypedArray())
        }
        fileToListT<BookGroup>(path, "bookGroup.json")?.let {
            appDb.bookGroupDao.insert(*it.toTypedArray())
        }
        fileToListT<BookSource>(path, "bookSource.json")?.let {
            appDb.bookSourceDao.insert(*it.toTypedArray())
        }
        fileToListT<OldRssSource>(path, "rssSources.json")?.let {
            appDb.bookSourceDao.insert(*it.map { old -> old.toBookSource() }.toTypedArray())
        }
        fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
            appDb.replaceRuleDao.insert(*it.toTypedArray())
        }
        fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
            appDb.searchKeywordDao.insert(*it.toTypedArray())
        }
        fileToListT<RuleSub>(path, "sourceSub.json")?.let {
            appDb.ruleSubDao.insert(*it.toTypedArray())
        }
        fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
            appDb.txtTocRuleDao.insert(*it.toTypedArray())
        }
        fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
            appDb.httpTTSDao.insert(*it.toTypedArray())
        }
        fileToListT<DictRule>(path, "dictRule.json")?.let {
            appDb.dictRuleDao.insert(*it.toTypedArray())
        }
        fileToListT<SourceFilterRule>(path, "sourceFilterRule.json")?.let {
            appDb.sourceFilterRuleDao.insert(*it.toTypedArray())
        }
        fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
            appDb.keyboardAssistsDao.insert(*it.toTypedArray())
        }

        currentCoroutineContext().ensureActive()
        restoreReadRecord(path)

        // 2. servers.json 解密 (若加密, 与原版同逻辑)
        runCatching {
            val serversFile = path + sep + "servers.json"
            if (BackupFileOps.exists(serversFile)) {
                var json = BackupFileOps.readText(serversFile)
                if (!json.isJsonArray()) {
                    json = aes.decryptStr(json)
                }
                GSON.fromJsonArray<Server>(json).getOrNull()?.let {
                    appDb.serverDao.insert(*it.toTypedArray())
                }
            }
        }.onFailure {
            // 块内 serverDao.insert 是挂起取消点, 首行 ensureActive 把取消放出去, 不当成"恢复出错"记日志
            currentCoroutineContext().ensureActive()
            AppLog.put("恢复服务器配置出错\n${it.message}", it, tag = TAG)
        }

        currentCoroutineContext().ensureActive()

        // 3.1 直链上传规则 (原版: ACache.put(ruleFileName, json) 原样写回)
        runCatching {
            val ruleFile = path + sep + ruleFileName
            if (BackupFileOps.exists(ruleFile)) {
                DirectLinkUploadStoreProviders.get()
                    ?.putConfigJson(BackupFileOps.readText(ruleFile))
            }
        }.onFailure {
            AppLog.put("恢复直链上传出错\n${it.message}", it, tag = TAG)
        }

        // 3.2 主题配置: 覆盖 themeConfig.json 后重载 (原版 delete + copyTo + ThemeConfig.upConfig())
        runCatching {
            val themeConfigFile = path + sep + BackupShared.THEME_CONFIG_FILE_NAME
            if (BackupFileOps.exists(themeConfigFile)) {
                BackupFileOps.delete(BackupShared.themeConfigFilePath)
                BackupFileOps.copyFile(themeConfigFile, BackupShared.themeConfigFilePath)
                hooks.onThemeConfigRestored()
            }
        }.onFailure {
            AppLog.put("恢复主题出错\n${it.message}", it, tag = TAG)
        }

        // 3.3 阅读界面配置 (备份忽略项勾选「阅读界面」时跳过, 与原版一致)
        if (!BackupConfigShared.ignoreReadConfig) {
            runCatching {
                val readConfigFile = path + sep + ReadBookConfigShared.configFileName
                if (BackupFileOps.exists(readConfigFile)) {
                    val readBookConfig = ReadBookConfigProviders.get()
                    BackupFileOps.delete(readBookConfig.configFilePath)
                    BackupFileOps.copyFile(readConfigFile, readBookConfig.configFilePath)
                    readBookConfig.initConfigs()
                }
            }.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.message}", it, tag = TAG)
            }
            runCatching {
                val shareConfigFile = path + sep + ReadBookConfigShared.shareConfigFileName
                if (BackupFileOps.exists(shareConfigFile)) {
                    val readBookConfig = ReadBookConfigProviders.get()
                    BackupFileOps.delete(readBookConfig.shareConfigFilePath)
                    BackupFileOps.copyFile(shareConfigFile, readBookConfig.shareConfigFilePath)
                    readBookConfig.initShareConfig()
                }
            }.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.message}", it, tag = TAG)
            }
        }

        currentCoroutineContext().ensureActive()

        // 4. config.json → prefs 写回; 无 config.json 时读旧版 config.xml (宿主钩子)
        runCatching {
            val configMap = mutableMapOf<String, Any?>()
            val configFile = path + sep + "config.json"
            if (BackupFileOps.exists(configFile)) {
                decodeAnyMapOrNull(BackupFileOps.readText(configFile))?.let { configMap.putAll(it) }
            } else {
                hooks.readLegacyConfig(path)?.let { configMap.putAll(it) }
            }
            val prefs = PreferenceProviders.get()
            configMap.forEach { (key, value) ->
                if (BackupConfigShared.keyIsNotIgnore(key)) {
                    when (key) {
                        PreferKey.webDavPassword -> {
                            runCatching { aes.decryptStr(value.toString()) }
                                .getOrNull()?.let { prefs.putString(key, it) }
                                ?: run {
                                    // 解密失败: 若当前 webDavPassword 也为空, 用密文兜底
                                    if (prefs.getString(PreferKey.webDavPassword, "").isBlank()) {
                                        prefs.putString(key, value.toString())
                                    }
                                }
                        }

                        else -> runCatching { putPrefByType(prefs, key, value) }
                            .onFailure {
                                // 单 key 失败只跳过该 key, 不中断整批: 桌面端 java.util.prefs
                                // key ≤ 80 / value ≤ 8192, 历史备份中的动态超长 key
                                // (如 video_progress_<超长url>) 会在此抛 IllegalArgumentException
                                AppLog.put(
                                    "恢复配置项失败, 已跳过: $key\n${it.message}",
                                    it,
                                    tag = TAG
                                )
                            }
                    }
                }
            }
        }.onFailure {
            AppLog.put("恢复配置出错\n${it.message}", it, tag = TAG)
        }

        // 5. 阅读配置项从 prefs 刷新 (与原版 ReadBookConfig.apply { ... } 一致)
        runCatching {
            val prefs = PreferenceProviders.get()
            ReadBookConfigProviders.get().apply {
                comicStyleSelect = prefs.getInt(PreferKey.comicStyleSelect, 0)
                readStyleSelect = prefs.getInt(PreferKey.readStyleSelect, 0)
                shareLayout = prefs.getBoolean(PreferKey.shareLayout, false)
                hideStatusBar = prefs.getBoolean(PreferKey.hideStatusBar, false)
                hideNavigationBar = prefs.getBoolean(PreferKey.hideNavigationBar, false)
                autoReadSpeed = prefs.getInt(PreferKey.autoReadSpeed, 46)
            }
        }.onFailure {
            AppLog.put("刷新阅读配置出错\n${it.message}", it, tag = TAG)
        }

        currentCoroutineContext().ensureActive()
        // 6. 宿主 UI 钩子 (app 端: toast 成功 + 图标切换 + 日夜间应用)
        hooks.onRestoreFinished()
    }

    /**
     * 恢复阅读记录 (新格式 + 旧格式迁移, 与 app 端 [io.legado.app.help.storage.Restore.restoreReadRecord] 同语义)。
     *
     * - 新格式 (startSec > 0 && endSec > startSec): 直接 insert
     * - 旧格式 (readTime > 0): 用 [restoreOldRecord] 迁移算法还原为时间段
     *
     * @see io.legado.app.help.storage.RestoreShared.ReadRecordBackup
     */
    private suspend fun restoreReadRecord(path: String) {
        val backups = fileToListT<ReadRecordBackup>(path, "readRecord.json") ?: return
        if (backups.isEmpty()) return
        val dao = AppDbProviders.get().readRecordDao
        val nowSec = systemCurrentTimeMillis() / 1000
        backups.forEach { b ->
            if (b.bookName.isEmpty()) return@forEach
            if (b.startSec > 0 && b.endSec > b.startSec) {
                // 新格式：直接插入
                dao.insertSession(ReadRecord(b.bookName, b.day, b.startSec, b.endSec))
            } else if (b.readTime > 0) {
                // 旧格式：用迁移算法还原为时间段 (与 app 端 Restore.restoreOldRecord 同算法)
                val endSec0 = if (b.lastRead > 0) b.lastRead / 1000 else nowSec
                val day0 = if (b.day != 0) b.day else ReadRecord.dayKey(endSec0)
                restoreOldRecord(dao, b.bookName, day0, b.readTime / 1000, endSec0)
            }
        }
    }

    /**
     * 旧格式 readRecord 迁移算法 (与 app 端 [io.legado.app.help.storage.Restore.restoreOldRecord] 完全一致)。
     *
     * 旧格式 readRecord 只有累计 readTime (毫秒) + lastRead (毫秒), 没有具体阅读时间段。
     * 本算法按天倒推分割 readTime 为多个 session 段:
     * 1. 当天窗口: 从 endSec 向前最多 16h (或当天已过时间, 取小), 插入第一段
     * 2. 之后每天: 窗口固定 4:00-20:00 (20 - 4 = 16h), 最多 16h, 继续向前倒推
     * 3. 直到 readTime 分配完
     *
     * 用 [prevDayKey] / [midnightSecFromDayKey] 替代 app 端 java.util.Calendar,
     * 保证 commonMain 跨平台可用 (jvmAndAndroid/iOS/ohos actual 行为等价)。
     */
    private suspend fun restoreOldRecord(
        dao: io.legado.app.data.dao.ReadRecordDao,
        bookName: String, day: Int, remainingSecs: Long, endSec: Long
    ) {
        var remaining = remainingSecs
        var curDay = day

        val maxBack = minOf(16L * 3600, (endSec - midnightSecFromDayKey(curDay)).coerceAtLeast(0))
        val seg0 = minOf(remaining, maxBack)
        if (seg0 > 0) {
            dao.insertSession(ReadRecord(bookName, curDay, endSec - seg0, endSec))
            remaining -= seg0
        }
        curDay = prevDayKey(curDay)
        while (remaining > 0) {
            val winEnd = midnightSecFromDayKey(curDay) + 20L * 3600
            val seg = minOf(remaining, 16L * 3600)
            dao.insertSession(ReadRecord(bookName, curDay, winEnd - seg, winEnd))
            remaining -= seg
            curDay = prevDayKey(curDay)
        }
    }

    /**
     * 通用: 读 JSON 文件 → 反序列化为 List<T>。
     *
     * 文件不存在返回 null; 解析失败记录日志并 toast (与原版一致), 不抛。
     */
    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        return runCatching {
            val file = path + BackupFileOps.separator + fileName
            if (!BackupFileOps.exists(file)) {
                AppLog.putDebug("阅读恢复备份 $fileName 文件不存在", tag = TAG)
                return null
            }
            val json = BackupFileOps.readText(file)
            GSON.fromJsonArray<T>(json).getOrThrow()
        }.onFailure {
            AppLog.put("$fileName\n读取解析出错\n${it.message}", it, toast = true, tag = TAG)
        }.getOrNull()
    }

    /**
     * 兼容 app 端 [io.legado.app.help.storage.Restore.ReadRecordBackup] 数据结构。
     *
     * 含旧字段 (readTime/lastRead), 桌面端仅识别新格式 (startSec/endSec)。
     */
    @kotlinx.serialization.Serializable
    private data class ReadRecordBackup(
        val bookName: String = "",
        val day: Int = 0,
        val startSec: Long = 0,
        val endSec: Long = 0,
        // 旧字段, 仅用于兼容旧备份 (桌面端不解析, app 端用)
        val readTime: Long = 0,
        val lastRead: Long = 0
    )

    /**
     * 把 Any? 配置值按类型写入 [PreferenceProviders]。
     *
     * 与 app 端 [io.legado.app.help.storage.Restore] 中 `defaultSharedPreferences.edit`
     * 分支同语义: 支持 Int/Boolean/Long/Float/Double/String 五种类型, Long/Double 超出
     * Int 范围走 putLong, 否则走 putInt (与 app 端完全一致)。
     */
    private fun putPrefByType(
        prefs: io.legado.app.help.config.PreferenceProvider,
        key: String,
        value: Any?
    ) {
        when (value) {
            is Int -> prefs.putInt(key, value)
            is Boolean -> prefs.putBoolean(key, value)
            is Long -> {
                if (value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
                    prefs.putInt(key, value.toInt())
                } else {
                    prefs.putLong(key, value)
                }
            }
            is Float -> prefs.putFloat(key, value)
            is Double -> {
                if (value == value.toLong().toDouble()) {
                    val longValue = value.toLong()
                    if (longValue >= Int.MIN_VALUE && longValue <= Int.MAX_VALUE) {
                        prefs.putInt(key, longValue.toInt())
                    } else {
                        prefs.putLong(key, longValue)
                    }
                } else {
                    prefs.putFloat(key, value.toFloat())
                }
            }
            is String -> prefs.putString(key, value)
        }
    }
}
