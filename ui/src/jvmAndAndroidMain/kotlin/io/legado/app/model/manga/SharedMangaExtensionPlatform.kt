package io.legado.app.model.manga

import eu.kanade.tachiyomi.source.model.FilterList
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.extension.model.ContentWarning
import io.legado.app.help.extension.ExtensionPrefs
import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.help.extension.model.MangaExtensionRepo
import io.legado.app.help.extension.model.RepoKind
import io.legado.app.ui.book.manga.extension.MangaContentWarning
import io.legado.app.ui.book.manga.extension.MangaExtensionItem
import io.legado.app.ui.book.manga.extension.MangaExtensionKind
import io.legado.app.ui.book.manga.extension.MangaExtensionService
import io.legado.app.ui.book.manga.extension.MangaExtensionUiState
import io.legado.app.ui.book.manga.extension.MangaInstallState
import io.legado.app.ui.book.manga.extension.MangaPrefItem
import io.legado.app.ui.book.manga.extension.MangaPrefValue
import io.legado.app.ui.book.manga.extension.MangaRepoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桥接 [MangaExtensionManager] 的插件管理页状态组装与操作面 (JVM+Android 共用;
 * 装载编排由管理器直连, 平台差异收敛在 MangaExtensionHost)。
 *
 * [config] 承载插件自带配置页 (androidx.preference shim + 平台偏好) 的平台桥:
 * Android 端注入 app 模块实现, 桌面端不注入 → isConfigurable 恒 false,
 * 「设置」入口隐藏 (桌面配置页降级, 不阻塞主功能)。
 *
 * 注册时机对照: app 端 MainActivity.initializePlatform 一行注册; desktop 端
 * Main.kt 启动序列注册; 未注册端 (iOS/鸿蒙) UI 隐藏入口。
 */
