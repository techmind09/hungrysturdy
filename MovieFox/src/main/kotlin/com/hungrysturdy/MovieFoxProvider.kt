package com.hungrysturdy

import com.fasterxml.jackson.annotation.JsonProperty
import com.hungrysturdy.Extractors.invokeAllAnimeSources
import com.hungrysturdy.Extractors.invokeAllSources
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

open class MovieFoxProvider : MainAPI() {
    override var mainUrl = "https://cinemeta-catalogs.strem.io"
    override var name = "MovieFox"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    private val skipMap: MutableMap<String, Int> = mutableMapOf()
    val cinemeta_url = "https://v3-cinemeta.strem.io"
    val kitsu_url = "https://anime-kitsu.strem.fun"
    val haglund_url = "https://arm.haglund.dev/api/v2"
    val image_proxy = "https://wsrv.nl/?url="

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime,
        TvType.Torrent,
    )

    override val mainPage = mainPageOf(
        // Trending & Popular
        "$mainUrl/top/catalog/movie/top/skip=###" to "Trending Movies",
        "$mainUrl/top/catalog/series/top/skip=###" to "Trending Web Series",
        "$mainUrl/top/catalog/movie/year/skip=###" to "Latest Releases (Movies)",
        "$mainUrl/top/catalog/series/year/skip=###" to "Latest Releases (Series)",

        // Bollywood & Indian / Regional Categories
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Bollywood" to "Bollywood Movies",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Bollywood" to "Hindi Web Series",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Hindi" to "Hindi Dubbed Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=South%20Indian" to "South Indian Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Tamil" to "Tamil Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Telugu" to "Telugu Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Malayalam" to "Malayalam Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Kannada" to "Kannada Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Punjabi" to "Punjabi Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Marathi" to "Marathi Movies",

        // Anime & Animation Categories
        "$kitsu_url/catalog/anime/kitsu-anime-airing/skip=###" to "Airing Anime",
        "$kitsu_url/catalog/anime/kitsu-anime-trending/skip=###" to "Popular Anime",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Animation" to "Animation & Cartoons",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Animation" to "Animated Series",

        // Hollywood & Genre Categories (Movies)
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Action" to "Action Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Adventure" to "Adventure Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Comedy" to "Comedy Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Drama" to "Drama Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Romance" to "Romance Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Horror" to "Horror Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Thriller" to "Thriller Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Sci-Fi" to "Sci-Fi Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Fantasy" to "Fantasy Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Mystery" to "Mystery Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Crime" to "Crime Movies",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Documentary" to "Documentaries",
        "$mainUrl/top/catalog/movie/top/skip=###&genre=Family" to "Family Movies",

        // Genre Categories (Series / TV Shows)
        "$mainUrl/top/catalog/series/top/skip=###&genre=Action" to "Action Series",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Comedy" to "Comedy Series",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Drama" to "Drama Series",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Romance" to "Romance Series",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Horror" to "Horror Series",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Thriller" to "Thriller Series",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Sci-Fi" to "Sci-Fi Series",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Fantasy" to "Fantasy Series",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Mystery" to "Mystery Series",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Crime" to "Crime Series",
        "$mainUrl/top/catalog/series/top/skip=###&genre=Documentary" to "Docu-Series",
    )

    private fun getPosterUrl(url: String? = null): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains("metahub.space") || url.contains("kitsu.")) return image_proxy + url
        return url
    }

    private fun String.toSansSerifBold(): String {
        val sb = StringBuilder(length)
        for (ch in this) {
            val mapped = when (ch) {
                in 'A'..'Z' -> Character.toChars(0x1D5D4 + (ch - 'A')).concatToString()
                in 'a'..'z' -> Character.toChars(0x1D5EE + (ch - 'a')).concatToString()
                in '0'..'9' -> Character.toChars(0x1D7EC + (ch - '0')).concatToString()
                else -> ch.toString()
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page == 1) skipMap.clear()
        val skip = skipMap[request.name] ?: 0
        val newRequestData = request.data.replace("###", skip.toString())

        val json = runCatching { app.get("$newRequestData.json").text }.getOrNull()
        val movies = json?.let { tryParseJson<Home>(it) } ?: return newHomePageResponse(
            list = HomePageList(name = request.name, list = emptyList()),
            hasNext = false
        )

        val movieCount = movies.metas.size
        skipMap[request.name] = skip + movieCount

        val home = movies.metas.mapNotNull { movie ->
            val type = when (movie.type) {
                "movie" -> TvType.Movie
                "anime" -> TvType.Anime
                else -> TvType.TvSeries
            }
            val title = movie.aliases?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: movie.name?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val dataJson = PassData(movie.id, movie.type).toJson()
            if (type == TvType.Movie) {
                newMovieSearchResponse(title, dataJson, type) {
                    this.posterUrl = getPosterUrl(movie.poster)
                    this.score = Score.from10(movie.imdbRating)
                }
            } else {
                newTvSeriesSearchResponse(title, dataJson, type) {
                    this.posterUrl = getPosterUrl(movie.poster)
                    this.score = Score.from10(movie.imdbRating)
                }
            }
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home),
            hasNext = movies.hasMore && home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()

        suspend fun fetchResults(url: String): List<SearchResponse> {
            return runCatching {
                val json = app.get(url).text
                tryParseJson<SearchResult>(json)?.metas?.mapNotNull {
                    val title = it.aliases?.firstOrNull()?.takeIf { a -> a.isNotBlank() }
                        ?: it.name?.takeIf { n -> n.isNotBlank() }
                        ?: return@mapNotNull null
                    val poster = if (it.id.startsWith("tt")) {
                        "https://images.metahub.space/poster/medium/${it.id}/img"
                    } else {
                        it.poster
                    }
                    val tvType = when (it.type) {
                        "movie" -> TvType.Movie
                        "anime" -> TvType.Anime
                        else -> TvType.TvSeries
                    }
                    val dataJson = PassData(it.id, it.type).toJson()

                    if (tvType == TvType.Movie) {
                        newMovieSearchResponse(title, dataJson, tvType).apply {
                            this.posterUrl = getPosterUrl(poster)
                            this.score = Score.from10(it.imdbRating)
                        }
                    } else {
                        newTvSeriesSearchResponse(title, dataJson, tvType).apply {
                            this.posterUrl = getPosterUrl(poster)
                            this.score = Score.from10(it.imdbRating)
                        }
                    }
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }

        val endpoints = listOf(
            "$cinemeta_url/catalog/movie/top/search=$query.json",
            "$cinemeta_url/catalog/series/top/search=$query.json",
            "$kitsu_url/catalog/anime/kitsu-anime-airing/search=$query.json",
            "$kitsu_url/catalog/anime/kitsu-anime-trending/search=$query.json"
        )

        val resultsLists = endpoints.map { async { fetchResults(it) } }.awaitAll()
        val maxSize = resultsLists.maxOfOrNull { it.size } ?: 0

        buildList {
            for (i in 0 until maxSize) {
                for (list in resultsLists) {
                    if (i < list.size) add(list[i])
                }
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val movie = tryParseJson<PassData>(url) ?: return null
        val tvtype = movie.type
        var id = movie.id
        val type = if (tvtype == "movie") TvType.Movie else TvType.TvSeries
        val metaUrl = if (id.contains("kitsu") || id.contains("mal")) kitsu_url else cinemeta_url
        val isKitsu = metaUrl == kitsu_url
        val kitsuId = if (isKitsu) id.substringAfter("kitsu:") else null
        val encodedId = if (isKitsu) id.replace(":", "%3A") else id

        val json = runCatching { app.get("$metaUrl/meta/$tvtype/$encodedId.json").text }.getOrNull() ?: return null
        val movieData = tryParseJson<ResponseData>(json)?.meta ?: return null

        if (isKitsu && id.contains("mal") && !movieData.id.isNullOrBlank()) {
            id = movieData.id
        }

        val externalIds = if (isKitsu) getExternalIds(kitsuId, "kitsu") else null
        val malId = externalIds?.myanimelist
        val anilistId = externalIds?.anilist

        val title = movieData.name.orEmpty().ifBlank { "Unknown" }
        val engTitle = movieData.aliases?.firstOrNull()?.takeIf { it.isNotBlank() } ?: title
        val posterUrl = getPosterUrl(movieData.poster)
        val logo = getPosterUrl(movieData.logo)
        val imdbRating = movieData.imdbRating?.toDoubleOrNull()
        val year = movieData.year
        val releaseInfo = movieData.releaseInfo

        val resolvedImdbId = if (isKitsu) {
            externalIds?.imdb?.takeIf { it.isNotBlank() } ?: id
        } else {
            movieData.imdb_id?.takeIf { it.isNotBlank() } ?: id
        }

        val tmdbId = if (isKitsu) {
            externalIds?.themoviedb ?: movieData.moviedb_id
        } else {
            movieData.moviedb_id ?: externalIds?.themoviedb
        }

        val description = movieData.awards?.takeIf { it.isNotBlank() }
            ?.let { "[${it.toSansSerifBold()}] ${movieData.description.orEmpty()}" }
            ?: movieData.description

        val actors: List<ActorData>? = movieData.app_extras?.cast
            ?.mapNotNull { c ->
                val actorName = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ActorData(
                    actor = Actor(actorName, getPosterUrl(c.photo)),
                    roleString = c.character
                )
            }
            ?.takeIf { it.isNotEmpty() }
            ?: movieData.cast?.map { ActorData(Actor(it)) }?.takeIf { it.isNotEmpty() }

        val country = movieData.country.orEmpty()
        val genre = movieData.genre ?: movieData.genres ?: emptyList()
        val background = getPosterUrl(movieData.background)
        val isCartoon = genre.any { it.contains("Animation", true) }
        val isAnime = isKitsu || ((country.contains("Japan", true) || country.contains("China", true)) && isCartoon)
        val isBollywood = country.contains("India", true)
        val isAsian = (country.contains("Korea", true) || country.contains("China", true)) && !isAnime

        return if (tvtype == "movie") {
            val data = LoadLinksData(
                title = title,
                id = resolvedImdbId,
                tmdbId = tmdbId,
                tvtype = tvtype,
                year = year ?: releaseInfo,
                isAnime = isAnime,
                isBollywood = isBollywood,
                isAsian = isAsian,
                isCartoon = isCartoon,
                imdb_id = resolvedImdbId,
                isKitsu = isKitsu,
                anilistId = anilistId,
                malId = malId,
                kitsuId = kitsuId,
            ).toJson()

            newMovieLoadResponse(engTitle, url, if (isAnime) TvType.AnimeMovie else type, data) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year?.toIntOrNull()
                    ?: releaseInfo?.toIntOrNull()
                    ?: year?.substringBefore("-")?.toIntOrNull()
                this.backgroundPosterUrl = background
                this.logoUrl = logo
                this.contentRating = if (isKitsu) "Kitsu" else "IMDB"
                this.actors = actors
                addAniListId(anilistId)
                addMalId(malId)
                if (resolvedImdbId.startsWith("tt")) addImdbId(resolvedImdbId)
            }
        } else {
            val episodes = movieData.videos
                ?.filter { it.season != 0 }
                ?.map { ep ->
                    val epImdbId = ep.imdb_id?.takeIf { it.isNotBlank() } ?: resolvedImdbId
                    newEpisode(
                        LoadLinksData(
                            title = title,
                            id = resolvedImdbId,
                            tmdbId = tmdbId,
                            tvtype = tvtype,
                            year = year ?: releaseInfo,
                            season = ep.season,
                            episode = ep.episode,
                            firstAired = ep.firstAired ?: ep.released,
                            isAnime = isAnime,
                            isBollywood = isBollywood,
                            isAsian = isAsian,
                            isCartoon = isCartoon,
                            imdb_id = epImdbId,
                            imdbSeason = ep.imdbSeason ?: externalIds?.thetvdbSeason ?: ep.season,
                            imdbEpisode = ep.imdbEpisode ?: ep.episode,
                            isKitsu = isKitsu,
                            anilistId = anilistId,
                            malId = malId,
                            kitsuId = kitsuId
                        ).toJson()
                    ) {
                        this.name = ep.name ?: ep.title
                        this.season = ep.season
                        this.episode = ep.episode
                        this.posterUrl = getPosterUrl(ep.thumbnail)
                        this.description = ep.overview
                        this.score = Score.from10(ep.rating?.toDoubleOrNull())
                        addDate(ep.firstAired ?: ep.released)
                    }
                } ?: emptyList()

            if (isAnime) {
                newAnimeLoadResponse(engTitle, url, TvType.Anime) {
                    addEpisodes(DubStatus.Subbed, episodes)
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = background
                    this.year = year?.substringBefore("–")?.toIntOrNull()
                        ?: releaseInfo?.substringBefore("–")?.toIntOrNull()
                        ?: year?.substringBefore("-")?.toIntOrNull()
                    this.plot = description
                    this.tags = genre
                    this.logoUrl = logo
                    this.score = Score.from10(imdbRating)
                    this.contentRating = if (isKitsu) "Kitsu" else "IMDB"
                    this.actors = actors
                    addAniListId(anilistId)
                    addMalId(malId)
                    if (resolvedImdbId.startsWith("tt")) addImdbId(resolvedImdbId)
                }
            } else {
                newTvSeriesLoadResponse(engTitle, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = background
                    this.year = year?.substringBefore("–")?.toIntOrNull()
                        ?: releaseInfo?.substringBefore("–")?.toIntOrNull()
                        ?: year?.substringBefore("-")?.toIntOrNull()
                    this.plot = description
                    this.tags = genre
                    this.logoUrl = logo
                    this.score = Score.from10(imdbRating)
                    this.contentRating = "IMDB"
                    this.actors = actors
                    addAniListId(anilistId)
                    addMalId(malId)
                    if (resolvedImdbId.startsWith("tt")) addImdbId(resolvedImdbId)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("MovieFox", data)

        val res = tryParseJson<LoadLinksData>(data) ?: return false
        val year = getYear(res)
        val seasonYear = getSeasonYear(res)

        return if (res.isKitsu) {
            runKitsuInvokers(res, year, seasonYear, subtitleCallback, callback)
            true
        } else {
            invokeAllSources(
                AllLoadLinksData(
                    res.title,
                    res.id,
                    res.tmdbId,
                    res.anilistId,
                    res.malId,
                    res.kitsuId,
                    year,
                    seasonYear,
                    res.season,
                    res.episode,
                    res.isAnime,
                    res.isBollywood,
                    res.isAsian,
                    res.isCartoon,
                    null,
                    null,
                    null,
                    null,
                    null,
                ),
                subtitleCallback,
                callback
            )
            true
        }
    }

    data class LoadLinksData(
        val title: String,
        val id: String,
        val tmdbId: Int?,
        val tvtype: String,
        val year: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val firstAired: String? = null,
        val isAnime: Boolean = false,
        val isBollywood: Boolean = false,
        val isAsian: Boolean = false,
        val isCartoon: Boolean = false,
        val imdb_id: String? = null,
        val imdbSeason: Int? = null,
        val imdbEpisode: Int? = null,
        val isKitsu: Boolean = false,
        val anilistId: Int? = null,
        val malId: Int? = null,
        val kitsuId: String? = null,
    )

    data class PassData(
        val id: String,
        val type: String,
    )

    data class Meta(
        val id: String?,
        val imdb_id: String?,
        val awards: String?,
        val type: String?,
        val aliases: ArrayList<String>?,
        val poster: String?,
        val logo: String?,
        val background: String?,
        val moviedb_id: Int?,
        val name: String?,
        val description: String?,
        val genre: List<String>?,
        val genres: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val runtime: String?,
        val cast: List<String>?,
        val app_extras: AppExtras? = null,
        val language: String?,
        val country: String?,
        val imdbRating: String?,
        val year: String?,
        val videos: List<EpisodeDetails>?,
    )

    data class AppExtras(
        val cast: List<Cast> = emptyList()
    )

    data class Cast(
        val name: String? = null,
        val character: String? = null,
        val photo: String? = null
    )

    data class SearchResult(
        val metas: List<Media>
    )

    data class Media(
        val id: String,
        val type: String,
        val name: String?,
        val poster: String?,
        val description: String?,
        val imdbRating: String?,
        val aliases: ArrayList<String>?,
    )

    data class EpisodeDetails(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int,
        val episode: Int,
        val rating: String?,
        val released: String?,
        val firstAired: String?,
        val overview: String?,
        val thumbnail: String?,
        val moviedb_id: Int?,
        val imdb_id: String?,
        val imdbSeason: Int?,
        val imdbEpisode: Int?,
    )

    data class ResponseData(
        val meta: Meta,
    )

    data class Home(
        val metas: List<Media>,
        val hasMore: Boolean = true,
    )

    data class ExternalIds(
        val anilist: Int? = null,
        val imdb: String? = null,
        val kitsu: Int? = null,
        val myanimelist: Int? = null,
        val simkl: Int? = null,
        val themoviedb: Int? = null,
        val thetvdb: Int? = null,
        @param:JsonProperty("thetvdb-season")
        val thetvdbSeason: Int? = null
    )

    suspend fun getExternalIds(id: String?, type: String): ExternalIds? {
        if (id.isNullOrBlank()) return null

        val url = "$haglund_url/ids?source=$type&id=$id"
        Log.d("MovieFox", "url: $url")
        val json = runCatching { app.get(url).text }.getOrNull() ?: return null
        Log.d("MovieFox", "json: $json")
        return tryParseJson<ExternalIds>(json)
    }

    private fun getYear(res: LoadLinksData): Int? {
        return if (res.tvtype == "movie") res.year?.toIntOrNull()
        else res.year?.substringBefore("-")?.toIntOrNull() ?: res.year?.substringBefore("–")?.toIntOrNull()
    }

    private fun getSeasonYear(res: LoadLinksData): Int? {
        return if (res.tvtype == "movie") getYear(res)
        else res.firstAired?.substringBefore("-")?.toIntOrNull() ?: res.firstAired?.substringBefore("–")?.toIntOrNull()
    }

    private suspend fun runKitsuInvokers(
        res: LoadLinksData,
        year: Int?,
        seasonYear: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var imdbTitle: String? = null
        var imdbYear: Int? = null
        var tmdbId: Int? = null

        val imdbLookup = res.imdb_id?.takeIf { it.isNotBlank() && it != "null" }
        if (imdbLookup != null) {
            runCatching {
                val json = app.get("$cinemeta_url/meta/${res.tvtype}/$imdbLookup.json").text
                val movieData = tryParseJson<ResponseData>(json)

                movieData?.meta?.let { meta ->
                    imdbTitle = meta.name
                    imdbYear = meta.year?.substringBefore("-")?.toIntOrNull()
                        ?: meta.year?.substringBefore("–")?.toIntOrNull()
                        ?: meta.year?.toIntOrNull()
                    tmdbId = meta.moviedb_id
                }
            }.onFailure { e ->
                Log.d("MovieFox", "Cinemeta API failed: ${e.localizedMessage}")
            }
        }

        invokeAllAnimeSources(
            AllLoadLinksData(
                res.title,
                imdbLookup,
                tmdbId ?: res.tmdbId,
                res.anilistId,
                res.malId,
                res.kitsuId,
                year,
                seasonYear,
                res.season,
                res.episode,
                res.isAnime,
                res.isBollywood,
                res.isAsian,
                res.isCartoon,
                null,
                imdbTitle,
                res.imdbSeason,
                res.imdbEpisode,
                imdbYear,
            ),
            subtitleCallback,
            callback
        )
    }
}

