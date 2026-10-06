package io.legado.app.help.extension

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.help.extension.model.MangaExtensionRepo
import io.legado.app.help.extension.repo.RepoHelper
import io.legado.app.help.extension.trust.TrustHelper
import io.legado.app.model.anime.AnimePluginSources
import io.legado.app.model.manga.MangaPluginFilterCache
import io.legado.app.model.manga.MangaPluginSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 漫画扩展管理器 (单例)。处理已装扩展的加载/卸载、仓库索引拉取、安装/更新、
 * 签名信任与源注册表。由宿主启动序列调用 [init] 触发 (幂等), 未注册宿主时静默跳过。
 *
 * 扩展数据全部以 JSON 字符串偏好持久化 (见 [ExtensionPrefs]), 自动随备份进出;
 * 恢复完成后调 [onRestoreFinished] 重载内存态。
 *
 * 平台相关面 (枚举装载/安装/卸载/广播监听) 经 [MangaExtensionHostProviders] 注入:
 * Android 端 PackageManager + 私有扩展文件, 桌面端扩展目录扫描 + JvmExtensionLoader。
 */
object MangaExtensionManager {

    /** 已加载扩展注册的漫画源, pkgName 用于反查归属扩展。 */
    data class RegisteredMangaSource(
        val source: Source,
        val pkgName: String,
    )

    /** 已加载扩展注册的视频源 (Aniyomi 系), 形状对齐 [RegisteredMangaSource]。 */
    data class RegisteredAnimeSource(
        val source: AnimeSource,
        val pkgName: String,
    )

    private val started = AtomicBoolean(false)
    private val initialized = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    private val _loadedExtensionsFlow = MutableStateFlow<Map<String, MangaExtension.Loaded>>(emptyMap())
    private val _notLoadedExtensionsFlow = MutableStateFlow<Map<String, MangaExtension.NotLoaded>>(emptyMap())
    private val _availableExtensionsFlow = MutableStateFlow<List<MangaExtension.Available>>(emptyList())
    private val _reposFlow = MutableStateFlow<List<MangaExtensionRepo>>(emptyList())
    private val _sourcesFlow = MutableStateFlow<List<RegisteredMangaSource>>(emptyList())
    private val _animeSourcesFlow = MutableStateFlow<List<RegisteredAnimeSource>>(emptyList())

    val loadedExtensions: StateFlow<Map<String, MangaExtension.Loaded>> = _loadedExtensionsFlow
    val notLoadedExtensions: StateFlow<Map<String, MangaExtension.NotLoaded>> = _notLoadedExtensionsFlow
    val availableExtensions: StateFlow<List<MangaExtension.Available>> = _availableExtensionsFlow
    val repos: StateFlow<List<MangaExtensionRepo>> = _reposFlow
    val sources: StateFlow<List<RegisteredMangaSource>> = _sourcesFlow
    val animeSources: StateFlow<List<RegisteredAnimeSource>> = _animeSourcesFlow

    /** UI 首入口触发; 重复调用无副作用。 */
    fun init() {
        if (!started.compareAndSet(false, true)) return
        _reposFlow.value = ExtensionPrefs.getRepos()
        // 平台初始化收尾 (Android 注册安装事件广播, 维持"扩展 UI 首入口才装广播"时序;
        // 平台未注册时静默跳过, 装载阶段按同样语义兜底)
        runCatching { MangaExtensionHostProviders.getOrNull()?.onInitialized() }
            .onFailure { AppLog.put("扩展宿主初始化失败", it) }
        scope.launch {
            reloadMutex.withLock {
                // 索引缓存仅加速首屏, 随后可被 findAvailableExtensions 覆盖
                _availableExtensionsFlow.value = RepoHelper.loadCachedIndex()
                refreshStatuses()
                loadExtensionsLocked()
            }
        }
    }

    private suspend fun awaitInitialized(): Boolean {
        if (!started.get()) return false
        initialized.await()
        return true
    }

    suspend fun getLoadedExtensions(): List<MangaExtension.Loaded> {
        if (!awaitInitialized()) return emptyList()
        return _loadedExtensionsFlow.value.values.sortedBy { it.name }
    }

