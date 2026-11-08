package com.hungrysturdy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SubDubAnimeProvider : MainAPI() {
    override var mainUrl = "https://www.subdubanime.site"
    override var name = "SubDubAnime"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries)

    private val apiUrl = "https://blakiteapi.xyz/api/getAllAnime.php"

    // Custom headers with provided Cookie session string
    private val defaultHeaders = mapOf(
        "Cookie" to "_ga=GA1.1.1058041228.1794107191; dom3ic8zudi28v8lr6fgphwffqoz0j6c=01a1b91f-32a7-72ff-9db5-ce7dc129fecc; pp_main_481484428e47e8b2a541aaafc2544461=1; vrk4n8fqhwc3jzy7pbsmgt6dx5lha2u9=01a1b91f-32a7-72ff-9db5-ce7dc129fecc_2; pp_sub_481484428e47e8b2a541aaafc2544461=1; _ga_P600KN8YQ0=GS2.1.s1794107191$o1$g1$t1794107625$j60$l0$h0",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$apiUrl" to "Recently Updated / All Anime",
        "$mainUrl/search/label/Ongoing" to "Ongoing Anime",
        "$mainUrl/search/label/Movie" to "Movies",
        "$mainUrl/search/label/Series" to "Series",
        "$mainUrl/search/label/Drama" to "Drama & Web Series",
        "$mainUrl/search/label/Hindi%20Dubbed%20ORG" to "Hindi Dubbed (Org)",
        "$mainUrl/search/label/FanDub" to "FanDub",
        "$mainUrl/search/label/English%20Subbed" to "English Subbed"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val list = ArrayList<SearchResponse>()
        
        if (request.data.contains("blakiteapi.xyz")) {
            val res = app.get(request.data, headers = defaultHeaders).parsedSafe<ApiResponse>()
            res?.data?.let { data ->
                // Process Movies
                data.movies?.forEach { (key, movie) ->
                    list.add(newAnimeSearchResponse(movie.title ?: "Unknown", "$mainUrl/2026/11/streaming.html?type=Movie&id=$key", TvType.AnimeMovie) {
                        this.posterUrl = movie.IMAGES?.poster ?: movie.IMAGES?.backdrop
                    })
                }
                // Process Series
                data.series?.forEach { (key, series) ->
                    series.seasons?.forEach { (sNum, season) ->
                        list.add(newAnimeSearchResponse("${series.title} Season $sNum", "$mainUrl/2026/11/streaming.html?type=Series&id=$key&s=$sNum", TvType.Anime) {
                            this.posterUrl = series.IMAGES?.poster ?: series.IMAGES?.backdrop
                        })
                    }
                }
                // Process Dramas
                data.dramas?.forEach { (key, drama) ->
                    drama.seasons?.forEach { (sNum, season) ->
                        list.add(newAnimeSearchResponse("${drama.title} Season $sNum", "$mainUrl/2026/11/streaming.html?type=Drama&id=$key&s=$sNum", TvType.TvSeries) {
                            this.posterUrl = drama.IMAGES?.poster ?: drama.IMAGES?.backdrop
                        })
                    }
                }
            }
        } else {
            // Fallback to HTML scraping for label search pages with cookies applied
            val doc = app.get(request.data, headers = defaultHeaders).document
            doc.select(".p-card, .customAgata, .post-item").forEach { element ->
                val title = element.select(".p-title, .entry-title, h2").text()
                val href = fixUrl(element.select("a").attr("href"))
                val poster = element.select("img").attr("src").ifEmpty { element.select("img").attr("data-original") }
                
                if (title.isNotEmpty() && href.isNotEmpty()) {
                    list.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = poster
                    })
                }
            }
        }

        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchList = ArrayList<SearchResponse>()
        val res = app.get(apiUrl, headers = defaultHeaders).parsedSafe<ApiResponse>()
        
        val q = query.lowercase().trim()
        res?.data?.let { data ->
            fun match(title: String?) = title?.lowercase()?.contains(q) == true

            data.movies?.forEach { (key, movie) ->
                if (match(movie.title)) {
                    searchList.add(newAnimeSearchResponse(movie.title!!, "$mainUrl/2026/11/streaming.html?type=Movie&id=$key", TvType.AnimeMovie) {
                        this.posterUrl = movie.IMAGES?.poster
                    })
                }
            }
            data.series?.forEach { (key, series) ->
                if (match(series.title)) {
                    series.seasons?.forEach { (sNum, _) ->
                        searchList.add(newAnimeSearchResponse("${series.title} Season $sNum", "$mainUrl/2026/11/streaming.html?type=Series&id=$key&s=$sNum", TvType.Anime) {
                            this.posterUrl = series.IMAGES?.poster
                        })
                    }
                }
            }
            data.dramas?.forEach { (key, drama) ->
                if (match(drama.title)) {
                    drama.seasons?.forEach { (sNum, _) ->
                        searchList.add(newAnimeSearchResponse("${drama.title} Season $sNum", "$mainUrl/2026/11/streaming.html?type=Drama&id=$key&s=$sNum", TvType.TvSeries) {
                            this.posterUrl = drama.IMAGES?.poster
                        })
                    }
                }
            }
        }
        return searchList
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = defaultHeaders).document
        
        val title = doc.select("h1, .post-title, .title").text().ifEmpty { "Sub Dub Anime Item" }
        val poster = doc.select(".poster img, #info img, .thumb img, .entry-content img").attr("src")
        val description = doc.select(".synopsis, .description, .entry-content p").text()

        if (url.contains("streaming.html")) {
            val episodes = ArrayList<Episode>()
            val totalEp = 12 
            for (i in 1..totalEp) {
                episodes.add(
                    Episode(
                        data = "$url&ep=$i",
                        name = "Episode $i",
                        episode = i
                    )
                )
            }
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                this.episodes = episodes
            }
        }

        return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = defaultHeaders).document
        doc.select("iframe, embed, source").forEach { element ->
            val src = element.attr("src").ifEmpty { element.attr("data-src") }
            if (src.isNotEmpty()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        return true
    }

    // Data Classes for JSON API mapping
    data class ApiResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("data") val data: ApiData?
    )

    data class ApiData(
        @JsonProperty("movies") val movies: Map<String, MediaItem>?,
        @JsonProperty("series") val series: Map<String, MediaItem>?,
        @JsonProperty("dramas") val dramas: Map<String, MediaItem>?
    )

    data class MediaItem(
        @JsonProperty("title") val title: String?,
        @JsonProperty("language") val language: String?,
        @JsonProperty("IMAGES") val IMAGES: Images?,
        @JsonProperty("seasons") val seasons: Map<String, SeasonItem>?
    )

    data class Images(
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("backdrop") val backdrop: String?,
        @JsonProperty("thumbnail") val thumbnail: String?
    )

    data class SeasonItem(
        @JsonProperty("seasonNumber") val seasonNumber: Int?,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int?,
        @JsonProperty("status") val status: String?
    )
}
