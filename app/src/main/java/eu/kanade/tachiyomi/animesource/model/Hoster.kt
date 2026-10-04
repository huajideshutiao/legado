// Copyright The Aniyomi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.json.JsonObject

/**
 * Class to represent a "hoster", i.e. a representation of a group of videos coming from the same source,
 * for example a site which has multiple servers, each server with a list of videos.
 *
 * @param hosterUrl The url of the hoster
 * @param hosterName The display name of the hoster
 * @param videoList If the list of video's can be constructed without additional network requests,
 * set the videoList to avoid calling [getVideoList][eu.kanade.tachiyomi.animesource.AnimeSource.getVideoList] on the hoster
 * @param lazy Specify a hoster as lazy, which means that the hoster won't load until the user clicks on it
 * @param memo Extra metadata associated with the hoster. The JSON object is not visible to users and
 * intended for internal or source-specific purposes. Apps may define their own namespaced
 * keys (e.g., `"aniyomi.*"`) for sources to populate.
 */
class Hoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: List<Video>? = null,
    @Deprecated("Use memo instead", level = DeprecationLevel.ERROR)
    val internalData: String = "",
    val lazy: Boolean = false,
    val memo: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        const val NO_HOSTER_LIST = "no_hoster_list"

        fun List<Video>.toHosterList(): List<Hoster> {
            return listOf(
                Hoster(
                    hosterUrl = "",
                    hosterName = NO_HOSTER_LIST,
                    videoList = this,
                ),
            )
        }
    }
}
