package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.constant.ThreadSafeDateFormat
import io.legado.app.data.AppDbProviders
import io.legado.app.data.dao.sortedByLocalizedOrder
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.DirectLinkUploadStoreProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.ruleFileName
import io.legado.app.help.storage.BackupShared.backupLocked
import io.legado.app.help.storage.BackupShared.backupPath
import io.legado.app.utils.GSON
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.toJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 备份流程 (KMP 共享版, 全平台唯一实现)。
 *
 * # 职责
 * 条目收集 / JSON 序列化 / zip 打包 / 本地复制 / WebDav 上传全部在这里,
 * app 端 [io.legado.app.help.storage.Backup] 只剩 autoBack 调度与 Android 钩子
 * (SAF content:// 复制、LocalConfig.lastBackup、背景图上传), 经
 * [BackupRestoreHooks] 注入, 保证各端走同一条核心路径。
 *
 * # 与原 app 端实现的对应
 * - 路径: [AppFilesDirs] 替代 appCtx.filesDir / externalFiles (同值)
 * - 配置 dump: [PreferenceProviders.getAll] 替代 defaultSharedPreferences.all (Android 同源)
 * - 文件 IO / zip: [BackupFileOps] 替代 FileUtils / ZipUtils (jvm actual 即原实现)
 * - 备份文件名/内容/顺序与原版逐项一致, 老备份文件可直接恢复
 */
object BackupShared {

    private const val TAG = "Backup"

    /** 主题配置文件名 (与 app 端 `ThemeConfig.configFileName` 一致)。 */
    const val THEME_CONFIG_FILE_NAME = "themeConfig.json"

    /** 主题配置落盘路径 (与 app 端 `ThemeConfig.configFilePath` 同值)。 */
    val themeConfigFilePath: String
        get() = AppFilesDirs.get().filesDir + BackupFileOps.separator + THEME_CONFIG_FILE_NAME

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

    /** 备份时需要导出的所有文件名 (与原版 backupFileNames 逐项一致, 19 个)。 */
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
        ruleFileName,
        ReadBookConfigShared.configFileName,
        ReadBookConfigShared.shareConfigFileName,
        THEME_CONFIG_FILE_NAME,
        "config.json"
    )

    /**
     * 备份执行入口 (互斥锁保护)。
     *
     * @param destinationPath 本地备份目录。为空时回退到平台默认落地目录
     *                        ([DataStorage.defaultBackupDir]), 再回退应用 externalFilesDir/filesDir;
     *                        Android 的 content:// 目录由 [BackupRestoreHook.copyBackupTo] 处理。
     * @param uploadToWebDav 是否上传到 WebDav
     * @return 已保留在本地的备份 zip 绝对路径
     */
    suspend fun backupLocked(
        destinationPath: String? = null,
        uploadToWebDav: Boolean = true,
    ): String {
        return mutex.withLock {
            backup(destinationPath, uploadToWebDav)
        }
    }

    /**
     * 实际备份逻辑 (不加锁, 由 [backupLocked] 包装)。
     *
     * 步骤与原版逐项对应:
     * 1. 清空 backupPath 残留
     * 2. 逐个 DAO 导出为 JSON 写入 backupPath
     * 3. servers.json 加密 / 阅读配置 / 主题配置 / 直链上传规则
     * 4. dump 全量配置 → config.json
     * 5. zip 打包 → 复制到本地目录 → 上传 WebDav
     * 6. 清理临时文件, 再走宿主收尾钩子 (背景图上传)
     */
    private suspend fun backup(destinationPath: String?, uploadToWebDav: Boolean): String {
        val hooks = BackupRestoreHooks.get()
        AppLog.putDebug("开始备份 path:$destinationPath", tag = TAG)
        hooks.onBackupStart()
        val aes = BackupAES()
        BackupFileOps.delete(backupPath)
        BackupFileOps.createFolderIfNotExist(backupPath)
        val appDb = AppDbProviders.get()

        // 1. DAO 数据导出 (与原版顺序一致)
        writeListToJson(appDb.bookDao.all(), "bookshelf.json")
        writeListToJson(appDb.bookmarkDao.all().sortedByLocalizedOrder(), "bookmark.json")
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

        // 2. servers.json 加密 (与原版一致)
        GSON.toJson(appDb.serverDao.all()).let { json ->
            val encrypted = aes.runCatching { encryptBase64(json) }.getOrDefault(json)
            BackupFileOps.writeText(backupPath + BackupFileOps.separator + "servers.json", encrypted)
        }

        currentCoroutineContext().ensureActive()

        // 3. 阅读界面配置 / 主题配置 / 直链上传规则 (与原版 ReadBookConfig + ThemeConfig + DirectLinkUpload 段一致)
        // runCatching 只兜 provider 取值 (某平台未注册时跳过该项); 写盘失败不吞,
        // 与原版一致直接中止整个备份, 避免"备份成功但缺文件"
        val readBookConfig = runCatching { ReadBookConfigProviders.get() }
            .onFailure { AppLog.put("备份 readConfig 出错\n${it.message}", it) }
            .getOrNull()
        if (readBookConfig != null) {
            BackupFileOps.writeText(
                backupPath + BackupFileOps.separator + ReadBookConfigShared.configFileName,
                GSON.toJson(readBookConfig.configList)
            )
            BackupFileOps.writeText(
                backupPath + BackupFileOps.separator + ReadBookConfigShared.shareConfigFileName,
                GSON.toJson(readBookConfig.shareConfig)
            )
        }
        val themeConfig = runCatching { ThemeConfigProviders.get() }
            .onFailure { AppLog.put("备份 themeConfig 出错\n${it.message}", it) }
            .getOrNull()
        if (themeConfig != null) {
            BackupFileOps.writeText(
                backupPath + BackupFileOps.separator + THEME_CONFIG_FILE_NAME,
                GSON.toJson(themeConfig.getConfigList())
            )
        }
        // get() 未注册即返回 null (等价原版 getConfig() 为 null 时跳过), 无需 runCatching
        DirectLinkUploadStoreProviders.get()?.getConfig()?.let { rule ->
            BackupFileOps.writeText(
                backupPath + BackupFileOps.separator + ruleFileName,
                GSON.toJson(rule)
            )
        }

        currentCoroutineContext().ensureActive()

        // 4. config.json dump 全量配置 (过滤忽略项, webDavPassword 加密, 与原版一致)
        val configMap = mutableMapOf<String, Any>()
        PreferenceProviders.get().getAll().forEach { (key, value) ->
            if (BackupConfigShared.keyIsNotIgnore(key)) {
                when (key) {
                    PreferKey.webDavPassword -> {
                        configMap[key] = aes.runCatching {
                            encryptBase64(value.toString())
                        }.getOrDefault(value.toString())
                    }

                    // Set<String> (SharedPreferences 合法值类型) 转 List 再序列化,
                    // 否则 toJsonElement 走 toString() 分支写成字符串, 与原版 Gson 的数组不兼容
                    else -> value?.let { configMap[key] = if (it is Set<*>) it.toList() else it }
                }
            }
        }
        BackupFileOps.writeText(
            backupPath + BackupFileOps.separator + "config.json",
            GSON.toJson(configMap)
        )

        currentCoroutineContext().ensureActive()

        // 5. zip 打包 (不存在的文件先过滤, jvm 端 ZipUtils 本就跳过, 打包结果一致)
        val zipFileName = nowZipFileName()
        val paths = backupFileNames.mapNotNull { name ->
            val p = backupPath + BackupFileOps.separator + name
            if (BackupFileOps.exists(p)) p else null
        }
        BackupFileOps.delete(zipFilePath)
        BackupFileOps.delete(zipFilePath.replace("tmp_", ""))
        // WebDav 始终使用带日期的文件名; onlyLatestBackup 仅控制本地副本名称
        val localFileName = if (
            PreferenceProviders.get().getBoolean(PreferKey.onlyLatestBackup, true)
        ) {
            "backup.zip"
        } else {
            zipFileName
        }
        val localDirectory = destinationPath?.takeIf { it.isNotBlank() }
            ?: usableDefaultBackupDir()
            ?: AppFilesDirs.get().externalFilesDir
            ?: AppFilesDirs.get().filesDir
        val localZipPath = localDirectory.trimEnd('/', '\\') +
            BackupFileOps.separator + localFileName

        if (BackupFileOps.zipFiles(paths, zipFilePath)) {
            if (!hooks.copyBackupTo(zipFilePath, localDirectory, localFileName)) {
                BackupFileOps.copyFile(zipFilePath, localZipPath)
            }
            if (uploadToWebDav) {
                try {
                    AppWebDavShared.backUpWebDav(zipFileName)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    AppLog.put("上传备份至webdav失败\n$e", e)
                }
            }
        } else {
            AppLog.put("备份 zip 打包失败")
        }
        BackupFileOps.delete(backupPath)
        BackupFileOps.delete(zipFilePath)
        currentCoroutineContext().ensureActive()
        // 6. 宿主收尾 (app 端: 上传阅读背景图到 WebDav)
        hooks.onBackupFinished(uploadToWebDav)
        return localZipPath
    }

    /**
     * 平台默认落地目录 ([DataStorage.defaultBackupDir], 桌面=文档目录下的 legado/backup)。
     * 平台要求用户先选目录 (Android SAF) 或目录建不出来时返回 null, 交回应用目录兜底。
     */
    private fun usableDefaultBackupDir(): String? {
        val dir = DataStorageProviders.getOrNull()?.defaultBackupDir ?: return null
        return runCatching {
            BackupFileOps.createFolderIfNotExist(dir)
            dir.takeIf { BackupFileOps.exists(it) }
        }.getOrNull()
    }

    /** 写入 List<Any> 为 JSON 到 [backupPath]/[fileName]。空列表跳过 (与原版一致)。 */
    private suspend fun writeListToJson(list: List<Any>, fileName: String) {
        currentCoroutineContext().ensureActive()
        if (list.isEmpty()) {
            AppLog.putDebug("阅读备份 $fileName 列表为空", tag = TAG)
            return
        }
        AppLog.putDebug("阅读备份 $fileName 列表大小 ${list.size}", tag = TAG)
        BackupFileOps.writeText(
            backupPath + BackupFileOps.separator + fileName,
            GSON.toJson(list)
        )
    }

    /**
     * 生成当前备份 zip 文件名 (与原版 getNowZipFileName 一致)。
     *
     * 始终为带日期的文件名: `backup{yyyy-MM-dd}.zip` / `backup{yyyy-MM-dd}-{deviceName}.zip`;
     * onlyLatestBackup 只影响本地副本命名, 不影响 WebDav 上传名 (云端保留历史备份)。
     */
    fun nowZipFileName(): String {
        val backupDate = datePattern.format(systemCurrentTimeMillis())
        val deviceName = AppConfigProviders.get().webDavDeviceName
        return if (deviceName.isNotBlank()) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }.normalizeFileName()
    }

    /** 清理备份工作目录与 zip 临时文件 (与原版 clearCache 同语义)。 */
    fun clearCache() {
        BackupFileOps.delete(backupPath)
        BackupFileOps.delete(zipFilePath)
    }
}
