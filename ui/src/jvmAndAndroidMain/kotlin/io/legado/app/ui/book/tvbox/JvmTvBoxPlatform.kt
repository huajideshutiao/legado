package io.legado.app.ui.book.tvbox

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.tvbox.TvBoxConfig
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.model.tvbox.TvBoxManager
import io.legado.app.model.tvbox.TvBoxPluginSources
import io.legado.app.model.tvbox.TvBoxSourceMapper
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TvBoxService] 的 JVM 家族实现 (jvmAndAndroidMain: Android 与桌面共用): 桥接
 * [TvBoxManager] (配置拉取/持久化) 与它同步出来的虚拟 BookSource 行
 * (站点开关 = 该行是否存在, 即站点是否已添加; 行的 enabled 归书源界面管,
 * 控制是否参与搜索, 本层不写它)。
 *
 * Android 端 MainActivity.initializePlatform 一行注册, 桌面端 DesktopCore
 * registerDesktopTvBoxProviders 注册; 未注册端 UI 隐藏入口。
 *
 * 平台依赖经 provider 注入: 目录走 [AppFilesDirs] (Android=filesDir, 桌面=
 * {dataRoot}/files, tvbox/ 布局两端一致), jar 装载/引导脚本/上下文走
 * [TvBoxPlatforms] (Android=DexClassLoader+assets, 桌面=URLClassLoader+classpath)。
 *
 * 配置来源列表 (多路添加/删除/切换) 是 UI 层自持的增面: TvBoxManager 只持久化当前
 * 生效的一份 (filesDir/tvbox/config.json + config_url.txt), 这里另存 sources.json
 * 记录全部已添加来源, 这样尽可能不动 model/tvbox 既有实现。
 */
