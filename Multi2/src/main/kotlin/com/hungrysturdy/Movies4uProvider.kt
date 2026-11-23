package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Movies4uProvider : MainAPI() {
    override var mainUrl = "https://new8.movies4u.garden"
    override var name = "Movies4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/category/bollywood-movies/" to "Bollywood Movies",
        "$mainUrl/category/hollywood-movies/" to "Hollywood Movies",
        "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/category/south-hindi-movies/" to "South Hindi Movies",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/k-drama/" to "K-Drama",
        "$mainUrl/category/anime/" to "Anime / Animation"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data.trimEnd('/')
            "$base/page/$page/"
        }

        val document = app.get(targetUrl).document
        val items = document.select("main.site-main article.post")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${encodeQuery(query)}"
        val document = app.get(searchUrl).document
        return document.select("article.post").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val contentRoot = document.selectFirst(".single-service-content")

        val rawTitle = contentRoot?.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.removeSuffix(" - Movies4u")?.trim()
            .orEmpty()

        val cleanTitle = cleanReleaseTitle(rawTitle)

        val posterUrl = contentRoot?.selectFirst(".entry-meta .post-thumbnail img")?.attr("src")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

        val storylineHeading = contentRoot?.select("h2.movie-title")
            ?.firstOrNull { it.text().contains("Storyline", ignoreCase = true) }
        val plot = storylineHeading?.nextElementSibling()
            ?.takeIf { it.tagName() == "p" }
            ?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val infoText = contentRoot?.select("h2.movie-title")
            ?.firstOrNull { it.text().contains("Info", ignoreCase = true) }
            ?.nextElementSibling()
            ?.text()
            .orEmpty()

        val rating = Regex("""IMDb Rating[^0-9]*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(infoText)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

        val year = Regex("""Released Year[^0-9]*((?:19|20)\d{2})""", RegexOption.IGNORE_CASE)
            .find(infoText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.value?.toIntOrNull()

        val downloadEntries = mutableListOf<String>()
        val downloadDiv = contentRoot?.selectFirst(".download-links-div")
        if (downloadDiv != null) {
            var currentLabel = ""
            for (child in downloadDiv.children()) {
                when (child.tagName()) {
                    "h4" -> {
                        val labelText = child.text().trim()
                        if (labelText.isNotBlank()) {
                            currentLabel = labelText
                        }
                    }
                    else -> {
                        child.select("a.btn[href], a[href]").forEach { a ->
                            val href = fixUrl(a.attr("href").trim())
                            if (href.isNotBlank() && !href.startsWith("#")) {
                                val entry = if (currentLabel.isNotBlank()) {
                                    "$currentLabel::$href"
                                } else {
                                    href
                                }
                                if (entry !in downloadEntries) {
                                    downloadEntries.add(entry)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (downloadEntries.isEmpty()) {
            contentRoot?.select("a.btn[href]")?.forEach { a ->
                val href = fixUrl(a.attr("href").trim())
                if (href.isNotBlank() && !href.startsWith("#") && !href.contains("t.me/")) {
                    val entry = "Download::$href"
                    if (entry !in downloadEntries) downloadEntries.add(entry)
                }
            }
        }

        val dataPayload = downloadEntries.joinToString("|")
        val isSeries = rawTitle.contains(
            Regex("""\b(season|s\d+|ep\d+|episode|web\s*series|tv\s*show|k-drama)\b""", RegexOption.IGNORE_CASE)
        )

        return if (isSeries) {
            newTvSeriesLoadResponse(
                name = cleanTitle.ifBlank { rawTitle },
                url = url,
                type = if (rawTitle.contains("k-drama", ignoreCase = true)) TvType.AsianDrama else TvType.TvSeries,
                episodes = listOf(
                    newEpisode(dataPayload) {
                        name = cleanTitle.ifBlank { "Season 1" }
                        season = 1
                        episode = 1
                    }
                )
            ) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(
                name = cleanTitle.ifBlank { rawTitle },
                url = url,
                type = TvType.Movie,
                dataUrl = dataPayload
            ) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val entries = data.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return false

        for (entry in entries) {
            val parts = entry.split("::", limit = 2)
            val baseLabel = if (parts.size == 2) parts[0].trim() else name
            val rawUrl = if (parts.size == 2) parts[1].trim() else parts[0].trim()
            if (rawUrl.isBlank()) continue

            if (rawUrl.contains("m4ulinks", ignoreCase = true)) {
                extractFromM4uLinksPage(rawUrl, baseLabel, subtitleCallback, callback)
            } else {
                processFinalHostUrl(rawUrl, baseLabel, rawUrl, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractFromM4uLinksPage(
        m4uUrl: String,
        parentLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(m4uUrl, referer = mainUrl).document
        } catch (_: Exception) {
            return
        }

        val linkDiv = doc.selectFirst(".download-links-div") ?: doc
        var currentQualityLabel = parentLabel

        for (child in linkDiv.children()) {
            when (child.tagName()) {
                "h4" -> {
                    val text = child.text().trim()
                    if (text.isNotBlank()) {
                        currentQualityLabel = text
                    }
                }
                else -> {
                    child.select("a[href]").forEach { a ->
                        val href = a.attr("href").trim()
                        if (href.isBlank() || href.startsWith("#")) return@forEach

                        val hostName = a.text().replace(Regex("""\s+"""), " ").trim().ifBlank {
                            when {
                                href.contains("hubcloud", ignoreCase = true) -> "HubCloud"
                                href.contains("gdflix", ignoreCase = true) -> "GDFlix"
                                else -> "Mirror"
                            }
                        }
                        val fullLabel = "$currentQualityLabel - $hostName"
                        processFinalHostUrl(href, fullLabel, m4uUrl, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    private suspend fun processFinalHostUrl(
        url: String,
        label: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = fixUrl(url)
        val quality = extractQualityFromText(label)

        if (fixedUrl.contains(".m3u8", ignoreCase = true) ||
            fixedUrl.contains(".mp4", ignoreCase = true) ||
            fixedUrl.contains(".mkv", ignoreCase = true)
        ) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - $label",
                    url = fixedUrl,
                    type = if (fixedUrl.contains(".m3u8", ignoreCase = true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    referer = refererUrl
                    this.quality = quality
                }
            )
            return
        }

        when {
            fixedUrl.contains("hubcloud", ignoreCase = true) -> {
                val extracted = HubCloudExtractor().collectLinks(fixedUrl, refererUrl, subtitleCallback)
                for (link in extracted) {
                    callback.invoke(
                        newExtractorLink(
                            source = link.source,
                            name = "$label (${link.name})",
                            url = link.url,
                            type = link.type
                        ) {
                            this.referer = link.referer
                            this.headers = link.headers
                            this.quality = if (link.quality != Qualities.Unknown.value) link.quality else quality
                        }
                    )
                }
            }

            fixedUrl.contains("gdflix", ignoreCase = true) -> {
                val extracted = GDFlixExtractor().collectLinks(fixedUrl, refererUrl, subtitleCallback)
                for (link in extracted) {
                    callback.invoke(
                        newExtractorLink(
                            source = link.source,
                            name = "$label (${link.name})",
                            url = link.url,
                            type = link.type
                        ) {
                            this.referer = link.referer
                            this.headers = link.headers
                            this.quality = if (link.quality != Qualities.Unknown.value) link.quality else quality
                        }
                    )
                }
            }

            else -> {
                loadExtractor(fixedUrl, refererUrl, subtitleCallback, callback)
            }
        }
    }

    private fun extractQualityFromText(text: String): Int {
        return when {
            text.contains("2160p", ignoreCase = true) || text.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            text.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            text.contains("720p", ignoreCase = true) -> Qualities.P720.value
            text.contains("480p", ignoreCase = true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("h2.entry-title a")
            ?: this.selectFirst("figure a.post-thumbnail")
            ?: return null

        val href = fixUrl(anchor.attr("href").trim())
        if (href.isBlank()) return null

        val fullTitle = anchor.attr("aria-label").trim().ifBlank { anchor.text().trim() }
        if (fullTitle.isBlank()) return null

        val imgEl = this.selectFirst("figure img")
        val poster = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }

        val qualityText = this.selectFirst("figure span.video-label")?.text()?.trim()
        val isSeries = fullTitle.contains(
            Regex("""\b(season|s\d+|ep\d+|episode|web\s*series|tv\s*show|k-drama)\b""", RegexOption.IGNORE_CASE)
        )

        val title = cleanReleaseTitle(fullTitle)

        return if (isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                qualityText?.let { q ->
                    addQuality(q)
                }
            }
        }
    }

    private fun cleanReleaseTitle(raw: String): String {
        return raw
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\b(480p|720p|1080p|2160p|4K|WEB-DL|WEBDL|HDTC|HDTS|HQ-HDTC|V2|BluRay|Full Movie|Complete WEB Series|Netflix Original|Amazon Prime)\b""", RegexOption.IGNORE_CASE), " ")
            .replace("|", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun encodeQuery(q: String): String =
        java.net.URLEncoder.encode(q, "UTF-8")
}

