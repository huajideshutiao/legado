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
import io.legado.app.utils.NetworkUtils
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

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
 * 线程模型: QuickJS ctx 线程独占, 由 [TvBoxJsSpider] 的站点级单线程执行器保证 ——
 * 同站点的并发调用 (取播与嗅探) 在 JS 侧串行化, 调用方无需自行避并发。
 *
 * 平台面: 目录走 [AppFilesDirs] (Android=filesDir/cacheDir, 桌面=~/.legado 等),
 * 引导脚本经 [TvBoxHostAssetProviders] 注入 (composeResources 单一数据源, 两端无平台副本)。
 */
class TvBoxJsSpiderLoader {

    private val spiders = ConcurrentHashMap<String, TvBoxJsSpider>()

    /** 按站点 key 的实例化锁 (与 TvBoxJarLoader 同款): 不用 key.intern() (任意 key 驻留常量池)。 */
    private val locks = ConcurrentHashMap<String, Any>()

    /** 仅预载 (下载 spider + 依赖模块), 不实例化回调。 */
    /**
     * 取 (并缓存) 站点 Spider。装载/初始化异常原样抛出, 由委派层收敛为取数错误。
     * [baseUrl] 为配置/站点 api 的解析基准 ("./lib/x.js" 相对它解析)。
     */
    fun getSpider(site: TvBoxSite, baseUrl: String): Spider {
        Init.set(TvBoxPlatforms.get().appContext)
        val cacheKey = site.key + "@" + Crypto.md5(site.api + "|" + baseUrl)
        spiders[cacheKey]?.let { return it }
        val lock = locks.computeIfAbsent(cacheKey) { Any() }
        synchronized(lock) {
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
        locks.clear()
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
         * 来源依据 (实测可达, FongMi/TV `fongmi` 分支 quickjs 模块, 已钉到 [REMOTE_ASSET_COMMIT] 提交):
         * `quickjs/src/main/assets/js/lib/` 下的 cat.js / cheerio.min.js / crypto-js.js /
         * gbk.js / http.js / similarity.js / spider.js 与宿主 `assets://js/lib/` 下同名同路径。
         *
         * 直连 GitHub 在国内网络常不可达, 故按序尝试 [assetUrlsOf] 里的镜像
         * (ghproxy / jsDelivr, 实测与源站同字节); 内容 md5 不符即换下一个镜像, 全部失败才如实报错。
         *
         * 可见性为 internal (非 private): 同文件的 [TvBoxJsSpider] 取源时也要拼 URL。
         */
        internal const val REMOTE_ASSET_COMMIT = "c616c0aa3613e87529791587a9f71b78c278c991"

        internal const val REMOTE_ASSET_REPO =
            "FongMi/TV/$REMOTE_ASSET_COMMIT/quickjs/src/main/assets/"

        /**
         * 可从镜像下载的依赖模块清单 (相对 assets 根 → 钉住的提交下的内容 md5)。
         * 表外路径一律拒绝下载: 无校验基准的远程内容不得进 eval。
         */
        internal val ASSET_MD5: Map<String, String> = mapOf(
            "js/lib/cat.js" to "ce5c0ecf92f7507c3b65c1ecbc167f95",
            "js/lib/cheerio.min.js" to "f4f72962fb5d6e15d4e32b39c02056f0",
            "js/lib/crypto-js.js" to "0290d675a485e70ff76879e17ecec46a",
            "js/lib/gbk.js" to "d5a05799eeeceb81cadd77e80f2807c8",
            "js/lib/http.js" to "b129d2ae9828694104b7b575588903e5",
            "js/lib/similarity.js" to "e5f3fe2ba5423aa0598508e2f6968dd4",
            "js/lib/spider.js" to "36ac57a8f42dea81bb3428b850e3d2da",
        )

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

    /**
     * 站点级单线程执行器: QuickJS ctx 线程独占 (生态契约 = 单站点单线程),
     * 同站点的取播与嗅探并发时不会再同时进 native ctx (SIGSEGV / JS 状态腐坏)。
     */
    private val jsThreadRef = AtomicReference<Thread>()
    private val jsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tvbox-js-${site.key}").also {
            it.isDaemon = true
            jsThreadRef.set(it)
        }
    }

    private lateinit var scope: QuickJsContext
    private lateinit var bridge: TvBoxJsBridge
    private lateinit var spiderExpr: String

    /** 导出面是否为 cat 系 (`__jsEvalReturn`): 决定 [init] 的入参形态, 见 [init]。 */
    private var isCat = false
    private val methods = ConcurrentHashMap<String, Boolean>()