class JvmTvBoxPlatform : TvBoxService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inited = AtomicBoolean(false)
    private val importLock = Mutex()

    private val _state = MutableStateFlow(TvBoxUiState())
    override val state: StateFlow<TvBoxUiState> = _state.asStateFlow()

    // 状态真源 (写路径分散在 IO / Default 线程, JVM 跨线程可见性靠 volatile;
    // 地图的大小写更新是整体赋值, 不是原地改, 故无复合写竞争)
    @Volatile
    private var jarProbes: Map<String, TvBoxJarItem> = emptyMap()

    /**
     * 当前库中实际存在的虚拟书源 URL 集合 (DAO flow 回填)。
     *
     * 管理页的"已添加"必须反映行此刻是否真在库里, 而非仅"未被 TVBox 侧关闭" ——
     * 否则行在书源界面被删后, 管理页仍显示已添加且再点开关无效。
     */
    @Volatile
    private var existingRows: Set<String> = emptySet()
    @Volatile
    private var activeSource: String? = null
    @Volatile
    private var lastError: String? = null
    @Volatile
    private var loading = false

    override fun init() {
        if (!inited.compareAndSet(false, true)) return
        TvBoxManager.init()
        activeSource = readActiveSource()
        scope.launch {
            AppDbProviders.get().bookSourceDao.flowAll().collect { rows ->
                existingRows = rows.asSequence()
                    .map { it.bookSourceUrl }
                    .filter { it.startsWith(TvBoxSourceMapper.SOURCE_URL_PREFIX) }
                    .toSet()
                emitState()
            }
        }
        emitState()
    }

    override suspend fun importConfig(url: String): Result<Unit> = withContext(IoDispatcher) {
        importLock.withLock {
            val trimmed = url.trim()
            loading = true
            emitState()
            val result = runCatching {
                check(trimmed.startsWith("http") || trimmed.startsWith("file")) {
                    "配置地址需为 http(s)/file 链接: $trimmed"
                }
                // 虚拟行的落库/回收由 TvBoxManager → TvBoxPluginSources 负责, 这里只拉+切+记来源
                val config = TvBoxManager.setConfigFromUrl(trimmed)
                check(config.sites.isNotEmpty()) { "配置未解析出站点: $trimmed" }
                addSource(trimmed)
                activeSource = trimmed
                lastError = null
                jarProbes = emptyMap()
            }.onFailure { lastError = describeFailure(it, trimmed) }
            loading = false
            emitState()
            if (result.isSuccess) probeJars()
            result
        }
    }

    override suspend fun refresh(): Result<Unit> {
        val url = activeSource
            ?: return Result.failure(IllegalStateException("未选定配置来源"))
        return importConfig(url)
    }

    override suspend fun removeSource(url: String): Result<Unit> = withContext(IoDispatcher) {
        importLock.withLock {
            writeSources(readSources().filterNot { it == url })
            if (url != activeSource) {
                emitState()
                return@withLock Result.success(Unit)
            }
            // 删除的是当前源: 写空白配置 (连带回收虚拟行) 后清内存,
            // 不能只 clear() —— 磁盘 config.json 还在, 下次启动 init 会把它重放回来
            val result = runCatching {
                TvBoxManager.setConfig("{}")
                TvBoxManager.clear()
            }.onFailure { lastError = describeFailure(it, url) }
            activeSource = null
            jarProbes = emptyMap()
            emitState()
            result
        }
    }

    override suspend fun activateSource(url: String): Result<Unit> {
        if (url == activeSource) return Result.success(Unit)
        return importConfig(url)
    }

    /**
     * 站点添加/移除: 落到虚拟书源行的存在与否 (移除 = 删行, 书源界面随之不再显示);
     * 是否参与搜索由书源界面的启用开关管理。仅支持站点可切 (非 csp_/CMS/JS 不落行)。
     */
    override suspend fun setSiteEnabled(siteKey: String, enabled: Boolean) = withContext(IoDispatcher) {
        siteOf(siteKey) ?: return@withContext
        TvBoxManager.setSiteAdded(siteKey, enabled)
        emitState()
    }

    override fun sourceUrlOf(siteKey: String): String? =
        siteOf(siteKey)?.let { TvBoxSourceMapper.siteUrlOf(it.key) }

    override fun probeJars() {
        val config = TvBoxManager.config ?: return
        val jars = config.jars()
        if (jars.isEmpty()) return
        jarProbes = jars.associateBy { it.spec }
            .mapValues { (_, item) -> item.copy(status = TvBoxJarStatus.LOADING, message = null) }
        emitState()
        for (jar in jars) {
            val site = config.sites.firstOrNull {
                kindOf(it) == TvBoxSiteKind.JAR && it.effectiveJar(config.spider) == jar.spec
            } ?: continue
            scope.launch {
                val outcome = runCatching {
                    withContext(IoDispatcher) { TvBoxManager.spiderFor(site, config.spider) }
                }
                jarProbes = jarProbes.toMutableMap().apply {
                    this[jar.spec] = TvBoxJarItem(
                        spec = jar.spec,
                        status = if (outcome.isSuccess) TvBoxJarStatus.READY else TvBoxJarStatus.FAILED,
                        message = outcome.exceptionOrNull()?.let { rootMessage(it) },
                        siteCount = jar.siteCount,
                    )
                }
                emitState()
            }
        }
    }

    override fun clearError() {
        lastError = null
        emitState()
    }

    // ===== 状态组装 =====

    /** 仅支持的类型 (有虚拟行) 才允许开关; 其余站点列出但不给开关。 */
    private fun siteOf(siteKey: String): TvBoxSite? =
        TvBoxManager.config?.sites?.firstOrNull { it.key == siteKey && hasVirtualRow(it) }

    /** 与 TvBoxPluginSources.sync 的准入口径一致: JAR/JS/CMS 三种才落虚拟行 (.py 站点排除)。 */
    private fun hasVirtualRow(site: TvBoxSite): Boolean =
        !site.isPySpider && (site.isJarSpider || site.isJsSpider || site.isCmsApi)

    /**
     * 站点形态 (决定 UI 的标记与开关可见性): .py 与 CMS 判定都必须在 HTTP 形态之上 ——
     * 相对 js/py api 解析后是 http URL, 会同时命中 isCmsApi, 但真实形态是 JS/Python Spider。
     */
    private fun kindOf(site: TvBoxSite): TvBoxSiteKind = when {
        site.isJarSpider -> TvBoxSiteKind.JAR
        site.isPySpider -> TvBoxSiteKind.OTHER
        site.isJsSpider -> TvBoxSiteKind.JS
        site.isCmsApi -> TvBoxSiteKind.CMS
        else -> TvBoxSiteKind.OTHER
    }

    /**
     * 重算并发布状态: 真源是 [TvBoxManager.config] (不可观测, 故任何写路径处理结束后
     * 都手动调一次), "已添加"由库中实际存在的虚拟行决定 (见 [existingRows])。
     */
    private fun emitState() {
        val config = TvBoxManager.config
        _state.value = TvBoxUiState(
            loading = loading,
            sources = readSources().ifEmpty { listOfNotNull(activeSource) },
            activeSource = if (config?.sites?.isNotEmpty() == true) activeSource else null,
            sites = config?.sites.orEmpty().map { site ->
                TvBoxSiteItem(
                    key = site.key,
                    name = site.name.ifBlank { site.key },
                    kind = kindOf(site),
                    api = site.api,
                    searchable = site.searchable,
                    filterable = site.filterable,
                    supported = hasVirtualRow(site),
                    added = TvBoxSourceMapper.siteUrlOf(site.key) in existingRows,
                    jar = site.effectiveJar(config!!.spider)
                        .takeIf { it.isNotBlank() && kindOf(site) == TvBoxSiteKind.JAR },
                )
            },
            jars = config?.jars()?.map(::probeOf) ?: emptyList(),
            error = lastError,
        )
    }

    /** jar 清单 (配置中去重的 jar 规格串 + 挂靠站点数); JS/CMS 站点无 jar, 不进本表。 */
    private fun TvBoxConfig.jars(): List<TvBoxJarItem> {
        val counts = LinkedHashMap<String, Int>()
        for (site in sites) {
            if (kindOf(site) != TvBoxSiteKind.JAR) continue
            val spec = site.effectiveJar(spider)
            if (spec.isBlank()) continue
            counts[spec] = (counts[spec] ?: 0) + 1
        }
        return counts.map { (spec, count) -> TvBoxJarItem(spec, TvBoxJarStatus.IDLE, null, count) }
    }

    private fun probeOf(jar: TvBoxJarItem): TvBoxJarItem =
        jarProbes[jar.spec] ?: jar

    private fun describeFailure(t: Throwable, url: String): String =
        "${t::class.simpleName}: ${rootMessage(t)} ($url)"

    /** 取异常链最深层消息 (TVBox/网络异常多为包装层 nesting, 根因才有诊断价值)。 */
    private fun rootMessage(t: Throwable): String =
        generateSequence(t) { it.cause }.last().message ?: t::class.simpleName.orEmpty()

    // ===== 平台自持的配置来源列表 (filesDir/tvbox/sources.json; 脏 JSON 退化为空表) =====

    private fun addSource(url: String) {
        val sources = readSources().toMutableList()
        sources.removeAll { it == url }
        sources.add(0, url)
        writeSources(sources)
    }

    private fun readSources(): List<String> {
        val file = sourcesFile()
        if (!file.isFile) return emptyList()
        return runCatching {
            GSON.decodeFromString(sourcesSerializer, file.readText())
        }.onFailure { AppLog.put("TVBox 配置来源列表读取失败", it) }
            .getOrNull().orEmpty()
    }

    private fun writeSources(sources: List<String>) {
        runCatching {
            val file = sourcesFile()
            file.parentFile?.mkdirs()
            file.writeText(GSON.encodeToString(sourcesSerializer, sources))
        }.onFailure { AppLog.put("TVBox 配置来源列表写入失败", it) }
    }

    private fun readActiveSource(): String? {
        val file = File(AppFilesDirs.get().filesDir, "tvbox/config_url.txt")
        if (!file.isFile) return null
        return runCatching { file.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()
    }

    private fun sourcesFile(): File = File(AppFilesDirs.get().filesDir, "tvbox/sources.json")

    private companion object {
        val sourcesSerializer = ListSerializer(String.serializer())
    }
}
