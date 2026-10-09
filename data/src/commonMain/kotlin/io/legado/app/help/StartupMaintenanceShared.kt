package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookHelpShared
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.HelpVersion
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.storage.BackupRestoreHooks
import io.legado.app.help.storage.BackupShared
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * 启动期维护编排 (commonMain 下沉版)。
 *
 * # 背景
 *
 * 四端启动时都要跑同一批维护动作, 但此前只有 Android (`App.onCreate` + `MainActivity`) 与
 * 桌面 (`DesktopCore.startupBackgroundTasks`) 各写一份等价实现 (两份重复代码), iOS/鸿蒙
 * 完全没有入口:
 * - 默认数据补齐 (对照 app 端 `DefaultData.upVersion()` + 建库回调预置数据)
 * - 启动期缓存清理 (对照 app 端 `App.onCreate` 的清理块)
 * - 自动检查云端新备份并提示恢复 (对照 app 端 `MainActivity.backupSync`)
 * - 启动确保 WebDav 认证 + 拉全部阅读进度 (对照 app 端 `App.onCreate` 的进度同步块)
 *
 * 书源排序号调整 (`SourceHelp.adjustSortNumber`) 与"后台自动更新书架书籍"不在本文件:
 * 前者是已下沉的共享件由各端自行调用, 后者既有共享触发链已覆盖四端 (进书架页 → 分组流发射
 * → scheduleAutoUpdate), 无需启动期再排一次。
 *
 * # 依赖
 *
 * 只用已下沉的 provider / 共享件 (AppDb / Preference / AppConfig / BookStorage /
 * RuleBigData / BookHelpShared / BackupShared / ReadBookConfig / ThemeConfig / AppWebDavShared),
 * 不引入任何平台类型; 唯一"平台值"是当前版本号, 经 [AppVersionProviders] 注入。
 *
 * # 调用时机
 *
 * 宿主启动早期起一个后台协程调用 (各端写法照抄自己现有的启动期异步块):
 * 全部依赖 provider 均已注册, 且**必须早于各端的升级更新日志弹窗** —— 那个弹窗 (`upVersion`)
 * 自己会写回 `LocalConfigKeys.appVersionCode`, 若先弹窗, 本文件的"版本是否推进"判断就被提前关死。
 */
object StartupMaintenanceShared {

    // =================== 1. 默认数据补齐 ===================

