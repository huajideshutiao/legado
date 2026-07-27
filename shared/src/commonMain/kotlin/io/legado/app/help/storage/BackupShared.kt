package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.constant.ThreadSafeDateFormat
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.DirectLinkUploadStoreProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.ruleFileName
import io.legado.app.utils.GSON
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.toJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 备份流程的 KMP 共享版 (与 [AppWebDavShared] / [RestoreShared] 配套)。
 *
 * # 背景
 * app 端 [io.legado.app.help.storage.Backup] 单例依赖大量 Android-specific 类
 * (Context / Uri / FileDoc / defaultSharedPreferences / DirectLinkUpload /
 * ReadBookConfig / ThemeConfig / Coroutine / LocalConfig / externalFiles /
 * ZipUtils / FileUtils 等), 不能直接下沉 shared/commonMain。本 KMP 版抽掉
 * Android 专属依赖, 仅保留核心备份流程 (DAO → JSON → zip → upload WebDav),
 * 桌面端 / iOS 端可直接复用。
 *
 * # 与 app 端 [io.legado.app.help.storage.Backup] 的差异
 * - **不依赖 Context**: 路径用 [AppFilesDirs.get] (provider 注入) 替代 appCtx
 * - **不依赖 SharedPreferences.all**: 配置 dump 走 [PreferenceProviders.get().getAll]
 * - **不依赖 DirectLinkUpload / ReadBookConfig / ThemeConfig**: 这些子项配置文件
 *   的备份保留在 app 端 [io.legado.app.help.storage.Backup], 桌面端如需可后续补
 * - **不实现 copyBackup(Uri/File)**: 依赖 Android FileDoc 的本地复制路径
 *   (path 是 content scheme / File), 桌面端如需可后续补
 * - **不实现 autoBack**: 依赖 app 端 [io.legado.app.help.coroutine.Coroutine] +
 *   [io.legado.app.help.config.LocalConfig.lastBackup], 由 app 端原 [Backup]
 *   继续承担, 本 KMP 版仅暴露 [backupLocked] 供宿主主动调用
 * - **BackupConfig**: 复用同包公共 [BackupConfigShared] (完整版, 含 ignoreConfig
 *   动态分支 + 持久化), 桌面端默认 ignoreConfig 为空时行为等价于「仅过滤 ignorePrefKeys」;
 *   app 端 [io.legado.app.help.storage.BackupConfig] 已 typealias 到同一 [BackupConfigShared]
 *
 * # 命名后缀 Shared
 * 与 app 端 [io.legado.app.help.storage.Backup] 同包同名会与 app 模块冲突
 * (shared androidMain target 被 app 模块依赖), 故加 `Shared` 后缀避免歧义,
 * 同时表明这是 KMP 共享版本。
 *
 * # 模式参考
 * - app 端 [io.legado.app.help.storage.Backup] (业务对照原型)
 * - shared/commonMain `io.legado.app.model.webBook.WebBook` (KMP 业务编排层下沉先例)
 */
object BackupShared {

    /** 备份工作目录名 (相对于 [AppFilesDirs.filesDir])。 */
    private const val BACKUP_DIR_NAME = "backup"

    /** 备份 zip 临时文件名。 */
    private const val ZIP_FILE_NAME = "tmp_backup.zip"

    /** 备份文件名时间戳格式 (yyyy-MM-dd)。 */
    private val datePattern by lazy { ThreadSafeDateFormat("yyyy-MM-dd") }

    /** 互斥锁, 防止并发备份 (与 app 端 [io.legado.app.help.storage.Backup.mutex] 同语义)。 */
    private val mutex = Mutex()

    /**
     * 备份工作目录绝对路径 (filesDir + "backup")。
     *
     * 与 app 端 [io.legado.app.help.storage.Backup.backupPath] 同语义,
     * 目录不存在时由 [BackupFileOps.createFolderIfNotExist] 创建。
     */
    val backupPath: String
        get() {
            val base = AppFilesDirs.get().filesDir
            val path = base + BackupFileOps.separator + BACKUP_DIR_NAME
            BackupFileOps.createFolderIfNotExist(path)
            return path
        }

    /**
     * 备份 zip 临时文件绝对路径。
     *
     * - 优先用 [AppFilesDirs.externalFilesDir] (与 app 端 externalFiles 一致)
     * - 桌面端 externalFilesDir 为 null, 回退到 [AppFilesDirs.filesDir]
     */
    val zipFilePath: String
        get() {
            val base = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
            return base + BackupFileOps.separator + ZIP_FILE_NAME
        }

