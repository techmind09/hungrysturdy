package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CinevoodProvider : MainAPI() {
    override var mainUrl = "https://one.1cinevood.baby"
    override var name = "CineVood"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Movies & Series",
        "$mainUrl/category/dual-audio-movies/page/" to "Hindi / Dual Audio Movies",
        "$mainUrl/category/bollywood-movies/page/" to "Bollywood Movies",
        "$mainUrl/category/amazon-prime-video/page/" to "Amazon Prime Video",
        "$mainUrl/category/netflix/page/" to "Netflix",
        "$mainUrl/category/jiohotstar/page/" to "JioHotstar",
        "$mainUrl/category/korean-series/page/" to "K-Drama Series",
        "$mainUrl/category/anime-series/page/" to "Anime Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(url).document
        val home = document.select("div.post-cards article.latestpost").mapNotNull {
            it.toSearchResult()
        }
        val hasNext = document.selectFirst("div.navigation li.next a") != null
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a") ?: return null
        val rawTitle = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrlNull(
            this.selectFirst("div.featured-thumbnail img")?.attr("src")
        )

        val cleanTitle = rawTitle
            .replace(Regex("""^Download\s+""", RegexOption.IGNORE_CASE), "")
            .substringBefore(" (20")
            .substringBefore(" (19")
            .substringBefore(" (Season")
            .substringBefore(" Season")
            .trim()
            .ifEmpty { rawTitle }

        val quality = getQualityFromString(rawTitle)
        val isSeries = rawTitle.contains(
            Regex("""(Season\s*\d+|S\d{1,2}|Series|Tv-Show|Talk Show|E\d+)""", RegexOption.IGNORE_CASE)
        ) || this.hasClass("category-dual-audio-series") ||
            this.hasClass("category-tv-shows") ||
            this.hasClass("category-korean-series") ||
            this.hasClass("category-anime-series")

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        return document.select("div.post-cards article.latestpost").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun isIgnoredLink(href: String): Boolean {
        val lower = href.lowercase()
        return href.isBlank() ||
            !href.startsWith("http") ||
            lower.contains("whatsapp.com") ||
            lower.contains("t.me/") ||
            lower.contains("telegram") ||
            lower.contains("imdb.com") ||
            lower.contains("instagram.com") ||
            lower.contains("youtube.com") ||
            lower.contains("youtu.be") ||
            lower.contains("#")
    }

    private fun decodeEncodedEpisodeHtml(rawHtml: String): String {
        val match = Regex("""const\s+encoded\s*=\s*["']([A-Za-z0-9+/=\s]+)["']""").find(rawHtml)
        if (match != null) {
            try {
                val cleanB64 = match.groupValues[1].replace(Regex("""\s+"""), "")
                val decoded = String(Base64.decode(cleanB64, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.contains("<a") || decoded.contains("href")) {
                    return "$rawHtml\n$decoded"
                }
            } catch (_: Exception) {
            }
        }
        return rawHtml
    }

    private fun extractEpisodesFromDocument(
        doc: Document,
        defaultSeason: Int,
        qualityHint: String
    ): List<Triple<Int, String, List<Pair<String, String>>>> {
        // Returns list of Triple(episodeNumber, episodeTitle, listOf(label to href))
        val epHeadingRegex = Regex("""(?:Episodes?|Ep|E)\s*[:\-]?\s*0*(\d+)""", RegexOption.IGNORE_CASE)
        val result = mutableListOf<Triple<Int, String, List<Pair<String, String>>>>()

        // Structure like mobilejsr: <h4>-:Episodes: 01:-</h4> followed by <p><a href="...">...</a></p>
        val headings = doc.select("h1, h2, h3, h4, h5, h6, strong, b").filter { el ->
            epHeadingRegex.containsMatchIn(el.text())
        }

        if (headings.isNotEmpty()) {
            headings.forEach { heading ->
                val hText = heading.text().replace(Regex("""^[\-:\s]+|[\-:\s]+$"""), "").trim()
                val epNum = epHeadingRegex.find(hText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach

                val links = mutableListOf<Pair<String, String>>()
                var sibling: Element? = heading.nextElementSibling()
                var steps = 0
                while (sibling != null && steps < 8) {
                    if (sibling.tagName().matches(Regex("""h[1-6]""")) && epHeadingRegex.containsMatchIn(sibling.text())) {
                        break
                    }
                    sibling.select("a[href]").forEach { a ->
                        val href = fixUrlNull(a.attr("href"))
                        if (href != null && !isIgnoredLink(href)) {
                            val btnText = a.text().replace(Regex("""\s+"""), " ").trim()
                                .ifBlank { "Server" }
                            val fullLabel = if (qualityHint.isNotBlank()) "$qualityHint - $btnText" else btnText
                            links.add(fullLabel to href)
                        }
                    }
                    if (sibling.tagName() == "a" && sibling.hasAttr("href")) {
                        val href = fixUrlNull(sibling.attr("href"))
                        if (href != null && !isIgnoredLink(href)) {
                            val btnText = sibling.text().replace(Regex("""\s+"""), " ").trim()
                                .ifBlank { "Server" }
                            val fullLabel = if (qualityHint.isNotBlank()) "$qualityHint - $btnText" else btnText
                            links.add(fullLabel to href)
                        }
                    }
                    sibling = sibling.nextElementSibling()
                    steps++
                }

                if (links.isNotEmpty()) {
                    val cleanHeading = hText.ifBlank { "Episode $epNum" }
                    result.add(Triple(epNum, cleanHeading, links.distinctBy { it.second }))
                }
            }
        }

        return result
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst(".mfx-main-title")?.text()?.trim()
            ?: return null

        val infoMap = mutableMapOf<String, String>()
        document.select("div.mfx-info-box li").forEach { li ->
            val label = li.selectFirst("strong")?.text()?.replace(":", "")?.trim()?.lowercase()
            if (!label.isNullOrBlank()) {
                val value = li.text().substringAfter(":").trim()
                infoMap[label] = value
            }
        }

        val title = infoMap["movie name"]?.takeIf { it.isNotBlank() }
            ?: rawTitle
                .replace(Regex("""^Download\s+""", RegexOption.IGNORE_CASE), "")
                .substringBefore(" (20")
                .substringBefore(" (19")
                .substringBefore(" (Season")
                .trim()
                .ifEmpty { rawTitle }

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.single_post img")?.attr("src")
        )

        val plot = document.selectFirst("div.mfx-plot-box")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val year = infoMap["release year"]?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val duration = infoMap["runtime"]?.let {
            Regex("""(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        val rating = document.selectFirst("div.mfx-imdb")?.text()
            ?.let { Regex("""(\d+\.\d+)""").find(it)?.groupValues?.getOrNull(1) }

        val tags = infoMap["genres"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: document.select("a[rel=tag]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        val actors = infoMap["cast"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val trailer = document.selectFirst("div.mfx-yt-lazy")?.let { el ->
            val ytId = el.attr("data-yt-id").ifBlank { el.attr("data-id") }
            if (ytId.isNotBlank()) "https://www.youtube.com/watch?v=$ytId" else null
        } ?: document.selectFirst("iframe[src*=youtube]")?.attr("src")

        val recommendations = document.select("div.related-posts article.latestpost").mapNotNull {
            it.toSearchResult()
        }

        val downloadGroups = document.select("div.mfx-download-group")
        val fallbackAnchors = document.select("div.entry-content a[href], div.single_post a[href]")
            .mapNotNull { a ->
                val link = fixUrlNull(a.attr("href"))
                if (link == null || isIgnoredLink(link) || link.contains(mainUrl, true)) null
                else Pair(a.text().trim(), link)
            }
            .distinctBy { it.second }

        val isSeries = rawTitle.contains(
            Regex("""(Season\s*\d+|S\d{1,2}|Series|Tv-Show|Episode)""", RegexOption.IGNORE_CASE)
        ) || downloadGroups.any { grp ->
            grp.select("a[href]").any {
                Regex("""(?:Ep|Episode|E)\s*0?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(it.text())
            }
        }

        if (isSeries) {
            val seasonRegex = Regex("""(?:Season|S)\s*0?(\d+)""", RegexOption.IGNORE_CASE)
            val epRegex = Regex("""(?:Ep|Episode|E)\s*0?(\d+)""", RegexOption.IGNORE_CASE)

            // Map: season -> episodeNumber -> mutableListOf(label to href)
            val groupedEpisodes = LinkedHashMap<Int, LinkedHashMap<Int, MutableList<Pair<String, String>>>>()
            val qualityFallbackEpisodes = mutableListOf<Episode>()

            fun addEpisodeLinks(
                season: Int,
                epNum: Int,
                links: List<Pair<String, String>>
            ) {
                val epMap = groupedEpisodes.getOrPut(season) { LinkedHashMap() }
                val targetList = epMap.getOrPut(epNum) { mutableListOf() }
                links.forEach { pair ->
                    if (targetList.none { it.second == pair.second }) {
                        targetList.add(pair)
                    }
                }
            }

            val sourceGroups = if (downloadGroups.isNotEmpty()) {
                downloadGroups.mapIndexed { idx, group ->
                    val gTitle = group.selectFirst(".mfx-quality-title")?.text()?.trim()
                        ?: "Group ${idx + 1}"
                    val gSeason = seasonRegex.find("$gTitle $rawTitle")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    val gLinks = group.select("a.mfx-download-link[href], a[href]")
                        .mapNotNull { a ->
                            val link = fixUrlNull(a.attr("href"))
                            if (link == null || isIgnoredLink(link) || link.contains(mainUrl, true)) null
                            else Pair(a.text().replace(Regex("""\s+"""), " ").trim().ifBlank { gTitle }, link)
                        }
                        .distinctBy { it.second }
                    Triple(gSeason, gTitle, gLinks)
                }
            } else if (fallbackAnchors.isNotEmpty()) {
                val sNum = seasonRegex.find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                listOf(Triple(sNum, title, fallbackAnchors))
            } else {
                emptyList()
            }

            sourceGroups.forEachIndexed { idx, (gSeason, gTitle, gLinks) ->
                if (gLinks.isEmpty()) return@forEachIndexed

                // Check if this group's links are intermediate pages (like mobilejsr.best) containing individual episodes
                val intermediateLinks = gLinks.filter { (_, href) ->
                    href.contains("mobilejsr", true) ||
                        href.contains("genxfm", true) ||
                        href.contains("howblogs", true) ||
                        href.contains("drivehub", true) ||
                        href.contains("hubdrive", true)
                }

                var expandedFromIntermediate = false
                if (intermediateLinks.isNotEmpty()) {
                    intermediateLinks.forEach { (_, interUrl) ->
                        try {
                            val res = app.get(interUrl, referer = url)
                            val expandedHtml = decodeEncodedEpisodeHtml(res.text)
                            val parsedDoc = Jsoup.parse(expandedHtml, res.url.ifBlank { interUrl })
                            val extractedEps = extractEpisodesFromDocument(parsedDoc, gSeason, gTitle)
                            if (extractedEps.isNotEmpty()) {
                                expandedFromIntermediate = true
                                extractedEps.forEach { (epNum, _, epLinks) ->
                                    addEpisodeLinks(gSeason, epNum, epLinks)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                if (!expandedFromIntermediate) {
                    val numbered = gLinks.mapNotNull { (btnLabel, href) ->
                        val epNum = epRegex.find("$btnLabel $gTitle")?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (epNum != null) Triple(epNum, btnLabel, href) else null
                    }

                    if (numbered.size > 1) {
                        numbered.groupBy { it.first }.forEach { (epNum, items) ->
                            addEpisodeLinks(
                                gSeason,
                                epNum,
                                items.map { "${gTitle} - ${it.second}" to it.third }
                            )
                        }
                    } else {
                        // Keep quality/batch entry (e.g. 480p / 720p / 1080p)
                        val epNum = epRegex.find(gTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (idx + 1)
                        val payload = gLinks.joinToString("|") { "$gTitle - ${it.first}###${it.second}" }
                        qualityFallbackEpisodes.add(
                            newEpisode(payload) {
                                this.name = gTitle
                                this.season = gSeason
                                this.episode = epNum
                            }
                        )
                    }
                }
            }

            val episodes = mutableListOf<Episode>()
            groupedEpisodes.forEach { (seasonNum, epMap) ->
                epMap.toSortedMap().forEach { (epNum, pairs) ->
                    val payload = pairs.joinToString("|") { "${it.first}###${it.second}" }
                    episodes.add(
                        newEpisode(payload) {
                            this.name = "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                episodes.addAll(qualityFallbackEpisodes)
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addScore(rating)
                addTrailer(trailer)
            }
        } else {
            val allLinks = mutableListOf<String>()
            downloadGroups.forEach { group ->
                val qualityLabel = group.selectFirst(".mfx-quality-title")?.text()?.trim() ?: ""
                group.select("a.mfx-download-link[href], a[href]").forEach { aTag ->
                    val link = fixUrlNull(aTag.attr("href"))
                    if (link != null && !isIgnoredLink(link) && !link.contains(mainUrl, true)) {
                        val label = qualityLabel.ifBlank { aTag.text().trim() }
                        allLinks.add("$label###$link")
                    }
                }
            }

            if (allLinks.isEmpty()) {
                fallbackAnchors.forEach { (btnText, link) ->
                    allLinks.add("$btnText###$link")
                }
            }

            return newMovieLoadResponse(title, url, TvType.Movie, allLinks.distinct().joinToString("|")) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addScore(rating)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val entries = data.split("|").filter { it.isNotBlank() }

        entries.forEach { entry ->
            val parts = entry.split("###")
            val qualityLabel = if (parts.size > 1) parts[0] else ""
            val rawLink = if (parts.size > 1) parts[1] else parts[0]

            resolveLink(rawLink, qualityLabel, mainUrl, subtitleCallback, callback, depth = 0)
        }
        return true
    }

    private suspend fun resolveLink(
        url: String,
        qualityLabel: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ) {
        if (depth > 4 || url.isBlank()) return

        // 0. MirrorServerExtractor (mCloud, VegaDrive, DOTFLIX)
        if (url.contains("mcloud", ignoreCase = true) ||
            url.contains("vegadrive", ignoreCase = true) ||
            url.contains("dotflix", ignoreCase = true)
        ) {
            val mirrorExtractor = MirrorServerExtractor(mainUrl)
            mirrorExtractor.extractAllServers(
                url = url,
                referer = referer,
                callback = callback
            )
            return
        }

        // 1. Direct video file / Googleusercontent stream check
        if (url.contains("video-downloads.googleusercontent.com") ||
            url.matches(Regex(""".*\.(mkv|mp4|m3u8)(\?.*)?$""", RegexOption.IGNORE_CASE))
        ) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (qualityLabel.isNotBlank()) "$name - $qualityLabel" else "$name Direct",
                    url = url
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(qualityLabel)
                }
            )
            return
        }

        // 2. Try built-in & custom CloudStream extractors
        if (loadExtractor(url, referer, subtitleCallback, callback)) return

        // 3. Fetch intermediate page (handles mobilejsr -> fastdl.rest / filebee.live / mcloud / vegadrive / dotflix)
        try {
            val res = app.get(url, referer = referer)
            val expandedHtml = decodeEncodedEpisodeHtml(res.text)
            val currentUrl = res.url.ifBlank { url }
            val doc = Jsoup.parse(expandedHtml, currentUrl)

            // If this page has episode blocks and we arrived at episode level, extract all download anchors inside it
            val hasEpisodeBlocks = doc.select("h1, h2, h3, h4, h5, h6, strong, b").any {
                Regex("""(?:Episodes?|Ep|E)\s*[:\-]?\s*0*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(it.text())
            }
            if (hasEpisodeBlocks) {
                val allEpLinks = doc.select("a[href]")
                    .mapNotNull { a ->
                        val href = fixUrlNull(a.attr("href"))
                        if (href == null || isIgnoredLink(href) || href == currentUrl) null
                        else Pair(a.text().replace(Regex("""\s+"""), " ").trim().ifBlank { "Mirror" }, href)
                    }
                    .distinctBy { it.second }

                if (allEpLinks.isNotEmpty()) {
                    allEpLinks.forEach { (btnLabel, targetHref) ->
                        val combinedLabel = if (qualityLabel.isNotBlank()) "$qualityLabel - $btnLabel" else btnLabel
                        resolveLink(targetHref, combinedLabel, currentUrl, subtitleCallback, callback, depth + 1)
                    }
                    return
                }
            }

            // Case A: FastDL Server page -> div.vd a#vd
            val fastDlAnchor = doc.selectFirst("div.vd a#vd[href], a#vd[href]")
            if (fastDlAnchor != null) {
                val directHref = fixUrlNull(fastDlAnchor.attr("href"))
                if (!directHref.isNullOrBlank() && directHref != currentUrl) {
                    val serverTitle = doc.title().ifBlank { "FastDL" }
                    callback.invoke(
                        newExtractorLink(
                            source = "FastDL",
                            name = listOfNotNull(
                                "FastDL",
                                qualityLabel.takeIf { it.isNotBlank() }
                            ).joinToString(" - "),
                            url = directHref
                        ) {
                            this.referer = currentUrl
                            this.quality = getQualityFromName(qualityLabel.ifBlank { serverTitle })
                        }
                    )
                    return
                }
            }

            // Case B: mCloud / HubCloud mirror page -> div.mirrors-container a[href]
            val mirrorLinks = doc.select("div.mirrors-container a[href]")
            if (mirrorLinks.isNotEmpty()) {
                val fileTitle = doc.selectFirst("div.card-header")?.text()?.trim() ?: qualityLabel
                val detectedQuality = getQualityFromName(fileTitle.ifBlank { qualityLabel })

                mirrorLinks.forEach { aTag ->
                    val btnLabel = aTag.text().trim()
                    val minuteBase = aTag.attr("data-minute-base-url")
                    val targetHref = if (minuteBase.isNotBlank()) {
                        val mins = java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE)
                        "${minuteBase}1$mins"
                    } else {
                        fixUrlNull(aTag.attr("href")) ?: ""
                    }

                    if (targetHref.isBlank() || targetHref == currentUrl) return@forEach

                    when {
                        targetHref.contains(".r2.dev") || btnLabel.contains("FSL", ignoreCase = true) -> {
                            callback.invoke(
                                newExtractorLink(
                                    source = "mCloud FSL",
                                    name = "mCloud [FSL Server] - $fileTitle",
                                    url = targetHref
                                ) {
                                    this.referer = currentUrl
                                    this.quality = detectedQuality
                                }
                            )
                        }
                        targetHref.contains("gpdl") || targetHref.contains("hubcloud") ||
                            btnLabel.contains("10Gbps", ignoreCase = true) -> {
                            resolveLink(
                                url = targetHref,
                                qualityLabel = fileTitle,
                                referer = currentUrl,
                                subtitleCallback = subtitleCallback,
                                callback = callback,
                                depth = depth + 1
                            )
                        }
                        else -> {
                            val normalizedUrl = targetHref.replace("pixeldrain.dev", "pixeldrain.com")
                            if (!loadExtractor(normalizedUrl, currentUrl, subtitleCallback, callback)) {
                                resolveLink(
                                    url = targetHref,
                                    qualityLabel = fileTitle,
                                    referer = currentUrl,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback,
                                    depth = depth + 1
                                )
                            }
                        }
                    }
                }
                return
            }

            // Case C: Intermediate shortener / gateway page
            val metaRedirect = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                ?.substringAfter("url=", "")?.trim()?.trim('\'', '"')
            val jsRedirect = Regex("""(?:window\.location(?:\.href)?|location\.replace)\s*(?:=|\()\s*['"](https?://[^'"]+)['"]""")
                .find(expandedHtml)?.groupValues?.getOrNull(1)
            val mirrorMatch = Regex("""https?://(?:mcloud|hubcloud|vegadrive|dotflix|fastdl)\.[a-z]+/[^\s"'<>]+""")
                .find(expandedHtml)?.value

            val nextHop = listOfNotNull(metaRedirect, jsRedirect, mirrorMatch)
                .firstOrNull { it.startsWith("http") && it != currentUrl }

            if (nextHop != null) {
                resolveLink(
                    url = nextHop,
                    qualityLabel = qualityLabel,
                    referer = currentUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                    depth = depth + 1
                )
                return
            }

            // Case D: Fallback to candidate anchors/iframes on gateway pages
            doc.select("a[href], iframe[src]").forEach { el ->
                val target = fixUrlNull(
                    if (el.tagName() == "iframe") el.attr("src") else el.attr("href")
                )
                if (!target.isNullOrBlank() && target != currentUrl && !isIgnoredLink(target)) {
                    if (!loadExtractor(target, currentUrl, subtitleCallback, callback)) {
                        resolveLink(
                            url = target,
                            qualityLabel = qualityLabel,
                            referer = currentUrl,
                            subtitleCallback = subtitleCallback,
                            callback = callback,
                            depth = depth + 1
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}

