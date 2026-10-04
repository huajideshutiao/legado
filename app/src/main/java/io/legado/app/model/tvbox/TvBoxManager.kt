package io.legado.app.model.tvbox

import android.content.Context
import com.github.catvod.Init
import com.github.catvod.crawler.Spider
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.tvbox.TvBoxConfig
import io.legado.app.help.tvbox.TvBoxCmsSpider
import io.legado.app.help.tvbox.TvBoxJarLoader
import io.legado.app.help.tvbox.TvBoxJsSpiderLoader
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.model.webBook.VideoSourceDelegates
import kotlinx.coroutines.withContext
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

    fun init(context: Context) {
        appContext = context.applicationContext
        Init.set(appContext)
        registerDelegateRouter()
        val dir = File(context.filesDir, "tvbox")
        val file = File(dir, "config.json")
        if (file.isFile) {
            val baseUrl = File(dir, "config_url.txt").takeIf { it.isFile }?.readText()?.trim()
            runCatching { config = TvBoxConfig.parse(file.readText(), baseUrl) }
                .onFailure { AppLog.put("TVBox 配置重载失败", it) }
        }
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
        TvBoxPluginSources.sync(parsed)
        parsed
    }

    /** 从 URL 拉取配置 (走壳 OkHttp, 信任全部证书, 与 jar 内请求环境一致)。 */
    suspend fun setConfigFromUrl(url: String): TvBoxConfig = withContext(IoDispatcher) {
        val json = com.github.catvod.net.OkHttp.string(url)
        check(json.isNotBlank()) { "TVBox 配置拉取为空: $url" }
        setConfig(json, url)
    }

    fun siteOf(siteKey: String): TvBoxSite? = config?.sites?.firstOrNull { it.key == siteKey }

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
        TvBoxJarLoader.clear()
        jsLoader?.destroyAll()
        config = null
        // 磁盘配置一并清除: 仅清内存会下次启动 init 重放 (removeSource 等调用方不再需要先 setConfig 兜底)
        appContext?.let { ctx -> runCatching { File(File(ctx.filesDir, "tvbox"), "config.json").delete() } }
    }
}
