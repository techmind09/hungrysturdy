package com.hungrysturdy

import android.content.Context
import android.util.Base64
import com.hungrysturdy.donation.DonationDialogFragment
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class DramaVerseProvider : MainAPI() {

    override var mainUrl = "https://mydverse.com"
    override var name = "DramaVerse"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "hi"
    override val hasMainPage = true

    fun openSupportDialog(context: Context) {
        DonationDialogFragment.show(
            context = context,
            pluginName = name
        )
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8"
    )

    private val xcloudReferer = "https://xcloud.tattoo/"
    private val xcloudStreamReferer = "https://stream.xcloud1.lol/"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/korean/" to "Korean Drama",
        "$mainUrl/chinese/" to "Chinese Drama",
        "$mainUrl/all-drama-list/" to "All Drama List",
        "$mainUrl/upcoming/" to "Upcoming"
    )

    // ---------------------------------------------------------
    // MAIN PAGE & SEARCH
    // ---------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val document = app.get(url, headers = defaultHeaders).document

        val home = document.select(
            "li.post-item, article.item-list, div.post-element"
        ).mapNotNull {
            it.toSearchResult()
        }.ifEmpty {
            document.select("a.post-thumb").mapNotNull {
                it.thumbToSearchResult()
            }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("h2.post-title a, h3.post-title a")
        val thumbElement = selectFirst("a.post-thumb")

        val href = titleElement?.absUrl("href")
            ?: thumbElement?.absUrl("href")
            ?: return null

        val title = titleElement?.text()?.trim()
            ?: thumbElement?.attr("aria-label")?.substringBefore("|")?.trim()
            ?: return null

        val posterUrl = selectFirst("img")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.thumbToSearchResult(): SearchResponse? {
        val href = absUrl("href").ifEmpty { return null }
        val img = selectFirst("img")

        val title = attr("aria-label")
            .substringBefore("|")
            .trim()
            .ifEmpty { img?.attr("alt")?.trim() ?: return null }

        val posterUrl = img?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery", headers = defaultHeaders).document

        return document.select("li.post-item, article.item-list")
            .mapNotNull { it.toSearchResult() }
            .ifEmpty {
                document.select("a.post-thumb").mapNotNull { it.thumbToSearchResult() }
            }
    }

    // ---------------------------------------------------------
    // URL HELPERS
    // ---------------------------------------------------------

    private fun resolveRelativeUrl(baseUrl: String, rawPath: String): String {
        val cleaned = rawPath
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()

        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            return cleaned
        }

        return try {
            URI(baseUrl).resolve(cleaned).toString()
        } catch (_: Exception) {
            cleaned
        }
    }

    private fun isXCloudHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("xcloud") || lower.contains("xcloud1.space")
    }

    private fun isXCloudHlsUrl(url: String): Boolean {
        val lower = url.lowercase().substringBefore("#").substringBefore("?")
        return lower.contains(".workers.dev/hls/") ||
            lower.contains(".workers.dev/m3u8/") ||
            lower.endsWith(".m3u8")
    }

    private fun isSupportedHostUrl(url: String): Boolean {
        val lower = url.lowercase()
        return isXCloudHost(lower) ||
            lower.contains("pixeldrain") ||
            lower.contains("drive.google.com") ||
            lower.contains("docs.google.com") ||
            lower.contains("drive.usercontent.google.com") ||
            lower.contains("googleusercontent.com") ||
            lower.contains("gdflix") ||
            lower.contains("gdtot") ||
            lower.contains("filepress") ||
            lower.contains("hubcloud") ||
            lower.contains("hubdrive") ||
            lower.contains("workers.dev") ||
            lower.contains("r2.dev") ||
            isXCloudHlsUrl(lower)
    }

    private fun isEpisodePageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("blogspot.com") || lower.contains("mydverse0")
    }

    private fun isSafelinkUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("sohojgyan.com")
    }

    private fun isIgnoredOrAdUrl(href: String): Boolean {
        val lower = href.lowercase()
        return !lower.startsWith("http") ||
            lower.contains("ardance.org") ||
            lower.contains("invoke_layer") ||
            lower.contains("fifa2026") ||
            lower.contains("machb.org") ||
            lower.contains("viinyzfu.com") ||
            lower.contains("join4ra.com") ||
            lower.contains("werefilledwit.org") ||
            lower.contains("zeusy.wiki") ||
            lower.contains("/redir.php") ||
            lower.contains("/redir2.php") ||
            lower.contains("5gvci.com") ||
            lower.contains("llvpn.com") ||
            lower.contains("telegram") ||
            lower.contains("t.me/") ||
            lower.contains("whatsapp") ||
            lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("instagram.com") ||
            lower.contains("/search/label/") ||
            lower.contains("/category/") ||
            lower.contains("/tag/") ||
            lower.contains("/author/") ||
            lower.contains("#comment") ||
            (
                lower.contains("google.com") &&
                    !lower.contains("drive.google.com") &&
                    !lower.contains("docs.google.com") &&
                    !lower.contains("drive.usercontent.google.com") &&
                    !lower.contains("googleusercontent.com")
            )
    }

    private fun extractCodeParam(url: String): String? {
        val match = Regex("""[?&]code=([^&#]+)""", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        return try {
            URLDecoder.decode(match.groupValues[1], "UTF-8").trim().ifBlank { null }
        } catch (_: Exception) {
            match.groupValues[1].trim().ifBlank { null }
        }
    }

    private suspend fun decodeSafelinkByCode(code: String, referer: String): String? {
        if (code.isBlank()) return null
        return try {
            val apiUrl = "https://safe.sohojgyan.com/api/decode/${URLEncoder.encode(code, "UTF-8")}"
            val jsonText = app.get(
                apiUrl,
                headers = defaultHeaders + mapOf("Accept" to "application/json, text/plain, */*"),
                referer = referer
            ).text

            val root = JSONObject(jsonText)
            if (root.optBoolean("success", false)) {
                val originalUrl = root.optJSONObject("data")
                    ?.optString("original_url", "")
                    ?.trim()
                if (!originalUrl.isNullOrBlank() && originalUrl.startsWith("http")) {
                    return resolveRelativeUrl(referer, originalUrl)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTargetUrlFromHtml(html: String, baseUrl: String): String? {
        val blogspotMatch = Regex(
            """https?://[a-zA-Z0-9.-]*blogspot\.com/p/[^\s"'<>\\]+""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value
        if (!blogspotMatch.isNullOrBlank()) {
            return resolveRelativeUrl(baseUrl, blogspotMatch)
        }

        val b64Regex = Regex("""atob\(\s*["']([A-Za-z0-9+/=]{12,})["']\s*\)""")
        for (m in b64Regex.findAll(html)) {
            try {
                val decoded = String(
                    Base64.decode(m.groupValues[1], Base64.DEFAULT)
                ).trim()
                if (decoded.startsWith("http") && !isIgnoredOrAdUrl(decoded)) {
                    return decoded
                }
            } catch (_: Exception) {
            }
        }

        val jsUrlMatch = Regex(
            """(?:window\.location(?:\.href)?|location\.href|location\.replace|href\s*=|url\s*[:=])\s*["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull { candidate ->
                val clean = candidate.replace("\\/", "/").replace("&amp;", "&").trim()
                !isIgnoredOrAdUrl(clean) &&
                    (isEpisodePageUrl(clean) || isSupportedHostUrl(clean))
            }

        if (!jsUrlMatch.isNullOrBlank()) {
            return resolveRelativeUrl(baseUrl, jsUrlMatch)
        }

        return null
    }

    // ---------------------------------------------------------
    // REDIRECT & SAFELINK RESOLVER
    // ---------------------------------------------------------

    private suspend fun resolveIntermediateUrl(url: String): String {
        if (isXCloudHlsUrl(url) || isEpisodePageUrl(url)) return url

        extractCodeParam(url)?.let { code ->
            decodeSafelinkByCode(code, url)?.let { decoded ->
                return resolveIntermediateUrl(decoded)
            }
        }

        var current = url
        var attempts = 0
        var sessionCookies = emptyMap<String, String>()
        val visited = mutableSetOf<String>()

        while (attempts < 10) {
            attempts++
            if (!visited.add(current)) break
            try {
                val resp = app.get(
                    current,
                    headers = defaultHeaders,
                    referer = "$mainUrl/",
                    cookies = sessionCookies,
                    allowRedirects = false
                )
                sessionCookies = sessionCookies + resp.cookies

                val location = resp.headers["location"] ?: resp.headers["Location"]
                if (!location.isNullOrEmpty()) {
                    val next = resolveRelativeUrl(current, location)

                    extractCodeParam(next)?.let { code ->
                        decodeSafelinkByCode(code, next)?.let { decoded ->
                            return resolveIntermediateUrl(decoded)
                        }
                    }

                    if (!isIgnoredOrAdUrl(next)) {
                        current = next
                        if (isXCloudHlsUrl(current) || isEpisodePageUrl(current)) return current
                        continue
                    }
                }

                val html = resp.text
                if (html.contains("EpList") || html.contains("EpItem")) {
                    return current
                }

                val inlineCode = Regex("""[?&]code=([A-Za-z0-9_-]+)""").find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("""safelink_code["']?\s*[,:]\s*["']([A-Za-z0-9_-]+)["']""").find(html)?.groupValues?.getOrNull(1)
                if (!inlineCode.isNullOrBlank()) {
                    decodeSafelinkByCode(inlineCode, current)?.let { decoded ->
                        return resolveIntermediateUrl(decoded)
                    }
                }

                val extracted = extractTargetUrlFromHtml(html, current)
                if (!extracted.isNullOrBlank() && !isIgnoredOrAdUrl(extracted)) {
                    current = extracted
                    if (isXCloudHlsUrl(current) || isEpisodePageUrl(current)) return current
                    continue
                }

                val metaMatch = Regex(
                    """content\s*=\s*["'][0-9]+;\s*url=([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(html)

                if (metaMatch != null) {
                    val nextUrl = resolveRelativeUrl(current, metaMatch.groupValues[1])
                    if (!isIgnoredOrAdUrl(nextUrl)) {
                        current = nextUrl
                        if (isXCloudHlsUrl(current) || isEpisodePageUrl(current)) return current
                        continue
                    }
                }

                val form = resp.document.selectFirst("form[action]")
                if (form != null) {
                    val action = resolveRelativeUrl(current, form.attr("action").ifEmpty { current })
                    val formData = form.select("input[name]").associate {
                        it.attr("name") to it.attr("value")
                    }
                    if (formData.isNotEmpty()) {
                        val postResp = app.post(
                            action,
                            headers = defaultHeaders,
                            referer = current,
                            cookies = sessionCookies,
                            data = formData,
                            allowRedirects = false
                        )
                        sessionCookies = sessionCookies + postResp.cookies
                        val postLoc = postResp.headers["location"] ?: postResp.headers["Location"]
                        if (!postLoc.isNullOrEmpty()) {
                            current = resolveRelativeUrl(action, postLoc)
                            if (isXCloudHlsUrl(current) || isEpisodePageUrl(current)) return current
                            continue
                        }
                        val postExtracted = extractTargetUrlFromHtml(postResp.text, action)
                        if (!postExtracted.isNullOrBlank()) return postExtracted
                    }
                }

                break
            } catch (_: Exception) {
                break
            }
        }

        return current
    }

    // ---------------------------------------------------------
    // EPISODE PARSER
    // ---------------------------------------------------------

    private fun parseEpNumber(text: String, fallback: Int): Int {
        return Regex("""(?i)(?:episode|ep|e)\s*0*(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b0*(\d+)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: fallback
    }

    private fun parseEpisodesFromDocument(
        doc: Document,
        baseUrl: String
    ): List<Pair<Int, String>> {
        val results = mutableListOf<Pair<Int, String>>()
        val seenPairs = mutableSetOf<Pair<Int, String>>()

        val epItems = doc.select("div.EpItem")
        if (epItems.isNotEmpty()) {
            epItems.forEachIndexed { index, item ->
                val epNameText = item.selectFirst(".EpName")?.text()?.trim().orEmpty()
                val epNum = parseEpNumber(epNameText, index + 1)

                item.select("a[href]").forEach { aTag ->
                    val href = resolveRelativeUrl(baseUrl, aTag.attr("href"))
                    if (
                        href.isNotEmpty() &&
                        !isIgnoredOrAdUrl(href) &&
                        !href.contains("blogspot.com") &&
                        seenPairs.add(epNum to href)
                    ) {
                        results.add(epNum to href)
                    }
                }
            }
            if (results.isNotEmpty()) return results
        }

        var autoIdx = 1
        doc.select("#postBody a[href], .entry-content a[href], .post-body a[href]").forEach { aTag ->
            val href = resolveRelativeUrl(baseUrl, aTag.attr("href"))
            if (
                href.isNotEmpty() &&
                !isIgnoredOrAdUrl(href) &&
                !href.contains("blogspot.com") &&
                isSupportedHostUrl(href)
            ) {
                val contextText = aTag.parent()?.selectFirst(".EpName")?.text()
                    ?: aTag.previousElementSibling()?.text()
                    ?: aTag.text()
                val epNum = parseEpNumber(contextText, autoIdx)
                if (seenPairs.add(epNum to href)) {
                    results.add(epNum to href)
                    autoIdx++
                }
            }
        }

        if (results.isEmpty()) {
            val hostRegex = Regex(
                """https?://(?:[a-zA-Z0-9-]+\.)*(?:xcloud\.[a-z]{2,}|xcloud\d*\.[a-z]{2,}|pixeldrain\.[a-z]{2,}|drive\.google\.com|docs\.google\.com|gdflix\.[a-z]{2,}|gdtot\.[a-z]{2,}|filepress\.[a-z]{2,}|hubcloud\.[a-z]{2,}|hubdrive\.[a-z]{2,}|workers\.dev)/[^\s"'<>]+""",
                RegexOption.IGNORE_CASE
            )
            hostRegex.findAll(doc.html()).forEach { match ->
                var href = match.value
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
                    .trim()
                    .trimEnd(',', ';', ')', ']', '}')

                if (href.contains("refer=")) {
                    val referValue = href.substringAfter("refer=").substringBefore("&")
                    try {
                        href = URLDecoder.decode(referValue, "UTF-8")
                    } catch (_: Exception) {
                    }
                }

                if (!isIgnoredOrAdUrl(href) && seenPairs.add(autoIdx to href)) {
                    results.add(autoIdx to href)
                    autoIdx++
                }
            }
        }

        return results
    }

    private suspend fun scrapeEpisodeLinksFromIntermediate(
        pageUrl: String,
        refererUrl: String
    ): List<Pair<Int, String>> {
        return try {
            val resolvedStart = resolveIntermediateUrl(pageUrl)
            val resp = app.get(
                resolvedStart,
                headers = defaultHeaders,
                referer = refererUrl
            )
            val finalUrl = resp.url.ifEmpty { resolvedStart }
            parseEpisodesFromDocument(resp.document, finalUrl)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst(
            "h1.entry-title, h1.post-title, h1.pTtl"
        )?.text()?.trim()?.substringBefore("|")?.trim() ?: "Drama"

        val poster = document.selectFirst(
            "a.post-thumb img, .post-thumb img, .entry-content img, .post-body img"
        )?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }

        val description = document.selectFirst(".entry-content p, .post-body p")?.text()

        val episodeMap = linkedMapOf<Int, MutableList<String>>()

        if (document.select("div.EpItem").isNotEmpty()) {
            val directEps = parseEpisodesFromDocument(document, url)
            for ((epNum, epHref) in directEps) {
                episodeMap.getOrPut(epNum) { mutableListOf() }.add(epHref)
            }
        }

        if (episodeMap.isEmpty()) {
            val candidateUrls = linkedSetOf<Pair<String, String>>()

            document.select(
                ".btn-container a.btn-slide[href], a.btn-slide[href], .entry-content a[href], .post-body a[href], article a[href], a.button[href]"
            ).forEach { el ->
                val href = el.absUrl("href").ifEmpty {
                    resolveRelativeUrl(url, el.attr("href"))
                }
                val label = el.text().trim().ifEmpty { el.selectFirst(".btn-title")?.text()?.trim().orEmpty() }
                if (href.isNotEmpty() && href != url && !isIgnoredOrAdUrl(href)) {
                    val isInternalPost = href.startsWith(mainUrl) &&
                        !href.contains("?") &&
                        !href.contains("go") &&
                        !href.contains("link") &&
                        !href.contains("watch") &&
                        !href.contains("download")
                    if (!isInternalPost) {
                        candidateUrls.add(href to label)
                    }
                }
            }

            val rawLinkRegex = Regex(
                """https?://[a-zA-Z0-9.-]*(?:blogspot\.com|sohojgyan\.[a-z]+|xcloud\.[a-z]+|xcloud\d*\.[a-z]+|pixeldrain\.[a-z]+|drive\.google\.com)/[^\s"'<>\\]+""",
                RegexOption.IGNORE_CASE
            )
            rawLinkRegex.findAll(document.html()).forEach { match ->
                val cleanHref = match.value.replace("\\/", "/").replace("&amp;", "&").trim()
                if (
                    !isIgnoredOrAdUrl(cleanHref) &&
                    !cleanHref.contains("/feeds/") &&
                    !cleanHref.contains("/favicon") &&
                    !cleanHref.contains("blogger.com")
                ) {
                    candidateUrls.add(cleanHref to "Episode")
                }
            }

            for ((candUrl, candLabel) in candidateUrls) {
                val resolved = resolveIntermediateUrl(candUrl)

                if (
                    isEpisodePageUrl(resolved) ||
                    isEpisodePageUrl(candUrl) ||
                    isSafelinkUrl(candUrl) ||
                    isSafelinkUrl(resolved)
                ) {
                    val scraped = scrapeEpisodeLinksFromIntermediate(resolved, url)
                    if (scraped.isNotEmpty()) {
                        for ((epNum, epHref) in scraped) {
                            episodeMap.getOrPut(epNum) { mutableListOf() }.add(epHref)
                        }
                    }
                } else if (isSupportedHostUrl(resolved)) {
                    val epNum = parseEpNumber(candLabel, (episodeMap.keys.maxOrNull() ?: 0) + 1)
                    episodeMap.getOrPut(epNum) { mutableListOf() }.add(resolved)
                }
            }
        }

        val episodes = episodeMap.toSortedMap().map { (epNum, urls) ->
            val combinedData = urls.distinct().joinToString("|||")
            newEpisode(combinedData) {
                this.name = "Episode $epNum"
                this.episode = epNum
            }
        }.toMutableList()

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Episode 1"
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.AsianDrama,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ---------------------------------------------------------
    // LOAD LINKS
    // ---------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawLinks = data.split("|||")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (itemUrl in rawLinks) {
            if (isIgnoredOrAdUrl(itemUrl)) continue

            val currentUrl = if (isXCloudHlsUrl(itemUrl)) {
                itemUrl
            } else {
                resolveIntermediateUrl(itemUrl)
            }

            if (isEpisodePageUrl(currentUrl)) {
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

    // ---------------------------------------------------------
    // HOST ROUTER
    // ---------------------------------------------------------

    private suspend fun processSingleHostLink(
        rawUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isIgnoredOrAdUrl(rawUrl)) return

        if (isXCloudHlsUrl(rawUrl)) {
            emitXCloudHls(rawUrl, xcloudStreamReferer, callback)
            return
        }

        val url = resolveIntermediateUrl(rawUrl)

        if (isXCloudHlsUrl(url)) {
            emitXCloudHls(url, xcloudStreamReferer, callback)
            return
        }

        when {
            isXCloudHost(url) -> {
                val found = extractXCloud(url, subtitleCallback, callback)
                if (!found) {
                    loadExtractor(url, referer, subtitleCallback, callback)
                }
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

                            when {
                                isXCloudHlsUrl(href) -> emitXCloudHls(href, xcloudStreamReferer, callback)
                                isXCloudHost(href) -> extractXCloud(href, subtitleCallback, callback)
                                href.contains("pixeldrain", ignoreCase = true) -> extractPixeldrain(href, callback)
                                href.contains("drive.google.com") || href.contains("drive.usercontent.google.com") -> {
                                    extractGDrive(href, subtitleCallback, callback)
                                }
                                href.contains("googleusercontent.com") || href.contains("r2.dev") -> {
                                    callback(
                                        newExtractorLink(
                                            "Direct",
                                            "Direct Stream",
                                            href,
                                            ExtractorLinkType.VIDEO
                                        )
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // XCLOUD HLS & VIDEO EMITTERS
    // ---------------------------------------------------------

    private suspend fun emitXCloudHls(
        hlsUrl: String,
        refererUrl: String = xcloudStreamReferer,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isXCloudHlsUrl(hlsUrl)) return false

        val originUrl = try {
            val u = URI(refererUrl)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            "https://stream.xcloud1.lol"
        }

        callback(
            newExtractorLink(
                source = "XCloud",
                name = "XCloud Direct HLS",
                url = hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = refererUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                    "Accept" to "*/*",
                    "Referer" to refererUrl,
                    "Origin" to originUrl
                )
            }
        )

        return true
    }

    private suspend fun emitXCloudVideoLink(
        rawUrl: String,
        label: String,
        refererUrl: String,
        sessionCookies: Map<String, String>,
        emittedUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullUrl = resolveRelativeUrl(refererUrl, rawUrl)
        if (isIgnoredOrAdUrl(fullUrl)) return false

        if (isXCloudHlsUrl(fullUrl)) {
            if (!emittedUrls.add(fullUrl)) return false
            return emitXCloudHls(fullUrl, refererUrl, callback)
        }

        var targetUrl = fullUrl
        var hops = 0
        while (hops < 4) {
            hops++
            try {
                val resp = app.get(
                    targetUrl,
                    headers = defaultHeaders,
                    referer = refererUrl,
                    cookies = sessionCookies,
                    allowRedirects = false
                )
                val loc = resp.headers["location"] ?: resp.headers["Location"]
                if (!loc.isNullOrBlank()) {
                    val next = resolveRelativeUrl(targetUrl, loc)
                    if (isIgnoredOrAdUrl(next) || next == targetUrl) break
                    targetUrl = next
                    if (isXCloudHlsUrl(targetUrl)) break
                    continue
                }
                break
            } catch (_: Exception) {
                break
            }
        }

        if (isIgnoredOrAdUrl(targetUrl) || !emittedUrls.add(targetUrl)) {
            return false
        }

        if (isXCloudHlsUrl(targetUrl)) {
            return emitXCloudHls(targetUrl, refererUrl, callback)
        }

        val cookieHeader = sessionCookies.entries.joinToString("; ") {
            "${it.key}=${it.value}"
        }

        callback(
            newExtractorLink(
                source = "XCloud",
                name = label,
                url = targetUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = refererUrl
                this.quality = Qualities.Unknown.value
                val headers = mutableMapOf(
                    "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                    "Accept" to "*/*",
                    "Referer" to refererUrl
                )
                if (cookieHeader.isNotEmpty()) {
                    headers["Cookie"] = cookieHeader
                }
                this.headers = headers
            }
        )

        return true
    }

    // ---------------------------------------------------------
    // PIXELDRAIN
    // ---------------------------------------------------------

    private suspend fun extractPixeldrain(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""/u/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/api/file/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringAfterLast("/").substringBefore("?")

        if (fileId.isNotEmpty()) {
            val directApiUrl = "https://pixeldrain.com/api/file/$fileId"
            callback(
                newExtractorLink(
                    source = "Pixeldrain",
                    name = "Pixeldrain",
                    url = directApiUrl,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
    }

    // ---------------------------------------------------------
    // GOOGLE DRIVE
    // ---------------------------------------------------------

    private suspend fun extractGDrive(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val emitted = mutableSetOf<String>()

        val fileId = Regex("""(?:/d/|id=)([a-zA-Z0-9_-]{15,})""")
            .find(url)?.groupValues?.get(1)

        if (!fileId.isNullOrEmpty()) {
            val gdriveHeaders = mapOf(
                "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                "Referer" to "https://drive.google.com/",
                "Accept" to "*/*"
            )

            val candidateUrls = listOf(
                "https://drive.usercontent.google.com/u/0/uc?id=$fileId&export=download",
                "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            )

            for (cand in candidateUrls) {
                var finalStreamUrl = cand
                try {
                    val resp = app.get(
                        cand,
                        headers = gdriveHeaders,
                        allowRedirects = false
                    )
                    val loc = resp.headers["location"] ?: resp.headers["Location"]
                    if (!loc.isNullOrBlank()) {
                        finalStreamUrl = resolveRelativeUrl(cand, loc)
                    } else {
                        val formAction = resp.document
                            .selectFirst("form[action]")
                            ?.absUrl("action")
                        if (!formAction.isNullOrBlank()) {
                            val params = resp.document
                                .select("form[action] input[name]")
                                .joinToString("&") { inp ->
                                    val k = URLEncoder.encode(inp.attr("name"), "UTF-8")
                                    val v = URLEncoder.encode(inp.attr("value"), "UTF-8")
                                    "$k=$v"
                                }
                            finalStreamUrl = if (params.isNotEmpty()) "$formAction?$params" else formAction
                        }
                    }
                } catch (_: Exception) {
                }

                if (emitted.add(finalStreamUrl)) {
                    callback(
                        newExtractorLink(
                            source = "GDrive",
                            name = "GDrive Download/Stream",
                            url = finalStreamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://drive.google.com/"
                            this.quality = Qualities.P720.value
                            this.headers = gdriveHeaders
                        }
                    )
                    found = true
                }
            }
        }

        val extractorFound = loadExtractor(
            url,
            "https://drive.google.com/",
            subtitleCallback,
            callback
        )

        return found || extractorFound
    }

    // ---------------------------------------------------------
    // XCLOUD CONFIG PARSER & EXTRACTOR
    // ---------------------------------------------------------

    private fun extractXCloudConfigValue(html: String, key: String): String? {
        val escapedKey = Regex.escape(key)
        val patterns = listOf(
            Regex("""["']?$escapedKey["']?\s*:\s*["']((?:\\.|[^"'\\])+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:var|let|const)\s+$escapedKey\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""$escapedKey\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val value = pattern.find(html)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?.replace("\\u003d", "=")
                ?.replace("&amp;", "&")
                ?.trim()

            if (!value.isNullOrBlank() && value != "null" && value != "undefined") {
                return value
            }
        }

        return null
    }

    private suspend fun extractXCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val emittedUrls = mutableSetOf<String>()

        if (isXCloudHlsUrl(url)) {
            return emitXCloudHls(url, xcloudStreamReferer, callback)
        }

        try {
            val resolvedUrl = resolveIntermediateUrl(url)
            if (isXCloudHlsUrl(resolvedUrl)) {
                return emitXCloudHls(resolvedUrl, xcloudStreamReferer, callback)
            }

            val cleanPath = resolvedUrl
                .substringBefore("?")
                .substringBefore("#")
                .trimEnd('/')

            val mediaId = cleanPath
                .substringAfterLast("/")
                .removePrefix("stream_")
                .trim()

            val streamPages = linkedSetOf<String>()
            var baseCookies = emptyMap<String, String>()

            if (mediaId.isNotEmpty() && mediaId.length >= 6) {
                streamPages.add("https://stream.xcloud1.lol/$mediaId")
            }

            try {
                val landingResp = app.get(
                    resolvedUrl,
                    headers = defaultHeaders,
                    referer = xcloudReferer
                )

                val landingFinalUrl = landingResp.url.ifEmpty { resolvedUrl }
                baseCookies = landingResp.cookies
                streamPages.add(landingFinalUrl)

                landingResp.document.select("a[href], [data-href], [data-url]").forEach { el ->
                    val rawHref = when {
                        el.hasAttr("href") -> el.absUrl("href")
                        el.hasAttr("data-href") -> resolveRelativeUrl(landingFinalUrl, el.attr("data-href"))
                        el.hasAttr("data-url") -> resolveRelativeUrl(landingFinalUrl, el.attr("data-url"))
                        else -> ""
                    }

                    if (rawHref.isBlank() || isIgnoredOrAdUrl(rawHref)) return@forEach

                    when {
                        rawHref.contains("stream.xcloud", ignoreCase = true) ||
                            rawHref.contains("/stream_", ignoreCase = true) -> {
                            streamPages.add(rawHref)
                        }

                        rawHref.contains("/apis/cloud/redirect/", ignoreCase = true) ||
                            rawHref.contains("/apis/cloud2/redirect/", ignoreCase = true) ||
                            rawHref.contains("/apis/redirect/", ignoreCase = true) -> {
                            val btnLabel = el.text().trim().ifEmpty { "XCloud Direct" }
                            if (
                                emitXCloudVideoLink(
                                    rawHref,
                                    btnLabel,
                                    landingFinalUrl,
                                    baseCookies,
                                    emittedUrls,
                                    callback
                                )
                            ) {
                                found = true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                streamPages.add(resolvedUrl)
            }

            if (mediaId.isNotEmpty() && mediaId.length >= 6) {
                streamPages.add("https://xcloud.tattoo/stream_$mediaId")
                streamPages.add("https://xcloud1.lol/stream_$mediaId")
            }

            for (pageUrl in streamPages) {
                try {
                    val resp = app.get(
                        pageUrl,
                        headers = defaultHeaders,
                        referer = xcloudReferer,
                        cookies = baseCookies
                    )

                    val finalPlayerUrl = resp.url.ifEmpty { pageUrl }
                    val playerCookies = baseCookies + resp.cookies

                    val html = resp.text
                        .replace("\\/", "/")
                        .replace("&amp;", "&")
                        .replace("\\u0026", "&")
                        .replace("\\u003d", "=")

                    val subtitleRegex = Regex(
                        """"lang"\s*:\s*"([^"]+)"[^}]*?"tgid"\s*:\s*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )

                    subtitleRegex.findAll(html).forEach { match ->
                        subtitleCallback(
                            SubtitleFile(
                                match.groupValues[1],
                                "https://sub.xcloud.icu/${match.groupValues[2]}"
                            )
                        )
                    }

                    val workerDomains = Regex(
                        """https?://[a-zA-Z0-9.-]+\.workers\.dev""",
                        RegexOption.IGNORE_CASE
                    ).findAll(html)
                        .map { it.value.trimEnd('/') }
                        .distinct()
                        .toList()

                    val hlsLinks = linkedSetOf<String>()

                    val hlsConfigKeys = listOf(
                        "hlsUrl",
                        "photosHlsUrl",
                        "HLS_URL",
                        "PHOTOS_HLS_URL"
                    )

                    for (key in hlsConfigKeys) {
                        val rawVal = extractXCloudConfigValue(html, key) ?: continue
                        if (rawVal.startsWith("http://") || rawVal.startsWith("https://")) {
                            hlsLinks.add(rawVal)
                        } else if (rawVal.startsWith("/hls/") || rawVal.startsWith("/m3u8/")) {
                            if (workerDomains.isNotEmpty()) {
                                workerDomains.forEach { domain ->
                                    hlsLinks.add("$domain$rawVal")
                                }
                            } else {
                                hlsLinks.add(resolveRelativeUrl(finalPlayerUrl, rawVal))
                            }
                        }
                    }

                    val hlsPatterns = listOf(
                        Regex(
                            """https?://[a-zA-Z0-9.-]+\.workers\.dev/(?:hls|m3u8)/[^\s"'<>\\]+""",
                            RegexOption.IGNORE_CASE
                        ),
                        Regex(
                            """https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""",
                            RegexOption.IGNORE_CASE
                        ),
                        Regex(
                            """(?:src|file|source|url|hls|playlist)\s*[:=]\s*["'](https?://[^"']+)["']""",
                            RegexOption.IGNORE_CASE
                        )
                    )

                    hlsPatterns.forEach { pattern ->
                        pattern.findAll(html).forEach { match ->
                            val candidate = if (
                                match.groupValues.size > 1 &&
                                match.groupValues[1].startsWith("http")
                            ) {
                                match.groupValues[1]
                            } else {
                                match.value
                            }
                                .replace("\\/", "/")
                                .replace("&amp;", "&")
                                .trim()
                                .trimEnd(',', ';', ')', ']', '}')

                            if (isXCloudHlsUrl(candidate)) {
                                hlsLinks.add(candidate)
                            }
                        }
                    }

                    for (hlsUrl in hlsLinks) {
                        if (emittedUrls.add(hlsUrl)) {
                            if (emitXCloudHls(hlsUrl, finalPlayerUrl, callback)) {
                                found = true
                            }
                        }
                    }

                    val videoConfigKeys = listOf(
                        "primaryUrl" to "XCloud Server 1",
                        "PRIMARY_URL" to "XCloud Server 1",
                        "wasabiUrl" to "XCloud Server 2",
                        "WASABI_URL" to "XCloud Server 2"
                    )

                    for ((key, label) in videoConfigKeys) {
                        val rawVal = extractXCloudConfigValue(html, key) ?: continue
                        if (
                            emitXCloudVideoLink(
                                rawVal,
                                label,
                                finalPlayerUrl,
                                playerCookies,
                                emittedUrls,
                                callback
                            )
                        ) {
                            found = true
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        return found
    }
}

