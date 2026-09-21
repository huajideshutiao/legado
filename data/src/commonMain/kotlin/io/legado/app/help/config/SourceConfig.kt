package io.legado.app.help.config

import io.legado.app.help.CacheManager
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import kotlinx.serialization.Serializable

/**
 * 书源评分 (换源排序参考)。存 caches 表一源一行: key = `sourceScore_{origin}`,
 * value = {total, books:{"name_author":score}} —— preference 后端在桌面端
 * java.util.prefs 下 key ≤ 80 / 超长 origin 读写抛异常。旧评分不迁移, 从 0 累积。
 */
object SourceConfig {

    @Serializable
    private data class Scores(
        val total: Int = 0,
        val books: Map<String, Int> = emptyMap(),
    )

    /** 内存缓存: 换源排序高频读, 免每次阻塞读库 */
    private val memoryScores = mutableMapOf<String, Scores>()

    private fun load(origin: String): Scores = memoryScores.getOrPut(origin) {
        CacheManager.get("sourceScore_$origin")
            ?.let { GSON.fromJsonObject<Scores>(it).getOrNull() } ?: Scores()
    }

    fun setBookScore(origin: String, name: String, author: String, score: Int) {
        val scores = load(origin)
        val bookKey = "${name}_${author}"
        val preScore = scores.books[bookKey] ?: 0
        val delta = if (preScore != 0) score - preScore else score
        val new = scores.copy(
            total = scores.total + delta,
            books = scores.books + (bookKey to score),
        )
        memoryScores[origin] = new
        CacheManager.put("sourceScore_$origin", GSON.toJson(new))
    }

    fun getBookScore(origin: String, name: String, author: String): Int =
        load(origin).books["${name}_${author}"] ?: 0

    fun getSourceScore(origin: String): Int = load(origin).total

    fun removeSource(origin: String) {
        memoryScores.remove(origin)
        CacheManager.delete("sourceScore_$origin")
    }

    fun removeSources(origins: Collection<String>) = origins.forEach { removeSource(it) }
}
