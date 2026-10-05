package io.legado.app.help.extension

import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.extension.model.MangaExtensionRepo
import io.legado.app.help.extension.repo.RepoHelper
import io.legado.app.utils.GSON
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 漫画扩展子系统的偏好存取。三个键均以 mangaExtension 前缀自持, 不进 PreferKey
 * 常量表 (零冲突); 值全部是 JSON 字符串, 备份侧 config.json 按 key 全量 dump
 * (纯黑名单), 自动随备份/恢复进出, 恢复后由 MangaExtensionManager.onRestoreFinished()
 * 重载内存态。
 *
 * 存储经 [PreferenceProviders] 平台抽象: Android 端委托 defaultSharedPreferences
 * (与下沉前 App.instance.getPrefString 同一文件, 存量数据无缝延续), 桌面端委托
 * java.util.prefs。public 可见性: 语言过滤读写被 ui 层插件服务消费。
 */
object ExtensionPrefs {

    private const val KEY_REPOS = "mangaExtensionRepos"
    private const val KEY_TRUSTED_SIGNATURES = "mangaExtensionTrustedSignatures"
    private const val KEY_SOURCE_ENABLED = "mangaExtensionSourceEnabled"
    private const val KEY_LANGUAGES = "mangaExtensionLanguages"

    private val repoListSerializer = ListSerializer(MangaExtensionRepo.serializer())
    private val stringSetSerializer = ListSerializer(String.serializer())
    private val sourceEnabledSerializer = MapSerializer(String.serializer(), Boolean.serializer())

    private fun getPrefString(key: String): String? =
        PreferenceProviders.get().getStringOrNull(key)

    private fun putPrefString(key: String, value: String) {
        PreferenceProviders.get().putString(key, value)
    }

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
        putPrefString(KEY_REPOS, GSON.encodeToString(repoListSerializer, repos))
    }

    fun removeRepo(indexUrl: String) {
        val repos = decodeUserRepos().filterNot { it.indexUrl == indexUrl }
        putPrefString(KEY_REPOS, GSON.encodeToString(repoListSerializer, repos))
    }

    private fun decodeUserRepos(): List<MangaExtensionRepo> {
        val json = getPrefString(KEY_REPOS) ?: return emptyList()
        return runCatching { GSON.decodeFromString(repoListSerializer, json) }.getOrNull().orEmpty()
    }

    /** 用户确认可信的扩展, 集合语义 "pkgName:versionCode:signatureHash"。 */
    fun getTrustedSignatures(): Set<String> {
        val json = getPrefString(KEY_TRUSTED_SIGNATURES) ?: return emptySet()
        return runCatching {
            GSON.decodeFromString(stringSetSerializer, json).toSet()
        }.getOrNull().orEmpty()
    }

    fun setTrustedSignatures(signatures: Set<String>) {
        putPrefString(KEY_TRUSTED_SIGNATURES, GSON.encodeToString(stringSetSerializer, signatures.toList()))
    }

    /** 展示语言过滤 (可用列表; 空集=全部语言, 匹配语义 ext.lang ∈ 集合 || "all" ∈ 集合)。 */
    fun getSelectedLanguages(): Set<String> {
        val json = getPrefString(KEY_LANGUAGES) ?: return emptySet()
        return runCatching {
            GSON.decodeFromString(stringSetSerializer, json).toSet()
        }.getOrNull().orEmpty()
    }

    fun setSelectedLanguages(languages: Set<String>) {
        putPrefString(
            KEY_LANGUAGES,
            GSON.encodeToString(stringSetSerializer, languages.toList()),
        )
    }

    /** 源启用表 (稀疏存储, 缺省启用), key 为源 id 的字符串形式。 */
    fun getSourceEnabled(): Map<Long, Boolean> {
        val json = getPrefString(KEY_SOURCE_ENABLED) ?: return emptyMap()
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
        putPrefString(KEY_SOURCE_ENABLED, json)
    }
}
