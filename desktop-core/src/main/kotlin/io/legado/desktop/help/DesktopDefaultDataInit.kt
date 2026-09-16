package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.DefaultDataShared
import io.legado.app.help.config.HelpVersion
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.desktop.constant.DesktopAppInfo

/**
 * 桌面端首启 / 升级时的默认数据补齐 (对照 app 端 `App.onCreate` 的 `DefaultData.upVersion()`
 * 与 `dbCallback.onCreate`)。
 *
 * app 端两条来源桌面端都缺:
 * - `DefaultData.upVersion()`: 依赖 `LocalConfig` (SharedPreferences) + `AppConst.appInfo`
 *   (PackageManager); 这里换成 [PreferenceProviders] + [DesktopAppInfo] + 已下沉的
 *   下面的 importOnce 版本号门 (导入成功才推进, 失败下次再补), 导入 httpTTS / txtTocRule / dictRule
 * - `dbCallback.onCreate` 的预置书架分组 + 键盘助手: 桌面端**已经**挂在建库回调 ——
 *   `BundledDatabaseDriver` 构造时 `.addCallback(AppDatabaseDefaults)`, 其 `onCreate` 调
 *   `AppDatabaseDefaultData.insert` 插同名的 4 个预置分组与键盘助手 (SQL 自带 not exists /
 *   insert or replace 幂等)。所以下面两个 `ensure*` **不是**主路径, 只兜两件事: 建库回调挂上
 *   之前的老库, 以及以后新增的预置项 —— 因此与 upVersion 共用同一道版本门。
 *
 * 为什么必须入门 (2026-09 启动实测): 原来两个 `ensure*` 每次启动无条件跑 5 条 SQL
 * (4 条 `getByID` + 1 条 keyboardAssists 全表读), 空库照跑; 更要紧的是它们可能在首帧之后往
 * book_groups 写数据 → 多推一次 Room 失效推送 → 多一轮书架整树重组。
 *
 * 每一项独立 runCatching: 单项资源缺失/解析失败不影响其余项与启动流程。
 */
suspend fun initDesktopDefaultData() {
    // 建库预置项只在版本号推进时补一次。upDefaultDataVersion 是在自己末尾才写回 appVersionCode,
    // 所以本判断必须在它之前, 否则同一版本内就再也补不上。
    if (PreferenceProviders.get().getLong(LocalConfigKeys.appVersionCode, 0L)
        < DesktopAppInfo.versionCode.toLong()
    ) {
        ensurePresetBookGroups()
        ensureKeyboardAssists()
    }
    upDefaultDataVersion()
}

/** 预置分组: 全部 / 本地 / 未分组 / 更新失败 (id + 名称 + order 与 app 端 dbCallback 一致)。 */
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

/** 键盘助手: 按 (type,key) 逐项补齐缺失项 (对照 app 端 dbCallback 的 insert or replace)。 */
private suspend fun ensureKeyboardAssists() {
    runCatching {
        val dao = AppDbProviders.get().keyboardAssistsDao
        // 旧实现是"整表为空才写": 库非空时以后新增的预置助手永远补不进去, 与该函数 KDoc
        // 自述的"兜以后新增的预置项"相反; 这里改为按主键 (type,key) 缺啥补啥
        val existing = dao.all().map { it.type to it.key }.toSet()
        val missing = DefaultDataShared.keyboardAssists.filter { (it.type to it.key) !in existing }
        if (missing.isNotEmpty()) dao.insert(*missing.toTypedArray())
    }.onFailure { AppLog.put("补齐默认键盘助手失败", it) }
}

/** 对照 app 端 `DefaultData.upVersion()`: 版本号推进时按各资源版本 key 补齐默认数据。 */
private suspend fun upDefaultDataVersion() {
    val prefs = PreferenceProviders.get()
    val recorded = prefs.getLong(LocalConfigKeys.appVersionCode, 0L)
    if (recorded >= DesktopAppInfo.versionCode.toLong()) return

    // 版本 key 只在**导入成功**后才写回。旧实现直接复用 LocalConfigShared.isLastVersion
    // (命中即先写回版本号), 加上本函数末尾无条件写 appVersionCode —— 导入失败那一次之后
    // 两个门都已关, 默认 httpTTS/目录规则/字典规则永远补不上 (桌面端没有 app 端那条
    // "下次启动再走 upVersion" 的路径)。任一项失败就不推进 appVersionCode, 下次启动重试。
    var allImported = true
    suspend fun importOnce(versionKey: String, lastVersion: Int, what: String, import: suspend () -> Unit) {
        if (prefs.getInt(versionKey, 0) >= lastVersion) return
        runCatching { import() }
            .onSuccess { prefs.putInt(versionKey, lastVersion) }
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
    // app 端由 MainActivity 展示更新日志后写回, 桌面端无更新日志弹窗, 就地写回
    if (allImported) {
        prefs.putLong(LocalConfigKeys.appVersionCode, DesktopAppInfo.versionCode.toLong())
    }
}
