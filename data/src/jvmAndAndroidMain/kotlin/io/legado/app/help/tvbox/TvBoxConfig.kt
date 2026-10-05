package io.legado.app.help.tvbox

import org.json.JSONArray
import org.json.JSONObject

/**
 * TVBox 配置数据面 (格式契约见 FongMi/TV 文档与 TVBox oss 约定):
 *
 * {"spider":"<jar url>;md5;<md5>","sites":[{"key","name","type","api","ext","jar",
 *   "searchable","filterable","quickSearch","timeout","header","playUrl",...}]}
 *
 * 站点形态由 api 判定 (不依赖 type): csp_=JAR Spider, 含 .js=JS Spider, http=CMS 直连。
 * type 字段在生态里只标识数据格式 (0=xml CMS, 1=json CMS, 3=spider), 与引擎选择无关
 * (FongMi BaseLoader.getSpider 亦只看 api)。站点未声明 jar 时回退全局 spider 字段。
 */
data class TvBoxSite(
    val key: String,
    val name: String,
    val type: Int,
    val api: String,
    val ext: String,
    val jar: String,
    val playUrl: String,
    val searchable: Boolean,
    val filterable: Boolean,
    /** 上游配置 schema 字段, 宿主暂未消费 (保留以维持解析面完整)。 */
    val quickSearch: Boolean,
    /** 上游配置 schema 字段, 宿主暂未消费。 */
    val timeoutSeconds: Int?,
    val header: Map<String, String>,
) {
    val isJarSpider: Boolean get() = api.startsWith("csp_")

    /**
     * JS Spider: api 指向 .js (FongMi BaseLoader.isJs 同语义: api.contains(".js")),
     * 站点类型不参与判定 —— 生态里 type=1 (JSON CMS) 与 type=3 (spider) 混用,
     * 只有 api 形态能可靠区分 jar class / js 文件 / CMS 接口。
     */
    val isJsSpider: Boolean get() = api.contains(".js")

    /**
     * Python spider 站点 (FongMi BaseLoader.isPy 同语义: api.contains(".py")),
     * 判定次序须在 .js/CMS 之前。本项目无 python 运行时, 只做准入排除:
     * 若放行到 CMS 分支, py 源码文本会被当 JSON 解析而炸出难排查的 JSONException。
     */
    val isPySpider: Boolean get() = api.contains(".py")

    /** type=0 苹果 CMS 直连: api 即接口根 URL (无 jar, 走宿主侧 CmsSpider)。 */
    val isCmsApi: Boolean get() = api.startsWith("http")

    fun effectiveJar(globalSpider: String): String = jar.ifBlank { globalSpider }

    companion object {

        fun fromJson(obj: JSONObject): TvBoxSite? {
            val key = obj.optString("key").trim()
            val api = obj.optString("api").trim()
            if (key.isEmpty() || api.isEmpty()) return null
            return TvBoxSite(
                key = key,
                name = obj.optString("name").ifBlank { key },
                type = obj.optInt("type", 0),
                api = api,
                ext = extStringOf(obj.opt("ext")),
                jar = obj.optString("jar").trim(),
                playUrl = obj.optString("playUrl").trim(),
                searchable = obj.optBoolean("searchable", true),
                filterable = obj.optBoolean("filterable", true),
                quickSearch = obj.optBoolean("quickSearch", true),
                timeoutSeconds = obj.optInt("timeout", 0).takeIf { it > 0 },
                header = headerOf(obj.opt("header")),
            )
        }

        /** ext 可为字符串/对象/数组, spider 侧一律取其字符串形态 (FongMi ExtAdapter 同语义)。 */
        private fun extStringOf(ext: Any?): String = when (ext) {
            null -> ""
            is JSONObject, is JSONArray -> ext.toString()
            else -> ext.toString().trim()
        }

        /** header 可为对象或 "k=v&k2=v2" 形态字符串。 */
        private fun headerOf(header: Any?): Map<String, String> {
            return when (header) {
                is JSONObject -> {
                    val map = linkedMapOf<String, String>()
                    for (key in header.keys()) map[key] = header.optString(key)
                    map.filterValues { it.isNotEmpty() }
                }
                is String -> header.split("&")
                    .mapNotNull { part ->
                        val i = part.indexOf('=')
                        if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
                    }.toMap()
                else -> emptyMap()
            }
        }
    }
}