    /**
     * 首启 / 升级时的默认数据补齐 (对照 app 端 `DefaultData.upVersion()` +
     * `dbCallback.onCreate` 的预置分组与键盘助手)。
     *
     * 幂等, 可重复调用。
     *
     * 三道门 (语义与下沉前逐字一致, 详见各私有方法):
     * 1. 预置书架分组: 仅"版本号推进"时补, 且按主键缺啥补啥 (不覆盖用户改动)
     * 2. 默认键盘助手: 同上, 按 (type,key) 逐项补齐
     * 3. httpTTS / txtTocRule / dictRule: 按各资源版本 key 独立门控, **导入成功才推进**版本 key
     *
     * 第 1/2 道判断必须在第 3 道之前: 第 3 道末尾才写回 `appVersionCode`, 反过来同一版本内就再也补不上。
     *
     * **第 3 道不拿 `appVersionCode` 当门** —— 各端的"更新日志"弹窗 (`upVersion`) 也会写回同一个
     * key, 它一旦先跑就把门关死, 资源导入永远不执行。按各资源自己的 key 门控既避开了这个竞态,
     * 又与"导入成功才推进、失败下次重试"的语义等价 (全部成功时各 key 已到位, 下次自然全部早退)。
     *
     * # 存储注入 (getInt/putInt/getLong/putLong)
     *
     * 版本门涉及的 key 在各端落脚不同: app 端历史上它们全在独立的 `local` 配置里
     * (`LocalConfig.versionCode` / `LocalConfig.needUpXxx`), 桌面/iOS/鸿蒙 则在默认配置。
     * 不注入而直接用默认配置, 会让 app 端老用户把已记录的版本当成 0 而重跑一次默认导入 ——
     * 字典规则是按名称 insert-or-replace, 会覆盖用户对同名默认规则的修改。
     *
     * 故按 [io.legado.app.help.config.LocalConfigShared.isLastVersion] 的既有做法, 把读写当参数注入:
     * 各端传入自己那份存储 (`LocalConfigProviders.get()` / 默认配置), 默认值就是默认配置 (桌面/iOS/鸿蒙
     * 无需传参)。:data 看不到 :core 的 `LocalConfigStore`, 这也是这里用函数参数而不是 provider 的原因。
     */
    suspend fun initDefaultData(
        getInt: (key: String, defValue: Int) -> Int = { k, d -> PreferenceProviders.get().getInt(k, d) },
        putInt: (key: String, value: Int) -> Unit = { k, v -> PreferenceProviders.get().putInt(k, v) },
        getLong: (key: String, defValue: Long) -> Long = { k, d -> PreferenceProviders.get().getLong(k, d) },
        putLong: (key: String, value: Long) -> Unit = { k, v -> PreferenceProviders.get().putLong(k, v) },
    ) {
        val currentVersionCode = AppVersionProviders.get().versionCode
        // 版本号取不到 (如鸿蒙 ArkTS 注入与首帧组合的竞态) 视为"版本未推进": 不补预置项、
        // 不写回版本号、下次启动再判。若当成 0 号版本处理, 会把"当前版本 0"永久写进配置。
        if (currentVersionCode <= 0L) return

        if (getLong(LocalConfigKeys.appVersionCode, 0L) < currentVersionCode) {
            ensurePresetBookGroups()
            ensureKeyboardAssists()
        }
        // 无条件进: 由各资源版本 key 自行门控 (见方法 KDoc, 不能拿 appVersionCode 当门)
        upDefaultDataVersion(currentVersionCode, getInt, putInt, putLong)
    }

    /**
     * 预置分组补齐: 全部 / 本地 / 未分组 / 更新失败
     * (id + 名称 + order 与 app 端建库回调一致)。
     *
     * 主路径是建库回调 (`AppDatabaseDefaultData.insert`, 建库与破坏性重建时都会跑), 这里只兜两件事:
     * 建库回调挂上之前就存在的老库, 以及以后新增的预置项 —— 所以入门控, 不每次启动无条件跑 SQL。
     */
    private suspend fun ensurePresetBookGroups() {
        runCatching {
            val dao = AppDbProviders.get().bookGroupDao
            val presets = listOf(
                BookGroup(BookGroup.IdAll, "全部", order = -10, enableRefresh = true, show = true),
                BookGroup(BookGroup.IdLocal, "本地", order = -9, enableRefresh = false, show = true),
                BookGroup(BookGroup.IdUngrouped, "未分组", order = -7, enableRefresh = true, show = true),
                BookGroup(BookGroup.IdError, "更新失败", order = -1, enableRefresh = true, show = true),
            )
            // 对照 app 端 "where not exists" 语义: 已存在的分组保留用户改动, 不覆盖
            val missing = presets.filter { dao.getByID(it.groupId) == null }
            if (missing.isNotEmpty()) dao.insert(*missing.toTypedArray())
        }.onFailure { AppLog.put("补齐预置书架分组失败", it) }
    }

    /**
     * 键盘助手补齐: 按主键 (type,key) 逐项补缺 (对照 app 端建库回调的 insert or replace)。
     *
     * 不能写成"整表为空才写": 库非空时以后新增的预置助手就永远补不进去。
     */
    private suspend fun ensureKeyboardAssists() {
        runCatching {
            val dao = AppDbProviders.get().keyboardAssistsDao
            val existing = dao.all().map { it.type to it.key }.toSet()
            val missing = DefaultDataShared.keyboardAssists.filter { (it.type to it.key) !in existing }
            if (missing.isNotEmpty()) dao.insert(*missing.toTypedArray())
        }.onFailure { AppLog.put("补齐默认键盘助手失败", it) }
    }