    suspend fun getNotLoadedExtensions(): List<MangaExtension.NotLoaded> {
        if (!awaitInitialized()) return emptyList()
        return _notLoadedExtensionsFlow.value.values.sortedBy { it.name }
    }

    // region 源注册表

    fun getSource(sourceId: Long): Source? {
        return _sourcesFlow.value.firstOrNull { it.source.id == sourceId }?.source
    }

    fun getAllSources(): List<Source> {
        return _sourcesFlow.value.map { it.source }
    }

    fun getEnabledSources(): List<Source> {
        return _sourcesFlow.value
            .filter { ExtensionPrefs.isSourceEnabled(it.source.id) }
            .map { it.source }
    }

    fun getPackageBySource(sourceId: Long): String? {
        return _sourcesFlow.value.firstOrNull { it.source.id == sourceId }?.pkgName
    }

    // ---- 视频源注册表 (启用表与漫画共用稀疏存储, 源 id 均为 MD5 域不重叠) ----

    fun getAnimeSource(sourceId: Long): AnimeSource? {
        return _animeSourcesFlow.value.firstOrNull { it.source.id == sourceId }?.source
    }

    fun getAllAnimeSources(): List<AnimeSource> {
        return _animeSourcesFlow.value.map { it.source }
    }

    fun getPackageByAnimeSource(sourceId: Long): String? {
        return _animeSourcesFlow.value.firstOrNull { it.source.id == sourceId }?.pkgName
    }

    fun isAnimeSourceEnabled(sourceId: Long): Boolean = ExtensionPrefs.isSourceEnabled(sourceId)

    fun setAnimeSourceEnabled(sourceId: Long, enabled: Boolean) {
        ExtensionPrefs.setSourceEnabled(sourceId, enabled)
    }

    /** 包名首个已装载源实例 (漫画源优先, 视频源次之); 未装载出源返回 null。 */
    fun firstSourceOf(pkgName: String): Any? =
        _sourcesFlow.value.firstOrNull { it.pkgName == pkgName }?.source
            ?: _animeSourcesFlow.value.firstOrNull { it.pkgName == pkgName }?.source

    /** 该扩展包是否提供自带配置界面 (包首个已装载源实现 Configurable* 契约)。 */
    fun isPkgConfigurable(pkgName: String): Boolean {
        val source = firstSourceOf(pkgName) ?: return false
        return source is ConfigurableSource || source is ConfigurableAnimeSource
    }

    fun getRepos(): List<MangaExtensionRepo> = _reposFlow.value

    fun isSourceEnabled(sourceId: Long): Boolean = ExtensionPrefs.isSourceEnabled(sourceId)

    fun setSourceEnabled(sourceId: Long, enabled: Boolean) {
        ExtensionPrefs.setSourceEnabled(sourceId, enabled)
    }

    // endregion

    // region 扩展加载

    private val reloadMutex = Mutex()

    /** 上一轮装载源 id 集合 (识别卸载, 失效对应筛选缓存)。 */
    @Volatile
    private var lastSyncedSourceIds: Set<Long> = emptySet()

    private fun invalidateRemovedFilterCaches(currentIds: Set<Long>) {
        lastSyncedSourceIds.forEach { id -> if (id !in currentIds) MangaPluginFilterCache.clear(id) }
        lastSyncedSourceIds = currentIds
    }

    /**
     * 整表重扫 (安装事件与信任变更后的入口)。
     */
    fun reloadExtensions() {
        scope.launch { loadExtensions() }
    }

    /**
     * 装载入口 (互斥串行): 装载进行中再触发排队执行, 防止旧扫描结果覆盖新扫描;
     * 桌面端 dex2jar 产物落盘也靠本互斥不并发互踩。首轮装载与备份恢复与重扫走同一把锁。
     */
    private suspend fun loadExtensions() {
        reloadMutex.withLock { loadExtensionsLocked() }
    }

