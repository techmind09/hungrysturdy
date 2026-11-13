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
                    val safeDoc = app.get(slideUrl, referer = url).document
                    val epLinks = safeDoc.select("a[href*='xcloud'], a[href*='hubcloud'], a[href*='gdflix']")
                        .filter { a ->
                            val href = a.absUrl("href")
                            href.isNotEmpty() && !href.contains("telegram", ignoreCase = true)
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

                            val cleanName = if (rawText.equals("XCloud", ignoreCase = true)) {
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
            val xcloudLinks = safeDoc.select("a[href*='xcloud']").mapNotNull {
                it.absUrl("href").ifEmpty { null }
            }
            for (link in xcloudLinks) {
                extractXCloud(link, subtitleCallback, callback)
            }
            if (xcloudLinks.isNotEmpty()) return true

            val firstLink = safeDoc.selectFirst("iframe")?.absUrl("src")
                ?: safeDoc.selectFirst("a[href*='http']")?.absUrl("href")
            if (!firstLink.isNullOrEmpty()) {
                currentUrl = firstLink
            }
        }

        if (currentUrl.contains("xcloud")) {
            if (extractXCloud(currentUrl, subtitleCallback, callback)) return true
        }

        val document = app.get(currentUrl, cookies = cookies).document
        val iframe = document.selectFirst("iframe")?.absUrl("src") ?: currentUrl
        loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        return true
    }

    private suspend fun extractXCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val xCloudDoc = app.get(url).document

        // 1. Subtitle link agar page par hai to add karo
        xCloudDoc.select("div.vd a[href*='subtitle']").firstOrNull()?.absUrl("href")?.let { subUrl ->
            if (subUrl.isNotEmpty()) {
                subtitleCallback.invoke(SubtitleFile("English", subUrl))
            }
        }

        // 2. Sirf asli <div class="vd"> ke buttons nikalo (fake ads-btns ko ignore karo)
        val directGenUrl = xCloudDoc.selectFirst("div.vd a:contains(Generate Direct Download), div.vd a[href*='token=']")
            ?.absUrl("href")
        val streamPageUrl = xCloudDoc.selectFirst("div.vd a:contains(Online Streaming), div.vd a[href*='stream_']")
            ?.absUrl("href")

        val emittedUrls = mutableSetOf<String>()

        // 3. Pehle "Generate Direct Download Link" (xcloud1.lol/...?token=...) resolve karo
        if (!directGenUrl.isNullOrEmpty() && !directGenUrl.contains("subtitle")) {
            try {
                val genDoc = app.get(directGenUrl, referer = url).document
                // Is page par jo actual download buttons / CDN links hain unhe nikalo
                genDoc.select("a[href]").forEach { a ->
                    val href = a.absUrl("href")
                    val text = a.text().trim()
                    if (href.isNotEmpty() &&
                        href != directGenUrl &&
                        !href.contains("telegram", ignoreCase = true) &&
                        !href.contains("fifa2026", ignoreCase = true) &&
                        !href.endsWith("#") &&
                        (href.matches(Regex("(?i).*\\.(mkv|mp4|m3u8)(\\?.*)?$")) ||
                         href.contains("googleusercontent.com") ||
                         href.contains("r2.dev") ||
                         href.contains("workers.dev") ||
                         href.contains("pixeldrain") ||
                         href.contains("fsl.gigabox") ||
                         href.contains("hubcdn") ||
                         text.contains("Download", ignoreCase = true) ||
                         text.contains("Server", ignoreCase = true) ||
                         text.contains("Direct", ignoreCase = true))
                    ) {
                        if (emittedUrls.add(href)) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "XCloud",
                                    name = text.ifEmpty { "XCloud Direct" },
                                    url = href,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = directGenUrl
                                }
                            )
                            found = true
                        }
                    }
                }

                // Regex se hidden video/CDN URLs scan karo
                Regex("""["'](https?://[^"'\s<>]+\.(?:m3u8|mp4|mkv)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
                    .findAll(genDoc.html())
                    .forEach { m ->
                        val vUrl = m.groupValues[1]
                        if (emittedUrls.add(vUrl)) {
                            val type = if (vUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink("XCloud", "XCloud Direct", vUrl, type) {
                                    this.referer = directGenUrl
                                }
                            )
                            found = true
                        }
                    }
            } catch (_: Exception) {
            }
        }

        // 4. Ab "Online Streaming" (/stream_...) page se player stream nikalo
        if (!streamPageUrl.isNullOrEmpty()) {
            try {
                val streamDoc = app.get(streamPageUrl, referer = url).document

                streamDoc.select("video source[src], video[src]").forEach { el ->
                    val src = el.absUrl("src")
                    if (src.isNotEmpty() && emittedUrls.add(src)) {
                        val type = if (src.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        callback.invoke(
                            newExtractorLink("XCloud", "XCloud Stream", src, type) {
                                this.referer = streamPageUrl
                            }
                        )
                        found = true
                    }
                }

                Regex("""["'](https?://[^"'\s<>]+\.(?:m3u8|mp4|mkv)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
                    .findAll(streamDoc.html())
                    .forEach { m ->
                        val vUrl = m.groupValues[1]
                        if (emittedUrls.add(vUrl)) {
                            val type = if (vUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink("XCloud", "XCloud Stream", vUrl, type) {
                                    this.referer = streamPageUrl
                                }
                            )
                            found = true
                        }
                    }
            } catch (_: Exception) {
            }
        }

        return found
    }
}
