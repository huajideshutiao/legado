package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.DefaultDataShared
import io.legado.app.help.config.HelpVersion
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.LocalConfigShared
import io.legado.app.help.config.PreferenceProviders
import io.legado.desktop.constant.DesktopAppInfo

/**
 * 桌面端首启 / 升级时的默认数据补齐 (对照 app 端 `App.onCreate` 的 `DefaultData.upVersion()`
 * 与 `dbCallback.onCreate`)。
 *
 * app 端两条来源桌面端都缺:
 * - `DefaultData.upVersion()`: 依赖 `LocalConfig` (SharedPreferences) + `AppConst.appInfo`
 *   (PackageManager); 这里换成 [PreferenceProviders] + [DesktopAppInfo] + 已下沉的
 *   [LocalConfigShared] 版本比较算法, 导入 httpTTS / txtTocRule / dictRule
 * - `dbCallback.onCreate` 的预置书架分组 + 键盘助手: Room KMP 的 BundledSQLiteDriver 未挂
 *   Callback (见 `BundledDatabaseDriver`), 改为启动期幂等补齐
 *
 * 每一项独立 runCatching: 单项资源缺失/解析失败不影响其余项与启动流程。
 */
suspend fun initDesktopDefaultData() {
    ensurePresetBookGroups()
    ensureKeyboardAssists()
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

/** 键盘助手: 空表时导入默认值 (对照 app 端 dbCallback 的 insert or replace)。 */
private suspend fun ensureKeyboardAssists() {
    runCatching {
        val dao = AppDbProviders.get().keyboardAssistsDao
        if (dao.all().isEmpty()) {
            dao.insert(*DefaultDataShared.keyboardAssists.toTypedArray())
        }
    }.onFailure { AppLog.put("补齐默认键盘助手失败", it) }
}

/** 对照 app 端 `DefaultData.upVersion()`: 版本号推进时按各资源版本 key 补齐默认数据。 */
private suspend fun upDefaultDataVersion() {
    val prefs = PreferenceProviders.get()
    val recorded = prefs.getLong(LocalConfigKeys.appVersionCode, 0L)
    if (recorded >= DesktopAppInfo.versionCode.toLong()) return

    // isLastVersion 命中即写回最新版本号, 故只会导入一次 (与 app 端 needUpXxx 语义一致)
    fun needUp(versionKey: String, lastVersion: Int): Boolean = !LocalConfigShared.isLastVersion(
        lastVersion = lastVersion,
        versionKey = versionKey,
        getInt = { k, d -> prefs.getInt(k, d) },
        getBoolean = { k, d -> prefs.getBoolean(k, d) },
        putInt = { k, v -> prefs.putInt(k, v) },
    )

    if (needUp(LocalConfigKeys.httpTtsVersion, HelpVersion.httpTts)) {
        runCatching { DefaultDataShared.importDefaultHttpTTS() }
            .onFailure { AppLog.put("导入默认 httpTTS 失败", it) }
    }
    if (needUp(LocalConfigKeys.txtTocRuleVersion, HelpVersion.txtTocRule)) {
        runCatching { DefaultDataShared.importDefaultTocRules() }
            .onFailure { AppLog.put("导入默认 txt 目录规则失败", it) }
    }
    if (needUp(LocalConfigKeys.needUpDictRule, HelpVersion.dictRule)) {
        runCatching { DefaultDataShared.importDefaultDictRules() }
            .onFailure { AppLog.put("导入默认字典规则失败", it) }
    }
    // app 端由 MainActivity 展示更新日志后写回, 桌面端无更新日志弹窗, 就地写回
    prefs.putLong(LocalConfigKeys.appVersionCode, DesktopAppInfo.versionCode.toLong())
}
