// 真实扩展业务方法 JVM 深度集成测试 (加载断言之上的一层, 见 JvmExtensionLoaderTest):
// NoSuchMethodError/NoClassDefFoundError/InstantiationError 只在真正调用扩展业务方法时暴露
// (方法表链接发生在调用点), 仅断言"加载成功"验不出宿主 shim/门面的 API 面缺口。
//
// 样本来源优先级: ① 真机拉取的 .ext 文件目录 (LEGADO_REAL_EXTS_DIR, 缺省
// D:/Documents/Android_Studio/_tmp_exts, 不进仓库) ② build/test-ext 下载缓存
// ③ 远程仓库索引直链下载。网络不可达时仅本地样本可跑。
package io.legado.desktop.extension

import android.app.Application
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.network.ExtensionInterceptorsProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import io.legado.desktop.TestNetwork
import io.legado.desktop.help.dex.CtorSiteFixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class RealExtensionJvmDeepTest {

    companion object {

        private val LOCAL_EXTS_DIR =
            System.getenv("LEGADO_REAL_EXTS_DIR") ?: "D:/Documents/Android_Studio/_tmp_exts"
        private const val KEIYOUSHI_INDEX_PB =
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
        private const val YUZONO_ANIME_INDEX =
            "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"
        private const val COMICFURY_PKG = "eu.kanade.tachiyomi.extension.all.comicfury"
        private const val IYF_PKG = "eu.kanade.tachiyomi.animeextension.zh.iyf"
        private const val JINMAN_PKG = "eu.kanade.tachiyomi.extension.zh.jinmantiantang"

        // 显式声明接口类型: Injekt 按 reified 类型落键, 匿名对象类型会注册错键
        private val desktopInterceptorsProvider: ExtensionInterceptorsProvider = object : ExtensionInterceptorsProvider {
            override fun clientInterceptors(
                context: android.content.Context,
                cookieJar: eu.kanade.tachiyomi.network.interceptor.ChallengeCookieResolver,
                defaultUserAgent: () -> String,
            ) = listOf(
                UncaughtExceptionInterceptor(),
                UserAgentInterceptor(defaultUserAgent),
                eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor(),
            )

            override fun cloudflareClientInterceptors(
                context: android.content.Context,
                cookieJar: eu.kanade.tachiyomi.network.interceptor.ChallengeCookieResolver,
                defaultUserAgent: () -> String,
            ) = listOf(
                UncaughtExceptionInterceptor(),
                UserAgentInterceptor(defaultUserAgent),
                eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor(),
            )
        }

        private val applicationStub: Application = object : Application() {}

        private val cacheDir = File("build/test-ext").apply { mkdirs() }

        private val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        private fun getBytes(url: String): ByteArray {
            TestNetwork.requireSamples(url)
            return http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}: $url" }
                response.body.bytes()
            }
        }

        private fun gunzipIfGzipped(bytes: ByteArray): ByteArray =
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            } else {
                bytes
            }

        /** keiyoushi index.pb (gzip protobuf) → 条目自带 GitHub Releases 直链, 与生产 parseProtoIndex 同源。 */
        private fun keiyoushiApkUrl(pkgArtifact: String): String {
            val pb = gunzipIfGzipped(getBytes(KEIYOUSHI_INDEX_PB)).decodeToString()
            val url = Regex(
                "https://github.com/keiyoushi/extensions/releases/download/[a-zA-Z0-9-]+/$pkgArtifact[a-z0-9.-]*\\.apk",
            ).findAll(pb).lastOrNull()?.value
            return checkNotNull(url) { "keiyoushi index.pb 中未找到 $pkgArtifact 条目" }
        }

        private fun iyfApkUrl(): String {
            val index = getBytes(YUZONO_ANIME_INDEX).decodeToString()
            val apk = Regex("\"pkg\":\"$IYF_PKG\",\"apk\":\"([^\"]+)\"").find(index)
                ?.groupValues?.get(1)
                ?: error("yuzono index.min.json 中未找到 iyf 条目")
            return YUZONO_ANIME_INDEX.substringBeforeLast('/') + "/apk/$apk"
        }

        /** 样本解析: 本地真机 .ext → 下载缓存 → 远程索引。缓存命中后不再触网。 */
        private fun sampleApk(localFileName: String?, apkUrl: () -> String): File {
            if (localFileName != null) {
                val local = File(LOCAL_EXTS_DIR, localFileName)
                if (local.isFile && local.length() > 1024) return local
            }
            val url = apkUrl()
            val target = File(cacheDir, url.substringAfterLast('/'))
            if (!target.isFile || target.length() == 0L) {
                target.writeBytes(getBytes(url))
            }
            check(target.length() > 1024) { "样本下载不完整: ${target.path}" }
            return target
        }

        private fun load(apk: File): JvmExtension.Loaded {
            val result = JvmExtensionLoader.load(
                apk,
                File(cacheDir, "jars-" + apk.nameWithoutExtension + "-" + System.nanoTime()),
            )
            if (result !is JvmExtension.Loaded) {
                val failed = result as JvmExtension.NotLoaded
                System.err.println("=== LOAD FAILED ${failed.pkgName} reason=${failed.reason} msg=${failed.message}")
                System.err.println(failed.stackTrace)
                error("加载失败: ${failed.reason} ${failed.message}")
            }
            return result
        }

        private fun localOrCached(localFileName: String?, cachedNamePattern: String, apkUrl: () -> String): File {
            if (localFileName != null) {
                val local = File(LOCAL_EXTS_DIR, localFileName)
                if (local.isFile && local.length() > 1024) return local
            }
            cacheDir.listFiles { f -> f.name.matches(Regex(cachedNamePattern)) }
                ?.maxByOrNull { it.lastModified() }
                ?.takeIf { it.length() > 1024 }
                ?.let { return it }
            return sampleApk(localFileName, apkUrl)
        }

        @BeforeClass
        @JvmStatic
        fun registerExtensionCompat() {
            // 与 Android 端 registerExtensionCompat 逐项对齐; 与 JvmExtensionLoaderTest 重复注册幂等
            Injekt.addSingleton(applicationStub)
            Injekt.addSingletonFactory<Json> {
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            }
            Injekt.addSingletonFactory { desktopInterceptorsProvider }
            Injekt.addSingletonFactory { NetworkHelper(applicationStub) }
            // 浏览器 UA: 真宿主 UA 为 WebView 形态, 站点按 UA 差异化响应 (gzip/内容协商),
            // Android 风格缺省 UA 会拿到与真机不同的响应形态
            io.legado.app.help.UserAgentProviders.impl =
                io.legado.app.help.UserAgentProvider {
                    "Mozilla/5.0 (Linux; Android 13; Redmi 5 Plus) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
                }
        }
    }

    @Test
    fun `comicfury getPopularManga 走宿主 jsoup 门面解析真站`() {
        val ext = load(
            localOrCached(
                null,
                """tachiyomi-all\.comicfury.*\.apk""",
            ) { keiyoushiApkUrl("tachiyomi-all\\.comicfury") },
        )
        assertEquals(COMICFURY_PKG, ext.pkgName)
        val source = ext.sources.first() as HttpSource
        runBlocking {
            try {
                val page = withTimeout(120_000) { source.getPopularManga(1) }
                assertTrue("popular 列表不应为空", page.mangas.isNotEmpty())
                assertTrue(page.mangas.all { it.title.isNotBlank() && it.url.isNotBlank() })
            } catch (t: Throwable) {
                t.printStackTrace()
                throw t
            }
        }
    }

    @Test
    fun `comicfury getMangaUpdate 走宿主解析深链`() {
        val ext = load(
            localOrCached(
                null,
                """tachiyomi-all\.comicfury.*\.apk""",
            ) { keiyoushiApkUrl("tachiyomi-all\\.comicfury") },
        )
        val source = ext.sources.first() as HttpSource
        runBlocking {
            try {
                val first = withTimeout(120_000) { source.getPopularManga(1) }.mangas.first()
                val update = withTimeout(120_000) {
                    source.getMangaUpdate(first, chapters = emptyList(), fetchDetails = true, fetchChapters = true)
                }
                assertTrue("详情标题不应为空", update.manga.title.isNotBlank())
                assertTrue("章节列表不应为空", update.chapters.isNotEmpty())
                assertTrue(update.chapters.all { it.url.isNotBlank() })
            } catch (t: Throwable) {
                t.printStackTrace()
                throw t
            }
        }
    }

    @Test
    fun `jinmantiantang setupPreferenceScreen 走 shim context 链`() {
        // 禁漫的 setupPreferenceScreen 首句即 screen.context —— 宿主 shim 缺 getContext 时
        // 这里抛 NoSuchMethodError (真机禁漫/哔咔"没有可配置项"的同源路径)
        val ext = load(
            localOrCached(
                null,
                """tachiyomi-zh\.jinmantiantang.*\.apk""",
            ) { keiyoushiApkUrl("tachiyomi-zh\\.jinmantiantang") },
        )
        assertEquals(JINMAN_PKG, ext.pkgName)
        val source = ext.sources.first()
        assertTrue("禁漫源应实现 ConfigurableSource, 实为 ${source.javaClass}", source is ConfigurableSource)
        val screen = PreferenceScreen(applicationStub)
        runBlocking {
            withTimeout(60_000) { (source as ConfigurableSource).setupPreferenceScreen(screen) }
        }
        assertTrue(
            "禁漫偏好 screen 应有配置项, 实得 ${screen.getPreferences().size} 个",
            screen.getPreferences().isNotEmpty(),
        )
    }

    @Test
    fun `iyf getPopularAnime 与 getAnimeEpisodeUpdate 走宿主 jsoup 深解析`() {
        val ext = load(
            localOrCached(
                "eu.kanade.tachiyomi.animeextension.zh.iyf.ext",
                """aniyomi-zh\.iyf.*\.apk""",
            ) { iyfApkUrl() },
        )
        assertEquals(IYF_PKG, ext.pkgName)
        val source = ext.animeSources.first() as AnimeCatalogueSource
        runBlocking(Dispatchers.Default) {
            try {
                val popular = withTimeout(120_000) { source.getPopularAnime(1) }
                assertTrue("popular 列表不应为空", popular.animes.isNotEmpty())
                val first = popular.animes.first()
                val update = withTimeout(120_000) {
                    source.getAnimeEpisodeUpdate(
                        anime = first,
                        episodes = emptyList(),
                        fetchDetails = true,
                        fetchEpisodes = true,
                    )
                }
                assertTrue("详情标题不应为空", update.anime.title.isNotBlank())
                assertTrue("集数列表不应为空", update.episodes.isNotEmpty())
            } catch (t: Throwable) {
                t.printStackTrace()
                throw t
            }
        }
    }

    @Test
    fun `jinmantiantang 接收者构造还原类强制初始化通过 JVM 校验`() {
        // dex 里 NEW 类型与构造器 owner 不一致的调用点 (如 <clinit> 里 new-instance g0 +
        // invoke-direct Object.<init>) 是 dex2jar 产出坏字节码的必现处 —— HotSpot 验证
        // 抛 VerifyError, 仅在类首次初始化时暴露, 业务请求断言覆盖不到
        val apk = localOrCached(
            null,
            """tachiyomi-zh\.jinmantiantang.*\.apk""",
        ) { keiyoushiApkUrl("tachiyomi-zh\\.jinmantiantang") }
        val ext = load(apk)
        assertEquals(JINMAN_PKG, ext.pkgName)
        val targets = ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && Regex("""classes\d*\.dex""").matches(it.name.substringAfterLast('/')) }
                .sortedBy { it.name }
                .map { zip.getInputStream(it).use { input -> input.readBytes() } }
                .flatMap { CtorSiteFixer.affectedClasses(it) }
                .toSet()
        }
        assertTrue("样本应含接收者构造还原类, 实得 $targets", targets.isNotEmpty())
        targets.forEach { name ->
            Class.forName(name, true, ext.classLoader)
        }
    }

    @Test
    fun `dragonballmultiverse 真机样本 getPopularManga 走宿主解析`() {
        val ext = load(
            localOrCached(
                "eu.kanade.tachiyomi.extension.all.dragonballmultiverse.ext",
                """tachiyomi-all\.dragonballmultiverse.*\.apk""",
            ) { keiyoushiApkUrl("tachiyomi-all\\.dragonballmultiverse") },
        )
        assertEquals("eu.kanade.tachiyomi.extension.all.dragonballmultiverse", ext.pkgName)
        val source = ext.sources.first() as HttpSource
        runBlocking {
            try {
                val page = withTimeout(120_000) { source.getPopularManga(1) }
                assertTrue("popular 列表不应为空", page.mangas.isNotEmpty())
                assertTrue(page.mangas.all { it.title.isNotBlank() && it.url.isNotBlank() })
            } catch (t: Throwable) {
                t.printStackTrace()
                throw t
            }
        }
    }
}