open class SharedMangaExtensionPlatform(
    private val config: MangaExtensionConfig? = null,
) : MangaExtensionService {

    /**
     * 插件自带配置页的平台桥。接口只暴露跨层数据类 ([MangaPrefItem]),
     * androidx.preference shim 与平台偏好的解析细节留在实现侧。
     */
    interface MangaExtensionConfig {
        fun isConfigurable(pkgName: String): Boolean

        suspend fun buildPreferenceItems(pkgName: String): List<MangaPrefItem>

        suspend fun setPreferenceValue(pkgName: String, key: String, value: MangaPrefValue)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inited = AtomicBoolean(false)

    private val _state = MutableStateFlow(MangaExtensionUiState(loading = true))
    override val state = _state.asStateFlow()

    /** checkForUpdates 结果 (Manager 返回插件 name 列表), 并入条目 hasUpdate。 */
    @Volatile
    private var updatedNames: Set<String> = emptySet()

    private val installSteps = MutableStateFlow<Map<String, MangaInstallState>>(emptyMap())

    /**
     * 刷新中标志 (独立于 [MangaExtensionUiState.loading] 持有)。
     * 本字段是 combine 的一路输入, 因此 combine 每次重建 state 时会把它的**当前值**写进
     * `refreshing`, 不会被覆盖回 false —— 这正是复用 loading 做不到的地方。
     */
    private val refreshing = MutableStateFlow(false)

    private val languages = MutableStateFlow(ExtensionPrefs.getSelectedLanguages())

    override fun init() {
        if (!inited.compareAndSet(false, true)) return
        MangaExtensionManager.init()

        // kotlinx.coroutines 只有 2..5 元的强类型 combine, 故把 languages/installSteps/refreshing
        // 先合成三元组, 主 combine 保持 5 路 (refreshing 作为三元组一员仍参与主 combine 重建,
        // 不会被其它路发射覆盖)。
        val aux = combine(languages, installSteps, refreshing) { langs, steps, isRefreshing ->
            Triple(langs, steps, isRefreshing)
        }
        combine(
            MangaExtensionManager.loadedExtensions,
            MangaExtensionManager.notLoadedExtensions,
            MangaExtensionManager.availableExtensions,
            MangaExtensionManager.repos,
            aux,
        ) { loaded, notLoaded, available, repos, (langs, steps, isRefreshing) ->
            MangaExtensionUiState(
                loading = false,
                installed = loaded.values.map { it.toItem() },
                notLoaded = notLoaded.values.map { it.toItem() },
                available = available
                    .filter { langs.isEmpty() || it.lang in langs || "all" in langs }
                    .map { it.toItem() },
                availableLanguages = available.mapTo(sortedSetOf("all")) { it.lang ?: "all" },
                selectedLanguages = langs,
                repos = repos.map { MangaRepoItem(it.name, it.indexUrl, it.signingKeyFingerprint) },
                installSteps = steps,
                refreshing = isRefreshing,
            )
        }.onEach { _state.value = it }.launchIn(scope)
    }

    override suspend fun refresh() {
        // 已在刷新则不叠加 (幂等): refreshing 是 combine 的独立输入路, 置位/复位都会经
        // state 流出, 整页转圈由 UI 侧 `loading || refreshing` 呈现。
        if (refreshing.value) return
        refreshing.value = true
        try {
            MangaExtensionManager.findAvailableExtensions()
        } finally {
            refreshing.value = false
        }
    }

    override fun setLanguages(languages: Set<String>) {
        this.languages.value = languages
        ExtensionPrefs.setSelectedLanguages(languages)
    }

    override suspend fun checkForUpdates(): List<String> {
        val names = MangaExtensionManager.checkForUpdates()
        updatedNames = names.toSet()
        return names
    }

    override fun install(pkgName: String) {
        val extension = MangaExtensionManager.availableExtensions.value
            .firstOrNull { it.pkgName == pkgName } ?: return
        collectInstallStep(pkgName, MangaExtensionManager.installExtension(extension))
    }

    override fun update(pkgName: String) {
        val extension = MangaPluginSources.installedOf(pkgName) ?: return
        collectInstallStep(pkgName, MangaExtensionManager.updateExtension(extension))
    }

    override fun cancelInstall(pkgName: String) {
        MangaExtensionManager.cancelInstallUpdateExtension(pkgName)
        installSteps.value = installSteps.value - pkgName
    }

    override fun uninstall(pkgName: String) {
        val extension = MangaPluginSources.installedOf(pkgName) ?: return
        MangaExtensionManager.uninstallExtension(extension)
    }

    override fun trust(pkgName: String) {
        val extension = MangaExtensionManager.notLoadedExtensions.value[pkgName] ?: return
        MangaExtensionManager.trust(extension)
    }

    override suspend fun addRepo(url: String): Result<MangaRepoItem> =
        MangaExtensionManager.addRepo(url).mapCatching {
            MangaRepoItem(it.name, it.indexUrl, it.signingKeyFingerprint)
        }

    override suspend fun removeRepo(indexUrl: String) {
        MangaExtensionManager.removeRepo(indexUrl)
        MangaExtensionManager.findAvailableExtensions()
    }

    override fun isPluginSource(bookSourceUrl: String): Boolean =
        bookSourceUrl.startsWith(MangaSourceMapper.SOURCE_URL_PREFIX)

    override suspend fun getFilterList(bookSourceUrl: String): FilterList? {
        val sourceId = MangaSourceMapper.sourceIdOf(bookSourceUrl) ?: return null
        val source = MangaExtensionManager.getSource(sourceId) ?: return null
        return MangaPluginFilterCache.getOrCreate(source)
    }

    // region 插件自带配置 (ConfigurableSource/ConfigurableAnimeSource)

    override fun isConfigurable(pkgName: String): Boolean =
        config?.isConfigurable(pkgName) ?: false

    override suspend fun buildPreferenceItems(pkgName: String): List<MangaPrefItem> =
        config?.buildPreferenceItems(pkgName) ?: emptyList()

    override suspend fun setPreferenceValue(pkgName: String, key: String, value: MangaPrefValue) {
        config?.setPreferenceValue(pkgName, key, value)
    }

    // endregion

    private fun collectInstallStep(pkgName: String, flow: Flow<InstallStep>) {
        scope.launch {
            flow.collect { step ->
                val mapped = when (step) {
                    is InstallStep.Progress, InstallStep.Downloading -> MangaInstallState.DOWNLOADING
                    InstallStep.Installing -> MangaInstallState.INSTALLING
                    InstallStep.Installed -> MangaInstallState.INSTALLED
                    is InstallStep.Error -> MangaInstallState.ERROR
                }
                installSteps.value = installSteps.value + (pkgName to mapped)
            }
        }
    }

    private fun MangaExtension.Loaded.toItem() = MangaExtensionItem(
        pkgName = pkgName,
        name = name,
        versionName = versionName,
        versionCode = versionCode,
        lang = lang,
        contentWarning = contentWarning.toUi(),
        isNsfw = contentWarning == ContentWarning.NSFW,
        isInstalled = true,
        hasUpdate = hasUpdate || name in updatedNames,
        isObsolete = isObsolete,
        sourceCount = sources.size,
        iconUrl = iconUrlOf(pkgName),
        // 装载器已按源实例类型分流: animeSources 非空即视频扩展
        kind = if (animeSources.isNotEmpty()) {
            MangaExtensionKind.VIDEO
        } else {
            MangaExtensionKind.MANGA
        },
        isConfigurable = isConfigurable(pkgName),
    )

    private fun MangaExtension.NotLoaded.toItem() = MangaExtensionItem(
        pkgName = pkgName,
        name = name,
        versionName = versionName,
        versionCode = versionCode,
        lang = lang,
        contentWarning = contentWarning.toUi(),
        isNsfw = contentWarning == ContentWarning.NSFW,
        isInstalled = true,
        hasUpdate = hasUpdate,
        isUntrusted = reason is MangaExtension.NotLoaded.Reason.Untrusted,
        notLoadedReason = reason.toText(),
        iconUrl = iconUrlOf(pkgName),
        // 未装载出源, 只能按仓库 kind 判定 (repo 可能为 null, 兜底看包名约定)
        kind = kindOf(repo, pkgName),
    )

    private fun MangaExtension.Available.toItem() = MangaExtensionItem(
        pkgName = pkgName,
        name = name,
        versionName = versionName,
        versionCode = versionCode,
        lang = lang,
        contentWarning = contentWarning.toUi(),
        isNsfw = contentWarning == ContentWarning.NSFW,
        isInstalled = false,
        sourceCount = sources.size,
        iconUrl = iconUrl.takeIf { it.isNotBlank() },
        // 索引条目的 sources 字段漫画/视频同形 (都落 Available.sources), 无法据此区分;
        // 唯一可靠依据是条目所属仓库的 kind (yuzono/anime-repo = ANIME)
        kind = if (repo.kind == RepoKind.ANIME) {
            MangaExtensionKind.VIDEO
        } else {
            MangaExtensionKind.MANGA
        },
    )

    /** 已装/未装载条目的图标: 从仓库索引同 pkgName 条目反查 (已装实体不带 iconUrl)。 */
    private fun iconUrlOf(pkgName: String): String? =
        MangaExtensionManager.availableExtensions.value
            .firstOrNull { it.pkgName == pkgName }
            ?.iconUrl
            ?.takeIf { it.isNotBlank() }

    /**
     * 未装载条目的类型判定: 仓库 kind 优先; 索引里查不到该包 (repo==null) 时按 Aniyomi
     * 包名约定 (`eu.kanade.tachiyomi.animeextension.<lang>.<name>`) 兜底。
     */
    private fun kindOf(repo: MangaExtensionRepo?, pkgName: String): MangaExtensionKind = when {
        repo?.kind == RepoKind.ANIME -> MangaExtensionKind.VIDEO
        pkgName.contains("animeextension") -> MangaExtensionKind.VIDEO
        else -> MangaExtensionKind.MANGA
    }

    private fun ContentWarning.toUi(): MangaContentWarning = when (this) {
        ContentWarning.SAFE -> MangaContentWarning.SAFE
        ContentWarning.MIXED -> MangaContentWarning.MIXED
        ContentWarning.NSFW -> MangaContentWarning.NSFW
    }

    private fun MangaExtension.NotLoaded.Reason.toText(): String? = when (this) {
        is MangaExtension.NotLoaded.Reason.Untrusted -> null
        MangaExtension.NotLoaded.Reason.Filtered -> "被内容分级过滤设置屏蔽"
        MangaExtension.NotLoaded.Reason.Unsigned -> "插件无有效签名"
        MangaExtension.NotLoaded.Reason.UnsupportedLibVersion -> "扩展库版本不受支持"
        MangaExtension.NotLoaded.Reason.Malformed -> "插件元数据缺失"
        is MangaExtension.NotLoaded.Reason.Failed -> "加载失败: $message"
    }
}
