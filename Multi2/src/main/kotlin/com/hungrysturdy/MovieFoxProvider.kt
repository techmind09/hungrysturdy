package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class MovieFoxProvider : MainAPI() {
    override var mainUrl = "https://moviefox.pro"
    override var name = "MovieFox"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trending Now",
        "$mainUrl/movies" to "Movies",
        "$mainUrl/tv" to "TV Shows",
        "$mainUrl/anime" to "Anime",
        "$mainUrl/dramas" to "Dramas"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = mutableListOf<HomePageList>()

        val rows = document.select("section")
        for (row in rows) {
            val title = row.select("h2").text().ifEmpty { continue }
            val elements = row.select("a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/]")
            
            val items = elements.mapNotNull { element ->
                toSearchResult(element)
            }.distinctBy { it.url }

            if (items.isNotEmpty()) {
                home.add(HomePageList(title, items))
            }
        }

        return newHomePageResponse(home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.attr("href")
        val title = element.attr("aria-label")
            .replace(Regex("^Number \\d+:\\s*"), "")
            .substringBefore(" (20")
            .ifEmpty { element.select("img").attr("alt") }
        
        val poster = element.select("img").attr("src").ifEmpty { 
            element.select("img").attr("data-src") 
        }

        if (href.isEmpty() || title.isEmpty()) return null

        val fixedUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        val isTv = href.contains("/tv/") || href.contains("/dramas/")
        val type = if (isTv) TvType.TvSeries else TvType.Movie

        return if (isTv) {
            newTvSeriesSearchResponse(title, fixedUrl, type) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, fixedUrl, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/]").mapNotNull { element ->
            toSearchResult(element)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("h1").text().ifEmpty { document.select("title").text().substringBefore("—") }
        val poster = document.select("img.object-cover, img[data-nimg='fill']").attr("src")
        val description = document.select("p.text-muted, .text-fg\\/80").text()
        
        val isTv = url.contains("/tv/") || url.contains("/dramas/")

        return if (isTv) {
            val episodes = mutableListOf<Episode>()
            document.select("a[href*='/watch/'], a[href*='/season/'], .episode-item").forEachIndexed { index, element ->
                val epUrl = element.attr("href")
                val epName = element.select(".ep-title, span").text().ifEmpty { element.text() }.ifEmpty { "Episode ${index + 1}" }
                
                val seasonMatch = Regex("s(\\d+)", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("season[-/]?(\\d+)", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                val epMatch = Regex("e(\\d+)", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("episode[-/]?(\\d+)", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

                episodes.add(
                    newEpisode(if (epUrl.startsWith("http")) epUrl else "$mainUrl$epUrl") {
                        this.name = epName
                        this.season = seasonMatch
                        this.episode = epMatch
                    }
                )
            }

            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { Pair(it.season, it.episode) }) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // JSON Response Models for MovieFox Debrid API
    data class StreamResponse(
        val streams: List<StreamItem>
    )

    data class StreamItem(
        val url: String,
        val type: String,
        val quality: String?,
        val subtitles: List<SubtitleItem>?
    )

    data class SubtitleItem(
        val language: String,
        val label: String,
        val url: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isTv = data.contains("/tv/") || data.contains("/dramas/") || data.contains("/s/") || data.contains("season")
        val idMatch = Regex("-(?:movie|tv|dramas)-(\\d+)|(?:tv|movie)/(\\d+)").find(data)
        val mediaId = idMatch?.groupValues?.let { it[1].ifEmpty { it[2] } } ?: "1368337"

        val apiUrl = if (isTv) {
            val season = Regex("s(?:eason)?[-/]?(\\d+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1) ?: "1"
            val episode = Regex("e(?:pisode)?[-/]?(\\d+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1) ?: "1"
            "$mainUrl/api/stream/debrid?type=tv&id=$mediaId&season=$season&episode=$episode"
        } else {
            "$mainUrl/api/stream/debrid?type=movie&id=$mediaId"
        }

        try {
            val response = app.get(apiUrl).text
            val parsedJson = parseJson<StreamResponse>(response)

            parsedJson.streams.forEach { stream ->
                val streamUrl = if (stream.url.startsWith("http")) stream.url else "$mainUrl${stream.url}"
                
                stream.subtitles?.forEach { sub ->
                    val subUrl = if (sub.url.startsWith("http")) sub.url else "$mainUrl${sub.url}"
                    subtitleCallback(
                        SubtitleFile(
                            lang = sub.label,
                            url = subUrl
                        )
                    )
                }

                callback(
                    ExtractorLink(
                        source = name,
                        name = "MovieFox (${stream.quality ?: "HD"})",
                        url = streamUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.HD.value,
                        isM3u8 = stream.type == "hls" || stream.url.contains(".m3u8")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }
}
