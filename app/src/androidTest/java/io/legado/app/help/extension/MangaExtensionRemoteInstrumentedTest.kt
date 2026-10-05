// Copyright The Keiyoushi Contributors. Apache-2.0.
// 真实远程仓库全链路验证: 索引拉取解析 (keiyoushi index.pb / yuzono min.json) →
// 扩展 APK 下载 (GitHub Releases/仓库) → 私有扩展安装 → 加载实例化 →
// 真实站点搜索/详情/目录/正文图片解析与下载 (漫画) / 剧集与视频流提取 (视频)。
package io.legado.app.help.extension

import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.net.ssl.SSLHandshakeException
import org.junit.Assume
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.extension.AndroidMangaExtensionHost
import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.help.extension.util.ExtensionLoader
import io.legado.app.help.extension.MangaExtensionHostProviders
import io.legado.app.constant.BookSourceType
import io.legado.app.model.anime.AnimePluginSources
import io.legado.app.model.anime.AnimeSourceMapper
import io.legado.app.model.anime.VideoSourceDelegateImpl
import io.legado.app.model.manga.MangaPluginSources
import io.legado.app.model.manga.MangaSourceDelegateImpl
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 测试样本取自远程仓库而非打进测试包: 一次运行同时覆盖索引解析与 APK 下载安装两条链路,
 * 并以真实站点数据压满 搜索→详情→目录→正文(图片) 解析闭环。
 */
