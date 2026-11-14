package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class DramaVerseProvider : MainAPI() {
    override var mainUrl = "https://mydverse.com"
    override var name = "DramaVerse"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
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

    private fun resolveRelativeUrl(baseUrl: String, rawPath: String): String {
        val cleaned = rawPath.replace("\\/", "/").trim()
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned
        return try {
            URI(baseUrl).resolve(cleaned).toString()
        } catch (_: Exception) {
            cleaned
        }
    }

    private fun isIgnoredOrAdUrl(href: String): Boolean {
        val lower = href.lowercase()
        return !lower.startsWith("http") ||
            lower.contains("ardance.org") ||
            lower.contains("invoke_layer") ||
            lower.contains("fifa2026") ||
            lower.contains("5gvci.com") ||
            lower.contains("llvpn.com") ||
            lower.contains("sohojgyan.com") ||
            lower.contains("mydverse.com") ||
            lower.contains("blogspot.com") ||
            lower.contains("blogger.com") ||
            lower.contains("telegram") ||
            lower.contains("t.me/") ||
            lower.contains("whatsapp") ||
            lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("instagram.com") ||
            (lower.contains("google.com") && !lower.contains("drive.google.com") && !lower.contains("docs.google.com") && !lower.contains("googleusercontent.com"))
    }

    // Shortener (safe.sohojgyan.com -> mydverse02.blogspot.com) ko resolve karta hai
    private suspend fun resolveIntermediateUrl(url: String): String {
        var current = url
        repeat(4) {
            try {
                val resp = app.get(current, headers = defaultHeaders, allowRedirects = true)
                val finalUrl = resp.url.ifEmpty { current }
                if (finalUrl != current && !finalUrl.contains("ardance.org")) {
                    current = finalUrl
                }
                val html = resp.text

                // Meta refresh check
                val metaMatch = Regex("""content\s*=\s*["'][0-9]+;\s*url=([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                if (metaMatch != null) {
                    val nextUrl = resolveRelativeUrl(current, metaMatch.groupValues[1])
                    if (nextUrl != current && !nextUrl.contains("ardance.org")) {
                        current = nextUrl
                        continue
                    }
                }

                // JS redirect check (ignore ad scripts)
                val jsMatch = Regex("""(?:window\.location|location\.href|location\.replace)\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .firstOrNull { !it.contains("ardance.org") && !it.contains("fifa2026") }

                if (jsMatch != null && jsMatch != current) {
                    current = resolveRelativeUrl(current, jsMatch)
                    continue
                }
                break
            } catch (_: Exception) {
                break
            }
        }
        return current
    }

    // Element ke aas-paas se "Episode 01", "Episode 02" heading dhoondhta hai
    private fun findEpisodeLabel(el: Element, fallbackIndex: Int): String {
        val epRegex = Regex("(?i)(?:episode|ep)\\s*0*(\\d+)")
        var curr: Element? = el
        repeat(4) {
            if (curr == null) return@repeat
            // Previous siblings check karo (e.g., <h3>Episode 03</h3> ke neeche <div class="EpFile">GDrive</div>)
            var prev = curr?.previousElementSibling()
            while (prev != null) {
                val t = prev.text().trim()
                if (epRegex.containsMatchIn(t)) return t
                prev = prev.previousElementSibling()
            }
            val own = curr?.ownText()?.trim().orEmpty()
            if (epRegex.containsMatchIn(own)) return own
            curr = curr?.parent()
        }
        val text = el.text().trim()
        if (epRegex.containsMatchIn(text)) return text
        return "Episode $fallbackIndex"
    }

    // mydverse02.blogspot.com / safe.sohojgyan.com se GDrive + XCloud dono links nikalta hai
    private suspend fun scrapeEpisodeLinksFromIntermediate(pageUrl: String, refererUrl: String): List<Pair<Int, String>> {
        // Pair(episodeNumber, url)
        val rawItems = mutableListOf<Pair<Int, String>>()
        val seenUrls = mutableSetOf<String>()

        try {
            val resolvedStart = resolveIntermediateUrl(pageUrl)
            val resp = app.get(resolvedStart, headers = defaultHeaders, referer = refererUrl)
            val doc = resp.document
            val html = resp.text

            var autoIdx = 1

            // 1. Standard <a href="..."> aur onclick wale buttons check karo
            doc.select("a[href], [onclick], [data-href], [data-url], div.EpFile").forEach { el ->
                val rawHref = when {
                    el.hasAttr("href") -> el.absUrl("href")
                    el.hasAttr("data-href") -> el.absUrl("data-href")
                    el.hasAttr("data-url") -> el.absUrl("data-url")
                    el.hasAttr("onclick") -> {
                        Regex("""["'](https?://[^"']+)["']""").find(el.attr("onclick"))?.groupValues?.get(1).orEmpty()
                    }
                    el.parent()?.hasAttr("href") == true -> el.parent()!!.absUrl("href")
                    el.parent()?.hasAttr("onclick") == true -> {
                        Regex("""["'](https?://[^"']+)["']""").find(el.parent()!!.attr("onclick"))?.groupValues?.get(1).orEmpty()
                    }
                    else -> ""
                }

                if (rawHref.isNotEmpty() && !isIgnoredOrAdUrl(rawHref) && seenUrls.add(rawHref)) {
                    val label = findEpisodeLabel(el, autoIdx)
                    val epNum = Regex("(?i)(?:episode|ep|e)\\s*0*(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()
                        ?: autoIdx
                    rawItems.add(epNum to rawHref)
                    autoIdx++
                }
            }

            // 2. Fallback: Agar Blogger page mein links JS array/variables ke andar chhupe hon
            if (rawItems.isEmpty()) {
                val hostRegex = Regex(
                    """https?://[^"'\s<>\\]+(?:xcloud|drive\.google\.com|docs\.google\.com|gdflix|gdtot|filepress|hubcloud|hubdrive|gdrive|workers\.dev)[^"'\s<>\\]*""",
                    RegexOption.IGNORE_CASE
                )
                hostRegex.findAll(html).forEach { m ->
                    val href = m.value.replace("\\/", "/")
                    if (!isIgnoredOrAdUrl(href) && seenUrls.add(href)) {
                        rawItems.add(autoIdx to href)
                        autoIdx++
                    }
                }
            }
        } catch (_: Exception) {
        }
        return rawItems
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders, cookies = cookies).document
        val title = document.selectFirst("h1.entry-title, h1.post-title")?.text()?.trim() ?: "Drama"
        val poster = document.selectFirst("a.post-thumb img, .post-thumb img, .entry-content img")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }
        val description = document.selectFirst(".entry-content p")?.text()

        // Episode number ke hisaab se multiple links (GDrive + XCloud) ko group karo
        val episodeMap = linkedMapOf<Int, MutableList<String>>()
        val btnSlides = document.select("a.btn-slide, .entry-content a[href*='sohojgyan'], .entry-content a[href*='blogspot'], .entry-content a[href*='xcloud'], .entry-content a[href*='drive.google.com'], .entry-content a[href*='gdflix']")

        for (slide in btnSlides) {
            val slideUrl = slide.absUrl("href").ifEmpty { continue }
            val slideTitle = slide.selectFirst(".btn-title")?.text()?.trim()
                ?: slide.text().trim()
                .ifEmpty { "Episode" }

            if (slideUrl.contains("sohojgyan") ||
                slideUrl.contains("blogspot") ||
                slideTitle.contains("Episode Wise", ignoreCase = true)
            ) {
                val scraped = scrapeEpisodeLinksFromIntermediate(slideUrl, url)
                if (scraped.isNotEmpty()) {
                    for ((epNum, epHref) in scraped) {
                        episodeMap.getOrPut(epNum) { mutableListOf() }.add(epHref)
                    }
                } else {
                    val nextEp = (episodeMap.keys.maxOrNull() ?: 0) + 1
                    episodeMap.getOrPut(nextEp) { mutableListOf() }.add(slideUrl)
                }
            } else if (!isIgnoredOrAdUrl(slideUrl)) {
                val epNum = Regex("(?i)(?:episode|ep|e)\\s*0*(\\d+)").find(slideTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: ((episodeMap.keys.maxOrNull() ?: 0) + 1)
                episodeMap.getOrPut(epNum) { mutableListOf() }.add(slideUrl)
            }
        }

        val episodes = mutableListOf<Episode>()
        for ((epNum, urls) in episodeMap) {
            // Multiple links (e.g. GDrive + XCloud) ko "|||" se join karke data mein bhejo
            val combinedData = urls.distinct().joinToString("|||")
            episodes.add(
                newEpisode(combinedData) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            )
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Full Movie / Stream"
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
        val rawLinks = data.split("|||").map { it.trim() }.filter { it.isNotEmpty() }

        for (itemUrl in rawLinks) {
            val currentUrl = resolveIntermediateUrl(itemUrl)

            // 1. Agar abhi bhi intermediate Blogger/Sohojgyan page hai
            if (currentUrl.contains("sohojgyan") || currentUrl.contains("blogspot")) {
                val scraped = scrapeEpisodeLinksFromIntermediate(currentUrl, "$mainUrl/")
                for ((_, link) in scraped) {
                    processSingleHostLink(link, currentUrl, subtitleCallback, callback)
                }
                continue
            }

            // 2. Single host link (XCloud, GDrive, GDFlix, HubCloud, etc.)
            processSingleHostLink(currentUrl, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }

    private suspend fun processSingleHostLink(
        rawUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = resolveIntermediateUrl(rawUrl)

        when {
            url.contains("xcloud", ignoreCase = true) || url.contains("xcloud1.lol", ignoreCase = true) -> {
                extractXCloud(url, subtitleCallback, callback)
            }
            url.contains("drive.google.com", ignoreCase = true) ||
            url.contains("docs.google.com", ignoreCase = true) ||
            url.contains("drive.usercontent.google.com", ignoreCase = true) -> {
                extractGDrive(url, subtitleCallback, callback)
            }
            else -> {
                // GDFlix, HubCloud, FilePress, GDTot, Streamtape, etc. ke liye pehle CloudStream extractors try karo
                val handled = loadExtractor(url, referer, subtitleCallback, callback)
                if (!handled) {
                    // Agar wrapper page ke andar GDrive ya XCloud link ho to use bhi nikal lo
                    try {
                        val doc = app.get(url, headers = defaultHeaders, referer = referer).document
                        doc.select("a[href]").forEach { a ->
                            val href = a.absUrl("href")
                            if (href.contains("xcloud", ignoreCase = true)) {
                                extractXCloud(href, subtitleCallback, callback)
                            } else if (href.contains("drive.google.com") || href.contains("drive.usercontent.google.com")) {
                                extractGDrive(href, subtitleCallback, callback)
                            } else if (href.contains("googleusercontent.com") || href.contains("r2.dev") || href.contains("workers.dev")) {
                                callback.invoke(
                                    newExtractorLink("GDrive", "GDrive Direct", href, ExtractorLinkType.VIDEO)
                                )
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    // Google Drive Direct Extractor (drive.google.com/file/d/<ID>/view -> Direct Stream)
    private suspend fun extractGDrive(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val fileId = Regex("""(?:/d/|id=)([a-zA-Z0-9_-]{15,})""").find(url)?.groupValues?.get(1)

        if (!fileId.isNullOrEmpty()) {
            // 1. Direct drive.usercontent.google.com download/stream endpoint (confirm=t bypasses virus scan warning)
            val directConfirmUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            try {
                val resp = app.get(directConfirmUrl, headers = defaultHeaders, allowRedirects = true)
                val finalUrl = resp.url.ifEmpty { directConfirmUrl }
                val html = resp.text

                // Agar confirmation form aaye to uske action + hidden inputs se URL banao
                val formAction = resp.document.selectFirst("form#download-form")?.attr("action")
                if (!formAction.isNullOrEmpty()) {
                    val params = resp.document.select("form#download-form input[name]").joinToString("&") {
                        "${it.attr("name")}=${it.attr("value")}"
                    }
                    val confirmedStreamUrl = if (params.isNotEmpty()) "$formAction?$params" else formAction
                    callback.invoke(
                        newExtractorLink(
                            source = "GDrive",
                            name = "GDrive Direct",
                            url = confirmedStreamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://drive.google.com/"
                        }
                    )
                    found = true
                } else if (!html.contains("<html", ignoreCase = true) || finalUrl.contains("googleusercontent.com")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "GDrive",
                            name = "GDrive Direct",
                            url = finalUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://drive.google.com/"
                        }
                    )
                    found = true
                }
            } catch (_: Exception) {
            }

            // 2. Fallback direct link
            if (!found) {
                callback.invoke(
                    newExtractorLink(
                        source = "GDrive",
                        name = "GDrive Stream",
                        url = directConfirmUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://drive.google.com/"
                    }
                )
                found = true
            }
        }

        // Built-in extractor bhi saath mein call karo
        loadExtractor(url, "https://drive.google.com/", subtitleCallback, callback)
        return found
    }

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
        val resolvedUrl = resolveIntermediateUrl(url)
        val xCloudResponse = app.get(resolvedUrl, headers = defaultHeaders)
        val xCloudDoc = xCloudResponse.document
        val resolvedPageUrl = xCloudResponse.url.ifEmpty { resolvedUrl }
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
            ?.let { streamCandidates.add(resolveIntermediateUrl(it)) }

        if (mediaId.isNotEmpty() && mediaId.length > 2) {
            streamCandidates.add("$baseUri/stream_$mediaId")
            streamCandidates.add("https://stream.xcloud1.lol/$mediaId")
        }

        for (streamPageUrl in streamCandidates) {
            try {
                val streamResp = app.get(streamPageUrl, headers = defaultHeaders, cookies = baseCookies)
                val finalPlayerUrl = streamResp.url.ifEmpty { streamPageUrl }
                val streamHtml = streamResp.text

                // Subtitles
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
                        val directCdnUrl = resolveFinalStreamUrl(redirectUrl, finalPlayerUrl)

                        if (emittedUrls.add(directCdnUrl)) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "XCloud",
                                    name = info.first,
                                    url = directCdnUrl,
                                    type = info.second
                                ) {
                                    this.referer = ""
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
