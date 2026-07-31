package io.legado.app.lib.webdav

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.utils.InputStream
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.findNS
import io.legado.app.utils.findNSPrefix
import io.legado.app.utils.toInputStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import io.legado.app.utils.File

/**
 * WebDav 客户端 nativeMain actual 实现。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉)。
 * 原 iosMain 用 NSDateFormatter/NSFileManager (iOS Foundation 专属),
 * 原 ohosMain 用纯 Kotlin (Howard Hinnant days_from_civil) / kotlin.io.File;
 * 按项目约束 "NSFileManager↔kotlin.io.File 统一用 kotlin.io.File",
 * nativeMain 采用鸿蒙端纯 Kotlin 实现, 避免平台专属 API 依赖 (nativeMain 不依赖 platform.Foundation)。
 *
 * 详见 commonMain/kotlin/io/legado/app/lib/webdav/WebDav.kt expect 注释。
 *
 * - Ktor Client + CIO 引擎 (纯 Kotlin, iOS/鸿蒙均可用), 替代 OkHttp
 * - PROPFIND/MKCOL/PUT/DELETE/GET 用 Ktor `HttpMethod` 自定义实现
 * - XML 解析用 ksoup (commonMain 已有依赖, 与 jvmAndAndroidMain 一致)
 * - lastModify 用纯 Kotlin RFC 1123 解析 (替代 java.time.ZonedDateTime / iOS NSDateFormatter)
 *
 * **与 jvmAndAndroidMain 的行为差异**:
 * - `httpUrl` 不做 OkHttp `toHttpUrl()` 级别的 URL 规范化 (默认端口/路径合并等),
 *   仅简单 replace 协议 (davs->https, dav->http); 功能等价, 但 URL 字符串可能不同
 * - `downloadInputStream()` 返回 ByteArrayInputStream 风格 (全量 bytes 装入内存),
 *   jvmAndAndroidMain 为流式 byteStream(); 大文件场景内存占用更高
 * - `downloadTo(savedPath, ...)` / `upload(localPath, ...)` 用 [kotlin.io.File]
 *   (Kotlin/Native 标准库支持, iOS/鸿蒙均可用)
 * - `lastModify` 解析用纯 Kotlin 实现 (Howard Hinnant days_from_civil 反算),
 *   与 jvmAndAndroidMain 的 java.time + iOS 端的 NSDateFormatter 行为等价
 * - URL 路径解码: jvmAndAndroidMain 用 UrlPathDecoder (字节级 %XX 解码, jvm 专属);
 *   nativeMain 用 [decodeUrlPath] 简化版 (UTF-8 解码, ASCII 场景一致)
 * - Auth header 在每个请求手动添加 (与 jvmAndAndroidMain 的 Interceptor 行为对齐),
 *   不依赖 Ktor Auth 插件 (避免 realm 过滤等差异)
 *
 * 注: Ktor 3.1.0 CIO 已发布 iosArm64/iosX64/iosSimulatorArm64 与 linuxArm64 变体,
 * nativeMain (iOS/鸿蒙共用) 可直接使用。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
actual open class WebDav actual constructor(
    actual val path: String,
    actual val authorization: Authorization
) {

    actual companion object {

        actual fun fromPath(path: String): WebDav {
            val id = AnalyzeUrlCore(path).serverID ?: throw WebDavException("没有serverID")
            val authorization = Authorization(id)
            return WebDav(path, authorization)
        }

        // 指定返回哪些属性 (与 jvmAndAndroidMain 完全一致)
        private const val DIR =
            """<?xml version="1.0"?>
            <a:propfind xmlns:a="DAV:">
                <a:prop>
                    <a:displayname/>
                    <a:resourcetype/>
                    <a:getcontentlength/>
                    <a:creationdate/>
                    <a:getlastmodified/>
                    %s
                </a:prop>
            </a:propfind>"""

        private const val EXISTS =
            """<?xml version="1.0"?>
            <propfind xmlns="DAV:">
               <prop>
                  <resourcetype />
               </prop>
            </propfind>"""
    }

    // 简单 replace 协议, 不做 OkHttp toHttpUrl() 级别的 URL 规范化
    // (OkHttp 会做默认端口移除/路径合并等; nativeMain 端 Ktor 内部自行解析, 功能等价但字符串可能不同)
    actual val httpUrl: String? by lazy {
        val raw = CustomUrl(path).getUrl()
            .replace("davs://", "https://")
            .replace("dav://", "http://")
        raw.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    // HttpClient 复用: 构造时创建一次, 不依赖 Ktor Auth 插件 (避免 realm 过滤等差异)
    // 每个 request 手动加 `authorization.name: authorization.data` header, 与 jvmAndAndroidMain Interceptor 行为对齐
    private val webDavClient: HttpClient by lazy {
        HttpClient(CIO)
    }

    /**
     * 获取当前url文件信息
     */
    @Throws(WebDavException::class)
    actual suspend fun getWebDavFile(): WebDavFile? {
        return propFindResponse(depth = 0)?.let {
            parseBody(it).firstOrNull()
        }
    }

    /**
     * 列出当前路径下的文件
     * @return 文件列表
     */
    @Throws(WebDavException::class)
    actual suspend fun listFiles(): List<WebDavFile> {
        propFindResponse()?.let { body ->
            val normalizedPath = path.removeSuffix("/")
            return parseBody(body).filter {
                it.path.removeSuffix("/") != normalizedPath
            }
        }
        return emptyList()
    }

    /**
     * @param propsList 指定列出文件的哪些属性
     */
    @Throws(WebDavException::class)
    private suspend fun propFindResponse(
        propsList: List<String> = emptyList(),
        depth: Int = 1
    ): String? {
        val requestProps = StringBuilder()
        for (p in propsList) {
            requestProps.append("<a:").append(p).append("/>\n")
        }
        val requestPropsStr: String = if (requestProps.toString().isEmpty()) {
            DIR.replace("%s", "")
        } else {
            // Native 无 String.format; DIR 仅含 1 个 %s 占位, replace 与 format 等价
            // (与上一分支同一写法; 属性名由 propsList 拼出, 不含 % 转义序列)
            DIR.replace("%s", requestProps.toString() + "\n")
        }
        val url = httpUrl ?: return null
        return kotlin.runCatching {
            withContext(Dispatchers.Default) {
                webDavClient.request(url) {
                    method = HttpMethod("PROPFIND")
                    header(authorization.name, authorization.data)
                    header("Depth", depth.toString())
                    // 添加RequestBody对象，可以只返回的属性。如果设为null，则会返回全部属性
                    // 注意：尽量手动指定需要返回的属性。若返回全部属性，可能后由于Prop.java里没有该属性名，而崩溃。
                    contentType(ContentType.Application.Xml.withCharset(Charsets.UTF_8))
                    setBody(requestPropsStr)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }.getOrThrow().let { resp ->
            checkResult(resp)
            resp.bodyAsText()
        }
    }

    /**
     * 解析webDav返回的xml
     */
    private fun parseBody(s: String): List<WebDavFile> {
        val list = ArrayList<WebDavFile>()
        val document = kotlin.runCatching {
            Ksoup.parse(s, parser = Parser.xmlParser())
        }.getOrElse {
            Ksoup.parse(s)
        }
        val ns = document.findNSPrefix("DAV:")
        val elements = document.findNS("response", ns)
        val urlStr = httpUrl ?: return list
        val baseUrl = NetworkUtils.getBaseUrl(urlStr)
        for (element in elements) {
            //依然是优化支持 caddy 自建的 WebDav ，其目录后缀都为“/”, 所以删除“/”的判定，不然无法获取该目录项
            val href = element.findNS("href", ns)[0].text()
            val hrefDecode = decodeUrlPath(href)
            val fileName = hrefDecode.removeSuffix("/").substringAfterLast("/")
            val webDavFile: WebDav
            try {
                val urlName = hrefDecode.ifEmpty {
                    urlStr.substringAfterLast("://").substringAfter("/").replace("/", "")
                }
                val displayName = element
                    .findNS("displayname", ns)
                    .firstOrNull()?.text()?.takeIf { it.isNotEmpty() }
                    ?.let { decodeUrlPath(it) } ?: fileName
                val contentType = element
                    .findNS("getcontenttype", ns)
                    .firstOrNull()?.text().orEmpty()
                val resourceType = element
                    .findNS("resourcetype", ns)
                    .firstOrNull()?.html()?.trim().orEmpty()
                val size = kotlin.runCatching {
                    element.findNS("getcontentlength", ns)
                        .firstOrNull()?.text()?.toLong() ?: 0
                }.getOrDefault(0)
                val lastModify: Long = kotlin.runCatching {
                    element.findNS("getlastmodified", ns)
                        .firstOrNull()?.text()?.let { parseRfc1123Time(it) }
                }.getOrNull() ?: 0
                var fullURL = NetworkUtils.getAbsoluteURL(baseUrl, hrefDecode)
                if (WebDavFile.isDir(contentType, resourceType) && !fullURL.endsWith("/")) {
                    fullURL += "/"
                }
                webDavFile = WebDavFile(
                    fullURL,
                    authorization,
                    displayName = displayName,
                    urlName = urlName,
                    size = size,
                    contentType = contentType,
                    resourceType = resourceType,
                    lastModify = lastModify
                )
                list.add(webDavFile)
            } catch (e: Exception) {
                // nativeMain 端无 java.net.MalformedURLException, 用通用 Exception 兜底
                e.printStackTraceOnDebug()
            }
        }
        return list
    }

    /**
     * 文件是否存在
     */
    actual suspend fun exists(): Boolean {
        val url = httpUrl ?: return false
        return kotlin.runCatching {
            withContext(Dispatchers.Default) {
                webDavClient.request(url) {
                    method = HttpMethod("PROPFIND")
                    header(authorization.name, authorization.data)
                    header("Depth", "0")
                    contentType(ContentType.Application.Xml)
                    setBody(EXISTS)
                }
            }.status.isSuccess()
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }.getOrDefault(false)
    }

    /**
     * 检查用户名密码是否有效
     */
    actual suspend fun check(): Boolean {
        val url = httpUrl ?: return true
        return kotlin.runCatching {
            withContext(Dispatchers.Default) {
                webDavClient.request(url) {
                    method = HttpMethod("PROPFIND")
                    header(authorization.name, authorization.data)
                    header("Depth", "0")
                    contentType(ContentType.Application.Xml)
                    setBody(EXISTS)
                }.status.value != 401
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }.getOrDefault(true)
    }

    /**
     * 根据自己的URL，在远程处创建对应的文件夹
     * @return 是否创建成功
     */
    actual suspend fun makeAsDir(): Boolean {
        val url = httpUrl ?: return false
        //防止报错
        return kotlin.runCatching {
            if (!exists()) {
                withContext(Dispatchers.Default) {
                    webDavClient.request(url) {
                        method = HttpMethod("MKCOL")
                        header(authorization.name, authorization.data)
                    }
                }.let { checkResult(it) }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav创建目录失败\n${it.message}", it)
        }.isSuccess
    }

    /**
     * 下载到本地
     *
     * **nativeMain 端实现**: 用 [kotlin.io.File] 写文件
     * (Kotlin/Native 标准库支持, iOS/鸿蒙均可用, 与 [io.legado.app.help.storage.BackupFileOps]
     * nativeMain actual 同一模式):
     * - 文件已存在且 [replaceExisting]=false 直接返回 (与 jvmAndAndroidMain 一致)
     * - 调 [download] 拿 ByteArray → [File.writeBytes]
     * - 父目录不存在时递归创建 (与 nativeMain BackupFileOps.writeText 行为对齐)
     *
     * 与 jvmAndAndroidMain 差异:
     * - jvmAndAndroidMain 用 downloadInputStream() 流式写 (避免全量入内存);
     *   nativeMain 端调 [download] 全量入内存再写文件, 大文件场景内存占用更高
     *   (与 [downloadInputStream] 降级一致)
     *
     * 解除 iOS/鸿蒙端 WebDav 备份恢复阻塞: [io.legado.app.help.AppWebDavShared.restoreWebDav]
     * 调用 `webDav.downloadTo(zipFilePath, true)` 下载备份 zip 现在可用。
     *
     * @param savedPath       本地的完整路径，包括最后的文件名
     * @param replaceExisting 是否替换本地的同名文件
     */
    @Throws(WebDavException::class)
    actual suspend fun downloadTo(savedPath: String, replaceExisting: Boolean) {
        val file = File(savedPath)
        if (file.exists() && !replaceExisting) {
            return
        }
        // 确保父目录存在 (与 nativeMain BackupFileOps.writeText 行为对齐)
        file.parentFile?.mkdirs()
        // 调 download() 拿 ByteArray → writeBytes (kotlin.io.File 无原子写 API, 与 jvmMain Files.write 行为一致)
        val bytes = download()
        file.writeBytes(bytes)
    }

    /**
     * 下载文件,返回ByteArray
     */
    @Throws(WebDavException::class)
    actual suspend fun download(): ByteArray {
        val url = httpUrl ?: throw WebDavException("WebDav下载出错\nurl为空")
        return kotlin.runCatching {
            withContext(Dispatchers.Default) {
                webDavClient.get(url) {
                    header(authorization.name, authorization.data)
                }.bodyAsBytes()
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            throw WebDavException("WebDav下载失败\n${it.message}")
        }.getOrThrow()
    }

    /**
     * 上传文件(本地路径)
     *
     * **nativeMain 端实现**: 用 [kotlin.io.File] 读文件
     * (Kotlin/Native 标准库支持, iOS/鸿蒙均可用, 与 [io.legado.app.help.storage.BackupFileOps]
     * nativeMain actual 同一模式):
     * - 文件不存在抛 [WebDavException] (与 jvmAndAndroidMain upload(file) 行为对齐)
     * - 读文件 ByteArray ([File.readBytes]) → 调 [upload] (ByteArray)
     *
     * 解除 iOS/鸿蒙端 WebDav 备份阻塞: [io.legado.app.help.AppWebDavShared.backUpWebDav]
     * 调用 `WebDav(putUrl, auth).upload(zipFilePath)` 上传备份 zip 现在可用。
     *
     * @param contentType 上传 Content-Type, 默认 [DEFAULT_CONTENT_TYPE]
     */
    @Throws(WebDavException::class)
    actual suspend fun upload(localPath: String, contentType: String) {
        val file = File(localPath)
        if (!file.exists()) {
            // 与 jvmAndAndroidMain upload(file) 行为对齐
            throw WebDavException("文件不存在")
        }
        // 读文件 ByteArray → 调 upload(ByteArray)
        val bytes = file.readBytes()
        upload(bytes, contentType)
    }

    /**
     * 上传文件(字节数组)
     */
    @Throws(WebDavException::class)
    actual suspend fun upload(byteArray: ByteArray, contentType: String) {
        kotlin.runCatching {
            withContext(Dispatchers.Default) {
                val url = httpUrl ?: throw NoStackTraceException("url不能为空")
                webDavClient.put(url) {
                    header(authorization.name, authorization.data)
                    // 务必注意RequestBody不要嵌套，不然上传时内容可能会被追加多余的文件信息
                    this.contentType(ContentType.parse(contentType))
                    setBody(byteArray)
                }.let { checkResult(it) }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav上传失败\n${it.message}", it)
            throw WebDavException("WebDav上传失败\n${it.message}")
        }
    }

    /**
     * 下载文件输入流
     *
     * **nativeMain 端降级**: 返回 ByteArrayInputStream 风格 (全量 bytes 装入内存),
     * jvmAndAndroidMain 为 OkHttp 流式 byteStream(); 大文件场景内存占用更高。
     */
    @Throws(WebDavException::class)
    actual suspend fun downloadInputStream(): InputStream {
        val url = httpUrl ?: throw WebDavException("WebDav下载出错\nurl为空")
        val bytes = kotlin.runCatching {
            withContext(Dispatchers.Default) {
                webDavClient.get(url) {
                    header(authorization.name, authorization.data)
                }.bodyAsBytes()
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            throw WebDavException("WebDav下载失败\n${it.message}")
        }.getOrThrow()
        return bytes.toInputStream()
    }

    /**
     * 移除文件/文件夹
     */
    actual suspend fun delete(): Boolean {
        val url = httpUrl ?: return false
        //防止报错
        return kotlin.runCatching {
            withContext(Dispatchers.Default) {
                webDavClient.delete(url) {
                    header(authorization.name, authorization.data)
                }
            }.let { checkResult(it) }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav删除失败\n${it.message}", it)
        }.isSuccess
    }

    /**
     * 按 Range 读取远程文件片段
     *
     * 注: 本方法是同步 (非 suspend) 函数, 但 Ktor 必须 suspend; 用 [runBlockingInScope] 阻塞。
     * Kotlin/Native 主线程调用 runBlocking 可能 deadlock, 调用方需在后台线程使用。
     */
    actual fun readRange(offset: Long, length: Int, fileSize: Long): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (fileSize in 1..offset) return ByteArray(0)

        val end = if (fileSize > 0) {
            minOf(fileSize - 1, offset + length - 1)
        } else {
            offset + length - 1
        }
        val range = "bytes=$offset-$end"

        val url = httpUrl ?: throw WebDavException("Invalid WebDAV URL")
        return runBlockingInScope(EmptyCoroutineContext) {
            val resp = webDavClient.get(url) {
                header(authorization.name, authorization.data)
                header("Range", range)
            }
            if (resp.status.value == 200) {
                throw WebDavException("Server does not support Range requests")
            }
            if (!resp.status.isSuccess()) {
                throw WebDavException("HTTP request failed: ${resp.status.value}")
            }
            resp.bodyAsBytes()
        }
    }

    /**
     * 检测返回结果是否正确
     *
     * 注: nativeMain 端 Ktor 的 body 读取是 suspend, 故本函数为 suspend;
     * 调用方均在 suspend 函数中, 无影响。
     */
    @Throws(WebDavException::class)
    private suspend fun checkResult(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            val code = response.status.value
            // 错误时才读 body (避免消耗成功响应的流)
            val body = kotlin.runCatching { response.bodyAsText() }.getOrDefault("")
            if (code == 401) {
                // 对齐 jvmAndAndroidMain: response.headers("WWW-Authenticate") 返回 List<String>
                // Ktor 用 headers.getAll 取所有值 (单值场景行为一致)
                val authHeaders = response.headers.getAll("WWW-Authenticate").orEmpty()
                val supportBasicAuth = authHeaders.any {
                    it.startsWith("Basic", ignoreCase = true)
                }
                if (authHeaders.isNotEmpty() && !supportBasicAuth) {
                    AppLog.put("服务器不支持BasicAuth认证")
                }
            }

            // jvmAndAndroidMain 用 response.message.isNotBlank(), Ktor 无 message 概念, 用 status.description
            val statusDescription = response.status.description
            if (statusDescription.isNotBlank() || body.isBlank()) {
                throw WebDavException("${url}\n${code}:${statusDescription}")
            }
            val document = Ksoup.parse(body)
            val exception = document.getElementsByTag("s:exception").firstOrNull()?.text()
            val message = document.getElementsByTag("s:message").firstOrNull()?.text()
            if (exception == "ObjectNotFound") {
                throw ObjectNotFoundException(
                    message ?: "$path doesn't exist. code:${code}"
                )
            }
            throw WebDavException(message ?: "未知错误 code:${code}")
        }
    }

    /**
     * RFC 1123 时间解析: "Sun, 06 Nov 1994 08:49:37 GMT" -> epoch millis
     *
     * 替代 jvmAndAndroidMain 的 java.time.ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
     * 与 iOS 端的 NSDateFormatter; nativeMain (iOS/鸿蒙共用) 不依赖平台专属 API, 用纯 Kotlin 实现:
     * 1. 正则提取 各字段
     * 2. 月份缩写映射到 1-12
     * 3. Howard Hinnant days_from_civil 算法计算 epoch 天数 → 毫秒
     *
     * 行为与 java.time/iOS 端等价 (RFC 1123 标准, UTC 时区)。
     */
    private fun parseRfc1123Time(s: String): Long {
        return kotlin.runCatching {
            // RFC 1123: "Sun, 06 Nov 1994 08:49:37 GMT" (周几和时区可选, 兼容 RFC 850/ascctime)
            val match = Regex(
                """^[A-Za-z]{3},\s+(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})\s+(\d{2}):(\d{2}):(\d{2})\s*(?:[A-Za-z]+)?$"""
            ).matchEntire(s.trim()) ?: return@runCatching 0L
            val day = match.groupValues[1].toInt()
            val month = monthFromAbbr(match.groupValues[2]) ?: return@runCatching 0L
            val year = match.groupValues[3].toInt()
            val hour = match.groupValues[4].toInt()
            val minute = match.groupValues[5].toInt()
            val second = match.groupValues[6].toInt()
            val days = daysFromCivil(year, month, day)
            (days * 86_400_000L) + (hour * 3_600_000L + minute * 60_000L + second * 1_000L)
        }.getOrDefault(0L)
    }

    /** 月份缩写 → 1-12 (RFC 1123 英文月份) */
    private fun monthFromAbbr(abbr: String): Int? = when (abbr) {
        "Jan" -> 1
        "Feb" -> 2
        "Mar" -> 3
        "Apr" -> 4
        "May" -> 5
        "Jun" -> 6
        "Jul" -> 7
        "Aug" -> 8
        "Sep" -> 9
        "Oct" -> 10
        "Nov" -> 11
        "Dec" -> 12
        else -> null
    }

    /**
     * Howard Hinnant days_from_civil 算法: (year, month, day) → 自 1970-01-01 起的天数。
     *
     * 与 ohosMain TimeUtils.ohos.kt 的 epochToYmd 互为逆运算, 行为等价 java.time.LocalDate.toEpochDay。
     * 参考: http://howardhinnant.github.io/date_algorithms.html
     */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146_097 + doe - 719_468
    }

    /**
     * URL 路径解码 (简化版, 替代 jvmAndAndroidMain 的 UrlPathDecoder)。
     *
     * - `+` 不转空格 (与 hutool URLDecoder.decodeForPath 行为一致)
     * - `%XX` 按 UTF-8 解码 (jvmAndAndroidMain 用 ISO_8859_1, ASCII 场景一致; 非 ASCII 场景为已知降级)
     * - 不合规的 '%' 原样输出
     */
    private fun decodeUrlPath(str: String): String {
        if (str.isEmpty()) return str
        val result = StringBuilder(str.length)
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '%' && i + 2 < str.length) {
                val h1 = hexDigit(str[i + 1])
                val h2 = hexDigit(str[i + 2])
                if (h1 >= 0 && h2 >= 0) {
                    result.append(((h1 shl 4) or h2).toChar())
                    i += 3
                    continue
                }
            }
            result.append(c)
            i++
        }
        // 此时 result 内是字节级解码后的字符串 (每 char < 256), 转 UTF-8 字节再解 String
        // 与 jvmAndAndroidMain 的 UrlPathDecoder.decode(str, UTF_8) 行为对齐
        val bytes = ByteArray(result.length)
        for (j in result.indices) {
            bytes[j] = result[j].code.toByte()
        }
        return bytes.decodeToString()
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> -1
    }

    /** 兼容 jvmAndAndroidMain checkResult 中 ${url} 引用 (java.net.URL toString) */
    private val url: String get() = httpUrl ?: path

}
