// 真实扩展 APK 在 JVM(desktop) 平台的加载回归。样本不进仓库, 运行时按生产 RepoHelper 同款
// 逻辑从远程仓库获取并缓存到 build/test-ext/:
// - 漫画 Comic Fury: keiyoushi index.pb (gzip protobuf, 条目自带 GitHub Releases 直链)
// - 视频 iyf: yuzono/anime-repo index.min.json (legacy 索引, apkUrl = indexUrl 去尾 + /apk/<apk>)
// 夹具镜像 Android 端 ExtensionCompat 的 Injekt 注册链 (扩展 dex 的 keiyoushi utils 在
// <clinit> 里 Injekt.get Application/Json)。
package io.legado.desktop.extension

import android.app.Application
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.network.ExtensionInterceptorsProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import java.io.File
import java.util.concurrent.TimeUnit

class JvmExtensionLoaderTest {

    companion object {

        private const val KEIYOUSHI_INDEX_PB =
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
        private const val YUZONO_ANIME_INDEX =
            "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"
        private const val COMICFURY_PKG = "eu.kanade.tachiyomi.extension.all.comicfury"
        private const val IYF_PKG = "eu.kanade.tachiyomi.animeextension.zh.iyf"

        /** 扩展客户端拦截器链 (JVM 端无 WebView, 无 Cloudflare 挑战段; Android 端在 ExtensionCompat 注册)。 */
        private val desktopInterceptorsProvider = object : ExtensionInterceptorsProvider {
            override fun clientInterceptors(
                context: android.content.Context,
                cookieJar: eu.kanade.tachiyomi.network.interceptor.ChallengeCookieResolver,
                defaultUserAgent: () -> String,
            ) = listOf(
                UncaughtExceptionInterceptor(),
                UserAgentInterceptor(defaultUserAgent),
            )

            override fun cloudflareClientInterceptors(
                context: android.content.Context,
                cookieJar: eu.kanade.tachiyomi.network.interceptor.ChallengeCookieResolver,
                defaultUserAgent: () -> String,
            ) = listOf(
                UncaughtExceptionInterceptor(),
                UserAgentInterceptor(defaultUserAgent),
            )
        }

        // 显式声明类型为 Application: Injekt 注册按 reified 类型落键, 匿名对象类型会注册错键
        private val applicationStub: Application = object : Application() {}

        private val cacheDir = File("build/test-ext").apply { mkdirs() }

        private val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        private fun getBytes(url: String): ByteArray =
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}: $url" }
                response.body.bytes()
            }

        private fun gunzipIfGzipped(bytes: ByteArray): ByteArray =
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            } else {
                bytes
            }

        /** keiyoushi index.pb → 解析条目自带 GitHub Releases 直链 (与生产 parseProtoIndex 同源)。 */
        private fun comicfuryApkUrl(): String {
            val pb = gunzipIfGzipped(getBytes(KEIYOUSHI_INDEX_PB)).decodeToString()
            val url = Regex(
                "https://github.com/keiyoushi/extensions/releases/download/[a-zA-Z0-9-]+/tachiyomi-all\\.comicfury[a-z0-9.-]*\\.apk",
            ).findAll(pb).lastOrNull()?.value
            return checkNotNull(url) { "keiyoushi index.pb 中未找到 comicfury 条目" }
        }

        /** yuzono legacy 索引 → apkUrl = indexUrl 去尾 + /apk/<apk> (与生产 toAvailable 同源)。 */
        private fun iyfApkUrl(): String {
            val index = getBytes(YUZONO_ANIME_INDEX).decodeToString()
            val apk = Regex("\"pkg\":\"$IYF_PKG\",\"apk\":\"([^\"]+)\"").find(index)
                ?.groupValues?.get(1)
                ?: error("yuzono index.min.json 中未找到 iyf 条目")
            return YUZONO_ANIME_INDEX.substringBeforeLast('/') + "/apk/$apk"
        }

        /** 样本落 build/test-ext 缓存 (按文件名缓存, 命中即复用, 保证一次拉取多次跑稳定)。 */
        private fun sampleApk(url: String): File {
            val name = url.substringAfterLast('/')
            val target = File(cacheDir, name)
            if (!target.isFile || target.length() == 0L) {
                target.writeBytes(getBytes(url))
            }
            check(target.length() > 1024) { "样本下载不完整: ${target.path}" }
            return target
        }

        private fun load(apkUrl: String): JvmExtension.Loaded {
            val result = JvmExtensionLoader.load(sampleApk(apkUrl))
            check(result is JvmExtension.Loaded) {
                val failed = result as? JvmExtension.NotLoaded
                "加载失败: ${failed?.reason} ${failed?.message ?: ""} ${failed?.stackTrace ?: ""}"
            }
            return result
        }

        @BeforeClass
        @JvmStatic
        fun registerExtensionCompat() {
            // 与 Android 端 registerExtensionCompat 逐项对齐: Application / Json / 拦截器链 / NetworkHelper
            Injekt.addSingleton(applicationStub)
            Injekt.addSingletonFactory<Json> {
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            }
            Injekt.addSingletonFactory { desktopInterceptorsProvider }
            Injekt.addSingletonFactory { NetworkHelper(applicationStub) }
        }
    }

    @Test
    fun `comicfury 从 keiyoushi 仓库加载出漫画 HttpSource`() {
        val ext = load(comicfuryApkUrl())
        assertEquals(COMICFURY_PKG, ext.pkgName)
        assertTrue(ext.sources.isNotEmpty())
        assertTrue(ext.animeSources.isEmpty())
        // 多语言变体多源扩展 (dex 里 Generated.createSources 一次造 14 个)
        ext.sources.forEach { source ->
            assertTrue("comicfury 源应为 HttpSource, 实为 ${source.javaClass}", source is HttpSource)
            val catalogue = source as? CatalogueSource
                ?: error("comicfury 源未实现 CatalogueSource: ${source.javaClass}")
            assertNotEquals(0L, catalogue.id)
            assertTrue(catalogue.name.isNotBlank())
            assertTrue(catalogue.lang.isNotBlank())
        }
        assertEquals(14, ext.sources.size)
        assertEquals(1.6, ext.libVersion, 0.0)
        assertTrue(ext.signatures.isNotEmpty())
    }

    @Test
    fun `iyf 从 yuzono 仓库加载出视频 AnimeSource`() {
        val ext = load(iyfApkUrl())
        assertEquals(IYF_PKG, ext.pkgName)
        assertTrue(ext.animeSources.isNotEmpty())
        assertTrue(ext.sources.isEmpty())
        val source = ext.animeSources.first()
        assertTrue("iyf 源应为 AnimeSource, 实为 ${source.javaClass}", source is AnimeSource)
        assertTrue(source is AnimeCatalogueSource)
        assertNotEquals(0L, source.id)
        assertTrue(source.name.isNotBlank())
        assertTrue(source.lang.isNotBlank())
        assertEquals(14.0, ext.libVersion, 0.0)
        assertTrue(ext.signatures.isNotEmpty())
    }
}
