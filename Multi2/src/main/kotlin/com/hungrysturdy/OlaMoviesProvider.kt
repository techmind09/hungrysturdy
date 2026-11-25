package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class OlaMoviesProvider : MainAPI() {
    override var mainUrl = "https://v6.olamovies.mov"
    private val driveBaseUrl = "https://drive.olamovies.download"

    override var name = "OlaMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Logged-in session cookie (v6.olamovies.mov aur drive.olamovies.download ke liye)
    private var authCookie: String = ""

    fun setCookie(cookie: String) {
        authCookie = cookie.trim()
    }

    private fun getHeaders(referer: String = "$mainUrl/"): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
            "Referer" to referer
        )
        if (authCookie.isNotBlank()) {
            headers["Cookie"] = authCookie
        }
        return headers
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Uploads",
        "$mainUrl/?sort=trending" to "Trending",
        "$mainUrl/?sort=most_viewed" to "Most Viewed",
        "$mainUrl/categories/bollywood" to "Bollywood Movies",
        "$mainUrl/categories/hollywood" to "Hollywood Movies",
        "$mainUrl/categories/south-indian" to "South Indian Movies",
        "$mainUrl/categories/hindi-tv-series" to "Hindi TV Series",
        "$mainUrl/categories/english-tv-series" to "English TV Series",
        "$mainUrl/categories/korean-tv-series" to "Korean TV Series",
        "$mainUrl/categories/anime-tv-series" to "Anime Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page == 1) request.data else "${request.data}${separator}page=$page"

        val doc = app.get(url, headers = getHeaders()).document
        val items = doc.select("div[data-listing-results] div.group\\/card > a, div.grid > div.group\\/card > a")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (href.contains("/sso-login")) return null

        val titleSpan = this.selectFirst("span.text-\\[13\\.5px\\]") ?: return null
        val clonedTitle = titleSpan.clone()
        clonedTitle.select("span[aria-label*='Paid'], span[title='Ongoing']").remove()
        val rawTitle = clonedTitle.text().trim()
        if (rawTitle.isBlank()) return null

        val cleanTitle = rawTitle
            .substringBefore("720p")
            .substringBefore("1080p")
            .substringBefore("2160p")
            .replace(Regex("^\\[Season.*?\\]\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { rawTitle }

        val imgSrc = this.selectFirst("img")?.attr("src") ?: ""
        val posterUrl = extractRealImageUrl(imgSrc)

        val qualityBadges = this.select("span.top-2\\.5.left-2\\.5 span").text()
        val quality = parseQuality("$qualityBadges $rawTitle")

        val isSeries = href.contains("/tv-series/") || href.contains("/tv-shows/")

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

    private fun parseQuality(text: String): SearchQuality? {
        return when {
            text.contains("2160p", true) || text.contains("4K", true) -> SearchQuality.UHD
            text.contains("1080p", true) || text.contains("720p", true) || text.contains("1280x", true) -> SearchQuality.HD
            else -> null
        }
    }

    private fun extractRealImageUrl(src: String): String? {
        if (src.isBlank()) return null
        return try {
            if (src.contains("/_next/image") && src.contains("url=")) {
                val encodedUrl = src.substringAfter("url=").substringBefore("&")
                URLDecoder.decode(encodedUrl, "UTF-8")
            } else {
                fixUrlNull(src)
            }
        } catch (_: Exception) {
            fixUrlNull(src)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${query.encodeUri()}"
        val doc = app.get(searchUrl, headers = getHeaders()).document
        return doc.select("div.group\\/card > a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("#")
        val response = app.get(cleanUrl, headers = getHeaders())

        if (response.url.contains("/sso-login")) {
            throw ErrorLoadingException("Login cookie required for OlaMovies.")
        }

        val doc = response.document
        val fullTitle = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val title = fullTitle
            .substringBefore("720p")
            .substringBefore("1080p")
            .substringBefore("2160p")
            .replace(Regex("^\\[Season.*?\\]\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { fullTitle }

        val year = Regex("\\((\\d{4})\\)").find(fullTitle)?.groupValues?.get(1)?.toIntOrNull()

        val rawImg = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("main img")?.attr("src") ?: ""
        val poster = extractRealImageUrl(rawImg)

        val description = doc.selectFirst("meta[name='description']")?.attr("content")
            ?: doc.select("p").firstOrNull { it.text().length > 60 }?.text()

        val tags = doc.select("a[href*='/tags/'], a[href*='/categories/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 25 }
            .distinct()

        val isSeries = cleanUrl.contains("/tv-series/") || cleanUrl.contains("/tv-shows/")

        return if (isSeries) {
            val downloadSection = doc.selectFirst("#downloads") ?: doc
            val candidates = downloadSection.select("a[href], button[data-url], button[data-href]")
                .mapNotNull { el ->
                    val rawLink = el.attr("href")
                        .ifBlank { el.attr("data-url") }
                        .ifBlank { el.attr("data-href") }
                    val fixed = fixUrlNull(rawLink) ?: return@mapNotNull null
                    if (fixed.contains("/sso-login") || fixed.startsWith("#")) return@mapNotNull null
                    val label = el.text().trim().ifBlank { el.attr("aria-label").trim() }
                    fixed to label
                }
                .distinctBy { it.first }

            val episodes = mutableListOf<Episode>()
            candidates.forEachIndexed { index, (epUrl, label) ->
                val seasonNum = Regex("S(\\d{1,2})|Season\\s*(\\d{1,2})", RegexOption.IGNORE_CASE)
                    .find(label)?.groupValues?.firstOrNull { it.isNotBlank() && it.all { c -> c.isDigit() } }?.toIntOrNull() ?: 1
                val epNum = Regex("E(\\d{1,3})|Episode\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
                    .find(label)?.groupValues?.firstOrNull { it.isNotBlank() && it.all { c -> c.isDigit() } }?.toIntOrNull() ?: (index + 1)

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = label.ifBlank { "Link ${index + 1}" }
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }

            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode("$cleanUrl#downloads") {
                        this.name = "Season / Quality Links"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, cleanUrl, TvType.Movie, "$cleanUrl#downloads") {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val cleanData = data.substringBefore("#")

        if (isDirectVideoUrl(cleanData)) {
            emitDirectVideo(cleanData, "OM Direct", "$driveBaseUrl/", callback)
            return true
        }

        if (cleanData.contains("drive.olamovies.download")) {
            return resolveDrivePage(cleanData, subtitleCallback, callback)
        }

        if (!cleanData.startsWith(mainUrl)) {
            loadExtractor(cleanData, "$mainUrl/", subtitleCallback, callback)
            return true
        }

        val response = app.get(cleanData, headers = getHeaders())
        if (response.url.contains("/sso-login")) return false

        val doc = response.document
        val downloadSection = doc.selectFirst("#downloads") ?: doc
        val htmlText = downloadSection.html() + "\n" + doc.select("script").joinToString("\n") { it.html() }

        val allUrls = mutableSetOf<String>()
        downloadSection.select("a[href]").mapNotNull { fixUrlNull(it.attr("href")) }.forEach { allUrls.add(it) }

        val urlRegex = Regex("""https?://[^\s"'\\<>]+""")
        urlRegex.findAll(htmlText).map { it.value.trimEnd('.', ',', ';', ')') }.forEach { allUrls.add(it) }

        for (target in allUrls) {
            when {
                isDirectVideoUrl(target) -> {
                    emitDirectVideo(target, "OM Direct", cleanData, callback)
                    found = true
                }
                target.contains("drive.olamovies.download") -> {
                    if (resolveDrivePage(target, subtitleCallback, callback)) {
                        found = true
                    }
                }
                !target.startsWith(mainUrl) &&
                    !target.contains("/sso-login") &&
                    !target.contains("telegram.me") &&
                    !target.contains("youtube.com") &&
                    !target.contains("olam18plus") &&
                    !target.contains("rarbgdump") &&
                    !target.contains("graph.org") -> {
                    loadExtractor(target, cleanData, subtitleCallback, callback)
                    found = true
                }
            }
        }

        return found
    }

    private suspend fun resolveDrivePage(
        driveUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val res = app.get(driveUrl, headers = getHeaders(referer = "$mainUrl/"))
        val body = res.text

        val directRegex = Regex("""https?://video-downloads\.googleusercontent\.com/[^\s"'\\<>]+|https?://[^\s"'\\<>]+\.(?:mp4|mkv|m3u8)(?:\?[^\s"'\\<>]*)?""", RegexOption.IGNORE_CASE)
        directRegex.findAll(body).map { it.value }.distinct().forEach { streamUrl ->
            emitDirectVideo(streamUrl, "OM Drive", driveUrl, callback)
            found = true
        }

        val mirrorRegex = Regex("""https?://(?:[^\s"'\\<>]*(?:vikingfile|pixeldrain|gdflix|buzzheavier|gofile|hubcloud|gdlink|workers\.dev)[^\s"'\\<>]*)""", RegexOption.IGNORE_CASE)
        mirrorRegex.findAll(body).map { it.value }.distinct().forEach { mirrorUrl ->
            loadExtractor(mirrorUrl, driveUrl, subtitleCallback, callback)
            found = true
        }

        return found
    }

    private suspend fun emitDirectVideo(
        videoUrl: String,
        sourceName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
        val quality = when {
            videoUrl.contains("2160", ignoreCase = true) || videoUrl.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            videoUrl.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            videoUrl.contains("720", ignoreCase = true) -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }

        val link = newExtractorLink(
            source = sourceName,
            name = sourceName,
            url = videoUrl,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = getHeaders(referer = referer)
        }
        callback(link)
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        return url.contains("video-downloads.googleusercontent.com", ignoreCase = true) ||
            url.contains(".mp4", ignoreCase = true) ||
            url.contains(".mkv", ignoreCase = true) ||
            url.contains(".m3u8", ignoreCase = true)
    }

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}

