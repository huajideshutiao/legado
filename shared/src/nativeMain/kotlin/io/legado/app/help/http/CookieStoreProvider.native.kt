package io.legado.app.help.http

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.io.File

/**
 * [CookieStoreProvider] 的 Native 鸿蒙 (OHOS) 真实文件持久化实现。
 *
 * - 持久 cookie: 落盘到 `{AppFilesDirs.filesDir}/cookies.json` (JSON), 进程重启仍保留
 * - session cookie: in-memory (进程退出即丢失, 对应 app 端 CacheManager "${domain}_session_cookie")
 * - 原子写: 先写 .tmp 再 renameTo (避免半写文件被读)
 * - 线程安全: synchronized 块保护内存 Map (Native 端不支持 @Synchronized)
 *
 * 模式参考 [io.legado.app.help.NativeSourceCacheProvider] (同 nativeMain 文件持久化模式) /
 * iOS 端 [IosCookieStoreProvider] / JVM 端 [JvmCookieStoreProvider]。
 */
class NativeCookieStoreProvider : CookieStoreProvider {

    private val lock = Any()

    // 持久 cookie: Map<二级域名, Map<cookieKey, cookieValue>>, 懒加载自文件
    @Volatile
    private var cookieStore: MutableMap<String, MutableMap<String, String>>? = null

    // session cookie (in-memory, 进程退出即丢失, 不落盘)
    private val sessionStore: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    private val cookieMapSerializer = MapSerializer(
        String.serializer(),
        MapSerializer(String.serializer(), String.serializer())
    )

    // cookie 文件路径 (懒加载, 首次访问时解析)
    private val cookieFilePath: String by lazy { resolveCookieFilePath() }

    override fun setCookie(url: String, cookie: String?) {
        if (!url.startsWith("http")) return
        val cookieStr = cookie ?: ""
        if (cookieStr.isBlank()) return
        val domain = extractDomain(url)
        synchronized(lock) {
            // 覆盖语义: 替换该域名下所有 cookie (与 iOS/JVM 端 setCookie 一致)
            ensureLoaded()[domain] = cookieToMap(cookieStr)
            asyncSave()
        }
    }

    override fun replaceCookie(url: String, cookie: String) {
        if (url.isBlank() || cookie.isBlank()) return
        val domain = extractDomain(url)
        synchronized(lock) {
            val store = ensureLoaded()
            // 合并语义: 按 key 合并, 新 cookie 覆盖同名 key (与 app 端 CookieStore.replaceCookie 一致)
            val existing = store[domain]
            if (existing == null) {
                store[domain] = cookieToMap(cookie)
            } else {
                existing.putAll(cookieToMap(cookie))
            }
            asyncSave()
        }
    }

    override fun getCookie(url: String): String {
        val domain = extractDomain(url)
        synchronized(lock) {
            val store = ensureLoaded()
            val persistent = store[domain]?.let { mapToCookie(it) } ?: ""
            val session = sessionStore[domain]?.let { mapToCookie(it) } ?: ""
            return mergeCookies(persistent, session) ?: ""
        }
    }

