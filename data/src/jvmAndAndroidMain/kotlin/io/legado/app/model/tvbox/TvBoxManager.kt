package io.legado.app.model.tvbox

import com.github.catvod.Init
import com.github.catvod.crawler.Spider
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.tvbox.TvBoxConfig
import io.legado.app.help.tvbox.TvBoxCmsSpider
import io.legado.app.help.tvbox.TvBoxJarLoader
import io.legado.app.help.tvbox.TvBoxJsSpiderLoader
import io.legado.app.help.tvbox.TvBoxLocalProxy
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.help.tvbox.TvBoxPlatforms
import io.legado.app.model.webBook.PluginSourceDelegates
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TVBox 宿主编排: 配置拉取/持久化/解析, spider jar 装载入口, 虚拟书源行同步。
 *
 * TVBox 取数委派经 [registerDelegate] 挂进 [PluginSourceDelegates] (与漫画/视频插件委派并列)。
 *
 * 平台差异 (目录/上下文/类加载器/引导脚本) 经 [TvBoxPlatforms] 注入:
 * Android 端 App.onCreate 注册 AndroidTvBoxHostPlatform, 桌面端 DesktopCore
 * 注册 DesktopTvBoxHostPlatform; 未注册端 init() 报错。
 *
 * 目录面统一 [AppFilesDirs]: Android=Context.filesDir, 桌面={dataRoot}/files ——
 * filesDir/tvbox/ 布局两端一致 (config.json / config_url.txt / sites.json)。
 */
object TvBoxManager {

    /** JS spider 装载器 (站点 api 含 .js; 与 JAR 装载器互斥, 各自缓存)。 */
    private val jsLoader = TvBoxJsSpiderLoader()

    /** 磁盘配置装载与写入的串行锁: setConfig/clear/setSiteAdded 与启动重载互斥。 */
    private val configLock = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    private val loadStarted = AtomicBoolean(false)

    @Volatile
    private var loadJob: Job? = null

    @Volatile
    var config: TvBoxConfig? = null
        private set

    /**
     * "未添加"站点集合的内存镜像 (磁盘真源是 filesDir/tvbox/sites.json)。
     * [sync] 与 [setSiteAdded] 都要读它, 故不每次走磁盘 I/O。
     */
    @Volatile
    private var disabledSitesCache: Set<String> = emptySet()

    @Volatile
    private var inited = false

    /**
     * 注册面 (同步): 平台上下文 + 取数委派挂载 —— 任何取数入口的前置, 必须同步完成。
     * 磁盘读与本地服务 bind 属冷启动 IO 面, 交给后台协程; [awaitLoaded] 标记装载完成。
     */
    fun init() {
        val platform = TvBoxPlatforms.get()
        Init.set(platform.appContext)
        inited = true
        registerDelegate()
        if (loadStarted.compareAndSet(false, true)) {
            loadJob = scope.launch { configLock.withLock { loadPersistedConfig() } }
        }
    }

    /** 等待启动重载完成 (取数入口用它避免读到未被装载的中间态; 未调用 init 时立即返回)。 */
    suspend fun awaitLoaded() {
        loadJob?.join()
    }

    private suspend fun loadPersistedConfig() {
        disabledSitesCache = readDisabledSites()
        val dir = tvBoxDir()
        val file = File(dir, "config.json")
        if (!file.isFile) return
        // 先起本地服务再解析配置: 配置里的 file:///proxy:// 协议头在解析期就要换成
        // 真实端口地址 (com.github.catvod.Proxy.getPort), 顺序颠倒会拿到 -1
        if (!TvBoxLocalProxy.start { proxyDispatch(it) }) return
        val baseUrl = File(dir, "config_url.txt").takeIf { it.isFile }?.readText()?.trim()
        runCatching { config = TvBoxConfig.parse(file.readText(), baseUrl) }
            .onFailure { AppLog.put("TVBox 配置重载失败", it) }
    }

    /**
     * 注册 TVBox 取数委派: `tvbox://` 行由 [TvBoxSourceDelegateImpl] 处理。与漫画/视频
     * 插件委派并列注册 (各实现身份互斥, 顺序无关), 重复调用幂等。
     */
    fun registerDelegate() {
        PluginSourceDelegates.register(TvBoxSourceDelegateImpl)
    }

