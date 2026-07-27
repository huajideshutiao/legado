package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabaseProviders
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
import io.legado.app.help.parseDirectLinkUploadRule
import io.legado.app.help.ruleFileName
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
 * 恢复流程的 KMP 共享版 (与 [AppWebDavShared] / [BackupShared] 配套)。
 *
 * # 背景
 * app 端 [io.legado.app.help.storage.Restore] 单例依赖大量 Android-specific 类
 * (Context / Uri / SQLiteConstraintException / XmlPullParser / FileDoc /
 * ACache / FileBook / LauncherIconHelp / ThemeConfig.applyDayNight(appCtx) /
 * defaultSharedPreferences.edit / appCtx.toastOnUi / R.string / BuildConfig.DEBUG 等),
 * 不能直接下沉 shared/commonMain。本 KMP 版抽掉 Android 专属依赖, 仅保留核心恢复流程
 * (zip 解压 → JSON → DAO 批量 insert + config.json 写回 prefs), 桌面端 / iOS 端可直接复用。
 *
 * # 与 app 端 [io.legado.app.help.storage.Restore] 的差异
 * - **不依赖 Context / Uri**: 仅接受解压后的本地 [path] 目录, 由 [BackupFileOps] 跨平台访问
 * - **不依赖 XmlPullParser**: 不兼容旧版本 XML 备份 (config.xml), 仅支持 JSON 格式
 *   (桌面端是新接入, 无历史 XML 备份; app 端如需仍用原 [io.legado.app.help.storage.Restore])
 * - **不依赖 ACache / FileBook / LauncherIconHelp / ThemeConfig.applyDayNight**:
 *   这些子项的恢复保留在 app 端 [io.legado.app.help.storage.Restore]
 * - **不依赖 defaultSharedPreferences.edit**: 配置写回走 [PreferenceProviders]
 *   (支持 String/Int/Boolean/Long/Float 类型, 与 app 端 SharedPreferences 类型映射一致)
 * - **简化 restoreReadRecord**: 仅支持新格式 (startSec/endSec), 旧格式 (readTime/lastRead)
 *   跳过并 AppLog 提示 (桌面端无历史旧备份, app 端如需仍用原 [Restore])
 * - **不调用 appCtx.toastOnUi(R.string.restore_success)**: 成功提示由调用方决定
 *   (desktop UI 用 Compose state 自行处理)
 *
 * # 命名后缀 Shared
 * 与 app 端 [io.legado.app.help.storage.Restore] 同包同名会与 app 模块冲突
 * (shared androidMain target 被 app 模块依赖), 故加 `Shared` 后缀避免歧义,
 * 同时表明这是 KMP 共享版本。
 *
 * # 模式参考
 * - app 端 [io.legado.app.help.storage.Restore] (业务对照原型)
 * - shared/commonMain `io.legado.app.model.webBook.WebBook` (KMP 业务编排层下沉先例)
 */
object RestoreShared {

    /** 互斥锁, 防止并发恢复 (与 app 端 [io.legado.app.help.storage.Restore.mutex] 同语义)。 */
    private val mutex = Mutex()

    /**
     * 恢复入口 (互斥锁保护)。
     *
     * 与 app 端 [io.legado.app.help.storage.Restore.restoreLocked] 同语义:
     * - 调用方先解压 zip 到 [path] (由 [AppWebDavShared.restoreWebDav] 完成)
     * - 本函数从 [path] 读 JSON → 批量 insert DAO + 写回 prefs
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
     * 与 app 端 [io.legado.app.help.storage.Restore.restore](context, uri) 的
     * "解压到 backupPath → restoreLocked" 流程同语义: 先清空备份工作目录残留,
     * 解压 zip 到 [BackupShared.backupPath], 再走 [restoreLocked]。
     *
     * 供桌面端本地恢复复用 (替代 inline 的 unZipToPath + restoreLocked),
     * content scheme 由调用方先解析为本地路径。
     *
     * @param zipPath 本地 zip 文件绝对路径
     */
    suspend fun restoreFromZip(zipPath: String) {
        val destPath = BackupShared.backupPath
        BackupFileOps.delete(destPath)
        BackupFileOps.unZipToPath(zipPath, destPath)
        restoreLocked(destPath)
    }

