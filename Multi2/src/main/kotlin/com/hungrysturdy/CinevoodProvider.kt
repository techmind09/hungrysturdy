package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1.entry-title, .mfx-main-title")?.text()?.trim()
            ?: return null

        val title = rawTitle
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

        val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val rating = document.selectFirst("div.mfx-imdb")?.text()
            ?.let { Regex("""(\d+\.\d+)""").find(it)?.groupValues?.getOrNull(1) }

        val tags = document.select("div.mfx-info-box a, span.thecategory a, a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val trailer = document.selectFirst("div.mfx-yt-lazy")?.attr("data-id")
            ?.let { "https://www.youtube.com/watch?v=$it" }
            ?: document.selectFirst("iframe[src*=youtube]")?.attr("src")

        val recommendations = document.select("div.related-posts article.latestpost").mapNotNull {
            it.toSearchResult()
        }

        // Matches the .mfx-download-group cards shown in the post layout
        val downloadGroups = document.select("div.mfx-download-group")
        val isSeries = rawTitle.contains(
            Regex("""(Season\s*\d+|S\d{1,2}|Series|Tv-Show|Episode)""", RegexOption.IGNORE_CASE)
        )

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            downloadGroups.forEachIndexed { index, group ->
                val groupTitle = group.selectFirst(".mfx-quality-title")?.text()?.trim()
                    ?: "Link Group ${index + 1}"
                val links = group.select("a.mfx-download-link, a[href]")
                    .mapNotNull { fixUrlNull(it.attr("href")) }
                    .filter { it.startsWith("http") && !it.contains(mainUrl) }
                    .distinct()

                if (links.isNotEmpty()) {
                    val seasonNum = Regex("""(?:Season|S)\s*0?(\d+)""", RegexOption.IGNORE_CASE)
                        .find(groupTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    val epNum = Regex("""(?:Episode|Ep|E)\s*0?(\d+)""", RegexOption.IGNORE_CASE)
                        .find(groupTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (index + 1)

                    episodes.add(
                        newEpisode(links.joinToString("|")) {
                            this.name = groupTitle
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addScore(rating)
                addTrailer(trailer)
            }
        } else {
            val allLinks = mutableListOf<String>()
            downloadGroups.forEach { group ->
                val qualityLabel = group.selectFirst(".mfx-quality-title")?.text()?.trim() ?: ""
                group.select("a.mfx-download-link, a[href]").forEach { aTag ->
                    val link = fixUrlNull(aTag.attr("href"))
                    if (!link.isNullOrBlank() && link.startsWith("http") && !link.contains(mainUrl)) {
                        allLinks.add("$qualityLabel###$link")
                    }
                }
            }

            if (allLinks.isEmpty()) {
                document.select("div.entry-content a[href]").forEach { aTag ->
                    val link = fixUrlNull(aTag.attr("href"))
                    val btnText = aTag.text().trim()
                    if (!link.isNullOrBlank() && link.startsWith("http") &&
                        !link.contains(mainUrl) && !link.contains("whatsapp.com") &&
                        !link.contains("imdb.com")
                    ) {
                        allLinks.add("$btnText###$link")
                    }
                }
            }

            return newMovieLoadResponse(title, url, TvType.Movie, allLinks.distinct().joinToString("|")) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
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
        if (depth > 3 || url.isBlank()) return

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

        // 2. Try built-in CloudStream extractors (PixelDrain, HubCloud, etc.)
        if (loadExtractor(url, referer, subtitleCallback, callback)) return

        // 3. Parse intermediate host pages (mCloud & FastDL) from provided HTML samples
        try {
            val res = app.get(url, referer = referer)
            val doc = res.document
            val currentUrl = res.url

            // Case A: FastDL Server page (cinev.html) -> div.vd a#vd
            val fastDlAnchor = doc.selectFirst("div.vd a#vd[href], a#vd[href]")
            if (fastDlAnchor != null) {
                val directHref = fixUrlNull(fastDlAnchor.attr("href"))
                if (!directHref.isNullOrBlank() && directHref != url) {
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

            // Case B: mCloud / HubCloud mirror page (cine (1).html / cine.html / dom (1).html)
            val mirrorLinks = doc.select("div.mirrors-container a[href]")
            if (mirrorLinks.isNotEmpty()) {
                val fileTitle = doc.selectFirst("div.card-header")?.text()?.trim() ?: qualityLabel
                val detectedQuality = getQualityFromName(fileTitle.ifBlank { qualityLabel })

                mirrorLinks.forEach { aTag ->
                    val btnLabel = aTag.text().trim()
                    val minuteBase = aTag.attr("data-minute-base-url")
                    var targetHref = if (minuteBase.isNotBlank()) {
                        val mins = java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE)
                        "${minuteBase}1$mins"
                    } else {
                        fixUrlNull(aTag.attr("href")) ?: ""
                    }

                    if (targetHref.isBlank() || targetHref == url) return@forEach

                    when {
                        // FSL Server (Cloudflare R2 direct download)
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
                        // Server : 10Gbps (gpdl2.hubcloud.mov -> FastDL wrapper)
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
                        // PixelServer : 2 (pixeldrain.dev) or other mirrors
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

            // 4. Generic fallback for any other redirector/shortener page
            doc.select("a[href], iframe[src]").forEach { el ->
                val target = fixUrlNull(
                    if (el.tagName() == "iframe") el.attr("src") else el.attr("href")
                )
                if (!target.isNullOrBlank() && target != url && target.startsWith("http") &&
                    !target.contains("telegram") && !target.contains("t.me") &&
                    !target.contains("whatsapp")
                ) {
                    loadExtractor(target, currentUrl, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {
        }
    }
}
