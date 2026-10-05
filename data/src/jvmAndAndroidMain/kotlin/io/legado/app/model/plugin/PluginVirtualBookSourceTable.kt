package io.legado.app.model.plugin

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.utils.GSON
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

/**
 * 插件虚拟书源行表公共实现 (漫画/视频共用, 消除两套手工同步的重复实现)。
 *
 * 历史教训 (2026-10-05): 漫画/视频两个 PluginSources 曾各自复制同步逻辑, 虚拟源登录
 * 标记 (loginUrl) 只补了视频侧、漏了漫画侧; 行构造/同步/清理只在本类实现一份,
 * 两侧薄壳 ([io.legado.app.model.manga.MangaPluginSources] /
 * [io.legado.app.model.anime.AnimePluginSources]) 仅提供类型差异
 * (分组/类型/前缀/头提取/源列表), 不再需要逐处手工对齐。
 */
internal class PluginVirtualBookSourceTable<SRC>(
    private val groupName: String,
    private val bookSourceType: Int,
    private val kindLabel: String,
    private val logTag: String,
    private val exploreUrl: String,
    private val headersOf: (SRC) -> Map<String, String>?,
) {

    /** 待同步行: url 身份 + 归属扩展包名 (可空) + 源实例 + 显示名。 */
    data class Row<SRC>(
        val url: String,
        val pkgName: String?,
        val source: SRC,
        val name: String,
    )

    /**
     * 由插件源构造虚拟 BookSource 行 (不落库)。
     * loginUrl 判定**唯一出处**: 可配置扩展把包名写进 loginUrl, 登录入口据此直达扩展设置
     * (未配置扩展无登录入口)。
     */
    fun buildVirtualSource(row: Row<SRC>): BookSource = BookSource(
        bookSourceUrl = row.url,
        bookSourceName = row.name,
        bookSourceGroup = groupName,
        bookSourceType = bookSourceType,
        enabled = true,
        enabledExplore = true,
        exploreUrl = exploreUrl,
        header = headerJsonOf(row.source),
        loginUrl = row.pkgName?.takeIf { MangaExtensionManager.isPkgConfigurable(it) },
        bookSourceComment = listOfNotNull(
            kindLabel,
            row.pkgName?.let { "pkg: $it" },
        ).joinToString("\n"),
    )

    /**
     * 与插件宿主已装载源同步虚拟行: 新装载 upsert (保留用户 enabled/customOrder),
     * 已卸载删除。由 MangaExtensionManager.loadExtensions 在注册表更新后调用。
     */
    suspend fun sync(rows: List<Row<SRC>>) = withContext(IoDispatcher) {
        runCatching {
            val dao = AppDbProviders.get().bookSourceDao
            val urls = rows.mapTo(HashSet()) { it.url }
            // 删除已卸载/失效的虚拟行
            val existing = dao.getByGroup(groupName)
                .filter { it.bookSourceType == bookSourceType }
            val stale = existing.filter { it.bookSourceUrl !in urls }
            if (stale.isNotEmpty()) dao.deleteIn(stale.map { it.bookSourceUrl })
            // upsert 新装载行 (存在则只补齐名称/登录包名/请求头, 不动用户设置)
            for (row in rows) {
                val fresh = buildVirtualSource(row)
                val old = dao.getBookSource(row.url)
                if (old == null) {
                    dao.insert(fresh)
                } else if (old.bookSourceName != fresh.bookSourceName ||
                    old.loginUrl != fresh.loginUrl ||
                    !headerSuperset(old.header, fresh.header)
                ) {
                    old.bookSourceName = fresh.bookSourceName
                    old.loginUrl = fresh.loginUrl
                    old.header = fresh.header
                    dao.update(old)
                }
            }
        }.onFailure {
            AppLog.put("$logTag 虚拟书源同步失败", it)
        }
    }

    /** 清理本组全部虚拟行 (插件宿主整体停用时使用)。 */
    suspend fun removeAll() = withContext(IoDispatcher) {
        runCatching {
            val dao = AppDbProviders.get().bookSourceDao
            val rows = dao.getByGroup(groupName)
                .filter { it.bookSourceType == bookSourceType }
            if (rows.isNotEmpty()) dao.deleteIn(rows.map { it.bookSourceUrl })
        }.onFailure {
            AppLog.put("$logTag 虚拟书源清理失败", it)
        }
    }

    private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

    private fun headerJsonOf(source: SRC): String? {
        val headers = headersOf(source) ?: return null
        return runCatching { GSON.encodeToString<Map<String, String>>(headers) }.getOrNull()
    }

    /** old.header 已含 fresh.header 全部键值时视为超集 (防盗链合并头), 不被静态头回退覆盖。 */
    private fun headerSuperset(oldJson: String?, freshJson: String?): Boolean {
        if (oldJson == freshJson) return true
        val old = oldJson?.let { runCatching { GSON.decodeFromString(stringMapSerializer, it) }.getOrNull() }
            ?: return false
        val fresh = freshJson?.let { runCatching { GSON.decodeFromString(stringMapSerializer, it) }.getOrNull() }
            ?: return true
        return old.entries.containsAll(fresh.entries)
    }
}

/** 已装载/未装载扩展查询 (漫画/视频共用, 原两侧 installedOf 收敛于此)。 */
fun pluginInstalledOf(pkgName: String): MangaExtension.Installed? =
    MangaExtensionManager.loadedExtensions.value[pkgName]
        ?: MangaExtensionManager.notLoadedExtensions.value[pkgName]
