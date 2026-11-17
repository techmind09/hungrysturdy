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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Sec-Ch-Ua" to "\"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"122\", \"Google Chrome\";v=\"122\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\""
    )

    private val cookies = mapOf(
        "_ga_5TVV62JEB6" to "GS2.1.s1794549640\$o1\$g0\$t1794549640\$j60\$l0\$h0",
        "_ga" to "GA1.1.1175613762.1794549641",
        "_clck" to "1nykysd%5E2%5Egb4%5E0%5E2508",
        "_clsk" to "5czkhx%5E1794549642448%5E1%5E1%5Ea.clarity.ms%2Fcollect"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
        "Accept" to "*/*"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/korean/" to "Korean Drama",
        "$mainUrl/chinese/" to "Chinese Drama",
        "$mainUrl/all-drama-list/" to "All Drama List",
        "$mainUrl/upcoming/" to "Upcoming"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.endsWith("/")) {
                "${request.data}page/$page/"
            } else {
                "${request.data}/page/$page/"
            }
        }

        val document = app.get(
            url,
            headers = defaultHeaders,
            cookies = cookies
        ).document

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
            ?: thumbElement?.attr("aria-label")
                ?.substringBefore("|")
                ?.trim()
            ?: return null

        val posterUrl = selectFirst("img")?.let {
            it.absUrl("data-src").ifEmpty {
                it.absUrl("src")
            }
        }

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.AsianDrama
        ) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.thumbToSearchResult(): SearchResponse? {
        val href = absUrl("href").ifEmpty {
            return null
        }

        val img = selectFirst("img")

        val title = attr("aria-label")
            .substringBefore("|")
            .trim()
            .ifEmpty {
                img?.attr("alt")?.trim() ?: return null
            }

        val posterUrl = img?.let {
            it.absUrl("data-src").ifEmpty {
                it.absUrl("src")
            }
        }

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.AsianDrama
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=$query",
            headers = defaultHeaders,
            cookies = cookies
        ).document

        return document.select(
            "li.post-item, article.item-list"
        ).mapNotNull {
            it.toSearchResult()
        }.ifEmpty {
            document.select("a.post-thumb").mapNotNull {
                it.thumbToSearchResult()
            }
        }
    }

    // ---------------------------------------------------------
    // URL HELPERS
    // ---------------------------------------------------------

    private fun resolveRelativeUrl(
        baseUrl: String,
        rawPath: String
    ): String {
        val cleaned = rawPath
            .replace("\\/", "/")
            .trim()

        if (
            cleaned.startsWith("http://") ||
            cleaned.startsWith("https://")
        ) {
            return cleaned
        }

        return try {
            URI(baseUrl).resolve(cleaned).toString()
        } catch (_: Exception) {
            cleaned
        }
    }

    private fun isXCloudHlsUrl(url: String): Boolean {
        val lower = url.lowercase().substringBefore("?")

        return lower.contains(".workers.dev/hls/") ||
               lower.contains(".workers.dev/m3u8/") ||
               lower.endsWith(".m3u8")
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
            (
                lower.contains("google.com") &&
                    !lower.contains("drive.google.com") &&
                    !lower.contains("docs.google.com") &&
                    !lower.contains("googleusercontent.com")
            )
    }

    private suspend fun resolveIntermediateUrl(url: String): String {
        if (isXCloudHlsUrl(url)) return url

        var current = url
        var attempts = 0

        while (attempts < 4) {
            attempts++

            try {
                val resp = app.get(
                    current,
                    headers = defaultHeaders,
                    allowRedirects = false
                )

                val loc = resp.headers["location"]
                    ?: resp.headers["Location"]

                if (!loc.isNullOrEmpty()) {
                    val next = resolveRelativeUrl(current, loc)

                    if (
                        !next.contains("ardance.org") &&
                        !next.contains("fifa2026") &&
                        next != current
                    ) {
                        current = next
                        if (isXCloudHlsUrl(current)) return current
                        continue
                    }
                }

                val html = resp.text

                if (html.contains("Can't access link ? Stuck into ads ?")) {
                    break
                }

                val metaMatch = Regex(
                    """content\s*=\s*["'][0-9]+;\s*url=([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(html)

                if (metaMatch != null) {
                    val nextUrl = resolveRelativeUrl(
                        current,
                        metaMatch.groupValues[1]
                    )

                    if (
                        nextUrl != current &&
                        !nextUrl.contains("ardance.org") &&
                        !nextUrl.contains("fifa2026")
                    ) {
                        current = nextUrl
                        if (isXCloudHlsUrl(current)) return current
                        continue
                    }
                }

                val jsMatch = Regex(
                    """(?:window\.location|location\.href|location\.replace)\s*=\s*["'](https?://[^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).findAll(html)
                    .map { it.groupValues[1] }
                    .firstOrNull {
                        !it.contains("ardance.org") &&
                            !it.contains("fifa2026")
                    }

                if (jsMatch != null && jsMatch != current) {
                    current = resolveRelativeUrl(current, jsMatch)
                    if (isXCloudHlsUrl(current)) return current
                    continue
                }

                break
            } catch (_: Exception) {
                break
            }
        }

        return current
    }

    // ---------------------------------------------------------
    // EPISODE HELPERS
    // ---------------------------------------------------------

    private fun findEpisodeLabel(
        el: Element,
        fallbackIndex: Int
    ): String {
        val epRegex = Regex(
            "(?i)(?:episode|ep)\\s*0*(\\d+)"
        )

        var curr: Element? = el
        var steps = 0

        while (curr != null && steps < 4) {
            steps++

            var prev = curr.previousElementSibling()

            while (prev != null) {
                val t = prev.text().trim()
                if (epRegex.containsMatchIn(t)) return t
                prev = prev.previousElementSibling()
            }

            val own = curr.ownText().trim()

            if (epRegex.containsMatchIn(own)) return own

            curr = curr.parent()
        }

        val text = el.text().trim()

        if (epRegex.containsMatchIn(text)) return text

        return "Episode $fallbackIndex"
    }

    private suspend fun scrapeEpisodeLinksFromIntermediate(
        pageUrl: String,
        refererUrl: String
    ): List<Pair<Int, String>> {
        val rawItems = mutableListOf<Pair<Int, String>>()
        val seenUrls = mutableSetOf<String>()

        try {
            val resolvedStart = resolveIntermediateUrl(pageUrl)

            val resp = app.get(
                resolvedStart,
                headers = defaultHeaders,
                referer = refererUrl
            )

            val html = resp.text
            val doc = resp.document

            var autoIdx = 1

            doc.select(
                "a[href], [onclick], [data-href], [data-url], div.EpFile"
            ).forEach { el ->

                val rawHref = when {
                    el.hasAttr("href") ->
                        el.absUrl("href")

                    el.hasAttr("data-href") ->
                        el.absUrl("data-href")

                    el.hasAttr("data-url") ->
                        el.absUrl("data-url")

                    el.hasAttr("onclick") ->
                        Regex("""["'](https?://[^"']+)["']""")
                            .find(el.attr("onclick"))
                            ?.groupValues?.get(1)
                            .orEmpty()

                    el.parent()?.hasAttr("href") == true ->
                        el.parent()!!.absUrl("href")

                    el.parent()?.hasAttr("onclick") == true ->
                        Regex("""["'](https?://[^"']+)["']""")
                            .find(el.parent()!!.attr("onclick"))
                            ?.groupValues?.get(1)
                            .orEmpty()

                    else -> ""
                }

                if (
                    rawHref.isNotEmpty() &&
                    !isIgnoredOrAdUrl(rawHref) &&
                    seenUrls.add(rawHref)
                ) {
                    val label = findEpisodeLabel(el, autoIdx)

                    val epNum = Regex(
                        "(?i)(?:episode|ep|e)\\s*0*(\\d+)"
                    ).find(label)
                        ?.groupValues?.get(1)
                        ?.toIntOrNull()
                        ?: autoIdx

                    rawItems.add(epNum to rawHref)
                    autoIdx++
                }
            }

            val hostRegex = Regex(
                """https?://(?:[a-zA-Z0-9-]+\.)*(?:xcloud\.[a-z]{2,}|xcloud\d*\.[a-z]{2,}|pixeldrain\.[a-z]{2,}|drive\.google\.com|docs\.google\.com|gdflix\.[a-z]{2,}|gdtot\.[a-z]{2,}|filepress\.[a-z]{2,}|hubcloud\.[a-z]{2,}|hubdrive\.[a-z]{2,}|workers\.dev)/[^\s"'<>]+""",
                RegexOption.IGNORE_CASE
            )

            hostRegex.findAll(html).forEach { match ->
                var href = match.value
                    .replace("\\/", "/")
                    .trim()
                    .trimEnd(',', ';', ')', ']', '}')

                if (href.contains("refer=")) {
                    href = href.substringAfter("refer=")
                        .substringBefore("&")

                    try {
                        href = URLDecoder.decode(href, "UTF-8")
                    } catch (_: Exception) {
                    }
                }

                if (
                    !isIgnoredOrAdUrl(href) &&
                    seenUrls.add(href)
                ) {
                    rawItems.add(autoIdx to href)
                    autoIdx++
                }
            }
        } catch (_: Exception) {
        }

        return rawItems
    }

    // ---------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = defaultHeaders,
            cookies = cookies
        ).document

        val title = document.selectFirst(
            "h1.entry-title, h1.post-title"
        )?.text()?.trim() ?: "Drama"

        val poster = document.selectFirst(
            "a.post-thumb img, .post-thumb img, .entry-content img"
        )?.let {
            it.absUrl("data-src").ifEmpty {
                it.absUrl("src")
            }
        }

        val description = document.selectFirst(
            ".entry-content p"
        )?.text()

        val episodeMap = linkedMapOf<Int, MutableList<String>>()

        val btnSlides = document.select(
            "a.btn-slide, " +
                ".entry-content a[href*='sohojgyan'], " +
                ".entry-content a[href*='blogspot'], " +
                ".entry-content a[href*='xcloud'], " +
                ".entry-content a[href*='pixeldrain'], " +
                ".entry-content a[href*='drive.google.com'], " +
                ".entry-content a[href*='gdflix'], " +
                ".entry-content a[href*='hubcloud']"
        )

        for (slide in btnSlides) {
            val slideUrl = slide.absUrl("href").ifEmpty {
                continue
            }

            val slideTitle = slide.selectFirst(".btn-title")
                ?.text()
                ?.trim()
                ?: slide.text().trim().ifEmpty {
                    "Episode"
                }

            if (
                slideUrl.contains("sohojgyan") ||
                slideUrl.contains("blogspot") ||
                slideTitle.contains("Episode Wise", ignoreCase = true)
            ) {
                val scraped = scrapeEpisodeLinksFromIntermediate(
                    slideUrl,
                    url
                )

                if (scraped.isNotEmpty()) {
                    for ((epNum, epHref) in scraped) {
                        episodeMap.getOrPut(epNum) {
                            mutableListOf()
                        }.add(epHref)
                    }
                } else {
                    val nextEp = (episodeMap.keys.maxOrNull() ?: 0) + 1

                    episodeMap.getOrPut(nextEp) {
                        mutableListOf()
                    }.add(slideUrl)
                }
            } else if (!isIgnoredOrAdUrl(slideUrl)) {
                val epNum = Regex(
                    "(?i)(?:episode|ep|e)\\s*0*(\\d+)"
                ).find(slideTitle)
                    ?.groupValues?.get(1)
                    ?.toIntOrNull()
                    ?: ((episodeMap.keys.maxOrNull() ?: 0) + 1)

                episodeMap.getOrPut(epNum) {
                    mutableListOf()
                }.add(slideUrl)
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
            if (
                isIgnoredOrAdUrl(itemUrl) &&
                !itemUrl.contains("sohojgyan") &&
                !itemUrl.contains("blogspot")
            ) {
                continue
            }

            val currentUrl = if (isXCloudHlsUrl(itemUrl)) {
                itemUrl
            } else {
                resolveIntermediateUrl(itemUrl)
            }

            if (
                currentUrl.contains("sohojgyan") ||
                currentUrl.contains("blogspot")
            ) {
                val scraped = scrapeEpisodeLinksFromIntermediate(
                    currentUrl,
                    "$mainUrl/"
                )

                for ((_, link) in scraped) {
                    processSingleHostLink(
                        link,
                        currentUrl,
                        subtitleCallback,
                        callback
                    )
                }

                continue
            }

            processSingleHostLink(
                currentUrl,
                "$mainUrl/",
                subtitleCallback,
                callback
            )
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

        if (isXCloudHlsUrl(rawUrl)) {
            extractXCloudHls(rawUrl, callback)
            return
        }

        val url = resolveIntermediateUrl(rawUrl)

        if (isXCloudHlsUrl(url)) {
            extractXCloudHls(url, callback)
            return
        }

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
                val handled = loadExtractor(
                    url,
                    referer,
                    subtitleCallback,
                    callback
                )

                if (!handled) {
                    try {
                        val doc = app.get(
                            url,
                            headers = defaultHeaders,
                            referer = referer
                        ).document

                        doc.select("a[href]").forEach { a ->
                            val href = a.absUrl("href")

                            if (isIgnoredOrAdUrl(href)) {
                                return@forEach
                            }

                            when {
                                isXCloudHlsUrl(href) -> {
                                    extractXCloudHls(href, callback)
                                }

                                href.contains("xcloud", ignoreCase = true) -> {
                                    extractXCloud(href, subtitleCallback, callback)
                                }

                                href.contains("pixeldrain", ignoreCase = true) -> {
                                    extractPixeldrain(href, callback)
                                }

                                href.contains("drive.google.com") ||
                                    href.contains("drive.usercontent.google.com") -> {
                                    extractGDrive(href, subtitleCallback, callback)
                                }

                                href.contains("googleusercontent.com") ||
                                    href.contains("r2.dev") -> {
                                    callback.invoke(
                                        newExtractorLink(
                                            "Direct",
                                            "Direct Stream",
                                            href,
                                            ExtractorLinkType.VIDEO
                                        )
                                    )
                                }

                                href.contains("workers.dev", ignoreCase = true) -> {
                                    if (isXCloudHlsUrl(href)) {
                                        extractXCloudHls(href, callback)
                                    }
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
    // DIRECT XCLOUD HLS EXTRACTOR
    // ---------------------------------------------------------

    private suspend fun extractXCloudHls(
        hlsUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                "Referer" to "https://xcloud.tattoo/"
            )

            val response = app.get(
                hlsUrl,
                headers = headers
            )

            if (!response.text.trimStart().startsWith("#EXTM3U")) {
                return
            }

            callback(
                newExtractorLink(
                    source = "XCloud",
                    name = "XCloud Direct HLS",
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://xcloud.tattoo/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------
    // PIXELDRAIN
    // ---------------------------------------------------------

    private suspend fun extractPixeldrain(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex(
            """/u/([a-zA-Z0-9]+)"""
        ).find(url)?.groupValues?.get(1)
            ?: Regex(
                """/api/file/([a-zA-Z0-9]+)"""
            ).find(url)?.groupValues?.get(1)
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

    // ---------------------------------------------------------
    // GOOGLE DRIVE
    // ---------------------------------------------------------

    private suspend fun extractGDrive(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val fileId = Regex(
            """(?:/d/|id=)([a-zA-Z0-9_-]{15,})"""
        ).find(url)?.groupValues?.get(1)

        if (!fileId.isNullOrEmpty()) {
            val directConfirmUrl =
                "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"

            try {
                val resp = app.get(
                    directConfirmUrl,
                    headers = defaultHeaders,
                    allowRedirects = false
                )

                val loc = resp.headers["location"]
                    ?: resp.headers["Location"]

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
                    val formAction = resp.document
                        .selectFirst("form#download-form")
                        ?.attr("action")

                    if (!formAction.isNullOrEmpty()) {
                        val params = resp.document
                            .select("form#download-form input[name]")
                            .joinToString("&") {
                                "${it.attr("name")}=${it.attr("value")}"
                            }

                        val confirmedStreamUrl =
                            if (params.isNotEmpty()) {
                                "$formAction?$params"
                            } else {
                                formAction
                            }

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

        loadExtractor(
            url,
            "https://drive.google.com/",
            subtitleCallback,
            callback
        )

        return found
    }

    // ---------------------------------------------------------
    // XCLOUD TATTOO EXTRACTOR
    // ---------------------------------------------------------

    private suspend fun extractXCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        try {
            val resolvedUrl = resolveIntermediateUrl(url)
            val resp = app.get(
                resolvedUrl,
                headers = defaultHeaders,
                referer = "https://xcloud.tattoo/"
            )

            val html = resp.text

            Regex(
                """\{[^{}]*"lang"\s*:\s*"([^"]+)"[^{}]*"tgid"\s*:\s*(\d+)[^{}]*\}"""
            ).findAll(html).forEach { m ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        m.groupValues[1],
                        "https://sub.xcloud.icu/${m.groupValues[2]}"
                    )
                )
            }

            val workerRegex = Regex(
                """https?://[a-zA-Z0-9.-]+\.workers\.dev/(?:hls|m3u8)/[^\s"'<>]+""",
                RegexOption.IGNORE_CASE
            )

            workerRegex.findAll(html).forEach { match ->
                val workerUrl = match.value
                    .replace("\\/", "/")
                    .trim()
                    .trimEnd(',', ';', ')', ']', '}')

                if (isXCloudHlsUrl(workerUrl)) {
                    extractXCloudHls(workerUrl, callback)
                    found = true
                }
            }
        } catch (_: Exception) {
        }

        return found
    }
}
