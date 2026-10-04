package io.legado.app.model.manga

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import io.legado.app.help.extension.ExtensionPrefs
import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.constant.AppLog
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.help.extension.model.ContentWarning
import io.legado.app.help.extension.model.MangaExtension
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

    /**
     * 刷新中标志 (独立于 [MangaExtensionUiState.loading] 持有)。
     * 本字段是 combine 的一路输入, 因此 combine 每次重建 state 时会把它的**当前值**写进
     * `refreshing`, 不会被覆盖回 false —— 这正是复用 loading 做不到的地方。
     */
    private val refreshing = MutableStateFlow(false)

    private val languages = MutableStateFlow(ExtensionPrefs.getSelectedLanguages())

    override fun init() {
        if (!inited.compareAndSet(false, true)) return
        MangaExtensionManager.init(appContext)

        // 虚拟 BookSource 行随装载源同步 (卸载插件后删除对应行, 并失效其筛选缓存)
        MangaExtensionManager.sources
            .onEach(::syncSourceRows)
            .launchIn(scope)

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
                repos = repos.map { MangaRepoItem(it.name, it.indexUrl, it.signingKeyFingerprint) },
                installSteps = steps,
                refreshing = isRefreshing,
            )
        }.onEach { _state.value = it }.launchIn(scope)
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

    override val selectedLanguages: Set<String>
        get() = languages.value

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

    /** 按 pkgName 取已装载源实例 (漫画源优先, 视频源次之); 未装载出源返回 null。 */
    private fun sourceOf(pkgName: String): Any? =
        MangaExtensionManager.sources.value.firstOrNull { it.pkgName == pkgName }?.source
            ?: MangaExtensionManager.animeSources.value.firstOrNull { it.pkgName == pkgName }?.source

    override fun isConfigurable(pkgName: String): Boolean = when (sourceOf(pkgName)) {
        is ConfigurableSource, is ConfigurableAnimeSource -> true
        else -> false
    }

    override suspend fun buildPreferenceItems(pkgName: String): List<MangaPrefItem> =
        withContext(IoDispatcher) {
            val source = sourceOf(pkgName) ?: return@withContext emptyList()
            // 对齐 keiyoushi stub: 插件 setupPreferenceScreen 首句即 screen.context, shim 必须能提供
            val screen = PreferenceScreen(appContext)
            // 插件 setupPreferenceScreen 抛错 (shim 缺 API/扩展内 NPE 等) 如实上抛:
            // 吞成空表会把"读取失败"伪装成"没有可配置项"
            try {
                when (source) {
                    is ConfigurableSource -> source.setupPreferenceScreen(screen)
                    is ConfigurableAnimeSource -> source.setupPreferenceScreen(screen)
                    else -> Unit
                }
            } catch (e: Throwable) {
                AppLog.put("插件配置读取失败 $pkgName\n${e.message}", e)
                throw e
            }
            val prefs = sourcePrefsOf(source) ?: return@withContext emptyList()
            // shim 的 getPreferences() 是 Kotlin 函数 (非 Java getter), 不合成 `.preferences` 属性,
            // 且其后备字段为 private, 必须走函数调用。
            screen.getPreferences()
                .filter { it.visible }
                .map { it.toPrefItem(prefs) }
        }

    override suspend fun setPreferenceValue(pkgName: String, key: String, value: MangaPrefValue) {
        withContext(IoDispatcher) {
            val source = sourceOf(pkgName) ?: return@withContext
            val prefs = sourcePrefsOf(source) ?: return@withContext
            val editor = prefs.edit()
            when (value) {
                is MangaPrefValue.Text -> editor.putString(key, value.value)
                is MangaPrefValue.Flag -> editor.putBoolean(key, value.value)
                is MangaPrefValue.Choice -> editor.putString(key, value.value)
                is MangaPrefValue.MultiChoice -> editor.putStringSet(key, value.values)
            }
            editor.apply()
        }
    }

    /**
     * 扩展自身偏好文件: `source_<sourceId>` (对齐 keiyoushi.utils.getPreferencesLazy /
     * Aniyomi sourcePreferences 的 `source_$id` 契约)。
     */
    private fun sourcePrefsOf(source: Any): SharedPreferences? {
        val id = when (source) {
            is Source -> source.id
            is AnimeSource -> source.id
            else -> return null
        }
        return appContext.getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

    /**
     * shim Preference → 跨层 [MangaPrefItem]。当前值优先读插件偏好, 缺失回落到 shim 上的
     * 声明值 (setDefaultValue/构造时 setChecked 等)。shim 的 getter 在未设置时为 null,
     * 故所有读取都带默认值。
     */
    private fun Preference.toPrefItem(prefs: SharedPreferences): MangaPrefItem {
        val entries = when (this) {
            is ListPreference -> this.entries?.map { it.toString() }.orEmpty()
            is MultiSelectListPreference -> this.entries?.map { it.toString() }.orEmpty()
            else -> emptyList()
        }
        val entryValues = when (this) {
            is ListPreference -> this.entryValues?.map { it.toString() }.orEmpty()
            is MultiSelectListPreference -> this.entryValues?.map { it.toString() }.orEmpty()
            else -> emptyList()
        }
        // 回落值优先取 setDefaultValue 的声明值 (对齐 androidx.preference: 多数插件只声明
        // 默认值不赋现值, 漏声明值会把默认开的开关误显示为关)
        val value: MangaPrefValue = when (this) {
            is ListPreference -> {
                val current = key?.let { prefs.getString(it, null) }
                    ?: defaultValue?.toString() ?: this.value
                MangaPrefValue.Choice(current ?: entryValues.firstOrNull().orEmpty())
            }

            is MultiSelectListPreference -> {
                val declared = (defaultValue as? Set<*>)?.map { it.toString() }?.toSet()
                val current = key?.let { prefs.getStringSet(it, null) } ?: declared ?: this.values
                MangaPrefValue.MultiChoice(current.toSet())
            }

            is TwoStatePreference -> {
                val declared = (defaultValue as? Boolean) ?: this.isChecked
                val current = key?.let { prefs.getBoolean(it, declared) } ?: declared
                MangaPrefValue.Flag(current)
            }

            is EditTextPreference -> {
                val current = key?.let { prefs.getString(it, null) }
                    ?: defaultValue?.toString() ?: this.text
                MangaPrefValue.Text(current.orEmpty())
            }

            else -> {
                val current = key?.let { prefs.getString(it, defaultValue?.toString()) }
                    ?: defaultValue?.toString()
                MangaPrefValue.Text(current.orEmpty())
            }
        }
        return MangaPrefItem(
            key = key,
            title = title?.toString().orEmpty(),
            summary = summary?.toString(),
            value = value,
            entries = entries,
            entryValues = entryValues,
        )
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