    private suspend fun loadExtensionsLocked() {
        try {
            val host = MangaExtensionHostProviders.getOrNull()
                ?: run {
                    AppLog.put("扩展宿主未注册 (MangaExtensionHostProviders), 跳过装载")
                    return
                }
            val extensions = host.loadExtensions(_loadedExtensionsFlow.value)
            _loadedExtensionsFlow.value = extensions
                .filterIsInstance<MangaExtension.Loaded>()
                .associateBy { it.pkgName }
            _notLoadedExtensionsFlow.value = extensions
                .filterIsInstance<MangaExtension.NotLoaded>()
                .associateBy { it.pkgName }
            _sourcesFlow.value = _loadedExtensionsFlow.value.values
                .flatMap { ext -> ext.sources.map { RegisteredMangaSource(it, ext.pkgName) } }
                .distinctBy { it.source.id }
            _animeSourcesFlow.value = _loadedExtensionsFlow.value.values
                .flatMap { ext -> ext.animeSources.map { RegisteredAnimeSource(it, ext.pkgName) } }
                .distinctBy { it.source.id }
            // 插件虚拟书源行随注册表同步 (漫画/视频同构直连, 不依赖扩展管理页 UI 首入口:
            // 书源页的登录标记/分组/排序值都落在行上, 必须每次装载注册表后即刷新)
            runCatching { MangaPluginSources.sync(_sourcesFlow.value) }
                .onFailure {
                    if (it is CancellationException) throw it
                    AppLog.put("漫画插件源虚拟行同步失败", it)
                }
            runCatching { AnimePluginSources.sync(_animeSourcesFlow.value) }
                .onFailure {
                    if (it is CancellationException) throw it
                    AppLog.put("视频插件源虚拟行同步失败", it)
                }
            invalidateRemovedFilterCaches(
                _sourcesFlow.value.mapTo(HashSet()) { it.source.id }
            )
            refreshStatuses()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            AppLog.put("漫画扩展加载失败", e)
        } finally {
            initialized.complete(Unit)
        }
    }

    // endregion

    // region 安装 / 更新 / 卸载

    fun installExtension(extension: MangaExtension.Available): Flow<InstallStep> {
        return MangaExtensionHostProviders.getOrNull()?.install(extension) ?: emptyFlow()
    }

    fun updateExtension(extension: MangaExtension.Installed): Flow<InstallStep> {
        val update = extension.findUpdate(_availableExtensionsFlow.value) ?: return emptyFlow()
        return installExtension(update)
    }

    fun cancelInstallUpdateExtension(pkgName: String) {
        MangaExtensionHostProviders.getOrNull()?.cancelInstall(pkgName)
    }

    fun uninstallExtension(extension: MangaExtension.Installed) {
        MangaExtensionHostProviders.getOrNull()?.uninstall(extension)
    }

    // endregion

    // region 信任

    fun trust(extension: MangaExtension.NotLoaded) {
        val reason = extension.reason as? MangaExtension.NotLoaded.Reason.Untrusted ?: return
        if (_notLoadedExtensionsFlow.value[extension.pkgName] == null) return
        TrustHelper.trust(extension.pkgName, extension.versionCode, reason.signatureHash)
        reloadExtensions()
    }

    fun revokeAllTrusted() {
        TrustHelper.revokeAll()
        reloadExtensions()
    }

    // endregion

    // region 仓库

    /**
     * 添加仓库: URL 归一化查重, 从 repo.json 取展示名与签名指纹, 持久化后
     * 后台拉取索引。元信息获取失败则不落库。
     */
    suspend fun addRepo(rawUrl: String): Result<MangaExtensionRepo> = withContext(IoDispatcher) {
        runCatching {
            val indexUrl = RepoHelper.normalizeIndexUrl(rawUrl)
            check(_reposFlow.value.none { it.indexUrl == indexUrl }) { "仓库已存在: $indexUrl" }
            val (name, fingerprint) = RepoHelper.fetchRepoMeta(indexUrl)
            val repo = MangaExtensionRepo(
                indexUrl = indexUrl,
                name = name,
                signingKeyFingerprint = fingerprint,
            )
            ExtensionPrefs.addUserRepo(repo)
            _reposFlow.value = ExtensionPrefs.getRepos()
            // 后台拉取新仓库索引, 不阻塞添加结果
            scope.launch {
                runCatching { findAvailableExtensions() }
                    .onFailure { if (it is CancellationException) throw it }
            }
            repo
        }.onFailure { if (it is CancellationException) throw it }
    }