@RunWith(AndroidJUnit4::class)
class MangaExtensionRemoteInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun installRemote(name: String): Pair<MangaExtension.Loaded, io.legado.app.data.entities.BookSource> {
        MangaExtensionHostProviders.register(AndroidMangaExtensionHost(context))
        MangaExtensionManager.init()
        MangaExtensionManager.findAvailableExtensions()
        val target = MangaExtensionManager.availableExtensions.value
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: error("远程仓库未找到扩展: $name")
        val steps = MangaExtensionManager.installExtension(target).toList()
        assertTrue("安装未完成: ${steps.last()}", steps.last() is InstallStep.Installed)
        // 触发注册表重扫并等待其完成 (生产路径由安装广播触发, 测试显式驱动)
        MangaExtensionManager.reloadExtensions()
        var loaded: MangaExtension.Loaded? = null
        kotlinx.coroutines.withTimeout(10_000) {
            while (loaded == null) {
                loaded = MangaExtensionManager.loadedExtensions.value.values
                    .filterIsInstance<MangaExtension.Loaded>()
                    .firstOrNull { it.pkgName == target.pkgName }
                if (loaded == null) kotlinx.coroutines.delay(200)
            }
        }
        val loadedExt = loaded!!
        val source = when (target.repo.kind) {
            io.legado.app.help.extension.model.RepoKind.MANGA ->
                MangaPluginSources.buildVirtualSource(loadedExt.sources.first(), loadedExt.pkgName)
            io.legado.app.help.extension.model.RepoKind.ANIME ->
                AnimePluginSources.buildVirtualSource(loadedExt.animeSources.first(), loadedExt.pkgName)
        }
        return loadedExt to source
    }

    private fun virtualSourceOf(loaded: MangaExtension.Loaded): io.legado.app.data.entities.BookSource =
        when (loaded.animeSources.size) {
            0 -> MangaPluginSources.buildVirtualSource(loaded.sources.first(), loaded.pkgName)
            else -> AnimePluginSources.buildVirtualSource(loaded.animeSources.first(), loaded.pkgName)
        }

    @Test
    fun fullPipelineManga_realParsingAndImage() = runBlocking {
        try {
            fullPipelineMangaBody()
        } catch (e: SSLHandshakeException) {
            // 源站 TLS 握手被真机网络中断 (环境因素, 非代码缺陷): 按跳过处理, 正常网络下照跑全链路
            Assume.assumeTrue("源站 TLS 握手被中断(网络环境): ${e.message}", false)
        }
    }

    private suspend fun fullPipelineMangaBody() {
        val (ext, bookSource) = installRemote("Comic Fury")
        assertTrue(ext.sources.isNotEmpty())

        // 搜索
        val page = MangaSourceDelegateImpl.getBookListAwait(bookSource, "test", 1)
        assertTrue("搜索无结果", page.books.isNotEmpty())

        // 详情 (字段写回)
        val hit = page.books.first()
        val book = Book().apply {
            bookUrl = hit.bookUrl
            origin = hit.origin
            name = hit.name
            type = BookType.image
        }
        val info = MangaSourceDelegateImpl.getBookInfoAwait(bookSource, book, canReName = true)
        assertTrue("详情标题为空", info.name.isNotBlank())

        // 目录
        val chapters = MangaSourceDelegateImpl.getChapterListAwait(bookSource, info).getOrThrow()
        assertTrue("目录为空", chapters.isNotEmpty())

        // 正文 (真实页面图片解析, 压 org.jsoup 门面与 <img src> 提取链)
        val content = MangaSourceDelegateImpl.getContentAwait(bookSource, info, chapters.first())
        val imgUrls = Regex("""src="([^"]+)"""").findAll(content).map { it.groupValues[1] }.toList()
        assertTrue("正文无图片 URL: ${content.take(200)}", imgUrls.isNotEmpty())

        // 真实下载首图 (验证防盗链头经虚拟源 header 注入)
        val request = Request.Builder().url(imgUrls.first()).apply {
            bookSource.getHeaderMap()?.forEach { (k, v) -> header(k, v) }
        }.build()
        OkHttpClient().newCall(request).execute().use { resp ->
            assertTrue("首图 HTTP ${resp.code}", resp.isSuccessful)
            val bytes = resp.body.bytes()
            assertTrue("首图字节过小: ${bytes.size}", bytes.size > 1024)
        }
    }

    @Test
    fun fullPipelineAnime_realEpisodeAndVideo() = runBlocking {
        // AnimeOnsen 搜索接口实测返回 401 (站点鉴权), 选同仓库可匿名访问的 iyf 源
        val (ext, bookSource) = installRemote("iyf")
        assertEquals(BookSourceType.video, bookSource.bookSourceType)
        val source = ext.animeSources.filterIsInstance<AnimeHttpSource>().first()

        val searchPage = source.getSearchAnime(1, "test", AnimeFilterList())
        assertTrue("搜索无结果", searchPage.animes.isNotEmpty())

        val update = source.getAnimeEpisodeUpdate(
            searchPage.animes.first(),
            emptyList(),
            fetchDetails = true,
            fetchEpisodes = true,
        )
        assertTrue("剧集为空", update.episodes.isNotEmpty())

        val book = Book().apply {
            bookUrl = AnimeSourceMapper.bookUrlOf(source.id, update.anime.url)
            origin = AnimeSourceMapper.sourceUrlOf(source.id)
            name = update.anime.title
            type = BookType.video
        }
        val chapters = VideoSourceDelegateImpl.getChapterListAwait(bookSource, book).getOrThrow()
        assertTrue("目录为空", chapters.isNotEmpty())
        val content = VideoSourceDelegateImpl.getContentAwait(bookSource, book, chapters.first())
        assertTrue("视频内容串应为 videoUrl + headers 链接参数: ${content.take(120)}", content.contains("://"))
        val videos = source.getVideoList(update.episodes.first())
        assertTrue("视频流列表为空", videos.isNotEmpty())
        assertTrue(videos.first().videoUrl.isNotBlank())
    }

    @Test
    fun uninstallRemovesPrivateFile() = runBlocking {
        val (ext, _) = installRemote("Comic Fury")
        ExtensionLoader.uninstallPrivateExtension(context, ext.pkgName)
        val remaining = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<MangaExtension.Loaded>()
            .filter { it.pkgName == ext.pkgName }
        assertEquals(0, remaining.size)
    }
}
