package io.legado.app.help.tvbox

import com.github.catvod.Init
import com.github.catvod.crawler.Spider
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Crypto
import com.github.catvod.utils.Path
import io.legado.app.constant.AppLog
import okhttp3.Request
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * TVBox spider jar 装载器 (装载/缓存模式对齐 FongMi/TV JarLoader):
 *
 * - jar 规格串 "url;md5;<md5|md5 url>" (分界符逐字为 ";md5;", md5 可为远程 URL);
 * - jar 落 cacheDir/jar/<md5(规格串)>.jar, 命中缓存且 md5 匹配时免下载;
 * - 类加载器经 [TvBoxPlatforms.newJarClassLoader] 平台注入 (Android=DexClassLoader,
 *   父加载器为宿主; 桌面=URLClassLoader), jar 内引用的壳类 (com.github.catvod.*)
 *   由宿主 classpath 提供 —— tachiyomi 兼容层同款 compileOnly 面;
 * - 装载后按生态惯例回调 jar 内 com.github.catvod.spider.Init.init(Context) (存在时);
 * - 站点类按 api "csp_Xxx" → "com.github.catvod.spider.Xxx" 实例化, 写 siteKey 后 init(context, ext)。
 *
 * 全部方法阻塞式 IO, 调用方须在 IoDispatcher 上。
 */
internal object TvBoxJarLoader {

    private const val JAR_INIT_CLASS = "com.github.catvod.spider.Init"
    private const val JAR_PROXY_CLASS = "com.github.catvod.spider.Proxy"
    private const val SPIDER_PACKAGE = "com.github.catvod.spider."

    private val loaders = ConcurrentHashMap<String, ClassLoader>()
    private val spiders = ConcurrentHashMap<String, Spider>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** jar 自带静态 Proxy 的 proxy(Map) 方法缓存 (FongMi JarLoader.methods 同语义), 按 jarKey 索引。 */
    private val methods = ConcurrentHashMap<String, Method>()

    /** 最近实例化站点所属的 jar (FongMi JarLoader.recent 同语义, proxy 分发优先走它)。 */
    @Volatile
    private var recent: String? = null

    /**
     * 实例化站点 Spider; api 非 csp_ 前缀返回 null (JS/Python/CMS 站点不走本装载器)。
     * 缺依赖类的 LinkageError 收敛为带类名的可读错误 (见 [jarLinkageFailure]), 其余异常原样抛出,
     * 由委派层收敛为取数错误。
     */
    fun getSpider(site: TvBoxSite, jarSpec: String): Spider? {
        Init.set(TvBoxPlatforms.get().appContext)
        if (!site.isJarSpider) return null
        val jarKey = Crypto.md5(jarSpec)
        val spKey = "$jarKey#${site.key}"
        spiders[spKey]?.let { return it }
        val lock = locks.computeIfAbsent(spKey) { Any() }
        synchronized(lock) {
            spiders[spKey]?.let { return it }
            val loader = loaderFor(jarSpec)
            val name = site.api.removePrefix("csp_")
            val spider = try {
                val cls = try {
                    loader.loadClass(SPIDER_PACKAGE + name)
                } catch (_: ClassNotFoundException) {
                    // 生态外 jar 可能用全限定类名, 兜底直查
                    loader.loadClass(name)
                }
                val instance = cls.getDeclaredConstructor().newInstance() as Spider
                instance.siteKey = site.key
                instance.init(TvBoxPlatforms.get().appContext, site.ext)
                instance
            } catch (e: LinkageError) {
                // 缺依赖类/字节码坏/native 链接失败: 收敛成带类名的可读错误 (Error 族裸穿会淹没原因)
                jarLinkageFailure("TVBox spider 装载失败 (${site.name} / csp_$name)", e)
            }
            recent = jarKey
            spiders[spKey] = spider
            return spider
        }
    }

    /** 按站点 key 取已实例化的 Spider (key 形态 "jarKey#siteKey")。 */
    fun spiderBySiteKey(key: String): Spider? =
        spiders.entries.firstOrNull { it.key.endsWith("#$key") }?.value

    /** /proxy 分发到 jar 自带静态 Proxy (FongMi JarLoader.proxy 同语义: recent 优先, 其余兜底, 首个非空即用)。 */
    fun proxyDispatch(params: Map<String, String>): Array<Any?>? {
        val primary = recent?.let { methods[it] }
        proxyInvoke(primary, params)?.let { return it }
        for ((jarKey, method) in methods) {
            if (jarKey == recent) continue
            proxyInvoke(method, params)?.let { return it }
        }
        return null
    }

    // jar 契约保证返回 Object[]{code, mime, stream[, headers]}, 反射 erased 形态只能不检查转换
    @Suppress("UNCHECKED_CAST")
    private fun proxyInvoke(method: Method?, params: Map<String, String>): Array<Any?>? =
        runCatching { method?.invoke(null, params) as? Array<Any?> }
            .onFailure { AppLog.put("TVBox jar 静态 Proxy 调用失败", it) }
            .getOrNull()

