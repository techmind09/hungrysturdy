package com.hungrysturdy

import android.net.Uri
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class MovieFoxProvider : MainAPI() {

    override var mainUrl = "https://moviefox.pro"
    override var name = "MovieFox"
    override var lang = "en"
    override val hasMainPage = true

    private val cfKiller by lazy { CloudflareKiller() }

    private val prmoviesMirrors = listOf(
        "https://prmovies.com.cv",
        "https://prmovies.kids",
        "https://prmovies.coupons",
        "https://prmovies.com"
    )
    private var activePrmoviesBase = "https://prmovies.coupons"
    private val prmoviesUserAgent =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
    private val prmoviesHeaders = mapOf(
        "User-Agent" to prmoviesUserAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "_ga=GA1.1.1629448397.1794059276; domain-alert=1; fpestid=qKEJFcR_c3RLFa6Kn--eeRQW2dShUUkgk5lB1Hx76DaGCwYwnn5wDmCy7qWmMUlFPnD5Zw; _ga_7BWGJ9MXSS=GS2.1.s1794059275\$o1\$g1\$t1794059680\$j60\$l0\$h0"
    )
    private val prmoviesMutexMap = ConcurrentHashMap<String, Mutex>()
    private val prmoviesCfMap = ConcurrentHashMap<String, CloudflareKiller>()

    // HindMoviez & VegaMovies State
    private val hindmoviezDomains = listOf("https://v3.hindmovie.im")
    private val vegamoviesDomains = listOf(
        "https://vegamovies.lease",
        "https://rogmovies.autos",
        "https://rogmovies.im"
    )
    private val vegaDriveCache = ConcurrentHashMap<String, VegaDrivePage>()

    private val vegaDeadHosts = Regex("""(?i)megaup|viking|vik1ng|telegram|t\.me""")
    private val vegaJunk = Regex(
        """(?i)vglist|t\.me|telegram|imdb\.com|google\.com/search|whatsapp|share|facebook|""" +
            """twitter|pinterest|report|hubcloud\.(foo|cx)|/admin|login|signup|\bidm\b|\bida\b|""" +
            """category|/tag/|/author/|/page/"""
    )
    private val vegaButtonHosts = Regex(
        """(?i)nexdrive|vcloud|vegadrive|fastdl|filebee|filepress|fpgo|gofile|hubcloud|""" +
            """pixeldrain|gpdl|\.r2\.dev|\.r2\.cloudflarestorage\.com|buzzserver|10gbps"""
    )

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "Trending Now",
        "$mainUrl/movies?list=popular" to "Popular Movies",
        "$mainUrl/movies?list=top_rated" to "Top Rated Movies",
        "$mainUrl/movies?list=now_playing" to "In Theaters Now",
        "$mainUrl/tv?list=popular" to "Popular TV Shows",
        "$mainUrl/tv?list=top_rated" to "Top Rated TV Shows",
        "$mainUrl/tv?lang=ko" to "K-Drama",
        "$mainUrl/movies?lang=ko" to "Korean Movies",
        "$mainUrl/movies?lang=hi" to "Indian Movies (Hindi)",
        "$mainUrl/tv?lang=hi" to "Indian TV Shows",
        "$mainUrl/movies?lang=pa" to "Punjabi Movies",
        "$mainUrl/movies?lang=ta" to "Tamil Movies",
        "$mainUrl/movies?lang=te" to "Telugu Movies",
        "$mainUrl/movies?lang=en" to "American / Hollywood Movies",
        "$mainUrl/tv?lang=en" to "American / English TV Shows",
        "$mainUrl/movies?lang=ur" to "Pakistani / Urdu Movies",
        "$mainUrl/tv?lang=ur" to "Pakistani Dramas",
        "$mainUrl/tv?lang=tr" to "Turkish Dramas",
        "$mainUrl/tv?lang=zh" to "Chinese Dramas (C-Drama)",
        "$mainUrl/anime" to "Anime",
        "$mainUrl/tv?lang=ja" to "Japanese Series",
        "$mainUrl/movies?lang=ja" to "Japanese Movies",
        "$mainUrl/movies?lang=es" to "Spanish Movies",
        "$mainUrl/tv?lang=es" to "Spanish TV Shows",
        "$mainUrl/dramas" to "Dramas",
        "$mainUrl/dramas?category=classics" to "Classic Dramas",
        "$mainUrl/universes" to "Cinematic Universes"
    )

    private val ignoredLinkTexts = setOf(
        "where to watch",
        "more info",
        "watch now",
        "view all",
        "play",
        "play now",
        "trailer",
        "all options",
        "go home",
        "search"
    )

    private fun currentTimeoutSec(minSec: Long = 3L): Long {
        val ctx = CloudStreamApp.context ?: return 5L
        return MovieFoxSettingsDialog.getTimeoutSeconds(ctx).toLong().coerceAtLeast(minSec)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val timeoutSec = currentTimeoutSec()
        val targetUrl = if (page > 1) {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page"
        } else {
            request.data
        }

        val rawHtml = app.get(targetUrl, interceptor = cfKiller, timeout = timeoutSec).text
        val document = Jsoup.parse(rawHtml, mainUrl)
        val home = mutableListOf<HomePageList>()

        val cardSelector =
            "article[data-cf] a[aria-label][href], a[href*=/universes/], a[href*=/universe/], a[href*=/channel/]"
        val fallbackLinkSelector =
            "a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/], a[href*=/series/]"

        val elements = document.select(cardSelector).ifEmpty { document.select(fallbackLinkSelector) }
        val items = elements
            .mapNotNull { element -> toSearchResult(element) }
            .distinctBy { it.url }

        if (items.isNotEmpty()) {
            home.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(home, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.attr("href").trim()
        if (href.isBlank()) return null

        val rawText = element.text().trim()
        if (rawText.lowercase() in ignoredLinkTexts) return null

        val article = element.closest("article")
        val img = element.selectFirst("img") ?: article?.selectFirst("img")
        val isSpecialCard = href.contains("/universe") || href.contains("/channel/")

        if (img == null && !isSpecialCard) return null

        val ariaTitle = element.attr("aria-label")
            .replace(Regex("^Number \\d+:\\s*"), "")
            .substringBefore(" (20")
            .substringBefore(" (19")
            .trim()

        val imgAlt = img?.attr("alt")?.trim().orEmpty()
        val headingTitle = article?.selectFirst("h3, h4")?.text()?.trim().orEmpty()
        val title = ariaTitle.ifEmpty { imgAlt }.ifEmpty { headingTitle }.ifEmpty { rawText }

        if (title.isBlank() || title.lowercase() in ignoredLinkTexts) return null

        val poster: String? = img?.let {
            it.attr("src")
                .ifEmpty { it.attr("data-src") }
                .ifEmpty { it.attr("srcset").substringBefore(" ") }
        }

        val fixedUrl = if (href.startsWith("http")) href else "$mainUrl/${href.trimStart('/')}"
        val cfType = article?.attr("data-cf-type").orEmpty()

        val type = when {
            fixedUrl.contains("/anime/") -> TvType.Anime
            fixedUrl.contains("/dramas/") || fixedUrl.contains("/channel/") -> TvType.AsianDrama
            cfType.equals("tv", true) ||
                fixedUrl.contains("/tv/") ||
                fixedUrl.contains("/series/") ||
                fixedUrl.contains("/universe") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (type != TvType.Movie) {
            newTvSeriesSearchResponse(title, fixedUrl, type) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, fixedUrl, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val timeoutSec = currentTimeoutSec()
        val document = app.get("$mainUrl/search?q=$query", interceptor = cfKiller, timeout = timeoutSec).document
        val elements = document.select(
            "article[data-cf] a[aria-label][href], a[href*=/universes/], a[href*=/universe/], a[href*=/channel/]"
        ).ifEmpty {
            document.select("a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/], a[href*=/series/]")
        }
        return elements.mapNotNull { toSearchResult(it) }.distinctBy { it.url }
    }

    private fun extractSeriesId(url: String): String? {
        return Regex("-(?:movie|tv|dramas|anime|series)-(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: Regex("/(?:movie|tv|dramas|anime|series)/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)
            ?: Regex("(?:movie|tv|dramas|anime|series)-(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)
            ?: Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)
    }

    private fun extractUnescapedFlightText(rawHtml: String): String {
        val sb = StringBuilder()
        Regex("""self\.__next_f\.push\(\[\d+,"((?:\\.|[^"\\])*)"\]\)""").findAll(rawHtml).forEach { m ->
            val unescaped = runCatching {
                JSONObject("{\"v\":\"${m.groupValues[1]}\"}").getString("v")
            }.getOrDefault(m.groupValues[1])
            sb.append(unescaped).append('\n')
        }
        return if (sb.isNotEmpty()) sb.toString() else rawHtml
    }

    private fun extractJsonArrayAfterKey(text: String, key: String): String? {
        val keyIdx = text.indexOf("\"$key\":")
        if (keyIdx == -1) return null
        val start = text.indexOf('[', keyIdx + key.length + 2)
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        val timeoutSec = currentTimeoutSec()
        val responseText = app.get(url, interceptor = cfKiller, timeout = timeoutSec).text
        val document = Jsoup.parse(responseText, mainUrl)
        val flightText = extractUnescapedFlightText(responseText)

        val title = document.selectFirst("h1")?.text()?.trim()
            .orEmpty()
            .ifEmpty {
                Regex("\"title\":\"([^\"]+)\"").find(flightText)?.groupValues?.get(1).orEmpty()
            }
            .ifEmpty { document.select("title").text().substringBefore("—").trim() }

        val posterPath: String? = Regex("\"posterPath\":\"(/[^\"\\\\]+)\"").find(flightText)?.groupValues?.get(1)
        val poster: String? = if (!posterPath.isNullOrBlank()) {
            "https://image.tmdb.org/t/p/w500$posterPath"
        } else {
            document.select("main img, img.object-cover, img[data-nimg='fill']").firstOrNull()?.let {
                it.attr("src").ifEmpty { it.attr("data-src") }
            }
        }

        val jsonOverview = Regex("\"overview\":\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(flightText)?.groupValues?.get(1)
            ?.let { raw -> runCatching { JSONObject("{\"v\":\"$raw\"}").getString("v") }.getOrDefault(raw) }
            ?.trim()
            .orEmpty()

        val description = jsonOverview.ifEmpty {
            document.select("main p.max-w-3xl, main p")
                .map { it.text().trim() }
                .firstOrNull { text ->
                    text.length > 30 &&
                        !text.contains("MovieFox indexes third-party content", ignoreCase = true) &&
                        !text.contains("All rights reserved", ignoreCase = true)
                }.orEmpty()
        }

        val type = when {
            url.contains("/anime/") -> TvType.Anime
            url.contains("/dramas/") || url.contains("/channel/") -> TvType.AsianDrama
            url.contains("/tv/") || url.contains("/series/") || url.contains("/universe") -> TvType.TvSeries
            else -> TvType.Movie
        }

        val isTv = type != TvType.Movie

        return if (isTv) {
            val baseSeriesId = extractSeriesId(url)
            val baseUrlWithoutQuery = url.substringBefore("?")
            val episodes = mutableListOf<Episode>()

            extractJsonArrayAfterKey(flightText, "episodes")?.let { arrJson ->
                runCatching {
                    val arr = JSONArray(arrJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val sNum = obj.optInt("seasonNumber", -1)
                        val eNum = obj.optInt("episodeNumber", -1)
                        if (sNum <= 0 || eNum <= 0) continue

                        val epTitle = obj.optString("name").ifBlank { "Episode $eNum" }
                        val epOverview: String? = obj.optString("overview").takeIf { it.isNotBlank() }
                        val stillPath: String? = obj.optString("stillPath").takeIf { it.startsWith("/") }
                        val epThumb: String? = stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }

                        episodes.add(
                            newEpisode("$baseUrlWithoutQuery?season=$sNum&episode=$eNum") {
                                this.name = epTitle
                                this.season = sNum
                                this.episode = eNum
                                this.description = epOverview
                                this.posterUrl = epThumb
                            }
                        )
                    }
                }
            }

            extractJsonArrayAfterKey(flightText, "seasons")?.let { seasonsJson ->
                runCatching {
                    val arr = JSONArray(seasonsJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val sNum = obj.optInt("seasonNumber", -1)
                        val epCount = obj.optInt("episodeCount", 0)
                        if (sNum <= 0 || epCount <= 0) continue

                        for (eNum in 1..epCount) {
                            if (episodes.none { it.season == sNum && it.episode == eNum }) {
                                episodes.add(
                                    newEpisode("$baseUrlWithoutQuery?season=$sNum&episode=$eNum") {
                                        this.name = "Episode $eNum"
                                        this.season = sNum
                                        this.episode = eNum
                                    }
                                )
                            }
                        }
                    }
                }
            }

            document.select("a[href*='/watch/']").forEach { element ->
                val epHref = element.attr("href").trim()
                if (baseSeriesId != null) {
                    val linkId = extractSeriesId(epHref)
                    if (linkId != null && linkId != baseSeriesId) return@forEach
                }

                val sNum = Regex("[?&]s(?:eason)?=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                val eNum = Regex("[?&]e(?:pisode)?=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                if (sNum <= 0 || eNum <= 0) return@forEach

                if (episodes.none { it.season == sNum && it.episode == eNum }) {
                    val label = element.attr("aria-label")
                        .replace(Regex("^Watch episode \\d+:\\s*", RegexOption.IGNORE_CASE), "")
                        .trim()
                        .ifEmpty {
                            element.parent()?.selectFirst("p")?.text()
                                ?.replace(Regex("^\\d+\\.\\s*"), "")
                                ?.trim()
                                .orEmpty()
                        }
                        .ifEmpty { "Episode $eNum" }

                    val thumb: String? = element.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }

                    episodes.add(
                        newEpisode("$baseUrlWithoutQuery?season=$sNum&episode=$eNum") {
                            this.name = label
                            this.season = sNum
                            this.episode = eNum
                            this.posterUrl = thumb
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                for (s in 1..3) {
                    for (e in 1..8) {
                        episodes.add(
                            newEpisode("$baseUrlWithoutQuery?season=$s&episode=$e") {
                                this.name = "Episode $e"
                                this.season = s
                                this.episode = e
                            }
                        )
                    }
                }
            }

            val sortedEpisodes = episodes
                .distinctBy { Pair(it.season, it.episode) }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            newTvSeriesLoadResponse(title, url, type, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    data class StreamResponse(
        val streams: List<StreamItem> = emptyList(),
        val sources: List<StreamItem> = emptyList(),
        val url: String? = null,
        val file: String? = null,
        val streamUrl: String? = null,
        val bid: Long? = null,
        val domains: List<String>? = null
    )

    data class StreamItem(
        val url: String = "",
        val file: String = "",
        val type: String = "",
        val id: String? = null,
        val provider: String? = null,
        val label: String? = null,
        val quality: String? = null,
        val expiresAt: Long? = null,
        val headers: Map<String, String>? = null,
        val subtitles: List<SubtitleItem>? = null
    )

    data class SubtitleItem(
        val language: String = "",
        val lang: String = "",
        val label: String = "",
        val url: String = "",
        val file: String = ""
    )

    private fun getQuality(value: String?): Int {
        val quality = value?.uppercase().orEmpty()
        return when {
            quality.contains("2160") || quality.contains("4K") -> Qualities.P2160.value
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            else -> Qualities.P1080.value
        }
    }

    private fun normalizeUrl(rawUrl: String, baseHost: String = mainUrl): String {
        val trimmed = rawUrl.replace("\\/", "/").trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "${baseHost.trimEnd('/')}/${trimmed.trimStart('/')}"
        }
    }

    private fun isAdUrl(url: String): Boolean {
        return url.contains("backhoemoya", true) ||
            url.contains("/cx/", true) ||
            url.contains("?md=[", true) ||
            url.contains("exoclick", true) ||
            url.contains("popads", true) ||
            url.contains("propeller", true)
    }

    private fun isStreamOrHlsLink(url: String, type: ExtractorLinkType, typeHint: String = ""): Boolean {
        return type == ExtractorLinkType.M3U8 ||
            type == ExtractorLinkType.DASH ||
            typeHint.equals("hls", true) ||
            typeHint.equals("m3u8", true) ||
            typeHint.equals("dash", true) ||
            url.contains(".m3u8", true) ||
            url.contains(".mpd", true) ||
            url.contains("type=hls", true) ||
            url.contains("itsnitrox.tech", true) ||
            url.contains("nxstreamhub", true) ||
            url.contains("stream_proxy.php", true) ||
            url.contains("vibuxer.com", true) ||
            url.contains("workers.dev", true) ||
            url.contains("/media?ticket=", true) ||
            url.contains("/playlist", true) ||
            url.contains("/manifest", true) ||
            url.contains("/proxy", true)
    }

    private suspend fun isM3u8Stream(url: String, typeHint: String, headers: Map<String, String>, timeoutSec: Long): Boolean {
        if (isStreamOrHlsLink(url, ExtractorLinkType.M3U8, typeHint)) {
            return true
        }

        return runCatching {
            val res = app.get(url, headers = headers, interceptor = cfKiller, timeout = timeoutSec)
            val contentType = res.headers["Content-Type"].orEmpty()
            contentType.contains("mpegurl", true) || res.text.trimStart().startsWith("#EXTM3U")
        }.getOrDefault(false)
    }

    // Prmovies Helpers
    private fun prmoviesMutexFor(url: String): Mutex =
        prmoviesMutexMap.getOrPut(runCatching { Uri.parse(url).host }.getOrNull() ?: activePrmoviesBase) { Mutex() }

    private fun prmoviesKillerFor(url: String): CloudflareKiller =
        prmoviesCfMap.getOrPut(runCatching { Uri.parse(url).host }.getOrNull() ?: activePrmoviesBase) { CloudflareKiller() }

    private suspend fun fetchPrmoviesSingleUrl(targetUrl: String, referer: String? = null, timeoutSec: Long = 5L): NiceResponse? {
        return runCatching {
            val response = app.get(targetUrl, headers = prmoviesHeaders, referer = referer, timeout = timeoutSec)
            val resolved = if (response.code !in listOf(403, 503)) {
                response
            } else {
                prmoviesMutexFor(targetUrl).withLock {
                    val killer = prmoviesKillerFor(targetUrl)
                    val retry = app.get(targetUrl, headers = prmoviesHeaders, interceptor = killer, referer = referer, timeout = timeoutSec)
                    if (retry.code in listOf(403, 503)) {
                        killer.savedCookies.clear()
                        app.get(targetUrl, headers = prmoviesHeaders, interceptor = killer, referer = referer, timeout = timeoutSec)
                    } else {
                        retry
                    }
                }
            }
            if (resolved.code in 200..299) resolved else null
        }.getOrNull()
    }

    private suspend fun getPrmoviesPage(url: String, referer: String? = null, timeoutSec: Long = 5L): NiceResponse? {
        val matchedDomain = prmoviesMirrors.firstOrNull { url.startsWith(it, ignoreCase = true) }
            ?: return fetchPrmoviesSingleUrl(url, referer, timeoutSec)

        val candidates = (listOf(activePrmoviesBase.trimEnd('/')) + prmoviesMirrors).distinct()
        for (domain in candidates) {
            val candidateUrl = domain + url.substring(matchedDomain.length)
            val response = fetchPrmoviesSingleUrl(candidateUrl, referer, timeoutSec)
            if (response != null) {
                activePrmoviesBase = domain
                return response
            }
        }
        return null
    }

    private suspend fun resolvePrmoviesSpeedostream(
        embedUrl: String,
        label: String,
        seenUrls: MutableSet<String>,
        timeoutSec: Long,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val clean = normalizeUrl(embedUrl, activePrmoviesBase)
            if (clean.isBlank() || isAdUrl(clean)) return false
            val host = runCatching { Uri.parse(clean).host }.getOrNull() ?: "speedostream1.com"
            val r = getPrmoviesPage(clean, referer = "https://$host/", timeoutSec = timeoutSec) ?: return false
            if (!r.isSuccessful) return false
            val text = r.text
            if (text.contains("Embeds disabled", true)) return false

            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""sources\s*:\s*\[\s*\{?\s*file\s*:\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
            if (m3u8.isNullOrBlank()) return false

            val streamUrl = normalizeUrl(m3u8, "https://$host")
            if (isAdUrl(streamUrl) || !seenUrls.add(streamUrl)) return false

            callback(
                newExtractorLink(
                    source = "Prmovies",
                    name = label.take(40).ifBlank { "Prmovies" },
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://$host/"
                    this.headers = mapOf("User-Agent" to prmoviesUserAgent, "Referer" to "https://$host/")
                }
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun resolvePrmoviesVidsrc(
        embedUrl: String,
        referer: String,
        seenUrls: MutableSet<String>,
        timeoutSec: Long,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val clean = normalizeUrl(embedUrl, activePrmoviesBase)
            if (clean.isBlank() || isAdUrl(clean)) return false
            val r1 = getPrmoviesPage(clean, referer = referer, timeoutSec = timeoutSec) ?: return false
            val d1 = Jsoup.parse(r1.text)

            var target: String? = null
            d1.select("iframe[src]").forEach {
                val s = it.attr("src").trim()
                if (s.isNotBlank()) {
                    target = normalizeUrl(s, activePrmoviesBase)
                    return@forEach
                }
            }
            val playerUrl = target ?: return false
            if (!playerUrl.startsWith("http")) return false

            val r2 = getPrmoviesPage(playerUrl, referer = clean, timeoutSec = timeoutSec) ?: return false
            val text = r2.text

            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""file["']?\s*["']?["']?([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""https:[^"' ]+\.m3u8[^"' ]*""").find(text)?.groupValues?.get(0)
            if (m3u8.isNullOrBlank()) return false

            val streamUrl = normalizeUrl(m3u8, playerUrl)
            if (isAdUrl(streamUrl) || !seenUrls.add(streamUrl)) return false

            callback(
                newExtractorLink(
                    source = "Prmovies",
                    name = "Prmovies",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = playerUrl
                    this.headers = mapOf("User-Agent" to prmoviesUserAgent, "Referer" to playerUrl)
                }
            )
            true
        }.getOrDefault(false)
    }

    private suspend fun resolvePrmoviesPagePlayer(
        doc: Element,
        seenUrls: MutableSet<String>,
        timeoutSec: Long,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLink = false
        val tabLabels = mutableMapOf<String, String>()
        for (li in doc.select(".player_nav .idTabs li")) {
            val anchor = li.selectFirst("a[href^=#]") ?: continue
            val tabId = anchor.attr("href").removePrefix("#")
            val label = li.selectFirst(".les-content")?.text()?.trim()
            if (tabId.isNotBlank() && !label.isNullOrBlank()) tabLabels[tabId] = label
        }

        for (iframe in doc.select("#player2 iframe[src], #content-embed iframe[src], div.movieplay iframe[src]")) {
            val src = iframe.attr("src")
            if (src.contains("speedostream", true) && src.contains("embed-")) {
                val tabId = iframe.parents().firstOrNull { it.id().startsWith("tab") }?.id()
                val label = tabLabels[tabId] ?: "Prmovies"
                if (resolvePrmoviesSpeedostream(src, label, seenUrls, timeoutSec, callback)) foundLink = true
            }
        }

        if (!foundLink) {
            for (a in doc.select("#list-downloads a[href*=speedostream]")) {
                val href = a.attr("href")
                if (href.isBlank() || href.contains("embed-")) continue
                val label = a.selectFirst(".serv_tit")?.text()?.ifBlank { null } ?: "Prmovies"
                if (resolvePrmoviesSpeedostream(href, "$label • Download", seenUrls, timeoutSec, callback)) foundLink = true
            }
        }
        return foundLink
    }

    private suspend fun resolvePrmoviesEpisodePage(
        doc: Element,
        pageUrl: String,
        seenUrls: MutableSet<String>,
        timeoutSec: Long,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLink = false
        for (iframe in doc.select("#player2 iframe[src], div.movieplay iframe[src]")) {
            val src = iframe.attr("src")
            if (src.contains("vidsrc", true)) {
                if (resolvePrmoviesVidsrc(src, pageUrl, seenUrls, timeoutSec, callback)) foundLink = true
            } else if (src.contains("speedostream", true) && src.contains("embed-")) {
                if (resolvePrmoviesSpeedostream(src, "Prmovies", seenUrls, timeoutSec, callback)) foundLink = true
            }
        }
        return foundLink
    }

    // HindMoviez & VegaMovies Shared Helpers
    private fun normalizeTitleKey(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.lowercase()
            .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .replace(Regex("""(?i)\b(season|part|s)\s*\d+\b"""), " ")
            .replace(Regex("""(?i)\b(480p|720p|1080p|2160p|4k|web-?dl|webrip|bluray|hdrip|hevc|x264|x265|hindi|dubbed|dual\s*audio)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun titleMatchesCandidate(candidateTitle: String?, targetTitle: String): Boolean {
        val cNorm = normalizeTitleKey(candidateTitle)
        val tNorm = normalizeTitleKey(targetTitle)
        if (cNorm.isBlank() || tNorm.isBlank()) return false
        if (cNorm == tNorm || cNorm.contains(tNorm) || tNorm.contains(cNorm)) return true
        val tWords = tNorm.split(" ").filter { it.length > 1 }.toSet()
        val cWords = cNorm.split(" ").filter { it.length > 1 }.toSet()
        if (tWords.isEmpty() || cWords.isEmpty()) return false
        val overlap = tWords.intersect(cWords).size.toDouble() / tWords.size.toDouble()
        return overlap >= 0.75
    }

    private fun seasonsInText(text: String): Set<Int>? {
        val found = mutableSetOf<Int>()
        Regex("""(?i)\bseason\s*(\d+)\b""").findAll(text).forEach {
            it.groupValues[1].toIntOrNull()?.let { n -> found.add(n) }
        }
        Regex("""(?i)\bs(\d{1,2})(?=e\d|\b|\s|$)""").findAll(text).forEach {
            it.groupValues[1].toIntOrNull()?.let { n -> found.add(n) }
        }
        return found.takeIf { it.isNotEmpty() }
    }

    private fun baseHostOf(url: String): String {
        return try {
            val u = URI(url)
            val portPart = if (u.port in 1..65535) ":${u.port}" else ""
            "${u.scheme}://${u.host}$portPart"
        } catch (_: Exception) {
            url.substringBefore("/", url)
        }
    }

    private fun hostLower(url: String): String {
        return try {
            URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun b64DecodeSafe(value: String): String {
        var s = value.replace("-", "+").replace("_", "/")
        val rem = s.length % 4
        if (rem != 0) s += "=".repeat(4 - rem)
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseIndexQuality(info: String): Int {
        val low = info.lowercase()
        return when {
            low.contains("2160p") || low.contains("4k") -> Qualities.P2160.value
            low.contains("1080p") -> Qualities.P1080.value
            low.contains("720p") -> Qualities.P720.value
            low.contains("480p") -> Qualities.P480.value
            else -> Qualities.P1080.value
        }
    }

    private fun buildSourceLabel(siteTag: String, server: String, info: String): String {
        val cleanInfo = info.replace(Regex("""\s+"""), " ").trim()
        return when {
            server.isNotBlank() && cleanInfo.isNotBlank() -> "$siteTag • $server ($cleanInfo)"
            server.isNotBlank() -> "$siteTag • $server"
            cleanInfo.isNotBlank() -> "$siteTag ($cleanInfo)"
            else -> siteTag
        }.take(60)
    }

    // HindMoviez Helpers
    private fun hindmoviezHpageTarget(href: String): String? {
        val outer = href.substringAfter("url=", "").substringBefore("&")
        if (outer.isBlank()) return null
        val layer1 = b64DecodeSafe(outer)
        val inner = layer1.substringAfter("url=", "").substringBefore("&")
        if (inner.isBlank()) return null
        val target = b64DecodeSafe(inner)
        return target.takeIf { it.startsWith("http") }
    }

    private suspend fun hindmoviezGdshineTarget(shortId: String, timeoutSec: Long): String? {
        if (shortId.isBlank()) return null
        val fileId = runCatching {
            val text = app.get(
                "https://gdshine.org/api/files/s/$shortId",
                headers = mapOf("Referer" to "https://gdshine.org/", "User-Agent" to USER_AGENT),
                timeout = timeoutSec
            ).text
            JSONObject(text).optJSONObject("data")?.optString("id")
        }.getOrNull() ?: return null

        return runCatching {
            val text = app.get(
                "https://gdshine.org/api/gp/mirror/$fileId",
                headers = mapOf("Referer" to "https://gdshine.org/", "User-Agent" to USER_AGENT),
                timeout = timeoutSec
            ).text
            JSONObject(text).optJSONObject("data")?.optString("url")
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private fun hindmoviezEpisodeMatch(a: Element, targetSeason: Int, targetEpisode: Int): Boolean {
        val fileName = Uri.decode(a.attr("href").substringAfter("?id=", ""))
        val sxxexx = Regex("(?i)S0*$targetSeason\\s*[-–]?\\s*E0*$targetEpisode(?!\\d)")
        val anyMarker = Regex("(?i)\\bS\\d{1,2}\\s*[-–]?\\s*E\\d{1,3}\\b")
        return when {
            anyMarker.containsMatchIn(fileName) -> sxxexx.containsMatchIn(fileName)
            else -> Regex("(?i)Episode\\s*:?\\s*0*$targetEpisode(?!\\d)").containsMatchIn(a.text())
        }
    }

    // VegaMovies Helpers
    private data class VegaDoc(
        val postTitle: String? = null,
        val permalink: String? = null,
        val domain: String = ""
    )

    private class VegaQualityGroup(
        val label: String,
        val links: MutableList<Pair<String, String>> = mutableListOf()
    )

    private class VegaSeasonBlock(val title: String, val number: Int) {
        val groups: MutableList<VegaQualityGroup> = mutableListOf()
    }

    private class VegaPostDetail(
        val seasons: List<VegaSeasonBlock>,
        val movieGroups: List<VegaQualityGroup>
    )

    private class VegaDrivePage(
        val servers: List<Pair<String, String>>,
        val episodes: Map<Int, List<Pair<String, String>>>
    )

    private data class VegaServer(val name: String, val url: String)

    private fun isVegaServerLink(href: String): Boolean {
        if (vegaDeadHosts.containsMatchIn(href)) return false
        return href.startsWith("http") && vegaButtonHosts.containsMatchIn(href)
    }

    private suspend fun searchVegaPosts(domain: String, query: String, timeoutSec: Long): List<VegaDoc> {
        val url = "$domain/ts-search.php?q=${Uri.encode(query)}&page=1"
        val text = runCatching {
            val res = app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$domain/"),
                interceptor = cfKiller,
                timeout = timeoutSec
            )
            if (res.code == 200) res.text else null
        }.getOrNull() ?: return emptyList()

        return runCatching {
            JSONObject(text).optJSONArray("hits")?.let { hits ->
                (0 until hits.length()).mapNotNull { i ->
                    hits.optJSONObject(i)?.optJSONObject("document")?.let { d ->
                        val title = d.optString("post_title")
                        val link = d.optString("permalink")
                        if (title.isBlank() || link.isBlank()) null
                        else VegaDoc(title, link, domain)
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun pickVegaPost(posts: List<VegaDoc>, targetTitle: String, isTv: Boolean, targetSeason: Int): VegaDoc? {
        val matched = posts.filter { titleMatchesCandidate(it.postTitle, targetTitle) }
        if (matched.isEmpty()) return null
        return if (isTv) {
            val seasonPosts = matched.filter {
                seasonsInText(it.postTitle.orEmpty())?.contains(targetSeason) == true
            }
            seasonPosts.firstOrNull() ?: matched.firstOrNull { seasonsInText(it.postTitle.orEmpty()) == null } ?: matched.first()
        } else {
            matched.first()
        }
    }

    private fun collectVegaButtons(scope: Element, group: VegaQualityGroup) {
        for (a in scope.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!isVegaServerLink(href) || vegaJunk.containsMatchIn(href)) continue
            val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                ?: a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank() || Regex("""(?i)batch|zip\b""").containsMatchIn(label)) continue
            group.links.add(label to href)
        }
    }

    private fun parseVegaPost(html: String): VegaPostDetail? {
        val doc = Jsoup.parse(html)
        val content = doc.selectFirst(".entry-content, .post-content, article") ?: doc
        val qualityRegex = Regex("""\b(480p|720p|1080p|2160p|4K|60FPS)\b""", RegexOption.IGNORE_CASE)

        fun groupsAfter(head: Element): MutableList<VegaQualityGroup> {
            val out = mutableListOf<VegaQualityGroup>()
            var sib = head.nextElementSibling()
            while (sib != null) {
                val tag = sib.tagName()
                if (tag == "h1" || tag == "h2") break
                if (tag == "h3" || tag == "h4" || tag == "h5") {
                    val label = sib.text().replace(Regex("""\s+"""), " ").trim()
                    if (label.isNotBlank() && qualityRegex.containsMatchIn(label)) {
                        val group = VegaQualityGroup(label)
                        var p = sib.nextElementSibling()
                        while (p != null && !listOf("h1", "h2", "h3", "h4", "h5").contains(p.tagName())) {
                            collectVegaButtons(p, group)
                            p = p.nextElementSibling()
                        }
                        if (group.links.isNotEmpty()) out.add(group)
                    }
                }
                sib = sib.nextElementSibling()
            }
            return out
        }

        val seasons = mutableListOf<VegaSeasonBlock>()
        val seasonHeads = content.select("h1, h2").filter { el ->
            Regex("""(?i)season\s*\d+|part\s*\d+""").containsMatchIn(el.text())
        }
        for (head in seasonHeads) {
            val num = Regex("""(?i)season\s*(\d+)""").find(head.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?i)part\s*(\d+)""").find(head.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val block = VegaSeasonBlock(head.text().replace(Regex("""\s+"""), " ").trim(), num)
            block.groups.addAll(groupsAfter(head))
            if (block.groups.isNotEmpty()) seasons.add(block)
        }

        val movieGroups = mutableListOf<VegaQualityGroup>()
        if (seasons.isEmpty()) {
            for (head in content.select("h3, h4, h5")) {
                val label = head.text().replace(Regex("""\s+"""), " ").trim()
                if (label.isBlank() || !qualityRegex.containsMatchIn(label)) continue
                val group = VegaQualityGroup(label)
                var sib = head.nextElementSibling()
                while (sib != null && !listOf("h1", "h2", "h3", "h4", "h5").contains(sib.tagName())) {
                    collectVegaButtons(sib, group)
                    sib = sib.nextElementSibling()
                }
                if (group.links.isNotEmpty()) movieGroups.add(group)
            }
        }
        if (seasons.isEmpty() && movieGroups.isEmpty()) return null
        return VegaPostDetail(seasons, movieGroups)
    }

    private suspend fun fetchVegaDrivePage(url: String, timeoutSec: Long): VegaDrivePage? {
        vegaDriveCache[url]?.let { return it }
        if (vegaDriveCache.size > 40) vegaDriveCache.clear()
        val res = runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://nexdrive.beer/"),
                interceptor = cfKiller,
                timeout = timeoutSec
            )
        }.getOrNull() ?: return null

        val html = res.text
        if (Regex("""(?i)file (is )?deleted|not found""").containsMatchIn(html)) return null
        val doc = Jsoup.parse(html)
        val scope = doc.selectFirst(".entry, article, .post-inner") ?: doc

        val servers = mutableListOf<Pair<String, String>>()
        for (a in scope.select("a[href]")) {
            val href = a.attr("href").trim()
            val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                ?: a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank() || !isVegaServerLink(href)) continue
            if (vegaJunk.containsMatchIn(href) || vegaJunk.containsMatchIn(label)) continue
            if (Regex("""(?i)batch|zip\b""").containsMatchIn(label)) continue
            servers.add(label to href)
        }

        val episodes = LinkedHashMap<Int, List<Pair<String, String>>>()
        for (head in scope.select("h3, h4, h5")) {
            val num = Regex("""(?i)episodes?\s*:?\s*0*(\d{1,3})""").find(head.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val links = mutableListOf<Pair<String, String>>()
            var sib = head.nextElementSibling()
            while (sib != null && !listOf("h3", "h4", "h5").contains(sib.tagName())) {
                for (a in sib.select("a[href]")) {
                    val href = a.attr("href").trim()
                    val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                        ?: a.text().replace(Regex("""\s+"""), " ").trim()
                    if (label.isBlank() || !isVegaServerLink(href) || vegaJunk.containsMatchIn(href)) continue
                    links.add(label to href)
                }
                sib = sib.nextElementSibling()
            }
            if (links.isNotEmpty() && !episodes.containsKey(num)) {
                episodes[num] = links
            }
        }
        return VegaDrivePage(servers.distinctBy { it.second }, episodes).also { vegaDriveCache[url] = it }
    }

    private fun vegaServerNameOf(label: String, href: String): String? {
        val low = label.lowercase()
        val h = href.lowercase()
        return when {
            low.contains("fslv2") || low.contains("fsl v2") || low.contains("fsl2") -> "FSLv2"
            low.contains("fsl") -> "FSL"
            low.contains("pixelserver") || low.contains("pixel server") || low.contains("pixeldrain") -> "PixelDrain"
            low.contains("gofile") -> "GoFile"
            low.contains("10gbps") -> "10Gbps"
            low.contains("buzzserver") -> "BuzzServer"
            low.contains("instant download") || low.contains("instant dl") -> "Instant Download"
            low.contains("vegadrive") || low.contains("v-drive") || low.contains("skydrop") -> "V-Drive"
            low.contains("vcloud") || low.contains("v-cloud") -> "V-Cloud"
            low.contains("g-direct") || low.contains("gdirect") || low.contains("fastdl") -> "G-Direct"
            low.contains("filepress") || low.contains("filebee") -> "Filepress"
            h.contains(".r2.cloudflarestorage.com") -> "FSLv2"
            h.contains(".r2.dev") -> "FSL"
            h.contains("pixeldrain") -> "PixelDrain"
            h.contains("gpdl") -> "10Gbps"
            else -> null
        }
    }

    private fun vegaPixelApi(href: String): String {
        val id = href.substringBefore("?").substringBefore("#").substringAfterLast("/")
        return "${baseHostOf(href)}/api/file/$id"
    }

    private suspend fun vegaGpdlDirect(url: String, timeoutSec: Long): String? {
        var cur = url
        return runCatching {
            for (i in 0 until 5) {
                val res = app.get(cur, headers = mapOf("User-Agent" to USER_AGENT), allowRedirects = false, timeout = timeoutSec)
                val loc = res.headers["location"]
                if (loc.isNullOrBlank() || res.code !in 301..308) break
                cur = if (loc.startsWith("http")) loc else baseHostOf(cur) + loc
            }
            for (pair in cur.substringAfter("?", "").split("&")) {
                if (pair.substringBefore("=") != "link") continue
                val value = pair.substringAfter("=")
                val direct = if (value.contains("%")) URLDecoder.decode(value, "UTF-8") else value
                return@runCatching direct.takeIf { it.startsWith("http") }
            }
            null
        }.getOrNull()
    }

    private suspend fun vegaGofileDirect(url: String, timeoutSec: Long): String? {
        val code = url.substringAfterLast("/").substringBefore("#").substringBefore("?")
        if (code.isBlank()) return null
        return runCatching {
            val api = "https://api.gofile.io"
            val token = JSONObject(app.post("$api/accounts", timeout = timeoutSec).text)
                .getJSONObject("data").getString("token")
            val wt = runCatching {
                Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                    app.get("$api/dist/js/global.js", timeout = timeoutSec).text
                )?.groupValues?.get(1)
            }.getOrNull()
            val contentUrl = if (wt.isNullOrBlank()) "$api/contents/$code?wt=$token" else "$api/contents/$code?wt=$wt"
            val data = JSONObject(
                app.get(contentUrl, headers = mapOf("Authorization" to "Bearer $token"), timeout = timeoutSec).text
            ).getJSONObject("data")
            val children = data.optJSONObject("children") ?: return@runCatching null
            var best: String? = null
            var bestSize = -1L
            for (key in children.keys()) {
                val child = children.optJSONObject(key) ?: continue
                if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(child.optString("name"))) continue
                val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
                if (child.optLong("size", 0L) > bestSize) {
                    bestSize = child.optLong("size", 0L)
                    best = link
                }
            }
            best
        }.getOrNull()
    }

    private suspend fun vegaFastdlDirect(url: String, timeoutSec: Long): String? {
        return runCatching {
            val res = app.get(url, headers = mapOf("User-Agent" to USER_AGENT), timeout = timeoutSec)
            val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(res.text)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(res.text)?.groupValues?.get(1)
                ?: return@runCatching null
            val carrier = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(reurl)?.groupValues?.get(1)?.let { wrapped ->
                b64DecodeSafe(wrapped)
            } ?: reurl
            val encoded = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1)
                ?: return@runCatching null
            URLDecoder.decode(encoded, "UTF-8").takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    private suspend fun vegaFilepressDirect(url: String, timeoutSec: Long): String? {
        val id = Regex("""/file/([a-f0-9]{16,40})""").find(url)?.groupValues?.get(1) ?: return null
        val base = "https://filebee.xyz"

        suspend fun postTask(method: String, targetId: String): Any? {
            return runCatching {
                val endpoint = if (method == "indexDownlaod" && targetId != id) "$base/api/file/downlaod2/" else "$base/api/file/downlaod/"
                val res = app.post(
                    endpoint,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$base/",
                        "Origin" to base,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                    json = JSONObject()
                        .put("captchaValue", JSONObject.NULL)
                        .put("id", targetId)
                        .put("method", method)
                        .toString(),
                    timeout = timeoutSec
                )
                if (!res.isSuccessful) return@runCatching null
                val parsed = JSONObject(res.text)
                if (!parsed.optBoolean("status")) return@runCatching null
                parsed.opt("data")
            }.getOrNull()
        }

        val share = postTask("dotFlixDownlaod", id) as? String
        if (!share.isNullOrBlank() && share.startsWith("http")) {
            val dotRes = runCatching {
                val pageText = app.get(
                    share,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://new3.dotflix.shop/"),
                    timeout = timeoutSec
                ).text
                val code = Regex("""btoa\('([^']+)'\)""").find(pageText)?.groupValues?.get(1) ?: return@runCatching null
                val obfuscated = java.util.Base64.getEncoder().encodeToString(code.toByteArray()).reversed()
                val requestId = List(13) { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                val timestamp = System.currentTimeMillis().toString()
                val extractRes = app.post(
                    "https://dotflix.fit/api/extract-download",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://new3.dotflix.shop/",
                        "Origin" to "https://new3.dotflix.shop",
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "X-Request-ID" to requestId,
                        "X-Timestamp" to timestamp
                    ),
                    json = JSONObject()
                        .put("requestId", requestId)
                        .put("timestamp", timestamp)
                        .put("data", obfuscated)
                        .toString(),
                    timeout = timeoutSec
                )
                if (!extractRes.isSuccessful) return@runCatching null
                val parsed = JSONObject(extractRes.text)
                if (!parsed.optBoolean("success")) null else parsed.optString("downloadUrl").takeIf { it.startsWith("http") }
            }.getOrNull()
            if (dotRes != null) return dotRes
        }

        val task = postTask("indexDownlaod", id) as? String
        if (!task.isNullOrBlank() && task.matches(Regex("[a-f0-9]{16,40}"))) {
            return when (val finalData = postTask("indexDownlaod", task)) {
                is String -> finalData.takeIf { it.startsWith("http") }
                is JSONArray -> (0 until finalData.length())
                    .firstNotNullOfOrNull { idx -> finalData.optString(idx).takeIf { it.startsWith("http") } }
                else -> null
            }
        }
        return null
    }

    private suspend fun vegaVegadriveSkydrop(url: String, timeoutSec: Long): VegaServer? {
        val page = runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://nexdrive.beer/"),
                timeout = timeoutSec
            )
        }.getOrNull() ?: return null
        val base = baseHostOf(page.url)
        val token = url.substringAfter("/s/").substringBefore("?").substringBefore("#")
            .substringAfter("/d/").substringBefore("/")
        if (token.isBlank() || token == url) return null
        for (attempt in 0 until 3) {
            val res = runCatching {
                app.get(
                    "$base/download-link/$token/skydrop",
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$base/s/$token"),
                    timeout = timeoutSec
                )
            }.getOrNull() ?: return null
            val parsed = runCatching { JSONObject(res.text) }.getOrNull()
            val link = parsed?.optString("download_url").orEmpty()
            if (res.code == 200 && link.startsWith("http")) return VegaServer("V-Drive", link)
            if (res.code != 202) return null
            delay((parsed?.optInt("retry_after_ms", 2500) ?: 2500).toLong().coerceAtMost(3000L))
        }
        return null
    }

    private suspend fun vegaAddServer(out: MutableList<VegaServer>, sName: String, href: String, timeoutSec: Long) {
        when (sName) {
            "10Gbps" -> vegaGpdlDirect(href, timeoutSec)?.let { out.add(VegaServer(sName, it)) }
            "GoFile" -> vegaGofileDirect(href, timeoutSec)?.let { out.add(VegaServer(sName, it)) }
            "PixelDrain" -> out.add(VegaServer(sName, vegaPixelApi(href)))
            else -> out.add(VegaServer(sName, href))
        }
    }

    private suspend fun vegaVcloudServers(url: String, timeoutSec: Long): List<VegaServer> {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://nexdrive.beer/",
            "Cookie" to "xla=s4t"
        )
        val first = runCatching { app.get(url, headers = headers, timeout = timeoutSec).text }.getOrNull() ?: return emptyList()
        val tokenRegex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]""")
        val plainRegex = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")

        var target = tokenRegex.find(first)?.groupValues?.get(1)
            ?.let { runCatching { base64Decode(base64Decode(it.trim())) }.getOrNull() }
            ?: plainRegex.find(first)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        var fromUrl = url

        if (target == null || !target.startsWith("http")) {
            val hop = Jsoup.parse(first).selectFirst("div.main h4 a")?.attr("href")?.trim() ?: return emptyList()
            fromUrl = if (hop.startsWith("http")) hop else baseHostOf(url) + hop
            val hopHtml = runCatching {
                app.get(fromUrl, headers = headers + mapOf("Referer" to url), timeout = timeoutSec).text
            }.getOrNull() ?: return emptyList()
            target = tokenRegex.find(hopHtml)?.groupValues?.get(1)
                ?.let { runCatching { base64Decode(base64Decode(it.trim())) }.getOrNull() }
                ?: plainRegex.find(hopHtml)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        }
        val resolvedTarget = target?.takeIf { it.startsWith("http") } ?: return emptyList()

        val html = runCatching {
            app.get(resolvedTarget, headers = headers + mapOf("Referer" to fromUrl), timeout = timeoutSec).text
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<VegaServer>()
        for (a in Jsoup.parse(html).select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http") || vegaJunk.containsMatchIn(href)) continue
            val label = a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank()) continue
            val sName = vegaServerNameOf(label, href) ?: continue
            vegaAddServer(out, sName, href, timeoutSec)
        }
        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") && it.contains("pixeldrain") }
            ?.let { vegaAddServer(out, "PixelDrain", it, timeoutSec) }

        return out.distinctBy { it.url }
    }

    private suspend fun vegaHubcloudServers(url: String, timeoutSec: Long): List<VegaServer> {
        val res = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT), timeout = timeoutSec)
        }.getOrNull() ?: return emptyList()
        val doc = Jsoup.parse(res.text)
        val header = doc.selectFirst("div.card-header")?.text()?.trim().orEmpty()
        if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(header)) return emptyList()
        val generate = doc.select("a.btn, a[download]")
            .firstOrNull { it.text().contains("generate", true) }
            ?.attr("href")?.trim() ?: return emptyList()
        val genUrl = if (generate.startsWith("http")) generate else baseHostOf(res.url) + generate
        val genRes = runCatching {
            app.get(genUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to res.url), timeout = timeoutSec)
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<VegaServer>()
        for (a in Jsoup.parse(genRes.text).select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http") || vegaJunk.containsMatchIn(href)) continue
            val label = a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank()) continue
            val sName = vegaServerNameOf(label, href) ?: continue
            vegaAddServer(out, sName, href, timeoutSec)
        }
        return out.distinctBy { it.url }
    }

    private suspend fun resolveVegaHref(href: String, timeoutSec: Long): List<VegaServer> {
        if (!href.startsWith("http") || vegaDeadHosts.containsMatchIn(href)) return emptyList()
        val appCtx = CloudStreamApp.context
        val host = hostLower(href)

        val subKey = when {
            host.contains("vcloud") -> "vcloud"
            host.contains("hubcloud") -> "hubcloud"
            host.contains("gofile") -> "gofile"
            href.contains(".r2.cloudflarestorage.com", true) -> "fsl2"
            href.contains(".r2.dev", true) -> "fsl"
            href.contains("pixeldrain", true) -> "pixeldrain"
            else -> null
        }

        if (subKey != null && appCtx != null && !MovieFoxSettingsDialog.isServerEnabled(appCtx, subKey)) {
            return emptyList()
        }

        val start = System.currentTimeMillis()
        val result = runCatching {
            when {
                host.contains("vcloud") -> vegaVcloudServers(href, timeoutSec)
                host.contains("vegadrive") -> listOfNotNull(vegaVegadriveSkydrop(href, timeoutSec))
                host.contains("fastdl") -> vegaFastdlDirect(href, timeoutSec)?.let { listOf(VegaServer("G-Direct", it)) } ?: emptyList()
                host.contains("filebee") || host.contains("filepress") || host.contains("fpgo") ->
                    vegaFilepressDirect(href, timeoutSec)?.let { listOf(VegaServer("Filepress", it)) } ?: emptyList()
                host.contains("gofile") -> vegaGofileDirect(href, timeoutSec)?.let { listOf(VegaServer("GoFile", it)) } ?: emptyList()
                host.contains("gpdl") -> vegaGpdlDirect(href, timeoutSec)?.let { listOf(VegaServer("10Gbps", it)) } ?: emptyList()
                host.contains("hubcloud") -> vegaHubcloudServers(href, timeoutSec)
                href.contains(".r2.cloudflarestorage.com", true) -> listOf(VegaServer("FSLv2", href))
                href.contains(".r2.dev", true) -> listOf(VegaServer("FSL", href))
                href.contains("pixeldrain", true) -> listOf(VegaServer("PixelDrain", vegaPixelApi(href)))
                else -> emptyList()
            }
        }.getOrDefault(emptyList())

        if (subKey != null) {
            MovieFoxSettingsDialog.reportServerResult(
                appCtx,
                subKey,
                success = result.isNotEmpty(),
                latencyMs = System.currentTimeMillis() - start
            )
        }
        return result
    }

    private data class QueuedLink(
        val link: ExtractorLink,
        val isStream: Boolean
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val appCtx = CloudStreamApp.context
            val timeoutSec = appCtx?.let { MovieFoxSettingsDialog.getTimeoutSeconds(it).toLong().coerceAtLeast(3L) } ?: 5L
            val streamOnly = appCtx?.let { MovieFoxSettingsDialog.isStreamOnly(it) } ?: false
            val downloadOnly = appCtx?.let { MovieFoxSettingsDialog.isDownloadOnly(it) } ?: false
            val maxConcurrency = appCtx?.let { MovieFoxSettingsDialog.getParallelServers(it) } ?: 14

            val cleanPageUrl = data.substringBefore("?")
            val rawHtml = app.get(cleanPageUrl, interceptor = cfKiller, timeout = timeoutSec).text
            val document = Jsoup.parse(rawHtml, mainUrl)
            val flightText = extractUnescapedFlightText(rawHtml)

            val pageTitle = document.selectFirst("h1")?.text()?.trim()
                .orEmpty()
                .ifEmpty { Regex("\"title\":\"([^\"]+)\"").find(flightText)?.groupValues?.get(1).orEmpty() }
                .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
                .trim()

            val urlId = extractSeriesId(cleanPageUrl)
            val tmdbId = Regex("\"tmdbId\":(\\d+)").find(flightText)?.groupValues?.get(1)
            val jsonId = Regex("\"id\":(\\d+)").find(flightText)?.groupValues?.get(1)
            val candidateIds = listOfNotNull(tmdbId, urlId, jsonId).distinct()
            val primaryId = candidateIds.firstOrNull() ?: return false

            val imdbId = Regex("\"imdbId\":\"(tt\\d+)\"").find(flightText)?.groupValues?.get(1)
                ?: Regex("tt\\d+").find(rawHtml)?.value.orEmpty()

            val isTv = cleanPageUrl.contains("/tv/") ||
                cleanPageUrl.contains("/dramas/") ||
                cleanPageUrl.contains("/anime/") ||
                cleanPageUrl.contains("/series/") ||
                data.contains("season=", true)
            val contentType = if (isTv) "tv" else "movie"

            val season = Regex("[?&]s(?:eason)?=(\\d+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1) ?: "1"
            val episode = Regex("[?&]e(?:pisode)?=(\\d+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1) ?: "1"

            val tvQuery = if (isTv) "&season=$season&episode=$episode" else ""
            val imdbQuery = if (imdbId.isNotBlank()) "&imdbId=$imdbId" else ""

            val watchUrl = if (isTv) {
                "$mainUrl/watch/tv-$primaryId?s=$season&e=$episode"
            } else {
                "$mainUrl/watch/movie-$primaryId"
            }

            val allEndpoints = mutableListOf<Pair<String, String>>()
            for (cid in candidateIds) {
                allEndpoints.add("purstream" to "$mainUrl/api/stream/purstream?type=$contentType&id=$cid$imdbQuery$tvQuery")
                allEndpoints.add("castle" to "$mainUrl/api/stream/castle?type=$contentType&id=$cid$imdbQuery$tvQuery")
                allEndpoints.add("watchout" to "$mainUrl/api/stream/watchout?type=$contentType&id=$cid$imdbQuery$tvQuery")
                allEndpoints.add("direct" to "$mainUrl/api/stream/direct?type=$contentType&id=$cid$imdbQuery$tvQuery")
                allEndpoints.add("debrid" to "$mainUrl/api/stream/debrid?type=$contentType&id=$cid$imdbQuery$tvQuery")
                allEndpoints.add("vidsrc" to "$mainUrl/api/stream/vidsrc?type=$contentType&id=$cid$imdbQuery$tvQuery")
                allEndpoints.add("autoembed" to "$mainUrl/api/stream/autoembed?type=$contentType&id=$cid$imdbQuery$tvQuery")
                allEndpoints.add("hls" to "$mainUrl/api/stream/hls?type=$contentType&id=$cid$imdbQuery$tvQuery")
                allEndpoints.add("fsl" to "$mainUrl/api/stream/fsl?type=$contentType&id=$cid$imdbQuery$tvQuery")
                allEndpoints.add("fsl2" to "$mainUrl/api/stream/fsl2?type=$contentType&id=$cid$imdbQuery$tvQuery")
                allEndpoints.add("gdrive" to "$mainUrl/api/stream/gdrive?type=$contentType&id=$cid$imdbQuery$tvQuery")
            }

            val activeEndpoints = allEndpoints
                .distinctBy { it.second }
                .filter { (key, _) -> appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, key) } ?: true }
                .let { list ->
                    if (streamOnly) {
                        list.sortedByDescending { (key, _) ->
                            when (key) {
                                "hls", "purstream", "castle", "watchout", "vidsrc", "autoembed" -> 1
                                else -> 0
                            }
                        }
                    } else {
                        list
                    }
                }
                .take(maxConcurrency * candidateIds.size)

            val baseHeaders = mapOf(
                "Referer" to watchUrl,
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/plain, */*"
            )

            val seenUrls = mutableSetOf<String>()
            val collectedLinks = mutableListOf<QueuedLink>()
            var found = false

            suspend fun recordLink(extractorLink: ExtractorLink, isStream: Boolean) {
                if (downloadOnly && isStream) return
                collectedLinks.add(QueuedLink(extractorLink, isStream))
                found = true
            }

            suspend fun emitServerLink(
                serverName: String,
                streamUrl: String,
                isHls: Boolean,
                qualityStr: String? = null,
                customHeaders: Map<String, String> = baseHeaders
            ) {
                if (isAdUrl(streamUrl) || !seenUrls.add(streamUrl)) return
                if (downloadOnly && isHls) return

                val link = newExtractorLink(
                    source = serverName,
                    name = if (streamOnly && isHls) "⚡ $serverName" else serverName,
                    url = streamUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = customHeaders["Referer"] ?: watchUrl
                    this.quality = getQuality(qualityStr)
                    this.headers = customHeaders
                }
                recordLink(link, isStream = isHls)
            }

            suspend fun extractHostWithQueue(hostUrl: String, refUrl: String): Boolean {
                val tempList = mutableListOf<ExtractorLink>()
                val ok = runCatching {
                    loadExtractor(hostUrl, refUrl, subtitleCallback) { ex ->
                        tempList.add(ex)
                    }
                }.getOrDefault(false)
                for (ex in tempList) {
                    recordLink(ex, isStream = isStreamOrHlsLink(ex.url, ex.type))
                }
                return ok || tempList.isNotEmpty()
            }

            // 1. Built-in MovieFox API Endpoints
            for ((endpointName, apiUrl) in activeEndpoints) {
                val tStart = System.currentTimeMillis()
                val response = runCatching {
                    app.get(apiUrl, headers = baseHeaders, interceptor = cfKiller, timeout = timeoutSec)
                }.getOrNull()

                if (response == null || response.text.isBlank()) {
                    MovieFoxSettingsDialog.reportServerResult(appCtx, endpointName, false)
                    continue
                }
                MovieFoxSettingsDialog.reportServerResult(appCtx, endpointName, true, System.currentTimeMillis() - tStart)

                val rawText = response.text
                val contentTypeHeader = response.headers["Content-Type"].orEmpty()
                if (
                    contentTypeHeader.contains("mpegurl", true) ||
                    rawText.trimStart().startsWith("#EXTM3U")
                ) {
                    emitServerLink(
                        serverName = "MovieFox ${endpointName.uppercase()} HLS",
                        streamUrl = apiUrl,
                        isHls = true,
                        customHeaders = baseHeaders
                    )
                    continue
                }

                val parsed = runCatching { parseJson<StreamResponse>(rawText) }.getOrNull() ?: continue
                if (parsed.bid != null || !parsed.domains.isNullOrEmpty()) continue

                val allStreams = buildList {
                    addAll(parsed.streams)
                    addAll(parsed.sources)
                    val fallbackSingle = parsed.url ?: parsed.file ?: parsed.streamUrl
                    if (!fallbackSingle.isNullOrBlank()) {
                        add(StreamItem(url = fallbackSingle, provider = endpointName))
                    }
                }

                if (allStreams.isEmpty()) continue

                for (stream in allStreams) {
                    val rawStreamUrl = stream.url.ifBlank { stream.file }.trim()
                    if (rawStreamUrl.isBlank()) continue

                    val streamUrl = normalizeUrl(rawStreamUrl)
                    if (isAdUrl(streamUrl)) continue

                    stream.subtitles?.forEach { sub ->
                        val rawSubUrl = sub.url.ifBlank { sub.file }.trim()
                        if (rawSubUrl.isNotBlank()) {
                            subtitleCallback(
                                newSubtitleFile(
                                    lang = sub.label.ifBlank {
                                        sub.language.ifBlank {
                                            sub.lang.ifBlank { "English" }
                                        }
                                    },
                                    url = normalizeUrl(rawSubUrl)
                                )
                            )
                        }
                    }

                    val isKnownHost = listOf(
                        "voe.", "pixeldrain.", "streamtape.", "shavetape.", "strcloud.",
                        "strtape.", "stape.fun", "luluvid.", "lulustream.", "luluvdo.",
                        "vidara.", "vidprime.", "upbolt.", "abyssplayer.", "abysscdn.",
                        "short.icu", "lplaygo.", "vidmoly.", "earnvids.", "dingtezuni.",
                        "rabbitstream.", "upcloud.", "megacloud.", "streamwish.", "filemoon.",
                        "drive.google.com"
                    ).any { streamUrl.contains(it, true) }

                    if (isKnownHost) {
                        val embedsEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "embeds") } ?: true
                        if (embedsEnabled && seenUrls.add(streamUrl)) {
                            if (extractHostWithQueue(streamUrl, watchUrl)) found = true
                        }
                        continue
                    }

                    val mergedHeaders = baseHeaders + (stream.headers ?: emptyMap())
                    val isHls = isM3u8Stream(streamUrl, stream.type, mergedHeaders, timeoutSec)

                    val serverTitle = when {
                        !stream.provider.isNullOrBlank() -> stream.provider
                        streamUrl.contains("itsnitrox.tech", true) -> "Nitrox"
                        streamUrl.contains("nxstreamhub", true) -> "NxStreamHub"
                        streamUrl.contains("vibuxer.com", true) -> "Vibuxer"
                        streamUrl.contains("workers.dev", true) -> "WorkerHLS"
                        else -> endpointName.uppercase()
                    }

                    val finalLabel = stream.label?.takeIf { it.isNotBlank() }
                        ?: "$serverTitle (${stream.quality ?: "HD"})"

                    emitServerLink(
                        serverName = finalLabel,
                        streamUrl = streamUrl,
                        isHls = isHls,
                        qualityStr = stream.quality,
                        customHeaders = mergedHeaders
                    )
                }
            }

            // 2. Embed Servers & Worker HLS from Watch Page & Detail Page
            val embedsEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "embeds") } ?: true
            if (embedsEnabled) {
                val watchHtml = runCatching {
                    app.get(watchUrl, headers = baseHeaders, interceptor = cfKiller, timeout = timeoutSec).text
                }.getOrNull().orEmpty()

                val watchFlight = if (watchHtml.isNotBlank()) {
                    extractUnescapedFlightText(watchHtml)
                } else {
                    ""
                }

                val combinedScanText = "$flightText\n$watchFlight"

                val hostScanRegex = Regex(
                    """https?://(?:[a-zA-Z0-9.-]+\.)?(?:voe\.(?:sx|to|co)|pixeldrain\.(?:com|dev)|streamtape\.(?:com|to|net|xyz)|shavetape\.cash|strcloud\.link|strtape\.tech|stape\.fun|luluvid\.com|lulustream\.com|luluvdo\.com|vidara\.(?:to|so)|vidprime\.top|upbolt\.(?:to|tv)|abysscdn\.com|short\.icu|lplaygo\.com|vidmoly\.(?:me|to)|earnvids\.com|dingtezuni\.com|rabbitstream\.net|upcloud\.to|megacloud\.(?:tv|club)|streamwish\.to|filemoon\.sx|drive\.google\.com|workers\.dev)/[^\s"'\\]+""",
                    RegexOption.IGNORE_CASE
                )

                for (m in hostScanRegex.findAll(combinedScanText)) {
                    val hostUrl = normalizeUrl(m.value)
                    if (isAdUrl(hostUrl) || !seenUrls.add(hostUrl)) continue

                    if (hostUrl.contains("workers.dev", true)) {
                        emitServerLink(
                            serverName = "WorkerHLS",
                            streamUrl = hostUrl,
                            isHls = true,
                            qualityStr = "1080p",
                            customHeaders = baseHeaders + mapOf("Referer" to watchUrl)
                        )
                    } else if (extractHostWithQueue(hostUrl, watchUrl)) {
                        found = true
                    }
                }

                extractJsonArrayAfterKey(combinedScanText, "sources")?.let { sourcesJson ->
                    runCatching {
                        val arr = JSONArray(sourcesJson)
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val embedUrl = normalizeUrl(obj.optString("url"))
                            if (embedUrl.isBlank() || !embedUrl.startsWith("http") || isAdUrl(embedUrl)) continue
                            if (!seenUrls.add(embedUrl)) continue

                            val rawServerName = obj.optString("name")
                                .ifBlank { obj.optString("label") }
                                .ifBlank { "Embed Server" }

                            if (
                                embedUrl.contains(".m3u8", true) ||
                                embedUrl.contains("type=hls", true) ||
                                embedUrl.contains("itsnitrox.tech", true) ||
                                embedUrl.contains("nxstreamhub", true) ||
                                embedUrl.contains("stream_proxy.php", true) ||
                                embedUrl.contains("vibuxer.com", true) ||
                                embedUrl.contains("workers.dev", true) ||
                                obj.optString("type").equals("hls", true)
                            ) {
                                val customHeaders = if (
                                    embedUrl.contains("itsnitrox.tech", true) ||
                                    embedUrl.contains("nxstreamhub", true) ||
                                    embedUrl.contains("vibuxer.com", true)
                                ) {
                                    baseHeaders + mapOf("Referer" to "https://vidprime.top/", "Origin" to "https://vidprime.top")
                                } else {
                                    baseHeaders + mapOf("Referer" to watchUrl)
                                }

                                emitServerLink(
                                    serverName = rawServerName,
                                    streamUrl = embedUrl,
                                    isHls = true,
                                    qualityStr = obj.optString("quality"),
                                    customHeaders = customHeaders
                                )
                            } else if (extractHostWithQueue(embedUrl, watchUrl)) {
                                found = true
                            }
                        }
                    }
                }
            }

            // 3. Auto-Fetch from Saved Custom Domains in MovieFoxSettingsDialog
            val enabledCustomSites = appCtx
                ?.let { MovieFoxSettingsDialog.getCustomSites(it).filter { s -> s.enabled } }
                .orEmpty()

            for (site in enabledCustomSites) {
                val baseSiteUrl = site.baseUrl.trimEnd('/')
                if (!baseSiteUrl.startsWith("http") || pageTitle.isBlank()) continue

                val encodedQuery = runCatching { URLEncoder.encode(pageTitle, "UTF-8") }.getOrDefault(pageTitle)
                val searchCandidates = listOf(
                    "$baseSiteUrl/search?q=$encodedQuery",
                    "$baseSiteUrl/search/$encodedQuery",
                    "$baseSiteUrl/?s=$encodedQuery"
                )

                var matchedDetailUrl: String? = null
                for (searchUrl in searchCandidates) {
                    val searchHtml = runCatching {
                        app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT), interceptor = cfKiller, timeout = timeoutSec).text
                    }.getOrNull().orEmpty()
                    if (searchHtml.isBlank()) continue

                    val searchDoc = Jsoup.parse(searchHtml, baseSiteUrl)
                    val targetLink = searchDoc.select("a[href]").firstOrNull { a ->
                        val href = a.attr("href").trim()
                        val text = (a.text() + " " + a.attr("title") + " " + a.attr("aria-label")).trim()
                        (href.contains("/detail/", true) ||
                            href.contains("/movie/", true) ||
                            href.contains("/tv/", true) ||
                            href.contains("/watch/", true) ||
                            href.contains("/series/", true) ||
                            href.contains("/drama/", true)) &&
                            text.contains(pageTitle, ignoreCase = true)
                    }

                    if (targetLink != null) {
                        matchedDetailUrl = normalizeUrl(targetLink.attr("href"), baseSiteUrl)
                        break
                    }
                }

                val candidatePages = buildList {
                    if (!matchedDetailUrl.isNullOrBlank()) add(matchedDetailUrl)
                    if (isTv) {
                        add("$baseSiteUrl/tv/$primaryId/$season/$episode")
                        add("$baseSiteUrl/embed/tv/$primaryId/$season/$episode")
                        add("$baseSiteUrl/watch/tv/$primaryId?s=$season&e=$episode")
                    } else {
                        add("$baseSiteUrl/movie/$primaryId")
                        add("$baseSiteUrl/embed/movie/$primaryId")
                        add("$baseSiteUrl/watch/movie/$primaryId")
                    }
                }.distinct()

                for (targetPageUrl in candidatePages) {
                    val extResp = runCatching {
                        app.get(
                            targetPageUrl,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "$baseSiteUrl/",
                                "Origin" to baseSiteUrl
                            ),
                            interceptor = cfKiller,
                            timeout = timeoutSec
                        )
                    }.getOrNull() ?: continue

                    val extHtml = extResp.text
                    if (extHtml.isBlank()) continue

                    val extHeaders = mapOf(
                        "Referer" to targetPageUrl,
                        "Origin" to baseSiteUrl,
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*"
                    )

                    val extDoc = Jsoup.parse(extHtml, baseSiteUrl)
                    for (el in extDoc.select("iframe[src], [data-src], [data-link], [data-url]")) {
                        val iframeUrl = normalizeUrl(
                            el.attr("src")
                                .ifEmpty { el.attr("data-src") }
                                .ifEmpty { el.attr("data-link") }
                                .ifEmpty { el.attr("data-url") },
                            baseSiteUrl
                        )
                        if (iframeUrl.startsWith("http") && !isAdUrl(iframeUrl) && seenUrls.add(iframeUrl)) {
                            if (extractHostWithQueue(iframeUrl, targetPageUrl)) found = true
                        }
                    }

                    val extStreamRegex = Regex(
                        """["'](https?://[^"'\s<>]+?(?:\.(?:m3u8|mp4)|workers\.dev/[^"'\s<>]+|itsnitrox\.tech/[^"'\s<>]+|vibuxer\.com[^"'\s<>]*|type=hls)[^"'\s<>]*)["']""",
                        RegexOption.IGNORE_CASE
                    )
                    for (m in extStreamRegex.findAll(extHtml)) {
                        val foundStream = normalizeUrl(m.groupValues[1], baseSiteUrl)
                        if (isAdUrl(foundStream)) continue
                        val isHls = foundStream.contains(".m3u8", true) ||
                            foundStream.contains("type=hls", true) ||
                            foundStream.contains("workers.dev", true) ||
                            foundStream.contains("itsnitrox.tech", true) ||
                            foundStream.contains("vibuxer.com", true)

                        emitServerLink(
                            serverName = "${site.name} ${if (isHls) "HLS" else "MP4"}",
                            streamUrl = foundStream,
                            isHls = isHls,
                            qualityStr = "1080p",
                            customHeaders = extHeaders
                        )
                    }

                    val extHostRegex = Regex(
                        """https?://(?:[a-zA-Z0-9.-]+\.)?(?:voe\.(?:sx|to|co)|pixeldrain\.(?:com|dev)|streamtape\.(?:com|to|net|xyz)|shavetape\.cash|strcloud\.link|strtape\.tech|stape\.fun|luluvid\.com|lulustream\.com|luluvdo\.com|vidara\.(?:to|so)|vidprime\.top|upbolt\.(?:to|tv)|abysscdn\.com|short\.icu|lplaygo\.com|vidmoly\.(?:me|to)|earnvids\.com|dingtezuni\.com|rabbitstream\.net|upcloud\.to|megacloud\.(?:tv|club)|streamwish\.to|filemoon\.sx|drive\.google\.com)/[^\s"'\\]+""",
                        RegexOption.IGNORE_CASE
                    )
                    for (m in extHostRegex.findAll(extHtml)) {
                        val hostUrl = normalizeUrl(m.value, baseSiteUrl)
                        if (!isAdUrl(hostUrl) && seenUrls.add(hostUrl)) {
                            if (extractHostWithQueue(hostUrl, targetPageUrl)) found = true
                        }
                    }
                }
            }

            // 4. Sflix Servers Integration inside MovieFoxProvider
            val sflixEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "sflix") } ?: true
            if (sflixEnabled && pageTitle.isNotBlank()) {
                val sflixStart = System.currentTimeMillis()
                var sflixFoundAny = false
                runCatching {
                    val sflixMainUrl = "https://sflix.film"
                    val sflixPlayerUrl = "https://mzfi.me"
                    val sflixDomains = listOf(
                        "https://sflix.film",
                        "https://h5-api.aoneroom.com",
                        "https://movie-box.co",
                        "https://123moviesfree.club"
                    )
                    val sflixCookies =
                        "sflix_i18n_lang=en; _ga=GA1.1.622965780.1794742147; " +
                            "sflix_token=%22eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjE5NjY4NTU1NTcyNzgwNjIyNzYsImF0cCI6MywiZXh0IjoiMTc5NDc0MjE0NyIsImV4cCI6MTgwMjUxODE0NywiaWF0IjoxNzk0NzQxODQ3fQ.8aOIckQOSKyISQdl85SAJvuMlhXfWodHmcPOIvlg0pM%22; " +
                            "vrk4n8fqhwc3jzy7pbsmgt6dx5lha2u9=01a1b98c-7aec-7761-b581-b09b7730a724_2; " +
                            "dom3ic8zudi28v8lr6fgphwffqoz0j6c=01a1b98c-7aec-7761-b581-b09b7730a724"
                    val sflixUa =
                        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

                    val encodedTitle = URLEncoder.encode(pageTitle, "UTF-8")
                    val sflixSearchDoc = app.get(
                        "$sflixMainUrl/search-result?keyword=$encodedTitle",
                        headers = mapOf(
                            "Referer" to "$sflixMainUrl/",
                            "User-Agent" to sflixUa,
                            "Cookie" to sflixCookies
                        ),
                        interceptor = cfKiller,
                        timeout = timeoutSec
                    ).document

                    val detailLink = sflixSearchDoc.select("a.video-item[href*='/detail/'], a[href*='/detail/']")
                        .firstOrNull { el ->
                            val t = (el.selectFirst("h3.text, .video-name, h3")?.text() ?: el.attr("title")).trim()
                            t.contains(pageTitle, ignoreCase = true) || pageTitle.contains(t, ignoreCase = true)
                        }?.attr("href")

                    if (!detailLink.isNullOrBlank()) {
                        val fullDetailUrl = if (detailLink.startsWith("http")) detailLink else "$sflixMainUrl/${detailLink.trimStart('/')}"
                        val detailDoc = app.get(
                            fullDetailUrl,
                            headers = mapOf(
                                "Referer" to "$sflixMainUrl/",
                                "User-Agent" to sflixUa,
                                "Cookie" to sflixCookies
                            ),
                            interceptor = cfKiller,
                            timeout = timeoutSec
                        ).document

                        var subjectId = Regex("""[?&]id=(\d+)""").find(fullDetailUrl)?.groupValues?.get(1) ?: ""
                        var detailPath = fullDetailUrl.substringAfter("/detail/").substringBefore("?")
                        val dubServers = mutableListOf<Triple<String, String, String>>()

                        val nuxtRaw = detailDoc.selectFirst("script#__NUXT_DATA__")?.data()
                        if (!nuxtRaw.isNullOrEmpty()) {
                            runCatching {
                                val nuxtArr = JSONArray(nuxtRaw)
                                var rootObj: JSONObject? = null
                                for (i in 0 until nuxtArr.length()) {
                                    val obj = nuxtArr.optJSONObject(i) ?: continue
                                    if (obj.has("subject") && obj.has("resource")) {
                                        rootObj = obj
                                        break
                                    }
                                }
                                val subjectObj = rootObj?.optInt("subject", -1)?.takeIf { it >= 0 }?.let { nuxtArr.optJSONObject(it) }
                                if (subjectObj != null) {
                                    subjectObj.optInt("subjectId", -1).takeIf { it >= 0 }?.let {
                                        val idVal = nuxtArr.optString(it)
                                        if (idVal.isNotEmpty()) subjectId = idVal
                                    }
                                    subjectObj.optInt("detailPath", -1).takeIf { it >= 0 }?.let {
                                        val dp = nuxtArr.optString(it)
                                        if (dp.isNotEmpty()) detailPath = dp
                                    }
                                    val dubsArr = subjectObj.optInt("dubs", -1).takeIf { it >= 0 }?.let { nuxtArr.optJSONArray(it) }
                                    if (dubsArr != null) {
                                        for (d in 0 until dubsArr.length()) {
                                            val dubObj = nuxtArr.optJSONObject(dubsArr.optInt(d, -1)) ?: continue
                                            val dubId = dubObj.optInt("subjectId", -1).takeIf { it >= 0 }?.let { nuxtArr.optString(it) } ?: continue
                                            val lanName = dubObj.optInt("lanName", -1).takeIf { it >= 0 }?.let { nuxtArr.optString(it) } ?: "Dub ${d + 1}"
                                            val dubPath = dubObj.optInt("detailPath", -1).takeIf { it >= 0 }?.let { nuxtArr.optString(it) } ?: detailPath
                                            dubServers.add(Triple(lanName, dubId, dubPath))
                                        }
                                    }
                                }
                            }
                        }

                        if (subjectId.isNotEmpty()) {
                            val sflixSe = if (isTv) season.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
                            val sflixEp = if (isTv) episode.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0

                            val targets = buildList {
                                add(Triple("Original", subjectId, detailPath))
                                dubServers.forEach { d ->
                                    if (d.second != subjectId) add(d)
                                }
                            }

                            for ((srvLabel, srvId, srvPath) in targets) {
                                var serverMatched = false
                                for (domain in sflixDomains) {
                                    val playUrl = "$domain/wefeed-h5api-bff/subject/play?subjectId=$srvId" +
                                        "&se=$sflixSe&ep=$sflixEp&detailPath=$srvPath" +
                                        "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
                                    val referer = "$sflixPlayerUrl/spa/videoPlayPage/movies/$srvPath?id=$srvId&type=/movie/detail&lang=en"
                                    val webHeaders = mapOf(
                                        "User-Agent" to sflixUa,
                                        "Referer" to referer,
                                        "Origin" to sflixPlayerUrl,
                                        "Accept" to "application/json",
                                        "Cookie" to sflixCookies,
                                        "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                                        "x-request-lang" to "en",
                                        "x-vip-restrict" to "0",
                                        "x-no-high-risk-restrict" to "0"
                                    )

                                    val playResp = runCatching { app.get(playUrl, headers = webHeaders, timeout = timeoutSec) }.getOrNull() ?: continue
                                    if (playResp.code != 200) continue

                                    val rootJson = runCatching { JSONObject(playResp.text) }.getOrNull() ?: continue
                                    val dataObj = rootJson.optJSONObject("data") ?: continue

                                    suspend fun emitSflixArray(arr: JSONArray?, kind: String) {
                                        if (arr == null) return
                                        for (i in 0 until arr.length()) {
                                            val item = arr.optJSONObject(i) ?: continue
                                            val streamUrl = item.optString("url").trim()
                                            if (streamUrl.isBlank() || isAdUrl(streamUrl) || !seenUrls.add(streamUrl)) continue

                                            val signCookie = item.optString("signCookie").takeIf { it.isNotBlank() }
                                            val signHeaderKey = item.optString("signHeaderKey").takeIf { it.isNotBlank() } ?: "X-MB-Token"
                                            val resolutions = item.optString("resolutions")

                                            val isDash = kind == "DASH" || streamUrl.contains(".mpd", true)
                                            val isHls = kind == "HLS" || streamUrl.contains(".m3u8", true)
                                            val linkType = when {
                                                isDash -> ExtractorLinkType.DASH
                                                isHls -> ExtractorLinkType.M3U8
                                                else -> ExtractorLinkType.VIDEO
                                            }
                                            if (downloadOnly && (isDash || isHls)) continue

                                            val maxRes = resolutions.split(",")
                                                .mapNotNull { it.trim().toIntOrNull() }
                                                .maxOrNull() ?: 480

                                            val qualityLabel = if (isDash && resolutions.contains(",")) {
                                                "1080p/720p/480p Auto"
                                            } else {
                                                "${maxRes}p"
                                            }

                                            val streamHeaders = mutableMapOf(
                                                "Origin" to sflixPlayerUrl,
                                                "Referer" to "$sflixPlayerUrl/",
                                                "User-Agent" to sflixUa,
                                                "Accept" to "*/*"
                                            )
                                            if (signCookie != null) {
                                                streamHeaders[signHeaderKey] = signCookie
                                                streamHeaders["Cookie"] = signCookie
                                            }

                                            val exLink = newExtractorLink(
                                                source = "Sflix ($srvLabel)",
                                                name = if (streamOnly && (isDash || isHls)) {
                                                    "⚡ Sflix - $srvLabel [$kind $qualityLabel]"
                                                } else {
                                                    "Sflix - $srvLabel [$kind $qualityLabel]"
                                                },
                                                url = streamUrl,
                                                type = linkType
                                            ) {
                                                this.referer = "$sflixPlayerUrl/"
                                                this.headers = streamHeaders
                                                this.quality = maxRes
                                            }
                                            recordLink(exLink, isStream = isDash || isHls)
                                            serverMatched = true
                                            sflixFoundAny = true
                                        }
                                    }

                                    emitSflixArray(dataObj.optJSONArray("dash"), "DASH")
                                    emitSflixArray(dataObj.optJSONArray("hls"), "HLS")
                                    emitSflixArray(dataObj.optJSONArray("streams"), "MP4")

                                    if (serverMatched) break
                                }
                            }
                        }
                    }
                }
                MovieFoxSettingsDialog.reportServerResult(appCtx, "sflix", sflixFoundAny, System.currentTimeMillis() - sflixStart)
            }

            // 5. Prmovies Integration inside MovieFoxProvider
            val prmoviesEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "prmovies") } ?: true
            if (prmoviesEnabled && pageTitle.isNotBlank()) {
                val prStart = System.currentTimeMillis()
                var prSuccess = false
                runCatching {
                    val query = pageTitle.trim().replace(Regex("\\s+"), "+")
                    val searchHtml = getPrmoviesPage("$activePrmoviesBase/?s=$query", timeoutSec = timeoutSec)?.text.orEmpty()
                    if (searchHtml.isNotBlank()) {
                        val searchDoc = Jsoup.parse(searchHtml, activePrmoviesBase)
                        val matchedCard = searchDoc.select(".ml-item").firstOrNull { card ->
                            val a = card.selectFirst("a.ml-mask") ?: return@firstOrNull false
                            val href = a.attr("href").trim()
                            if (href.isBlank()) return@firstOrNull false
                            val candidateTitle = (
                                a.attr("oldtitle")
                                    .ifBlank { a.attr("title") }
                                    .ifBlank { card.selectFirst(".mli-info h2")?.text().orEmpty() }
                                    .ifBlank { card.selectFirst("img.mli-thumb")?.attr("alt").orEmpty() }
                                ).trim()
                            if (candidateTitle.isBlank()) return@firstOrNull false

                            val cardIsSeries = href.contains("/series/", true) ||
                                Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\d{1,2}\b""").containsMatchIn(candidateTitle)
                            val typeMatches = if (isTv) cardIsSeries else !cardIsSeries
                            val titleMatches = candidateTitle.contains(pageTitle, ignoreCase = true) ||
                                pageTitle.contains(candidateTitle, ignoreCase = true)
                            typeMatches && titleMatches
                        }

                        val matchedUrl = matchedCard?.selectFirst("a.ml-mask")?.attr("href")
                            ?.let { normalizeUrl(it, activePrmoviesBase) }

                        if (!matchedUrl.isNullOrBlank()) {
                            val detailHtml = getPrmoviesPage(matchedUrl, timeoutSec = timeoutSec)?.text.orEmpty()
                            if (detailHtml.isNotBlank()) {
                                val detailDoc = Jsoup.parse(detailHtml, activePrmoviesBase)
                                val targetSeason = season.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                val targetEpisode = episode.toIntOrNull()?.coerceAtLeast(1) ?: 1

                                var targetPageUrl = matchedUrl
                                var targetDoc = detailDoc

                                if (isTv && matchedUrl.contains("/series/", true)) {
                                    var epHref: String? = null
                                    for (tvseason in detailDoc.select("#seasons .tvseason")) {
                                        val seasonTitle = tvseason.selectFirst(".les-title strong")?.text().orEmpty()
                                        val sNum = Regex("""(?i)\bseason\s*(\d+)""").find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                                        if (sNum != targetSeason) continue

                                        for (a in tvseason.select(".les-content a[href]")) {
                                            val href = a.attr("href").trim()
                                            if (href.isBlank()) continue
                                            val label = a.text().trim()
                                            val eNum = Regex("""(?i)\bepisode\s*[-:]?\s*(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                                            if (eNum == targetEpisode) {
                                                epHref = normalizeUrl(href, activePrmoviesBase)
                                                break
                                            }
                                        }
                                        if (epHref != null) break
                                    }

                                    if (!epHref.isNullOrBlank()) {
                                        val epHtml = getPrmoviesPage(epHref, referer = matchedUrl, timeoutSec = timeoutSec)?.text.orEmpty()
                                        if (epHtml.isNotBlank()) {
                                            targetPageUrl = epHref
                                            targetDoc = Jsoup.parse(epHtml, activePrmoviesBase)
                                        }
                                    }
                                }

                                val prLinks = mutableListOf<ExtractorLink>()
                                val prmoviesFound = if (targetPageUrl.contains("/episode/", true)) {
                                    resolvePrmoviesEpisodePage(targetDoc, targetPageUrl, seenUrls, timeoutSec) { ex ->
                                        prLinks.add(ex)
                                    }
                                } else {
                                    resolvePrmoviesPagePlayer(targetDoc, seenUrls, timeoutSec) { ex ->
                                        prLinks.add(ex)
                                    }
                                }
                                for (ex in prLinks) {
                                    recordLink(ex, isStream = isStreamOrHlsLink(ex.url, ex.type))
                                }
                                if (prmoviesFound || prLinks.isNotEmpty()) {
                                    found = true
                                    prSuccess = true
                                }
                            }
                        }
                    }
                }
                MovieFoxSettingsDialog.reportServerResult(appCtx, "prmovies", prSuccess, System.currentTimeMillis() - prStart)
            }

            // 6. HindMoviez Integration inside MovieFoxProvider
            val hindmoviezEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "hindmoviez") } ?: true
            if (hindmoviezEnabled && pageTitle.isNotBlank()) {
                val hmStart = System.currentTimeMillis()
                var hmSuccess = false
                runCatching {
                    val targetSeason = season.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val targetEpisode = episode.toIntOrNull()?.coerceAtLeast(1) ?: 1

                    var chosenPost: Pair<String, String>? = null
                    outer@ for (domain in hindmoviezDomains) {
                        val searchUrl = "$domain/?s=${URLEncoder.encode(pageTitle, "UTF-8")}"
                        val searchDoc = runCatching {
                            app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT), interceptor = cfKiller, timeout = timeoutSec).document
                        }.getOrNull() ?: continue

                        val posts = searchDoc.select("h2.entry-title > a[href]").mapNotNull { a ->
                            val t = a.text().replace(Regex("\\s+"), " ").trim()
                            val h = a.attr("href").trim()
                            if (t.isBlank() || !h.startsWith("http")) null else t to h
                        }
                        if (posts.isEmpty()) continue

                        val matched = posts.filter { titleMatchesCandidate(it.first, pageTitle) }
                        if (matched.isNotEmpty()) {
                            chosenPost = if (isTv) {
                                val sPosts = matched.filter { seasonsInText(it.first)?.contains(targetSeason) == true }
                                sPosts.firstOrNull() ?: matched.firstOrNull { seasonsInText(it.first) == null } ?: matched.first()
                            } else {
                                matched.first()
                            }
                            if (chosenPost != null) break@outer
                        }
                    }

                    if (chosenPost != null) {
                        val postDoc = runCatching {
                            app.get(chosenPost.second, headers = mapOf("User-Agent" to USER_AGENT), interceptor = cfKiller, timeout = timeoutSec).document
                        }.getOrNull()

                        val targets = postDoc?.select("a.maxbutton")?.mapNotNull { a ->
                            a.attr("href").trim().takeIf { it.startsWith("http") }
                        }?.distinct().orEmpty()

                        if (targets.isNotEmpty()) {
                            val sem = Semaphore(3)
                            coroutineScope {
                                targets.map { linkUrl ->
                                    async(Dispatchers.IO) {
                                        sem.withPermit {
                                            val subPage = runCatching {
                                                app.get(linkUrl, headers = mapOf("User-Agent" to USER_AGENT), interceptor = cfKiller, timeout = timeoutSec).document
                                            }.getOrNull() ?: return@withPermit false

                                            val shareHref = if (!isTv) {
                                                subPage.select("a[href]").firstOrNull {
                                                    it.attr("href").contains("?id=")
                                                }?.attr("abs:href")?.trim()
                                            } else {
                                                subPage.select("a[href]").firstOrNull {
                                                    it.attr("href").contains("?id=") && hindmoviezEpisodeMatch(it, targetSeason, targetEpisode)
                                                }?.attr("abs:href")?.trim()
                                            }

                                            if (!shareHref.isNullOrBlank() && shareHref.startsWith("http")) {
                                                val sDoc = runCatching {
                                                    app.get(shareHref, headers = mapOf("User-Agent" to USER_AGENT), timeout = timeoutSec).document
                                                }.getOrNull() ?: return@withPermit false
                                                val shareName = sDoc.selectFirst("p:contains(Name:)")?.text()?.substringAfter("Name:")?.trim().orEmpty()
                                                val shareSize = sDoc.selectFirst("p:contains(Size:)")?.text()?.substringAfter("Size:")?.trim().orEmpty()
                                                val info = listOf(shareName, shareSize).filter { it.isNotBlank() }.joinToString(" ")
                                                if (info.isBlank()) return@withPermit false
                                                val quality = parseIndexQuality(info)

                                                var emittedAny = false
                                                for (a in sDoc.select("div.btn-group a.btn, a.btn")) {
                                                    val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                                                    if (!href.startsWith("http")) continue
                                                    val targetUrl = when {
                                                        a.classNames().contains("btn-info") -> hindmoviezHpageTarget(href)
                                                        href.contains("gdshine.org/s/") ->
                                                            hindmoviezGdshineTarget(href.substringAfter("/s/").substringBefore("?"), timeoutSec)
                                                        else -> null
                                                    }
                                                    if (targetUrl != null) {
                                                        val cleanUrl = normalizeUrl(targetUrl)
                                                        if (cleanUrl.isNotBlank() && !isAdUrl(cleanUrl) && seenUrls.add(cleanUrl)) {
                                                            val isHls = cleanUrl.contains(".m3u8", true)
                                                            if (downloadOnly && isHls) continue
                                                            val exLink = newExtractorLink(
                                                                source = "HindMoviez",
                                                                name = buildSourceLabel("HindMoviez", "", info),
                                                                url = cleanUrl,
                                                                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                                            ) {
                                                                this.quality = quality
                                                                this.headers = mapOf("User-Agent" to USER_AGENT)
                                                            }
                                                            recordLink(exLink, isStream = isHls)
                                                            emittedAny = true
                                                        }
                                                    }
                                                }
                                                emittedAny
                                            } else {
                                                false
                                            }
                                        }
                                    }
                                }.forEach { job ->
                                    if (job.await()) {
                                        found = true
                                        hmSuccess = true
                                    }
                                }
                            }
                        }
                    }
                }
                MovieFoxSettingsDialog.reportServerResult(appCtx, "hindmoviez", hmSuccess, System.currentTimeMillis() - hmStart)
            }

            // 7. VegaMovies Integration inside MovieFoxProvider
            val vegamoviesEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "vegamovies") } ?: true
            if (vegamoviesEnabled && pageTitle.isNotBlank()) {
                val vegaStart = System.currentTimeMillis()
                var vegaSuccess = false
                runCatching {
                    val targetSeason = season.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val targetEpisode = episode.toIntOrNull()?.coerceAtLeast(1) ?: 1

                    var chosenVega: VegaDoc? = null
                    val searchQueries = listOfNotNull(
                        pageTitle,
                        if (isTv) "$pageTitle Season $targetSeason" else null
                    ).distinct()

                    for (q in searchQueries) {
                        val roundDocs = coroutineScope {
                            vegamoviesDomains.map { domain ->
                                async(Dispatchers.IO) { searchVegaPosts(domain, q, timeoutSec) }
                            }.flatMap { it.await() }
                        }
                        if (roundDocs.isEmpty()) continue
                        chosenVega = pickVegaPost(roundDocs, pageTitle, isTv, targetSeason)
                        if (chosenVega != null) break
                    }

                    val permalink = chosenVega?.permalink?.takeIf { it.isNotBlank() }
                    if (permalink != null) {
                        val domain = chosenVega.domain.ifBlank {
                            vegamoviesDomains.firstOrNull { permalink.contains(it.removePrefix("https://"), true) }
                                ?: vegamoviesDomains.first()
                        }
                        val postUrl = normalizeUrl(permalink, domain)
                        val postHtml = runCatching {
                            app.get(
                                postUrl,
                                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$domain/"),
                                interceptor = cfKiller,
                                timeout = timeoutSec
                            ).text
                        }.getOrNull().orEmpty()

                        val detail = if (postHtml.isNotBlank()) parseVegaPost(postHtml) else null
                        if (detail != null) {
                            val groups: List<VegaQualityGroup> = if (isTv) {
                                val inSeason = detail.seasons.filter { it.number == targetSeason }.flatMap { it.groups }
                                when {
                                    inSeason.isNotEmpty() -> inSeason
                                    detail.seasons.isNotEmpty() ->
                                        detail.seasons.filter { s -> seasonsInText(s.title) == null }.flatMap { it.groups }
                                    seasonsInText(chosenVega.postTitle.orEmpty())?.contains(targetSeason) == true -> detail.movieGroups
                                    else -> emptyList()
                                }
                            } else {
                                detail.movieGroups
                            }

                            if (groups.isNotEmpty()) {
                                val claimedKeys = ConcurrentHashMap.newKeySet<String>()
                                coroutineScope {
                                    val gate = Semaphore(5)
                                    groups.map { group ->
                                        async(Dispatchers.IO) {
                                            gate.withPermit {
                                                var groupEmitted = false
                                                val tier = parseIndexQuality(group.label)
                                                val cleanInfo = group.label
                                                    .replace(Regex("""(?i)\bdownload\s+now\b|\bdownload\b"""), "")
                                                    .replace(Regex("""\s+"""), " ").trim()
                                                val isDv = Regex("""(?i)\bDV\b|dolby|hybrid""").containsMatchIn(group.label)

                                                for ((_, driveUrl) in group.links.distinctBy { it.second }) {
                                                    val drivePage = fetchVegaDrivePage(driveUrl, timeoutSec) ?: continue
                                                    val targets = if (isTv) {
                                                        drivePage.episodes[targetEpisode]
                                                            ?: drivePage.servers.takeIf { drivePage.episodes.isEmpty() }
                                                    } else {
                                                        drivePage.servers
                                                    }.orEmpty()

                                                    for ((_, href) in targets) {
                                                        val nextList = if (href.contains("nexdrive", true)) {
                                                            val nested = fetchVegaDrivePage(href, timeoutSec) ?: continue
                                                            if (isTv) {
                                                                nested.episodes[targetEpisode]
                                                                    ?: nested.servers.takeIf { nested.episodes.isEmpty() }
                                                            } else {
                                                                nested.servers
                                                            }.orEmpty()
                                                        } else {
                                                            listOf("" to href)
                                                        }

                                                        for ((_, href2) in nextList) {
                                                            for (srv in resolveVegaHref(href2, timeoutSec)) {
                                                                val streamUrl = normalizeUrl(srv.url)
                                                                if (streamUrl.isBlank() || isAdUrl(streamUrl) || !seenUrls.add(streamUrl)) continue
                                                                val isHls = streamUrl.contains(".m3u8", true)
                                                                if (downloadOnly && isHls) continue

                                                                val key = "${srv.name}|$tier|${if (isDv) "dv" else ""}"
                                                                if (!claimedKeys.add(key)) continue

                                                                val fullInfo = if (isDv && !cleanInfo.contains("DV", true)) {
                                                                    "$cleanInfo DV".trim()
                                                                } else {
                                                                    cleanInfo
                                                                }

                                                                val exLink = newExtractorLink(
                                                                    source = "VegaMovies",
                                                                    name = buildSourceLabel("VegaMovies", srv.name, fullInfo),
                                                                    url = streamUrl,
                                                                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                                                ) {
                                                                    this.quality = tier
                                                                    this.headers = mapOf("User-Agent" to USER_AGENT)
                                                                }
                                                                recordLink(exLink, isStream = isHls)
                                                                groupEmitted = true
                                                            }
                                                        }
                                                    }
                                                }
                                                groupEmitted
                                            }
                                        }
                                    }.forEach { job ->
                                        if (job.await()) {
                                            found = true
                                            vegaSuccess = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                MovieFoxSettingsDialog.reportServerResult(appCtx, "vegamovies", vegaSuccess, System.currentTimeMillis() - vegaStart)
            }

            // Final dispatch: Stream Only mode prioritizes HLS/DASH/stream links ahead of direct files
            val orderedLinks = if (streamOnly) {
                collectedLinks.sortedByDescending { if (it.isStream) 1 else 0 }
            } else {
                collectedLinks
            }
            orderedLinks.forEach { queued ->
                callback(queued.link)
            }

            found
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
