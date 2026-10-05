package io.legado.app.help.tvbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * TVBox parse=1 (网页嗅探 + type=1 json API + type=2/3 jar 聚合) 的宿主实现 —— 平台无关面。
 *
 * 生态语义 (查证自 FongMi/TV fongmi 分支与 TVBox 原版 q215613905/TVBoxOS):
 * - spider 的 `playerContent` 回 `parse`(1) 或 `jx`(1) 表明 [sniff] 的入参 [url] 是**播放页**
 *   而非视频直链, 需先加载该页取出真实媒体地址 —— FongMi `bean/Result.needParse()` /
 *   `isUseParse()`, `player/parse/ParseJob.doInBackground()` 的 type=0 分支;
 * - type=1 json API 与 type=2/3 jar 聚合是纯 HTTP/反射, 直接在本对象实现;
 * - type=0 网页嗅探要加载页面并拦子资源, 依赖平台 WebView 引擎, 经 [TvBoxSniffPlatforms]
 *   分发 (Android=app 模块 AndroidTvBoxSniffer 的 headless WebView, 桌面端=desktop 模块
 *   DesktopTvBoxSniffer 的 DesktopWebViewEngine; 未注册时如实报错)。
 *
 * 取播委派 [TvBoxSourceDelegateImpl.getContentAwait] 的顺序: 先收直连线路 (无 WebView
 * 开销), 全线路都拿不到直链时才逐条走嗅探。
 */
object TvBoxSniffer {

    /** 单次嗅探的默认超时; 超出即视为失败, 避免解析页无限挂住取播链路。 */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    /** 内嵌播放器页的下钻深度上限; 到顶后不再往下套娃。 */
    const val MAX_DEPTH = 3

    /**
     * 加载 [url] (播放页) 并嗅探出真实媒体地址。可在任意线程调用。
     *
     * @param url 播放页地址
     * @param headers 附加请求头 (UA/Referer/Cookie)
     * @param timeoutMs 超时
     * @param videoChecker 视频地址判据; 默认 [TvBoxVideoPredicate.Sniffer]。spider 声明
     *   `manualVideoCheck()` 时应在此钩子委托它自己的 `isVideoFormat()` —— FongMi
     *   `CustomWebView.isVideoFormat()` 的 spider 委托分支。
     * @param depth 下钻剩余深度 (内部递归用)
     * @return 嗅到的真实媒体地址与应随播放请求携带的头
     */
    suspend fun sniff(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        videoChecker: TvBoxVideoPredicate = TvBoxVideoPredicate.Sniffer,
        depth: Int = MAX_DEPTH,
    ): TvBoxSniffResult {
        check(url.startsWith("http")) { "嗅探地址非 http(s): $url" }
        val platform = TvBoxSniffPlatforms.getOrNull()
            ?: error("当前平台未实现 TVBox 网页嗅探 (WebView 引擎未接入), 仅支持直链与 json 解析")
        return platform.sniff(url, headers, timeoutMs, videoChecker, depth)
    }

    /**
     * type=1 json API 解析 (FongMi ParseJob.jsonParse): HTTP 请求 `parse.url + 播放页`,
     * 按返回 JSON 结构提取直链 (`url` 或 `data.url`), 结果直链直接可用, 无 WebView。
     *
     * 有效性判据对齐 FongMi checkResult: url 过短 (≤40 字符) 视为无直链。
     */
    suspend fun parseJsonApi(
        parse: TvBoxParse,
        playUrl: String,
        headers: Map<String, String> = emptyMap(),
    ): TvBoxSniffResult = withContext(Dispatchers.IO) {
        val requestUrl = parse.url + playUrl
        check(requestUrl.startsWith("http")) { "json 解析站地址非 http(s): $requestUrl" }
        val merged = parse.headers + headers
        val json = com.github.catvod.net.OkHttp.newCall(requestUrl, merged).execute().use { resp ->
            check(resp.isSuccessful) { "json 解析请求 HTTP ${resp.code}: $requestUrl" }
            resp.body.string()
        }
        val root = JSONObject(json)
        val url = extractJsonUrl(root)
        check(url.length > 40) { "json 解析未返回有效直链 (url=${url.take(40)}): $requestUrl" }
        check(url.startsWith("http") || url.startsWith("rtmp")) { "json 解析返回非媒体地址: $url" }
        val respHeaders = extractJsonHeaders(root)
        TvBoxSniffResult(url, if (respHeaders.isEmpty()) merged else respHeaders)
    }