    /** 备份时需要导出的所有 JSON 文件名 (与 app 端 [io.legado.app.help.storage.Backup.backupFileNames] 完全一致, 19 个)。 */
    private val backupFileNames: Array<String> = arrayOf(
        "bookshelf.json",
        "bookmark.json",
        "bookGroup.json",
        "bookSource.json",
        "replaceRule.json",
        "readRecord.json",
        "searchHistory.json",
        "sourceSub.json",
        "txtTocRule.json",
        "httpTTS.json",
        "keyboardAssists.json",
        "dictRule.json",
        "sourceFilterRule.json",
        "servers.json",
        // 以下 4 个为 app 端独有配置文件 (桌面端按需写入, 可能留空)
        "directLinkUploadRule.json",
        "readConfig.json",
        "shareReadConfig.json",
        "themeConfig.json",
        "config.json"
    )

    /**
     * 备份执行入口 (互斥锁保护)。
     *
     * 与 app 端 [io.legado.app.help.storage.Backup.backupLocked] 同语义:
     * - 通过 [BackupFileOps] 完成 zip + 文件 IO (跨平台抽象)
     * - 通过 [AppDatabaseProviders.get].appDb 拿全部 DAO (Room KMP)
     * - 通过 [PreferenceProviders.get].getAll dump 配置
     * - 完成后调用 [AppWebDavShared.backUpWebDav] 上传到云端
     *
     * @param uploadToWebDav 是否上传到 WebDav (false 仅生成本地 zip, 测试用)
     */
    suspend fun backupLocked(uploadToWebDav: Boolean = true) {
        mutex.withLock {
            backup(uploadToWebDav)
        }
    }

    /**
     * 实际备份逻辑 (不加锁, 由 [backupLocked] 包装)。
     *
     * 步骤:
     * 1. 清空 backupPath + zipFilePath 残留
     * 2. 逐个 DAO 导出为 JSON 写入 backupPath
     * 3. dump PreferenceProviders 全量配置 → config.json
     * 4. zip 所有 JSON 文件为 zipFilePath
     * 5. 上传到 WebDav (若 [uploadToWebDav] = true)
     * 6. 清理临时文件
     */
    private suspend fun backup(uploadToWebDav: Boolean) {
        AppLog.put("BackupShared 开始备份 uploadToWebDav=$uploadToWebDav")
        val aes = BackupAES()
        BackupFileOps.delete(backupPath)
        BackupFileOps.createFolderIfNotExist(backupPath)
        val appDb = AppDatabaseProviders.get().appDb

        // 1. DAO 数据导出 (与 app 端顺序一致)
        writeListToJson(appDb.bookDao.all(), "bookshelf.json")
        writeListToJson(appDb.bookmarkDao.all(), "bookmark.json")
        writeListToJson(appDb.bookGroupDao.all(), "bookGroup.json")
        writeListToJson(appDb.bookSourceDao.all(), "bookSource.json")
        writeListToJson(appDb.replaceRuleDao.all(), "replaceRule.json")
        writeListToJson(appDb.readRecordDao.all(), "readRecord.json")
        writeListToJson(appDb.searchKeywordDao.all(), "searchHistory.json")
        writeListToJson(appDb.ruleSubDao.all(), "sourceSub.json")
        writeListToJson(appDb.txtTocRuleDao.all(), "txtTocRule.json")
        writeListToJson(appDb.httpTTSDao.all(), "httpTTS.json")
        writeListToJson(appDb.keyboardAssistsDao.all(), "keyboardAssists.json")
        writeListToJson(appDb.dictRuleDao.all(), "dictRule.json")
        writeListToJson(appDb.sourceFilterRuleDao.all(), "sourceFilterRule.json")

        currentCoroutineContext().ensureActive()

        // 2. servers.json 加密 (与 app 端一致, 密码 webDavPassword 用 AES 加密)
        GSON.toJson(appDb.serverDao.all()).let { json ->
            val encrypted = aes.runCatching { encryptBase64(json) }.getOrDefault(json)
            BackupFileOps.writeText(backupPath + BackupFileOps.separator + "servers.json", encrypted)
        }

        currentCoroutineContext().ensureActive()

        // 2.1 app 端独有配置文件备份 (与 app 端 Backup.kt:158-173 同语义)
        // readConfig.json / shareReadConfig.json: ReadBookConfigShared.configList / shareConfig
        // themeConfig.json: ThemeConfigProviders.get().getConfigList()
        // directLinkUploadRule.json: DirectLinkUploadStoreProviders.get().getConfig() (Android=ACache, desktop=java.util.prefs)
        runCatching {
            val readBookConfig = ReadBookConfigProviders.get()
            BackupFileOps.writeText(
                backupPath + BackupFileOps.separator + "readConfig.json",
                GSON.toJson(readBookConfig.configList)
            )
            BackupFileOps.writeText(
                backupPath + BackupFileOps.separator + "shareReadConfig.json",
                GSON.toJson(readBookConfig.shareConfig)
            )
        }.onFailure {
            AppLog.put("BackupShared 备份 readConfig 出错\n${it.localizedMessage}", it)
        }
        runCatching {
            BackupFileOps.writeText(
                backupPath + BackupFileOps.separator + "themeConfig.json",
                GSON.toJson(ThemeConfigProviders.get().getConfigList())
            )
        }.onFailure {
            AppLog.put("BackupShared 备份 themeConfig 出错\n${it.localizedMessage}", it)
        }
        // directLinkUploadRule.json (与 app 端 Backup.kt DirectLinkUpload.getConfig() 备份同语义)
        runCatching {
            DirectLinkUploadStoreProviders.get()?.getConfig()?.let { rule ->
                BackupFileOps.writeText(
                    backupPath + BackupFileOps.separator + ruleFileName,
                    GSON.toJson(rule)
                )
            }
        }.onFailure {
            AppLog.put("BackupShared 备份 directLinkUploadRule 出错\n${it.localizedMessage}", it)
        }

        currentCoroutineContext().ensureActive()

        // 3. config.json dump PreferenceProviders 全量 (过滤 ignorePrefKeys)
        val configMap = mutableMapOf<String, Any?>()
        val allPrefs = PreferenceProviders.get().getAll()
        allPrefs.forEach { (key, value) ->
            if (BackupConfigShared.keyIsNotIgnore(key)) {
                when (key) {
                    PreferKey.webDavPassword -> {
                        configMap[key] = aes.runCatching {
                            encryptBase64(value.toString())
                        }.getOrDefault(value.toString())
                    }
                    else -> {
                        if (value != null) configMap[key] = value
                    }
                }
            }
        }
        BackupFileOps.writeText(
            backupPath + BackupFileOps.separator + "config.json",
            GSON.toJson(configMap)
        )

        currentCoroutineContext().ensureActive()

        // 4. zip 所有 JSON 文件 (过滤不存在的文件, 如 directLinkUploadRule.json 桌面端无此数据)
        val paths = backupFileNames.mapNotNull { name ->
            val p = backupPath + BackupFileOps.separator + name
            if (BackupFileOps.exists(p)) p else null
        }
        BackupFileOps.delete(zipFilePath)
        BackupFileOps.delete(zipFilePath.replace("tmp_", ""))
        val backupFileName = getNowZipFileName()
        if (BackupFileOps.zipFiles(paths, zipFilePath)) {
            // 5. 上传到 WebDav
            if (uploadToWebDav) {
                try {
                    AppWebDavShared.backUpWebDav(backupFileName)
                } catch (e: Exception) {
                    AppLog.put("BackupShared 上传至 WebDav 失败\n${e.localizedMessage}", e)
                }
            }
        } else {
            AppLog.put("BackupShared zip 打包失败")
        }

        // 6. 清理临时文件
        BackupFileOps.delete(backupPath)
        BackupFileOps.delete(zipFilePath)
        currentCoroutineContext().ensureActive()
    }

