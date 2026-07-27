package io.legado.app.help.config

/**
 * LocalConfig 的 KMP 共享逻辑 (与 app 端 [io.legado.app.help.config.LocalConfig] 配套)。
 *
 * # 背景
 * app 端 [io.legado.app.help.config.LocalConfig] 是 `SharedPreferences` 委托 object,
 * 依赖 `appCtx.getSharedPreferences("local", MODE_PRIVATE)` (Android Context),
 * 不能直接下沉 shared/commonMain。本 KMP 版抽掉 Android 专属依赖, 仅保留:
 * - [LocalConfigKeys] 配置 key 常量 (平台无关字符串, 供 app 端 / 桌面端统一引用)
 * - [HelpVersion] 各 help 引导的最新版本号常量 (平台无关数值)
 * - [isLastVersion] 版本比较算法 (平台无关, 通过函数参数注入 get/put 操作)
 * - [isFirstOpen] 首次打开判断算法 (平台无关, 通过函数参数注入 get/put 操作)
 *
 * # 与 app 端 [io.legado.app.help.config.LocalConfig] 的差异
 * - **不下沉 SharedPreferences 委托**: 依赖 Android Context, 保留 app 端原 object
 * - **不下沉字段读写 (password/lastBackup/versionCode 等)**: 这些字段直接委托
 *   SharedPreferences getter/setter, 无独立算法, 保留 app 端原实现
 *   (password 已通过 [PasswordProvider] 抽象供 shared 端 BackupAES 用)
 * - **算法通过函数参数解耦**: [isLastVersion] / [isFirstOpen] 接收 getInt/getBoolean/putInt/putBoolean
 *   函数参数, 宿主传入自身 SharedPreferences 操作, 算法逻辑零变化
 *
 * # 模式参考
 * - app 端 [io.legado.app.help.config.LocalConfig] (业务对照原型)
 * - shared/commonMain [io.legado.app.help.config.PasswordProviders] (LocalConfig 字段抽象先例)
 */

/**
 * LocalConfig 配置 key 常量 (与 app 端 [io.legado.app.help.config.LocalConfig] 内硬编码字符串一致)。
 *
 * 供 app 端 / 桌面端统一引用, 避免散落字符串拼写错误。
 * 桌面端如需实现等价 LocalConfig, 用本常量 + PreferenceProvider 即可。
 */
@Suppress("ConstPropertyName")
object LocalConfigKeys {
    const val password = "password"
    const val lastBackup = "lastBackup"
    const val privacyPolicyOk = "privacyPolicyOk"

    // help 引导版本 key
    const val readHelpVersion = "readHelpVersion"
    const val firstRead = "firstRead"
    const val backupHelpVersion = "backupHelpVersion"
    const val firstBackup = "firstBackup"
    const val readMenuHelpVersion = "readMenuHelpVersion"
    const val firstReadMenu = "firstReadMenu"
    const val bookSourceHelpVersion = "bookSourceHelpVersion"
    const val firstOpenBookSources = "firstOpenBookSources"
    const val webDavBookHelpVersion = "webDavBookHelpVersion"
    const val firstOpenWebDavBook = "firstOpenWebDavBook"
    const val ruleHelpVersion = "ruleHelpVersion"

    // 资源升级版本 key
    const val httpTtsVersion = "httpTtsVersion"
    const val txtTocRuleVersion = "txtTocRuleVersion"
    const val rssSourceVersion = "rssSourceVersion"
    const val needUpDictRule = "needUpDictRule"

    const val appVersionCode = "appVersionCode"
    const val firstOpen = "firstOpen"
    const val deleteBookOriginal = "deleteBookOriginal"
    const val appCrash = "appCrash"
}

/**
 * 各 help 引导 / 资源升级的最新版本号常量。
 *
 * 与 app 端 [io.legado.app.help.config.LocalConfig] 各 `isLastVersion` / `needUpXxx`
 * 属性调用时传入的 `lastVersion` 参数值完全一致。
 */
@Suppress("ConstPropertyName")
object HelpVersion {
    /** 阅读引导最新版本 (对应 `isLastVersion(1, "readHelpVersion", "firstRead")`)。 */
    const val readHelp = 1

    /** 备份引导最新版本 (对应 `isLastVersion(1, "backupHelpVersion", "firstBackup")`)。 */
    const val backupHelp = 1

