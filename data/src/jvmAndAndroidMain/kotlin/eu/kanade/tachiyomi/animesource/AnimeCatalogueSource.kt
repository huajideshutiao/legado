// Copyright The Aniyomi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video

interface AnimeCatalogueSource : AnimeSource {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang: String

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        throw UnsupportedOperationException()
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        throw UnsupportedOperationException()
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        throw UnsupportedOperationException()
    }

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        throw UnsupportedOperationException()
    }

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate {
        throw UnsupportedOperationException()
    }

    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> {
        throw UnsupportedOperationException()
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        throw UnsupportedOperationException()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        throw UnsupportedOperationException()
    }
}