    /** 写入 List<Any> 为 JSON 到 [backupPath]/[fileName]。空列表跳过。 */
    private suspend fun writeListToJson(list: List<Any>, fileName: String) {
        currentCoroutineContext().ensureActive()
        if (list.isEmpty()) return
        val json = GSON.toJson(list)
        BackupFileOps.writeText(backupPath + BackupFileOps.separator + fileName, json)
    }

    /**
     * 生成当前备份 zip 文件名。
     *
     * 与 app 端 [io.legado.app.help.storage.Backup.getNowZipFileName] 同语义:
     * - 默认格式: `backup{yyyy-MM-dd}.zip`
     * - 配置了 webDavDeviceName 时: `backup{yyyy-MM-dd}-{deviceName}.zip`
     * - AppConfig.onlyLatestBackup = true 时返回固定 `backup.zip` (由 [backup] 调用方决定)
     */
    private fun getNowZipFileName(): String {
        val backupDate = datePattern.format(systemCurrentTimeMillis())
        val deviceName = PreferenceProviders.get().getString(PreferKey.webDavDeviceName, "")
        val name = if (deviceName.isNotBlank()) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }
        // AppConfig.onlyLatestBackup 时只保留 backup.zip (与 app 端逻辑一致)
        val onlyLatest = PreferenceProviders.get().getBoolean(PreferKey.onlyLatestBackup, true)
        return if (onlyLatest) "backup.zip" else name.normalizeFileName()
    }

    /** 清理备份工作目录与 zip 临时文件 (与 app 端 [io.legado.app.help.storage.Backup.clearCache] 同语义)。 */
    fun clearCache() {
        BackupFileOps.delete(backupPath)
        BackupFileOps.delete(zipFilePath)
    }
}