    fun removeRepo(indexUrl: String) {
        ExtensionPrefs.removeRepo(indexUrl)
        _reposFlow.value = ExtensionPrefs.getRepos()
        _availableExtensionsFlow.update { list ->
            list.filterNot { it.repo.indexUrl == indexUrl }
        }
        RepoHelper.saveCachedIndex(_availableExtensionsFlow.value)
        refreshStatuses()
    }

    // endregion

    // region 可用扩展

    /**
     * 拉取全部仓库索引并合并去重 (同包名同签名指纹只留最新条目), 写入
     * availableExtensions 与本地缓存。全部仓库失败时抛出, 部分失败仅记日志。
     */
    suspend fun findAvailableExtensions(): List<MangaExtension.Available> = withContext(IoDispatcher) {
        val repoList = _reposFlow.value
        val results = repoList.map { repo ->
            async { repo to runCatching { RepoHelper.fetchIndex(repo) } }
        }.awaitAll()

        val failures = mutableListOf<Throwable>()
        val listings = results.flatMap { (repo, result) ->
            result.getOrElse { e ->
                if (e is CancellationException) throw e
                failures += e
                AppLog.put("扩展仓库拉取失败: ${repo.name}", e)
                emptyList()
            }
        }
        if (repoList.isNotEmpty() && failures.size == repoList.size) {
            throw NoStackTraceException(failures.last().message ?: "扩展仓库拉取失败")
        }

        val merged = listings
            .groupBy { it.pkgName to it.repo.signingKeyFingerprint.lowercase() }
            .values.map { entries ->
                entries.maxWith(
                    compareBy<MangaExtension.Available> { it.versionCode }.thenBy { it.libVersion }
                )
            }
            .sortedBy { it.name }
        _availableExtensionsFlow.value = merged
        RepoHelper.saveCachedIndex(merged)
        refreshStatuses()
        merged
    }

    /** 刷新仓库索引, 返回有更新的扩展名; 拉取失败静默返回空表。 */
    suspend fun checkForUpdates(): List<String> {
        try {
            findAvailableExtensions()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLog.put("扩展更新检查失败", e)
            return emptyList()
        }
        return (_loadedExtensionsFlow.value.values + _notLoadedExtensionsFlow.value.values)
            .filter { it.hasUpdate }
            .map { it.name }
            .distinct()
    }

    // endregion

    /**
     * 备份恢复收尾: 偏好已被恢复覆盖, 重载仓库/信任/启用表/扩展列表等内存态。
     * 未初始化过时为 no-op。
     */
    suspend fun onRestoreFinished() {
        if (!started.get()) return
        reloadMutex.withLock {
            _reposFlow.value = ExtensionPrefs.getRepos()
            _availableExtensionsFlow.value = RepoHelper.loadCachedIndex()
            loadExtensionsLocked()
        }
    }

    /**
     * 依据仓库条目推导已安装扩展的更新/归属状态; 无条目时保持原值 (不标过期)。
     */
    private fun refreshStatuses() {
        val available = _availableExtensionsFlow.value
        if (available.isEmpty()) return
        _loadedExtensionsFlow.update { loaded ->
            loaded.mapValues { (_, ext) ->
                ext.copy(
                    hasUpdate = ext.findUpdate(available) != null,
                    isObsolete = ext.findListing(available) == null,
                    repo = pickRepo(ext.pkgName, ext.signatures, available),
                )
            }
        }
        _notLoadedExtensionsFlow.update { notLoaded ->
            notLoaded.mapValues { (_, ext) ->
                ext.copy(
                    hasUpdate = ext.findUpdate(available) != null,
                    repo = pickRepo(ext.pkgName, ext.signatures, available),
                )
            }
        }
    }

    /** 签名匹配的仓库里, 优先取实际列出该扩展的那个。 */
    private fun pickRepo(
        pkgName: String,
        signatures: List<String>,
        available: List<MangaExtension.Available>,
    ): MangaExtensionRepo? {
        val signatureSet = signatures.mapTo(HashSet()) { it.lowercase() }
        val signingRepos = _reposFlow.value.filter { it.signingKeyFingerprint.lowercase() in signatureSet }
        if (signingRepos.isEmpty()) return null
        val listedBy = available.filter { it.pkgName == pkgName }.mapTo(HashSet()) { it.repo.indexUrl }
        return signingRepos.firstOrNull { it.indexUrl in listedBy } ?: signingRepos.first()
    }
}
