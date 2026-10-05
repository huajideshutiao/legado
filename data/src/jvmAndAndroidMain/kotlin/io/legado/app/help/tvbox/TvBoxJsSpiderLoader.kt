package io.legado.app.help.tvbox

import android.util.Base64
import com.github.catvod.Init
import com.github.catvod.crawler.Spider
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Crypto
import com.script.quickjs.QuickJsContext
import com.script.quickjs.QuickJsEngine
import com.script.quickjs.ScriptBindings
import io.legado.app.constant.AppLog
import io.legado.app.help.file.AppFilesDirs
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TVBox JS spider 装载与运行时 (type=1/type=3 且 api 指向 .js 的站点)。
 *
 * 站点判定沿用 FongMi BaseLoader.isJs 语义 (api 含 ".js"), 与 JAR 站点 (csp_) 互斥:
 *   FongMi/TV fongmi 分支 quickjs/crawler/Spider.java
 *
 * 运行时搭建顺序 (对齐 FongMi quickjs/crawler/Spider 的 createCtx/createFun/createObj):
 * 1. [QuickJsEngine.getRuntimeScope] 起独立 QuickJS scope (站点级常驻, 保存 JS 侧 spider 状态);
 * 2. 注入 __M (ESM→CJS 装载器) + 宿主门面 + 站点上下文, 建立模块取源回调;
 * 3. require(站点 api) 取 spider 模块, 按 `__jsEvalReturn` / `default` 两步解析导出面;
 * 4. init(cfg) 回调 (cfg = {stype, skey, ext}), 之后按 Spider 四路签名转发调用。
 *
 * 线程模型: QuickJS ctx 线程独占。当前无站点级串行化, 调用方若可能跨线程并发
 * 访问同一站点 ctx, 需自行保证不并发 (已知缺口)。
 *
 * 平台面: 目录走 [AppFilesDirs] (Android=filesDir/cacheDir, 桌面=~/.legado 等),
 * 引导脚本经 [TvBoxPlatforms] 注入 (Android=assets, 桌面=classpath 资源)。
 */
class TvBoxJsSpiderLoader {

    private val spiders = ConcurrentHashMap<String, TvBoxJsSpider>()

    /** 仅预载 (下载 spider + 依赖模块), 不实例化回调。 */
    /**
     * 取 (并缓存) 站点 Spider。装载/初始化异常原样抛出, 由委派层收敛为取数错误。
     * [baseUrl] 为配置/站点 api 的解析基准 ("./lib/x.js" 相对它解析)。
     */
    fun getSpider(site: TvBoxSite, baseUrl: String): Spider {
        Init.set(TvBoxPlatforms.get().appContext)
        val cacheKey = site.key + "@" + Crypto.md5(site.api + "|" + baseUrl)
        spiders[cacheKey]?.let { return it }
        synchronized(cacheKey.intern()) {
            spiders[cacheKey]?.let { return it }
            val spider = TvBoxJsSpider(site, baseUrl)
            spider.init(TvBoxPlatforms.get().appContext, site.ext)
            spiders[cacheKey] = spider
            return spider
        }
    }

    fun destroyAll() {
        spiders.values.forEach { runCatching { it.destroy() } }
        spiders.clear()
    }

