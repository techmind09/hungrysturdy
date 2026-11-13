package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DramaVerseProvider : MainAPI() {
    override var mainUrl = "https://mydverse.com"
    override var name = "DramaVerse"
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "hi"
    override val hasMainPage = true

    // Included cookies
    private val cookies = mapOf(
        "_ga_5TVV62JEB6" to "GS2.1.s1794549640\$o1\$g0\$t1794549640\$j60\$l0\$h0",
        "_ga" to "GA1.1.1175613762.1794549641",
        "_clck" to "1nykysd%5E2%5Egb4%5E0%5E2508",
        "_clsk" to "5czkhx%5E1794549642448%5E1%5E1%5Ea.clarity.ms%2Fcollect"
    )

    // Main page categories matching the site menu
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Updates",
        "$mainUrl/korean/" to "Korean Drama",
        "$mainUrl/chinese/" to "Chinese Drama",
        "$mainUrl/all-drama-list/" to "All Drama List",
        "$mainUrl/upcoming/" to "Upcoming"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.endsWith("/")) {
                "${request.data}page/$page/"
            } else {
                "${request.data}/page/$page/"
            }
        }

        val document = app.get(url, cookies = cookies).document
        val home = document.select("ul.posts-items > li.post-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.post-title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("img")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }

        return newMovieSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", cookies = cookies).document
        return document.select("ul.posts-items > li.post-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, cookies = cookies).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: "Drama"
        val poster = document.selectFirst(".entry-content img, .post-thumb img")?.absUrl("src")
        val description = document.selectFirst(".entry-content p")?.text()

        val episodes = mutableListOf<Episode>()
        val btnSlides = document.select("a.btn-slide")
        
        if (btnSlides.isNotEmpty()) {
            btnSlides.forEachIndexed { index, element ->
                val epName = element.selectFirst(".btn-title")?.text() ?: "Episode ${index + 1}"
                val epUrl = element.attr("href")
                episodes.add(Episode(data = epUrl, name = epName, episode = index + 1))
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(Episode(data = url, name = "Full Episode / Stream", episode = 1))
        }

        return newMovieLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // Step 1: Get the initial page (Blogspot / Safelink / DramaVerse page)
        val document = app.get(data, cookies = cookies).document
        
        // Find iframe or direct link pointing to xcloud
        val iframe = document.selectFirst("iframe")?.absUrl("src")
        val targetUrl = iframe ?: data

        // Step 2: Handle XCloud / xcloud.tattoo / stream.xcloud1.lol links
        if (targetUrl.contains("xcloud")) {
            // If it's the info page (xcloud.tattoo), find the streaming or download link first
            val xCloudDoc = app.get(targetUrl).document
            
            // Check for "Online Streaming", "Watch Online", or direct stream link if it redirects
            val watchOnlineBtn = xCloudDoc.selectFirst("a:contains(Streaming), a:contains(Watch Online), a.btn-success, a[href*='stream']")?.absUrl("href")
            
            val streamPageUrl = watchOnlineBtn ?: targetUrl
            val streamDoc = if (streamPageUrl != targetUrl) app.get(streamPageUrl).document else xCloudDoc

            // Look for video source in the player page (`<video src="...">` or `<source src="...">`)
            val videoSrc = streamDoc.selectFirst("video source")?.absUrl("src")
                ?: streamDoc.selectFirst("video")?.absUrl("src")
                ?: streamDoc.selectFirst("a:contains(Download), a.download")?.absUrl("href")

            // Fallback: Check script tags for direct video url patterns if not found in tags directly
            val htmlContent = streamDoc.html()
            val extractedVideoUrl = videoSrc ?: Regex("[\"'](https?://[^\"']+\\.(m3u8|mp4|mkv)[^\"']*)[\"']").find(htmlContent)?.groupValues?.get(1)

            if (!extractedVideoUrl.isNullOrEmpty()) {
                callback.invoke(
                    ExtractorLink(
                        name = "XCloud",
                        source = "XCloud",
                        url = extractedVideoUrl,
                        referer = "https://xcloud.tattoo/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = extractedVideoUrl.contains(".m3u8")
                    )
                )
                return true
            }
        }

        // Fallback to standard extractors
        loadExtractor(targetUrl, "$mainUrl/", subtitleCallback, callback)
        return true
    }
}
