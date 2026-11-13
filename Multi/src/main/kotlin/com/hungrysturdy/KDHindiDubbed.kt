package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class KDHindiDubbed : MainAPI() {
    override var mainUrl = "https://kdhindidubbed.cfd"
    override var name = "KDHindiDubbed"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest",
        "$mainUrl/category/korean-drama/page/" to "Korean Drama",
        "$mainUrl/category/chinese-drama/page/" to "Chinese Drama",
        "$mainUrl/category/hindi-dubbed/page/" to "Hindi Dubbed",
        "$mainUrl/category/movie/page/" to "Movie",
        "$mainUrl/category/action/page/" to "Action",
        "$mainUrl/category/romance/page/" to "Romance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = "${request.data}$page"
        val document = app.get(pageUrl).document
        val home = document.select("article.herald-lay-c, article.post").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".entry-title a, h2.entry-title a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        if (title.isBlank() || href.isBlank()) return null

        val poster = this.selectFirst("img.wp-post-image, img.attachment-herald-lay-c")?.attr("src") 
            ?: this.selectFirst("img")?.attr("src")
            ?: ""

        return if (title.contains("Movie", ignoreCase = true)) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.herald-lay-c, article.post").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
        
        val poster = document.selectFirst(".herald-post-thumbnail img, .entry-content img, meta[property='og:image']")?.let {
            it.attr("src").ifBlank { it.attr("content") }
        } ?: ""
        
        val description = document.select(".entry-content p").joinToString("\n\n") { it.text() }
            .takeIf { it.isNotBlank() } ?: "No Plot Found"

        val tvType = if (title.contains("Movie", ignoreCase = true)) TvType.Movie else TvType.TvSeries

        val episodeMap = mutableMapOf<Int, MutableList<EpisodeLink>>()
        val allLinksForMovie = mutableListOf<EpisodeLink>()

        val contentLinks = document.select(".entry-content a[href]")
        
        var fallbackEp = 1
        for (link in contentLinks) {
            val href = link.attr("href").trim()
            val text = link.text().trim()
            
            // FIX 2: Strict filters to ignore images and invalid links
            if (href.isBlank() || href.contains("/category/") || href.contains("/tag/") || href.contains("t.me") ||
                href.contains("blogger.googleusercontent.com/img") || 
                href.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE))) {
                continue
            }

            if (href.startsWith("http")) {
                val epNumText = Regex("""(?i)(?:episode|ep)\.?\s*0*(\d{1,3})""").find(text)?.groupValues?.get(1)
                    ?: Regex("""(?i)episode-(\d+)""").find(href)?.groupValues?.get(1)
                
                val epNum = epNumText?.toIntOrNull() ?: fallbackEp

                episodeMap.getOrPut(epNum) { mutableListOf() }.add(EpisodeLink(href, epNum))
                allLinksForMovie.add(EpisodeLink(href, null))
                
                if (epNumText == null) fallbackEp++
            }
        }

        return if (tvType == TvType.Movie) {
            val movieData = mapper.writeValueAsString(allLinksForMovie.distinctBy { it.source })
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = episodeMap.map { (epNum, links) ->
                val distinctLinks = links.distinctBy { it.source }
                newEpisode(mapper.writeValueAsString(distinctLinks)) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            }.ifEmpty {
                listOf(
                    newEpisode(mapper.writeValueAsString(allLinksForMovie.distinctBy { it.source })) {
                        this.name = "Full Episodes"
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
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
        return try {
            val sources = tryParseJson<ArrayList<EpisodeLink>>(data)
            var found = false

            if (!sources.isNullOrEmpty()) {
                for (sourceObj in sources) {
                    KDHindiDubbedExtractor().getUrl(sourceObj.source, data, subtitleCallback, callback)
                    found = true
                }
                return found
            }

            if (data.startsWith("http")) {
                KDHindiDubbedExtractor().getUrl(data, data, subtitleCallback, callback)
                found = true
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    data class EpisodeLink(val source: String, val episode: Int? = null)
}