    /**
     * type=2/3 (Json 扩展/聚合) 解析 (FongMi ParseJob.jsonExtend/jsonMix):
     * 收集全体解析项, 经站点 jar 内解析类 (`com.github.catvod.parser.Json{url}`/`Mix{url}`)
     * 反射提取直链。结果若仍标 parse/jx=1 则下钻网页嗅探 (FongMi checkResult(Result) 同义)。
     * JS spider 站点无 jar, 解析类缺失时如实失败。
     */
    suspend fun parseJsonAggregate(
        parse: TvBoxParse,
        allParses: List<TvBoxParse>,
        flag: String,
        playUrl: String,
        spider: com.github.catvod.crawler.Spider,
        headers: Map<String, String> = emptyMap(),
        videoChecker: TvBoxVideoPredicate = TvBoxVideoPredicate.Sniffer,
    ): TvBoxSniffResult = withContext(Dispatchers.IO) {
        val root = invokeJarParser(parse, allParses, flag, playUrl, spider)
        val url = root.optString("url").trim()
        check(url.isNotEmpty()) { "聚合解析返回无 url: ${parse.name}" }
        val respHeaders = extractJsonHeaders(root)
        val merged = if (respHeaders.isEmpty()) parse.headers + headers else respHeaders
        if (root.optInt("parse", 0) != 0 || root.optInt("jx", 0) != 0) {
            return@withContext sniff(url, headers = merged, videoChecker = videoChecker)
        }
        TvBoxSniffResult(url, merged)
    }

    /** 经站点 jar 内解析类取结果 (FongMi JarLoader.jsonExt/jsonExtMix 同语义: 反射调静态 parse)。 */
    private fun invokeJarParser(
        parse: TvBoxParse,
        allParses: List<TvBoxParse>,
        flag: String,
        playUrl: String,
        spider: com.github.catvod.crawler.Spider,
    ): JSONObject {
        val suffix = when (parse.type) {
            TvBoxParse.TYPE_JSON_EXT -> "Json" + parse.url
            TvBoxParse.TYPE_MIX -> "Mix" + parse.url
            else -> error("非聚合 type: ${parse.type}")
        }
        val className = "com.github.catvod.parser.$suffix"
        val loader = spider.javaClass.classLoader
            ?: error("站点无 jar 类加载器, 无法调用聚合解析类: $className")
        val clz = try {
            loader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            // JS spider 站点无 jar 侧解析类, 聚合解析不可用 (FongMi 同语义, 如实失败)
            error("站点 jar 无聚合解析类 $className (${e.message})")
        }
        return try {
            when (parse.type) {
                TvBoxParse.TYPE_JSON_EXT -> {
                    clz.getMethod("parse", LinkedHashMap::class.java, String::class.java)
                        .invoke(null, jsonExtJxs(allParses), playUrl) as JSONObject
                }
                else -> {
                    clz.getMethod(
                        "parse", LinkedHashMap::class.java, String::class.java,
                        String::class.java, String::class.java,
                    ).invoke(null, mixJxs(allParses), parse.name, flag, playUrl) as JSONObject
                }
            }
        } catch (e: Exception) {
            val root = generateSequence<Throwable>(e) { it.cause }.last()
            error("调用聚合解析类 $className 失败: ${root.message}")
        }
    }
}

