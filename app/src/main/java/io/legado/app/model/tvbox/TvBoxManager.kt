package io.legado.app.model.tvbox

import android.content.Context
import com.github.catvod.Init
import com.github.catvod.crawler.Spider
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.tvbox.TvBoxConfig
import io.legado.app.help.tvbox.TvBoxCmsSpider
import io.legado.app.help.tvbox.TvBoxJarLoader
import io.legado.app.help.tvbox.TvBoxJsSpiderLoader
import io.legado.app.help.tvbox.TvBoxLocalProxy
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.model.webBook.VideoSourceDelegates
import io.legado.app.utils.GSON
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * TVBox 宿主编排: 配置拉取/持久化/解析, spider jar 装载入口, 虚拟书源行同步。
 *
 * 视频取数委派经 [registerDelegateRouter] 以组合方式挂进 [VideoSourceDelegates]
 * (该注册表为单实现覆盖语义, 故包裹既有委派而非替换, 漫画/视频插件链路零感知)。
 *
 * 本轮无 UI 入口: init() 仅在测试或后续接线处显式调用; 已持久化配置会随 init 重载。
 */
object TvBoxManager {

    private var appContext: Context? = null

    /** JS spider 装载器 (站点 api 含 .js; 与 JAR 装载器互斥, 各自缓存)。 */
    private val jsLoader: TvBoxJsSpiderLoader?
        get() = appContext?.let { TvBoxJsSpiderLoader(it) }

    @Volatile
    var config: TvBoxConfig? = null
        private set

    /**
     * "未添加"站点集合的内存镜像 (磁盘真源是 filesDir/tvbox/sites.json)。
     * [sync] 与 [setSiteAdded] 都要读它, 故不每次走磁盘 I/O。
     */
    @Volatile
    private var disabledSitesCache: Set<String> = emptySet()

    fun init(context: Context) {
        appContext = context.applicationContext
        Init.set(appContext)
        registerDelegateRouter()
        disabledSitesCache = readDisabledSites()
        val dir = File(context.filesDir, "tvbox")
        val file = File(dir, "config.json")
        if (file.isFile) {
            val baseUrl = File(dir, "config_url.txt").takeIf { it.isFile }?.readText()?.trim()
            runCatching { config = TvBoxConfig.parse(file.readText(), baseUrl) }
                .onFailure { AppLog.put("TVBox 配置重载失败", it) }
        }
        if (config != null) TvBoxLocalProxy.start { proxyDispatch(it) }
    }

    /**
     * 以组合方式注册视频取数委派: tvbox:// 行转 TvBoxSourceDelegateImpl,
     * 其余沿用注册时既有的委派实现 (通常为 AnimePlugin 的 VideoSourceDelegateImpl)。
     * 幂等; 须在 registerAndroidWebBookProviders 完成后调用。
     */
    fun registerDelegateRouter() {
        val current = VideoSourceDelegates.getOrNull()
        if (current is TvBoxVideoSourceDelegateRouter) return
        VideoSourceDelegates.register(TvBoxVideoSourceDelegateRouter(current, TvBoxSourceDelegateImpl))
    }

    /** 设置配置 (json 原文), 持久化并同步虚拟书源行; [baseUrl] 用于相对路径解析。 */
    suspend fun setConfig(json: String, baseUrl: String? = null): TvBoxConfig = withContext(IoDispatcher) {
        val context = appContext ?: error("TvBoxManager.init 未调用")
        val parsed = TvBoxConfig.parse(json, baseUrl)
        val dir = File(context.filesDir, "tvbox")
        dir.mkdirs()
        File(dir, "config.json").writeText(json)
        File(dir, "config_url.txt").writeText(baseUrl.orEmpty())
        config = parsed
        TvBoxPluginSources.sync(parsed, disabledSitesCache)
        TvBoxLocalProxy.start { proxyDispatch(it) }
        parsed
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
            return jsLoader?.getSpider(site, cfg.baseUrl)?.proxy(params)
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
        if (appContext == null) error("TvBoxManager.init 未调用")
        val disabled = disabledSitesCache.toMutableSet()
        if (added) disabled.remove(siteKey) else disabled.add(siteKey)
        writeDisabledSites(disabled)
        disabledSitesCache = disabled.toSet()
        val dao = AppDbProviders.get().bookSourceDao
        val url = TvBoxSourceMapper.siteUrlOf(siteKey)
        if (!added) {
            dao.deleteIn(listOf(url))
            return@withContext
        }
        val cfg = config ?: return@withContext
        val site = cfg.sites.firstOrNull { it.key == siteKey } ?: return@withContext
        if (dao.getBookSource(url) == null) {
            dao.insert(TvBoxPluginSources.buildVirtualSource(site, cfg.spider))
        }
    }

