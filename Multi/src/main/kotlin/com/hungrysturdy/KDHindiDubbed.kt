package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class KDHindiDubbed : MainAPI() {
    // Note: mainUrl is kdhindidubbed.cfd based on your HTML source
    override var mainUrl = "https://kdhindidubbed.cfd"
    override var name = "KDHindiDubbed"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest",
        "$mainUrl/category/action/page/" to "Action",
        "$mainUrl/category/adventure/page/" to "Adventure",
        "$mainUrl/category/arabic-subtitle/page/" to "Arabic Subtitle",
        "$mainUrl/category/business/page/" to "Business",
        "$mainUrl/category/chinese-drama/page/" to "Chinese Drama",
        "$mainUrl/category/chinese-language/page/" to "Chinese Language",
        "$mainUrl/category/comedy/page/" to "Comedy",
        "$mainUrl/category/crime/page/" to "Crime",
        "$mainUrl/category/drama/page/" to "Drama",
        "$mainUrl/category/english-dubbed/page/" to "English Dubbed",
        "$mainUrl/category/family/page/" to "Family",
        "$mainUrl/category/fantasy/page/" to "Fantasy",
        "$mainUrl/category/filipino-language/page/" to "Filipino Language",
        "$mainUrl/category/food/page/" to "Food",
        "$mainUrl/category/hindi-dubbed/page/" to "Hindi Dubbed",
        "$mainUrl/category/historical/page/" to "Historical",
        "$mainUrl/category/horror/page/" to "Horror",
        "$mainUrl/category/japanese-drama/page/" to "Japanese Drama",
        "$mainUrl/category/japanese-language/page/" to "Japanese Language",
        "$mainUrl/category/korean-drama/page/" to "Korean Drama",
        "$mainUrl/category/korean-language/page/" to "Korean Language",
        "$mainUrl/category/law/page/" to "Law",
        "$mainUrl/category/life/page/" to "Life",
        "$mainUrl/category/medical/page/" to "Medical",
        "$mainUrl/category/military/page/" to "Military",
        "$mainUrl/category/movie/page/" to "Movie",
        "$mainUrl/category/music/page/" to "Music",
        "$mainUrl/category/mystery/page/" to "Mystery",
        "$mainUrl/category/philippines-drama/page/" to "Philippines Drama",
        "$mainUrl/category/political/page/" to "Political",
        "$mainUrl/category/psychological/page/" to "Psychological",
        "$mainUrl/category/reality-show/page/" to "Reality Show",
        "$mainUrl/category/romance/page/" to "Romance",
        "$mainUrl/category/school/page/" to "School",
        "$mainUrl/category/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/category/sports/page/" to "Sports",
        "$mainUrl/category/supernatural/page/" to "Supernatural",
        "$mainUrl/category/suspense/page/" to "Suspense",
        "$mainUrl/category/thai-language/page/" to "Thai Language",
        "$mainUrl/category/thailand-drama/page/" to "Thailand Drama",
        "$mainUrl/category/thriller/page/" to "Thriller",
        "$mainUrl/category/youth/page/" to "Youth"
    )
        

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = "${request.data}$page"
        val document = app.get(pageUrl).document
        
        // Match the exact HTML structure from the website
        val home = document.select("article.herald-lay-c, article.post").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".entry-title a, h2.entry-title a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        if (title.isBlank() || href.isBlank()) return null

        // Explicitly get the image inside THIS specific article block
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

        // Scan all links in the content area
        val contentLinks = document.select(".entry-content a[href]")
        
        var fallbackEp = 1
        for (link in contentLinks) {
            val href = link.attr("href").trim()
            val text = link.text().trim()
            
            // Ignore internal categories, tags, or telegram links
            if (href.isBlank() || href.contains("/category/") || href.contains("/tag/") || href.contains("t.me")) continue

            if (href.startsWith("http")) {
                // Try to find episode number from the link text OR the URL itself
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
                    // Send to the Extractor class
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
