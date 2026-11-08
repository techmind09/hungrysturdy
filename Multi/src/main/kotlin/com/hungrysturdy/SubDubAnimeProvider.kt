package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SubDubAnimeProvider : MainAPI() {
    override var mainUrl = "https://www.animehubindia.com"
    override var name = "Sub Dub Anime"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.AsianDrama
    )
    override var lang = "hi"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Newly Released",
        "$mainUrl/search/label/Isekai" to "Isekai",
        "$mainUrl/search/label/Action" to "Action",
        "$mainUrl/search/label/Shounen" to "Shounen",
        "$mainUrl/search/label/Fantasy" to "Fantasy",
        "$mainUrl/search/label/Supernatural" to "Supernatural",
        "$mainUrl/search/label/Movie" to "Movies"
    )

    private val headers = mapOf(
        "Cookie" to "_ga=GA1.1.1058041228.1794107191; dom3ic8zudi28v8lr6fgphwffqoz0j6c=01a1b91f-32a7-72ff-9db5-ce7dc129fecc; pp_main_481484428e47e8b2a541aaafc2544461=1; vrk4n8fqhwc3jzy7pbsmgt6dx5lha2u9=01a1b91f-32a7-72ff-9db5-ce7dc129fecc_2; pp_sub_481484428e47e8b2a541aaafc2544461=1; _ga_P600KN8YQ0=GS2.1.s1794107191$o1$g1$t1794107625$j60$l0$h0",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data, headers = headers).document
        val home = document.select(".p-card, .customAgata, .recent-item, article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select(".p-title, .entry-title, h2, h3").text().trim()
        val href = fixUrl(this.select("a").attr("href")) ?: return null
        val posterUrl = fixUrl(this.select("img").attr("src"))
        
        if (title.isEmpty() || href.isEmpty()) return null

        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query", headers = headers).document
        return document.select(".p-card, .search-item, article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.select("h1, .entry-title").first()?.text()?.trim() ?: "No Title"
        val poster = fixUrl(document.select(".post-thumbnail img, .entry-content img, .p-poster img").first()?.attr("src"))
        val description = document.select(".synopsis, .description, .entry-content p").text().trim()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.episodes = listOf(
                newEpisode(url) {
                    name = title
                }
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        
        document.select("iframe, embed").forEach { element ->
            val videoUrl = element.attr("src")
            if (videoUrl.isNotEmpty()) {
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