/** 嗅探结果: 真实媒体地址 + 应随播放请求携带的头。 */
data class TvBoxSniffResult(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 一条解析配置 (jxs), 对齐 FongMi `bean/Parse` / TVBox 原版 `bean/ParseBean`:
 * 配置 json 顶层 `parses[]` 的元素, 形如
 * `{"name":"虾米","type":0,"url":"https://jx.xmflv.com/?url=","ext":{"flag":[...],"header":{...}}}`。
 *
 * `type` 语义 (原版 ParseBean 注释 + FongMi ParseJob.doInBackground 分支):
 * 0=普通嗅探 (加载 `url + 播放页` 后嗅出真实地址)、
 * 1=json 请求 `url + 播放页` 取 `{url}`、2=Json 扩展 (jar 内 `Json{url}` 类)、
 * 3=聚合 (jar 内 `Mix{url}` 类)、4=FongMi 自加的"神解析"。
 * `ext.flag` 限定该解析只对哪些播放线路 (play_from) 生效。
 *
 * type=0 参与网页嗅探拼装, type=1 走 json API, type=2/3 走 jar 聚合类:
 * 三类在 [TvBoxSourceDelegateImpl.sniffContent] 按序尝试 (FongMi ParseJob 的 type 分支)。
 */
data class TvBoxParse(
    val name: String,
    val type: Int,
    val url: String,
    val flags: List<String>,
    val headers: Map<String, String>,
    /** ext 原始 JSON 文本 (聚合类透传用, FongMi Parse.ext 的字符串形态)。 */
    val extJson: String = "",
) {
    /** type=0 普通嗅探: 可参与网页嗅探拼装。 */
    val isWebSniff: Boolean get() = type == TYPE_WEB_SNIFF && url.isNotBlank()

    /** type=1 json API: 请求 `url+播放页` 按返回 JSON 结构提取直链 (FongMi ParseJob.jsonParse)。 */
    val isJsonApi: Boolean get() = type == TYPE_JSON && url.isNotBlank()

    /** type=2/3 (Json 扩展/聚合): 依赖 jar 内解析类, 经站点 jar 类加载器反射调用。 */
    val isAggregate: Boolean get() = type == TYPE_JSON_EXT || type == TYPE_MIX

    /** 拼出加载给嗅探引擎的解析页地址 (播放页原样拼在 url 之后)。 */
    fun pageOf(playUrl: String): String = url + playUrl

    /** type=2 传给 jar 解析类的请求形态 (FongMi Parse.extUrl): 带 base64 的 ext。 */
    fun extUrl(): String {
        if (extJson.isBlank()) return url
        val index = url.indexOf('?')
        if (index == -1) return url
        return url.substring(0, index + 1) + "cat_ext=" + base64Url(extJson) + "&" + url.substring(index + 1)
    }

    /** type=3 传给 jar 聚合类的元信息 (FongMi Parse.mixMap)。 */
    fun mixMap(): Map<String, String> = linkedMapOf(
        "type" to type.toString(),
        "ext" to extJson,
        "url" to url,
    )

    companion object {
        /** FongMi ParseJob 分支 type: 0 普通嗅探 / 1 json API / 2 Json 扩展 / 3 聚合 / 4 神解析。 */
        const val TYPE_WEB_SNIFF = 0
        const val TYPE_JSON = 1
        const val TYPE_JSON_EXT = 2
        const val TYPE_MIX = 3

        fun fromJson(obj: JSONObject): TvBoxParse? {
            val url = obj.optString("url").trim()
            if (url.isEmpty()) return null
            val ext = obj.optJSONObject("ext") ?: JSONObject()
            val flags = ArrayList<String>()
            ext.optJSONArray("flag")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(flags::add)
                }
            }
            val headers = linkedMapOf<String, String>()
            for (table in listOf(ext.optJSONObject("header"), obj.optJSONObject("header"))) {
                val entry = table ?: continue
                for (key in entry.keys()) {
                    val value = entry.opt(key)?.toString()?.trim().orEmpty()
                    if (value.isNotEmpty() && !headers.containsKey(key)) headers[key] = value
                }
            }
            return TvBoxParse(
                name = obj.optString("name").trim(),
                type = obj.optInt("type", TYPE_WEB_SNIFF),
                url = url,
                flags = flags,
                headers = headers,
                extJson = if (obj.has("ext")) obj.opt("ext")?.toString().orEmpty() else "",
            )
        }
    }
}