    companion object {

        /** 随包引导脚本 (ESM→CJS 装载器)。 */
        const val MODULE_LOADER_JS = "tvbox/TvBoxJsModuleLoader.js"

        /** 随包宿主门面脚本 (req/joinUrl/加密/local/console 等全局面)。 */
        const val HOST_API_JS = "tvbox/TvBoxJsApi.js"

        /**
         * 随包 drpy2 宿主契约脚本 (pdfh/pdfa/pd)。
         * drpy2.min.js 顶层 `const defaultParser={pdfh:pdfh,pdfa:pdfa,pd:pd}` 在模块
         * 求值期即读这三全局, 必须在 spider 模块装载前注入 (生态契约, 见资产头注)。
         */
        const val DRPY_PARSER_JS = "tvbox/TvBoxDrpyParser.js"

        /**
         * 宿主引导资源中缺失的 drpy 系依赖库的按需下载来源 (基础 URL)。
         *
         * 宿主只带引导脚本与门面; 生态 JS spider 常见的
         * `assets://js/lib/{cheerio.min.js,crypto-js.js,gbk.js,http.js,spider.js,…}`
         * 不随包分发 (理由见 [TvBoxJsSpider.fetchSource] KDoc)。首次用到时按
         * `REMOTE_ASSET_BASE + <相对路径>` 下载并落缓存目录。
         *
         * 来源依据 (实测可达, FongMi/TV `fongmi` 分支 quickjs 模块):
         * `quickjs/src/main/assets/js/lib/` 下的 cat.js / cheerio.min.js / crypto-js.js /
         * gbk.js / http.js / similarity.js / spider.js 与宿主 `assets://js/lib/` 下同名同路径。
         *
         * 直连 GitHub 在国内网络常不可达, 故按序尝试 [assetUrlsOf] 里的镜像
         * (ghproxy / jsDelivr, 实测与源站同字节); 全部失败才如实报错。
         *
         * 可见性为 internal (非 private): 同文件的 [TvBoxJsSpider] 取源时也要拼 URL。
         */
        internal const val REMOTE_ASSET_REPO =
            "FongMi/TV/fongmi/quickjs/src/main/assets/"

        /**
         * jsDelivr gh 路径前缀 (与 [REMOTE_ASSET_REPO] 同源, 路径形态不同)。
         *
         * jsDelivr 的 gh 形式为 `<owner>/<repo>@<ref>/<rest>`, 故 `FongMi/TV/fongmi/<rest>`
         * 需把第三个斜杠换成 `@`。
         * 可见性为 internal (非 private), 理由同 [REMOTE_ASSET_REPO]。
         */
        internal val REMOTE_ASSET_JSDELIVR_REPO: String = run {
            val (owner, rest) = REMOTE_ASSET_REPO.split('/', limit = 2)
            val (name, tail) = rest.split('/', limit = 2)
            val (ref, path) = tail.split('/', limit = 2)
            "$owner/$name@$ref/$path"
        }
    }
}

/**
 * JS spider 运行时实例: 一个站点一个 QuickJS scope + 一个单线程 executor。
 *
 * 调用转发 (FongMi quickjs/crawler/Spider 同名映射):
 * homeContent→home, homeVideoContent→homeVod, categoryContent→category,
 * detailContent→detail, searchContent→search, playerContent→play,
 * liveContent→live, manualVideoCheck→sniffer, isVideoFormat→isVideo,
 * action→action, proxy→proxy, destroy→destroy。
 */
