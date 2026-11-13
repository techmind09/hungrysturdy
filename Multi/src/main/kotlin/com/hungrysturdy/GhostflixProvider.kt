package com.hungrysturdy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
        private const val TMDB_API_KEY = "e76d52f346700d0edf06fb2e938f98ad"
        private const val TMDB_ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJlNzZkNTJmMzQ2NzAwZDBlZGYwNmZiMmU5MzhmOThhZCIsIm5iZiI6MTc3NzkwNzk4NS4xNiwic3ViIjoiNjlmOGI5MTEzODdlMWExNWY3MzI3MWQxIiwic2NvcGVzIjpbImFwaV9yZWFkIl0sInZlcnNpb24iOjF9.xKkhmPxRCBvBPok8br1xxjIMEHfkAO_jQZQ5nBkX2Cc"
    }

    override val mainPage = mainPageOf(
        "" to "Trending Now",
        "movies" to "Movies",
        "tv-shows" to "TV Shows",
        "anime" to "Anime",
        "animation" to "Animation",
        "asian-dramas?region=KR&sort=popular" to "Asian Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        val document = app.get(url).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.nx-row").forEach { row ->
            val title = row.selectFirst("h2")?.text()?.trim() ?: return@forEach
            if (title.contains("Continue Watching", ignoreCase = true)) return@forEach

            val items = row.select("a.group\\/card, a[href^='/movie/'], a[href^='/tv/']").mapNotNull { card ->
                card.toSearchResult()
            }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        val trendingItems = extractNextFlightTrending(document)
        if (trendingItems.isNotEmpty()) {
            val sectionTitle = if (request.data.isEmpty()) "Trending" else request.name
            homePageList.add(0, HomePageList(sectionTitle, trendingItems))
        }

        return newHomePageResponse(homePageList, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val isTv = href.startsWith("/tv/") || href.contains("type=tv")
        val idMatch = Regex("""/(?:movie|tv)/(\d+)""").find(href)?.groupValues?.get(1)
            ?: Regex("""id=(\d+)""").find(href)?.groupValues?.get(1)
            ?: return null

        val title = this.selectFirst("p.truncate, p.line-clamp-2")?.text()?.trim()
            ?: this.attr("aria-label").substringBefore(", rated").trim()
            .takeIf { it.isNotBlank() } ?: "Ghostflix Title #$idMatch"

        val imgElem = this.selectFirst("img")
        val posterUrl = imgElem?.attr("src")?.takeIf { it.startsWith("http") }
            ?: imgElem?.attr("data-src")
            ?: "$TMDB_IMAGE_BASE/t/p/w500"

        val targetUrl = if (isTv) "$mainUrl/tv/$idMatch" else "$mainUrl/movie/$idMatch"

        return if (isTv) {
            newTvSeriesSearchResponse(title, targetUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
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
        val url = "$mainUrl/browse?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document

        val htmlResults = document.select("a[href^='/movie/'], a[href^='/tv/']").mapNotNull { it.toSearchResult() }
        if (htmlResults.isNotEmpty()) return htmlResults

        return extractNextFlightTrending(document).filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/") || url.contains("type=tv")
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""id=(\d+)""").find(url)?.groupValues?.get(1)
            ?: return null

        // Fallback to TMDB API if website page details fail
        val mediaType = if (isTv) "tv" else "movie"
        val tmdbUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$TMDB_API_KEY"
        
        var title = "Ghostflix Title #$tmdbId"
        var description = "No description available."
        var poster: String? = null

        try {
            val tmdbResp = app.get(tmdbUrl, headers = mapOf("Authorization" to "Bearer $TMDB_ACCESS_TOKEN")).parsedSafe<TmdbDetails>()
            if (tmdbResp != null) {
                title = tmdbResp.title ?: tmdbResp.name ?: title
                description = tmdbResp.overview ?: description
                poster = tmdbResp.posterPath?.let { "$TMDB_IMAGE_BASE$it" }
            }
        } catch (_: Exception) {}

        if (poster == null) {
            val docUrl = if (isTv) "$mainUrl/tv/$tmdbId" else "$mainUrl/movie/$tmdbId"
            val document = try { app.get(docUrl).document } catch (e: Exception) { Document(mainUrl) }
            title = document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - ")?.trim() ?: title
            description = document.selectFirst("meta[property='og:description']")?.attr("content") ?: description
            poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        }

        return if (isTv) {
            val episodes = mutableListOf<Episode>()
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
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val playData = mapper.writeValueAsString(LoadData(id = tmdbId, type = "movie"))
            newMovieLoadResponse(title, url, TvType.Movie, playData) {
                this.posterUrl = poster
                this.plot = description
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

        val servers = listOf(
            "vidbolt", "vidcore", "flix", "vidly", "orion", 
            "vidrift", "vidqueen", "hindi", "hindi-new", "cinezo"
        )

        servers.forEach { server ->
            val playUrl = if (loadData.type == "tv") {
                "$mainUrl/play?id=${loadData.id}&type=tv&season=${loadData.season ?: 1}&episode=${loadData.episode ?: 1}&server=$server"
            } else {
                "$mainUrl/play?id=${loadData.id}&type=movie&server=$server"
            }

            try {
                val response = app.get(playUrl, referer = mainUrl)
                val pageText = response.text

                Regex("""https?://[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*""").findAll(pageText).forEach { match ->
                    val streamUrl = match.value.replace("\\u0026", "&")
                    val linkType = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name Direct (${server.replaceFirstChar { it.uppercase() }})",
                            url = streamUrl,
                            type = linkType
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }

                response.document.select("iframe[src]").forEach { iframe ->
                    val embedUrl = iframe.attr("src")
                    if (embedUrl.isNotBlank()) {
                        loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                    }
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

    data class TrendingItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )

    data class TmdbDetails(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null
    )
}
