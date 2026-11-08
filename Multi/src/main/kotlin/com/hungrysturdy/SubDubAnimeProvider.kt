package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SubDubAnimeProvider : MainAPI() {
    override var mainUrl = "https://www.subdubanime.site"
    override var name = "SubDubAnime"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.AsianDrama
    )
    override var lang = "hi"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/search/label/Ongoing" to "Ongoing Anime",
        "$mainUrl/search/label/Hindi%20Dubbed%20ORG" to "Hindi Dubbed (ORG)",
        "$mainUrl/search/label/Hindi%20Fan%20Dubbed" to "Hindi Fan Dubbed",
        "$mainUrl/search/label/English%20Subbed" to "English Subbed",
        "$mainUrl/search/label/English%20Dubbed" to "English Dubbed",
        "$mainUrl/search/label/Movie" to "Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select(".p-card, .customAgata").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select(".p-title, .entry-title").text().trim()
        val href = fixUrl(this.select("a").attr("href"))
        val posterUrl = fixUrl(this.select("img").attr("src"))
        
        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select(".p-card, .search-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("h1, .entry-title").first()?.text()?.trim() ?: "No Title"
        val poster = fixUrl(document.select(".post-thumbnail img, .entry-content img, .p-poster img").first()?.attr("src"))
        val description = document.select(".synopsis, .description, .entry-content p").text().trim()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.episodes = listOf(
                Episode(
                    data = url,
                    name = title
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Extract embed sources or iframe elements if present
        document.select("iframe, embed").forEach { element ->
            val videoUrl = element.attr("src")
            if (videoUrl.isNotEmpty()) {
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