    /** 阅读菜单引导最新版本 (对应 `isLastVersion(1, "readMenuHelpVersion", "firstReadMenu")`)。 */
    const val readMenuHelp = 1

    /** 书源引导最新版本 (对应 `isLastVersion(1, "bookSourceHelpVersion", "firstOpenBookSources")`)。 */
    const val bookSourcesHelp = 1

    /** WebDav 书籍引导最新版本 (对应 `isLastVersion(1, "webDavBookHelpVersion", "firstOpenWebDavBook")`)。 */
    const val webDavBookHelp = 1

    /** 规则引导最新版本 (对应 `isLastVersion(1, "ruleHelpVersion")`)。 */
    const val ruleHelp = 1

    /** HttpTTS 资源最新版本 (对应 `!isLastVersion(6, "httpTtsVersion")`)。 */
    const val httpTts = 6

    /** TxtTocRule 资源最新版本 (对应 `!isLastVersion(3, "txtTocRuleVersion")`)。 */
    const val txtTocRule = 3

    /** RssSources 资源最新版本 (对应 `!isLastVersion(6, "rssSourceVersion")`)。 */
    const val rssSources = 6

    /** DictRule 资源最新版本 (对应 `!isLastVersion(2, "needUpDictRule")`)。 */
    const val dictRule = 2
}

/**
 * 版本比较算法 (平台无关, 与 app 端 [io.legado.app.help.config.LocalConfig.isLastVersion] 逻辑完全一致)。
 *
 * 算法步骤:
 * 1. 读取当前版本号 [versionKey] (默认 0)
 * 2. 若版本号为 0 且提供了 [firstOpenKey], 则检查 [firstOpenKey] 标志:
 *    - 标志为 false (非首次) → 视为版本 1 (兼容旧版本迁移)
 * 3. 若当前版本 < [lastVersion] → 写入最新版本, 返回 false (需要展示引导)
 * 4. 否则返回 true (已是最新版本)
 *
 * 放在 [LocalConfigShared] object 内, 避免与 app 端 [io.legado.app.help.config.LocalConfig]
 * 的同名私有方法在调用时产生命名歧义 (app 端用 `LocalConfigShared.isLastVersion(...)` 显式调用)。
 *
 * @param lastVersion 最新版本号
 * @param versionKey 版本存储 key
 * @param firstOpenKey 首次打开标志 key (可选, 兼容旧版本)
 * @param getInt 对应 SharedPreferences.getInt(key, default)
 * @param getBoolean 对应 SharedPreferences.getBoolean(key, default)
 * @param putInt 对应 SharedPreferences.edit { putInt(key, value) }
 * @return true = 已是最新版本 (无需展示引导), false = 需要展示引导
 */
object LocalConfigShared {

    fun isLastVersion(
        lastVersion: Int,
        versionKey: String,
        firstOpenKey: String? = null,
        getInt: (key: String, default: Int) -> Int,
        getBoolean: (key: String, default: Boolean) -> Boolean,
        putInt: (key: String, value: Int) -> Unit
    ): Boolean {
        var version = getInt(versionKey, 0)
        if (version == 0 && firstOpenKey != null) {
            if (!getBoolean(firstOpenKey, true)) {
                version = 1
            }
        }
        if (version < lastVersion) {
            putInt(versionKey, lastVersion)
            return false
        }
        return true
    }

    /**
     * 首次打开判断算法 (平台无关, 与 app 端 [io.legado.app.help.config.LocalConfig.isFirstOpenApp] 逻辑完全一致)。
     *
     * 算法步骤:
     * 1. 读取 [firstOpenKey] 标志 (默认 true)
     * 2. 若为 true → 写入 false (标记已打开过), 返回 true (首次打开)
     * 3. 否则返回 false (非首次打开)
     *
     * @param firstOpenKey 首次打开标志 key (默认 "firstOpen")
     * @param getBoolean 对应 SharedPreferences.getBoolean(key, default)
     * @param putBoolean 对应 SharedPreferences.edit { putBoolean(key, value) }
     * @return true = 首次打开, false = 非首次打开
     */
    fun isFirstOpen(
        firstOpenKey: String = LocalConfigKeys.firstOpen,
        getBoolean: (key: String, default: Boolean) -> Boolean,
        putBoolean: (key: String, value: Boolean) -> Unit
    ): Boolean {
        val value = getBoolean(firstOpenKey, true)
        if (value) {
            putBoolean(firstOpenKey, false)
        }
        return value
    }
}
