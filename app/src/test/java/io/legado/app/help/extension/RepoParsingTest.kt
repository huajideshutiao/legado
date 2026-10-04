@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.legado.app.help.extension

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.extension.model.NetworkExtensionStore
import io.legado.app.help.extension.model.NetworkLegacyExtension
import io.legado.app.help.extension.model.RepoKind
import io.legado.app.help.extension.model.toAvailable
import io.legado.app.help.extension.repo.RepoHelper
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * 仓库索引解析契约: URL 规范化、老格式 JSON 映射、keiyoushi 真实 index.pb 解码。
 */
class RepoParsingTest {

    private fun repo(indexUrl: String, kind: RepoKind = RepoKind.MANGA) =
        io.legado.app.help.extension.model.MangaExtensionRepo(
            indexUrl = indexUrl,
            name = "test",
            signingKeyFingerprint = "a".repeat(64),
            kind = kind,
        )

    @Test
    fun `normalizeIndexUrl 仅接受完整 https 索引地址`() {
        assertEquals(
            "https://example.com/repo/index.min.json",
            RepoHelper.normalizeIndexUrl("https://example.com/repo/index.min.json"),
        )
        assertEquals(
            "https://example.com/repo/index.pb",
            RepoHelper.normalizeIndexUrl("https://example.com/repo/index.pb"),
        )
        assertThrows(NoStackTraceException::class.java) {
            RepoHelper.normalizeIndexUrl("example.com/repo")
        }
        assertThrows(NoStackTraceException::class.java) {
            RepoHelper.normalizeIndexUrl("https://example.com/repo/")
        }
        assertThrows(NoStackTraceException::class.java) {
            RepoHelper.normalizeIndexUrl("http://example.com/repo/index.min.json")
        }
    }

    @Test
    fun `老格式条目映射 apkUrl 拼接与名称前缀剥离`() {
        val json = """
            [{
              "name": "Aniyomi: Cycity",
              "pkg": "eu.kanade.tachiyomi.animeextension.zh.cycity",
              "apk": "aniyomi-zh.cycity-v14.3.apk",
              "lang": "zh",
              "code": 143,
              "version": "14.3",
              "nsfw": 0,
              "sources": [{"id": 777, "name": "Cycity", "lang": "zh", "baseUrl": "https://cycity.example"}]
            }]
        """.trimIndent()
        val items = Json.decodeFromString(
            ListSerializer(NetworkLegacyExtension.serializer()),
            json,
        ).map { it.toAvailable(repo("https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json", RepoKind.ANIME)) }

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("Cycity", item.name)
        assertEquals("eu.kanade.tachiyomi.animeextension.zh.cycity", item.pkgName)
        assertEquals(
            "https://raw.githubusercontent.com/yuzono/anime-repo/repo/apk/aniyomi-zh.cycity-v14.3.apk",
            item.apkUrl,
        )
        assertEquals(
            "https://raw.githubusercontent.com/yuzono/anime-repo/repo/icon/eu.kanade.tachiyomi.animeextension.zh.cycity.png",
            item.iconUrl,
        )
        assertEquals(14.0, item.libVersion, 0.0)
        assertEquals(143L, item.versionCode)
        assertEquals("zh", item.lang)
        assertEquals(777L, item.sources.single().id)
    }

    @Test
    fun `老格式条目空 sources 兜底与 nsfw 标记`() {
        val json = """
            [{
              "name": "Tachiyomi: SomeSource",
              "pkg": "eu.kanade.tachiyomi.extension.en.somesource",
              "apk": "tachiyomi-en.somesource-v1.4.2.apk",
              "lang": "en",
              "code": 142,
              "version": "1.4.2",
              "nsfw": 1,
              "sources": []
            }]
        """.trimIndent()
        val item = Json.decodeFromString(
            ListSerializer(NetworkLegacyExtension.serializer()),
            json,
        ).map { it.toAvailable(repo("https://example.com/repo/index.min.json")) }.single()

        assertEquals("SomeSource", item.name)
        assertEquals("https://example.com/repo/apk/tachiyomi-en.somesource-v1.4.2.apk", item.apkUrl)
        assertEquals(0, item.sources.single().id)
        assertEquals("SomeSource", item.sources.single().name)
    }

    @Test
    fun `keiyoushi 真实 index_pb 解码与字段抽查`() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("keiyoushi_index.pb")!!
            .readBytes()
        val bytes = when {
            raw.size >= 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte() ->
                GZIPInputStream(raw.inputStream()).readBytes()
            else -> raw
        }
        val store = ProtoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), bytes)

        assertEquals("Keiyoushi", store.name)
        assertEquals(
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            store.signingKey,
        )
        val extensions = store.extensionList?.extensions.orEmpty()
        assertTrue("extensions 应非空", extensions.isNotEmpty())
        val sample = extensions.first()
        assertTrue(sample.packageName.startsWith("eu.kanade.tachiyomi.extension."))
        assertTrue(sample.versionCode > 0)
        assertTrue(sample.resources?.apkUrl?.startsWith("https://github.com/keiyoushi/extensions/releases/") == true)
        val withSources = extensions.first { it.sources.isNotEmpty() }
        assertTrue(withSources.sources.first().id != 0L)
        assertTrue(withSources.sources.first().language.isNotBlank())
    }
}
