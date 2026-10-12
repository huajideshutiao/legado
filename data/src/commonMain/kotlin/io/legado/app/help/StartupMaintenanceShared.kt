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
import io.legado.app.help.config.LocalConfigShared
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
 * 全部依赖 provider 均已注册, 且**必须早于各端的升级更新日志弹窗** —— 唯一写回
 * `LocalConfigKeys.appVersionCode` 的就是那个弹窗 (`upVersion`), 若先弹窗, 本文件的"版本是否推进"
 * 判断就被提前关死 (与 app 端 `App.onCreate` 的 `DefaultData.upVersion()` 先于 `MainActivity` 同一时序)。
 */
object StartupMaintenanceShared {

    // =================== 1. 默认数据补齐 ===================

    /**
     * 首启 / 升级时的默认数据补齐 (对照 app 端 `DefaultData.upVersion()` +
     * `dbCallback.onCreate` 的预置分组与键盘助手)。
     *
     * 幂等, 可重复调用。
     *
     * 三道动作整包在**同一个版本门**下 (对照原版 `DefaultData.upVersion()` 的
     * `if (LocalConfig.versionCode < AppConst.appInfo.versionCode) { ... }`):
     * 1. 预置书架分组: 按主键缺啥补啥 (不覆盖用户改动)
     * 2. 默认键盘助手: 按 (type,key) 逐项补齐
     * 3. httpTTS / txtTocRule / dictRule: 各自再加一层资源版本门
     *    ([LocalConfigShared.isLastVersion], 与原版 `LocalConfig.needUpXxx` 同一算法)
     *
     * 版本门要求 [AppVersionProviders] 注册进来的当前版本号随版本推进单调不减
     * (iOS/鸿蒙/桌面 由 `versionNameToVersionCode` 的定宽位槽编码保证): 按数值大小判"版本是否推进"。
     *
     * # `appVersionCode` 本文件只读, 不写
     *
     * 写这个键的只有各端的更新日志弹窗 (`upVersion`: `LocalConfig.versionCode = appInfo.versionCode`);
     * 原版 `DefaultData.upVersion()` 同样只读它, 只写各资源版本 key。本文件也如此 —— 一旦本文件也写
     * 这个键, 它就抢在弹窗之前把关卡门的门关死 (弹窗的判据是"已保存版本号 == 当前版本号就 return"),
     * 这一版的首启帮助/更新日志就不会再弹。
     *
     * # 第 3 道失败不重试 (对齐原版)
     *
     * [LocalConfigShared.isLastVersion] 在返回"需要导入"之前就把新版本号写进了 key, 故那次导入抛出的
     * 异常不会让下次启动重试 —— 原版就是这个语义 (每资源每版本只尝试一次): `DefaultData.upVersion`
     * 的 `Coroutine.async { ... }.onError { it.printOnDebug() }` 只记日志, 版本 key 已经推进。
     *
     * # 存储注入 (getInt/putInt/getBoolean/getLong)
     *
     * 版本门涉及的 key 在各端落脚不同: app 端历史上它们全在独立的 `local` 配置里
     * (`LocalConfig.versionCode` 与原版 `LocalConfig.needUpXxx`), 桌面/iOS/鸿蒙 则在默认配置。
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
        getBoolean: (key: String, defValue: Boolean) -> Boolean =
            { k, d -> PreferenceProviders.get().getBoolean(k, d) },
        getLong: (key: String, defValue: Long) -> Long = { k, d -> PreferenceProviders.get().getLong(k, d) },
    ) {
        val currentVersionCode = AppVersionProviders.get().versionCode
        // 版本号取不到 (如鸿蒙 ArkTS 注入与首帧组合的竞态) 视为"版本未推进": 不补预置项、
        // 下次启动再判。若当成 0 号版本处理, 会把"当前版本 0"永久写进配置。
        if (currentVersionCode <= 0L) return

        // 版本门 (对照原版 `LocalConfig.versionCode < AppConst.appInfo.versionCode`):
        // 这一版已经补过就整体跳过。只读不写 —— 写回该键的只有各端的更新日志弹窗。
        if (getLong(LocalConfigKeys.appVersionCode, 0L) >= currentVersionCode) return

        ensurePresetBookGroups()
        ensureKeyboardAssists()
        upDefaultDataVersion(getInt, getBoolean, putInt)
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
     * 门控与写回逐字对齐原版 `LocalConfig.needUpXxx` → [LocalConfigShared.isLastVersion]:
     * **先写回资源版本 key, 再返回"需要导入"** —— 每资源每版本只尝试一次, 那一次抛异常也不重试。
     */
    private suspend fun upDefaultDataVersion(
        getInt: (key: String, defValue: Int) -> Int,
        getBoolean: (key: String, defValue: Boolean) -> Boolean,
        putInt: (key: String, value: Int) -> Unit,
    ) {
        suspend fun importOnce(
            versionKey: String,
            lastVersion: Int,
            what: String,
            import: suspend () -> Unit,
        ) {
            // firstOpenKey 传 null: 与原版 `isLastVersion(6, "httpTtsVersion")` 等资源档调用一致
            // (只有 help 引导那几档才带 firstOpenKey)
            val isLast = LocalConfigShared.isLastVersion(
                lastVersion = lastVersion,
                versionKey = versionKey,
                getInt = getInt,
                getBoolean = getBoolean,
                putInt = putInt,
            )
            if (isLast) return
            runCatching { import() }.onFailure {
                // isLastVersion 内已写回版本 key, 与原版一致: 这一版不再重试
                AppLog.put("$what 失败 (资源版本号已推进, 与原版一致不重试)", it)
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
    }

    // =================== 2. 启动期缓存清理 ===================

    /**
     * 启动期缓存清理 (对照 app 端 `App.onCreate` 的清理块)。
     *
     * 节流: 距上次清理不足 1 天直接返回。
     *
     * 节流 key 用**本仓自造的** [LocalConfigKeys.lastCacheCleanup], **不是**原版那段用的
     * `LocalConfig.lastBackup` (原版 `app/.../App.kt` 里逐字是
     * `if (LocalConfig.lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis())`;
     * 原版全仓无 `lastCacheCleanup` 这个键)。之所以不复用 `lastBackup`:
     * 1. 它不是"上次清理时间"——写入方是"用户真的备份/恢复"与"启动时判定到云端有更新的备份"
     *    (`MainActivity.backupSync`)。从不备份的用户它恒为 0 → 节流恒不成立, 清理族每次启动全跑;
     * 2. 反过来说, 备份过一次之后的一整天里清理会被这条与清理无关的时间戳静默挡住。
     * 属原版明显缺陷, 不复刻 (但键名与本仓自造事实如实写在 [LocalConfigKeys.lastCacheCleanup])。
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
     * 单层目录下是否有子项 (不存在返回 false)。
     *
     * **"目录存在但读不出来"必须按非空处理**: 共享文件门面 [FileUtilsCommon.listSubDirs] /
     * [FileUtilsCommon.listFiles] 把 `File.listFiles()` 返回 null (含"是目录但读目录出错")
     * 一律吞成 `emptyList()`, 拿它们直接判空会把 IO 错误当成"目录为空" → 早退跳过整个清理。
     * 故这里用保留该区分的 [FileUtilsCommon.isDirAbsentOrEmpty]
     * (语义与下沉前桌面端 `DesktopCore.dirAbsentOrEmpty` 一致: "File.list() 在'是目录但读目录
     * 出错'时也返回 null, 这种情况按非空处理让清理照原样跑, 不用空判掩盖 IO 错误造成漏清")。
     */
    private fun dirHasChildren(dirPath: String): Boolean =
        !FileUtilsCommon.isDirAbsentOrEmpty(dirPath)

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