    /**
     * 版本号推进时按各资源版本 key 补齐默认数据 (httpTTS / txt 目录规则 / 字典规则)。
     *
     * **资源版本 key 只在导入成功后才写回**。原 app 端实现走 `LocalConfig.needUpXxx`, 其底层
     * `LocalConfigShared.isLastVersion` 在返回"需要导入"之前就把新版本号写进了 key; 一旦那次导入
     * 抛异常, 版本 key 与 appVersionCode 两扇门都已关闭, 默认 httpTTS / 目录规则 / 字典规则
     * 就永远补不上 (用户表现为新装后默认朗读引擎与目录规则为空, 且重装前不会自愈)。
     * 任一项失败就不推进 appVersionCode, 下次启动整体重试。
     *
     * # 为什么不拿 `appVersionCode` 当入口门
     * 各端的"更新日志"弹窗 (`upVersion`) 也会写回同一个 `appVersionCode`。若这里先判断
     * `appVersionCode < currentVersionCode` 才做事, 弹窗一旦先跑, 这里的导入就永远不执行 ——
     * 与要修的缺陷同型 (只是把"资源 key 先写回"换成了"版本号先写回")。
     * 故入口只看各资源自己的 key; `appVersionCode` 仅作为本方法成功后的记账。
     */
    private suspend fun upDefaultDataVersion(
        currentVersionCode: Long,
        getInt: (key: String, defValue: Int) -> Int,
        putInt: (key: String, value: Int) -> Unit,
        putLong: (key: String, value: Long) -> Unit,
    ) {
        var allImported = true
        suspend fun importOnce(
            versionKey: String,
            lastVersion: Int,
            what: String,
            import: suspend () -> Unit,
        ) {
            if (getInt(versionKey, 0) >= lastVersion) return
            runCatching { import() }
                .onSuccess { putInt(versionKey, lastVersion) }
                .onFailure {
                    allImported = false
                    AppLog.put("$what 失败 (不推进版本号, 下次启动重试)", it)
                }
        }

        importOnce(LocalConfigKeys.httpTtsVersion, HelpVersion.httpTts, "导入默认 httpTTS") {
            DefaultDataShared.importDefaultHttpTTS()
        }
        importOnce(LocalConfigKeys.txtTocRuleVersion, HelpVersion.txtTocRule, "导入默认 txt 目录规则") {
            DefaultDataShared.importDefaultTocRules()
        }
        importOnce(LocalConfigKeys.needUpDictRule, HelpVersion.dictRule, "导入默认字典规则") {
            DefaultDataShared.importDefaultDictRules()
        }
        // app 端由"更新日志"弹窗展示后写回, 本共享件就地写回 (各端弹窗只负责展示)
        if (allImported) {
            putLong(LocalConfigKeys.appVersionCode, currentVersionCode)
        }
    }

    // =================== 2. 启动期缓存清理 ===================

