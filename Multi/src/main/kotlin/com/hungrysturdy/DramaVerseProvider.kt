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

    private val cookies = mapOf(
        "_ga_5TVV62JEB6" to "GS2.1.s1794549640\$o1\$g0\$t1794549640\$j60\$l0\$h0",
        "_ga" to "GA1.1.1175613762.1794549641",
        "_clck" to "1nykysd%5E2%5Egb4%5E0%5E2508",
        "_clsk" to "5czkhx%5E1794549642448%5E1%5E1%5Ea.clarity.ms%2Fcollect"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
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
        val home = document.select("li.post-item, article.item-list, div.post-element")
            .mapNotNull { it.toSearchResult() }
            .ifEmpty {
                // Fallback selector matching a.post-thumb directly
                document.select("a.post-thumb").mapNotNull { it.ThumbToSearchResult() }
            }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.post-title a, h3.post-title a")
        val thumbElement = this.selectFirst("a.post-thumb")
        val href = titleElement?.absUrl("href") ?: thumbElement?.absUrl("href") ?: return null
        val title = titleElement?.text()?.trim()
            ?: thumbElement?.attr("aria-label")?.substringBefore("|")?.trim()
            ?: return null

        val posterUrl = this.selectFirst("img")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.ThumbToSearchResult(): SearchResponse? {
        val href = this.absUrl("href").ifEmpty { return null }
        val img = this.selectFirst("img")
        val title = this.attr("aria-label").substringBefore("|").trim()
            .ifEmpty { img?.attr("alt")?.trim() ?: return null }
        val posterUrl = img?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", cookies = cookies).document
        return document.select("li.post-item, article.item-list")
            .mapNotNull { it.toSearchResult() }
            .ifEmpty {
                document.select("a.post-thumb").mapNotNull { it.ThumbToSearchResult() }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, cookies = cookies).document
        val title = document.selectFirst("h1.entry-title, h1.post-title")?.text()?.trim() ?: "Drama"
        val poster = document.selectFirst("a.post-thumb img, .post-thumb img, .entry-content img")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }
        val description = document.selectFirst(".entry-content p")?.text()

        val episodes = mutableListOf<Episode>()
        val btnSlides = document.select("a.btn-slide")

        for (slide in btnSlides) {
            val slideUrl = slide.absUrl("href").ifEmpty { continue }
            val slideTitle = slide.selectFirst(".btn-title")?.text()?.trim() ?: "Episode"

            // If the button points to safe.sohojgyan.com ("Episode Wise Links"), scrape episodes inside it
            if (slideUrl.contains("sohojgyan") || slideTitle.contains("Episode Wise", ignoreCase = true)) {
                try {
                    val safeDoc = app.get(slideUrl, referer = url).document
                    val epLinks = safeDoc.select("a[href*='xcloud'], a[href*='hubcloud'], a[href*='gdflix'], .EpFile a, a.btn, .entry-content a")
                        .filter { a ->
                            val href = a.absUrl("href")
                            href.isNotEmpty() && !href.contains("telegram", ignoreCase = true) && href != slideUrl
                        }

                    if (epLinks.isNotEmpty()) {
                        epLinks.forEachIndexed { index, epElement ->
                            val epHref = epElement.absUrl("href")
                            val epText = epElement.text().trim().ifEmpty { "Episode ${index + 1}" }
                            val epNum = Regex("(?i)(?:ep|episode|e)\\s*(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                                ?: (index + 1)

                            episodes.add(
                                newEpisode(epHref) {
                                    this.name = epText
                                    this.episode = epNum
                                }
                            )
                        }
                    } else {
                        episodes.add(
                            newEpisode(slideUrl) {
                                this.name = slideTitle
                                this.episode = episodes.size + 1
                            }
                        )
                    }
                } catch (_: Exception) {
                    episodes.add(
                        newEpisode(slideUrl) {
                            this.name = slideTitle
                            this.episode = episodes.size + 1
                        }
                    )
                }
            } else {
                episodes.add(
                    newEpisode(slideUrl) {
                        this.name = slideTitle
                        this.episode = episodes.size + 1
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Full Episode / Stream"
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
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
        var currentUrl = data

        // 1. If data is still a safe.sohojgyan.com page, resolve XCloud links from it first
        if (currentUrl.contains("sohojgyan")) {
            val safeDoc = app.get(currentUrl).document
            val xcloudLinks = safeDoc.select("a[href*='xcloud'], a[href]").mapNotNull {
                val href = it.absUrl("href")
                if (href.contains("xcloud")) href else null
            }
            for (link in xcloudLinks) {
                extractXCloud(link, callback)
            }
            if (xcloudLinks.isNotEmpty()) return true

            val firstLink = safeDoc.selectFirst("iframe")?.absUrl("src")
                ?: safeDoc.selectFirst("a[href*='http']")?.absUrl("href")
            if (!firstLink.isNullOrEmpty()) {
                currentUrl = firstLink
            }
        }

        // 2. If it's an XCloud page (e.g. https://xcloud.tattoo/4ad2cac5576e66b)
        if (currentUrl.contains("xcloud")) {
            if (extractXCloud(currentUrl, callback)) return true
        }

        // 3. Fallback to standard iframe / CloudStream extractors
        val document = app.get(currentUrl, cookies = cookies).document
        val iframe = document.selectFirst("iframe")?.absUrl("src") ?: currentUrl
        loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        return true
    }

    private suspend fun extractXCloud(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val xCloudDoc = app.get(url).document

        // Grab candidate action buttons on XCloud:
        // "Online Streaming", "WATCH ONLINE", "Generate Direct Download Link", "DOWNLOAD"
        val candidatePages = mutableSetOf<String>()
        xCloudDoc.select(
            "a:contains(Online Streaming), a:contains(WATCH ONLINE), a:contains(Generate Direct Download Link), a:contains(Download), a[href*='stream'], a[href*='download']"
        ).forEach { el ->
            val href = el.absUrl("href")
            if (href.isNotEmpty() && !href.contains("telegram", ignoreCase = true) && !href.endsWith("#")) {
                candidatePages.add(href)
            }
        }

        // Also check the current page directly in case video tags are already present
        candidatePages.add(url)

        for (pageUrl in candidatePages) {
            try {
                // Check if the link itself is already a direct video file (.mkv / .mp4 / .m3u8)
                if (pageUrl.matches(Regex("(?i).*\\.(mkv|mp4|m3u8)(\\?.*)?$"))) {
                    callback.invoke(
                        newExtractorLink(
                            source = "XCloud",
                            name = "XCloud Direct",
                            url = pageUrl
                        )
                    )
                    found = true
                    continue
                }

                val doc = if (pageUrl == url) xCloudDoc else app.get(pageUrl, referer = url).document

                // Look for <video src="...">, <source src="...">, or direct download links on the next page
                val directLinks = mutableSetOf<String>()
                doc.select("video source[src], video[src]").forEach {
                    val src = it.absUrl("src")
                    if (src.isNotEmpty()) directLinks.add(src)
                }
                doc.select("a[href*='.mkv'], a[href*='.mp4'], a[href*='.m3u8'], a:contains(Download)").forEach {
                    val href = it.absUrl("href")
                    if (href.matches(Regex("(?i).*\\.(mkv|mp4|m3u8).*")) || href.contains("token=") || href.contains("download")) {
                        if (!href.contains("telegram", ignoreCase = true) && href != pageUrl) {
                            directLinks.add(href)
                        }
                    }
                }

                // Regex scan raw HTML/scripts for direct stream URLs
                val html = doc.html()
                Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|mkv)[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .forEach { match ->
                        directLinks.add(match.groupValues[1])
                    }

                for (videoUrl in directLinks) {
                    callback.invoke(
                        newExtractorLink(
                            source = "XCloud",
                            name = "XCloud",
                            url = videoUrl
                        )
                    )
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }
}