    init {
        runSerial {
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
    }

    /**
     * 串行执行 JS 块: 已在 JS 线程时直接跑 (重入, 不会自锁), 否则提交并等待结果。
     * 异常拆壳原样抛出, 调用方的 runCatching 语义不变。
     */
    private fun <T> runSerial(block: () -> T): T {
        if (Thread.currentThread() === jsThreadRef.get()) return block()
        val future = jsExecutor.submit(Callable { block() })
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    override fun init(context: android.content.Context?, extend: String?) {
        runSerial {
            // 入参形态按导出面分流 (FongMi quickjs Spider.getExt 同语义):
            // - cat 系 (__jsEvalReturn) 收 {stype,skey,ext} 包装对象;
            // - drpy2 系收 ext 原文 —— 它把入参当规则体 (`rule = ext`), 收包装对象会得到
            //   空 host 而取不到任何数据 (drpy2 站点实测卡在无种子)。
            val rawExt: Any? =
                if (!extend.isNullOrEmpty() && isJsonObject(extend)) JSONObject(extend) else extend
            if (!isCat) {
                callRaw("init", rawExt)
            } else {
                val cfg = JSONObject()
                    .put("stype", site.type)
                    .put("skey", site.key)
                cfg.put("ext", rawExt)
                callRaw("init", cfg)
            }
            Unit
        }
    }

    override fun homeContent(filter: Boolean): String = runSerial { call("home", filter) ?: "" }

    override fun homeVideoContent(): String = runSerial { call("homeVod") ?: "" }

    override fun categoryContent(
        tid: String?,
        pg: String?,
        filter: Boolean,
        extend: HashMap<String, String>?,
    ): String = runSerial {
        call("category", tid, pg, filter, JSONObject(extend ?: HashMap<String, String>())) ?: ""
    }

    override fun detailContent(ids: List<String>?): String = runSerial {
        call("detail", ids.orEmpty().firstOrNull().orEmpty()) ?: ""
    }

    override fun searchContent(key: String?, quick: Boolean): String =
        runSerial { call("search", key, quick) ?: "" }

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String =
        runSerial { call("search", key, quick, pg) ?: "" }

    override fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String =
        runSerial { call("play", flag, id, JSONArray(vipFlags ?: emptyList<String>())) ?: "" }

    override fun liveContent(url: String?): String = runSerial { call("live", url) ?: "" }

    override fun manualVideoCheck(): Boolean = runSerial { callRaw("sniffer").isTrue() }

    override fun isVideoFormat(url: String?): Boolean = runSerial { callRaw("isVideo", url).isTrue() }

    override fun action(action: String?): String? = runSerial { call("action", action) }

    /**
     * proxy 面 (FongMi proxy1): JS 返回 [code, type, content, headers?, base64?]。
     * 本轮只落宿主侧解析, 返回 [code, type, stream, headers] 供代理/播放链路使用。
     */
    override fun proxy(params: Map<String, String>?): Array<Any?>? = runSerial {
        val args: Map<*, *> = params ?: emptyMap<String, String>()
        val raw = callRaw("proxy", JSONObject(args))
        val array = raw?.let { runCatching { JSONArray(it.toString()) }.getOrNull() }
        if (array == null) {
            null
        } else {
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
            arrayOf<Any?>(code, type, java.io.ByteArrayInputStream(bytes), headers)
        }
    }

    override fun destroy() {
        runCatching { runSerial { call("destroy") } }
        runCatching { runSerial { scope.close() } }
        jsExecutor.shutdown()
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
     *   即随包 `js/lib/x.js`; 本仓库宿主资源唯一落点为 composeResources
     *   `files/tvbox/`, 经 [TvBoxHostAssetProviders] 读)。宿主未随包时按需下载, 见 [fetchAsset];
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
        val expectedMd5 = TvBoxJsSpiderLoader.ASSET_MD5[path]
        if (expectedMd5 == null) {
            AppLog.put("TVBox JS 依赖模块不在信任清单内, 拒绝远程下载: $path")
            return ""
        }
        var lastError: String? = null
        for (url in assetUrlsOf(path)) {
            val cached = cachedFile(url)
            cachedVerifiedJs(cached, expectedMd5)?.let { return it }
            val body = runCatching {
                OkHttp.client().newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) "" else resp.body.string()
                }
            }.onFailure { lastError = "${it::class.simpleName}: ${it.message} ($url)" }
                .getOrDefault("")
            // 镜像内容必须与钉住提交下的 md5 一致: 不符即换下一个镜像 (防镜像篡改)
            if (Crypto.md5(body) != expectedMd5) {
                lastError = "md5 不符 ($url)"
                continue
            }
            runCatching {
                cached.parentFile?.mkdirs()
                cached.writeText(body)
            }
            return body
        }
        AppLog.put(
            "TVBox JS 依赖模块获取失败 (宿主资源无且全部镜像不可用/校验不过): $path" +
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

    /** 缓存命中取源: 命中且内容仍像 JS 才用, 否则当未命中重下 (错误页/半写内容不得进 eval)。 */
    private fun cachedJs(file: File): String? {
        if (!file.isFile || file.length() <= 0) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return text.takeIf { looksLikeJs(it) }
    }

    /** 信任清单内模块的缓存命中: 内容 md5 必须与钉住提交下的记录一致, 不符视为未命中。 */
    private fun cachedVerifiedJs(file: File, expectedMd5: String): String? {
        if (!file.isFile || file.length() <= 0) return null
        if (Crypto.md5(file) != expectedMd5) return null
        return runCatching { file.readText() }.getOrNull()
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
        return NetworkUtils.getAbsoluteURL(base, raw)
    }

    companion object {
        /** 宿主对象在 JS 侧的注入键 (与 TvBoxJsApi.js 的 __M.setHost 参数一致)。 */
        internal const val HOST_KEY_HOST = "__hostBridge__"

        /** 宿主资源协议头 (FongMi/TV `assets://` 语义: 随包资源, 非远端)。 */
        private const val ASSETS_SCHEME = "assets://"
    }
}

/**
 * 读宿主自带引导脚本 (composeResources 单一数据源, 见 [TvBoxHostAssetProviders]);
 * 缺失时抛错, 避免静默空运行时。
 */
internal fun readHostAsset(assetPath: String): String {
    val provider = TvBoxHostAssetProviders.get()
    val text = runCatching { provider.read(assetPath) }.getOrNull()
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