    /**
     * 启动期缓存清理 (对照 app 端 `App.onCreate` 的清理块)。
     *
     * 节流: 距上次清理不足 1 天直接返回。节流 key 用 [LocalConfigKeys.lastCacheCleanup] 而**不是**
     * `lastBackup` —— 后者只在用户真的备份/恢复时才写, 从不备份的用户恒为 0, 拿它当门控会让
     * 清理族每次启动都全跑 (原 app 端实现即为此写法, 属缺陷, 新代码不再沿用)。
     *
     * 五连调全部跑完才记账: 中途抛错不写时间戳, 下次启动重试, 否则一次失败会被当成"今天已经清过"。
     *
     * @param cacheExtraMayHaveWork 本平台 `clearCacheExtra` 是否可能真有活 (见 [hasInvalidCacheWork]);
     *   app 端传 true, 其余端可传 false。
     */
    suspend fun clearInvalidCacheIfDue(cacheExtraMayHaveWork: Boolean = true) {
        val prefs = PreferenceProviders.get()
        val lastCleanup = prefs.getLong(LocalConfigKeys.lastCacheCleanup, 0L)
        // 1 天 = 86_400_000ms (与 BackupShared 自动备份间隔同一常量口径, commonMain 无 TimeUnit)
        if (lastCleanup + 86_400_000L >= systemCurrentTimeMillis()) return

        runCatching {
            AppDbProviders.get().cacheDao.clearDeadline(systemCurrentTimeMillis())
            // 书架无书且缓存目录无残留时 clearInvalidCache 必然空转, 见 hasInvalidCacheWork
            if (hasInvalidCacheWork(cacheExtraMayHaveWork)) {
                BookHelpShared.clearInvalidCache()
            }
            BackupShared.clearCache()
            ReadBookConfigProviders.get().clearBgAndCache()
            ThemeConfigProviders.get().clearBg()
        }.onSuccess {
            prefs.putLong(LocalConfigKeys.lastCacheCleanup, systemCurrentTimeMillis())
        }.onFailure {
            AppLog.put("启动期缓存清理失败\n${it.message}", it)
        }
    }

    /**
     * [BookHelpShared.clearInvalidCache] 是否还有活可干 (调用点早退判据)。
     *
     * 只在**能确定四步全为空转**时返回 false。判据必须比被它省掉的活便宜: 1 条 COUNT(*)
     * + 2 组单层目录列举, 换掉"全表读 name/bookUrl + book_cache 子目录逐个扫描 +
     * ruleData 子目录逐个扫描"。
     *
     * 不漏清的依据: clearInvalidCache 四步 —— ①② 按书架删 book_cache 失效目录与漫画超量淘汰
     * (根目录空 → 无可删对象), ③ 按书架删大变量目录 (provider 未注册时本来就是 no-op),
     * ④ clearCacheExtra (平台专属临时文件)。④ **各端不一定为空实现**: app 端会删压缩临时目录与
     * 草稿 json, 那些文件与书架/缓存目录无关 —— 所以只凭"书架 0 行 + 目录空"就早退会漏掉④。
     *
     * @param cacheExtraMayHaveWork 平台报告的"④ 可能还有活"。默认 true (不早退), 只允许
     *   确认④为空实现的端 (桌面/headless 的 accessor 是空方法) 传 false 以启用早退。
     */
    private suspend fun hasInvalidCacheWork(cacheExtraMayHaveWork: Boolean): Boolean {
        if (cacheExtraMayHaveWork) return true
        if (AppDbProviders.get().bookDao.allBookCount() > 0) return true
        if (dirHasChildren(BookStorageProviders.get().rootPath)) return true
        return RuleBigDataProviders.impl?.listBookDataDirs().orEmpty().any { dirHasChildren(it) }
    }

    /**
     * 单层目录下是否有子项 (不存在或无子项返回 false)。
     *
     * 只列举一层, 不递归。子目录与普通文件分开列举后取并集: 原实现用 `File.list()` 一次拿全部子项,
     * 而共享文件门面把"子目录"与"文件"拆成了两个方法。
     */
    private fun dirHasChildren(dirPath: String): Boolean =
        FileUtilsCommon.listSubDirs(dirPath).isNotEmpty() || FileUtilsCommon.listFiles(dirPath).isNotEmpty()

    // =================== 3. 自动检查云端新备份 ===================

    /** [checkNewWebDavBackup] 的判定结果。 */
    sealed interface NewBackupCheckResult {
        /** 云端无备份 / 未配置 / 断网 / 读不到 —— 一律无事可做。 */
        data object None : NewBackupCheckResult

        /** 云端备份比本地记录更新, 需要提示用户恢复。 */
        data class NewBackup(val name: String) : NewBackupCheckResult
    }

