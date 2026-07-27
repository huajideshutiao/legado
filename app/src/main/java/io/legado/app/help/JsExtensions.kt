package io.legado.app.help

import android.content.Intent
import android.webkit.WebSettings
import androidx.annotation.Keep
import com.script.jsdispatch.JsApi
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.ConcurrentRateLimiter
import io.legado.app.help.http.cookieJarHeader
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.script.JsFn
import io.legado.app.model.script.jsContext
import io.legado.app.ui.association.JsActivity1
import io.legado.app.ui.association.JsActivity2
import io.legado.app.ui.association.OpenUrlConfirmDialog
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getEncode
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.LibArchiveUtils
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.externalCache
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isMainThread
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import okio.use
import org.jsoup.Connection
import org.jsoup.Jsoup
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * js扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 * 所有对于文件的读写删操作都是相对路径,只能操作阅读缓存内的文件
 * /android/data/{package}/cache/...
 */
@Keep
@JsApi
@Suppress("unused")
interface JsExtensions : JsEncodeUtilsAndroid, JsExtensionsCommon {

    private val context: CoroutineContext
        get() = jsContext.coroutineContext ?: EmptyCoroutineContext

    /**
     * 使用内置浏览器打开链接，手动验证网站防爬
     * @param url 要打开的链接
     * @param title 浏览器页面的标题
     */
    fun startBrowser(url: String, title: String) {
        jsContext.ensureActive()
        SourceVerificationHelp.startBrowser(getSource(), url, title, refetchAfterSuccess = false)
    }

    /**
     * 使用内置浏览器打开链接，并等待网页结果
     */
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): StrResponse {
        jsContext.ensureActive()
        val body = SourceVerificationHelp.getVerificationResult(
            getSource(), url, title, true, refetchAfterSuccess
        )
        return StrResponse(url, body)
    }

    fun startBrowserAwait(url: String, title: String): StrResponse {
        return startBrowserAwait(url, title, false)
    }

    /**
     * 打开图片验证码对话框，等待返回验证结果
     */
    fun getVerificationCode(imageUrl: String): String {
        jsContext.ensureActive()
        return SourceVerificationHelp.getVerificationResult(getSource(), imageUrl, "", false)
    }

    /**
     * 可从网络，本地文件(阅读私有数据目录相对路径)导入JavaScript脚本
     */
    fun importScript(path: String): String {
        val result = when {
            path.startsWith("http") -> cacheFile(path)
            else -> readTxtFile(path)
        }
        if (result.isBlank()) throw NoStackTraceException("$path 内容获取失败或者为空")
        return result
    }

    /**
     * 缓存以文本方式保存的文件 如.js .txt等
     * @param urlStr 网络文件的链接
     * @return 返回缓存后的文件内容
     */
    fun cacheFile(urlStr: String): String {
        return cacheFile(urlStr, 0)
    }

    /**
     * 缓存以文本方式保存的文件 如.js .txt等
     * @param saveTime 缓存时间，单位：秒
     */
    fun cacheFile(urlStr: String, saveTime: Int): String {
        val key = md5Encode16(urlStr)
        val cachePath = CacheManager.get(key)
        return if (
            cachePath.isNullOrBlank() ||
            !getFile(cachePath).exists()
        ) {
            val path = downloadFile(urlStr)
            log("首次下载 $urlStr >> $path")
            CacheManager.put(key, path, saveTime)
            readTxtFile(path)
        } else {
            readTxtFile(cachePath)
        }
    }

    /**
     * 下载文件
     * @param url 下载地址:可带参数type
     * @return 下载的文件相对路径
     */
    fun downloadFile(url: String): String {
        jsContext.ensureActive()
        val analyzeUrl = AnalyzeUrl(url, source = getSource(), coroutineContext = context)
        val type = analyzeUrl.type ?: UrlUtil.getSuffix(url)
        val path = FileUtils.getPath(
            File(FileUtils.getCachePath()),
            "${MD5Utils.md5Encode16(url)}.${type}"
        )
        val file = File(path)
        file.delete()
        analyzeUrl.getInputStream().use { iStream ->
            file.createFileReplace()
            try {
                file.outputStream().buffered().use { oStream ->
                    iStream.copyTo(oStream)
                }
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }
        return path.substring(FileUtils.getCachePath().length)
    }


    /**
     * 实现16进制字符串转文件
     * @param content 需要转成文件的16进制字符串
     * @param url 通过url里的参数来判断文件类型
     * @return 相对路径
     */
    @Deprecated(
        "Deprecated",
        ReplaceWith("downloadFile(url)")
    )
    fun downloadFile(content: String, url: String): String {
        jsContext.ensureActive()
        val type = AnalyzeUrl(url, source = getSource(), coroutineContext = context).type
            ?: return ""
        val path = FileUtils.getPath(
            FileUtils.createFolderIfNotExist(FileUtils.getCachePath()),
            "${MD5Utils.md5Encode16(url)}.${type}"
        )
        val file = File(path)
        file.createFileReplace()
        content.hexToByteArray().let {
            if (it.isNotEmpty()) {
                file.writeBytes(it)
            }
        }
        return path.substring(FileUtils.getCachePath().length)
    }

    /**
     * js实现重定向拦截,网络访问get
     */
    fun get(urlStr: String, headers: Map<String, String>): Connection.Response {
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headers.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headers
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            jsContext.ensureActive()
            Jsoup.connect(urlStr)
                .sslContext(SSLHelper.unsafeSslContext)
                .ignoreContentType(true)
                .followRedirects(false)
                .headers(requestHeaders)
                .method(Connection.Method.GET)
                .execute()
        }
        return response
    }

    /**
     * js实现重定向拦截,网络访问head,不返回Response Body更省流量
     */
    fun head(urlStr: String, headers: Map<String, String>): Connection.Response {
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headers.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headers
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            jsContext.ensureActive()
            Jsoup.connect(urlStr)
                .sslContext(SSLHelper.unsafeSslContext)
                .ignoreContentType(true)
                .followRedirects(false)
                .headers(requestHeaders)
                .method(Connection.Method.HEAD)
                .execute()
        }
        return response
    }

    /**
     * 网络访问post
     */
    fun post(urlStr: String, body: String, headers: Map<String, String>): Connection.Response {
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headers.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headers
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            jsContext.ensureActive()
            Jsoup.connect(urlStr)
                .sslContext(SSLHelper.unsafeSslContext)
                .ignoreContentType(true)
                .followRedirects(false)
                .requestBody(body)
                .headers(requestHeaders)
                .method(Connection.Method.POST)
                .execute()
        }
        return response
    }

    fun getWebViewUA(): String {
        return WebSettings.getDefaultUserAgent(appCtx)
    }