class TvBoxJsSpider internal constructor(
    private val site: TvBoxSite,
    private val baseUrl: String,
) : Spider() {

    private val scope: QuickJsContext
    private val bridge: TvBoxJsBridge
    private val spiderExpr: String

    /** 导出面是否为 cat 系 (`__jsEvalReturn`): 决定 [init] 的入参形态, 见 [init]。 */
    private var isCat = false
    private val methods = HashMap<String, Boolean>()

    init {
        val fetcher = TvBoxJsSourceFetcher { name, base -> fetchSource(name, base) }
        bridge = TvBoxJsBridge(fetcher, site.key, site.type)
        val bindings = ScriptBindings().apply {
            dangerousApi = true
            put(HOST_KEY_HOST, bridge)
            put("__hostBaseUrl", baseUrl)
            put("__hostSiteKey", site.key)
            put("__hostSiteType", site.type)
        }
        scope = QuickJsEngine.getRuntimeScope(bindings)
        try {
            evaluate(readHostAsset(TvBoxJsSpiderLoader.MODULE_LOADER_JS))
            evaluate(readHostAsset(TvBoxJsSpiderLoader.HOST_API_JS))
            evaluate(readHostAsset(TvBoxJsSpiderLoader.DRPY_PARSER_JS))
            evaluate("__M.setHost(__hostBridge__); __M.setBase(__hostBaseUrl);")
            spiderExpr = resolveSpiderExpr(site.api)
        } catch (t: Throwable) {
            runCatching { scope.close() }
            throw t
        }
    }

    override fun init(context: android.content.Context?, extend: String?) {
        // 入参形态按导出面分流 (FongMi quickjs Spider.getExt 同语义):
        // - cat 系 (__jsEvalReturn) 收 {stype,skey,ext} 包装对象;
        // - drpy2 系收 ext 原文 —— 它把入参当规则体 (`rule = ext`), 收包装对象会得到
        //   空 host 而取不到任何数据 (drpy2 站点实测卡在无种子)。
        if (!isCat) {
            callRaw("init", if (!extend.isNullOrEmpty() && isJsonObject(extend)) JSONObject(extend) else extend)
            return
        }
        val cfg = JSONObject()
            .put("stype", site.type)
            .put("skey", site.key)
        cfg.put("ext", if (!extend.isNullOrEmpty() && isJsonObject(extend)) JSONObject(extend) else extend)
        callRaw("init", cfg)
    }

    override fun homeContent(filter: Boolean): String = call("home", filter) ?: ""

    override fun homeVideoContent(): String = call("homeVod") ?: ""

    override fun categoryContent(
        tid: String?,
        pg: String?,
        filter: Boolean,
        extend: HashMap<String, String>?,
    ): String = call("category", tid, pg, filter, JSONObject(extend ?: HashMap<String, String>())) ?: ""

    override fun detailContent(ids: List<String>?): String =
        call("detail", ids.orEmpty().firstOrNull().orEmpty()) ?: ""

    override fun searchContent(key: String?, quick: Boolean): String = call("search", key, quick) ?: ""

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String =
        call("search", key, quick, pg) ?: ""

    override fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String =
        call("play", flag, id, JSONArray(vipFlags ?: emptyList<String>())) ?: ""

    override fun liveContent(url: String?): String = call("live", url) ?: ""

    override fun manualVideoCheck(): Boolean = callRaw("sniffer").isTrue()

    override fun isVideoFormat(url: String?): Boolean = callRaw("isVideo", url).isTrue()

    override fun action(action: String?): String? = call("action", action)

    /**
     * proxy 面 (FongMi proxy1): JS 返回 [code, type, content, headers?, base64?]。
     * 本轮只落宿主侧解析, 返回 [code, type, stream, headers] 供代理/播放链路使用。
     */
    override fun proxy(params: Map<String, String>?): Array<Any?>? {
        val raw = callRaw("proxy", JSONObject(params as Map<*, *>)) ?: return null
        val array = runCatching { JSONArray(raw.toString()) }.getOrNull() ?: return null
        val code = array.optInt(0, 200)
        val type = array.optString(1, "application/octet-stream")
        val content = array.opt(2)?.toString().orEmpty()
        val base64 = array.length() > 4 && array.optInt(4) == 1
        val bytes = if (base64) {
            Base64.decode(content, Base64.DEFAULT)
        } else {
            content.toByteArray()
        }
        val headers = if (array.length() > 3) {
            runCatching { JSONObject(array.optString(3)) }.getOrNull()?.let { obj ->
                LinkedHashMap<String, String>().apply {
                    for (key in obj.keys()) put(key, obj.opt(key)?.toString().orEmpty())
                }
            }
        } else null
        return arrayOf<Any?>(code, type, java.io.ByteArrayInputStream(bytes), headers)
    }

    override fun destroy() {
        runCatching { call("destroy") }
        runCatching { scope.close() }
    }

    // ============ JS 调用桥 ============

    /** 调 JS 方法并把返回值收敛为 Spider 契约的 String (对象/数组 → JSON)。 */
    private fun call(name: String, vararg args: Any?): String? = stringify(callRaw(name, *args))

    /**
     * 原始调用: 拼 `spider.name(args)` 表达式在站点 scope 上 eval。
     * 未声明的方法返回 null (FongMi Async.run 同语义: getJSFunction 为空即空结果)。
     * 返回值为对象/数组时先 JSON.stringify, 让宿主侧拿到可解析的 JSON 文本
     * (QuickJS 的 JS object 经 JNI 回传形态不定, 字符串化是唯一稳定面)。
     */
    private fun callRaw(name: String, vararg args: Any?): Any? {
        if (methods[name] == false) return null
        if (!hasMethod(name)) {
            methods[name] = false
            return null
        }
        methods[name] = true
        val callExpr = "$spiderExpr.${name}(" + args.joinToString(",") { jsLiteral(it) } + ")"
        val expr =
            "(function(){var __r = $callExpr; " +
                "return (__r !== null && typeof __r === 'object') ? JSON.stringify(__r) : __r;})()"
        return try {
            evaluate(expr)
        } catch (t: Throwable) {
            AppLog.put("TVBox JS ${site.api} $name 调用失败", t)
            throw t
        }
    }

    private fun hasMethod(name: String): Boolean = evaluate("(typeof $spiderExpr.$name === 'function')").isTrue()

    private fun evaluate(js: String): Any? = QuickJsEngine.eval(js, scope, null)

    /** JS 字面量: 字符串转义 / 布尔数字原样 / JSONObject|JSONArray 内联 / 其余转字符串。 */
    private fun jsLiteral(value: Any?): String = when (value) {
        null -> "null"
        is String -> jsString(value)
        is Boolean -> value.toString()
        is Number -> value.toString()
        is JSONObject, is JSONArray -> value.toString()
        else -> jsString(value.toString())
    }

    private fun jsString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

    private fun stringify(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        else -> value.toString()
    }

    private fun isJsonObject(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("{") && runCatching { JSONObject(trimmed) }.isSuccess
    }

    // ============ 模块装载 ============

    /**
     * 解析 spider 导出面: FongMi spider.js 同语义 ——
     * `__jsEvalReturn()` 优先 (cat 系), 否则 `default` (drpy2 系, 函数则先调用)。
     */
    private fun resolveSpiderExpr(api: String): String {
        val module = "__M.require(" + jsString(api) + ")"
        evaluate("globalThis.__jsSpiderModule__ = $module;")
        val hasEval = evaluate("(typeof __jsSpiderModule__.__jsEvalReturn === 'function')")
        if (hasEval.isTrue()) {
            isCat = true
            evaluate("globalThis.__JS_SPIDER__ = __jsSpiderModule__.__jsEvalReturn();")
            // cat 系约定: __jsEvalReturn 存在时把 req 重新绑到 http (spider.js 同款)
            evaluate("if (typeof http === 'function') globalThis.req = http;")
        } else {
            evaluate(
                "globalThis.__JS_SPIDER__ = (typeof __jsSpiderModule__.default === 'function')" +
                    " ? __jsSpiderModule__.default() : __jsSpiderModule__.default;"
            )
            evaluate("if (!globalThis.__JS_SPIDER__) globalThis.__JS_SPIDER__ = __jsSpiderModule__;")
        }
        val ok = evaluate("(!!globalThis.__JS_SPIDER__ && typeof globalThis.__JS_SPIDER__ === 'object')")
        check(ok.isTrue()) {
            "TVBox JS spider 未导出可用对象 (__jsEvalReturn/default): $api"
        }
        return "globalThis.__JS_SPIDER__"
    }

    /**
     * 模块取源 (阻塞):
     * - `assets://<path>` → **宿主自带引导资源** (FongMi/TV 语义: `assets://js/lib/x.js`
     *   即随包 `js/lib/x.js`)。宿主未随包时按需下载, 见 [fetchAsset];
     * - http(s) → 壳 OkHttp (信任全部证书, 与 jar 内请求环境一致), 落缓存目录 tvbox/js;
     * - lib/x.js (裸路径) → 相对配置基准解析 (FongMi Module/UriUtil 同语义);
     * - file:// 或裸路径 → 本地文件。
     */
    private fun fetchSource(name: String, base: String): String {
        if (name.isBlank()) return ""
        val raw = name.trim()
        // assets:// 是宿主自带资源的地址, 不经 base 解析也不走配置仓镜像路径
        if (raw.startsWith(ASSETS_SCHEME)) return fetchAsset(raw.removePrefix(ASSETS_SCHEME))
        val resolved = resolveUrl(raw, base.ifBlank { baseUrl })
        if (resolved.isBlank()) return ""
        if (!resolved.startsWith("http")) {
            return TvBoxJsBridge.readLocalFile(resolved).orEmpty()
        }
        val cached = cachedFile(resolved)
        cachedJs(cached)?.let { return it }
        // 按 HTTP 状态判定成败: 404 正文 ("404: Not Found") 是纯文本, 形态判据拦不住,
        // 当 JS 缓存后会在 eval 期炸出与真实原因无关的语法错 (drpy2 系站点实测)。
        val body = OkHttp.client().newCall(Request.Builder().url(resolved).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return ""
                resp.body.string()
            }
        // 200 也可能是防盗链/登录页 HTML; 拿到的不是 JS 就当缺失,
        // 否则下游会抛 "Unexpected token '<'" 这类与真实原因无关的装载错误。
        if (!looksLikeJs(body)) return ""
        runCatching {
            cached.parentFile?.mkdirs()
            cached.writeText(body)
        }
        return body
    }

    /**
     * 宿主引导资源取源: 随包资源优先, 缺失时按 [assetUrlsOf] 逐个镜像
     * 按需下载并缓存。
     *
     * 为什么不随包: drpy/cat 系依赖库 (cheerio.min.js / crypto-js.js / gbk.js 等) 只在
     * 使用 TVBox JS 源的机器上才需要, 打进安装包会给所有用户白增体积; 故只在首次真正用到
     * 某个模块时下载, 落缓存目录复用 (与 http 模块同一 [cachedFile] 缓存机制)。
     *
     * 镜像逐个尝试的理由: 直连 GitHub 在国内网络常不可达; 缓存 key 取实际命中的 URL,
     * 不同镜像各存一份互不干扰。
     *
     * 全部镜像都拿不到时返回空串: 下游 `require` 会抛 `tvbox js module not found: <path>`
     * 使装载失败可观测 (不静默返回空模块)。
     */
    private fun fetchAsset(path: String): String {
        if (path.isBlank()) return ""
        runCatching { readHostAsset(path) }.getOrNull()?.let { return it }
        var lastError: String? = null
        for (url in assetUrlsOf(path)) {
            val cached = cachedFile(url)
            cachedJs(cached)?.let { return it }
            val body = runCatching {
                OkHttp.client().newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) "" else resp.body.string()
                }
            }.onFailure { lastError = "${it::class.simpleName}: ${it.message} ($url)" }
                .getOrDefault("")
            if (!looksLikeJs(body)) continue
            runCatching {
                cached.parentFile?.mkdirs()
                cached.writeText(body)
            }
            return body
        }
        AppLog.put(
            "TVBox JS 依赖模块获取失败 (宿主资源无且全部镜像不可用): $path" +
                (lastError?.let { "\n$it" } ?: "")
        )
        return ""
    }

    /**
     * 同一相对路径的下载地址候选 (按序尝试, 首个成功即用)。
     *
     * 顺序: 直连 GitHub raw → ghproxy 镜像 → jsDelivr CDN。三者内容一致
     * (实测 cheerio.min.js 均 356592 字节), 仅可达性不同。
     */
    private fun assetUrlsOf(path: String): List<String> = listOf(
        "https://raw.githubusercontent.com/${TvBoxJsSpiderLoader.REMOTE_ASSET_REPO}$path",
        "https://ghproxy.net/https://raw.githubusercontent.com/${TvBoxJsSpiderLoader.REMOTE_ASSET_REPO}$path",
        "https://cdn.jsdelivr.net/gh/${TvBoxJsSpiderLoader.REMOTE_ASSET_JSDELIVR_REPO}$path",
    )

    /**
     * 缓存命中取源: 命中且内容仍像 JS 才用, 否则当未命中重下。
     *
     * 外部约束 (勿改): 旧版本把 404 正文 ("404: Not Found", 14 字节) 当 JS 缓存进
     * `cacheDir/tvbox/js`, 升级后仅改下载判定不足以自愈 —— 毒缓存会一直命中。
     * 故读取侧也要过同一形态判据, 命中脏文件即丢弃重下。
     */
    private fun cachedJs(file: File): String? {
        if (!file.isFile || file.length() <= 0) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return text.takeIf { looksLikeJs(it) }
    }

    private fun cachedFile(url: String): File =
        File(AppFilesDirs.get().cacheDir, "tvbox/js/" + Crypto.md5(url) + ".js")

    /** 粗判模块内容是 JS 而非 HTML 错误页/空响应。 */
    private fun looksLikeJs(body: String): Boolean {
        if (body.isBlank()) return false
        val head = body.trimStart().take(200)
        if (head.startsWith("<!DOCTYPE", true) || head.startsWith("<html", true)) return false
        return true
    }

    /**
     * 裸路径/相对路径 → 可下载 URL: 按配置基准做 URI 解析 (FongMi Module/UriUtil 同语义)。
     * `assets://` 已在 [fetchSource] 分流到宿主资源, 不经此路径。
     */
    private fun resolveUrl(name: String, base: String): String {
        val raw = name.trim()
        if (raw.startsWith("http")) return raw
        if (raw.startsWith("file://")) return raw
        if (base.isBlank()) return raw
        return runCatching { java.net.URI(base).resolve(raw).toString() }.getOrDefault(raw)
    }

    companion object {
        /** 宿主对象在 JS 侧的注入键 (与 TvBoxJsApi.js 的 __M.setHost 参数一致)。 */
        internal const val HOST_KEY_HOST = "__hostBridge__"

        /** 宿主资源协议头 (FongMi/TV `assets://` 语义: 随包资源, 非远端)。 */
        private const val ASSETS_SCHEME = "assets://"
    }
}

/** 读宿主自带引导脚本 (Android=assets, 桌面=classpath 资源); 缺失时抛错, 避免静默空运行时。 */
internal fun readHostAsset(assetPath: String): String {
    val text = TvBoxPlatforms.get().readHostAsset(assetPath)
    check(!text.isNullOrBlank()) { "TVBox JS 引导脚本缺失: $assetPath" }
    return text
}

/** JS 真值判定: QuickJS 经 JNI 回传可能是 Boolean 也可能是字符串 "true"。 */
private fun Any?.isTrue(): Boolean = when (this) {
    is Boolean -> this
    is Number -> this.toInt() != 0
    is String -> equals("true", true)
    else -> false
}
