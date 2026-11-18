package com.hungrysturdy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import java.net.URLEncoder

class GhostflixProvider : MainAPI() {

    override var mainUrl = "https://ghostflix.st"
    private val altUrl = "https://ghostflix.uk"

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
        private const val TMDB_API_KEY =
            "e76d52f346700d0edf06fb2e938f98ad"

        private const val TMDB_IMAGE_BASE =
            "https://image.tmdb.org/t/p/w500"

        private const val TMDB_BACKDROP_BASE =
            "https://image.tmdb.org/t/p/original"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36"
    }

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/json,*/*",
        "Accept-Language" to "en-US,en;q=0.9"
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

    private fun tmdbApi(path: String): String {
        val separator = if (path.contains("?")) "&" else "?"
        return "https://api.themoviedb.org/3/$path${separator}api_key=$TMDB_API_KEY"
    }

    private fun imageUrl(path: String?): String? {
        return path?.takeIf { it.isNotBlank() }
            ?.let { "$TMDB_IMAGE_BASE$it" }
    }

    private fun watchUrl(
        domain: String,
        id: String,
        type: String
    ): String {
        return "$domain/watch/$type/$id"
    }

    private fun searchResponse(
        item: TrendingItem,
        domain: String,
        forcedTv: Boolean = false
    ): SearchResponse? {
        val title = item.title
            ?: item.name
            ?: item.originalTitle
            ?: item.originalName
            ?: return null

        val id = item.id ?: return null

        val isTv = forcedTv ||
            item.mediaType.equals("tv", true) ||
            item.name != null ||
            item.originalName != null

        val target = watchUrl(
            domain,
            id.toString(),
            if (isTv) "tv" else "movie"
        )

        val poster = imageUrl(item.posterPath)

        return if (isTv) {
            newTvSeriesSearchResponse(
                title,
                target,
                TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(
                title,
                target,
                TvType.Movie
            ) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        try {
            val url = "${tmdbApi(request.data)}&page=$page"

            val response = app.get(
                url,
                headers = requestHeaders
            ).parsedSafe<TmdbPageResponse>()

            val isTv = request.data.startsWith("tv/") ||
                request.data.contains("discover/tv")

            val items = response?.results.orEmpty().mapNotNull {
                searchResponse(
                    it,
                    mainUrl,
                    forcedTv = isTv
                )
            }

            if (items.isNotEmpty()) {
                lists.add(
                    HomePageList(
                        request.name,
                        items
                    )
                )

                return newHomePageResponse(
                    lists,
                    hasNext = response?.results?.isNotEmpty() == true
                )
            }
        } catch (_: Exception) {
        }

        // Website fallback
        for (domain in listOf(mainUrl, altUrl)) {
            try {
                val html = app.get(
                    domain,
                    headers = requestHeaders
                ).text

                val document = Jsoup.parse(html, domain)
                val items = extractWebsiteItems(document, domain)

                if (items.isNotEmpty()) {
                    lists.add(
                        HomePageList(
                            request.name,
                            items
                        )
                    )
                    break
                }
            } catch (_: Exception) {
            }
        }

        return newHomePageResponse(
            lists,
            hasNext = false
        )
    }

    private fun extractWebsiteItems(
        document: org.jsoup.nodes.Document,
        domain: String
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        document.select(
            "a[href*='/movie/'], a[href*='/tv/'], a[href*='/watch/']"
        ).forEach { element ->
            val href = element.attr("href")
            val id = Regex("""/(\d+)(?:[/?#]|$)""")
                .find(href)
                ?.groupValues
                ?.get(1)
                ?: return@forEach

            val title = element.selectFirst(
                "[title], h2, h3, .title"
            )?.let {
                it.attr("title").ifBlank { it.text() }
            }?.takeIf { it.isNotBlank() }
                ?: element.attr("title").takeIf { it.isNotBlank() }
                ?: return@forEach

            val isTv = href.contains("/tv/")

            val poster = element.selectFirst("img")
                ?.let {
                    it.absUrl("src").ifBlank {
                        it.absUrl("data-src")
                    }
                }

            val target = watchUrl(
                domain,
                id,
                if (isTv) "tv" else "movie"
            )

            if (isTv) {
                results.add(
                    newTvSeriesSearchResponse(
                        title,
                        target,
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster
                    }
                )
            } else {
                results.add(
                    newMovieSearchResponse(
                        title,
                        target,
                        TvType.Movie
                    ) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        val encoded = URLEncoder.encode(
            query,
            "UTF-8"
        )

        try {
            val response = app.get(
                tmdbApi("search/multi?query=$encoded&page=1"),
                headers = requestHeaders
            ).parsedSafe<TmdbPageResponse>()

            val results = response?.results.orEmpty()
                .filterNot {
                    it.mediaType.equals("person", true)
                }
                .mapNotNull {
                    searchResponse(it, mainUrl)
                }

            if (results.isNotEmpty()) {
                return results
            }
        } catch (_: Exception) {
        }

        return emptyList()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        val match = Regex(
            """/(?:watch/)?(movie|tv)/(\d+)"""
        ).find(url) ?: return null

        val mediaType = match.groupValues[1]
        val tmdbId = match.groupValues[2]
        val isTv = mediaType == "tv"

        var mediaTitle = "Ghostflix $tmdbId"
        var plotOverview = ""
        var poster: String? = null
        var backdrop: String? = null
        var releaseYear: Int? = null
        var seasons: List<TmdbSeason> = emptyList()

        try {
            val details = app.get(
                tmdbApi("$mediaType/$tmdbId"),
                headers = requestHeaders
            ).parsedSafe<TmdbDetails>()

            if (details != null) {
                mediaTitle = details.title
                    ?: details.name
                    ?: mediaTitle

                plotOverview = details.overview.orEmpty()
                poster = imageUrl(details.posterPath)

                backdrop = details.backdropPath?.let {
                    "$TMDB_BACKDROP_BASE$it"
                }

                releaseYear = (
                    details.releaseDate
                        ?: details.firstAirDate
                    )?.take(4)?.toIntOrNull()

                seasons = details.seasons.orEmpty()
            }
        } catch (_: Exception) {
        }

        if (!isTv) {
            val playData = mapper.writeValueAsString(
                LoadData(
                    id = tmdbId,
                    type = "movie"
                )
            )

            return newMovieLoadResponse(
                mediaTitle,
                url,
                TvType.Movie,
                playData
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.plot = plotOverview
                this.year = releaseYear
            }
        }

        val episodes = mutableListOf<Episode>()

        val validSeasons = seasons.filter {
            (it.seasonNumber ?: 0) > 0
        }

        for (seasonItem in validSeasons) {
            val seasonNumber = seasonItem.seasonNumber ?: continue

            try {
                val seasonResponse = app.get(
                    tmdbApi(
                        "tv/$tmdbId/season/$seasonNumber"
                    ),
                    headers = requestHeaders
                ).parsedSafe<TmdbSeasonDetails>()

                for (ep in seasonResponse?.episodes.orEmpty()) {
                    val episodeNumber =
                        ep.episodeNumber ?: continue

                    val playData = mapper.writeValueAsString(
                        LoadData(
                            id = tmdbId,
                            type = "tv",
                            season = seasonNumber,
                            episode = episodeNumber
                        )
                    )

                    episodes.add(
                        newEpisode(playData) {
                            this.name = ep.name
                                ?: "Episode $episodeNumber"

                            this.season = seasonNumber
                            this.episode = episodeNumber

                            this.posterUrl = imageUrl(ep.stillPath)
                            this.description = ep.overview
                        }
                    )
                }
            } catch (_: Exception) {
                val count = (seasonItem.episodeCount ?: 0)
                    .coerceIn(1, 100)

                for (episodeNumber in 1..count) {
                    val playData = mapper.writeValueAsString(
                        LoadData(
                            id = tmdbId,
                            type = "tv",
                            season = seasonNumber,
                            episode = episodeNumber
                        )
                    )

                    episodes.add(
                        newEpisode(playData) {
                            this.name = "Episode $episodeNumber"
                            this.season = seasonNumber
                            this.episode = episodeNumber
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(
            mediaTitle,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop ?: poster
            this.plot = plotOverview
            this.year = releaseYear
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
        val season = loadData.season ?: 1
        val episode = loadData.episode ?: 1

        val watchPages = if (isTv) {
            listOf(
                "$mainUrl/watch/tv/$id/$season/$episode",
                "$mainUrl/watch/tv/$id?season=$season&episode=$episode",
                "$altUrl/watch/tv/$id/$season/$episode",
                "$altUrl/watch/tv/$id?season=$season&episode=$episode"
            )
        } else {
            listOf(
                "$mainUrl/watch/movie/$id",
                "$altUrl/watch/movie/$id"
            )
        }

        val candidates = linkedSetOf<String>()
        val emitted = mutableSetOf<String>()

        val urlRegex = Regex(
            """https?://[^\s"'<>\\]+""",
            RegexOption.IGNORE_CASE
        )

        // 1. Extract actual sources from watch pages
        for (watchPage in watchPages) {
            try {
                val response = app.get(
                    watchPage,
                    headers = requestHeaders,
                    timeout = 20
                )

                if (response.code != 200) continue

                val html = response.text
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&")

                val document = Jsoup.parse(
                    html,
                    watchPage
                )

                // iframe sources
                document.select(
                    "iframe[src], iframe[data-src]"
                ).forEach { element ->
                    val src = element.absUrl("src")
                        .ifBlank { element.absUrl("data-src") }

                    if (src.startsWith("http")) {
                        candidates.add(src)
                    }
                }

                // Video sources
                document.select(
                    "video[src], source[src]"
                ).forEach { element ->
                    val src = element.absUrl("src")

                    if (src.startsWith("http")) {
                        candidates.add(src)
                    }
                }

                // JavaScript / HTML URLs
                urlRegex.findAll(html).forEach { match ->
                    val src = match.value
                        .trimEnd(',', ';', ')', ']', '}')

                    if (isSupportedSource(src)) {
                        candidates.add(src)
                    }
                }

                if (candidates.isNotEmpty()) break

            } catch (_: Exception) {
            }
        }

        if (candidates.isEmpty()) return false

        // 2. Extract each discovered server
        val extractor = GhostFlixExtractor()

        for (sourceUrl in candidates) {
            val serverName = when {
                sourceUrl.contains("cinesrc.st", true) -> "CineSrc"
                sourceUrl.contains("vidrift.net", true) -> "Vidrift"
                sourceUrl.contains("goldenkite.top", true) -> "GreenKite"
                sourceUrl.contains("vidy.st", true) -> "Vidy"
                sourceUrl.contains("vidcore.org", true) -> "Vidcore"
                sourceUrl.contains("vidlink.pro", true) -> "VidLink"
                sourceUrl.contains("vidsrc", true) -> "VidSrc"
                sourceUrl.contains("embed.su", true) -> "EmbedSu"
                else -> "Ghostflix Server"
            }

            val collectedLinks = mutableListOf<ExtractorLink>()

            try {
                if (isDirectStream(sourceUrl)) {
                    extractor.getUrl(
                        sourceUrl,
                        "$mainUrl/",
                        subtitleCallback
                    ) { link ->
                        collectedLinks.add(link)
                    }
                } else {
                    // Embed extraction
                    loadExtractor(
                        sourceUrl,
                        "$mainUrl/",
                        subtitleCallback
                    ) { link ->
                        collectedLinks.add(link)
                    }

                    // Custom extraction
                    extractor.getUrl(
                        sourceUrl,
                        "$mainUrl/",
                        subtitleCallback
                    ) { link ->
                        collectedLinks.add(link)
                    }
                }

                for (link in collectedLinks) {
                    if (emitted.add(link.url)) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$serverName - ${link.name}",
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.headers = link.headers
                            }
                        )
                    }
                }

            } catch (_: Exception) {
            }
        }

        return emitted.isNotEmpty()
    }

    private fun isSupportedSource(url: String): Boolean {
        val value = url.lowercase()

        return value.contains(".m3u8") ||
            value.contains(".mp4") ||
            value.contains("/api/orion/") ||
            value.contains("/api/playlist/")
    }

    private fun isDirectStream(url: String): Boolean {
        return isSupportedSource(url)
    }

    data class LoadData(
        @JsonProperty("id")
        val id: String,

        @JsonProperty("type")
        val type: String,

        @JsonProperty("season")
        val season: Int? = null,

        @JsonProperty("episode")
        val episode: Int? = null
    )

    data class TmdbPageResponse(
        @JsonProperty("results")
        val results: List<TrendingItem>? = null
    )

    data class TrendingItem(
        @JsonProperty("id")
        val id: Int? = null,

        @JsonProperty("title")
        val title: String? = null,

        @JsonProperty("name")
        val name: String? = null,

        @JsonProperty("original_title")
        val originalTitle: String? = null,

        @JsonProperty("original_name")
        val originalName: String? = null,

        @JsonProperty("poster_path")
        val posterPath: String? = null,

        @JsonProperty("media_type")
        val mediaType: String? = null
    )

    data class TmdbDetails(
        @JsonProperty("title")
        val title: String? = null,

        @JsonProperty("name")
        val name: String? = null,

        @JsonProperty("overview")
        val overview: String? = null,

        @JsonProperty("poster_path")
        val posterPath: String? = null,

        @JsonProperty("backdrop_path")
        val backdropPath: String? = null,

        @JsonProperty("release_date")
        val releaseDate: String? = null,

        @JsonProperty("first_air_date")
        val firstAirDate: String? = null,

        @JsonProperty("seasons")
        val seasons: List<TmdbSeason>? = null
    )

    data class TmdbSeason(
        @JsonProperty("season_number")
        val seasonNumber: Int? = null,

        @JsonProperty("episode_count")
        val episodeCount: Int? = null
    )

    data class TmdbSeasonDetails(
        @JsonProperty("episodes")
        val episodes: List<TmdbEpisode>? = null
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number")
        val episodeNumber: Int? = null,

        @JsonProperty("name")
        val name: String? = null,

        @JsonProperty("overview")
        val overview: String? = null,

        @JsonProperty("still_path")
        val stillPath: String? = null
    )
}
