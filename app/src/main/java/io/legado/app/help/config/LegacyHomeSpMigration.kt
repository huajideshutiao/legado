package io.legado.app.help.config

import android.content.SharedPreferences
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.storage.FilesJsonStore

/**
 * 旧版（KMP 化前）Android 主页设置与收藏的迁移: default SP JSON → filesDir JSON 文件。
 *
 * 背景: e8b2c5837d 起 [HomeTabHelpShared] / [PinnedExploreHelp] 的真身从 default SP
 * 改存 filesDir JSON 文件, 但没做迁移, SP 里的旧数据自此弃读 —— 旧版升级用户的主页
 * Tab/展示项与「收藏的发现」全部回落默认。本迁移把旧值搬回。
 *
 * 策略:
 * - 幂等靠"目标文件存在即跳过"保证 (只补不覆盖: 用户已在 KMP 版编辑过主页/收藏一律不动),
 *   不引入迁移完成标记 —— 标记在写文件失败时也会置位, 一次 IO 失败就永久跳过迁移;
 *   无标记还能自愈 Auto Backup 只恢复 SP 而未随迁 filesDir 的场景;
 * - SP 里的 JSON 字符串原样搬运, 不反序列化重排 (新旧字段同名, KMP 端 GSON 直读);
 * - 旧 SP key 保留不删, 留作回滚保险;
 * - 写完 invalidate() 清内存缓存, 兼容迁移晚于首次 load 的场景。
 *
 * 调用时机: App.onCreate 同步段 (registerAndroidPreferenceProvider 之后,
 * AppFilesDirs 注册之后), 必须早于 HomeScreen 首次 [HomeTabHelpShared.load]。
 * 同步文件 IO 为刻意取舍: 异步化会让"早于首次 load"的时序保证失效; 量级 KB 级,
 * 迁移完成后每次启动仅 2 次文件存在性检查。
 */
fun migrateLegacyHomeSp(prefs: SharedPreferences) {
    // 主页 Tab 树: SP "homeTabs" → homeTabs.json
    prefs.getString(HomeTabHelpShared.PREF_KEY, null)?.takeIf { it.isNotBlank() }?.let { json ->
        if (FilesJsonStore.readText(HomeTabHelpShared.FILE_NAME) == null &&
            FilesJsonStore.writeText(HomeTabHelpShared.FILE_NAME, json)
        ) {
            HomeTabHelpShared.invalidate()
        }
    }
    // 收藏的发现: SP "exploreFavorites" → exploreFavorites.json
    prefs.getString(PinnedExploreHelp.PREF_KEY, null)?.takeIf { it.isNotBlank() }?.let { json ->
        if (FilesJsonStore.readText(PinnedExploreHelp.FILE_NAME) == null &&
            FilesJsonStore.writeText(PinnedExploreHelp.FILE_NAME, json)
        ) {
            PinnedExploreHelp.invalidate()
        }
    }
}
