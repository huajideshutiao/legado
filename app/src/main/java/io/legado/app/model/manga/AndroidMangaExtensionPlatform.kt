package io.legado.app.model.manga

import android.content.Context
import eu.kanade.tachiyomi.source.model.FilterList
import io.legado.app.help.extension.ExtensionPrefs
import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.help.extension.model.ContentWarning
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.ui.book.manga.extension.MangaContentWarning
import io.legado.app.ui.book.manga.extension.MangaExtensionItem
import io.legado.app.ui.book.manga.extension.MangaExtensionService
import io.legado.app.ui.book.manga.extension.MangaExtensionUiState
import io.legado.app.ui.book.manga.extension.MangaInstallState
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [MangaExtensionService] 安卓实现: 桥接 [MangaExtensionManager] (DexClassLoader 装载
 * 插件 apk 仅 Android 可用), 同时承担插件源的虚拟 BookSource 行同步 (MangaPluginSources)
 * 与取数委派的筛选器共享 (MangaPluginFilterCache)。
 *
 * MainActivity.initializePlatform 一行注册; desktop 等端不注册 → UI 隐藏入口。
 */
class AndroidMangaExtensionPlatform(
    context: Context,
) : MangaExtensionService {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inited = AtomicBoolean(false)

    private val _state = MutableStateFlow(MangaExtensionUiState(loading = true))
    override val state = _state.asStateFlow()

    /** checkForUpdates 结果 (Manager 返回插件 name 列表), 并入条目 hasUpdate。 */
    @Volatile
    private var updatedNames: Set<String> = emptySet()

    private val installSteps = MutableStateFlow<Map<String, MangaInstallState>>(emptyMap())

    private val languages = MutableStateFlow(ExtensionPrefs.getSelectedLanguages())

    override fun init() {
        if (!inited.compareAndSet(false, true)) return
        MangaExtensionManager.init(appContext)

        // 虚拟 BookSource 行随装载源同步 (卸载插件后删除对应行, 并失效其筛选缓存)
        MangaExtensionManager.sources
            .onEach(::syncSourceRows)
            .launchIn(scope)

        val stepsWithLangs = combine(languages, installSteps) { langs, steps -> langs to steps }
        combine(
            MangaExtensionManager.loadedExtensions,
            MangaExtensionManager.notLoadedExtensions,
            MangaExtensionManager.availableExtensions,
            MangaExtensionManager.repos,
            stepsWithLangs,
        ) { loaded, notLoaded, available, repos, (langs, steps) ->
            MangaExtensionUiState(
                loading = false,
                installed = loaded.values.map { it.toItem() },
                notLoaded = notLoaded.values.map { it.toItem() },
                available = available
                    .filter { langs.isEmpty() || it.lang in langs || "all" in langs }
                    .map { it.toItem() },
                repos = repos.map { MangaRepoItem(it.name, it.indexUrl, it.signingKeyFingerprint) },
                installSteps = steps,
            )
        }.launchIn(scope)
    }

    /** 上一轮装载源 id 集合 (识别卸载, 失效对应筛选缓存)。 */
    @Volatile
    private var lastSourceIds: Set<Long> = emptySet()

    private fun syncSourceRows(registered: List<MangaExtensionManager.RegisteredMangaSource>) {
        val previousIds = lastSourceIds
        val currentIds = registered.mapTo(HashSet()) { it.source.id }
        lastSourceIds = currentIds
        scope.launch {
            MangaPluginSources.sync(registered)
            previousIds.forEach { id -> if (id !in currentIds) MangaPluginFilterCache.clear(id) }
        }
    }

    override suspend fun refresh() {
        MangaExtensionManager.findAvailableExtensions()
    }

    override val selectedLanguages: Set<String>
        get() = languages.value

    override fun setLanguages(newLanguages: Set<String>) {
        languages.value = newLanguages
        ExtensionPrefs.setSelectedLanguages(newLanguages)
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
    )

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
