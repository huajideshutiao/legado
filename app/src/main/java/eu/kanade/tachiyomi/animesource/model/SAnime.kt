// Copyright The Aniyomi Contributors. Apache-2.0.
@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SAnime : Serializable {

    var url: String

    var title: String

    var thumbnail_url: String?

    var background_url: String?

    var artist: String?

    var author: String?

    var description: String?

    /**
     * A string containing list of all genres separated with `", "`
     */
    var genre: String?

    /**
     * An "enum" value. Refer to the values in the [SAnime companion object].
     */
    var status: Int

    /**
     * Useful to exclude animes/movies that will always only have the same episode list
     * from the global updates.
     */
    var update_strategy: AnimeUpdateStrategy

    var fetch_type: FetchType

    var season_number: Double

    /**
     * Extra metadata associated with the anime.
     *
     * The JSON object is not visible to users and intended for internal or source-specific
     * purposes. Apps may define their own namespaced keys (e.g., `"aniyomi.*"`) for sources to populate.
     *
     * @since extensions-lib 17
     */
    var memo: JsonObject

    /**
     * Tells the app if it should call [getAnimeXXXUpdate].
     */
    var initialized: Boolean

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    fun copy() = create().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre
        it.status = status
        it.thumbnail_url = thumbnail_url
        it.background_url = background_url
        it.update_strategy = update_strategy
        it.fetch_type = fetch_type
        it.season_number = season_number
        it.initialized = initialized
        it.memo = memo
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SAnime {
            return SAnimeImpl()
        }
    }
}
