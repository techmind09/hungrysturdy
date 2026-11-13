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

    private fun isValidVideoLink(href: String): Boolean {
        if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) return false
        val lower = href.lowercase()
        return !(lower.contains("t.me") || lower.contains("telegram") || lower.contains("whatsapp") ||
                lower.contains("privacy") || lower.contains("cookie") || lower.contains("/category/") ||
                lower.contains("/tag/") || lower.contains("?s=") || lower.contains("/page/") ||
                lower.contains("sohojgyan.com") || lower.contains("mydverse.com") ||
                lower.contains("blogspot.com") || lower.contains("blogger.com") ||
                lower.contains("google.com/search") || lower.contains("facebook.com") ||
                lower.contains("twitter.com") || lower.contains("instagram.com"))
    }

    private fun getServerName(btnText: String, href: String): String {
        val lowerText = btnText.lowercase()
        val lowerHref = href.lowercase()
        return when {
            lowerHref.contains("drive.google.com") || lowerHref.contains("docs.google.com") || lowerText.contains("gdrive") || lowerText.contains("drive") -> "GDrive"
            lowerHref.contains("xcloud") || lowerText.contains("xcloud") -> "XCloud"
            lowerHref.contains("filego") || lowerHref.contains("fdownload") || lowerText.contains("filego") -> "FileGo"
            lowerHref.contains("hubcloud") || lowerText.contains("hubcloud") -> "HubCloud"
            lowerHref.contains("gdflix") || lowerText.contains("gdflix") -> "GDFlix"
            lowerHref.contains("pixeldrain") || lowerText.contains("pixel") -> "PixelDrain"
            else -> btnText.trim().takeIf { it.isNotEmpty() && it.length <= 15 } ?: "Server"
        }
    }

    private data class ParsedPageResult(
        val episodes: Map<Int, List<Pair<String, String>>>,
        val zipPacks: List<Triple<String, String, String>> // Title, ServerName, URL
    )

    // Sequential Top-to-Bottom DOM Scanner for Blogger / Intermediate pages
    private suspend fun scrapeEpisodeLinksFromIntermediate(pageUrl: String, refererUrl: String): ParsedPageResult {
        val episodeMap = mutableMapOf<Int, MutableList<Pair<String, String>>>()
        val zipList = mutableListOf<Triple<String, String, String>>()
        val visitedLinks = mutableSetOf<String>()

        try {
            val resp = app.get(pageUrl, headers = defaultHeaders, referer = refererUrl)
            val doc = resp.document
            val contentRoot = doc.selectFirst(".post-body, .entry-content, article, main") ?: doc.body()

            var currentEp: Int? = null
            var inZipSection = false
            var currentZipLabel = "All Episodes (ZIP)"

            // Regex patterns
            val rangeRegex = Regex("""(?i)(?:episode|ep|e)\s*0*(\d+)\s*[-– to]+\s*0*(\d+)""")
            val singleEpRegex = Regex("""(?i)\b(?:episode|ep|e)\s*0*(\d+)\b""")
            val zipKeywordRegex = Regex("""(?i)(?:\bzip\b|all\s*episodes?|complete\s*pack|batch|\d+(?:\.\d+)?\s*gb)""")

            // Traverse every element in document order
            for (el in contentRoot.select("*")) {
                val ownText = el.ownText().trim()

                // 1. Check headings/labels (only on elements that aren't huge wrappers)
                if (ownText.isNotEmpty() && ownText.length < 100 && el.tagName() != "a") {
                    val rangeMatch = rangeRegex.find(ownText)
                    val singleMatch = singleEpRegex.find(ownText)

                    when {
                        rangeMatch != null -> {
                            inZipSection = true
                            currentZipLabel = ownText.replace(Regex("""[-–=]{2,}"""), "").trim()
                        }
                        zipKeywordRegex.containsMatchIn(ownText) && singleMatch == null -> {
                            inZipSection = true
                            val cleaned = ownText.replace(Regex("""[-–=]{2,}"""), "").trim()
                            if (cleaned.isNotEmpty() && !cleaned.equals("ZIP FILE", ignoreCase = true)) {
                                currentZipLabel = cleaned
                            }
                        }
                        singleMatch != null -> {
                            val epNum = singleMatch.groupValues[1].toIntOrNull()
                            if (epNum != null && epNum in 1..500) {
                                currentEp = epNum
                                inZipSection = false
                            }
                        }
                    }
                }

                // 2. Check if current element is a valid video/download link
                if (el.tagName() == "a") {
                    val href = el.absUrl("href").trim()
                    if (!isValidVideoLink(href) || !visitedLinks.add(href)) continue

                    val btnText = el.text().trim()
                    if (btnText.contains("----------") && href.isEmpty()) continue

                    val serverName = getServerName(btnText, href)

                    // Check if the button text itself specifies a range or episode
                    val btnRangeMatch = rangeRegex.find(btnText)
                    val btnSingleMatch = singleEpRegex.find(btnText)
                    val btnIsZip = btnRangeMatch != null || (zipKeywordRegex.containsMatchIn(btnText) && btnSingleMatch == null)

                    if (inZipSection || btnIsZip) {
                        val zipTitle = when {
                            btnRangeMatch != null -> btnText
                            currentZipLabel.isNotBlank() -> currentZipLabel
                            else -> "All Episodes (ZIP)"
                        }
                        zipList.add(Triple(zipTitle, serverName, href))
                    } else {
                        val epNum = btnSingleMatch?.groupValues?.get(1)?.toIntOrNull()
                            ?: currentEp
                            ?: 1
                        episodeMap.getOrPut(epNum) { mutableListOf() }.add(serverName to href)
                    }
                }
            }
        } catch (_: Exception) {
        }

        return ParsedPageResult(episodeMap, zipList)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders, cookies = cookies).document
        val title = document.selectFirst("h1.entry-title, h1.post-title")?.text()?.trim() ?: "Drama"
        val poster = document.selectFirst("a.post-thumb img, .post-thumb img, .entry-content img")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }
        val description = document.selectFirst(".entry-content p")?.text()

        val allEpLinks = mutableMapOf<Int, MutableList<Pair<String, String>>>()
        val allZipPacks = mutableListOf<Triple<String, String, String>>()
        val scrapedSubPages = mutableSetOf<String>()

        // Find intermediate Blogger / Sohojgyan / Episode Wise buttons
        val candidateLinks = document.select("a.btn-slide, .entry-content a[href], .post-body a[href]")

        for (link in candidateLinks) {
            val href = link.absUrl("href").ifEmpty { continue }
            val text = link.text().trim()

            // Ignore pure separator lines without real links
            if (text.contains("----") && !isValidVideoLink(href)) continue

            val isIntermediatePage = href.contains("sohojgyan") ||
                    href.contains("blogspot.com") ||
                    href.contains("blogger.com") ||
                    text.contains("Episode Wise", ignoreCase = true)

            if (isIntermediatePage) {
                if (scrapedSubPages.add(href)) {
                    val parsed = scrapeEpisodeLinksFromIntermediate(href, url)
                    parsed.episodes.forEach { (epNum, list) ->
                        allEpLinks.getOrPut(epNum) { mutableListOf() }.addAll(list)
                    }
                    allZipPacks.addAll(parsed.zipPacks)
                }
            }
        }

        // If no intermediate page had episodes, scan the main page directly using the same state machine
        if (allEpLinks.isEmpty()) {
            val mainParsed = scrapeEpisodeLinksFromIntermediate(url, "$mainUrl/")
            mainParsed.episodes.forEach { (epNum, list) ->
                allEpLinks.getOrPut(epNum) { mutableListOf() }.addAll(list)
            }
            allZipPacks.addAll(mainParsed.zipPacks)
        }

        val episodes = mutableListOf<Episode>()

        // 1. Add all regular episodes in strict sequential order (1, 2, 3... N)
        val sortedEpNums = allEpLinks.keys.sorted()
        for (epNum in sortedEpNums) {
            val links = allEpLinks[epNum].orEmpty().distinctBy { it.second }
            if (links.isEmpty()) continue

            val serverNames = links.map { it.first }.distinct().joinToString(" • ")
            val serializedData = links.joinToString(";;;") { "${it.first}|${it.second}" }

            episodes.add(
                newEpisode(serializedData) {
                    this.name = "Episode $epNum [$serverNames]"
                    this.episode = epNum
                }
            )
        }

        // 2. Group and add ZIP / Batch packs at the very end
        if (allZipPacks.isNotEmpty()) {
            val groupedZips = allZipPacks.groupBy { it.first }
            var zipEpCounter = (sortedEpNums.maxOrNull() ?: episodes.size) + 1

            for ((zipTitle, items) in groupedZips) {
                val uniqueItems = items.distinctBy { it.third }
                val serverNames = uniqueItems.map { it.second }.distinct().joinToString(" • ")
                val serializedData = uniqueItems.joinToString(";;;") { "${it.second}|${it.third}" }

                episodes.add(
                    newEpisode(serializedData) {
                        this.name = "📦 $zipTitle [$serverNames]"
                        this.episode = zipEpCounter++
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
        val items = data.split(";;;").filter { it.isNotBlank() }
        if (items.isEmpty()) {
            return loadSingleLink(data, "Server", subtitleCallback, callback)
        }

        items.amap { item ->
            val parts = item.split("|", limit = 2)
            val serverName = if (parts.size == 2) parts[0] else "Server"
            val url = if (parts.size == 2) parts[1] else parts[0]

            loadSingleLink(url, serverName, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun loadSingleLink(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val quality = getQualityFromName(serverName)
        return when {
            url.contains("drive.google.com", ignoreCase = true) || url.contains("docs.google.com", ignoreCase = true) -> {
                extractGDrive(url, quality, callback)
                true
            }
            url.contains("xcloud", ignoreCase = true) -> {
                extractXCloud(url, subtitleCallback, callback)
            }
            else -> {
                loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                true
            }
        }
    }

    private suspend fun extractGDrive(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = Regex("""(?:file/d/|id=)([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
            if (!fileId.isNullOrBlank()) {
                // 1. Check Google Drive page title for 480p / 720p / 1080p quality
                val viewUrl = "https://drive.google.com/file/d/$fileId/view"
                val driveResp = app.get(viewUrl, headers = defaultHeaders)
                val pageTitle = driveResp.document.title()
                val detectedQuality = getQualityFromName(pageTitle).let {
                    if (it == Qualities.Unknown.value) quality else it
                }

                // 2. Fetch drive.usercontent.google.com direct download form if confirmation is required
                val ucUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                val ucResp = app.get(ucUrl, headers = defaultHeaders, referer = "https://drive.google.com/")
                val ucDoc = ucResp.document

                // Parse hidden form inputs if virus scan warning page appears
                val formAction = ucDoc.selectFirst("form#download-form")?.attr("action")
                val finalDirectUrl = if (!formAction.isNullOrBlank()) {
                    val params = ucDoc.select("form#download-form input[name]").mapNotNull { input ->
                        val name = input.attr("name")
                        val value = input.attr("value")
                        if (name.isNotBlank()) "$name=$value" else null
                    }.joinToString("&")
                    if (params.isNotBlank()) "$formAction?$params" else ucUrl
                } else {
                    ucUrl
                }

                callback.invoke(
                    newExtractorLink(
                        source = "GDrive",
                        name = "GDrive [Direct Server]",
                        url = finalDirectUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://drive.google.com/"
                        this.quality = detectedQuality
                    }
                )

                // Fallback direct export link
                callback.invoke(
                    newExtractorLink(
                        source = "GDrive",
                        name = "GDrive [Mirror]",
                        url = "https://drive.google.com/uc?export=download&id=$fileId&confirm=t",
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://drive.google.com/"
                        this.quality = detectedQuality
                    }
                )
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = "GDrive",
                        name = "GDrive",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://drive.google.com/"
                        this.quality = quality
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        try {
            val xCloudResponse = app.get(url, headers = defaultHeaders)
            val xCloudDoc = xCloudResponse.document
            val resolvedPageUrl = xCloudResponse.url.ifEmpty { url }
            val baseCookies = xCloudResponse.cookies

            val fileTitle = xCloudDoc.title()
            val detectedQuality = getQualityFromName(fileTitle)

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

                    Regex("""\{[^{}]*"lang"\s*:\s*"([^"]+)"[^{}]*"tgid"\s*:\s*(\d+)[^{}]*\}""")
                        .findAll(streamHtml)
                        .forEach { m ->
                            subtitleCallback.invoke(
                                SubtitleFile(m.groupValues[1], "https://sub.xcloud.icu/${m.groupValues[2]}")
                            )
                        }

                    val varPatterns = listOf(
                        "PRIMARY_URL" to ("XCloud [Server 1]" to ExtractorLinkType.VIDEO),
                        "WASABI_URL" to ("XCloud [Server 2]" to ExtractorLinkType.VIDEO),
                        "HLS_URL" to ("XCloud [HLS]" to ExtractorLinkType.M3U8)
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
                                        this.quality = detectedQuality
                                    }
                                )
                                found = true
                            }
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
