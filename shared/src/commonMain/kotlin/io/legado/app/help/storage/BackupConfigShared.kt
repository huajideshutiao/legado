package io.legado.app.help.storage

import io.legado.app.constant.PreferKey
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson

/**
 * 备份配置 (KMP 共享版, 替代 app 端 [io.legado.app.help.storage.BackupConfig])。
 *
 * # 背景
 * app 端 [io.legado.app.help.storage.BackupConfig] 依赖 `appCtx.filesDir` (路径) +
 * `appCtx.getString(R.string.xxx)` (忽略项标题), 不能直接下沉 commonMain。本 KMP 版
 * 把这两个 Android 依赖分别替换为:
 * - 路径: [AppFilesDirs.get].filesDir (provider 注入, 安卓=appCtx.filesDir.path)
 * - 字符串: [appString]([AppStringKey]) (provider 注入, 安卓=R.string+appCtx.getString)
 *
 * 其余纯数据 (常量 / Map / 列表) 与 app 端完全一致, 实现逻辑零变化。
 *
 * # 与 app 端 [io.legado.app.help.storage.BackupConfig] 的差异
 * - 仅替换上述两个 Android 依赖, 业务逻辑 (keyIsNotIgnore 分支 / ignoreConfig 持久化 /
 *   readPrefKeys / themePrefKeys / coverPrefKeys 等) 与 app 端逐行对齐
 * - 桌面端默认 ignoreConfig 为空 HashMap (无 restoreIgnore.json 时), keyIsNotIgnore 行为
 *   等价于「仅过滤 ignorePrefKeys」, 与原 [BackupShared] 内的简化版一致; 若后续桌面端
 *   提供 ignoreConfig UI 编辑, 完整逻辑可立即生效
 *
 * # 命名后缀 Shared
 * 与 app 端 [io.legado.app.help.storage.BackupConfig] 同包同名会与 app 模块冲突
 * (shared androidMain target 被 app 模块依赖), 故加 `Shared` 后缀避免歧义,
 * 同时表明这是 KMP 共享版本。app 端 [io.legado.app.help.storage.BackupConfig] 已改为
 * `typealias BackupConfig = BackupConfigShared` 薄壳, 调用点零改动。
 *
 * # 模式参考
 * - app 端 [io.legado.app.help.storage.BackupConfig] (业务对照原型)
 * - shared/commonMain `io.legado.app.help.i18n.AppStringKey` (字符串 provider 注入)
 * - shared/commonMain `io.legado.app.help.file.AppFilesDirs` (目录 provider 注入)
 */
@Suppress("ConstPropertyName")
object BackupConfigShared {

    private val ignoreConfigPath: String =
        AppFilesDirs.get().filesDir + BackupFileOps.separator + "restoreIgnore.json"

    val ignoreConfig: HashMap<String, Boolean> by lazy {
        BackupFileOps.createFileIfNotExist(ignoreConfigPath)
        val json = BackupFileOps.readText(ignoreConfigPath)
        GSON.fromJsonObject<HashMap<String, Boolean>>(json).getOrNull() ?: hashMapOf()
    }

    private const val readConfigKey = "readConfig"
    private const val themeConfigKey = "themeConfig"
    private const val coverConfigKey = "coverConfig"
    private const val localBookKey = "localBook"

    //配置忽略key
    val ignoreKeys = arrayOf(
        readConfigKey,
        PreferKey.themeMode,
        themeConfigKey,
        coverConfigKey,
        PreferKey.bookshelfLayout,
        PreferKey.bookshelfFixedWidthMode,
        PreferKey.bookshelfGridWidth,
        PreferKey.threadCount,
        localBookKey
    )

    //配置忽略标题
    val ignoreTitle = arrayOf(
        appString(AppStringKey.read_config),
        appString(AppStringKey.theme_mode),
        appString(AppStringKey.theme_config),
        appString(AppStringKey.cover_config),
        appString(AppStringKey.bookshelf_layout),
        appString(AppStringKey.thread_count),
        appString(AppStringKey.local_book)
    )

    //自动忽略keys
    private val ignorePrefKeys = arrayOf(
        PreferKey.defaultCover,
        PreferKey.defaultCoverDark,
        PreferKey.backupPath,
        PreferKey.defaultBookTreeUri,
        PreferKey.webDavDeviceName,
        PreferKey.launcherIcon,
        PreferKey.bitmapCacheSize,
        PreferKey.webServiceWakeLock,
        PreferKey.readAloudWakeLock,
        PreferKey.audioPlayWakeLock
    )

    //阅读配置
    private val readPrefKeys = arrayOf(
        PreferKey.readStyleSelect,
        PreferKey.comicStyleSelect,
        PreferKey.shareLayout,
        PreferKey.hideStatusBar,
        PreferKey.hideNavigationBar,
        PreferKey.autoReadSpeed,
        PreferKey.clickActionTL,
        PreferKey.clickActionTC,
        PreferKey.clickActionTR,
        PreferKey.clickActionML,
        PreferKey.clickActionMC,
        PreferKey.clickActionMR,
        PreferKey.clickActionBL,
        PreferKey.clickActionBC,
        PreferKey.clickActionBR
    )

    private val themePrefKeys = arrayOf(
        PreferKey.cPrimary,
        PreferKey.cAccent,
        PreferKey.cBackground,
        PreferKey.cBBackground,
        PreferKey.bgImage,
        PreferKey.bgImageBlurring,
        PreferKey.cNPrimary,
        PreferKey.cNAccent,
        PreferKey.cNBackground,
        PreferKey.cNBBackground,
        PreferKey.bgImageN,
        PreferKey.bgImageNBlurring
    )

    private val coverPrefKeys = arrayOf(
        PreferKey.useDefaultCover,
        PreferKey.loadCoverOnlyWifi,
        PreferKey.coverShowName,
        PreferKey.coverShowAuthor,
        PreferKey.coverShowNameN,
        PreferKey.coverShowAuthorN
    )

    fun keyIsNotIgnore(key: String): Boolean {
        return when {
            ignorePrefKeys.contains(key) -> false
            ignoreReadConfig && readPrefKeys.contains(key) -> false
            ignoreThemeConfig && themePrefKeys.contains(key) -> false
            ignoreCoverConfig && coverPrefKeys.contains(key) -> false
            PreferKey.themeMode == key && ignoreThemeMode -> false
            (key == PreferKey.bookshelfLayout || key == PreferKey.bookshelfFixedWidthMode || key == PreferKey.bookshelfGridWidth) && ignoreBookshelfLayout -> false
            PreferKey.threadCount == key && ignoreThreadCount -> false
            else -> true
        }
    }

    val ignoreReadConfig: Boolean
        get() = ignoreConfig[readConfigKey] == true
    private val ignoreThemeMode: Boolean
        get() = ignoreConfig[PreferKey.themeMode] == true
    private val ignoreThemeConfig: Boolean
        get() = ignoreConfig[themeConfigKey] == true
    private val ignoreCoverConfig: Boolean
        get() = ignoreConfig[coverConfigKey] == true
    private val ignoreBookshelfLayout: Boolean
        get() = ignoreConfig[PreferKey.bookshelfLayout] == true
    private val ignoreThreadCount: Boolean
        get() = ignoreConfig[PreferKey.threadCount] == true
    val ignoreLocalBook: Boolean
        get() = ignoreConfig[localBookKey] == true

    fun saveIgnoreConfig() {
        val json = GSON.toJson(ignoreConfig)
        BackupFileOps.writeText(ignoreConfigPath, json)
    }

}
