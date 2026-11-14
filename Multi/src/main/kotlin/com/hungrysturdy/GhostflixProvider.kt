package com.hungrysturdy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document

class GhostflixProvider : MainAPI() {
    override var mainUrl = "https://ghostflix.st"
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

    override val mainPage = mainPageOf(
        "trending/all/day" to "Trending Now",
        "movie/popular" to "Popular Movies",
        "tv/popular" to "Popular TV Shows",
        "discover/tv?with_genres=16&with_original_language=ja" to "Anime",
        "discover/tv?with_original_language=ko&sort_by=popularity.desc" to "Asian Drama"
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

        val document = app.get(mainUrl).document
        val trendingItems = extractNextFlightTrending(document)
        if (trendingItems.isNotEmpty()) {
            homePageList.add(HomePageList(request.name, trendingItems))
        }

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

        val resolveHosts = listOf(
            "Bolt" to "https://vidbolt.pro",
            "Orion" to "https://vidcore.net"
        )

        resolveHosts.amap { (serverName, baseHost) ->
            val resolveEndpoints = if (isTv) {
                listOf(
                    "$baseHost/api/resolve?type=tv&id=$id&season=$s&episode=$e",
                    "$baseHost/tv/$id/$s/$e?theme=E50914&autoPlay=true"
                )
            } else {
                listOf(
                    "$baseHost/api/resolve?type=movie&id=$id",
                    "$baseHost/movie/$id?theme=E50914&autoPlay=true"
                )
            }

            for (endpoint in resolveEndpoints) {
                try {
                    val resText = app.get(
                        endpoint,
                        referer = "$baseHost/",
                        headers = mapOf(
                            "Origin" to baseHost,
                            "Referer" to "$baseHost/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                            "Accept" to "*/*"
                        )
                    ).text

                    extractStreamsFromText(resText, serverName, "$baseHost/", subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        return true
    }

    private suspend fun extractStreamsFromText(
        text: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val unescaped = text.replace("\\/", "/").replace("\\u0026", "&")

        Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""").findAll(unescaped).forEach { match ->
            val streamUrl = match.value
            if (streamUrl.contains("track_") && !streamUrl.contains("video")) return@forEach

            val isM3u8 = streamUrl.contains(".m3u8")
            val quality = when {
                streamUrl.contains("2160") || streamUrl.contains("4k", true) -> Qualities.P2160.value
                streamUrl.contains("1080") -> Qualities.P1080.value
                streamUrl.contains("720") -> Qualities.P720.value
                streamUrl.contains("480") -> Qualities.P480.value
                streamUrl.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - $serverName",
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                        "Referer" to referer,
                        "Origin" to referer.removeSuffix("/"),
                        "Accept" to "*/*",
                        "Connection" to "keep-alive"
                    )
                }
            )
        }

        Regex("""https?://[^\s"'<>\\]+\.(?:vtt|srt)(?:\?[^\s"'<>\\]*)?""").findAll(unescaped).forEach { match ->
            val subUrl = match.value
            if (!subUrl.contains("thumbs.vtt", ignoreCase = true)) {
                subtitleCallback.invoke(SubtitleFile("English", subUrl))
            }
        }
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
