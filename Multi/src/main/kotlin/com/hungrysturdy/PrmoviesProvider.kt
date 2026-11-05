package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller

class PrmoviesProvider : MainAPI() {
    override var mainUrl = "https://prmovies.ventures"
    override var name = "Prmovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val cfKiller by lazy { CloudflareKiller() }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "Home",
        "/movies/bollywood/" to "Bollywood",
        "/movies/hollywood/" to "Hollywood",
        "/hindi-series/" to "Hindi Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }

        val document = app.get(url, headers = headers, interceptor = cfKiller).document
        val home = document.select("div.item, div.movie-card, div.post-cards > article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("h3 a, h2 a").text().ifEmpty { this.select("a").attr("title") }
        val href = this.select("a").attr("href")
        val posterUrl = this.select("img").attr("src").ifEmpty { this.select("img").attr("data-src") }
    
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("$mainUrl/?s=$query", headers = headers, interceptor = cfKiller).document
        val results = document.select("div.item, div.movie-card, div.search-item").mapNotNull { it.toSearchResult() }
        val hasNext = results.isNotEmpty()
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers, interceptor = cfKiller).document
        val title = document.selectFirst("h1, .entry-title")?.text()?.replace("Watch Online", "")?.trim() ?: "No Title"
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst(".entry-content, .description")?.text()
        
        val tvtype = if (url.contains("series") || title.contains("Season", ignoreCase = true)) TvType.TvSeries else TvType.Movie

        val links = mutableListOf<String>()
        document.select("iframe, .server-link").forEach { element ->
            val link = element.attr("src").ifEmpty { element.attr("data-url") }
            if (link.isNotEmpty()) links.add(link)
        }

        return if (tvtype == TvType.TvSeries) {
            val episodes = listOf(
                newEpisode(links.firstOrNull() ?: url) {
                    this.name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                }
            )
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, links.firstOrNull() ?: url) {
                this.posterUrl = posterUrl
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
        if (data.startsWith("http")) {
            loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
        }
        return true
    }
}
