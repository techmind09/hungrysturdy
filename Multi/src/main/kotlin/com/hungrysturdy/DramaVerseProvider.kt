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
        val document = app.get("$mainUrl/?s=$query", cookies = cookies).document
        return document.select("li.post-item, article.item-list")
            .mapNotNull { it.toSearchResult() }
            .ifEmpty {
                document.select("a.post-thumb").mapNotNull { it.thumbToSearchResult() }
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

            if (slideUrl.contains("sohojgyan") || slideTitle.contains("Episode Wise", ignoreCase = true)) {
                try {
                    val safeDoc = app.get(slideUrl).document
                    // Sabhi valid episode/host links uthao (sirf social/telegram/nav links chhodkar)
                    val epLinks = safeDoc.select("a[href]")
                        .filter { a ->
                            val href = a.absUrl("href")
                            href.startsWith("http") &&
                                !href.contains("sohojgyan.com") &&
                                !href.contains("mydverse.com") &&
                                !href.contains("telegram", ignoreCase = true) &&
                                !href.contains("t.me/") &&
                                !href.contains("whatsapp", ignoreCase = true) &&
                                !href.contains("facebook", ignoreCase = true) &&
                                !href.contains("twitter", ignoreCase = true) &&
                                !href.contains("instagram", ignoreCase = true)
                        }

                    if (epLinks.isNotEmpty()) {
                        epLinks.forEachIndexed { index, epElement ->
                            val epHref = epElement.absUrl("href")
                            val parentText = epElement.parent()?.ownText()?.trim().orEmpty()
                            val siblingTitle = epElement.parent()
                                ?.selectFirst("h2, h3, h4, strong, .title, .ep-title")
                                ?.text()?.trim().orEmpty()

                            val rawText = epElement.ownText().trim()
                                .ifEmpty { parentText }
                                .ifEmpty { siblingTitle }
                                .ifEmpty { "Episode ${index + 1}" }

                            val epNum = Regex("(?i)(?:ep|episode|e)\\s*0*(\\d+)")
                                .find("$rawText $siblingTitle")
                                ?.groupValues?.get(1)?.toIntOrNull()
                                ?: (index + 1)

                            val cleanName = if (
                                rawText.equals("XCloud", ignoreCase = true) ||
                                rawText.equals("HubCloud", ignoreCase = true) ||
                                rawText.equals("GDFlix", ignoreCase = true) ||
                                rawText.equals("Download", ignoreCase = true)
                            ) {
                                "Episode $epNum"
                            } else {
                                rawText
                            }

                            episodes.add(
                                newEpisode(epHref) {
                                    this.name = cleanName
                                    this.episode = epNum
                                }
                            )
                        }
                    }
                } catch (_: Exception) {
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

        if (currentUrl.contains("sohojgyan")) {
            val safeDoc = app.get(currentUrl).document
            val hostLinks = safeDoc.select("a[href*='xcloud'], a[href*='hubcloud'], a[href*='gdflix']")
                .mapNotNull { it.absUrl("href").ifEmpty { null } }

            for (link in hostLinks) {
                if (link.contains("xcloud", ignoreCase = true)) {
                    extractXCloud(link, subtitleCallback, callback)
                } else {
                    loadExtractor(link, currentUrl, subtitleCallback, callback)
                }
            }
            if (hostLinks.isNotEmpty()) return true
        }

        if (currentUrl.contains("xcloud", ignoreCase = true)) {
            if (extractXCloud(currentUrl, subtitleCallback, callback)) return true
        }

        // Built-in CloudStream extractors (HubCloud, GDFlix, Streamtape, etc.)
        loadExtractor(currentUrl, "$mainUrl/", subtitleCallback, callback)
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

    // /apis/cloud/redirect/... ke 302 Location headers ko follow karke final CDN .mkv URL nikalta hai
    private suspend fun resolveFinalStreamUrl(startUrl: String, refererUrl: String): String {
        var current = startUrl
        repeat(4) {
            try {
                val resp = app.get(
                    current,
                    referer = refererUrl,
                    allowRedirects = false
                )
                val loc = resp.headers["location"] ?: resp.headers["Location"]
                if (!loc.isNullOrEmpty()) {
                    current = resolveRelativeUrl(current, loc)
                } else {
                    return current
                }
            } catch (_: Exception) {
                return current
            }
        }
        return current
    }

    private suspend fun extractXCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        // no-referrer meta tag ki wajah se referer mat bhejo
        val xCloudResponse = app.get(url)
        val xCloudDoc = xCloudResponse.document
        val resolvedPageUrl = xCloudResponse.url.ifEmpty { url }

        val emittedUrls = mutableSetOf<String>()

        // 1. Online Streaming (/stream_...) button ko follow karke stream.xcloud1.lol se links nikalo
        val streamPageUrl = xCloudDoc.selectFirst("div.vd a[href*='stream_'], div.vd a:contains(Online Streaming)")
            ?.absUrl("href")
            ?: if (resolvedPageUrl.contains("stream.")) resolvedPageUrl else null

        if (!streamPageUrl.isNullOrEmpty()) {
            try {
                val streamResp = app.get(streamPageUrl)
                val finalPlayerUrl = streamResp.url.ifEmpty { streamPageUrl }
                val streamHtml = streamResp.text

                // Subtitles (RAW_SUBS -> https://sub.xcloud.icu/<tgid>)
                Regex("""\{[^{}]*"lang"\s*:\s*"([^"]+)"[^{}]*"tgid"\s*:\s*(\d+)[^{}]*\}""")
                    .findAll(streamHtml)
                    .forEach { m ->
                        val langName = m.groupValues[1]
                        val tgid = m.groupValues[2]
                        subtitleCallback.invoke(
                            SubtitleFile(langName, "https://sub.xcloud.icu/$tgid")
                        )
                    }

                val varPatterns = listOf(
                    "HLS_URL" to ("XCloud HLS" to ExtractorLinkType.M3U8),
                    "PRIMARY_URL" to ("XCloud HD (Server 1)" to ExtractorLinkType.VIDEO),
                    "WASABI_URL" to ("XCloud Backup (Server 2)" to ExtractorLinkType.VIDEO)
                )

                for ((varName, info) in varPatterns) {
                    val match = Regex("""var\s+$varName\s*=\s*"([^"]+)"\s*;""").find(streamHtml)
                    val rawVal = match?.groupValues?.get(1)
                    if (!rawVal.isNullOrEmpty() && rawVal != "null") {
                        val redirectUrl = resolveRelativeUrl(finalPlayerUrl, rawVal)
                        val directCdnUrl = resolveFinalStreamUrl(redirectUrl, finalPlayerUrl)
                        if (emittedUrls.add(directCdnUrl)) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "XCloud",
                                    name = info.first,
                                    url = directCdnUrl,
                                    type = info.second
                                )
                            )
                            found = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 2. Generate Direct Download Link (xcloud1.lol/...?token=...) se bhi direct link nikalo
        val directGenUrl = xCloudDoc.selectFirst("div.vd a:contains(Generate Direct Download), div.vd a[href*='token=']")
            ?.absUrl("href")
        if (!directGenUrl.isNullOrEmpty() && !directGenUrl.contains("subtitle")) {
            try {
                val genResp = app.get(directGenUrl)
                val genFinalUrl = genResp.url.ifEmpty { directGenUrl }
                val genDoc = genResp.document

                genDoc.select("a[href]").forEach { a ->
                    val href = a.absUrl("href")
                    val text = a.text().trim()
                    if (href.startsWith("http") &&
                        href != genFinalUrl &&
                        !href.contains("telegram", ignoreCase = true) &&
                        !href.contains("fifa2026", ignoreCase = true) &&
                        (href.matches(Regex("(?i).*\\.(mkv|mp4|m3u8)(\\?.*)?$")) ||
                         href.contains("googleusercontent.com") ||
                         href.contains("r2.dev") ||
                         href.contains("workers.dev") ||
                         href.contains("wasabisys.com") ||
                         href.contains("pixeldrain") ||
                         text.contains("Download", ignoreCase = true))
                    ) {
                        val directCdnUrl = resolveFinalStreamUrl(href, genFinalUrl)
                        if (emittedUrls.add(directCdnUrl)) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "XCloud",
                                    name = text.ifEmpty { "XCloud Direct" },
                                    url = directCdnUrl,
                                    type = ExtractorLinkType.VIDEO
                                )
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