/** 挑一条适用于 [flag] 线路的 type=0 解析配置; 无线路限定或限定不命中时退回首条可用项。 */
fun List<TvBoxParse>.pickWebSniff(flag: String): TvBoxParse? {
    val usable = filter { it.isWebSniff }
    if (usable.isEmpty()) return null
    return usable.firstOrNull { flag.isNotBlank() && it.flags.contains(flag) } ?: usable.first()
}

/** 挑一条适用于 [flag] 线路的 type=1 json API 解析; 无线路限定或限定不命中时退回首条可用项。 */
fun List<TvBoxParse>.pickJsonApi(flag: String): TvBoxParse? {
    val usable = filter { it.isJsonApi }
    if (usable.isEmpty()) return null
    return usable.firstOrNull { flag.isNotBlank() && it.flags.contains(flag) } ?: usable.first()
}

/** 聚合类解析 (type=2/3, Json 扩展/聚合): 配置里通常至多一条, 取首条。 */
fun List<TvBoxParse>.pickAggregate(): TvBoxParse? = firstOrNull { it.isAggregate }

/** type=1 json 解析的 url 提取 (FongMi ParseJob.jsonParse: 根 url → data.url 兜底)。 */
public fun extractJsonUrl(root: JSONObject): String {
    val url = root.optString("url").trim()
    if (url.isNotEmpty()) return url
    return root.optJSONObject("data")?.optString("url")?.trim().orEmpty()
}

/**
 * type=1/聚合响应的防盗链头提取 (FongMi ParseJob.getHeader 同语义): 只认 UA/Referer/Cookie,
 * "ua" 键归一为 "User-Agent"。
 */
public fun extractJsonHeaders(root: JSONObject): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    for (key in root.keys()) {
        val value = root.opt(key)?.toString()?.trim().orEmpty()
        if (value.isEmpty()) continue
        when (key.lowercase()) {
            "user-agent", "ua" -> headers["User-Agent"] = value
            "referer" -> headers["Referer"] = value
            "cookie" -> headers["Cookie"] = value
        }
    }
    return headers
}

/** type=2 传给 jar 解析类的 jxs 映射 (全体 type=1 解析的 name→extUrl)。 */
public fun jsonExtJxs(parses: List<TvBoxParse>): Map<String, String> {
    val map = linkedMapOf<String, String>()
    for (item in parses) if (item.isJsonApi) map[item.name] = item.extUrl()
    return map
}

/** type=3 传给 jar 聚合类的 jxs 映射 (全体解析的 name→{type,ext,url})。 */
public fun mixJxs(parses: List<TvBoxParse>): Map<String, Map<String, String>> {
    val map = linkedMapOf<String, Map<String, String>>()
    for (item in parses) map[item.name] = item.mixMap()
    return map
}

/** Android Base64(URL_SAFE|NO_WRAP) 的 JVM 等价编码 (FongMi Util.base64 URL_SAFE)。 */
private fun base64Url(text: String): String =
    java.util.Base64.getUrlEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

/** 视频地址形态正则: FongMi `utils/Sniffer.SNIFFER`。 */
private val SNIFFER = Regex(
    "https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\\?.*)?" +
        "|https?://.*?video/tos[^\\s]*|rtmp:[^\\s]+",
)

/**
 * 视频地址形态过滤 (FongMi `Sniffer.isVideoFormat()` 的纯 URL 面, 无 rule 配置部分):
 * 明显的非视频形态 (`url=http` / `v=http` / `.html`) 直接排除 —— 解析页的 URL 参数里
 * 往往带着真实播放页且常常是 .html, 不排除会把解析页自己误判成视频。
 * 纯函数 (无平台依赖), JVM 单测直接覆盖。
 */
public fun isVideoUrl(url: String): Boolean {
    if (url.contains("url=http") || url.contains("v=http") || url.contains(".html")) return false
    return SNIFFER.containsMatchIn(url)
}

/** 视频地址判据: 默认 [TvBoxVideoPredicate.Sniffer] 走 URL 形态; spider 侧 `manualVideoCheck()` 可换实现。 */
fun interface TvBoxVideoPredicate {
    fun isVideoFormat(url: String): Boolean

    companion object {
        val Sniffer = TvBoxVideoPredicate { isVideoUrl(it) }
    }
}
