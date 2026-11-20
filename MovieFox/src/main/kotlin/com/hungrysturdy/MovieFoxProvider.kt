package com.hungrysturdy

import android.net.Uri
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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

    // MovieBox BFF State (replaces NetNaija)
    private val movieBoxBff = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    private val movieBoxSiteUrl = "https://netnaija.film"
    private val movieBoxUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

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

    private fun buildSourceLabel(siteTag: String, server: String, info: String): String {
        val cleanInfo = info.replace(Regex("""\s+"""), " ").trim()
        return when {
            server.isNotBlank() && cleanInfo.isNotBlank() -> "$siteTag • $server ($cleanInfo)"
            server.isNotBlank() -> "$siteTag • $server"
            cleanInfo.isNotBlank() -> "$siteTag ($cleanInfo)"
            else -> siteTag
        }.take(60)
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
            }

            val activeEndpoints = allEndpoints
                .distinctBy { it.second }
                .filter { (key, _) -> appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, key) } ?: true }
                .let { list ->
                    if (streamOnly) {
                        list.sortedByDescending { (key, _) ->
                            when (key) {
                                "purstream", "castle", "watchout" -> 1
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

                    val mergedHeaders = baseHeaders + (stream.headers ?: emptyMap())
                    val isHls = isM3u8Stream(streamUrl, stream.type, mergedHeaders, timeoutSec)
                    val finalLabel = stream.label?.takeIf { it.isNotBlank() }
                        ?: "${endpointName.uppercase()} (${stream.quality ?: "HD"})"

                    emitServerLink(
                        serverName = finalLabel,
                        streamUrl = streamUrl,
                        isHls = isHls,
                        qualityStr = stream.quality,
                        customHeaders = mergedHeaders
                    )
                }
            }

            // 2. Auto-Fetch from Saved Custom Domains in MovieFoxSettingsDialog
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
                }
            }

            // 3. Sflix Servers Integration inside MovieFoxProvider
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

            // 4. Prmovies Integration inside MovieFoxProvider
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

            // 5. Play4u Integration inside MovieFoxProvider
            val play4uEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "play4u") } ?: true
            if (play4uEnabled && pageTitle.isNotBlank()) {
                val p4Start = System.currentTimeMillis()
                var p4Success = false
                runCatching {
                    val targetSeason = season.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val targetEpisode = episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val p4Headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/html;q=0.9, */*;q=0.8",
                        "Referer" to "$play4uBaseUrl/"
                    )
                    val p4StreamHeaders = mapOf(
                        "Referer" to "$play4uBaseUrl/",
                        "Origin" to play4uBaseUrl,
                        "User-Agent" to USER_AGENT
                    )

                    var pageHtml: String? = null
                    if (imdbId.startsWith("tt") && imdbId.length > 2) {
                        val directUrl = if (isTv) "$play4uBaseUrl/series/$imdbId" else "$play4uBaseUrl/watch/$imdbId"
                        val candidateHtml = runCatching {
                            app.get(directUrl, headers = p4Headers, interceptor = cfKiller, timeout = timeoutSec).text
                        }.getOrNull()
                        if (!candidateHtml.isNullOrBlank()) {
                            val hasValid = if (isTv) {
                                extractWindowJsonObject(candidateHtml, "__PA_SERIES__") != null
                            } else {
                                val pObj = extractWindowJsonObject(candidateHtml, "__PA_PLAY__")
                                pObj?.optBoolean("ok") == true && pObj.optJSONObject("movie") != null
                            }
                            if (hasValid) pageHtml = candidateHtml
                        }
                    }

                    if (pageHtml == null) {
                        val encodedQ = URLEncoder.encode(pageTitle, "UTF-8")
                        val searchUrl = "$play4uBaseUrl/api/search.php?q=$encodedQ&page=1&per_page=40"
                        val searchText = runCatching {
                            app.get(searchUrl, headers = p4Headers, interceptor = cfKiller, timeout = timeoutSec).text
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
                                val candidateHtml = runCatching {
                                    app.get("$play4uBaseUrl$watchPath", headers = p4Headers, interceptor = cfKiller, timeout = timeoutSec).text
                                }.getOrNull() ?: continue

                                val hasValid = if (isTv) {
                                    extractWindowJsonObject(candidateHtml, "__PA_SERIES__") != null
                                } else {
                                    val pObj = extractWindowJsonObject(candidateHtml, "__PA_PLAY__")
                                    pObj?.optBoolean("ok") == true && pObj.optJSONObject("movie") != null
                                }
                                if (hasValid) {
                                    pageHtml = candidateHtml
                                    break
                                }
                            }
                        }
                    }

                    if (!pageHtml.isNullOrBlank()) {
                        val sealKey = play4uSealKeyOf(pageHtml)
                        if (isTv) {
                            val seriesObj = extractWindowJsonObject(pageHtml, "__PA_SERIES__")
                            val seasonsObj = seriesObj?.optJSONObject("seasons")
                            if (seasonsObj != null) {
                                var matchedSeason: JSONObject? = null
                                for (k in seasonsObj.keys()) {
                                    val sObj = seasonsObj.optJSONObject(k) ?: continue
                                    if (sObj.optInt("number", -1) == targetSeason || k.toIntOrNull() == targetSeason) {
                                        matchedSeason = sObj
                                        break
                                    }
                                }
                                if (matchedSeason == null && seasonsObj.length() == 1) {
                                    seasonsObj.keys().next()?.let { onlyKey ->
                                        matchedSeason = seasonsObj.optJSONObject(onlyKey)
                                    }
                                }

                                val episodesObj = matchedSeason?.optJSONObject("episodes")
                                if (episodesObj != null) {
                                    for (ek in episodesObj.keys()) {
                                        val epObj = episodesObj.optJSONObject(ek) ?: continue
                                        if (epObj.optInt("number", -1) != targetEpisode) continue
                                        val rawHls = epObj.optString("hls")
                                        val unsealed = play4uUnseal(rawHls, sealKey)
                                        val linkUrl = if (unsealed.isNotBlank()) normalizeUrl(unsealed, play4uBaseUrl) else ""
                                        if (linkUrl.startsWith("http") && !isAdUrl(linkUrl) && seenUrls.add(linkUrl)) {
                                            if (downloadOnly) continue
                                            val qHeight = parseQualityHeightFromArray(epObj.optJSONArray("qualities"))
                                            val audioStr = parseAudioLabelFromArray(epObj.optJSONArray("languages"))
                                            val infoStr = listOfNotNull(qHeight?.let { "${it}p" }, audioStr).joinToString(" ")
                                            val exLink = newExtractorLink(
                                                source = "Play4u",
                                                name = buildSourceLabel("Play4u", "HLS", infoStr),
                                                url = linkUrl,
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.quality = qHeight ?: Qualities.Unknown.value
                                                this.headers = p4StreamHeaders
                                                this.referer = "$play4uBaseUrl/"
                                            }
                                            recordLink(exLink, isStream = true)
                                            found = true
                                            p4Success = true
                                        }
                                    }
                                }
                            }
                        } else {
                            val playObj = extractWindowJsonObject(pageHtml, "__PA_PLAY__")
                            val streamsArr = playObj?.optJSONArray("streams")
                            if (streamsArr != null) {
                                for (i in 0 until streamsArr.length()) {
                                    val sObj = streamsArr.optJSONObject(i) ?: continue
                                    val rawSealed = sObj.optString("m3u8_url")
                                    val unsealed = play4uUnseal(rawSealed, sealKey)
                                    val linkUrl = if (unsealed.isNotBlank()) {
                                        normalizeUrl(unsealed, play4uBaseUrl)
                                    } else {
                                        val dName = sObj.optString("domain_name").trim()
                                        val mPath = sObj.optString("m3u8_path").trim()
                                        if (dName.isNotBlank() && mPath.startsWith("/")) {
                                            "https://$dName$mPath"
                                        } else ""
                                    }
                                    if (linkUrl.isBlank() || !linkUrl.startsWith("http") || isAdUrl(linkUrl) || !seenUrls.add(linkUrl)) continue
                                    if (downloadOnly) continue

                                    val srvLabel = sObj.optString("label").trim().ifBlank { "Play4u" }
                                    val qHeight = parseQualityHeightFromArray(sObj.optJSONArray("qualities"))
                                    val audioStr = parseAudioLabelFromArray(sObj.optJSONArray("languages"))
                                    val infoStr = listOfNotNull(qHeight?.let { "${it}p" }, audioStr).joinToString(" ")

                                    val exLink = newExtractorLink(
                                        source = "Play4u",
                                        name = buildSourceLabel("Play4u", srvLabel, infoStr),
                                        url = linkUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = qHeight ?: Qualities.Unknown.value
                                        this.headers = p4StreamHeaders
                                        this.referer = "$play4uBaseUrl/"
                                    }
                                    recordLink(exLink, isStream = true)
                                    found = true
                                    p4Success = true
                                }
                            }
                        }
                    }
                }
                MovieFoxSettingsDialog.reportServerResult(appCtx, "play4u", p4Success, System.currentTimeMillis() - p4Start)
            }

            // 6. MovieBox BFF Server Integration inside MovieFoxProvider (replaces NetNaija)
            val movieBoxEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "moviebox") } ?: true
            if (movieBoxEnabled && pageTitle.isNotBlank()) {
                val mbStart = System.currentTimeMillis()
                var mbSuccess = false
                runCatching {
                    val targetSeason = if (isTv) (season.toIntOrNull()?.coerceAtLeast(1) ?: 1) else 0
                    val targetEpisode = if (isTv) (episode.toIntOrNull()?.coerceAtLeast(1) ?: 1) else 0
                    val mbPlayHeaders = mapOf(
                        "Referer" to "$movieBoxSiteUrl/",
                        "Origin" to movieBoxSiteUrl,
                        "User-Agent" to movieBoxUa
                    )

                    val searchBody = JSONObject()
                        .put("keyword", pageTitle)
                        .put("page", 1)
                        .put("perPage", 20)
                        .toString()

                    val searchRaw = runCatching {
                        app.post(
                            "$movieBoxBff/subject/search",
                            headers = movieBoxHeaders(mapOf("Content-Type" to "application/json")),
                            json = searchBody,
                            timeout = timeoutSec
                        ).text
                    }.getOrNull().orEmpty()

                    val itemsArr = runCatching {
                        JSONObject(searchRaw).optJSONObject("data")?.optJSONArray("items")
                    }.getOrNull()

                    var matchedDetailPath: String? = null
                    if (itemsArr != null) {
                        for (i in 0 until itemsArr.length()) {
                            val item = itemsArr.optJSONObject(i) ?: continue
                            val itemTitle = item.optString("title").trim()
                            val dPath = item.optString("detailPath").trim()
                            if (dPath.isBlank() || itemTitle.isBlank()) continue
                            val itemIsMovie = item.optInt("subjectType", 1) == 1
                            if (itemIsMovie == isTv) continue
                            if (titleMatchesCandidate(itemTitle, pageTitle)) {
                                matchedDetailPath = dPath
                                break
                            }
                        }
                    }

                    if (!matchedDetailPath.isNullOrBlank()) {
                        val detailRaw = runCatching {
                            app.get(
                                "$movieBoxBff/detail?detailPath=$matchedDetailPath",
                                headers = movieBoxHeaders(),
                                timeout = timeoutSec
                            ).text
                        }.getOrNull().orEmpty()

                        val detailData = runCatching {
                            JSONObject(detailRaw).optJSONObject("data")
                        }.getOrNull()

                        val subjectObj = detailData?.optJSONObject("subject")
                        if (subjectObj != null) {
                            val dubsArr = subjectObj.optJSONArray("dubs") ?: JSONArray().apply {
                                put(
                                    JSONObject()
                                        .put("subjectId", subjectObj.optString("subjectId"))
                                        .put("detailPath", matchedDetailPath)
                                        .put("lanName", "Original Audio")
                                        .put("lanCode", "en")
                                        .put("type", 0)
                                        .put("original", true)
                                )
                            }

                            val mbSubs = mutableListOf<SubtitleFile>()
                            val seenSubUrls = mutableSetOf<String>()

                            for (dIdx in 0 until dubsArr.length()) {
                                val dub = dubsArr.optJSONObject(dIdx) ?: continue
                                val dubId = dub.optString("subjectId").trim()
                                val dubPath = dub.optString("detailPath").trim().ifBlank { matchedDetailPath }
                                if (dubId.isBlank()) continue

                                val label = movieBoxDubLabel(dub) ?: continue
                                val playUrl = "$movieBoxBff/subject/play?subjectId=$dubId&se=$targetSeason&ep=$targetEpisode&detailPath=$dubPath"
                                val playRaw = runCatching {
                                    app.get(
                                        playUrl,
                                        headers = movieBoxHeaders(
                                            mapOf(
                                                "X-Source" to "webNetnaijaSite",
                                                "Referer" to "$movieBoxSiteUrl/videoPlayPage/$dubPath"
                                            )
                                        ),
                                        timeout = timeoutSec
                                    ).text
                                }.getOrNull().orEmpty()

                                val playData = runCatching {
                                    JSONObject(playRaw).optJSONObject("data")
                                }.getOrNull() ?: continue

                                var firstStreamId: String? = null

                                playData.optJSONArray("streams")?.let { stArr ->
                                    for (j in 0 until stArr.length()) {
                                        val st = stArr.optJSONObject(j) ?: continue
                                        if (st.optBoolean("vipLocked")) continue
                                        val sUrl = st.optString("url").trim()
                                        val resStr = st.optString("resolutions").trim()
                                        if (sUrl.isBlank() || resStr.isBlank() || isAdUrl(sUrl) || !seenUrls.add(sUrl)) continue
                                        if (firstStreamId == null) firstStreamId = st.optString("id").takeIf { it.isNotBlank() }

                                        val qHeight = resStr.toIntOrNull()
                                        val qLabel = if (qHeight != null) "${qHeight}p" else "${resStr}p"
                                        val sizeMb = st.optString("size").toLongOrNull()
                                            ?.takeIf { it > 0 }
                                            ?.let { " (${it / 1_048_576}MB)" }
                                            .orEmpty()

                                        val exLink = newExtractorLink(
                                            source = "MovieBox",
                                            name = "MovieBox $label $qLabel$sizeMb",
                                            url = sUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.quality = getQuality(qLabel)
                                            this.headers = mbPlayHeaders
                                            this.referer = "$movieBoxSiteUrl/"
                                        }
                                        recordLink(exLink, isStream = false)
                                        mbSuccess = true
                                    }
                                }

                                playData.optJSONArray("dash")?.let { dashArr ->
                                    for (j in 0 until dashArr.length()) {
                                        val dUrl = dashArr.optJSONObject(j)?.optString("url").orEmpty().trim()
                                        if (dUrl.isBlank() || isAdUrl(dUrl) || !seenUrls.add(dUrl)) continue
                                        if (downloadOnly) continue
                                        val exLink = newExtractorLink(
                                            source = "MovieBox",
                                            name = if (streamOnly) "⚡ MovieBox $label DASH" else "MovieBox $label DASH",
                                            url = dUrl,
                                            type = ExtractorLinkType.DASH
                                        ) {
                                            this.quality = Qualities.P1080.value
                                            this.headers = mbPlayHeaders
                                            this.referer = "$movieBoxSiteUrl/"
                                        }
                                        recordLink(exLink, isStream = true)
                                        mbSuccess = true
                                    }
                                }

                                playData.optJSONArray("hls")?.let { hlsArr ->
                                    for (j in 0 until hlsArr.length()) {
                                        val hObj = hlsArr.optJSONObject(j) ?: continue
                                        val hUrl = hObj.optString("url").trim()
                                        if (hUrl.isBlank() || isAdUrl(hUrl) || !seenUrls.add(hUrl)) continue
                                        if (downloadOnly) continue
                                        val resStr = hObj.optString("resolutions", "0").trim()
                                        val exLink = newExtractorLink(
                                            source = "MovieBox",
                                            name = if (streamOnly) "⚡ MovieBox $label HLS ${resStr}p" else "MovieBox $label HLS ${resStr}p",
                                            url = hUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.quality = getQuality(resStr)
                                            this.headers = mbPlayHeaders
                                            this.referer = "$movieBoxSiteUrl/"
                                        }
                                        recordLink(exLink, isStream = true)
                                        mbSuccess = true
                                    }
                                }

                                if (firstStreamId != null && (dub.optBoolean("original") || mbSubs.isEmpty())) {
                                    runCatching {
                                        val capRaw = app.get(
                                            "$movieBoxBff/subject/caption?format=MP4&id=$firstStreamId&subjectId=$dubId&detailPath=$dubPath",
                                            headers = movieBoxHeaders(mapOf("X-Source" to "webNetnaijaSite")),
                                            timeout = timeoutSec
                                        ).text
                                        val capsArr = JSONObject(capRaw).optJSONObject("data")?.optJSONArray("captions")
                                        if (capsArr != null) {
                                            for (c in 0 until capsArr.length()) {
                                                val cObj = capsArr.optJSONObject(c) ?: continue
                                                val subUrl = cObj.optString("url").trim()
                                                if (subUrl.isBlank() || !seenSubUrls.add(subUrl)) continue
                                                val lang = cObj.optString("lanName").ifBlank { cObj.optString("lan", "Unknown") }
                                                mbSubs.add(newSubtitleFile(lang = lang, url = subUrl))
                                            }
                                        }
                                    }
                                }
                            }

                            mbSubs.forEach { subtitleCallback(it) }
                        }
                    }
                }
                MovieFoxSettingsDialog.reportServerResult(appCtx, "moviebox", mbSuccess, System.currentTimeMillis() - mbStart)
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