    /** 设置配置 (json 原文), 持久化并同步虚拟书源行; [baseUrl] 用于相对路径解析。 */
    suspend fun setConfig(json: String, baseUrl: String? = null): TvBoxConfig = withContext(IoDispatcher) {
        configLock.withLock {
            check(TvBoxLocalProxy.start { proxyDispatch(it) }) {
                "TVBox 本地代理启动失败, 端口 9978-9998 均不可用, 配置无法导入"
            }
            // 解析成功后才落盘: 非法 JSON 覆写磁盘会让下次冷启动整体静默失效
            val parsed = TvBoxConfig.parse(json, baseUrl)
            val dir = tvBoxDir()
            dir.mkdirs()
            writeTextAtomically(File(dir, "config.json"), json)
            writeTextAtomically(File(dir, "config_url.txt"), baseUrl.orEmpty())
            config = parsed
            TvBoxPluginSources.sync(parsed, disabledSitesCache)
            parsed
        }
    }

    /** 从 URL 拉取配置 (走壳 OkHttp, 信任全部证书, 与 jar 内请求环境一致)。 */
    suspend fun setConfigFromUrl(url: String): TvBoxConfig = withContext(IoDispatcher) {
        val json = com.github.catvod.net.OkHttp.string(url)
        check(json.isNotBlank()) { "TVBox 配置拉取为空: $url" }
        setConfig(json, url)
    }

    fun siteOf(siteKey: String): TvBoxSite? = config?.sites?.firstOrNull { it.key == siteKey }

    /** /proxy 请求分发 (FongMi BaseLoader.proxy 同语义): 带 siteKey 按站点 key 找 Spider
     *  实例 (jar/JS 统一), 否则交 jar 自带静态 Proxy 方法 (do 值由 jar 自定义, 如 "bili")。 */
    fun proxyDispatch(params: Map<String, String>): Array<Any?>? {
        params["siteKey"]?.let { key ->
            TvBoxJarLoader.spiderBySiteKey(key)?.let { return it.proxy(params) }
            val cfg = config ?: return null
            val site = cfg.sites.firstOrNull { it.key == key } ?: return null
            if (!site.isJsSpider) return null
            return jsLoader.getSpider(site, cfg.baseUrl).proxy(params)
        }
        return TvBoxJarLoader.proxyDispatch(params)
    }

    /**
     * 站点"已添加"开关 (TVBox 管理页的开关): 决定是否落虚拟 BookSource 行,
     * 即该站点是否在书源界面显示。是否参与搜索由书源界面的 enabled 开关管理,
     * 此处刻意不写它 (责任边界分离)。
     *
     * [added] = false 记入关闭集合并删除该虚拟行 (关闭 == 删行);
     * [added] = true 从集合移除并按当前配置重建该行 (行已存在则不重复插)。
     */
    suspend fun setSiteAdded(siteKey: String, added: Boolean) = withContext(IoDispatcher) {
        check(inited) { "TvBoxManager.init 未调用" }
        configLock.withLock {
            val disabled = disabledSitesCache.toMutableSet()
            if (added) disabled.remove(siteKey) else disabled.add(siteKey)
            writeDisabledSites(disabled)
            disabledSitesCache = disabled.toSet()
            val dao = AppDbProviders.get().bookSourceDao
            val url = TvBoxSourceMapper.siteUrlOf(siteKey)
            if (!added) {
                dao.deleteIn(listOf(url))
                return@withLock
            }
            val cfg = config ?: return@withLock
            val site = cfg.sites.firstOrNull { it.key == siteKey } ?: return@withLock
            if (dao.getBookSource(url) == null) {
                dao.insert(TvBoxPluginSources.buildVirtualSource(site, cfg.spider))
            }
        }
    }

    /** 解析站点并取 Spider (jar 缺失/站点非 csp_ 时抛出, 委派层收敛为取数错误)。 */
    suspend fun spiderFor(siteKey: String): Pair<TvBoxSite, Spider> = withContext(IoDispatcher) {
        awaitLoaded()
        val cfg = config ?: error("TVBox 配置未加载")
        val site = siteOf(siteKey) ?: error("TVBox 站点不存在: $siteKey")
        spiderFor(site, cfg.spider)
    }