    /**
     * 实际恢复逻辑 (不加锁, 由 [restoreLocked] 包装)。
     *
     * 步骤:
     * 1. 逐个 JSON 文件 → DAO 批量 insert
     * 2. servers.json 解密 (若加密) → serverDao.insert
     * 3. config.json → PreferenceProviders 写回 (过滤 ignorePrefKeys)
     */
    private suspend fun restore(path: String) {
        val aes = BackupAES()
        val appDb = AppDatabaseProviders.get().appDb
        val sep = BackupFileOps.separator

        // 1. DAO 数据恢复 (与 app 端顺序一致)
        fileToListT<Book>(path, "bookshelf.json")?.let { books ->
            // 与 app 端 Restore.kt 一致: 先刷 type, 本地书封面路径重算, 再按 ignoreLocalBook 过滤
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
                    runCatching { appDb.bookDao.update(book) }
                        .onFailure { appDb.bookDao.insert(book) }
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

        // 2. servers.json 解密 (若加密, 与 app 端同逻辑)
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
            AppLog.put("RestoreShared 恢复服务器配置出错\n${it.message}", it)
        }

        currentCoroutineContext().ensureActive()

        // 2.1 app 端独有配置文件恢复 (与 app 端 Restore.kt:180-217 同语义)
        // readConfig.json / shareReadConfig.json: 复制回 filesDir 后重载入 (落盘, 非仅内存)
        // themeConfig.json: 解析 JSON → 通过 ThemeConfigProvider.addConfig 逐个写入
        // directLinkUploadRule.json: 桌面端无 DirectLinkUpload, 跳过
        // 与 app 端 Restore.kt 一致: 备份忽略项勾选「阅读界面」时不恢复阅读界面配置
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
                AppLog.put("RestoreShared 恢复阅读界面出错\n${it.message}", it)
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
                AppLog.put("RestoreShared 恢复阅读界面出错\n${it.message}", it)
            }
        }
        runCatching {
            val themeConfigFile = path + sep + "themeConfig.json"
            if (BackupFileOps.exists(themeConfigFile)) {
                val json = BackupFileOps.readText(themeConfigFile)
                GSON.fromJsonArray<io.legado.app.help.config.ThemeConfigData>(json)
                    .getOrNull()?.let { themes ->
                        val themeProvider = io.legado.app.help.config.ThemeConfigProviders.get()
                        themes.forEach { themeProvider.addConfig(it) }
                    }
            }
        }.onFailure {
            AppLog.put("RestoreShared 恢复 themeConfig 出错\n${it.message}", it)
        }
        // directLinkUploadRule.json (与 app 端 Restore.kt ACache.put(ruleFileName, json) 同语义)
        // 解析 JSON → DirectLinkUploadRule, 通过 DirectLinkUploadStoreProviders.putConfig 写回
        runCatching {
            val ruleFile = path + sep + ruleFileName
            if (BackupFileOps.exists(ruleFile)) {
                val json = BackupFileOps.readText(ruleFile)
                parseDirectLinkUploadRule(json)?.let { rule ->
                    DirectLinkUploadStoreProviders.get()?.putConfig(rule)
                }
            }
        }.onFailure {
            AppLog.put("RestoreShared 恢复 directLinkUploadRule 出错\n${it.message}", it)
        }

        currentCoroutineContext().ensureActive()

        // 3. config.json → PreferenceProviders 写回 (支持 String/Int/Boolean/Long/Float/Double)
        runCatching {
            val configFile = path + sep + "config.json"
            if (BackupFileOps.exists(configFile)) {
                val json = BackupFileOps.readText(configFile)
                val configMap = decodeAnyMapOrNull(json)
                val prefs = PreferenceProviders.get()
                configMap?.forEach { (key, value) ->
                    if (BackupConfigShared.keyIsNotIgnore(key)) {
                        when (key) {
                            PreferKey.webDavPassword -> {
                                runCatching { aes.decryptStr(value.toString()) }
                                    .getOrNull()?.let { prefs.putString(key, it) }
                                    ?: run {
                                        // 解密失败: 若当前 prefs 中 webDavPassword 也为空, 用密文兜底
                                        if (prefs.getString(PreferKey.webDavPassword, "").isBlank()) {
                                            prefs.putString(key, value.toString())
                                        }
                                    }
                            }
                            else -> putPrefByType(prefs, key, value)
                        }
                    }
                }
            }
        }.onFailure {
            AppLog.put("RestoreShared 恢复 config.json 出错\n${it.message}", it)
        }

        currentCoroutineContext().ensureActive()
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
        val dao = AppDatabaseProviders.get().appDb.readRecordDao
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
     * 文件不存在 / 解析失败均返回 null (走 AppLog 记录, 不抛)。
     */
    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        return runCatching {
            val file = path + BackupFileOps.separator + fileName
            if (!BackupFileOps.exists(file)) return null
            val json = BackupFileOps.readText(file)
            GSON.fromJsonArray<T>(json).getOrThrow()
        }.onFailure {
            AppLog.put("$fileName\n读取解析出错\n${it.message}", it)
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
