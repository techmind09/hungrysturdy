package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

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
            lower.contains("fifa2026.mom") ||
            lower.contains("fifa2026") ||
            lower.contains("/redir.php") ||
            lower.contains("/redir2.php") ||
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
            (lower.contains("google.com") &&
                !lower.contains("drive.google.com") &&
                !lower.contains("docs.google.com") &&
                !lower.contains("googleusercontent.com"))
    }

    private suspend fun resolveIntermediateUrl(url: String): String {
        var current = url
        var attempts = 0
        while (attempts < 4) {
            attempts++
            try {
                val resp = app.get(current, headers = defaultHeaders, allowRedirects = false)
                val loc = resp.headers["location"] ?: resp.headers["Location"]
                if (!loc.isNullOrEmpty()) {
                    val next = resolveRelativeUrl(current, loc)
                    if (!next.contains("ardance.org") && !next.contains("fifa2026") && next != current) {
                        current = next
                        continue
                    }
                }

                val html = resp.text
                if (html.contains("Can't access link ? Stuck into ads ?")) break

                val metaMatch = Regex("""content\s*=\s*["'][0-9]+;\s*url=([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                if (metaMatch != null) {
                    val nextUrl = resolveRelativeUrl(current, metaMatch.groupValues[1])
                    if (nextUrl != current && !nextUrl.contains("ardance.org") && !nextUrl.contains("fifa2026")) {
                        current = nextUrl
                        continue
                    }
                }

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

    private fun findEpisodeLabel(el: Element, fallbackIndex: Int): String {
        val epRegex = Regex("(?i)(?:episode|ep)\\s*0*(\\d+)")
        var curr: Element? = el
        var steps = 0
        while (curr != null && steps < 4) {
            steps++
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

    private suspend fun scrapeEpisodeLinksFromIntermediate(pageUrl: String, refererUrl: String): List<Pair<Int, String>> {
        val rawItems = mutableListOf<Pair<Int, String>>()
        val seenUrls = mutableSetOf<String>()

        try {
            val resolvedStart = resolveIntermediateUrl(pageUrl)
            val resp = app.get(resolvedStart, headers = defaultHeaders, referer = refererUrl)
            val html = resp.text
            val doc = resp.document

            var autoIdx = 1

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

            val hostRegex = Regex(
                """https?://(?:[a-zA-Z0-9-]+\.)*(?:xcloud\.[a-z]{2,}|xcloud\d*\.[a-z]{2,}|pixeldrain\.[a-z]{2,}|drive\.google\.com|docs\.google\.com|gdflix\.[a-z]{2,}|gdtot\.[a-z]{2,}|filepress\.[a-z]{2,}|hubcloud\.[a-z]{2,}|hubdrive\.[a-z]{2,}|workers\.dev)/[a-zA-Z0-9_/?=&.-]+""",
                RegexOption.IGNORE_CASE
            )
            hostRegex.findAll(html).forEach { m ->
                var href = m.value.replace("\\/", "/")
                if (href.contains("refer=")) {
                    href = href.substringAfter("refer=").substringBefore("&")
                    try {
                        href = URLDecoder.decode(href, "UTF-8")
                    } catch (_: Exception) {
                    }
                }
                if (!isIgnoredOrAdUrl(href) && seenUrls.add(href)) {
                    rawItems.add(autoIdx to href)
                    autoIdx++
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

        val episodeMap = linkedMapOf<Int, MutableList<String>>()
        val btnSlides = document.select("a.btn-slide, .entry-content a[href*='sohojgyan'], .entry-content a[href*='blogspot'], .entry-content a[href*='xcloud'], .entry-content a[href*='pixeldrain'], .entry-content a[href*='drive.google.com'], .entry-content a[href*='gdflix'], .entry-content a[href*='hubcloud']")

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
            if (isIgnoredOrAdUrl(itemUrl) && !itemUrl.contains("sohojgyan") && !itemUrl.contains("blogspot")) {
                continue
            }

            val currentUrl = resolveIntermediateUrl(itemUrl)

            if (currentUrl.contains("sohojgyan") || currentUrl.contains("blogspot")) {
                val scraped = scrapeEpisodeLinksFromIntermediate(currentUrl, "$mainUrl/")
                for ((_, link) in scraped) {
                    processSingleHostLink(link, currentUrl, subtitleCallback, callback)
                }
                continue
            }

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
        if (isIgnoredOrAdUrl(rawUrl)) return
        val url = resolveIntermediateUrl(rawUrl)

        when {
            url.contains("xcloud", ignoreCase = true) -> {
                extractXCloud(url, subtitleCallback, callback)
            }
            url.contains("pixeldrain", ignoreCase = true) -> {
                extractPixeldrain(url, callback)
            }
            url.contains("drive.google.com", ignoreCase = true) ||
            url.contains("docs.google.com", ignoreCase = true) ||
            url.contains("drive.usercontent.google.com", ignoreCase = true) -> {
                extractGDrive(url, subtitleCallback, callback)
            }
            else -> {
                val handled = loadExtractor(url, referer, subtitleCallback, callback)
                if (!handled) {
                    try {
                        val doc = app.get(url, headers = defaultHeaders, referer = referer).document
                        doc.select("a[href]").forEach { a ->
                            val href = a.absUrl("href")
                            if (isIgnoredOrAdUrl(href)) return@forEach
                            if (href.contains("xcloud", ignoreCase = true)) {
                                extractXCloud(href, subtitleCallback, callback)
                            } else if (href.contains("pixeldrain", ignoreCase = true)) {
                                extractPixeldrain(href, callback)
                            } else if (href.contains("drive.google.com") || href.contains("drive.usercontent.google.com")) {
                                extractGDrive(href, subtitleCallback, callback)
                            } else if (href.contains("googleusercontent.com") || href.contains("r2.dev") || href.contains("workers.dev")) {
                                callback.invoke(
                                    newExtractorLink("Direct", "Direct Stream", href, ExtractorLinkType.VIDEO)
                                )
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private suspend fun extractPixeldrain(url: String, callback: (ExtractorLink) -> Unit) {
        val fileId = Regex("""/u/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/api/file/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringAfterLast("/").substringBefore("?")

        if (fileId.isNotEmpty()) {
            val directApiUrl = "https://pixeldrain.com/api/file/$fileId"
            callback.invoke(
                newExtractorLink(
                    source = "Pixeldrain",
                    name = "Pixeldrain",
                    url = directApiUrl,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
    }

    private suspend fun extractGDrive(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val fileId = Regex("""(?:/d/|id=)([a-zA-Z0-9_-]{15,})""").find(url)?.groupValues?.get(1)

        if (!fileId.isNullOrEmpty()) {
            val directConfirmUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            try {
                val resp = app.get(directConfirmUrl, headers = defaultHeaders, allowRedirects = false)
                val loc = resp.headers["location"] ?: resp.headers["Location"]

                if (!loc.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "GDrive",
                            name = "GDrive Direct",
                            url = loc,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://drive.google.com/"
                        }
                    )
                    found = true
                } else {
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
                    }
                }
            } catch (_: Exception) {
            }

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

        loadExtractor(url, "https://drive.google.com/", subtitleCallback, callback)
        return found
    }

    private suspend fun resolveFinalStreamUrl(
        startUrl: String,
        refererUrl: String,
        sessionCookies: Map<String, String> = emptyMap()
    ): String {
        var current = startUrl
        var hops = 0
        while (hops < 6) {
            hops++
            try {
                val resp = app.get(
                    current,
                    headers = defaultHeaders,
                    referer = refererUrl,
                    cookies = sessionCookies,
                    allowRedirects = false
                )
                val loc = resp.headers["location"] ?: resp.headers["Location"]
                if (!loc.isNullOrEmpty()) {
                    val nextUrl = resolveRelativeUrl(current, loc)
                    if (isIgnoredOrAdUrl(nextUrl)) break
                    current = nextUrl
                } else {
                    break
                }
            } catch (_: Exception) {
                break
            }
        }
        return current
    }

    private suspend fun emitXCloudRedirect(
        rawRedirectUrl: String,
        label: String,
        defaultType: ExtractorLinkType,
        refererUrl: String,
        sessionCookies: Map<String, String>,
        emittedUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isIgnoredOrAdUrl(rawRedirectUrl)) return false
        val fullRedirectUrl = resolveRelativeUrl(refererUrl, rawRedirectUrl)
        val directCdnUrl = resolveFinalStreamUrl(fullRedirectUrl, refererUrl, sessionCookies)

        if (isIgnoredOrAdUrl(directCdnUrl) || !emittedUrls.add(directCdnUrl)) return false

        val hostLabel = try {
            URI(directCdnUrl).host ?: "CDN"
        } catch (_: Exception) {
            "CDN"
        }
        val finalType = if (
            directCdnUrl.contains(".m3u8", ignoreCase = true) ||
            directCdnUrl.contains(".workers.dev", ignoreCase = true)
        ) {
            ExtractorLinkType.M3U8
        } else {
            defaultType
        }

        callback.invoke(
            newExtractorLink(
                source = "XCloud",
                name = "$label - $hostLabel",
                url = directCdnUrl,
                type = finalType
            ) {
                this.referer = ""
            }
        )
        return true
    }

    private suspend fun extractXCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val emittedUrls = mutableSetOf<String>()

        // Case A: Agar input URL khud hi direct /apis/cloud/redirect/, /apis/cloud2/redirect/, ya /apis/redirect/ hai
        if (url.contains("/apis/cloud/redirect/") ||
            url.contains("/apis/cloud2/redirect/") ||
            url.contains("/apis/redirect/")
        ) {
            val label = when {
                url.contains("/apis/cloud2/redirect/") -> "XCloud Server 2"
                url.contains("/apis/cloud/redirect/") -> "XCloud Server 1"
                else -> "XCloud Mirror"
            }
            return emitXCloudRedirect(url, label, ExtractorLinkType.VIDEO, url, emptyMap(), emittedUrls, callback)
        }

        val resolvedUrl = resolveIntermediateUrl(url)
        val xCloudResponse = app.get(resolvedUrl, headers = defaultHeaders)
        val xCloudDoc = xCloudResponse.document
        val resolvedPageUrl = xCloudResponse.url.ifEmpty { resolvedUrl }
        val baseCookies = xCloudResponse.cookies

        val mediaId = resolvedPageUrl.substringAfterLast("/").substringBefore("?").trim()
        val baseUri = try {
            val u = URI(resolvedPageUrl)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            "https://xcloud1.lol"
        }

        // Case B: Token / Download page (https://xcloud1.lol/<id>?token=...) ke andar se /apis/redirect/ aur /apis/cloud/redirect/ links nikalo
        xCloudDoc.select("a[href]").forEach { a ->
            val href = a.absUrl("href")
            if (isIgnoredOrAdUrl(href)) return@forEach

            if (href.contains("/apis/cloud/redirect/") ||
                href.contains("/apis/cloud2/redirect/") ||
                href.contains("/apis/redirect/")
            ) {
                val btnText = a.text().trim().ifEmpty { "XCloud Direct" }
                if (emitXCloudRedirect(href, btnText, ExtractorLinkType.VIDEO, resolvedPageUrl, baseCookies, emittedUrls, callback)) {
                    found = true
                }
            } else if (href.matches(Regex("(?i).*\\.(mkv|mp4|m3u8)(\\?.*)?$")) ||
                href.contains("r2.cloudflarestorage.com") ||
                href.contains("workers.dev") ||
                href.contains("googleusercontent.com")
            ) {
                if (emittedUrls.add(href)) {
                    val type = if (href.contains(".m3u8", true) || href.contains(".workers.dev", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                    callback.invoke(
                        newExtractorLink("XCloud", a.text().trim().ifEmpty { "XCloud File" }, href, type)
                    )
                    found = true
                }
            }
        }

        // Case C: Streaming player page (stream.xcloud1.lol/<id> aur /stream_<id>) se PRIMARY_URL, WASABI_URL, HLS_URL nikalo
        val streamCandidates = linkedSetOf<String>()
        xCloudDoc.selectFirst("div.vd a[href*='stream_'], a[href*='/stream_']")
            ?.absUrl("href")
            ?.takeIf { it.isNotEmpty() }
            ?.let { streamCandidates.add(resolveIntermediateUrl(it)) }

        if (mediaId.isNotEmpty() && mediaId.length > 2) {
            streamCandidates.add("https://stream.xcloud1.lol/$mediaId")
            streamCandidates.add("$baseUri/stream_$mediaId")
        }

        for (streamPageUrl in streamCandidates) {
            try {
                val streamResp = app.get(
                    streamPageUrl,
                    headers = defaultHeaders,
                    referer = resolvedPageUrl,
                    cookies = baseCookies
                )
                val finalPlayerUrl = streamResp.url.ifEmpty { streamPageUrl }
                val streamCookies = baseCookies + streamResp.cookies
                val streamHtml = streamResp.text

                // Subtitles
                Regex("""\{[^{}]*"lang"\s*:\s*"([^"]+)"[^{}]*"tgid"\s*:\s*(\d+)[^{}]*\}""")
                    .findAll(streamHtml)
                    .forEach { m ->
                        subtitleCallback.invoke(
                            SubtitleFile(m.groupValues[1], "https://sub.xcloud.icu/${m.groupValues[2]}")
                        )
                    }

                // Script variables: PRIMARY_URL (/apis/cloud/redirect/...), WASABI_URL (/apis/cloud2/redirect/...), HLS_URL
                val varPatterns = listOf(
                    "PRIMARY_URL" to ("XCloud Server 1" to ExtractorLinkType.VIDEO),
                    "WASABI_URL" to ("XCloud Server 2" to ExtractorLinkType.VIDEO),
                    "HLS_URL" to ("XCloud HLS" to ExtractorLinkType.M3U8)
                )

                for ((varName, info) in varPatterns) {
                    val match = Regex("""var\s+$varName\s*=\s*["']([^"']+)["']""").find(streamHtml)
                    val rawVal = match?.groupValues?.get(1)
                    if (!rawVal.isNullOrEmpty() && rawVal != "null") {
                        if (emitXCloudRedirect(rawVal, info.first, info.second, finalPlayerUrl, streamCookies, emittedUrls, callback)) {
                            found = true
                        }
                    }
                }

                // Direct .m3u8 / .workers.dev / .mkv links agar HTML/JS me hon
                Regex("""["'](https?://[^"'\s<>]+\.(?:m3u8|mp4|mkv)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
                    .findAll(streamHtml)
                    .forEach { m ->
                        val vUrl = m.groupValues[1]
                        if (!isIgnoredOrAdUrl(vUrl) && emittedUrls.add(vUrl)) {
                            val type = if (vUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink("XCloud", "XCloud Direct", vUrl, type) {
                                    this.referer = finalPlayerUrl
                                }
                            )
                            found = true
                        }
                    }

                Regex("""["'](https?://[^"'\s<>]+\.workers\.dev/[^"'\s<>]*)["']""", RegexOption.IGNORE_CASE)
                    .findAll(streamHtml)
                    .forEach { m ->
                        val wUrl = m.groupValues[1]
                        if (!isIgnoredOrAdUrl(wUrl) && emittedUrls.add(wUrl)) {
                            callback.invoke(
                                newExtractorLink("XCloud", "XCloud Worker HLS", wUrl, ExtractorLinkType.M3U8) {
                                    this.referer = finalPlayerUrl
                                }
                            )
                            found = true
                        }
                    }
            } catch (_: Exception) {
            }
        }

        // Case D: Token page URL (https://xcloud1.lol/<id>?token=...) ko khud bhi open karke /apis/redirect/ buttons nikalo
        val tokenGenUrl = xCloudDoc.selectFirst("div.vd a[href*='token='], a[href*='xcloud1.lol'][href*='token=']")
            ?.absUrl("href")
            ?: if (resolvedPageUrl.contains("token=")) resolvedPageUrl else null

        if (!tokenGenUrl.isNullOrEmpty() && !tokenGenUrl.contains("subtitle")) {
            try {
                val tokenResp = app.get(tokenGenUrl, headers = defaultHeaders, referer = resolvedPageUrl, cookies = baseCookies)
                val tokenFinalUrl = tokenResp.url.ifEmpty { tokenGenUrl }
                val tokenCookies = baseCookies + tokenResp.cookies

                tokenResp.document.select("a[href]").forEach { a ->
                    val href = a.absUrl("href")
                    if (isIgnoredOrAdUrl(href)) return@forEach
                    if (href.contains("/apis/redirect/") ||
                        href.contains("/apis/cloud/redirect/") ||
                        href.contains("/apis/cloud2/redirect/")
                    ) {
                        val label = a.text().trim().ifEmpty { "XCloud Token Mirror" }
                        if (emitXCloudRedirect(href, label, ExtractorLinkType.VIDEO, tokenFinalUrl, tokenCookies, emittedUrls, callback)) {
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