    override fun getKey(url: String, key: String): String {
        val cookie = getCookie(url)
        if (cookie.isBlank()) return ""
        // 直接解析不转 Map (与 app/iOS/JVM 一致)
        cookie.split(';').forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0 && pair.take(index).trim() == key) {
                return pair.substring(index + 1).trim()
            }
        }
        return ""
    }

    override fun removeCookie(url: String) {
        val domain = extractDomain(url)
        synchronized(lock) {
            ensureLoaded().remove(domain)
            sessionStore.remove(domain)
            asyncSave()
        }
    }

    override fun removeCookie(url: String, key: String) {
        val domain = extractDomain(url)
        synchronized(lock) {
            ensureLoaded()[domain]?.remove(key)
            sessionStore[domain]?.remove(key)
            asyncSave()
        }
    }

    override fun getCookieNoSession(url: String): String {
        val domain = extractDomain(url)
        synchronized(lock) {
            return ensureLoaded()[domain]?.let { mapToCookie(it) } ?: ""
        }
    }

    override fun getSessionCookie(domain: String): String? {
        synchronized(lock) {
            return sessionStore[domain]?.let { mapToCookie(it) }
        }
    }

    override fun clear() {
        synchronized(lock) {
            cookieStore = mutableMapOf()
            sessionStore.clear()
            runCatching { File(cookieFilePath).delete() }
        }
    }

    // ===== 内部方法 =====

    // 懒加载 cookie 文件到内存 (调用方需持有 lock)
    private fun ensureLoaded(): MutableMap<String, MutableMap<String, String>> {
        cookieStore?.let { return it }
        val loaded = loadFromFile()
        cookieStore = loaded
        return loaded
    }

    private fun loadFromFile(): MutableMap<String, MutableMap<String, String>> {
        return runCatching {
            val file = File(cookieFilePath)
            if (!file.exists()) return mutableMapOf()
            val content = file.readText(Charsets.UTF_8)
            if (content.isBlank()) return mutableMapOf()
            KS_JSON.decodeFromString(cookieMapSerializer, content)
                .mapValuesTo(mutableMapOf()) { (_, v) -> v.toMutableMap() }
        }.getOrDefault(mutableMapOf())
    }

    // 异步保存到文件 (调用方需持有 lock, 内部做防御性快照避免并发修改)
    private fun asyncSave() {
        val store = cookieStore ?: return
        val snapshot: Map<String, Map<String, String>> = store.mapValues { it.value.toMap() }
        val path = cookieFilePath
        GlobalScope.launch(Dispatchers.IO) {
            saveToFile(path, snapshot)
        }
    }

    // 原子写: 先写 .tmp 再 renameTo (与 nativeMain FileDownloader 一致)
    private fun saveToFile(path: String, store: Map<String, Map<String, String>>) {
        runCatching {
            val target = File(path)
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tmp = File("$path.tmp")
            tmp.writeText(KS_JSON.encodeToString(cookieMapSerializer, store), Charsets.UTF_8)
            // renameTo 失败兜底: 删除 tmp 避免残留
            if (!tmp.renameTo(target)) {
                tmp.delete()
            }
        }
    }

    // 解析 cookie 文件路径: {AppFilesDir}/cookies.json, 未注册回退当前目录
    // (AppFilesDirs 由各端 registerOhosAppFilesDir/registerIosAppFilesDir 注册,
    //  鸿蒙端 OhosAppFilesDir 内部已处理 OhosNativeBridge.getFilesDir() ?: user.dir 回退)
    private fun resolveCookieFilePath(): String {
        val filesDir = runCatching { AppFilesDirs.get().filesDir }.getOrNull() ?: "."
        return if (filesDir.endsWith("/")) "${filesDir}cookies.json" else "$filesDir/cookies.json"
    }

    // 提取二级域名: 复用 NetworkUtils.getSubDomain 取 host, 取最后两段近似 eTLD+1
    // (IP / 短域名保持原样; 简单实现, 不处理 .co.uk 等多级 TLD)
    private fun extractDomain(url: String): String {
        val host = NetworkUtils.getSubDomain(url)
        val parts = host.split('.')
        return when {
            parts.size <= 2 -> host
            // 末段为数字视为 IP, 保持原样
            parts.last().toIntOrNull() != null -> host
            else -> parts.takeLast(2).joinToString(".")
        }
    }
}

/**
 * 鸿蒙 (OpenHarmony) 端默认 [CookieStoreProvider] 注册入口 (真实文件持久化实现)。
 *
 * 在 [io.legado.app.help.config.registerOhosProviders] 中调用一次, 把 [NativeCookieStoreProvider]
 * (基于 kotlin.io.File + JSON) 注册到 [CookieStoreProviders], 让 shared 内
 * `CookieStoreProviders.get()` 返回真实实现, cookie 持久化到 `{AppFilesDirs.filesDir}/cookies.json`。
 *
 * 对应 app 端 `registerAndroidCookieStoreProvider` /
 * desktop `registerDefaultJvmCookieStoreProvider` /
 * iOS `registerDefaultIosCookieStoreProvider`。
 */
fun registerDefaultOhosCookieStoreProvider() {
    CookieStoreProviders.register(NativeCookieStoreProvider())
}
