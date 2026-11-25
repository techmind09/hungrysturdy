package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

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

    // Apni valid cookies yahan daalein:
    // mainCookie = "om_auth_token=eyJ..."
    // driveCookie = "__Secure-next-auth.session-token=eyJ..."
    private var mainCookie: String = ""
    private var driveCookie: String = ""

    fun setCookies(v6Cookie: String, omDriveCookie: String) {
        mainCookie = v6Cookie.trim()
        driveCookie = omDriveCookie.trim()
    }

    private fun getHeaders(referer: String = "$mainUrl/", isDrive: Boolean = false): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer
        )
        val cookieToUse = if (isDrive) driveCookie else mainCookie
        if (cookieToUse.isNotBlank()) {
            headers["Cookie"] = cookieToUse
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

        val res = app.get(url, headers = getHeaders())
        val items = parseListingResponse(res.text)

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun parseListingResponse(body: String): List<SearchResponse> {
        val doc = Jsoup.parse(body, mainUrl)
        // Header/Nav dropdown links ko remove karein taaki "Marvel Cinematic Universe" jaise nav links na aayein
        doc.select("header, nav, aside, footer").remove()

        // 1. Primary card selector inside main content
        val cardResults = doc.select("main div[data-listing-results] div.group\\/card > a, main div.grid > div.group\\/card > a, div.group\\/card > a")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        if (cardResults.isNotEmpty()) return cardResults

        // 2. Fallback: sirf wahi links jinke andar <img> tag hai (nav links skip ho jayenge)
        val anchorResults = doc.select("main a[href*='/movies/'], main a[href*='/tv-series/'], main a[href*='/tv-shows/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val rawTitle = anchor.selectFirst("span.text-\\[13\\.5px\\]")?.text()?.trim()
                    ?: anchor.text().trim()
                if (rawTitle.length < 3) return@mapNotNull null

                val cleanTitle = cleanReleaseTitle(rawTitle)
                val imgSrc = anchor.selectFirst("img")?.attr("src") ?: ""
                val poster = extractRealImageUrl(imgSrc)
                val isSeries = href.contains("/tv-series/") || href.contains("/tv-shows/")

                if (isSeries) {
                    newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.quality = parseQuality(rawTitle)
                    }
                } else {
                    newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                        this.posterUrl = poster
                        this.quality = parseQuality(rawTitle)
                    }
                }
            }
            .distinctBy { it.url }
        if (anchorResults.isNotEmpty()) return anchorResults

        return emptyList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (href.contains("/sso-login")) return null

        // Card ke andar poster image honi zaroori hai
        val imgEl = this.selectFirst("img") ?: return null
        val imgSrc = imgEl.attr("src").ifBlank { imgEl.attr("data-src") }
        val posterUrl = extractRealImageUrl(imgSrc) ?: return null

        val titleSpan = this.selectFirst("span.text-\\[13\\.5px\\]") ?: return null
        val clonedTitle = titleSpan.clone()
        clonedTitle.select("span[aria-label*='Paid'], span[title='Ongoing']").remove()
        val rawTitle = clonedTitle.text().trim()
        if (rawTitle.isBlank()) return null

        val cleanTitle = cleanReleaseTitle(rawTitle)
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

    private fun cleanReleaseTitle(rawTitle: String): String {
        return rawTitle
            .substringBefore("720p")
            .substringBefore("1080p")
            .substringBefore("2160p")
            .substringBefore("480p")
            .replace(Regex("^\\[Season.*?\\]\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { rawTitle }
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
        val res = app.get(searchUrl, headers = getHeaders())
        return parseListingResponse(res.text)
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("#")
        val response = app.get(cleanUrl, headers = getHeaders())
        val html = response.text
        val doc = response.document

        val fullTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: cleanUrl.substringAfterLast("/").replace("-", " ")
        val title = cleanReleaseTitle(fullTitle)
        val year = Regex("\\((\\d{4})\\)").find(fullTitle)?.groupValues?.get(1)?.toIntOrNull()

        val postId = extractPostId(html)
        val backdrops = if (postId != null) fetchPostBackdrops(postId, cleanUrl) else emptyList()

        val rawImg = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("main img")?.attr("src") ?: ""
        val poster = extractRealImageUrl(rawImg) ?: backdrops.firstOrNull()
        val background = backdrops.firstOrNull() ?: poster

        val description = doc.selectFirst("meta[name='description']")?.attr("content")
            ?: doc.select("main p").firstOrNull { it.text().length > 60 }?.text()

        // Sirf main content ke tags (header/nav ke "All Movies ->" links exclude)
        val mainDoc = doc.selectFirst("main") ?: doc
        val tags = mainDoc.select("a[href*='/tags/'], a[href*='/categories/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("All ") && it.length < 25 }
            .distinct()

        val isSeries = cleanUrl.contains("/tv-series/") || cleanUrl.contains("/tv-shows/")

        return if (isSeries) {
            val downloadSection = mainDoc.selectFirst("#downloads") ?: mainDoc
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
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, cleanUrl, TvType.Movie, "$cleanUrl#downloads") {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    private fun extractPostId(html: String): String? {
        return Regex("""/api/posts/(\d+)/""").find(html)?.groupValues?.get(1)
            ?: Regex(""""postId"\s*:\s*"?(\d+)"?""").find(html)?.groupValues?.get(1)
    }

    private suspend fun fetchPostBackdrops(postId: String, referer: String): List<String> {
        if (postId.isBlank()) return emptyList()
        return try {
            val apiUrl = "$mainUrl/api/posts/$postId/backdrops"
            val jsonText = app.get(apiUrl, headers = getHeaders(referer)).text
            Regex("""https?://[^\s"'\\<>]+?\.(?:jpg|jpeg|png|webp)(?:\?[^\s"'\\<>]*)?""", RegexOption.IGNORE_CASE)
                .findAll(jsonText)
                .map { it.value.replace("\\/", "/") }
                .distinct()
                .toList()
        } catch (_: Exception) {
            emptyList()
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

        // WebViewResolver: #downloads ke andar pehle quality/download button par auto-click karke
        // drive.olamovies.download/file/... ya direct stream URL intercept karna
        val autoClickJs = """
            (function() {
                let attempts = 0;
                const timer = setInterval(() => {
                    attempts++;
                    const container = document.querySelector('#downloads') || document.body;
                    const btns = Array.from(container.querySelectorAll('button, a'));
                    const dlBtn = btns.find(b => {
                        const t = (b.innerText || '').toLowerCase();
                        return (t.includes('1080p') || t.includes('720p') || t.includes('2160p') || t.includes('download') || t.includes('get link'))
                            && !t.includes('how to') && !b.href?.includes('/sso-login');
                    });
                    if (dlBtn) {
                        dlBtn.click();
                    }
                    const driveLink = Array.from(document.querySelectorAll('a[href*="drive.olamovies.download/file/"]'))[0];
                    if (driveLink) {
                        clearInterval(timer);
                        window.location.href = driveLink.href;
                    }
                    if (attempts > 20) clearInterval(timer);
                }, 600);
            })();
        """.trimIndent()

        val interceptedUrl = try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""drive\.olamovies\.download/file/|video-downloads\.googleusercontent\.com/|\.(?:mp4|mkv|m3u8)"""),
                script = autoClickJs,
                scriptCallback = { _ -> },
                useOkhttp = false,
                timeout = 18_000L
            )
            app.get(
                url = data,
                headers = getHeaders(),
                interceptor = resolver
            ).url
        } catch (_: Exception) {
            ""
        }

        if (interceptedUrl.isNotBlank()) {
            when {
                isDirectVideoUrl(interceptedUrl) -> {
                    emitDirectVideo(interceptedUrl, "OM WebView", data, callback)
                    found = true
                }
                interceptedUrl.contains("drive.olamovies.download/file/") -> {
                    if (resolveDrivePage(interceptedUrl, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        // HTML & script regex fallback
        val response = app.get(cleanData, headers = getHeaders())
        val doc = response.document
        val downloadSection = doc.selectFirst("#downloads") ?: doc
        val htmlText = downloadSection.html() + "\n" + doc.select("script").joinToString("\n") { it.html() }

        val urlRegex = Regex("""https?://[^\s"'\\<>]+""")
        val allUrls = urlRegex.findAll(htmlText)
            .map { it.value.trimEnd('.', ',', ';', ')').replace("\\/", "/") }
            .distinct()
            .toList()

        for (target in allUrls) {
            when {
                isDirectVideoUrl(target) -> {
                    emitDirectVideo(target, "OM Direct", cleanData, callback)
                    found = true
                }
                target.contains("drive.olamovies.download/file/") -> {
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
        val res = app.get(driveUrl, headers = getHeaders(referer = "$mainUrl/", isDrive = true))
        val body = res.text

        // 1. /api/file/cloud/progress API try karna
        val fileId = driveUrl.substringAfter("/file/").substringBefore("?").substringBefore("#").trim()
        val fileName = Regex(""""fileName"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
        val fileSize = Regex(""""fileSize"\s*:\s*([0-9.]+)""").find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        if (fileId.isNotBlank()) {
            try {
                val payload = """{"fileId":"$fileId","fileSize":$fileSize,"fileName":"$fileName"}"""
                val progressRes = app.post(
                    "$driveBaseUrl/api/file/cloud/progress",
                    headers = getHeaders(referer = driveUrl, isDrive = true) + mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to driveBaseUrl
                    ),
                    requestBody = payload.toRequestBody("application/json".toMediaType())
                ).text

                extractDirectUrls(progressRes).forEach { streamUrl ->
                    emitDirectVideo(streamUrl, "OM Fast Cloud", driveUrl, callback)
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        // 2. WebViewResolver se OM Drive page par Rocket / Fast Cloud button click karke stream intercept karna
        if (!found) {
            val driveClickJs = """
                (function() {
                    let tries = 0;
                    const t = setInterval(() => {
                        tries++;
                        const btns = Array.from(document.querySelectorAll('button, a'));
                        const fastBtn = btns.find(b => {
                            const txt = (b.innerText || '').toLowerCase();
                            return txt.includes('rocket download') || txt.includes('fast cloud') || txt.includes('instant link');
                        });
                        if (fastBtn) {
                            clearInterval(t);
                            fastBtn.click();
                        }
                        if (tries > 15) clearInterval(t);
                    }, 500);
                })();
            """.trimIndent()

            try {
                val driveResolver = WebViewResolver(
                    interceptUrl = Regex("""video-downloads\.googleusercontent\.com/|\.(?:mp4|mkv|m3u8)"""),
                    script = driveClickJs,
                    scriptCallback = { _ -> },
                    useOkhttp = false,
                    timeout = 18_000L
                )
                val resolvedStream = app.get(
                    url = driveUrl,
                    headers = getHeaders(referer = "$mainUrl/", isDrive = true),
                    interceptor = driveResolver
                ).url

                if (isDirectVideoUrl(resolvedStream)) {
                    emitDirectVideo(resolvedStream, "OM Drive WebView", driveUrl, callback)
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        // 3. Direct ya mirror links in page source
        extractDirectUrls(body).forEach { streamUrl ->
            emitDirectVideo(streamUrl, "OM Drive", driveUrl, callback)
            found = true
        }

        val mirrorRegex = Regex(
            """https?://(?:[^\s"'\\<>]*(?:vikingfile|pixeldrain|gdflix|buzzheavier|gofile|hubcloud|gdlink|workers\.dev)[^\s"'\\<>]*)""",
            RegexOption.IGNORE_CASE
        )
        mirrorRegex.findAll(body).map { it.value.replace("\\/", "/") }.distinct().forEach { mirrorUrl ->
            loadExtractor(mirrorUrl, driveUrl, subtitleCallback, callback)
            found = true
        }

        return found
    }

    private fun extractDirectUrls(text: String): List<String> {
        val directRegex = Regex(
            """https?://video-downloads\.googleusercontent\.com/[^\s"'\\<>]+|https?://[^\s"'\\<>]+\.(?:mp4|mkv|m3u8)(?:\?[^\s"'\\<>]*)?""",
            RegexOption.IGNORE_CASE
        )
        return directRegex.findAll(text)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()
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
            this.headers = getHeaders(referer = referer, isDrive = true)
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
        URLEncoder.encode(this, "UTF-8")
}