    /** 缓存 jar 自带 com.github.catvod.spider.Proxy 的 proxy(Map) 静态方法 (FongMi invokeProxy 同语义)。 */
    private fun invokeJarProxy(loader: ClassLoader, jarKey: String) {
        try {
            methods[jarKey] = loader.loadClass(JAR_PROXY_CLASS)
                .getMethod("proxy", Map::class.java)
        } catch (e: ClassNotFoundException) {
            AppLog.put("TVBox jar 无自带静态 Proxy, do 分发不可用: ${e.message}")
        } catch (e: NoSuchMethodException) {
            AppLog.put("TVBox jar 静态 Proxy 缺 proxy(Map) 方法, do 分发不可用: ${e.message}")
        } catch (e: Throwable) {
            // 类存在但链接/初始化失败: 与"没有该能力"不是一回事, 必须留痕 (对齐 FongMi 的 printStackTrace)
            AppLog.put("TVBox jar 静态 Proxy 装载失败 (类存在但链接/初始化出错), do 分发不可用", e)
        }
    }

    /** 销毁全部 Spider 并清缓存 (换配置/退出场景)。 */
    fun clear() {
        spiders.values.forEach { runCatching { it.destroy() } }
        spiders.clear()
        loaders.clear()
        methods.clear()
        recent = null
        locks.clear()
    }

    private fun loaderFor(jarSpec: String): ClassLoader {
        val jarKey = Crypto.md5(jarSpec)
        loaders[jarKey]?.let { return it }
        val lock = locks.computeIfAbsent(jarKey) { Any() }
        synchronized(lock) {
            loaders[jarKey]?.let { return it }
            val spec = parseJarSpec(jarSpec)
            val file = Path.jar(jarSpec)
            val jarFile = when {
                spec.url.startsWith("http") -> {
                    // 命中缓存且 md5 匹配 (或未声明 md5) 时免下载, 否则重下并校验
                    if (Path.exists(file) && (spec.md5.isEmpty() || Crypto.equals(file, spec.md5))) {
                        file
                    } else {
                        download(spec, file)
                        file
                    }
                }
                spec.url.startsWith("file") -> Path.local(spec.url)
                file.exists() -> file
                else -> throw IllegalStateException("spider jar 不可达: $jarSpec")
            }
            if (!Path.exists(jarFile)) throw IllegalStateException("spider jar 下载失败: $jarSpec")
            // DexClassLoader 输入文件只读 (FongMi JarLoader 与私有扩展装载同约束, 部分 ROM 硬性要求)
            jarFile.setReadOnly()
            val loader = TvBoxPlatforms.get().newJarClassLoader(jarFile)
            invokeJarInit(loader)
            invokeJarProxy(loader, jarKey)
            loaders[jarKey] = loader
            return loader
        }
    }

    /** jar 规格串解析; md5 段可为远程 URL (FongMi JarLoader.parseJar 同语义)。 */
    private fun parseJarSpec(jarSpec: String): JarSpec {
        val texts = jarSpec.split(";md5;")
        var md5 = if (texts.size > 1) texts[1].trim() else ""
        if (md5.startsWith("http")) md5 = OkHttp.string(md5).trim()
        return JarSpec(url = texts[0].trim(), md5 = md5)
    }

    private fun download(spec: JarSpec, target: File) {
        val call = OkHttp.client().newCall(Request.Builder().url(spec.url).build())
        call.execute().use { resp ->
            check(resp.isSuccessful) { "spider jar 下载 HTTP ${resp.code}: ${spec.url}" }
            val body = resp.body
            val tmp = File(target.absolutePath + ".tmp")
            tmp.parentFile?.mkdirs()
            body.byteStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (spec.md5.isNotEmpty() && !Crypto.equals(tmp, spec.md5)) {
                tmp.delete()
                throw IllegalStateException("spider jar md5 校验失败: ${spec.url}")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * 生态惯例: jar 携带自初始化类时在装载后回调。
     *
     * "带了 Init 类/init 方法才调"是契约面, 缺失不视为错误也不留痕; 但 `init` 真的抛了
     * 就必须留痕 —— 静默吞掉会让 jar 初始化没生效这件事拖到很久以后以无关形态 (如空指针) 爆发
     * (对齐 FongMi JarLoader.invokeInit 的 `catch (Throwable) e.printStackTrace()` 行为,
     * 差异只在走 AppLog 而非控制台)。
     */
    private fun invokeJarInit(loader: ClassLoader) {
        try {
            val clz = loader.loadClass(JAR_INIT_CLASS)
            clz.getMethod("init", android.content.Context::class.java)
                .invoke(clz, TvBoxPlatforms.get().appContext)
        } catch (_: ClassNotFoundException) {
            // jar 不带 Init 类
        } catch (_: NoSuchMethodException) {
            // jar 带 Init 类但无 init(Context) 方法
        } catch (e: Throwable) {
            AppLog.put("TVBox jar 自带 Init.init 调用失败 (jar 自定义初始化未生效)", e)
        }
    }

    private data class JarSpec(val url: String, val md5: String)
}