    /**
     * 自动检查云端新备份 (对照 app 端 `MainActivity.backupSync` 的判定段)。
     *
     * 开关 [PreferKey.autoCheckNewBackup] 默认 **true** (与 app 端 `AppConfig` 同款取值, 故显式传 true)。
     * 判定: 云端最新 `backup*` 的 `lastModify` 比本地 `lastBackup` 记录晚 1 分钟以上才算"有新备份"
     * (1 分钟是为容忍同一份备份在两端时钟/写入顺序上的抖动)。
     *
     * **判定成立时立即写回 `lastBackup`**, 与 app 端原实现一致: 该时间戳的语义是"已经提示过用户的最新
     * 云端备份时间", 用户这次拒绝恢复也不该每次启动重复弹同一个提示。
     *
     * 读写该时间戳一律走 [io.legado.app.help.storage.BackupRestoreHooks] (而不是直接读
     * [PreferenceProviders]) —— app 端它落在独立的 `local` 配置里, 由 Android 实现经
     * `LocalConfig.lastBackup` 读写; 直接走偏好 provider 会读串到默认配置, 既看不到 app 端已记录的
     * 时间 (每次启动都误判"有新备份"), 也会把用户真实的备份时间戳覆盖掉。
     *
     * 调用方在拿到 [NewBackupCheckResult.NewBackup] 后自行决定提示方式 —— 本函数不含任何 UI。
     */
    suspend fun checkNewWebDavBackup(): NewBackupCheckResult {
        if (!PreferenceProviders.get().getBoolean(PreferKey.autoCheckNewBackup, true)) {
            return NewBackupCheckResult.None
        }
        // lastBackUp() 首行是 authorization ?: return, 未认证时返回 null, 与"云端无备份"同路
        val backupFile = AppWebDavShared.lastBackUp().getOrNull() ?: return NewBackupCheckResult.None
        val hooks = BackupRestoreHooks.get()
        // 1 分钟 (对照 app 端 DateUtils.MINUTE_IN_MILLIS)
        if (backupFile.lastModify - hooks.getLastBackup() <= 60_000L) {
            return NewBackupCheckResult.None
        }
        hooks.setLastBackup(backupFile.lastModify)
        return NewBackupCheckResult.NewBackup(backupFile.displayName)
    }

    // =================== 4. 启动确保 WebDav 认证 + 拉进度 ===================

    /**
     * 启动期同步全部书籍阅读进度 (对照 app 端 `App.onCreate` 的进度同步块)。
     *
     * `syncBookProgress` 关闭时直接返回 (与 app 端 `AppConfig.syncBookProgress` 判断一致)。
     *
     * **必须先显式认证**: `downloadAllBookProgress` 首行是 `authorization ?: return`,
     * app 端之所以能用, 靠的是 `AppWebDav` object 初始化时那次 `upConfig()`; 其余端没有那个
     * 初始化块, 直接调会静默 no-op (表现为"开了同步但进度永远拉不下来")。所以这里显式
     * [AppWebDavShared.upConfig], 认证不成 (未配置账号 / 断网 / 密码错) 就干净地不拉。
     *
     * @param ensureAuthorization 认证入口。默认是共享层的 [AppWebDavShared.upConfig];
     *   app 端传入自己的 `AppWebDav.upConfig` —— 它在共享认证之外还要初始化 Android 专属的
     *   `defaultBookWebDav`(远端书的封面/正文读取靠它, 为空时会报 "Unexpected defaultBookWebDav")。
     *   下沉前那个初始化是由启动块里的 app 端调用顺带触发的, 改传共享入口就会丢掉它。
     */
    suspend fun syncBookProgressOnStartup(
        ensureAuthorization: suspend () -> Unit = { AppWebDavShared.upConfig() },
    ) {
        if (!AppConfigProviders.get().syncBookProgress) return
        runCatching { ensureAuthorization() }
            .onFailure { AppLog.put("启动期 WebDav 认证失败\n${it.message}", it) }
        if (AppWebDavShared.authorization == null) return
        AppWebDavShared.downloadAllBookProgress()
    }
}
