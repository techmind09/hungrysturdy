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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
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

    // VegaMovies State (only PixelDrain, FSL, FSL2; 10Gbps, G-Direct, V-Drive removed)
    private val vegamoviesDomains = listOf(
        "https://vegamovies.lease",
        "https://rogmovies.autos",
        "https://rogmovies.im"
    )
    private val vegaDriveCache = ConcurrentHashMap<String, VegaDrivePage>()

    private val vegaDeadHosts = Regex("""(?i)megaup|viking|vik1ng|telegram|t\.me|gpdl|10gbps|fastdl|vegadrive|skydrop|buzzserver""")
    private val vegaJunk = Regex(
        """(?i)vglist|t\.me|telegram|imdb\.com|google\.com/search|whatsapp|share|facebook|""" +
            """twitter|pinterest|report|hubcloud\.(foo|cx)|/admin|login|signup|\bidm\b|\bida\b|""" +
            """category|/tag/|/author/|/page/"""
    )
    private val vegaButtonHosts = Regex(
        """(?i)nexdrive|vcloud|filebee|filepress|fpgo|gofile|hubcloud|pixeldrain|\.r2\.dev|\.r2\.cloudflarestorage\.com"""
    )

    // MovieBox BFF State
    private val movieBoxBff = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    private val movieBoxSiteUrl = "https://netnaija.film"
    private val movieBoxUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // NetNaija State
    private val netnaijaDomains = listOf(
        "https://www.thenetnaija.net",
        "https://netnaija.xyz",
        "https://netnaija.com.ng"
    )
    private var activeNetnaijaBase = "https://www.thenetnaija.net"

    // Play4u State
    private val play4uBaseUrl = "https://play4u.org"
    private val play4uSealPrefix = "pa!~"
    private val play4uDefaultSealKey = "pa4u-stream-seal-v1"
    private val play4uSealKeyPattern = Regex("""window\.__PA_SEAL_KEY__\s*=\s*"([^"]*)"""")

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
        val ctx = CloudStreamApp.context ?: return 4L
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

    private fun isM3u8Stream(url: String, typeHint: String): Boolean {
        return isStreamOrHlsLink(url, ExtractorLinkType.M3U8, typeHint)
    }

    // MovieBox BFF Helpers
    private fun movieBoxClientToken(): String {
        val ts = System.currentTimeMillis() / 1000
        val rev = ts.toString().reversed()
        val md5 = MessageDigest.getInstance("MD5").digest(rev.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    private fun movieBoxHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to movieBoxUa,
            "Accept" to "application/json",
            "Origin" to movieBoxSiteUrl,
            "Referer" to "$movieBoxSiteUrl/",
            "X-Request-Lang" to "en",
            "X-Client-Info" to "{\"timezone\":\"Asia/Kolkata\"}",
            "X-Client-Token" to movieBoxClientToken()
        )
        h.putAll(extra)
        return h
    }

    private fun movieBoxDubLabel(dub: JSONObject): String? {
        val lan = dub.optString("lanName").trim()
        if (lan.isBlank()) return null
        if (dub.optBoolean("original")) return "Original"
        val base = lan.substringBefore(" dub").substringBefore(" sub").trim()
        if (base.isEmpty()) return null
        val pretty = when (base.lowercase()) {
            "ptbr" -> "PT-BR"
            "esla" -> "ES-LA"
            else -> base.replaceFirstChar { it.uppercase() }
        }
        return if (dub.optInt("type", 0) == 1) "$pretty Hardsub" else "$pretty Dub"
    }

    // NetNaija Helpers
    private suspend fun getNetnaijaDoc(pathOrUrl: String, timeoutSec: Long): org.jsoup.nodes.Document? {
        val candidates = (listOf(activeNetnaijaBase.trimEnd('/')) + netnaijaDomains).distinct()
        val suffix = if (pathOrUrl.startsWith("http")) {
            val matched = candidates.firstOrNull { pathOrUrl.startsWith(it, ignoreCase = true) }
            if (matched != null) {
                pathOrUrl.substring(matched.length)
            } else {
                val res = runCatching {
                    app.get(
                        pathOrUrl,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$activeNetnaijaBase/"),
                        interceptor = cfKiller,
                        timeout = timeoutSec
                    )
                }.getOrNull() ?: return null
                return if (res.code in 200..299 && res.text.isNotBlank()) Jsoup.parse(res.text, pathOrUrl) else null
            }
        } else {
            if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
        }

        for (domain in candidates) {
            val fullUrl = "$domain$suffix"
            val res = runCatching {
                app.get(
                    fullUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$domain/"),
                    interceptor = cfKiller,
                    timeout = timeoutSec
                )
            }.getOrNull()
            if (res != null && res.code in 200..299 && res.text.isNotBlank()) {
                activeNetnaijaBase = domain
                return Jsoup.parse(res.text, domain)
            }
        }
        return null
    }

    private suspend fun resolveNetnaijaDownloadLink(
        rawLink: String,
        refererUrl: String,
        label: String,
        seenUrls: MutableSet<String>,
        timeoutSec: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        emitNow: suspend (ExtractorLink, Boolean) -> Unit
    ): Boolean {
        var resolved = normalizeUrl(rawLink, activeNetnaijaBase)
        if (resolved.isBlank() || isAdUrl(resolved)) return false

        // Follow NetNaija intermediate /d/ or /download/ landing page if present
        if (resolved.contains("netnaija", true) && (resolved.contains("/d/") || resolved.contains("/download"))) {
            val dlDoc = getNetnaijaDoc(resolved, timeoutSec)
            val directHref = dlDoc?.select("a[href*='sabishare'], a[href*='.mp4'], a[href*='.mkv'], a.download-btn, a[href*='/dl/']")
                ?.firstOrNull()
                ?.attr("href")
                ?.trim()
            if (!directHref.isNullOrBlank()) {
                resolved = normalizeUrl(directHref, activeNetnaijaBase)
            }
        }

        // Resolve Sabishare / NetNaija file host page to direct CDN video URL
        if (resolved.contains("sabishare.com", true)) {
            val sabiHtml = runCatching {
                app.get(
                    resolved,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to refererUrl),
                    interceptor = cfKiller,
                    timeout = timeoutSec
                ).text
            }.getOrNull().orEmpty()
            val sabiDoc = Jsoup.parse(sabiHtml, resolved)
            val cdnUrl = sabiDoc.select("a#download-btn[href], a.btn-download[href], video source[src], a[href*='.mp4'], a[href*='.mkv']")
                .firstOrNull()
                ?.let { it.attr("href").ifBlank { it.attr("src") } }
                ?.trim()
                ?: Regex("""https?://[^"'\s<>]+\.(?:mp4|mkv)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
                    .find(sabiHtml)?.value
            if (!cdnUrl.isNullOrBlank()) {
                resolved = normalizeUrl(cdnUrl, resolved)
            }
        }

        if (resolved.isBlank() || isAdUrl(resolved) || !seenUrls.add(resolved)) return false

        val isDirectMedia = Regex("""(?i)\.(mp4|mkv|webm|m3u8)(\?|$)""").containsMatchIn(resolved) ||
            resolved.contains("sabishare", true)

        return if (isDirectMedia) {
            val isHls = isM3u8Stream(resolved, "")
            val exLink = newExtractorLink(
                source = "NetNaija",
                name = buildSourceLabel("NetNaija", label, ""),
                url = resolved,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = refererUrl
                this.quality = getQuality(label)
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to refererUrl)
            }
            emitNow(exLink, isHls)
            true
        } else {
            var loaded = false
            loadExtractor(resolved, refererUrl, subtitleCallback) { link ->
                if (seenUrls.add(link.url)) {
                    loaded = true
                }
            }
            loaded
        }
    }

    // Play4u Helpers
    private fun play4uUnseal(value: String?, key: String): String {
        if (value.isNullOrBlank()) return ""
        if (!value.startsWith(play4uSealPrefix)) return value
        var b64 = value.substring(play4uSealPrefix.length)
            .replace("-", "+")
            .replace("_", "/")
        when (b64.length % 4) {
            1 -> return ""
            2 -> b64 += "=="
            3 -> b64 += "="
        }
        return try {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            val kb = key.toByteArray(Charsets.UTF_8)
            val out = StringBuilder(raw.size)
            for (i in raw.indices) {
                out.append(((raw[i].toInt() and 0xFF) xor (kb[i % kb.size].toInt() and 0xFF)).toChar())
            }
            val text = out.toString()
            if (text.isEmpty() || text.any { it < ' ' || it > '~' }) "" else text
        } catch (_: IllegalArgumentException) {
            ""
        }
    }

    private fun play4uSealKeyOf(html: String): String =
        play4uSealKeyPattern.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: play4uDefaultSealKey

    private fun extractWindowJsonObject(html: String, varName: String): JSONObject? {
        val marker = "window.$varName = "
        val at = html.indexOf(marker)
        if (at < 0) return null
        var start = html.indexOf('=', at) + 1
        while (start < html.length && html[start].isWhitespace()) start++
        if (start >= html.length || html[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            when (c) {
                '\\' -> escaped = true
                '"' -> inString = !inString
                '{', '[' -> if (!inString) depth++
                '}', ']' -> if (!inString) {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONObject(html.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun parseQualityHeightFromArray(arr: JSONArray?): Int? {
        if (arr == null) return null
        var maxH: Int? = null
        for (i in 0 until arr.length()) {
            val qObj = arr.optJSONObject(i) ?: continue
            val h = qObj.optInt("height", -1).takeIf { it > 0 }
                ?: qObj.optString("slug").removeSuffix("p").toIntOrNull()
            if (h != null && (maxH == null || h > maxH)) {
                maxH = h
            }
        }
        return maxH
    }

    private fun parseAudioLabelFromArray(arr: JSONArray?): String? {
        if (arr == null) return null
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val lObj = arr.optJSONObject(i) ?: continue
            val n = lObj.optString("name").trim()
            if (n.isNotBlank()) names.add(n)
        }
        return names.joinToString("/").takeIf { it.isNotBlank() }
    }

    // Prmovies Helpers
    private fun prmoviesMutexFor(url: String): Mutex =
        prmoviesMutexMap.getOrPut(runCatching { Uri.parse(url).host }.getOrNull() ?: activePrmoviesBase) { Mutex() }

    private fun prmoviesKillerFor(url: String): CloudflareKiller =
        prmoviesCfMap.getOrPut(runCatching { Uri.parse(url).host }.getOrNull() ?: activePrmoviesBase) { CloudflareKiller() }

    private suspend fun fetchPrmoviesSingleUrl(targetUrl: String, referer: String? = null, timeoutSec: Long = 4L): NiceResponse? {
        return runCatching {
            val response = app.get(targetUrl, headers = prmoviesHeaders, referer = referer, timeout = timeoutSec)
            val resolved = if (response.code !in listOf(403, 503)) {
                response
            } else {
                prmoviesMutexFor(targetUrl).withLock {
                    val killer = prmoviesKillerFor(targetUrl)
                    app.get(targetUrl, headers = prmoviesHeaders, interceptor = killer, referer = referer, timeout = timeoutSec)
                }
            }
            if (resolved.code in 200..299) resolved else null
        }.getOrNull()
    }

    private suspend fun getPrmoviesPage(url: String, referer: String? = null, timeoutSec: Long = 4L): NiceResponse? {
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
        callback: suspend (ExtractorLink) -> Unit
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
        callback: suspend (ExtractorLink) -> Unit
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
        callback: suspend (ExtractorLink) -> Unit
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
        callback: suspend (ExtractorLink) -> Unit
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

    // VegaMovies Helpers (keeps PixelDrain, FSL, FSL2; skips 10Gbps, G-Direct, V-Drive)
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

    // Only keep PixelDrain, FSL, FSL2; skip 10Gbps, G-Direct, V-Drive
    private fun vegaAllowedServerName(label: String, href: String): String? {
        val low = label.lowercase()
        val h = href.lowercase()
        if (
            low.contains("10gbps") || h.contains("gpdl") ||
            low.contains("g-direct") || low.contains("gdirect") || low.contains("fastdl") || h.contains("fastdl") ||
            low.contains("vegadrive") || low.contains("v-drive") || low.contains("skydrop") || h.contains("vegadrive")
        ) {
            return null
        }
        return when {
            low.contains("fslv2") || low.contains("fsl v2") || low.contains("fsl2") || h.contains(".r2.cloudflarestorage.com") -> "FSL2"
            low.contains("fsl") || h.contains(".r2.dev") -> "FSL"
            low.contains("pixelserver") || low.contains("pixel server") || low.contains("pixeldrain") || h.contains("pixeldrain") -> "PixelDrain"
            else -> null
        }
    }

    private fun vegaPixelApi(href: String): String {
        val id = href.substringBefore("?").substringBefore("#").substringAfterLast("/")
        return "${baseHostOf(href)}/api/file/$id"
    }

    private suspend fun vegaCollectFromHtml(html: String, baseUrl: String, timeoutSec: Long): List<VegaServer> {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://nexdrive.beer/",
            "Cookie" to "xla=s4t"
        )
        val tokenRegex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]""")
        val plainRegex = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")

        var target = tokenRegex.find(html)?.groupValues?.get(1)
            ?.let { runCatching { base64Decode(base64Decode(it.trim())) }.getOrNull() }
            ?: plainRegex.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        var fromUrl = baseUrl

        if (target == null || !target.startsWith("http")) {
            val hop = Jsoup.parse(html).selectFirst("div.main h4 a")?.attr("href")?.trim()
            if (!hop.isNullOrBlank()) {
                fromUrl = if (hop.startsWith("http")) hop else baseHostOf(baseUrl) + hop
                val hopHtml = runCatching {
                    app.get(fromUrl, headers = headers + mapOf("Referer" to baseUrl), timeout = timeoutSec).text
                }.getOrNull().orEmpty()
                target = tokenRegex.find(hopHtml)?.groupValues?.get(1)
                    ?.let { runCatching { base64Decode(base64Decode(it.trim())) }.getOrNull() }
                    ?: plainRegex.find(hopHtml)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
            }
        }

        val finalHtml = if (!target.isNullOrBlank() && target.startsWith("http")) {
            runCatching {
                app.get(target, headers = headers + mapOf("Referer" to fromUrl), timeout = timeoutSec).text
            }.getOrNull().orEmpty()
        } else {
            html
        }

        val out = mutableListOf<VegaServer>()
        for (a in Jsoup.parse(finalHtml).select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http") || vegaJunk.containsMatchIn(href)) continue
            val label = a.text().replace(Regex("""\s+"""), " ").trim()
            val sName = vegaAllowedServerName(label, href) ?: continue
            val resolvedUrl = if (sName == "PixelDrain") vegaPixelApi(href) else href
            out.add(VegaServer(sName, resolvedUrl))
        }
        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(finalHtml)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") && it.contains("pixeldrain") }
            ?.let { pxl -> out.add(VegaServer("PixelDrain", vegaPixelApi(pxl))) }

        return out.distinctBy { it.url }
    }

    private suspend fun resolveVegaHref(href: String, timeoutSec: Long): List<VegaServer> {
        if (!href.startsWith("http") || vegaDeadHosts.containsMatchIn(href)) return emptyList()
        val host = hostLower(href)
        if (host.contains("gpdl") || host.contains("fastdl") || host.contains("vegadrive")) return emptyList()

        return runCatching {
            when {
                host.contains("vcloud") -> {
                    val html = app.get(
                        href,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://nexdrive.beer/", "Cookie" to "xla=s4t"),
                        timeout = timeoutSec
                    ).text
                    vegaCollectFromHtml(html, href, timeoutSec)
                }
                host.contains("hubcloud") -> {
                    val res = app.get(href, headers = mapOf("User-Agent" to USER_AGENT), timeout = timeoutSec)
                    val doc = Jsoup.parse(res.text)
                    val header = doc.selectFirst("div.card-header")?.text()?.trim().orEmpty()
                    if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(header)) return@runCatching emptyList()
                    val generate = doc.select("a.btn, a[download]")
                        .firstOrNull { it.text().contains("generate", true) }
                        ?.attr("href")?.trim()
                    val genHtml = if (!generate.isNullOrBlank()) {
                        val genUrl = if (generate.startsWith("http")) generate else baseHostOf(res.url) + generate
                        app.get(genUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to res.url), timeout = timeoutSec).text
                    } else {
                        res.text
                    }
                    vegaCollectFromHtml(genHtml, res.url, timeoutSec)
                }
                href.contains(".r2.cloudflarestorage.com", true) -> listOf(VegaServer("FSL2", href))
                href.contains(".r2.dev", true) -> listOf(VegaServer("FSL", href))
                href.contains("pixeldrain", true) -> listOf(VegaServer("PixelDrain", vegaPixelApi(href)))
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
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
            val timeoutSec = appCtx?.let { MovieFoxSettingsDialog.getTimeoutSeconds(it).toLong().coerceAtLeast(3L) } ?: 4L
            val perProviderCapMs = (timeoutSec * 1000L) + 1500L
            val streamOnly = appCtx?.let { MovieFoxSettingsDialog.isStreamOnly(it) } ?: false
            val downloadOnly = appCtx?.let { MovieFoxSettingsDialog.isDownloadOnly(it) } ?: false
            val maxConcurrency = appCtx?.let { MovieFoxSettingsDialog.getParallelServers(it) } ?: 16

            val cleanPageUrl = data.substringBefore("?")
            val rawHtml = withTimeoutOrNull(perProviderCapMs) {
                app.get(cleanPageUrl, interceptor = cfKiller, timeout = timeoutSec).text
            }.orEmpty()
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

            val baseHeaders = mapOf(
                "Referer" to watchUrl,
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/plain, */*"
            )

            val seenUrls = ConcurrentHashMap.newKeySet<String>()
            val collectedLinks = java.util.Collections.synchronizedList(mutableListOf<QueuedLink>())
            val emitMutex = Mutex()
            var found = false

            suspend fun emitNow(extractorLink: ExtractorLink, isStream: Boolean) {
                if (downloadOnly && isStream) return
                emitMutex.withLock {
                    collectedLinks.add(QueuedLink(extractorLink, isStream))
                    found = true
                    callback(extractorLink)
                }
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
                emitNow(link, isStream = isHls)
            }

            coroutineScope {
                val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                // 1. Purstream / Castle / Watchout APIs
                val apiEndpoints = mutableListOf<Pair<String, String>>()
                for (cid in candidateIds) {
                    apiEndpoints.add("purstream" to "$mainUrl/api/stream/purstream?type=$contentType&id=$cid$imdbQuery$tvQuery")
                    apiEndpoints.add("castle" to "$mainUrl/api/stream/castle?type=$contentType&id=$cid$imdbQuery$tvQuery")
                    apiEndpoints.add("watchout" to "$mainUrl/api/stream/watchout?type=$contentType&id=$cid$imdbQuery$tvQuery")
                }
                val activeApis = apiEndpoints
                    .distinctBy { it.second }
                    .filter { (key, _) -> appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, key) } ?: true }
                    .take(maxConcurrency)

                activeApis.forEach { (endpointName, apiUrl) ->
                    tasks.add(async(Dispatchers.IO) {
                        withTimeoutOrNull(perProviderCapMs) {
                            val tStart = System.currentTimeMillis()
                            val response = runCatching {
                                app.get(apiUrl, headers = baseHeaders, interceptor = cfKiller, timeout = timeoutSec)
                            }.getOrNull()

                            if (response == null || response.text.isBlank()) {
                                MovieFoxSettingsDialog.reportServerResult(appCtx, endpointName, false)
                                return@withTimeoutOrNull
                            }
                            MovieFoxSettingsDialog.reportServerResult(appCtx, endpointName, true, System.currentTimeMillis() - tStart)

                            val rawText = response.text
                            if (response.headers["Content-Type"].orEmpty().contains("mpegurl", true) || rawText.trimStart().startsWith("#EXTM3U")) {
                                emitServerLink("MovieFox ${endpointName.uppercase()} HLS", apiUrl, true, customHeaders = baseHeaders)
                                return@withTimeoutOrNull
                            }

                            val parsed = runCatching { parseJson<StreamResponse>(rawText) }.getOrNull() ?: return@withTimeoutOrNull
                            if (parsed.bid != null || !parsed.domains.isNullOrEmpty()) return@withTimeoutOrNull

                            val allStreams = buildList {
                                addAll(parsed.streams)
                                addAll(parsed.sources)
                                val fallbackSingle = parsed.url ?: parsed.file ?: parsed.streamUrl
                                if (!fallbackSingle.isNullOrBlank()) add(StreamItem(url = fallbackSingle, provider = endpointName))
                            }

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
                                                lang = sub.label.ifBlank { sub.language.ifBlank { sub.lang.ifBlank { "English" } } },
                                                url = normalizeUrl(rawSubUrl)
                                            )
                                        )
                                    }
                                }

                                val mergedHeaders = baseHeaders + (stream.headers ?: emptyMap())
                                val isHls = isM3u8Stream(streamUrl, stream.type)
                                val finalLabel = stream.label?.takeIf { it.isNotBlank() }
                                    ?: "${endpointName.uppercase()} (${stream.quality ?: "HD"})"

                                emitServerLink(finalLabel, streamUrl, isHls, stream.quality, mergedHeaders)
                            }
                        }
                        Unit
                    })
                }

                // 2. VegaMovies (PixelDrain, FSL, FSL2 only)
                val vegaEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "vegamovies") } ?: true
                if (vegaEnabled && pageTitle.isNotBlank()) {
                    tasks.add(async(Dispatchers.IO) {
                        withTimeoutOrNull(perProviderCapMs) {
                            val vStart = System.currentTimeMillis()
                            var vSuccess = false
                            runCatching {
                                val targetSeason = season.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                val targetEpisode = episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                val roundDocs = vegamoviesDomains.map { dom ->
                                    async(Dispatchers.IO) { searchVegaPosts(dom, pageTitle, timeoutSec) }
                                }.flatMap { it.await() }

                                val chosen = pickVegaPost(roundDocs, pageTitle, isTv, targetSeason) ?: return@runCatching
                                val permalink = chosen.permalink?.takeIf { it.isNotBlank() } ?: return@runCatching
                                val domain = chosen.domain.ifBlank { vegamoviesDomains.first() }
                                val postHtml = runCatching {
                                    app.get(
                                        normalizeUrl(permalink, domain),
                                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$domain/"),
                                        interceptor = cfKiller,
                                        timeout = timeoutSec
                                    ).text
                                }.getOrNull().orEmpty()

                                val detail = if (postHtml.isNotBlank()) parseVegaPost(postHtml) else null
                                    ?: return@runCatching

                                val groups = if (isTv) {
                                    detail.seasons.filter { it.number == targetSeason }.flatMap { it.groups }.ifEmpty {
                                        detail.seasons.filter { seasonsInText(it.title) == null }.flatMap { it.groups }
                                    }
                                } else {
                                    detail.movieGroups
                                }

                                val gate = Semaphore(4)
                                groups.map { group ->
                                    async(Dispatchers.IO) {
                                        gate.withPermit {
                                            val tier = parseIndexQuality(group.label)
                                            val cleanInfo = group.label.replace(Regex("""(?i)\bdownload\s+now\b|\bdownload\b"""), "").replace(Regex("""\s+"""), " ").trim()
                                            for ((_, driveUrl) in group.links.distinctBy { it.second }) {
                                                val drivePage = fetchVegaDrivePage(driveUrl, timeoutSec) ?: continue
                                                val targets = if (isTv) {
                                                    drivePage.episodes[targetEpisode] ?: drivePage.servers.takeIf { drivePage.episodes.isEmpty() }
                                                } else {
                                                    drivePage.servers
                                                }.orEmpty()

                                                for ((_, href) in targets) {
                                                    val nextList = if (href.contains("nexdrive", true)) {
                                                        val nested = fetchVegaDrivePage(href, timeoutSec) ?: continue
                                                        if (isTv) nested.episodes[targetEpisode] ?: nested.servers.takeIf { nested.episodes.isEmpty() }
                                                        else nested.servers
                                                    } else {
                                                        listOf("" to href)
                                                    }.orEmpty()

                                                    for ((_, href2) in nextList) {
                                                        for (srv in resolveVegaHref(href2, timeoutSec)) {
                                                            val sUrl = normalizeUrl(srv.url)
                                                            if (sUrl.isBlank() || isAdUrl(sUrl) || !seenUrls.add(sUrl)) continue
                                                            val exLink = newExtractorLink(
                                                                source = "VegaMovies",
                                                                name = buildSourceLabel("VegaMovies", srv.name, cleanInfo),
                                                                url = sUrl,
                                                                type = ExtractorLinkType.VIDEO
                                                            ) {
                                                                this.quality = tier
                                                                this.headers = mapOf("User-Agent" to USER_AGENT)
                                                            }
                                                            emitNow(exLink, isStream = false)
                                                            vSuccess = true
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }.awaitAll()
                            }
                            MovieFoxSettingsDialog.reportServerResult(appCtx, "vegamovies", vSuccess, System.currentTimeMillis() - vStart)
                        }
                        Unit
                    })
                }

                // 3. Sflix
                val sflixEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "sflix") } ?: true
                if (sflixEnabled && pageTitle.isNotBlank()) {
                    tasks.add(async(Dispatchers.IO) {
                        withTimeoutOrNull(perProviderCapMs) {
                            val sStart = System.currentTimeMillis()
                            var sSuccess = false
                            runCatching {
                                val sflixMainUrl = "https://sflix.film"
                                val sflixPlayerUrl = "https://mzfi.me"
                                val sflixDomains = listOf("https://sflix.film", "https://h5-api.aoneroom.com")
                                val sflixUa = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"
                                val encodedTitle = URLEncoder.encode(pageTitle, "UTF-8")
                                val sflixSearchDoc = app.get(
                                    "$sflixMainUrl/search-result?keyword=$encodedTitle",
                                    headers = mapOf("Referer" to "$sflixMainUrl/", "User-Agent" to sflixUa),
                                    interceptor = cfKiller,
                                    timeout = timeoutSec
                                ).document
                                val detailLink = sflixSearchDoc.select("a.video-item[href*='/detail/'], a[href*='/detail/']")
                                    .firstOrNull { el ->
                                        val t = (el.selectFirst("h3.text, .video-name, h3")?.text() ?: el.attr("title")).trim()
                                        t.contains(pageTitle, ignoreCase = true) || pageTitle.contains(t, ignoreCase = true)
                                    }?.attr("href") ?: return@runCatching

                                val fullDetailUrl = if (detailLink.startsWith("http")) detailLink else "$sflixMainUrl/${detailLink.trimStart('/')}"
                                val subjectId = Regex("""[?&]id=(\d+)""").find(fullDetailUrl)?.groupValues?.get(1).orEmpty()
                                val detailPath = fullDetailUrl.substringAfter("/detail/").substringBefore("?")
                                if (subjectId.isEmpty()) return@runCatching

                                val sflixSe = if (isTv) season.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
                                val sflixEp = if (isTv) episode.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0

                                for (domain in sflixDomains) {
                                    val playUrl = "$domain/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$sflixSe&ep=$sflixEp&detailPath=$detailPath&streamSignType=1"
                                    val playResp = runCatching {
                                        app.get(
                                            playUrl,
                                            headers = mapOf("User-Agent" to sflixUa, "Referer" to "$sflixPlayerUrl/", "Origin" to sflixPlayerUrl, "Accept" to "application/json"),
                                            timeout = timeoutSec
                                        )
                                    }.getOrNull() ?: continue
                                    if (playResp.code != 200) continue
                                    val dataObj = runCatching { JSONObject(playResp.text).optJSONObject("data") }.getOrNull() ?: continue

                                    listOf("dash" to ExtractorLinkType.DASH, "hls" to ExtractorLinkType.M3U8, "streams" to ExtractorLinkType.VIDEO).forEach { (k, lType) ->
                                        dataObj.optJSONArray(k)?.let { arr ->
                                            for (i in 0 until arr.length()) {
                                                val item = arr.optJSONObject(i) ?: continue
                                                val sUrl = item.optString("url").trim()
                                                if (sUrl.isBlank() || isAdUrl(sUrl) || !seenUrls.add(sUrl)) continue
                                                val maxRes = item.optString("resolutions").split(",").mapNotNull { it.trim().toIntOrNull() }.maxOrNull() ?: 720
                                                val exLink = newExtractorLink(
                                                    source = "Sflix",
                                                    name = "Sflix [${k.uppercase()} ${maxRes}p]",
                                                    url = sUrl,
                                                    type = lType
                                                ) {
                                                    this.referer = "$sflixPlayerUrl/"
                                                    this.headers = mapOf("Origin" to sflixPlayerUrl, "Referer" to "$sflixPlayerUrl/", "User-Agent" to sflixUa)
                                                    this.quality = maxRes
                                                }
                                                emitNow(exLink, isStream = lType != ExtractorLinkType.VIDEO)
                                                sSuccess = true
                                            }
                                        }
                                    }
                                    if (sSuccess) break
                                }
                            }
                            MovieFoxSettingsDialog.reportServerResult(appCtx, "sflix", sSuccess, System.currentTimeMillis() - sStart)
                        }
                        Unit
                    })
                }

                // 4. Prmovies
                val prmoviesEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "prmovies") } ?: true
                if (prmoviesEnabled && pageTitle.isNotBlank()) {
                    tasks.add(async(Dispatchers.IO) {
                        withTimeoutOrNull(perProviderCapMs) {
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
                                            a.attr("oldtitle").ifBlank { a.attr("title") }
                                                .ifBlank { card.selectFirst(".mli-info h2")?.text().orEmpty() }
                                                .ifBlank { card.selectFirst("img.mli-thumb")?.attr("alt").orEmpty() }
                                            ).trim()
                                        if (candidateTitle.isBlank()) return@firstOrNull false
                                        val cardIsSeries = href.contains("/series/", true) || Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\d{1,2}\b""").containsMatchIn(candidateTitle)
                                        val typeMatches = if (isTv) cardIsSeries else !cardIsSeries
                                        val titleMatches = candidateTitle.contains(pageTitle, ignoreCase = true) || pageTitle.contains(candidateTitle, ignoreCase = true)
                                        typeMatches && titleMatches
                                    }
                                    val matchedUrl = matchedCard?.selectFirst("a.ml-mask")?.attr("href")?.let { normalizeUrl(it, activePrmoviesBase) }
                                    if (!matchedUrl.isNullOrBlank()) {
                                        val detailHtml = getPrmoviesPage(matchedUrl, timeoutSec = timeoutSec)?.text.orEmpty()
                                        if (detailHtml.isNotBlank()) {
                                            val detailDoc = Jsoup.parse(detailHtml, activePrmoviesBase)
                                            val ok = if (matchedUrl.contains("/episode/", true)) {
                                                resolvePrmoviesEpisodePage(detailDoc, matchedUrl, seenUrls, timeoutSec) { ex ->
                                                    emitNow(ex, isStream = isStreamOrHlsLink(ex.url, ex.type))
                                                }
                                            } else {
                                                resolvePrmoviesPagePlayer(detailDoc, seenUrls, timeoutSec) { ex ->
                                                    emitNow(ex, isStream = isStreamOrHlsLink(ex.url, ex.type))
                                                }
                                            }
                                            if (ok) prSuccess = true
                                        }
                                    }
                                }
                            }
                            MovieFoxSettingsDialog.reportServerResult(appCtx, "prmovies", prSuccess, System.currentTimeMillis() - prStart)
                        }
                        Unit
                    })
                }

                // 5. Play4u
                val play4uEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "play4u") } ?: true
                if (play4uEnabled && pageTitle.isNotBlank()) {
                    tasks.add(async(Dispatchers.IO) {
                        withTimeoutOrNull(perProviderCapMs) {
                            val p4Start = System.currentTimeMillis()
                            var p4Success = false
                            runCatching {
                                val targetSeason = season.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                val targetEpisode = episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                val p4Headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json, text/html;q=0.9, */*;q=0.8", "Referer" to "$play4uBaseUrl/")
                                val p4StreamHeaders = mapOf("Referer" to "$play4uBaseUrl/", "Origin" to play4uBaseUrl, "User-Agent" to USER_AGENT)

                                var pageHtml: String? = null
                                if (imdbId.startsWith("tt") && imdbId.length > 2) {
                                    val directUrl = if (isTv) "$play4uBaseUrl/series/$imdbId" else "$play4uBaseUrl/watch/$imdbId"
                                    val candidateHtml = runCatching { app.get(directUrl, headers = p4Headers, interceptor = cfKiller, timeout = timeoutSec).text }.getOrNull()
                                    if (!candidateHtml.isNullOrBlank()) {
                                        val ok = if (isTv) extractWindowJsonObject(candidateHtml, "__PA_SERIES__") != null
                                        else extractWindowJsonObject(candidateHtml, "__PA_PLAY__")?.optBoolean("ok") == true
                                        if (ok) pageHtml = candidateHtml
                                    }
                                }
                                if (pageHtml == null) {
                                    val searchText = runCatching {
                                        app.get("$play4uBaseUrl/api/search.php?q=${URLEncoder.encode(pageTitle, "UTF-8")}&page=1&per_page=30", headers = p4Headers, interceptor = cfKiller, timeout = timeoutSec).text
                                    }.getOrNull().orEmpty()
                                    val itemsArr = runCatching { JSONObject(searchText).optJSONArray("items") }.getOrNull()
                                    if (itemsArr != null) {
                                        for (i in 0 until itemsArr.length()) {
                                            val item = itemsArr.optJSONObject(i) ?: continue
                                            val mType = item.optString("media_type")
                                            if (mType == "series" && !isTv) continue
                                            if (mType == "movie" && isTv) continue
                                            val itemTitle = item.optString("title").replace(Regex("""\s*\((19|20)\d{2}\)\s*$"""), "").trim()
                                            if (!titleMatchesCandidate(itemTitle, pageTitle)) continue
                                            val watchPath = item.optString("watch").takeIf { it.startsWith("/") } ?: continue
                                            val candidateHtml = runCatching { app.get("$play4uBaseUrl$watchPath", headers = p4Headers, interceptor = cfKiller, timeout = timeoutSec).text }.getOrNull() ?: continue
                                            val ok = if (isTv) extractWindowJsonObject(candidateHtml, "__PA_SERIES__") != null
                                            else extractWindowJsonObject(candidateHtml, "__PA_PLAY__")?.optBoolean("ok") == true
                                            if (ok) { pageHtml = candidateHtml; break }
                                        }
                                    }
                                }
                                if (!pageHtml.isNullOrBlank()) {
                                    val sealKey = play4uSealKeyOf(pageHtml)
                                    if (!isTv) {
                                        val streamsArr = extractWindowJsonObject(pageHtml, "__PA_PLAY__")?.optJSONArray("streams")
                                        if (streamsArr != null) {
                                            for (i in 0 until streamsArr.length()) {
                                                val sObj = streamsArr.optJSONObject(i) ?: continue
                                                val linkUrl = play4uUnseal(sObj.optString("m3u8_url"), sealKey).ifBlank {
                                                    val dName = sObj.optString("domain_name").trim()
                                                    val mPath = sObj.optString("m3u8_path").trim()
                                                    if (dName.isNotBlank() && mPath.startsWith("/")) "https://$dName$mPath" else ""
                                                }.let { if (it.isNotBlank()) normalizeUrl(it, play4uBaseUrl) else "" }
                                                if (linkUrl.isBlank() || !linkUrl.startsWith("http") || isAdUrl(linkUrl) || !seenUrls.add(linkUrl)) continue
                                                val qH = parseQualityHeightFromArray(sObj.optJSONArray("qualities"))
                                                val infoStr = listOfNotNull(qH?.let { "${it}p" }, parseAudioLabelFromArray(sObj.optJSONArray("languages"))).joinToString(" ")
                                                val exLink = newExtractorLink(source = "Play4u", name = buildSourceLabel("Play4u", sObj.optString("label").ifBlank { "Play4u" }, infoStr), url = linkUrl, type = ExtractorLinkType.M3U8) {
                                                    this.quality = qH ?: Qualities.Unknown.value
                                                    this.headers = p4StreamHeaders
                                                    this.referer = "$play4uBaseUrl/"
                                                }
                                                emitNow(exLink, isStream = true)
                                                p4Success = true
                                            }
                                        }
                                    } else {
                                        val seasonsObj = extractWindowJsonObject(pageHtml, "__PA_SERIES__")?.optJSONObject("seasons")
                                        val matchedSeason = seasonsObj?.let { sMap ->
                                            var foundS: JSONObject? = null
                                            for (k in sMap.keys()) {
                                                val o = sMap.optJSONObject(k) ?: continue
                                                if (o.optInt("number", -1) == targetSeason || k.toIntOrNull() == targetSeason) { foundS = o; break }
                                            }
                                            foundS ?: if (sMap.length() == 1) sMap.optJSONObject(sMap.keys().next()) else null
                                        }
                                        val episodesObj = matchedSeason?.optJSONObject("episodes")
                                        if (episodesObj != null) {
                                            for (ek in episodesObj.keys()) {
                                                val epObj = episodesObj.optJSONObject(ek) ?: continue
                                                if (epObj.optInt("number", -1) != targetEpisode) continue
                                                val linkUrl = play4uUnseal(epObj.optString("hls"), sealKey).let { if (it.isNotBlank()) normalizeUrl(it, play4uBaseUrl) else "" }
                                                if (linkUrl.startsWith("http") && !isAdUrl(linkUrl) && seenUrls.add(linkUrl)) {
                                                    val qH = parseQualityHeightFromArray(epObj.optJSONArray("qualities"))
                                                    val infoStr = listOfNotNull(qH?.let { "${it}p" }, parseAudioLabelFromArray(epObj.optJSONArray("languages"))).joinToString(" ")
                                                    val exLink = newExtractorLink(source = "Play4u", name = buildSourceLabel("Play4u", "HLS", infoStr), url = linkUrl, type = ExtractorLinkType.M3U8) {
                                                        this.quality = qH ?: Qualities.Unknown.value
                                                        this.headers = p4StreamHeaders
                                                        this.referer = "$play4uBaseUrl/"
                                                    }
                                                    emitNow(exLink, isStream = true)
                                                    p4Success = true
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            MovieFoxSettingsDialog.reportServerResult(appCtx, "play4u", p4Success, System.currentTimeMillis() - p4Start)
                        }
                        Unit
                    })
                }

                // 6. MovieBox BFF (netnaija.film BFF)
                val movieBoxEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "moviebox") } ?: true
                if (movieBoxEnabled && pageTitle.isNotBlank()) {
                    tasks.add(async(Dispatchers.IO) {
                        withTimeoutOrNull(perProviderCapMs) {
                            val mbStart = System.currentTimeMillis()
                            var mbSuccess = false
                            runCatching {
                                val targetSeason = if (isTv) (season.toIntOrNull()?.coerceAtLeast(1) ?: 1) else 0
                                val targetEpisode = if (isTv) (episode.toIntOrNull()?.coerceAtLeast(1) ?: 1) else 0
                                val mbPlayHeaders = mapOf("Referer" to "$movieBoxSiteUrl/", "Origin" to movieBoxSiteUrl, "User-Agent" to movieBoxUa)

                                val searchRaw = runCatching {
                                    app.post(
                                        "$movieBoxBff/subject/search",
                                        headers = movieBoxHeaders(mapOf("Content-Type" to "application/json")),
                                        json = JSONObject().put("keyword", pageTitle).put("page", 1).put("perPage", 15).toString(),
                                        timeout = timeoutSec
                                    ).text
                                }.getOrNull().orEmpty()

                                val itemsArr = runCatching { JSONObject(searchRaw).optJSONObject("data")?.optJSONArray("items") }.getOrNull()
                                var matchedDetailPath: String? = null
                                if (itemsArr != null) {
                                    for (i in 0 until itemsArr.length()) {
                                        val item = itemsArr.optJSONObject(i) ?: continue
                                        val itemTitle = item.optString("title").trim()
                                        val dPath = item.optString("detailPath").trim()
                                        if (dPath.isBlank() || itemTitle.isBlank()) continue
                                        if ((item.optInt("subjectType", 1) == 1) == isTv) continue
                                        if (titleMatchesCandidate(itemTitle, pageTitle)) { matchedDetailPath = dPath; break }
                                    }
                                }
                                if (!matchedDetailPath.isNullOrBlank()) {
                                    val detailRaw = runCatching { app.get("$movieBoxBff/detail?detailPath=$matchedDetailPath", headers = movieBoxHeaders(), timeout = timeoutSec).text }.getOrNull().orEmpty()
                                    val subjectObj = runCatching { JSONObject(detailRaw).optJSONObject("data")?.optJSONObject("subject") }.getOrNull()
                                    if (subjectObj != null) {
                                        val dubsArr = subjectObj.optJSONArray("dubs") ?: JSONArray().apply {
                                            put(JSONObject().put("subjectId", subjectObj.optString("subjectId")).put("detailPath", matchedDetailPath).put("lanName", "Original Audio").put("lanCode", "en").put("type", 0).put("original", true))
                                        }
                                        val seenSubUrls = mutableSetOf<String>()
                                        for (dIdx in 0 until dubsArr.length()) {
                                            val dub = dubsArr.optJSONObject(dIdx) ?: continue
                                            val dubId = dub.optString("subjectId").trim()
                                            val dubPath = dub.optString("detailPath").trim().ifBlank { matchedDetailPath }
                                            if (dubId.isBlank()) continue
                                            val label = movieBoxDubLabel(dub) ?: continue
                                            val playRaw = runCatching {
                                                app.get(
                                                    "$movieBoxBff/subject/play?subjectId=$dubId&se=$targetSeason&ep=$targetEpisode&detailPath=$dubPath",
                                                    headers = movieBoxHeaders(mapOf("X-Source" to "webNetnaijaSite", "Referer" to "$movieBoxSiteUrl/videoPlayPage/$dubPath")),
                                                    timeout = timeoutSec
                                                ).text
                                            }.getOrNull().orEmpty()
                                            val playData = runCatching { JSONObject(playRaw).optJSONObject("data") }.getOrNull() ?: continue
                                            var firstStreamId: String? = null

                                            playData.optJSONArray("streams")?.let { stArr ->
                                                for (j in 0 until stArr.length()) {
                                                    val st = stArr.optJSONObject(j) ?: continue
                                                    if (st.optBoolean("vipLocked")) continue
                                                    val sUrl = st.optString("url").trim()
                                                    val resStr = st.optString("resolutions").trim()
                                                    if (sUrl.isBlank() || resStr.isBlank() || isAdUrl(sUrl) || !seenUrls.add(sUrl)) continue
                                                    if (firstStreamId == null) firstStreamId = st.optString("id").takeIf { it.isNotBlank() }
                                                    val qLabel = "${resStr.toIntOrNull() ?: resStr}p"
                                                    val exLink = newExtractorLink(source = "MovieBox", name = "MovieBox $label $qLabel", url = sUrl, type = ExtractorLinkType.VIDEO) {
                                                        this.quality = getQuality(qLabel)
                                                        this.headers = mbPlayHeaders
                                                        this.referer = "$movieBoxSiteUrl/"
                                                    }
                                                    emitNow(exLink, isStream = false)
                                                    mbSuccess = true
                                                }
                                            }
                                            listOf("dash" to ExtractorLinkType.DASH, "hls" to ExtractorLinkType.M3U8).forEach { (k, lType) ->
                                                playData.optJSONArray(k)?.let { arr ->
                                                    for (j in 0 until arr.length()) {
                                                        val u = arr.optJSONObject(j)?.optString("url").orEmpty().trim()
                                                        if (u.isBlank() || isAdUrl(u) || !seenUrls.add(u)) continue
                                                        val exLink = newExtractorLink(source = "MovieBox", name = "MovieBox $label ${k.uppercase()}", url = u, type = lType) {
                                                            this.quality = Qualities.P1080.value
                                                            this.headers = mbPlayHeaders
                                                            this.referer = "$movieBoxSiteUrl/"
                                                        }
                                                        emitNow(exLink, isStream = true)
                                                        mbSuccess = true
                                                    }
                                                }
                                            }
                                            if (firstStreamId != null && (dub.optBoolean("original") || seenSubUrls.isEmpty())) {
                                                runCatching {
                                                    val capRaw = app.get("$movieBoxBff/subject/caption?format=MP4&id=$firstStreamId&subjectId=$dubId&detailPath=$dubPath", headers = movieBoxHeaders(mapOf("X-Source" to "webNetnaijaSite")), timeout = timeoutSec).text
                                                    val capsArr = JSONObject(capRaw).optJSONObject("data")?.optJSONArray("captions")
                                                    if (capsArr != null) {
                                                        for (c in 0 until capsArr.length()) {
                                                            val cObj = capsArr.optJSONObject(c) ?: continue
                                                            val subUrl = cObj.optString("url").trim()
                                                            if (subUrl.isNotBlank() && seenSubUrls.add(subUrl)) {
                                                                subtitleCallback(newSubtitleFile(lang = cObj.optString("lanName").ifBlank { "Unknown" }, url = subUrl))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            MovieFoxSettingsDialog.reportServerResult(appCtx, "moviebox", mbSuccess, System.currentTimeMillis() - mbStart)
                        }
                        Unit
                    })
                }

                // 7. NetNaija (Direct Scraper)
                val netnaijaEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "netnaija") } ?: true
                if (netnaijaEnabled && pageTitle.isNotBlank()) {
                    tasks.add(async(Dispatchers.IO) {
                        withTimeoutOrNull(perProviderCapMs) {
                            val nnStart = System.currentTimeMillis()
                            var nnSuccess = false
                            runCatching {
                                val targetSeason = season.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                val targetEpisode = episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                val encodedQuery = URLEncoder.encode(pageTitle, "UTF-8")
                                val searchDoc = getNetnaijaDoc("/search?t=$encodedQuery", timeoutSec)
                                    ?: getNetnaijaDoc("/?s=$encodedQuery", timeoutSec)
                                    ?: return@runCatching

                                val matchedPostUrl = searchDoc.select("article.sr-one h2 a, article h2.entry-title a, .search-results article a[href]")
                                    .firstOrNull { a ->
                                        val href = a.attr("href").trim()
                                        val candTitle = a.text().trim()
                                        if (href.isBlank() || candTitle.isBlank()) return@firstOrNull false
                                        val isVideoPost = href.contains("/videos/", true) ||
                                            href.contains("/movies/", true) ||
                                            href.contains("/series/", true)
                                        val candIsSeries = href.contains("/series/", true) ||
                                            Regex("""(?i)\bseason\s*\d|\bs\d{1,2}\b""").containsMatchIn(candTitle)
                                        isVideoPost && (candIsSeries == isTv) && titleMatchesCandidate(candTitle, pageTitle)
                                    }
                                    ?.attr("href")
                                    ?.let { normalizeUrl(it, activeNetnaijaBase) }
                                    ?: return@runCatching

                                var targetPageUrl = matchedPostUrl
                                var targetDoc = getNetnaijaDoc(targetPageUrl, timeoutSec) ?: return@runCatching

                                if (isTv) {
                                    // Navigate Season -> Episode if series landing page
                                    val seasonLink = targetDoc.select("a[href*='/season-'], .video-seasons a[href]")
                                        .firstOrNull { a ->
                                            val txt = a.text() + " " + a.attr("href")
                                            Regex("""(?i)season[\s-]*0*$targetSeason\b""").containsMatchIn(txt)
                                        }
                                        ?.attr("href")
                                        ?.let { normalizeUrl(it, activeNetnaijaBase) }

                                    if (!seasonLink.isNullOrBlank()) {
                                        targetPageUrl = seasonLink
                                        targetDoc = getNetnaijaDoc(targetPageUrl, timeoutSec) ?: return@runCatching
                                    }

                                    val epLink = targetDoc.select("a[href*='/episode-'], .video-episodes a[href], article h2 a[href]")
                                        .firstOrNull { a ->
                                            val txt = a.text() + " " + a.attr("href")
                                            Regex("""(?i)(?:episode|ep|e)[\s-]*0*$targetEpisode\b""").containsMatchIn(txt)
                                        }
                                        ?.attr("href")
                                        ?.let { normalizeUrl(it, activeNetnaijaBase) }
                                        ?: return@runCatching

                                    targetPageUrl = epLink
                                    targetDoc = getNetnaijaDoc(targetPageUrl, timeoutSec) ?: return@runCatching
                                }

                                // Extract subtitle if attached on NetNaija page
                                targetDoc.select("a[href*='/sub'], a[href$='.srt'], a[href$='.vtt']").firstOrNull()?.attr("href")?.let { subHref ->
                                    val subUrl = normalizeUrl(subHref, activeNetnaijaBase)
                                    if (subUrl.startsWith("http")) {
                                        subtitleCallback(newSubtitleFile(lang = "English", url = subUrl))
                                    }
                                }

                                // Extract video download / stream buttons
                                val dlAnchors = targetDoc.select(
                                    ".download-block a[href], .db-one a[href], a[href*='/d/'], a[href*='sabishare.com'], a.btn-download[href]"
                                )
                                for (a in dlAnchors) {
                                    val href = a.attr("href").trim()
                                    if (href.isBlank() || href.contains("/sub", true)) continue
                                    val label = a.text().replace(Regex("""\s+"""), " ").trim().ifBlank { "HD" }
                                    if (resolveNetnaijaDownloadLink(href, targetPageUrl, label, seenUrls, timeoutSec, subtitleCallback, ::emitNow)) {
                                        nnSuccess = true
                                    }
                                }
                            }
                            MovieFoxSettingsDialog.reportServerResult(appCtx, "netnaija", nnSuccess, System.currentTimeMillis() - nnStart)
                        }
                        Unit
                    })
                }

                tasks.awaitAll()
            }

            found
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
