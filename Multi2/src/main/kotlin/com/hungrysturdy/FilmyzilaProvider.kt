package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FilmyzilaProvider : MainAPI() {
    override var mainUrl = "https://gfilmyzilla.in"
    override var name = "FilmyZilla"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Updates",
        "$mainUrl/treding-movies/" to "Trending Movies",
        "$mainUrl/bollywood-hindi-movies/" to "Bollywood Hindi Movies",
        "$mainUrl/south-hindi-dubbed-movie/" to "South Hindi Dubbed Movie",
        "$mainUrl/hollywood-hindi-movies/" to "Hollywood Hindi Movies",
        "$mainUrl/hq-dubbed-movies-uncut/" to "HQ Dubbed Movies UnCut",
        "$mainUrl/hq-hindi-dub-movie-with-ads/" to "HQ Hindi Dub Movie With ads",
        "$mainUrl/web-series/" to "Web Series",
        "$mainUrl/japanese-anime/" to "Japanese Anime",
        "$mainUrl/animation-movies/" to "Animation Movies",
        "$mainUrl/marvel-hollywood-movies/" to "Marvel Hollywood Movies",
        "$mainUrl/punjabi-movies/" to "Punjabi Movies",
        "$mainUrl/imdb-top-movies/" to "IMDb Top Movies",
        "$mainUrl/movies/" to "Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val document = app.get(targetUrl).document
        var items = document.select("main.movie-grid div.fz-movie-row, div.fz-movie-row")
            .mapNotNull { element -> element.toSearchResult() }
            .distinctBy { it.url }

        // Fallback for Trending Movies from homepage section if /treding-movies/ returns empty
        if (items.isEmpty() && page <= 1 && request.data.contains("treding-movies", ignoreCase = true)) {
            val homeDoc = app.get("$mainUrl/").document
            val trendingHeader = homeDoc.select("div.head")
                .firstOrNull { it.text().contains("Trending", ignoreCase = true) }
            val trendingItems = mutableListOf<SearchResponse>()
            var sibling = trendingHeader?.nextElementSibling()
            while (sibling != null && !sibling.hasClass("head")) {
                if (sibling.hasClass("fz-movie-row")) {
                    sibling.toSearchResult()?.let { trendingItems.add(it) }
                }
                sibling = sibling.nextElementSibling()
            }
            items = trendingItems.distinctBy { it.url }
        }

        // Fallback parser if a category uses div.touch or simple .html links instead of .fz-movie-row
        if (items.isEmpty()) {
            items = document.select("div.touch a[href$='.html'], main a[href$='.html']")
                .mapNotNull { anchor ->
                    val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                    if (href.contains("dmca.html", true) || href.contains("disclaimer.html", true)) {
                        return@mapNotNull null
                    }
                    val rawTitle = anchor.text().trim().ifBlank { return@mapNotNull null }
                    val cleanTitle = cleanTitleText(rawTitle)
                    val slug = href.substringAfterLast("/").substringBeforeLast(".html")
                    val thumb = if (slug.isNotBlank()) "$mainUrl/uploads/thumb/$slug.webp" else null
                    val isSeries = rawTitle.contains(Regex("""\bS\d+\b|Series""", RegexOption.IGNORE_CASE))
                    val isAnime = request.data.contains("anime", ignoreCase = true)

                    if (isSeries) {
                        newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                            this.posterUrl = thumb
                        }
                    } else {
                        val type = if (isAnime) TvType.Anime else TvType.Movie
                        newMovieSearchResponse(cleanTitle, href, type) {
                            this.posterUrl = thumb
                        }
                    }
                }
                .distinctBy { it.url }
        }

        val hasNextPage = if (request.data == "$mainUrl/") {
            false
        } else {
            document.selectFirst("div.fz-pagination a[href*='/page/${page + 1}/']") != null
        }

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = hasNextPage
        )
    }

    private fun cleanTitleText(rawTitle: String): String {
        return rawTitle
            .replace(Regex("""\(\d{4}\).*$"""), "")
            .replace(
                Regex(
                    """\b(Hindi|English|Telugu|Tamil|Malayalam|Dual Audio|UnCut|South Movie|Bollywood|Hollywood|Web Series|HEVC|ESub|PreDvD|HQCam)\b.*$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
            .ifEmpty { rawTitle }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a.fz-title-link") ?: selectFirst("a.fz-poster-link")
        val href = fixUrlNull(linkElement?.attr("href")) ?: return null

        val rawTitle = selectFirst("div.fz-title")?.text()?.trim()
            ?: selectFirst("img.fz-poster")?.attr("alt")?.trim()
            ?: return null

        val posterUrl = fixUrlNull(selectFirst("img.fz-poster")?.attr("src"))
        val categoryText = selectFirst("div.fz-category")?.text()?.trim().orEmpty()

        val isSeries = categoryText.contains("Web Series", ignoreCase = true) ||
            rawTitle.contains(Regex("""\bS\d+\b|Series""", RegexOption.IGNORE_CASE))

        val isAnime = categoryText.contains("Anime", ignoreCase = true)
        val cleanTitle = cleanTitleText(rawTitle)

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            val type = if (isAnime) TvType.Anime else TvType.Movie
            newMovieSearchResponse(cleanTitle, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(searchUrl).document

        return document.select("main.movie-grid div.fz-movie-row, div.fz-movie-row")
            .mapNotNull { element -> element.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun extractPoster(document: Document, url: String): String? {
        val slug = url.substringAfterLast("/").substringBeforeLast(".html").trim()

        val metaImage = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("favicon", ignoreCase = true) }
        if (metaImage != null) return fixUrlNull(metaImage)

        if (slug.isNotBlank()) {
            val slugImg = document.select("img[src]")
                .firstOrNull { it.attr("src").contains(slug, ignoreCase = true) }
                ?.attr("src")
            if (!slugImg.isNullOrBlank()) return fixUrlNull(slugImg)
        }

        val mainImg = document.select("img[src*='/uploads/']")
            .firstOrNull { img -> img.parents().none { it.hasClass("fz-movie-row") } }
            ?.attr("src")
        if (!mainImg.isNullOrBlank()) return fixUrlNull(mainImg)

        return if (slug.isNotBlank()) "$mainUrl/uploads/thumb/$slug.webp" else null
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1, div.fz-title, title")?.text()?.trim()
            ?: "Untitled"
        val cleanTitle = rawTitle
            .replace(Regex("""\(\d{4}\).*$"""), "")
            .replace(Regex("""\s*-\s*FilmyZilla.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { rawTitle }

        val posterUrl = extractPoster(document, url)
        val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val plot = document.selectFirst("div.fz-seo p, meta[name=description]")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()

        val qualityLinks = document.select("a[href]")
            .mapNotNull { el ->
                val href = el.attr("abs:href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val label = el.text().trim()
                if (
                    href != url &&
                    (
                        href.contains("filesdl", ignoreCase = true) ||
                        href.contains("hubcloud", ignoreCase = true) ||
                        href.contains("gofile", ignoreCase = true) ||
                        href.contains("drivenext", ignoreCase = true) ||
                        href.contains("fuckingfast", ignoreCase = true) ||
                        href.contains("r2.dev", ignoreCase = true) ||
                        label.contains(Regex("""\b(480p|720p|1080p|2160p|4K|HEVC|PreDvD|WEB-?DL)\b""", RegexOption.IGNORE_CASE))
                    )
                ) {
                    label to href
                } else {
                    null
                }
            }
            .distinctBy { it.second }

        val isSeries = rawTitle.contains(Regex("""\bS\d+\b|Web Series""", RegexOption.IGNORE_CASE))

        return if (isSeries) {
            val episodes = qualityLinks.mapIndexed { index, (label, link) ->
                newEpisode(link) {
                    name = label.ifBlank { "Episode ${index + 1}" }
                    episode = index + 1
                }
            }
            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
            }
        } else {
            val payload = qualityLinks.joinToString("||") { "${it.first}::${it.second}" }
                .ifEmpty { url }

            newMovieLoadResponse(cleanTitle, url, TvType.Movie, payload) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val entries = data.split("||").mapNotNull { item ->
            val parts = item.split("::", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else "Stream" to parts[0]
        }

        for ((label, link) in entries) {
            processLink(link, label, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun processLink(
        url: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseQuality = getQualityFromName(label)

        if (isDirectVideoUrl(url)) {
            emitVideoLink(
                url = url,
                referer = mainUrl,
                sourceLabel = "$name - $label",
                quality = baseQuality,
                callback = callback
            )
            return
        }

        if (url.contains("filesdl", ignoreCase = true) || url.startsWith(mainUrl)) {
            val doc = app.get(url, referer = mainUrl).document
            val pageTitle = doc.selectFirst("div.title")?.text()?.trim().orEmpty()
            val combinedQuality = getQualityFromName("$label $pageTitle")

            val buttons = doc.select("div.container a.button[href], a.button[href]")
            for (btn in buttons) {
                val href = btn.attr("abs:href").trim()
                val btnText = btn.text().trim()
                if (href.isBlank() || href == url) continue
                if (href.contains("t.me/", ignoreCase = true)) continue

                when {
                    isDirectVideoUrl(href) -> {
                        emitVideoLink(
                            url = href,
                            referer = url,
                            sourceLabel = btnText.ifEmpty { "$name - $label" },
                            quality = getQualityFromName("$label $pageTitle $btnText"),
                            callback = callback
                        )
                    }

                    href.contains("fffast.filesdl.in/zdownload.php", ignoreCase = true) -> {
                        val response = app.get(href, referer = url, allowRedirects = false)
                        val location = response.headers["Location"]
                        if (!location.isNullOrBlank()) {
                            val resolved = fixUrl(location)
                            if (isDirectVideoUrl(resolved)) {
                                emitVideoLink(
                                    url = resolved,
                                    referer = url,
                                    sourceLabel = btnText.ifEmpty { "Direct Download" },
                                    quality = combinedQuality,
                                    callback = callback
                                )
                            } else {
                                loadExtractor(resolved, url, subtitleCallback, callback)
                            }
                        } else if (response.url.isNotBlank() && isDirectVideoUrl(response.url)) {
                            emitVideoLink(
                                url = response.url,
                                referer = url,
                                sourceLabel = btnText.ifEmpty { "Direct Download" },
                                quality = combinedQuality,
                                callback = callback
                            )
                        }
                    }

                    btnText.contains("Login To Download", ignoreCase = true) ||
                        href.contains("gdflix", ignoreCase = true) -> {
                        continue
                    }

                    else -> {
                        loadExtractor(href, url, subtitleCallback, callback)
                    }
                }
            }
            return
        }

        loadExtractor(url, mainUrl, subtitleCallback, callback)
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        return url.contains(
            Regex("""\.(mp4|mkv|webm|avi|m3u8)(\?|&|#|$)""", RegexOption.IGNORE_CASE)
        )
    }

    private suspend fun emitVideoLink(
        url: String,
        referer: String,
        sourceLabel: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = sourceLabel,
                name = "$name - $sourceLabel",
                url = url,
                type = if (url.contains(".m3u8", ignoreCase = true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                this.referer = referer
                this.quality = quality
            }
        )
    }
}

