package io.legado.app.help.extension

import io.legado.app.App
import io.legado.app.help.extension.model.MangaExtensionRepo
import io.legado.app.help.extension.repo.RepoHelper
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 漫画扩展子系统的偏好存取。三个键均以 mangaExtension 前缀自持, 不进 PreferKey
 * 常量表 (零冲突); 值全部是 JSON 字符串, 备份侧 config.json 按 key 全量 dump
 * (纯黑名单), 自动随备份/恢复进出, 恢复后由 MangaExtensionManager.onRestoreFinished()
 * 重载内存态。
 */
internal object ExtensionPrefs {

    private const val KEY_REPOS = "mangaExtensionRepos"
    private const val KEY_TRUSTED_SIGNATURES = "mangaExtensionTrustedSignatures"
    private const val KEY_SOURCE_ENABLED = "mangaExtensionSourceEnabled"
    private const val KEY_LANGUAGES = "mangaExtensionLanguages"

    private val repoListSerializer = ListSerializer(MangaExtensionRepo.serializer())
    private val stringSetSerializer = ListSerializer(String.serializer())
    private val sourceEnabledSerializer = MapSerializer(String.serializer(), Boolean.serializer())

    /** 仓库全表: 默认仓库两枚 (签名指纹硬编码, 不可移除) + 用户自建仓库。 */
    fun getRepos(): List<MangaExtensionRepo> {
        val defaults = listOf(RepoHelper.defaultRepo, RepoHelper.defaultAnimeRepo)
        val userRepos = decodeUserRepos()
            .filter { repo -> defaults.none { it.indexUrl == repo.indexUrl } }
        return defaults + userRepos
    }

    fun addUserRepo(repo: MangaExtensionRepo) {
        val repos = decodeUserRepos().toMutableList()
        repos.removeAll { it.indexUrl == repo.indexUrl }
        repos.add(repo)
        App.instance.putPrefString(KEY_REPOS, GSON.encodeToString(repoListSerializer, repos))
    }

    fun removeRepo(indexUrl: String) {
        val repos = decodeUserRepos().filterNot { it.indexUrl == indexUrl }
        App.instance.putPrefString(KEY_REPOS, GSON.encodeToString(repoListSerializer, repos))
    }

    private fun decodeUserRepos(): List<MangaExtensionRepo> {
        val json = App.instance.getPrefString(KEY_REPOS) ?: return emptyList()
        return runCatching { GSON.decodeFromString(repoListSerializer, json) }.getOrNull().orEmpty()
    }

    /** 用户确认可信的扩展, 集合语义 "pkgName:versionCode:signatureHash"。 */
    fun getTrustedSignatures(): Set<String> {
        val json = App.instance.getPrefString(KEY_TRUSTED_SIGNATURES) ?: return emptySet()
        return runCatching {
            GSON.decodeFromString(stringSetSerializer, json).toSet()
        }.getOrNull().orEmpty()
    }

    fun setTrustedSignatures(signatures: Set<String>) {
        App.instance.putPrefString(KEY_TRUSTED_SIGNATURES, GSON.encodeToString(stringSetSerializer, signatures.toList()))
    }

    /** 展示语言过滤 (可用列表; 空集=全部语言, 匹配语义 ext.lang ∈ 集合 || "all" ∈ 集合)。 */
    fun getSelectedLanguages(): Set<String> {
        val json = App.instance.getPrefString(KEY_LANGUAGES) ?: return emptySet()
        return runCatching {
            GSON.decodeFromString(stringSetSerializer, json).toSet()
        }.getOrNull().orEmpty()
    }

    fun setSelectedLanguages(languages: Set<String>) {
        App.instance.putPrefString(
            KEY_LANGUAGES,
            GSON.encodeToString(stringSetSerializer, languages.toList()),
        )
    }

    /** 源启用表 (稀疏存储, 缺省启用), key 为源 id 的字符串形式。 */
    fun getSourceEnabled(): Map<Long, Boolean> {
        val json = App.instance.getPrefString(KEY_SOURCE_ENABLED) ?: return emptyMap()
        val decoded = runCatching {
            GSON.decodeFromString(sourceEnabledSerializer, json)
        }.getOrNull().orEmpty()
        return decoded.mapNotNull { (key, value) -> key.toLongOrNull()?.let { it to value } }.toMap()
    }

    fun isSourceEnabled(sourceId: Long): Boolean = getSourceEnabled()[sourceId] ?: true

    fun setSourceEnabled(sourceId: Long, enabled: Boolean) {
        val map = getSourceEnabled().toMutableMap()
        if (enabled) {
            map.remove(sourceId)
        } else {
            map[sourceId] = false
        }
        val json = GSON.encodeToString(sourceEnabledSerializer, map.mapKeys { (key, _) -> key.toString() })
        App.instance.putPrefString(KEY_SOURCE_ENABLED, json)
    }
}