    /** 指定站点取 Spider (jar 规格由站点声明回退全局 spider; 多配置并存时用此重载)。 */
    suspend fun spiderFor(site: TvBoxSite, globalSpider: String): Pair<TvBoxSite, Spider> =
        withContext(IoDispatcher) {
            // 判定次序与 FongMi BaseLoader.getSpider 一致: .py → .js → csp_ → null。
            // JS 必须排在 CMS 之前: api 解析后是绝对 http URL (如 .../cat/js/x.js),
            // 只看 "http 开头" 会把 JS 站点误判成 CMS 直连站。
            //
            // Python Spider: api 指向 .py (FongMi BaseLoader.isPy 同语义), 判定须在 .js/CMS 之前。
            // 本项目无 python 运行时, 如实报错 —— 决不能落进下方 CMS 分支: py 源码里
            // "import" 开头的文本会被 CMS 侧当 JSON 解析, 炸出与真实原因无关的 JSONException。
            if (site.isPySpider) {
                error("TVBox 站点为 Python spider (.py), 本轮不支持: ${site.name} (${site.api})")
            }
            // JS Spider: api 指向 .js 模块 (FongMi BaseLoader.isJs 同语义), 无需 jar
            if (site.isJsSpider) {
                val spider = jsLoader.getSpider(site, config?.baseUrl.orEmpty())
                spider.siteKey = site.key
                return@withContext site to spider
            }
            // type=0/1 苹果 CMS 直连站: api 即接口根 URL, 无 jar, 走宿主侧 CmsSpider
            if (site.isCmsApi) {
                val spider = TvBoxCmsSpider(site)
                spider.siteKey = site.key
                spider.init(TvBoxPlatforms.get().appContext, site.ext)
                return@withContext site to spider
            }
            val jar = site.effectiveJar(globalSpider)
            check(jar.isNotBlank()) { "TVBox 站点缺 spider jar: ${site.key}" }
            val spider = TvBoxJarLoader.getSpider(site, jar)
                ?: error("TVBox 站点非 JAR Spider (api=${site.api}), 本轮不支持")
            site to spider
        }

    suspend fun clear() {
        configLock.withLock {
            TvBoxLocalProxy.stop()
            TvBoxJarLoader.clear()
            jsLoader.destroyAll()
            config = null
            // 关闭集合与配置同生命周期: 站点 key 只在所属配置里有意义, 换配置后旧 key 会误关
            // 新配置的同名站点, 故随配置一并清 (磁盘 sites.json 同时删)
            disabledSitesCache = emptySet()
            runCatching { File(tvBoxDir(), "sites.json").delete() }
            // 磁盘配置一并清除: 仅清内存会下次启动 init 重放 (removeSource 等调用方不再需要先 setConfig 兜底)
            runCatching { File(tvBoxDir(), "config.json").delete() }
        }
    }

    /** 配置落盘走临时文件 + 重命名: 半写文件会让下次冷启动解析失败而整体失效。 */
    private fun writeTextAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text)
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ===== 站点"未添加"集合 (filesDir/tvbox/sites.json; 脏 JSON 退化为空集) =====

    private fun tvBoxDir(): File = File(AppFilesDirs.get().filesDir, "tvbox")

    private fun readDisabledSites(): Set<String> {
        val file = File(tvBoxDir(), "sites.json")
        if (!file.isFile) return emptySet()
        return runCatching {
            GSON.decodeFromString(disabledSitesSerializer, file.readText())
        }.onFailure { AppLog.put("TVBox 站点关闭集合读取失败", it) }
            .getOrNull().orEmpty().toSet()
    }

    private fun writeDisabledSites(keys: Set<String>) {
        runCatching {
            val file = File(tvBoxDir(), "sites.json")
            file.parentFile?.mkdirs()
            file.writeText(GSON.encodeToString(disabledSitesSerializer, keys.toList()))
        }.onFailure { AppLog.put("TVBox 站点关闭集合写入失败", it) }
    }

    private val disabledSitesSerializer = ListSerializer(String.serializer())
}