//****************文件操作******************//

    /**
     * 获取本地文件
     * @param path 相对路径
     * @return File
     */
    fun getFile(path: String): File {
        val cachePath = appCtx.externalCache.absolutePath
        val aPath = if (path.startsWith(File.separator)) {
            cachePath + path
        } else {
            cachePath + File.separator + path
        }
        val file = File(aPath)
        val safePath = appCtx.externalCache.parent!!
        if (!file.canonicalPath.startsWith(safePath)) {
            throw SecurityException("非法路径")
        }
        return file
    }

    fun readFile(path: String): ByteArray? {
        val file = getFile(path)
        if (file.exists()) {
            return file.readBytes()
        }
        return null
    }

    fun readTxtFile(path: String): String {
        val file = getFile(path)
        if (file.exists()) {
            val charsetName = EncodingDetect.getEncode(file)
            return String(file.readBytes(), charset(charsetName))
        }
        return ""
    }

    fun readTxtFile(path: String, charsetName: String): String {
        val file = getFile(path)
        if (file.exists()) {
            return String(file.readBytes(), charset(charsetName))
        }
        return ""
    }

    /**
     * 删除本地文件
     */
    fun deleteFile(path: String): Boolean {
        val file = getFile(path)
        return FileUtils.delete(file, true)
    }

    /**
     * js实现Zip压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    fun unzipFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    /**
     * js实现7Zip压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    fun un7zFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    /**
     * js实现Rar压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    fun unrarFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    /**
     * js实现压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    fun unArchiveFile(zipPath: String): String {
        if (zipPath.isEmpty()) return ""
        val zipFile = getFile(zipPath)
        return ArchiveUtils.deCompress(zipFile.absolutePath).let {
            ArchiveUtils.TEMP_FOLDER_NAME + File.separator + MD5Utils.md5Encode16(zipFile.name)
        }
    }

    /**
     * js实现文件夹内所有文本文件读取
     * @param path 文件夹相对路径
     * @return 所有文件字符串换行连接
     */
    fun getTxtInFolder(path: String): String {
        if (path.isEmpty()) return ""
        val folder = getFile(path)
        val contents = StringBuilder()
        folder.listFiles()?.forEach { f ->
            val charsetName = EncodingDetect.getEncode(f)
            contents.append(String(f.readBytes(), charset(charsetName)))
                .append("\n")
        }
        if (contents.isNotEmpty()) {
            contents.deleteCharAt(contents.length - 1)
        }
        FileUtils.delete(folder.absolutePath)
        return contents.toString()
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    fun getZipStringContent(url: String, path: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        val charsetName = EncodingDetect.getEncode(byteArray)
        return String(byteArray, Charset.forName(charsetName))
    }

    fun getZipStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charset.forName(charsetName))
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    fun getRarStringContent(url: String, path: String): String {
        val byteArray = getRarByteArrayContent(url, path) ?: return ""
        val charsetName = EncodingDetect.getEncode(byteArray)
        return String(byteArray, Charset.forName(charsetName))
    }

    fun getRarStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = getRarByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charset.forName(charsetName))
    }

    /**
     * 获取网络7zip文件里面的数据
     * @param url 7zip文件的链接或十六进制字符串
     * @param path 所需获取文件在7zip内的路径
     * @return zip指定文件的数据
     */
    fun get7zStringContent(url: String, path: String): String {
        val byteArray = get7zByteArrayContent(url, path) ?: return ""
        val charsetName = EncodingDetect.getEncode(byteArray)
        return String(byteArray, Charset.forName(charsetName))
    }

    fun get7zStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = get7zByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charset.forName(charsetName))
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    fun getZipByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.isAbsUrl()) {
            AnalyzeUrl(url, source = getSource(), coroutineContext = context).getByteArray()
        } else {
            url.hexToByteArray()
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            val entry = generateSequence(zis::getNextEntry).firstOrNull { it.name == path }
            if (entry != null) {
                val bos = ByteArrayOutputStream()
                zis.copyTo(bos)
                return bos.toByteArray()
            }
        }

        log("getZipContent 未发现内容")
        return null
    }

    /**
     * 获取网络Rar文件里面的数据
     * @param url Rar文件的链接或十六进制字符串
     * @param path 所需获取文件在Rar内的路径
     * @return Rar指定文件的数据
     */
    fun getRarByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.isAbsUrl()) {
            AnalyzeUrl(url, source = getSource(), coroutineContext = context).getByteArray()
        } else {
            url.hexToByteArray()
        }

        return ByteArrayInputStream(bytes).use {
            LibArchiveUtils.getByteArrayContent(it, path)
        }
    }

    /**
     * 获取网络7zip文件里面的数据
     * @param url 7zip文件的链接或十六进制字符串
     * @param path 所需获取文件在7zip内的路径
     * @return 7zip指定文件的数据
     */
    fun get7zByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.isAbsUrl()) {
            AnalyzeUrl(url, source = getSource(), coroutineContext = context).getByteArray()
        } else {
            url.hexToByteArray()
        }

        return ByteArrayInputStream(bytes).use {
            LibArchiveUtils.getByteArrayContent(it, path)
        }
    }


    /**
     * 弹窗提示
     */
    fun toast(msg: Any?) {
        jsContext.ensureActive()
        appCtx.toastOnUi("${getSource()?.getTag()}: ${msg.toString()}")
    }

    /**
     * 弹窗提示 停留时间较长
     */
    fun longToast(msg: Any?) {
        jsContext.ensureActive()
        appCtx.longToastOnUi("${getSource()?.getTag()}: ${msg.toString()}")
    }

    fun openUrl(url: String) {
        openUrl(url, null)
    }

    // 新增 mimeType 参数，默认为 null（保持兼容性）
    fun openUrl(url: String, mimeType: String? = null) {
        require(url.length < 64 * 1024) { "openUrl parameter url too long" }
        jsContext.ensureActive()
        val source = getSource() ?: throw NoStackTraceException("openUrl source cannot be null")
        OpenUrlConfirmDialog.display(
            url,
            mimeType,
            source.getKey(),
            source.getTag(),
            source.getSourceType()
        )
    }

    /**
     * 启动一个空白Activity，允许js操作，允许传入是否透明
     * @param action js函数
     * @param isTransparent 是否透明
     */
    fun startJsActivity(action: JsFn, isTransparent: Boolean = true) {
        val cx = jsContext
        if (isMainThread) return
        cx.ensureActive()
        val currentThread = Thread.currentThread()
        var error: Throwable? = null
        val intent = Intent(
            appCtx,
            if (isTransparent) JsActivity1::class.java else JsActivity2::class.java
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("actionKey", IntentData.put(action))
            putExtra("waitKey", IntentData.put { e: Throwable? ->
                error = e
                LockSupport.unpark(currentThread)
            })
        }
        appCtx.startActivity(intent)
        LockSupport.park(currentThread)
        error?.let { throw it }
    }

    fun startJsActivity(action: JsFn) = startJsActivity(action, true)
}