    /** 解析站点并取 Spider (jar 缺失/站点非 csp_ 时抛出, 委派层收敛为取数错误)。 */
    suspend fun spiderFor(siteKey: String): Pair<TvBoxSite, Spider> = withContext(IoDispatcher) {
        val cfg = config ?: error("TVBox 配置未加载")
        val site = siteOf(siteKey) ?: error("TVBox 站点不存在: $siteKey")
        spiderFor(site, cfg.spider)
    }

    /** 指定站点取 Spider (jar 规格由站点声明回退全局 spider; 多配置并存时用此重载)。 */
    suspend fun spiderFor(site: TvBoxSite, globalSpider: String): Pair<TvBoxSite, Spider> =
        withContext(IoDispatcher) {
            val context = appContext ?: error("TvBoxManager.init 未调用")
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
                val loader = jsLoader ?: error("TvBoxManager.init 未调用")
                val spider = loader.getSpider(site, config?.baseUrl.orEmpty())
                spider.siteKey = site.key
                return@withContext site to spider
            }
            // type=0/1 苹果 CMS 直连站: api 即接口根 URL, 无 jar, 走宿主侧 CmsSpider
            if (site.isCmsApi) {
                val spider = TvBoxCmsSpider(site)
                spider.siteKey = site.key
                spider.init(context, site.ext)
                return@withContext site to spider
            }
            val jar = site.effectiveJar(globalSpider)
            check(jar.isNotBlank()) { "TVBox 站点缺 spider jar: ${site.key}" }
            val spider = TvBoxJarLoader.getSpider(context, site, jar)
                ?: error("TVBox 站点非 JAR Spider (api=${site.api}), 本轮不支持")
            site to spider
        }

    fun clear() {
        TvBoxLocalProxy.stop()
        TvBoxJarLoader.clear()
        jsLoader?.destroyAll()
        config = null
        // 关闭集合与配置同生命周期: 站点 key 只在所属配置里有意义, 换配置后旧 key 会误关
        // 新配置的同名站点, 故随配置一并清 (磁盘 sites.json 同时删)
        disabledSitesCache = emptySet()
        disabledSitesFile()?.let { runCatching { it.delete() } }
        // 磁盘配置一并清除: 仅清内存会下次启动 init 重放 (removeSource 等调用方不再需要先 setConfig 兜底)
        appContext?.let { ctx -> runCatching { File(File(ctx.filesDir, "tvbox"), "config.json").delete() } }
    }

    // ===== 站点"未添加"集合 (filesDir/tvbox/sites.json; 脏 JSON 退化为空集) =====

    private fun readDisabledSites(): Set<String> {
        val file = disabledSitesFile() ?: return emptySet()
        if (!file.isFile) return emptySet()
        return runCatching {
            GSON.decodeFromString(disabledSitesSerializer, file.readText())
        }.onFailure { AppLog.put("TVBox 站点关闭集合读取失败", it) }
            .getOrNull().orEmpty().toSet()
    }

    private fun writeDisabledSites(keys: Set<String>) {
        val file = disabledSitesFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(GSON.encodeToString(disabledSitesSerializer, keys.toList()))
        }.onFailure { AppLog.put("TVBox 站点关闭集合写入失败", it) }
    }

    private fun disabledSitesFile(): File? =
        appContext?.let { File(it.filesDir, "tvbox/sites.json") }

    private val disabledSitesSerializer = ListSerializer(String.serializer())
}