data class TvBoxConfig(
    val spider: String,
    val sites: List<TvBoxSite>,
    /**
     * 顶层 `flags` 解析线路名清单 (FongMi `VodConfig.get().getFlags()`)。
     *
     * 取播时原样透传给 `spider.playerContent` 的第三参 vipFlags, 由 spider 判定该线路
     * 是否需要走解析; 只取数组形态, 缺失/其余形态为空表。
     */
    val flags: List<String> = emptyList(),
    /**
     * 顶层 `parses[]` 解析站清单 (jxs), 供 parse=1 网页嗅探拼解析页;
     * 见 [TvBoxParse]。多数配置都有该数组, 缺失时为空表 (退化为直接嗅探播放页本身)。
     */
    val parses: List<TvBoxParse> = emptyList(),
    /**
     * 配置拉取地址 (可为 null/空): JS spider 的 api 常是 "./cat/js/x.js",
     * 运行时按它解析模块 URL (FongMi UrlUtil.convert + Module.fetch 同语义)。
     */
    val baseUrl: String = "",
) {
    companion object {

        /**
         * [baseUrl] 为配置拉取地址时, spider/jar/ext 中 "./x" 形态的相对路径
         * 相对配置 URL 解析 (FongMi UrlUtil.convert 同语义); 其余形态原样保留。
         */
        fun parse(json: String, baseUrl: String? = null): TvBoxConfig {
            val root = JSONObject(json)
            val spider = when (val raw = root.opt("spider")) {
                is JSONArray -> raw.optString(0)
                else -> raw?.toString()?.trim().orEmpty()
            }
            val sitesJson = root.optJSONArray("sites")
            val sites = ArrayList<TvBoxSite>(sitesJson?.length() ?: 0)
            for (i in 0 until (sitesJson?.length() ?: 0)) {
                val obj = sitesJson?.optJSONObject(i) ?: continue
                TvBoxSite.fromJson(obj)?.let(sites::add)
            }
            val parses = parseParses(root.optJSONArray("parses"))
            val flags = parseFlags(root.opt("flags"))
            // 协议头转换与 baseUrl 无关 (FongMi Site.objectFrom 无条件 convert), 必须先做:
            // baseUrl 缺失 (本地配置文件无来源地址) 时也不能跳过, 否则 file:///proxy:// 原样进 spider
            if (baseUrl == null) {
                return TvBoxConfig(
                    spider = convertSpec(spider),
                    sites = sites.map {
                        it.copy(
                            jar = convertSpec(it.jar),
                            ext = convertSpec(it.ext),
                            api = convertSpec(it.api),
                        )
                    },
                    flags = flags,
                    parses = parses.map { it.copy(url = convertSpec(it.url)) },
                )
            }
            return TvBoxConfig(
                spider = convertSpec(resolveRelative(spider, baseUrl)),
                sites = sites.map {
                    it.copy(
                        jar = convertSpec(resolveRelative(it.jar, baseUrl)),
                        ext = convertSpec(resolveRelative(it.ext, baseUrl)),
                        api = convertSpec(resolveRelative(it.api, baseUrl)),
                    )
                },
                flags = flags,
                parses = parses.map { it.copy(url = convertSpec(resolveRelative(it.url, baseUrl))) },
                baseUrl = baseUrl,
            )
        }

        /**
         * 顶层 `flags` 解析: 只取数组形态, 逐项转字符串; 其余形态 (含单串/对象) 为空表。
         *
         * FongMi `Json.safeListString` 对数组外的形态是副产物行为: 对象形态会塞进一个空串项,
         * 单串形态则直接抛异常 (上游未捕) —— 两者均为缺陷, 不复刻, 统一收敛为空表。
         */
        private fun parseFlags(raw: Any?): List<String> =
            if (raw is JSONArray) (0 until raw.length()).map { raw.optString(it) } else emptyList()

        /**
         * `file://` / `proxy://` 两种协议头 → 本地服务 HTTP 地址 (FongMi `UrlUtil.convert` 同语义):
         *
         * - `file://<path>` → `http://127.0.0.1:<port>/file/<path>`
         *   (spider 拿不到文件系统路径, 只能走宿主 /file 服务);
         * - `proxy://<query>` → `http://127.0.0.1:<port>/proxy?<query>`
         *   (jar/JS spider 用它在配置里预置代理地址)。
         *
         * 端口取 [com.github.catvod.Proxy.getPort] (本地服务已起时为正), 故调用方必须先起服务。
         * 未起的 -1 会原样进 URL —— 那是配置装载顺序错误, 不是本函数能兜的。
         * 逐 `$$$` 段处理: ext 可能是 `./lib/token.json$$$https://site/$$$null` 形态。
         */
        private fun convertSpec(spec: String): String {
            if (!spec.contains(FILE_SCHEME) && !spec.contains(PROXY_SCHEME)) return spec
            return spec.split("$$$").joinToString("$$$") { segment -> convertSegment(segment) }
        }

        private fun convertSegment(segment: String): String {
            val trimmed = segment.trim()
            val base = "http://127.0.0.1:" + com.github.catvod.Proxy.getPort()
            return when {
                trimmed.startsWith(FILE_SCHEME) ->
                    base + "/file/" + encodePath(trimmed.removePrefix(FILE_SCHEME))
                trimmed.startsWith(PROXY_SCHEME) ->
                    base + "/proxy?" + trimmed.removePrefix(PROXY_SCHEME)
                else -> segment
            }
        }

        /** 路径百分号编码, 保留 `/` (FongMi `Uri.encode(path, "/")` 同语义)。 */
        private fun encodePath(path: String): String =
            java.net.URLEncoder.encode(path, "UTF-8")
                .replace("+", "%20")
                .replace("%2F", "/", ignoreCase = true)

        private const val FILE_SCHEME = "file://"
        private const val PROXY_SCHEME = "proxy://"

        /** 解析站清单; 单条坏数据跳过不影响其余 (第三方配置的 parses 常有残缺项)。 */
        private fun parseParses(array: JSONArray?): List<TvBoxParse> {
            val list = ArrayList<TvBoxParse>(array?.length() ?: 0)
            for (i in 0 until (array?.length() ?: 0)) {
                val obj = array?.optJSONObject(i) ?: continue
                runCatching { TvBoxParse.fromJson(obj) }.getOrNull()?.let(list::add)
            }
            return list
        }

        /**
         * 仅处理 "./x" 相对路径; jar 规格串的 ";md5;…" 段原样保留; ext 可能是
         * 美元符号分段的复合格式 (如 "./lib/token.json$$$https://site/$$$null"), 逐段解析。
         */
        private fun resolveRelative(spec: String, baseUrl: String): String {
            if (!spec.contains("./") && !spec.contains("../")) return spec
            return spec.split("$$$").joinToString("$$$") { segment ->
                resolveSegment(segment, baseUrl)
            }
        }

        private fun resolveSegment(segment: String, baseUrl: String): String {
            val trimmed = segment.trim()
            if (!trimmed.startsWith("./") && !trimmed.startsWith("../")) return segment
            val sep = trimmed.indexOf(";md5;")
            val url = if (sep >= 0) trimmed.substring(0, sep) else trimmed
            val suffix = if (sep >= 0) trimmed.substring(sep) else ""
            val resolved = runCatching {
                java.net.URI(baseUrl).resolve(url).toString()
            }.getOrNull() ?: return segment
            return resolved + suffix
        }
    }
}
