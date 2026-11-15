package com.hungrysturdy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document

class GhostflixProvider : MainAPI() {
    override var mainUrl = "https://ghostflix.uk"
    override var name = "Ghostflix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    companion object {
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
        private const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/original"
        private const val TMDB_API_KEY = "e76d52f346700d0edf06fb2e938f98ad"
        private const val TMDB_ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJlNzZkNTJmMzQ2NzAwZDBlZGYwNmZiMmU5MzhmOThhZCIsIm5iZiI6MTc3NzkwNzk4NS4xNiwic3ViIjoiNjlmOGI5MTEzODdlMWExNWY3MzI3MWQxIiwic2NvcGVzIjpbImFwaV9yZWFkIl0sInZlcnNpb24iOjF9.xKkhmPxRCBvBPok8br1xxjIMEHfkAO_jQZQ5nBkX2Cc"
    }

    private val tmdbHeaders = mapOf(
        "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
        "Accept" to "application/json"
    )

    private val cfHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Connection" to "keep-alive"
    )

    override val mainPage = mainPageOf(
        "trending/all/day" to "Trending Now",
        "movie/popular" to "Popular Movies",
        "tv/popular" to "Popular TV Shows",
        "discover/tv?with_genres=16&with_original_language=ja" to "Anime",
        "discover/tv?with_original_language=ko&sort_by=popularity.desc" to "Asian Drama",
        "discover/movie?with_genres=28" to "Action",
        "discover/movie?with_genres=35" to "Comedy",
        "discover/movie?with_genres=27" to "Horror",
        "discover/movie?with_genres=10749" to "Romance",
        "discover/movie?with_genres=878" to "Sci-Fi",
        "discover/movie?with_genres=16" to "Animation",
        "discover/movie?with_genres=18" to "Drama",
        "discover/movie?with_genres=53" to "Thriller",
        "discover/movie?with_genres=12" to "Adventure",
        "discover/movie?with_genres=80" to "Crime",
        "discover/movie?with_genres=14" to "Fantasy"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = mutableListOf<HomePageList>()
        val sep = if (request.data.contains("?")) "&" else "?"
        val tmdbUrl = "https://api.themoviedb.org/3/${request.data}${sep}api_key=$TMDB_API_KEY&page=$page"

        try {
            val response = app.get(tmdbUrl, headers = tmdbHeaders).parsedSafe<TmdbPageResponse>()
            val resultsList: List<TrendingItem> = response?.results ?: emptyList()
            val items = resultsList.mapNotNull { item ->
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val mediaId = item.id ?: return@mapNotNull null
                val isTv = item.mediaType.equals("tv", ignoreCase = true) || request.data.startsWith("tv") || request.data.contains("discover/tv")
                val poster = item.posterPath?.let { "$TMDB_IMAGE_BASE$it" }
                val targetUrl = if (isTv) "$mainUrl/tv/$mediaId" else "$mainUrl/movie/$mediaId"

                if (isTv) {
                    newTvSeriesSearchResponse(title, targetUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, items))
                return newHomePageResponse(homePageList, hasNext = true)
            }
        } catch (_: Exception) {}

        try {
            val html = app.get(mainUrl, headers = cfHeaders).text
            val document = org.jsoup.Jsoup.parse(html)
            val trendingItems = extractNextFlightTrending(document)
            if (trendingItems.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, trendingItems))
            }
        } catch (_: Exception) {}

        return newHomePageResponse(homePageList, hasNext = false)
    }

    private fun extractNextFlightTrending(document: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val scripts = document.select("script").map { it.data() }
        val regex = Regex("""\"initialTrending\"\s*:\s*(\[\{.*?\}\])""")

        for (script in scripts) {
            if (!script.contains("initialTrending")) continue
            val unescaped = script.replace("\\\"", "\"").replace("\\\\", "\\")
            val match = regex.find(unescaped) ?: continue
            val jsonArray = match.groupValues[1]

            val parsed = tryParseJson<List<TrendingItem>>(jsonArray) ?: continue
            parsed.forEach { item ->
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@forEach
                val mediaId = item.id ?: return@forEach
                val isTv = item.mediaType.equals("tv", ignoreCase = true)
                val poster = item.posterPath?.let { "$TMDB_IMAGE_BASE$it" }
                val targetUrl = if (isTv) "$mainUrl/tv/$mediaId" else "$mainUrl/movie/$mediaId"

                if (isTv) {
                    results.add(newTvSeriesSearchResponse(title, targetUrl, TvType.TvSeries) { this.posterUrl = poster })
                } else {
                    results.add(newMovieSearchResponse(title, targetUrl, TvType.Movie) { this.posterUrl = poster })
                }
            }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://api.themoviedb.org/3/search/multi?api_key=$TMDB_API_KEY&query=$encoded&page=1"

        try {
            val response = app.get(searchUrl, headers = tmdbHeaders).parsedSafe<TmdbPageResponse>()
            val resultsList: List<TrendingItem> = response?.results ?: emptyList()
            val results = resultsList.mapNotNull { item ->
                if (item.mediaType == "person") return@mapNotNull null
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val mediaId = item.id ?: return@mapNotNull null
                val isTv = item.mediaType.equals("tv", ignoreCase = true) || item.name != null
                val poster = item.posterPath?.let { "$TMDB_IMAGE_BASE$it" }
                val targetUrl = if (isTv) "$mainUrl/tv/$mediaId" else "$mainUrl/movie/$mediaId"

                if (isTv) {
                    newTvSeriesSearchResponse(title, targetUrl, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, targetUrl, TvType.Movie) { this.posterUrl = poster }
                }
            }

            if (results.isNotEmpty()) return results
        } catch (_: Exception) {}

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/") || url.contains("type=tv")
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""id=(\d+)""").find(url)?.groupValues?.get(1)
            ?: return null

        val mediaType = if (isTv) "tv" else "movie"
        val tmdbUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$TMDB_API_KEY"

        var title = "Ghostflix #$tmdbId"
        var description = "No description available."
        var poster: String? = null
        var backdrop: String? = null
        var year: Int? = null
        var seasonsList: List<TmdbSeason> = emptyList()

        try {
            val tmdbResp = app.get(tmdbUrl, headers = tmdbHeaders).parsedSafe<TmdbDetails>()
            if (tmdbResp != null) {
                title = tmdbResp.title ?: tmdbResp.name ?: title
                description = tmdbResp.overview ?: description
                poster = tmdbResp.posterPath?.let { "$TMDB_IMAGE_BASE$it" }
                backdrop = tmdbResp.backdropPath?.let { "$TMDB_BACKDROP_BASE$it" }
                year = (tmdbResp.releaseDate ?: tmdbResp.firstAirDate)?.take(4)?.toIntOrNull()
                seasonsList = tmdbResp.seasons ?: emptyList()
            }
        } catch (_: Exception) {}

        return if (isTv) {
            val episodes = mutableListOf<Episode>()
            val validSeasons = seasonsList.filter { it.seasonNumber != null && it.seasonNumber > 0 }

            if (validSeasons.isNotEmpty()) {
                validSeasons.forEach { season ->
                    val sNum = season.seasonNumber ?: return@forEach
                    try {
                        val sUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/$sNum?api_key=$TMDB_API_KEY"
                        val sResp = app.get(sUrl, headers = tmdbHeaders).parsedSafe<TmdbSeasonDetails>()
                        val epsList: List<TmdbEpisode> = sResp?.episodes ?: emptyList()
                        epsList.forEach { ep ->
                            val epNum = ep.episodeNumber ?: return@forEach
                            val playData = mapper.writeValueAsString(LoadData(id = tmdbId, type = "tv", season = sNum, episode = epNum))

                            episodes.add(
                                newEpisode(playData) {
                                    this.name = ep.name ?: "Episode $epNum"
                                    this.season = sNum
                                    this.episode = epNum
                                    this.posterUrl = ep.stillPath?.let { "$TMDB_IMAGE_BASE$it" }
                                    this.description = ep.overview
                                }
                            )
                        }
                    } catch (_: Exception) {
                        val epCount = (season.episodeCount ?: 12).coerceAtLeast(1)
                        for (epNum in 1..epCount) {
                            val playData = mapper.writeValueAsString(LoadData(id = tmdbId, type = "tv", season = sNum, episode = epNum))

                            episodes.add(
                                newEpisode(playData) {
                                    this.name = "Episode $epNum"
                                    this.season = sNum
                                    this.episode = epNum
                                }
                            )
                        }
                    }
                }
            } else {
                for (ep in 1..24) {
                    val playData = mapper.writeValueAsString(LoadData(id = tmdbId, type = "tv", season = 1, episode = ep))

                    episodes.add(
                        newEpisode(playData) {
                            this.name = "Episode $ep"
                            this.season = 1
                            this.episode = ep
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.plot = description
                this.year = year
            }
        } else {
            val playData = mapper.writeValueAsString(LoadData(id = tmdbId, type = "movie"))
            newMovieLoadResponse(title, url, TvType.Movie, playData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.plot = description
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<LoadData>(data) ?: return false
        val id = loadData.id
        val isTv = loadData.type == "tv"
        val s = loadData.season ?: 1
        val e = loadData.episode ?: 1

        val embedUrls = if (isTv) {
            listOf(
                "CineSrc" to "https://cinesrc.ru/embed/tv/$id/$s/$e",
                "Vidcore" to "https://vidcore.net/embed/tv/$id/$s/$e",
                "Vidrift" to "https://embed.vidrift.net/embed/tv/$id/$s/$e",
                "Vidy" to "https://www.viduki.net/embed/tv/$id/$s/$e",
                "VidLink" to "https://vidlink.pro/tv/$id/$s/$e",
                "EmbedSu" to "https://embed.su/embed/tv/$id/$s/$e",
                "VidSrcXYZ" to "https://vidsrc.xyz/embed/tv/$id/$s-$e",
                "VidSrcCC" to "https://vidsrc.cc/v2/embed/tv/$id/$s/$e"
            )
        } else {
            listOf(
                "CineSrc" to "https://cinesrc.ru/embed/movie/$id",
                "Vidcore" to "https://vidcore.net/embed/movie/$id",
                "Vidrift" to "https://embed.vidrift.net/embed/movie/$id",
                "Vidy" to "https://www.viduki.net/embed/movie/$id",
                "VidLink" to "https://vidlink.pro/movie/$id",
                "EmbedSu" to "https://embed.su/embed/movie/$id",
                "VidSrcXYZ" to "https://vidsrc.xyz/embed/movie/$id",
                "VidSrcCC" to "https://vidsrc.cc/v2/embed/movie/$id"
            )
        }

        embedUrls.amap { (serverName, embedUrl) ->
            try {
                val extractedLinks = mutableListOf<ExtractorLink>()
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback) { link ->
                    extractedLinks.add(link)
                }
                extractedLinks.forEach { link ->
                    callback.invoke(
                        newExtractorLink(
                            source = link.source,
                            name = "$name - $serverName",
                            url = link.url,
                            type = link.type
                        ) {
                            this.referer = link.referer
                            this.quality = link.quality
                            this.headers = link.headers
                        }
                    )
                }
            } catch (_: Exception) {}
        }

        return true
    }

    data class LoadData(
        @JsonProperty("id") val id: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null
    )

    data class TmdbPageResponse(
        @JsonProperty("results") val results: List<TrendingItem>? = null
    )

    data class TrendingItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null
    )

    data class TmdbDetails(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null
    )

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class TmdbSeasonDetails(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null
    )
}
