package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class DramaVerseProvider : MainAPI() {
    override var mainUrl = "https://mydverse.com"
    override var name = "DramaVerse"
    override val supportedTypes = setOf(TvType.AsianDrama)
    override var lang = "hi"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

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

        val document = app.get(url, headers = defaultHeaders, cookies = cookies).document
        val home = document.select("li.post-item, article.item-list, div.post-element")
            .mapNotNull { it.toSearchResult() }
            .ifEmpty {
                document.select("a.post-thumb").mapNotNull { it.thumbToSearchResult() }
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

    private fun Element.thumbToSearchResult(): SearchResponse? {
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
        val document = app.get("$mainUrl/?s=$query", headers = defaultHeaders, cookies = cookies).document
        return document.select("li.post-item, article.item-list")
            .mapNotNull { it.toSearchResult() }
            .ifEmpty {
                document.select("a.post-thumb").mapNotNull { it.thumbToSearchResult() }
            }
    }

    private suspend fun scrapeEpisodeLinksFromIntermediate(pageUrl: String, refererUrl: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seenUrls = mutableSetOf<String>()
        try {
            val resp = app.get(pageUrl, headers = defaultHeaders, referer = refererUrl)
            val doc = resp.document
            val html = resp.text

            doc.select("a[href]").forEach { a ->
                val href = a.absUrl("href")
                if (href.startsWith("http") &&
                    !href.contains("sohojgyan.com") &&
                    !href.contains("mydverse.com") &&
                    !href.contains("blogspot.com") &&
                    !href.contains("blogger.com") &&
                    !href.contains("google.com") &&
                    !href.contains("telegram", ignoreCase = true) &&
                    !href.contains("t.me/") &&
                    !href.contains("whatsapp", ignoreCase = true)
                ) {
                    if (seenUrls.add(href)) {
                        val parentText = a.parent()?.ownText()?.trim().orEmpty()
                        val prevHeading = a.parent()?.previousElementSibling()?.text()?.trim().orEmpty()
                        val label = prevHeading.ifEmpty { parentText }.ifEmpty { a.text().trim() }
                        results.add(label to href)
                    }
                }
            }

            if (results.isEmpty()) {
                Regex("""https?://[^"'\s<>\\]+(?:xcloud|hubcloud|gdflix|filepress)[^"'\s<>\\]*""", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .forEachIndexed { idx, m ->
                        val href = m.value.replace("\\/", "/")
                        if (seenUrls.add(href)) {
                            results.add("Episode ${idx + 1}" to href)
                        }
                    }
            }
        } catch (_: Exception) {
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders, cookies = cookies).document
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

            if (slideUrl.contains("sohojgyan") ||
                slideUrl.contains("blogspot") ||
                slideTitle.contains("Episode Wise", ignoreCase = true)
            ) {
                val scraped = scrapeEpisodeLinksFromIntermediate(slideUrl, url)
                if (scraped.isNotEmpty()) {
                    scraped.forEachIndexed { index, (rawLabel, epHref) ->
                        val epNum = Regex("(?i)(?:ep|episode|e)\\s*0*(\\d+)")
                            .find(rawLabel)
                            ?.groupValues?.get(1)?.toIntOrNull()
                            ?: (index + 1)

                        val cleanName = if (
                            rawLabel.isEmpty() ||
                            rawLabel.equals("XCloud", ignoreCase = true) ||
                            rawLabel.equals("HubCloud", ignoreCase = true) ||
                            rawLabel.equals("GDFlix", ignoreCase = true) ||
                            rawLabel.equals("Download", ignoreCase = true)
                        ) {
                            "Episode $epNum"
                        } else {
                            rawLabel
                        }

                        episodes.add(
                            newEpisode(epHref) {
                                this.name = cleanName
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

        if (currentUrl.contains("sohojgyan") || currentUrl.contains("blogspot")) {
            val scraped = scrapeEpisodeLinksFromIntermediate(currentUrl, "$mainUrl/")
            for ((_, link) in scraped) {
                if (link.contains("xcloud", ignoreCase = true)) {
                    extractXCloud(link, subtitleCallback, callback)
                } else {
                    loadExtractor(link, currentUrl, subtitleCallback, callback)
                }
            }
            if (scraped.isNotEmpty()) return true
        }

        if (currentUrl.contains("xcloud", ignoreCase = true)) {
            if (extractXCloud(currentUrl, subtitleCallback, callback)) return true
        }

        val document = app.get(currentUrl, headers = defaultHeaders, cookies = cookies).document
        val xcloudInPage = document.selectFirst("a[href*='xcloud']")?.absUrl("href")
        if (!xcloudInPage.isNullOrEmpty()) {
            if (extractXCloud(xcloudInPage, subtitleCallback, callback)) return true
        }

        val iframe = document.selectFirst("iframe")?.absUrl("src") ?: currentUrl
        loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        return true
    }

    private fun resolveRelativeUrl(baseUrl: String, rawPath: String): String {
        val cleaned = rawPath.replace("\\/", "/").trim()
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned
        return try {
            URI(baseUrl).resolve(cleaned).toString()
        } catch (_: Exception) {
            cleaned
        }
    }

    // 302 redirect ko follow karke final R2 direct CDN link nikalta hai
    private suspend fun resolveFinalStreamUrl(startUrl: String, refererUrl: String): String {
        return try {
            val resp = app.get(startUrl, headers = defaultHeaders, referer = refererUrl, allowRedirects = true)
            resp.url.ifEmpty { startUrl }
        } catch (_: Exception) {
            startUrl
        }
    }

    private suspend fun extractXCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val xCloudResponse = app.get(url, headers = defaultHeaders)
        val xCloudDoc = xCloudResponse.document
        val resolvedPageUrl = xCloudResponse.url.ifEmpty { url }
        val baseCookies = xCloudResponse.cookies

        val emittedUrls = mutableSetOf<String>()

        val mediaId = resolvedPageUrl.substringAfterLast("/").substringBefore("?").trim()
        val baseUri = try {
            val u = URI(resolvedPageUrl)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            "https://xcloud.tattoo"
        }

        val streamCandidates = linkedSetOf<String>()
        xCloudDoc.selectFirst("div.vd a[href*='stream_'], a[href*='/stream_']")
            ?.absUrl("href")
            ?.takeIf { it.isNotEmpty() }
            ?.let { streamCandidates.add(it) }

        if (mediaId.isNotEmpty()) {
            streamCandidates.add("$baseUri/stream_$mediaId")
            streamCandidates.add("https://stream.xcloud1.lol/$mediaId")
        }

        for (streamPageUrl in streamCandidates) {
            try {
                val streamResp = app.get(streamPageUrl, headers = defaultHeaders, cookies = baseCookies)
                val finalPlayerUrl = streamResp.url.ifEmpty { streamPageUrl }
                val streamHtml = streamResp.text

                // Subtitles (https://sub.xcloud.icu/<tgid>)[span_2](start_span)[span_2](end_span)
                Regex("""\{[^{}]*"lang"\s*:\s*"([^"]+)"[^{}]*"tgid"\s*:\s*(\d+)[^{}]*\}""")
                    .findAll(streamHtml)
                    .forEach { m ->
                        subtitleCallback.invoke(
                            SubtitleFile(m.groupValues[1], "https://sub.xcloud.icu/${m.groupValues[2]}")
                        )
                    }

                val varPatterns = listOf(
                    "PRIMARY_URL" to ("XCloud Server 1" to ExtractorLinkType.VIDEO),
                    "WASABI_URL" to ("XCloud Server 2" to ExtractorLinkType.VIDEO),
                    "HLS_URL" to ("XCloud HLS" to ExtractorLinkType.M3U8)
                )

                for ((varName, info) in varPatterns) {
                    val match = Regex("""var\s+$varName\s*=\s*["']([^"']+)["']""").find(streamHtml)
                    val rawVal = match?.groupValues?.get(1)
                    if (!rawVal.isNullOrEmpty() && rawVal != "null") {
                        val redirectUrl = resolveRelativeUrl(finalPlayerUrl, rawVal)
                        // Redirect follow karke direct R2 link fetch karo
                        val directCdnUrl = resolveFinalStreamUrl(redirectUrl, finalPlayerUrl)

                        if (emittedUrls.add(directCdnUrl)) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "XCloud",
                                    name = info.first,
                                    url = directCdnUrl,
                                    type = info.second
                                ) {
                                    this.referer = "" // Direct R2 signed link ko referer ki zaroorat nahi hoti
                                }
                            )
                            found = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return found
    }
}
