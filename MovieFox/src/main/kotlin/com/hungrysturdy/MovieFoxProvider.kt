package com.hungrysturdy

import android.net.Uri
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

class MovieFoxProvider : MainAPI() {

    override var mainUrl = "https://moviefox.pro"
    override var name = "MovieFox"
    override var lang = "hi"
    override val hasMainPage = true

    private val cineTvBaseUrl = "https://cinetv.app"
    private val cineTvHlsWorker = "https://silent-frost-f2dbv.nxstreamhub.workers.dev/hls"

    private val activePrmoviesBase = "https://prmovies.coupons"

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

    private val movieBoxBff = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    private val movieBoxSiteUrl = "https://netnaija.film"
    private val movieBoxUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val netnaijaDomains = listOf(
        "https://www.thenetnaija.net",
        "https://netnaija.xyz",
        "https://netnaija.com.ng"
    )
    private var activeNetnaijaBase = "https://www.thenetnaija.net"

    private val play4uBaseUrl = "https://play4u.org"
    private val play4uSealPrefix = "pa!~"
    private val play4uDefaultSealKey = "pa4u-stream-seal-v1"

    private val hicineApiBase = "https://hicine.biz"
    private val hindmoviezDomains = listOf("https://v3.hindmovie.im")
    private val movielinkbdHubUrl = "https://movielinkbd.com"
    private val movielinkbdDomains = listOf("https://drjuea.movielinkbd.at", "https://movielinkbd.com")
    private val netmirrorBaseUrl = "https://net52.cc"
    private val movyMainUrl = "https://www.movy.sx"
    private val movyStreamApiBase = "https://api.wecollege.net"

    private val hdhub4uDomains = listOf("https://new13.hdhub4u.cl")
    private val hdhub4uTypesenseUrl = "https://search.pingora.fyi/collections/post/documents/search"
    private val fourKhdHubDomains = listOf("https://4khdhub.one")
    private val movies4uDomains = listOf("https://new6.movies4u.garden")
    private val multimoviesDomains = listOf("https://m1.multimovies.courses", "https://multimovies.garden")
    private val tmfDomains = listOf(
        "https://themoviesflix.live",
        "https://one.1moviesflix.lol",
        "https://themoviesflixhq.com",
        "https://moviesflixhq.com"
    )

    private val multiAudioRegex = Regex(
        """(?i)\b(hindi|english|tamil|telugu|kannada|malayalam|punjabi|marathi|bengali|urdu|gujarati|""" +
            """japanese|korean|chinese|spanish|french|german|russian|turkish|arabic|""" +
            """dual[ -]?audio|multi[ -]?audio|hin|eng|tam|tel|mal|kan|kor|jpn|chi|spa|fre|ger)\b"""
    )

    private val downloadOnlyTokens = listOf(
        "10gbps",
        "gdflix instant download",
        "instant download",
        "download",
        "g-direct",
        "g direct"
    )

    private val cs3PluginCache = ConcurrentHashMap<String, List<MainAPI>>()

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
        "where to watch", "more info", "watch now", "view all",
        "play", "play now", "trailer", "all options", "go home", "search"
    )

    private fun isDownloadOnlyLabel(label: String): Boolean {
        val low = label.lowercase()
        return downloadOnlyTokens.any { low.contains(it) }
    }

    private fun normalizeAudioTag(token: String): String = when (token.lowercase().replace(" ", "").replace("-", "")) {
        "hindi", "hin" -> "Hindi"
        "english", "eng" -> "English"
        "tamil", "tam" -> "Tamil"
        "telugu", "tel" -> "Telugu"
        "kannada", "kan" -> "Kannada"
        "malayalam", "mal" -> "Malayalam"
        "punjabi" -> "Punjabi"
        "marathi" -> "Marathi"
        "bengali" -> "Bengali"
        "urdu" -> "Urdu"
        "gujarati" -> "Gujarati"
        "japanese", "jpn" -> "Japanese"
        "korean", "kor" -> "Korean"
        "chinese", "chi" -> "Chinese"
        "spanish", "spa" -> "Spanish"
        "french", "fre" -> "French"
        "german", "ger" -> "German"
        "russian" -> "Russian"
        "turkish" -> "Turkish"
        "arabic" -> "Arabic"
        "dualaudio" -> "Dual Audio"
        "multiaudio" -> "Multi Audio"
        else -> token.trim().replaceFirstChar { it.uppercase() }
    }

    private fun extractAudioSummary(vararg texts: String?): String {
        val found = LinkedHashSet<String>()
        texts.forEach { src ->
            if (src.isNullOrBlank()) return@forEach
            multiAudioRegex.findAll(src).forEach { m ->
                found.add(normalizeAudioTag(m.value))
            }
        }
        return found.joinToString("/")
    }

    private fun currentTimeoutSec(minSec: Long = 8L): Long {
        val ctx = CloudStreamApp.context ?: return minSec
        return MovieFoxSettingsDialog.getTimeoutSeconds(ctx).toLong().coerceAtLeast(minSec)
    }

    private fun directHeaders(url: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val ctx = CloudStreamApp.context
        val savedCookie = MovieFoxSettingsDialog.getCookieForDomain(ctx, url)
        val out = mutableMapOf<String, String>()
        out["User-Agent"] = if (!savedCookie.isNullOrBlank()) MovieFoxSettingsDialog.CF_BYPASS_UA else USER_AGENT
        if (!savedCookie.isNullOrBlank()) out["Cookie"] = savedCookie
        out.putAll(extra)
        return out
    }

    private suspend fun safeGetText(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutSec: Long = currentTimeoutSec()
    ): String? {
        return runCatching {
            val res = app.get(
                url,
                headers = directHeaders(url, extraHeaders),
                timeout = timeoutSec
            )
            if (res.code in 200..299) {
                res.text
            } else {
                runCatching { res.okhttpResponse.close() }
                null
            }
        }.getOrNull()
    }

    private fun JSONObject.safeStr(key: String, fallback: String = ""): String {
        if (!has(key) || isNull(key)) return fallback
        val v = optString(key, "").trim()
        return if (v.isBlank() || v.equals("null", ignoreCase = true)) fallback else v
    }

    private fun safeEncode(raw: String?): String {
        val src = raw.orEmpty()
        return runCatching { URLEncoder.encode(src, "UTF-8") }.getOrDefault(src)
    }

    private fun safeUriEncode(raw: String?, allow: String? = null): String {
        val src = raw.orEmpty()
        return if (allow != null) Uri.encode(src, allow).orEmpty() else Uri.encode(src).orEmpty()
    }

    private suspend fun safeSubtitle(langRaw: String?, urlRaw: String?, baseHost: String = mainUrl): SubtitleFile? {
        val cleanUrl = normalizeUrl(urlRaw.orEmpty().trim(), baseHost)
        if (!cleanUrl.startsWith("http")) return null
        val cleanLang = langRaw.orEmpty().trim().ifBlank { "English" }
        return newSubtitleFile(lang = cleanLang, url = cleanUrl)
    }

    private fun encodeLoadData(
        url: String,
        title: String,
        tmdbId: String?,
        imdbId: String?,
        isTv: Boolean,
        season: Int = 1,
        episode: Int = 1
    ): String {
        return JSONObject().apply {
            put("url", url)
            put("title", title)
            if (!tmdbId.isNullOrBlank()) put("tmdbId", tmdbId)
            if (!imdbId.isNullOrBlank()) put("imdbId", imdbId)
            put("isTv", isTv)
            put("season", season)
            put("episode", episode)
        }.toString()
    }

    private fun resolveCategoryLabel(defaultName: String, url: String): String? {
        val ctx = CloudStreamApp.context ?: return defaultName
        val cats = MovieFoxSettingsDialog.getHomeCategories(ctx)
        val match = cats.firstOrNull { c ->
            when (c.id) {
                "trending" -> url.contains("/trending", true)
                "popular_movies" -> url.contains("list=popular", true) && url.contains("/movies", true)
                "popular_tv" -> url.contains("list=popular", true) && url.contains("/tv", true)
                "hindi_movies" -> url.contains("lang=hi", true) && url.contains("/movies", true)
                "hindi_tv" -> url.contains("lang=hi", true) && url.contains("/tv", true)
                "kdrama" -> url.contains("lang=ko", true) && url.contains("/tv", true)
                "anime" -> url.contains("/anime", true)
                "hollywood" -> url.contains("lang=en", true) && url.contains("/movies", true)
                "tamil_telugu" -> url.contains("lang=ta", true) || url.contains("lang=te", true)
                "cdrama" -> url.contains("lang=zh", true) || url.contains("lang=tr", true)
                else -> false
            }
        }
        if (match != null) {
            if (!match.visible) return null
            return match.title
        }
        return defaultName
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val ctx = CloudStreamApp.context
        val categoryLabel = resolveCategoryLabel(request.name, request.data)
            ?: return newHomePageResponse(emptyList(), hasNext = false)

        val timeoutSec = currentTimeoutSec()
        val targetUrl = if (page > 1) {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page"
        } else {
            request.data
        }

        val rawHtml = safeGetText(targetUrl, timeoutSec = timeoutSec).orEmpty()
        val document = Jsoup.parse(rawHtml, mainUrl)
        val cardSelector =
            "article[data-cf] a[aria-label][href], a[href*=/universes/], a[href*=/universe/], a[href*=/channel/]"
        val fallbackLinkSelector =
            "a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/], a[href*=/series/]"

        val elements = document.select(cardSelector).ifEmpty { document.select(fallbackLinkSelector) }
        var items = elements.mapNotNull { toSearchResult(it) }.distinctBy { it.url }
        if (ctx != null && MovieFoxSettingsDialog.isShuffleHomeCategories(ctx)) {
            items = items.shuffled()
        }

        val home = if (items.isNotEmpty()) listOf(HomePageList(categoryLabel, items)) else emptyList()
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
            it.attr("src").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("srcset").substringBefore(" ") }
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
            newTvSeriesSearchResponse(title, fixedUrl, type) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, fixedUrl, type) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val timeoutSec = currentTimeoutSec()
        val encoded = safeEncode(query)
        val html = safeGetText("$mainUrl/search?q=$encoded", timeoutSec = timeoutSec).orEmpty()
        val document = Jsoup.parse(html, mainUrl)
        val elements = document.select(
            "article[data-cf] a[aria-label][href], a[href*=/universes/], a[href*=/universe/], a[href*=/channel/]"
        ).ifEmpty {
            document.select("a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/], a[href*=/series/]")
        }
        return elements.mapNotNull { toSearchResult(it) }.distinctBy { it.url }
    }

    private fun extractSeriesId(url: String): String? {
        return Regex("-(?:movie|tv|dramas|anime|series)-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: Regex("/(?:movie|tv|dramas|anime|series)/(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: Regex("(?:movie|tv|dramas|anime|series)-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)
            ?: Regex("-(\\d+)(?:\\?|$)").find(url)?.groupValues?.get(1)
    }

    private fun extractUnescapedFlightText(rawHtml: String): String {
        val sb = StringBuilder()
        Regex("""self\.__next_f\.push\(\[\d+,"((?:\\.|[^"\\])*)"\]\)""").findAll(rawHtml).forEach { m ->
            val unescaped = runCatching { JSONObject("{\"v\":\"${m.groupValues[1]}\"}").getString("v") }
                .getOrDefault(m.groupValues[1])
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
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
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
        val responseText = safeGetText(url, timeoutSec = timeoutSec).orEmpty()
        val document = Jsoup.parse(responseText, mainUrl)
        val flightText = extractUnescapedFlightText(responseText)

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifEmpty { Regex("\"title\":\"([^\"]+)\"").find(flightText)?.groupValues?.get(1).orEmpty() }
            .ifEmpty { document.select("title").text().substringBefore("—").substringBefore("|").trim() }
            .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
            .trim()

        val tmdbId = Regex("\"tmdbId\":(\\d+)").find(flightText)?.groupValues?.get(1)
            ?: extractSeriesId(url)
            ?: Regex("\"id\":(\\d+)").find(flightText)?.groupValues?.get(1)
        val imdbId = Regex("\"imdbId\":\"(tt\\d+)\"").find(flightText)?.groupValues?.get(1)
            ?: Regex("tt\\d{6,10}").find(responseText)?.value

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
            ?.trim().orEmpty()

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

        if (type == TvType.Movie) {
            val movieData = encodeLoadData(url, title, tmdbId, imdbId, false)
            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = description
            }
        }

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
                    val epTitle = obj.safeStr("name", "Episode $eNum")
                    val epOverview = obj.safeStr("overview").takeIf { it.isNotBlank() }
                    val stillPath = obj.safeStr("stillPath").takeIf { it.startsWith("/") }
                    val epData = encodeLoadData("$baseUrlWithoutQuery?season=$sNum&episode=$eNum", title, tmdbId, imdbId, true, sNum, eNum)
                    episodes.add(newEpisode(epData) {
                        this.name = epTitle
                        this.season = sNum
                        this.episode = eNum
                        this.description = epOverview
                        this.posterUrl = stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                    })
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
                            val epData = encodeLoadData("$baseUrlWithoutQuery?season=$sNum&episode=$eNum", title, tmdbId, imdbId, true, sNum, eNum)
                            episodes.add(newEpisode(epData) {
                                this.name = "Episode $eNum"
                                this.season = sNum
                                this.episode = eNum
                            })
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
            val sNum = Regex("[?&]s(?:eason)?=(\\d+)", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val eNum = Regex("[?&]e(?:pisode)?=(\\d+)", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            if (sNum <= 0 || eNum <= 0) return@forEach
            if (episodes.none { it.season == sNum && it.episode == eNum }) {
                val label = element.attr("aria-label")
                    .replace(Regex("^Watch episode \\d+:\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifEmpty { element.parent()?.selectFirst("p")?.text()?.replace(Regex("^\\d+\\.\\s*"), "")?.trim().orEmpty() }
                    .ifEmpty { "Episode $eNum" }
                val thumb = element.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
                val epData = encodeLoadData("$baseUrlWithoutQuery?season=$sNum&episode=$eNum", title, tmdbId, imdbId, true, sNum, eNum)
                episodes.add(newEpisode(epData) {
                    this.name = label
                    this.season = sNum
                    this.episode = eNum
                    this.posterUrl = thumb
                })
            }
        }

        if (episodes.isEmpty()) {
            for (s in 1..3) {
                for (e in 1..8) {
                    val epData = encodeLoadData("$baseUrlWithoutQuery?season=$s&episode=$e", title, tmdbId, imdbId, true, s, e)
                    episodes.add(newEpisode(epData) {
                        this.name = "Episode $e"
                        this.season = s
                        this.episode = e
                    })
                }
            }
        }

        val sortedEpisodes = episodes
            .distinctBy { Pair(it.season, it.episode) }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, type, sortedEpisodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private data class LinkContext(
        val pageTitle: String,
        val isTv: Boolean,
        val season: String,
        val episode: String,
        val imdbId: String,
        val candidateIds: List<String>,
        val watchUrl: String,
        val baseHeaders: Map<String, String>,
        val timeoutSec: Long,
        val streamOnly: Boolean,
        val downloadOnly: Boolean,
        val allow4k: Boolean,
        val allow1080: Boolean,
        val allow720: Boolean,
        val allow480: Boolean,
        val seenUrls: MutableSet<String>
    )

    private fun getQuality(value: String?): Int {
        val quality = value?.uppercase().orEmpty()
        return when {
            quality.contains("2160") || quality.contains("4K") -> Qualities.P2160.value
            quality.contains("1440") -> Qualities.P1440.value
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            else -> Qualities.P1080.value
        }
    }

    private fun isQualityAllowed(lc: LinkContext, qualityValue: Int, qualityStr: String?): Boolean {
        val anyToggleEnabled = lc.allow4k || lc.allow1080 || lc.allow720 || lc.allow480
        if (!anyToggleEnabled) return true
        val raw = qualityStr.orEmpty()
        val hasExplicitTag = Regex("""\b(2160|1440|1080|720|480|4k|uhd|fhd|hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (!hasExplicitTag) return true
        return when {
            qualityValue >= Qualities.P1440.value -> lc.allow4k
            qualityValue >= Qualities.P1080.value -> lc.allow1080
            qualityValue >= Qualities.P720.value -> lc.allow720
            else -> lc.allow480
        }
    }

    private fun normalizeUrl(rawUrl: String, baseHost: String = mainUrl): String {
        val trimmed = rawUrl.replace("\\/", "/").trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("magnet:") -> trimmed
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

    private fun isM3u8Stream(url: String, typeHint: String): Boolean =
        isStreamOrHlsLink(url, ExtractorLinkType.M3U8, typeHint)

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
        return (tWords.intersect(cWords).size.toDouble() / tWords.size.toDouble()) >= 0.7
    }

    private fun buildSourceLabel(siteTag: String, server: String, info: String): String {
        val cleanServer = server.replace(Regex("(?i)^null$"), "").trim()
        val audioTag = extractAudioSummary(info, cleanServer, siteTag)
        val cleanInfo = info.replace(Regex("""\s+"""), " ").trim().let { raw ->
            if (audioTag.isNotBlank() && !raw.contains(audioTag, ignoreCase = true)) {
                if (raw.isBlank()) audioTag else "$raw • $audioTag"
            } else {
                raw
            }
        }
        val base = when {
            cleanServer.isNotBlank() && cleanInfo.isNotBlank() -> "$siteTag • $cleanServer ($cleanInfo)"
            cleanServer.isNotBlank() -> "$siteTag • $cleanServer"
            cleanInfo.isNotBlank() -> "$siteTag ($cleanInfo)"
            else -> siteTag
        }
        val tagged = if (isDownloadOnlyLabel(base) && !base.contains("(DOWNLOAD ONLY)", ignoreCase = true)) {
            "$base (DOWNLOAD ONLY)"
        } else {
            base
        }
        return tagged.take(70)
    }

    private fun extractCandidateMediaAndEmbedUrls(text: String, baseUrl: String): List<Pair<String, String>> {
        val found = LinkedHashMap<String, String>()
        Regex("""https?://[^\s"'<>\\)\]]+\.(?:m3u8|mpd|mp4|mkv|webm)(?:\?[^\s"'<>\\)\]]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m ->
                val u = normalizeUrl(m.value, baseUrl)
                if (!isAdUrl(u)) found.putIfAbsent(u, "DIRECT")
            }

        val hostPattern = Regex(
            """https?://[^\s"'<>\\)\]]*(?:filemoon|streamtape|voe|dood|mixdrop|upstream|vidhide|streamwish|luluvdo|pixeldrain|gofile|hubcloud|vcloud|nexdrive|mobilejsr|fastdl|fsl|speedostream|gdflix|gdtot|driveseed|driveleech|filepress|vidsrc|embedsu|autoembed|nxstreamhub)[^\s"'<>\\)\]]*""",
            RegexOption.IGNORE_CASE
        )
        hostPattern.findAll(text).forEach { m ->
            val u = normalizeUrl(m.value.trimEnd('.', ',', ';', ')', ']', '"', '\''), baseUrl)
            if (!isAdUrl(u)) found.putIfAbsent(u, "HOST")
        }

        Regex("""["']?(?:url|file|streamUrl|videoUrl|hls|dash|link|src)["']?\s*[:=]\s*["'](https?://[^"'\s<>\\]+|/[^"'\s<>\\]+)["']""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m ->
                val u = normalizeUrl(m.groupValues[1], baseUrl)
                if (u.startsWith("http") && !isAdUrl(u)) {
                    val kind = if (Regex("""(?i)\.(m3u8|mpd|mp4|mkv|webm)(\?|$)""").containsMatchIn(u)) "DIRECT" else "HOST"
                    found.putIfAbsent(u, kind)
                }
            }
        return found.entries.map { it.key to it.value }
    }

    private suspend fun buildLinkContext(data: String, timeoutSec: Long): LinkContext? {
        val appCtx = CloudStreamApp.context
        var rawPageUrl = data
        var pageTitle = ""
        var tmdbId: String? = null
        var imdbId = ""
        var isTv = false
        var season = "1"
        var episode = "1"

        if (data.trimStart().startsWith("{")) {
            runCatching {
                val obj = JSONObject(data)
                rawPageUrl = obj.safeStr("url", data)
                pageTitle = obj.safeStr("title")
                tmdbId = obj.safeStr("tmdbId").takeIf { it.isNotBlank() }
                imdbId = obj.safeStr("imdbId")
                isTv = obj.optBoolean("isTv", false)
                season = obj.optInt("season", 1).coerceAtLeast(1).toString()
                episode = obj.optInt("episode", 1).coerceAtLeast(1).toString()
            }
        }

        val cleanPageUrl = rawPageUrl.substringBefore("?")
        val urlId = extractSeriesId(cleanPageUrl)
        var jsonId: String? = null

        if (pageTitle.isBlank() || (tmdbId.isNullOrBlank() && urlId.isNullOrBlank() && imdbId.isBlank())) {
            val rawHtml = withTimeoutOrNull(timeoutSec * 1500L) {
                safeGetText(cleanPageUrl, timeoutSec = timeoutSec)
            }.orEmpty()
            if (rawHtml.isNotBlank()) {
                val doc = Jsoup.parse(rawHtml, mainUrl)
                val flightText = extractUnescapedFlightText(rawHtml)
                if (pageTitle.isBlank()) {
                    pageTitle = doc.selectFirst("h1")?.text()?.trim().orEmpty()
                        .ifEmpty { Regex("\"title\":\"([^\"]+)\"").find(flightText)?.groupValues?.get(1).orEmpty() }
                        .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
                }
                if (tmdbId.isNullOrBlank()) tmdbId = Regex("\"tmdbId\":(\\d+)").find(flightText)?.groupValues?.get(1)
                jsonId = Regex("\"id\":(\\d+)").find(flightText)?.groupValues?.get(1)
                if (imdbId.isBlank()) {
                    imdbId = Regex("\"imdbId\":\"(tt\\d+)\"").find(flightText)?.groupValues?.get(1)
                        ?: Regex("tt\\d{6,10}").find(rawHtml)?.value.orEmpty()
                }
            }
        }

        if (!data.trimStart().startsWith("{")) {
            isTv = cleanPageUrl.contains("/tv/") || cleanPageUrl.contains("/dramas/") ||
                cleanPageUrl.contains("/anime/") || cleanPageUrl.contains("/series/") || rawPageUrl.contains("season=", true)
            season = Regex("[?&]s(?:eason)?=(\\d+)", RegexOption.IGNORE_CASE).find(rawPageUrl)?.groupValues?.get(1) ?: "1"
            episode = Regex("[?&]e(?:pisode)?=(\\d+)", RegexOption.IGNORE_CASE).find(rawPageUrl)?.groupValues?.get(1) ?: "1"
        }

        val candidateIds = listOfNotNull(tmdbId, urlId, jsonId).distinct()
        val primaryId = candidateIds.firstOrNull()
        if (primaryId == null && pageTitle.isBlank() && imdbId.isBlank()) return null

        val watchUrl = if (primaryId != null) {
            if (isTv) "$mainUrl/watch/tv-$primaryId?s=$season&e=$episode" else "$mainUrl/watch/movie-$primaryId"
        } else {
            cleanPageUrl
        }

        val baseHeaders = directHeaders(
            mainUrl,
            mapOf(
                "Referer" to watchUrl,
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/plain, */*"
            )
        )

        return LinkContext(
            pageTitle = pageTitle,
            isTv = isTv,
            season = season,
            episode = episode,
            imdbId = imdbId,
            candidateIds = candidateIds,
            watchUrl = watchUrl,
            baseHeaders = baseHeaders,
            timeoutSec = timeoutSec,
            streamOnly = appCtx?.let { MovieFoxSettingsDialog.isStreamOnly(it) } ?: false,
            downloadOnly = appCtx?.let { MovieFoxSettingsDialog.isDownloadOnly(it) } ?: false,
            allow4k = appCtx?.let { MovieFoxSettingsDialog.isQuality4kEnabled(it) } ?: true,
            allow1080 = appCtx?.let { MovieFoxSettingsDialog.isQuality1080Enabled(it) } ?: true,
            allow720 = appCtx?.let { MovieFoxSettingsDialog.isQuality720Enabled(it) } ?: true,
            allow480 = appCtx?.let { MovieFoxSettingsDialog.isQuality480Enabled(it) } ?: true,
            seenUrls = ConcurrentHashMap.newKeySet()
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val appCtx = CloudStreamApp.context
            val timeoutSec = currentTimeoutSec(8L)
            val taskBudgetMs = maxOf(timeoutSec * 2000L, 14_000L)
            val maxConcurrency = (appCtx?.let { MovieFoxSettingsDialog.getParallelServers(it) } ?: 12).coerceIn(4, 24)
            val workerSemaphore = Semaphore(maxConcurrency)

            val lc = buildLinkContext(data, timeoutSec) ?: return false

            val emitLock = Any()
            val subLock = Any()
            var found = false

            val emitSub: (SubtitleFile) -> Unit = { sub ->
                synchronized(subLock) { subtitleCallback(sub) }
            }
            val emitLink: (ExtractorLink, Boolean) -> Unit = { link, isStream ->
                val allowedByMode = !(lc.downloadOnly && isStream)
                val allowedByQuality = isQualityAllowed(lc, link.quality, link.name)
                if (allowedByMode && allowedByQuality && link.url.startsWith("http")) {
                    synchronized(emitLock) {
                        found = true
                        callback(link)
                    }
                }
            }

            val orderedKeys = appCtx?.let { MovieFoxSettingsDialog.getOrderedEnabledKeys(it) }
                ?: MovieFoxSettingsDialog.BASE_SERVERS.map { it.key }

            coroutineScope {
                val tasks = mutableListOf<Deferred<Unit>>()
                orderedKeys.forEach { srvKey ->
                    tasks.add(async(Dispatchers.IO) {
                        workerSemaphore.withPermit {
                            withTimeoutOrNull(taskBudgetMs) {
                                runServerKey(srvKey, lc, isCasting, emitSub, emitLink)
                            }
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

    private suspend fun runServerKey(
        srvKey: String,
        lc: LinkContext,
        isCasting: Boolean,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        when (srvKey.lowercase()) {
            "purstream", "castle", "watchout" -> fetchMovieFoxApi(srvKey.lowercase(), lc, emitSub, emitLink)
            "cinetv_api" -> fetchCineTvApi(lc, emitSub, emitLink)
            "cinetv_hls" -> fetchCineTvHls(lc, emitSub, emitLink)
            "play4u" -> fetchPlay4u(lc, emitLink)
            "sflix" -> fetchSflix(lc, emitLink)
            "moviebox" -> fetchMovieBox(lc, emitSub, emitLink)
            "vegamovies" -> fetchVegaMovies(lc, emitLink)
            "prmovies" -> fetchPrmovies(lc, emitSub, emitLink)
            "netnaija" -> fetchNetNaija(lc, emitSub, emitLink)
            "nuvio" -> fetchNuvioAddons(lc, emitSub, emitLink)
            "hicine" -> fetchHiCine(lc, emitSub, emitLink)
            "hindmoviez" -> fetchHindMoviez(lc, emitSub, emitLink)
            "movielinkbd" -> fetchMoviesLinkBd(lc, emitSub, emitLink)
            "netmirror", "netmirror79", "netflix", "disney_studio", "prime_video_mirror" ->
                fetchNetMirror(lc, emitSub, emitLink)
            "movy", "atlantic" -> fetchMovy(lc, emitSub, emitLink)
            "hdhub4u" -> fetchHdHub4u(lc, emitSub, emitLink)
            "4khdhub" -> fetch4KhdHub(lc, emitSub, emitLink)
            "movies4u" -> fetchMovies4u(lc, emitSub, emitLink)
            "tmf", "themoviesflix" -> fetchTmf(lc, emitSub, emitLink)
            "multimovies" -> fetchMultimovies(lc, emitSub, emitLink)
            "vidlink", "bingr", "vidcore", "vidspark", "rivestream", "vidrift", "blakite", "primesrc", "onetouchtv" ->
                fetchProviderByProbe(srvKey.lowercase(), lc, emitSub, emitLink)
            "animeworld", "hianime", "just4anime", "animesalt", "anikage", "animekizz", "reanime", "fibwatch",
            "allmovieland", "bollyflix", "sdmovies", "dudefilms", "zinkmovies", "moviesdrive", "rtally", "mlsbd", "skymovies" ->
                fetchProviderByProbe(srvKey.lowercase(), lc, emitSub, emitLink)
            else -> fetchCustomCs3Server(srvKey, lc, isCasting, emitSub, emitLink)
        }
    }

    private suspend fun fetchProviderByProbe(
        key: String,
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val srv = MovieFoxSettingsDialog.SERVERS.firstOrNull { it.key.equals(key, true) } ?: return
        val baseUrl = srv.probeUrl.trimEnd('/')
        if (!baseUrl.startsWith("http")) return

        val q = safeUriEncode(lc.pageTitle)
        val searchUrls = listOf(
            "$baseUrl/?s=$q",
            "$baseUrl/search?q=$q",
            "$baseUrl/search?keyword=$q"
        )
        val headers = mapOf("Referer" to "$baseUrl/", "User-Agent" to USER_AGENT)

        for (sUrl in searchUrls) {
            val body = safeGetText(sUrl, headers, lc.timeoutSec).orEmpty()
            if (body.isBlank()) continue
            if (body.trimStart().startsWith("{")) {
                if (parseAndEmitApiJson(srv.name, body, lc, headers, emitSub, emitLink)) return
            }
            val doc = Jsoup.parse(body, baseUrl)
            val detailHref = doc.select("a[href]")
                .firstOrNull { a ->
                    val t = a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }.ifBlank { a.text() }.trim()
                    t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                }?.attr("href")?.let { normalizeUrl(it, baseUrl) }.orEmpty()
            if (detailHref.isNotBlank()) {
                val detailHtml = safeGetText(detailHref, headers, lc.timeoutSec).orEmpty()
                if (detailHtml.isNotBlank()) {
                    val audioTag = extractAudioSummary(detailHtml, lc.pageTitle)
                    extractCandidateMediaAndEmbedUrls(detailHtml, detailHref).take(8).forEach { (u, kind) ->
                        if (kind == "DIRECT") {
                            emitStream(lc, buildSourceLabel(srv.name, "", audioTag), u, isM3u8Stream(u, ""), audioTag, headers, emitLink)
                        } else {
                            loadExtractor(u, detailHref, emitSub) { ex ->
                                if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                            }
                        }
                    }
                    return
                }
            }
        }
    }

    private suspend fun emitStream(
        lc: LinkContext,
        serverName: String,
        streamUrl: String,
        isHls: Boolean,
        qualityStr: String? = null,
        customHeaders: Map<String, String> = lc.baseHeaders,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (streamUrl.isBlank() || !streamUrl.startsWith("http") || isAdUrl(streamUrl) || !lc.seenUrls.add(streamUrl)) return
        if (lc.downloadOnly && isHls) return

        val resolvedQuality = getQuality(qualityStr ?: serverName)
        if (!isQualityAllowed(lc, resolvedQuality, qualityStr ?: serverName)) return

        val audioSummary = extractAudioSummary(qualityStr, serverName, lc.pageTitle)
        val withAudio = if (audioSummary.isNotBlank() && !serverName.contains(audioSummary, ignoreCase = true)) {
            "$serverName [$audioSummary]"
        } else {
            serverName
        }

        val baseName = if (lc.streamOnly && isHls) "⚡ $withAudio" else withAudio
        val label = if (isDownloadOnlyLabel(baseName) && !baseName.contains("(DOWNLOAD ONLY)", ignoreCase = true)) {
            "$baseName (DOWNLOAD ONLY)"
        } else {
            baseName
        }
        val link = newExtractorLink(
            source = serverName,
            name = label,
            url = streamUrl,
            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = customHeaders["Referer"] ?: lc.watchUrl
            this.quality = resolvedQuality
            this.headers = customHeaders
        }
        emitLink(link, isHls)
    }

    private suspend fun parseAndEmitApiJson(
        endpointName: String,
        rawText: String,
        lc: LinkContext,
        baseHeaders: Map<String, String>,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ): Boolean {
        var emitted = false
        val root = runCatching { JSONObject(rawText) }.getOrNull() ?: return false
        if (root.has("bid") && root.optJSONArray("domains") != null) return false

        val containers = listOfNotNull(root, root.optJSONObject("data"))
        val streamObjs = mutableListOf<JSONObject>()

        containers.forEach { obj ->
            listOf("streams", "sources", "links", "items", "servers").forEach { key ->
                obj.optJSONArray(key)?.let { arr ->
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { streamObjs.add(it) }
                }
            }
            val singleUrl = obj.safeStr("url")
                .ifBlank { obj.safeStr("file") }
                .ifBlank { obj.safeStr("streamUrl") }
                .ifBlank { obj.safeStr("hls") }
            if (singleUrl.isNotBlank()) {
                streamObjs.add(JSONObject().put("url", singleUrl).put("quality", obj.safeStr("quality", "HD")))
            }
        }

        for (st in streamObjs) {
            val rawStreamUrl = st.safeStr("url").ifBlank { st.safeStr("file") }.ifBlank { st.safeStr("streamUrl") }
            if (rawStreamUrl.isBlank()) continue
            val streamUrl = normalizeUrl(rawStreamUrl)
            if (isAdUrl(streamUrl)) continue

            st.optJSONArray("subtitles")?.let { subs ->
                for (k in 0 until subs.length()) {
                    val sub = subs.optJSONObject(k) ?: continue
                    val rawSubUrl = sub.safeStr("url").ifBlank { sub.safeStr("file") }
                    val lang = sub.safeStr("label").ifBlank { sub.safeStr("language") }.ifBlank { sub.safeStr("lang", "English") }
                    safeSubtitle(lang, rawSubUrl)?.let { emitSub(it) }
                }
            }

            val audioList = mutableListOf<String>()
            st.optJSONArray("languages")?.let { langs ->
                for (a in 0 until langs.length()) {
                    langs.optJSONObject(a)?.safeStr("name")?.takeIf { it.isNotBlank() }?.let { audioList.add(it) }
                        ?: langs.optString(a).trim().takeIf { it.isNotBlank() && it != "null" }?.let { audioList.add(it) }
                }
            }
            val langTag = st.safeStr("language").ifBlank { st.safeStr("lang") }.ifBlank { audioList.distinct().joinToString("/") }

            val extraHeaders = mutableMapOf<String, String>()
            st.optJSONObject("headers")?.let { hObj ->
                val keys = hObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = hObj.safeStr(k)
                    if (v.isNotBlank()) extraHeaders[k] = v
                }
            }

            val mergedHeaders = baseHeaders + extraHeaders
            val isHls = isM3u8Stream(streamUrl, st.safeStr("type"))
            val qualityStr = listOf(st.safeStr("quality", "HD"), langTag).filter { it.isNotBlank() }.joinToString(" ")
            val rawLabel = st.safeStr("label").ifBlank { st.safeStr("title") }.ifBlank { "${endpointName.uppercase()} ($qualityStr)" }
            val finalLabel = if (langTag.isNotBlank() && !rawLabel.contains(langTag, ignoreCase = true)) {
                "$rawLabel • $langTag"
            } else {
                rawLabel
            }

            emitStream(lc, finalLabel, streamUrl, isHls, qualityStr, mergedHeaders, emitLink)
            emitted = true
        }
        return emitted
    }

    private suspend fun fetchMovieFoxApi(
        srvKey: String,
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        val contentType = if (lc.isTv) "tv" else "movie"
        val tvQuery = if (lc.isTv) "&season=${safeEncode(lc.season)}&episode=${safeEncode(lc.episode)}" else ""
        val imdbQuery = if (lc.imdbId.isNotBlank()) "&imdbId=${safeEncode(lc.imdbId)}" else ""

        for (cid in lc.candidateIds) {
            val apiUrl = "$mainUrl/api/stream/$srvKey?type=$contentType&id=${safeEncode(cid)}$imdbQuery$tvQuery"
            val rawText = safeGetText(apiUrl, lc.baseHeaders, lc.timeoutSec) ?: continue
            if (rawText.isBlank()) continue
            if (rawText.trimStart().startsWith("#EXTM3U")) {
                emitStream(lc, "MovieFox ${srvKey.uppercase()} HLS", apiUrl, true, null, lc.baseHeaders, emitLink)
                break
            }
            if (parseAndEmitApiJson(srvKey, rawText, lc, lc.baseHeaders, emitSub, emitLink)) break
        }
    }

    private suspend fun fetchCineTvApi(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        val sNum = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val eNum = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val headers = mapOf("Referer" to "$cineTvBaseUrl/", "Origin" to cineTvBaseUrl, "Accept" to "application/json, text/html, */*")
        val endpoints = buildList {
            lc.candidateIds.forEach { cid ->
                if (lc.isTv) {
                    add("$cineTvBaseUrl/api/stream/tv/$cid/$sNum/$eNum")
                    add("$cineTvBaseUrl/api/sources?type=tv&id=$cid&season=$sNum&episode=$eNum")
                } else {
                    add("$cineTvBaseUrl/api/stream/movie/$cid")
                    add("$cineTvBaseUrl/api/sources?type=movie&id=$cid")
                }
            }
            if (lc.imdbId.startsWith("tt")) {
                if (lc.isTv) add("$cineTvBaseUrl/api/stream/tv/${lc.imdbId}/$sNum/$eNum")
                else add("$cineTvBaseUrl/api/stream/movie/${lc.imdbId}")
            }
        }.distinct()

        for (ep in endpoints) {
            val body = safeGetText(ep, headers, lc.timeoutSec)?.trim().orEmpty()
            if (body.isBlank()) continue
            if (body.startsWith("#EXTM3U")) {
                emitStream(lc, "CineTV API HLS", ep, true, null, headers, emitLink)
                break
            }
            if (body.startsWith("{") && parseAndEmitApiJson("CineTV API", body, lc, headers, emitSub, emitLink)) break
        }
    }

    private suspend fun fetchCineTvHls(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        val sNum = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val eNum = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val headers = mapOf("Referer" to "$cineTvBaseUrl/", "Origin" to cineTvBaseUrl, "Accept" to "*/*")
        val urls = buildList {
            lc.candidateIds.forEach { cid ->
                if (lc.isTv) {
                    add("$cineTvHlsWorker/tv/$cid/$sNum/$eNum")
                    add("$cineTvHlsWorker/tv/$cid/$sNum/$eNum/master.m3u8")
                } else {
                    add("$cineTvHlsWorker/movie/$cid")
                    add("$cineTvHlsWorker/movie/$cid/master.m3u8")
                }
            }
        }.distinct()

        for (wUrl in urls) {
            val body = safeGetText(wUrl, headers, lc.timeoutSec)?.trim().orEmpty()
            if (body.isBlank()) continue
            if (body.startsWith("#EXTM3U")) {
                emitStream(lc, "CineTV HLS", wUrl, true, "1080p", headers, emitLink)
                break
            } else if (body.startsWith("{") && parseAndEmitApiJson("CineTV HLS", body, lc, headers, emitSub, emitLink)) {
                break
            }
        }
    }

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
            if (escaped) { escaped = false; continue }
            when (c) {
                '\\' -> escaped = true
                '"' -> inString = !inString
                '{', '[' -> if (!inString) depth++
                '}', ']' -> if (!inString) {
                    depth--
                    if (depth == 0) return runCatching { JSONObject(html.substring(start, i + 1)) }.getOrNull()
                }
            }
        }
        return null
    }

    private fun play4uUnseal(value: String?, key: String): String {
        if (value.isNullOrBlank()) return ""
        if (!value.startsWith(play4uSealPrefix)) return value
        var b64 = value.substring(play4uSealPrefix.length).replace("-", "+").replace("_", "/")
        when (b64.length % 4) { 1 -> return ""; 2 -> b64 += "=="; 3 -> b64 += "=" }
        return try {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            val kb = key.toByteArray(Charsets.UTF_8)
            val out = StringBuilder(raw.size)
            for (i in raw.indices) out.append(((raw[i].toInt() and 0xFF) xor (kb[i % kb.size].toInt() and 0xFF)).toChar())
            val text = out.toString()
            if (text.isEmpty() || text.any { it < ' ' || it > '~' }) "" else text
        } catch (_: IllegalArgumentException) { "" }
    }

    private suspend fun fetchPlay4u(lc: LinkContext, emitLink: (ExtractorLink, Boolean) -> Unit) {
        if (lc.pageTitle.isBlank()) return
        val targetSeason = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val targetEpisode = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val p4Headers = mapOf("Referer" to "$play4uBaseUrl/", "Accept" to "application/json, text/html;q=0.9, */*;q=0.8")
        val p4StreamHeaders = mapOf("Referer" to "$play4uBaseUrl/", "Origin" to play4uBaseUrl, "User-Agent" to USER_AGENT)

        var pageHtml: String? = null
        if (lc.imdbId.startsWith("tt") && lc.imdbId.length > 2) {
            val directUrl = if (lc.isTv) "$play4uBaseUrl/series/${lc.imdbId}" else "$play4uBaseUrl/watch/${lc.imdbId}"
            val cand = safeGetText(directUrl, p4Headers, lc.timeoutSec)
            if (!cand.isNullOrBlank()) {
                val ok = if (lc.isTv) extractWindowJsonObject(cand, "__PA_SERIES__") != null
                else extractWindowJsonObject(cand, "__PA_PLAY__")?.optBoolean("ok") == true
                if (ok) pageHtml = cand
            }
        }

        if (pageHtml == null) {
            val searchText = safeGetText("$play4uBaseUrl/api/search.php?q=${safeEncode(lc.pageTitle)}&page=1&per_page=30", p4Headers, lc.timeoutSec).orEmpty()
            val itemsArr = runCatching { JSONObject(searchText).optJSONArray("items") }.getOrNull()
            if (itemsArr != null) {
                for (i in 0 until itemsArr.length()) {
                    val item = itemsArr.optJSONObject(i) ?: continue
                    val mType = item.safeStr("media_type")
                    if (mType == "series" && !lc.isTv) continue
                    if (mType == "movie" && lc.isTv) continue
                    val itemTitle = item.safeStr("title").replace(Regex("""\s*\((19|20)\d{2}\)\s*$"""), "").trim()
                    if (!titleMatchesCandidate(itemTitle, lc.pageTitle)) continue
                    val watchPath = item.safeStr("watch").takeIf { it.startsWith("/") } ?: continue
                    val cand = safeGetText("$play4uBaseUrl$watchPath", p4Headers, lc.timeoutSec) ?: continue
                    val ok = if (lc.isTv) extractWindowJsonObject(cand, "__PA_SERIES__") != null
                    else extractWindowJsonObject(cand, "__PA_PLAY__")?.optBoolean("ok") == true
                    if (ok) { pageHtml = cand; break }
                }
            }
        }

        if (pageHtml.isNullOrBlank()) return
        val sealKey = Regex("""window\.__PA_SEAL_KEY__\s*=\s*"([^"]*)"""").find(pageHtml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: play4uDefaultSealKey

        if (!lc.isTv) {
            val streamsArr = extractWindowJsonObject(pageHtml, "__PA_PLAY__")?.optJSONArray("streams") ?: return
            for (i in 0 until streamsArr.length()) {
                val sObj = streamsArr.optJSONObject(i) ?: continue
                val linkUrl = play4uUnseal(sObj.safeStr("m3u8_url"), sealKey).ifBlank {
                    val dName = sObj.safeStr("domain_name")
                    val mPath = sObj.safeStr("m3u8_path")
                    if (dName.isNotBlank() && mPath.startsWith("/")) "https://$dName$mPath" else ""
                }.let { if (it.isNotBlank()) normalizeUrl(it, play4uBaseUrl) else "" }
                if (linkUrl.isBlank() || !linkUrl.startsWith("http")) continue
                val srvLabel = sObj.safeStr("label", "Play4u")
                val langs = mutableListOf<String>()
                sObj.optJSONArray("languages")?.let { lArr ->
                    for (k in 0 until lArr.length()) {
                        lArr.optJSONObject(k)?.safeStr("name")?.takeIf { it.isNotBlank() }?.let { langs.add(it) }
                    }
                }
                val infoTag = langs.distinct().joinToString("/")
                emitStream(lc, buildSourceLabel("Play4u", srvLabel, infoTag), linkUrl, true, infoTag, p4StreamHeaders, emitLink)
            }
        } else {
            val seasonsObj = extractWindowJsonObject(pageHtml, "__PA_SERIES__")?.optJSONObject("seasons") ?: return
            var matchedSeason: JSONObject? = null
            for (k in seasonsObj.keys()) {
                val o = seasonsObj.optJSONObject(k) ?: continue
                if (o.optInt("number", -1) == targetSeason || k.toIntOrNull() == targetSeason) { matchedSeason = o; break }
            }
            val episodesObj = (matchedSeason ?: if (seasonsObj.length() == 1) seasonsObj.optJSONObject(seasonsObj.keys().next()) else null)
                ?.optJSONObject("episodes") ?: return
            for (ek in episodesObj.keys()) {
                val epObj = episodesObj.optJSONObject(ek) ?: continue
                if (epObj.optInt("number", -1) != targetEpisode) continue
                val linkUrl = play4uUnseal(epObj.safeStr("hls"), sealKey).let { if (it.isNotBlank()) normalizeUrl(it, play4uBaseUrl) else "" }
                if (linkUrl.startsWith("http")) {
                    val langs = mutableListOf<String>()
                    epObj.optJSONArray("languages")?.let { lArr ->
                        for (k in 0 until lArr.length()) {
                            lArr.optJSONObject(k)?.safeStr("name")?.takeIf { it.isNotBlank() }?.let { langs.add(it) }
                        }
                    }
                    val infoTag = listOfNotNull("Ep $targetEpisode", langs.distinct().joinToString("/").takeIf { it.isNotBlank() }).joinToString(" • ")
                    emitStream(lc, buildSourceLabel("Play4u", "HLS", infoTag), linkUrl, true, infoTag, p4StreamHeaders, emitLink)
                }
            }
        }
    }

    private suspend fun fetchSflix(lc: LinkContext, emitLink: (ExtractorLink, Boolean) -> Unit) {
        if (lc.pageTitle.isBlank()) return
        val sflixMainUrl = "https://sflix.film"
        val sflixPlayerUrl = "https://mzfi.me"
        val sflixDomains = listOf("https://sflix.film", "https://h5-api.aoneroom.com")
        val encodedTitle = safeEncode(lc.pageTitle)
        val searchHtml = safeGetText("$sflixMainUrl/search-result?keyword=$encodedTitle", mapOf("Referer" to "$sflixMainUrl/"), lc.timeoutSec).orEmpty()
        if (searchHtml.isBlank()) return

        val doc = Jsoup.parse(searchHtml, sflixMainUrl)
        val detailLink = doc.select("a.video-item[href*='/detail/'], a[href*='/detail/']").firstOrNull { el ->
            val t = (el.selectFirst("h3.text, .video-name, h3")?.text() ?: el.attr("title")).trim()
            titleMatchesCandidate(t, lc.pageTitle)
        }?.attr("href").orEmpty()
        if (detailLink.isBlank()) return

        val fullDetailUrl = if (detailLink.startsWith("http")) detailLink else "$sflixMainUrl/${detailLink.trimStart('/')}"
        val subjectId = Regex("""[?&]id=(\d+)""").find(fullDetailUrl)?.groupValues?.get(1).orEmpty()
        val detailPath = fullDetailUrl.substringAfter("/detail/").substringBefore("?")
        if (subjectId.isEmpty()) return

        val se = if (lc.isTv) lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
        val ep = if (lc.isTv) lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
        val playHeaders = mapOf("Referer" to "$sflixPlayerUrl/", "Origin" to sflixPlayerUrl, "Accept" to "application/json")

        for (domain in sflixDomains) {
            val playUrl = "$domain/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath&streamSignType=1"
            val playText = safeGetText(playUrl, playHeaders, lc.timeoutSec).orEmpty()
            val dataObj = runCatching { JSONObject(playText).optJSONObject("data") }.getOrNull() ?: continue
            var any = false
            listOf("dash" to ExtractorLinkType.DASH, "hls" to ExtractorLinkType.M3U8, "streams" to ExtractorLinkType.VIDEO).forEach { (k, lType) ->
                dataObj.optJSONArray(k)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val sUrl = item.safeStr("url")
                        if (sUrl.isBlank()) continue
                        val maxRes = item.safeStr("resolutions").split(",").mapNotNull { it.trim().toIntOrNull() }.maxOrNull() ?: 720
                        emitStream(lc, "Sflix [${k.uppercase()} ${maxRes}p]", sUrl, lType != ExtractorLinkType.VIDEO, "${maxRes}p", playHeaders, emitLink)
                        any = true
                    }
                }
            }
            if (any) break
        }
    }

    private fun movieBoxClientToken(): String {
        val ts = System.currentTimeMillis() / 1000
        val md5 = MessageDigest.getInstance("MD5").digest(ts.toString().reversed().toByteArray()).joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    private suspend fun fetchMovieBox(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val se = if (lc.isTv) lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
        val ep = if (lc.isTv) lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
        val headers = mapOf(
            "User-Agent" to movieBoxUa, "Accept" to "application/json",
            "Origin" to movieBoxSiteUrl, "Referer" to "$movieBoxSiteUrl/",
            "X-Request-Lang" to "en", "X-Client-Info" to "{\"timezone\":\"Asia/Kolkata\"}",
            "X-Client-Token" to movieBoxClientToken(), "Content-Type" to "application/json"
        )
        val searchRaw = runCatching {
            app.post("$movieBoxBff/subject/search", headers = headers, json = mapOf("keyword" to lc.pageTitle, "page" to 1, "perPage" to 15), timeout = lc.timeoutSec).text
        }.getOrNull().orEmpty()
        val itemsArr = runCatching { JSONObject(searchRaw).optJSONObject("data")?.optJSONArray("items") }.getOrNull() ?: return

        var matchedPath: String? = null
        for (i in 0 until itemsArr.length()) {
            val item = itemsArr.optJSONObject(i) ?: continue
            val t = item.safeStr("title")
            val dPath = item.safeStr("detailPath")
            if (dPath.isBlank() || t.isBlank()) continue
            val itemIsTv = item.optInt("subjectType", 1) == 2
            if (itemIsTv != lc.isTv) continue
            if (titleMatchesCandidate(t, lc.pageTitle)) { matchedPath = dPath; break }
        }
        val detailPath = matchedPath ?: return
        val detailRaw = safeGetText("$movieBoxBff/detail?detailPath=${safeEncode(detailPath)}", headers, lc.timeoutSec).orEmpty()
        val subjectObj = runCatching { JSONObject(detailRaw).optJSONObject("data")?.optJSONObject("subject") }.getOrNull() ?: return
        val dubsArr = subjectObj.optJSONArray("dubs") ?: JSONArray().apply {
            put(JSONObject().put("subjectId", subjectObj.safeStr("subjectId")).put("detailPath", detailPath).put("lanName", "Original").put("type", 0).put("original", true))
        }
        val seenSubs = Collections.synchronizedSet(mutableSetOf<String>())
        val playHeaders = mapOf("Referer" to "$movieBoxSiteUrl/", "Origin" to movieBoxSiteUrl, "User-Agent" to movieBoxUa)

        for (dIdx in 0 until dubsArr.length()) {
            val dub = dubsArr.optJSONObject(dIdx) ?: continue
            val dubId = dub.safeStr("subjectId")
            val dubPath = dub.safeStr("detailPath", detailPath)
            if (dubId.isBlank()) continue
            val label = dub.safeStr("lanName").substringBefore(" dub").substringBefore(" sub").trim().ifBlank { "Original" }
            val playRaw = safeGetText(
                "$movieBoxBff/subject/play?subjectId=${safeEncode(dubId)}&se=$se&ep=$ep&detailPath=${safeEncode(dubPath)}",
                headers + mapOf("X-Source" to "webNetnaijaSite"),
                lc.timeoutSec
            ).orEmpty()
            val playData = runCatching { JSONObject(playRaw).optJSONObject("data") }.getOrNull() ?: continue
            var firstId: String? = null

            playData.optJSONArray("streams")?.let { stArr ->
                for (j in 0 until stArr.length()) {
                    val st = stArr.optJSONObject(j) ?: continue
                    if (st.optBoolean("vipLocked")) continue
                    val sUrl = st.safeStr("url")
                    val resStr = st.safeStr("resolutions")
                    if (sUrl.isBlank() || resStr.isBlank()) continue
                    if (firstId == null) firstId = st.safeStr("id").takeIf { it.isNotBlank() }
                    val qLabel = "${resStr.toIntOrNull() ?: resStr}p"
                    emitStream(lc, "MovieBox $label $qLabel", sUrl, false, "$qLabel $label", playHeaders, emitLink)
                }
            }
            if (firstId != null && (dub.optBoolean("original") || seenSubs.isEmpty())) {
                runCatching {
                    val capRaw = safeGetText(
                        "$movieBoxBff/subject/caption?format=MP4&id=${safeEncode(firstId)}&subjectId=${safeEncode(dubId)}&detailPath=${safeEncode(dubPath)}",
                        headers,
                        lc.timeoutSec
                    ).orEmpty()
                    val capsArr = JSONObject(capRaw).optJSONObject("data")?.optJSONArray("captions")
                    if (capsArr != null) {
                        for (c in 0 until capsArr.length()) {
                            val cObj = capsArr.optJSONObject(c) ?: continue
                            val subUrl = cObj.safeStr("url")
                            if (subUrl.isNotBlank() && seenSubs.add(subUrl)) {
                                safeSubtitle(cObj.safeStr("lanName", "English"), subUrl)?.let { emitSub(it) }
                            }
                        }
                    }
                }
            }
        }
    }

    private data class VegaDoc(val postTitle: String, val permalink: String, val domain: String)
    private class VegaDrivePage(val servers: List<Pair<String, String>>, val episodes: Map<Int, List<Pair<String, String>>>)

    private suspend fun fetchVegaDrivePage(url: String, timeoutSec: Long): VegaDrivePage? {
        vegaDriveCache[url]?.let { return it }
        if (vegaDriveCache.size > 40) vegaDriveCache.clear()
        val html = safeGetText(url, mapOf("Referer" to "https://nexdrive.beer/"), timeoutSec).orEmpty()
        if (html.isBlank() || Regex("""(?i)file (is )?deleted|not found""").containsMatchIn(html)) return null
        val doc = Jsoup.parse(html)
        val scope = doc.selectFirst(".entry, article, .post-inner") ?: doc
        val servers = mutableListOf<Pair<String, String>>()
        for (a in scope.select("a[href]")) {
            val href = a.attr("href").trim()
            val lbl = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim() ?: a.text().replace(Regex("""\s+"""), " ").trim()
            if (lbl.isBlank() || !href.startsWith("http") || vegaDeadHosts.containsMatchIn(href) || !vegaButtonHosts.containsMatchIn(href)) continue
            if (vegaJunk.containsMatchIn(href) || Regex("""(?i)batch|zip\b""").containsMatchIn(lbl)) continue
            servers.add(lbl to href)
        }
        val episodes = LinkedHashMap<Int, List<Pair<String, String>>>()
        for (head in scope.select("h3, h4, h5")) {
            val num = Regex("""(?i)episodes?\s*:?\s*0*(\d{1,3})""").find(head.text())?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val links = mutableListOf<Pair<String, String>>()
            var sib = head.nextElementSibling()
            while (sib != null && !listOf("h3", "h4", "h5").contains(sib.tagName())) {
                for (a in sib.select("a[href]")) {
                    val href = a.attr("href").trim()
                    val lbl = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim() ?: a.text().replace(Regex("""\s+"""), " ").trim()
                    if (lbl.isNotBlank() && href.startsWith("http") && vegaButtonHosts.containsMatchIn(href) && !vegaJunk.containsMatchIn(href)) {
                        links.add(lbl to href)
                    }
                }
                sib = sib.nextElementSibling()
            }
            if (links.isNotEmpty() && !episodes.containsKey(num)) episodes[num] = links
        }
        return VegaDrivePage(servers.distinctBy { it.second }, episodes).also { vegaDriveCache[url] = it }
    }

    private suspend fun fetchVegaMovies(lc: LinkContext, emitLink: (ExtractorLink, Boolean) -> Unit) {
        if (lc.pageTitle.isBlank()) return
        val targetSeason = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val targetEpisode = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val docs = vegamoviesDomains.flatMap { dom ->
            val raw = safeGetText("$dom/ts-search.php?q=${safeUriEncode(lc.pageTitle)}&page=1", mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            runCatching {
                val hits = JSONObject(raw).optJSONArray("hits") ?: return@runCatching emptyList<VegaDoc>()
                (0 until hits.length()).mapNotNull { i ->
                    val d = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
                    val t = d.safeStr("post_title"); val p = d.safeStr("permalink")
                    if (t.isBlank() || p.isBlank()) null else VegaDoc(t, p, dom)
                }
            }.getOrDefault(emptyList())
        }
        val chosen = docs.firstOrNull { titleMatchesCandidate(it.postTitle, lc.pageTitle) } ?: return
        val postAudio = extractAudioSummary(chosen.postTitle)
        val postHtml = safeGetText(normalizeUrl(chosen.permalink, chosen.domain), mapOf("Referer" to "${chosen.domain}/"), lc.timeoutSec).orEmpty()
        if (postHtml.isBlank()) return
        val driveLinks = Jsoup.parse(postHtml).select("a[href]")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http") && vegaButtonHosts.containsMatchIn(it) && !vegaJunk.containsMatchIn(it) }
            .distinct().take(6)

        for (dUrl in driveLinks) {
            val page = fetchVegaDrivePage(dUrl, lc.timeoutSec) ?: continue
            val targets = if (lc.isTv) page.episodes[targetEpisode] ?: page.servers.takeIf { page.episodes.isEmpty() } else page.servers
            targets.orEmpty().take(4).forEach { (lbl, href) ->
                if (href.startsWith("http")) {
                    val info = listOfNotNull("S${targetSeason}E${targetEpisode}", postAudio.takeIf { it.isNotBlank() }).joinToString(" • ")
                    emitStream(lc, buildSourceLabel("VegaMovies", lbl, info), href, false, "$lbl $postAudio", mapOf("User-Agent" to USER_AGENT), emitLink)
                }
            }
        }
    }

    private suspend fun fetchPrmovies(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val q = lc.pageTitle.trim().replace(Regex("\\s+"), "+")
        val searchHtml = safeGetText("$activePrmoviesBase/?s=$q", mapOf("Referer" to "$activePrmoviesBase/"), lc.timeoutSec).orEmpty()
        if (searchHtml.isBlank()) return
        val card = Jsoup.parse(searchHtml, activePrmoviesBase).select(".ml-item").firstOrNull { c ->
            val a = c.selectFirst("a.ml-mask") ?: return@firstOrNull false
            val t = (a.attr("oldtitle").ifBlank { a.attr("title") }.ifBlank { c.selectFirst(".mli-info h2")?.text().orEmpty() }).trim()
            t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
        } ?: return
        val cardTitle = (card.selectFirst("a.ml-mask")?.attr("oldtitle").orEmpty().ifBlank { card.selectFirst("a.ml-mask")?.attr("title").orEmpty() }).trim()
        val audioTag = extractAudioSummary(cardTitle)
        val detailUrl = card.selectFirst("a.ml-mask")?.attr("href")?.let { normalizeUrl(it, activePrmoviesBase) }.orEmpty()
        if (detailUrl.isBlank()) return
        val detailHtml = safeGetText(detailUrl, mapOf("Referer" to activePrmoviesBase), lc.timeoutSec).orEmpty()
        if (detailHtml.isBlank()) return
        extractCandidateMediaAndEmbedUrls(detailHtml, detailUrl).take(8).forEach { (u, kind) ->
            if (kind == "DIRECT") {
                emitStream(lc, buildSourceLabel("Prmovies", "", audioTag), u, isM3u8Stream(u, ""), audioTag, mapOf("Referer" to detailUrl, "User-Agent" to USER_AGENT), emitLink)
            } else {
                loadExtractor(u, detailUrl, emitSub) { ex ->
                    if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                }
            }
        }
    }

    private suspend fun fetchNetNaija(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val q = safeEncode(lc.pageTitle)
        for (dom in (listOf(activeNetnaijaBase) + netnaijaDomains).distinct()) {
            val html = safeGetText("$dom/search?t=$q", mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (html.isBlank()) continue
            activeNetnaijaBase = dom
            val postHref = Jsoup.parse(html, dom).select("article.sr-one h2 a, article h2.entry-title a, .search-results article a[href]")
                .firstOrNull { titleMatchesCandidate(it.text().trim(), lc.pageTitle) }
                ?.attr("href")?.let { normalizeUrl(it, dom) }.orEmpty()
            if (postHref.isBlank()) continue
            val postHtml = safeGetText(postHref, mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (postHtml.isBlank()) continue
            extractCandidateMediaAndEmbedUrls(postHtml, postHref).take(8).forEach { (u, kind) ->
                if (kind == "DIRECT") {
                    emitStream(lc, "NetNaija", u, isM3u8Stream(u, ""), null, mapOf("Referer" to postHref, "User-Agent" to USER_AGENT), emitLink)
                } else {
                    loadExtractor(u, postHref, emitSub) { ex ->
                        if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                    }
                }
            }
            break
        }
    }

    private suspend fun fetchNuvioAddons(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        val ctx = CloudStreamApp.context ?: return
        val manifests = MovieFoxSettingsDialog.getNuvioManifests(ctx).filter { it.enabled }
        if (manifests.isEmpty()) return
        val sNum = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val eNum = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val stremioType = if (lc.isTv) "series" else "movie"

        for (m in manifests) {
            val withoutQ = m.manifestUrl.substringBefore("?")
            val base = if (withoutQ.endsWith("/manifest.json", true)) withoutQ.dropLast("/manifest.json".length) else withoutQ.trimEnd('/')
            val qSuffix = if (m.manifestUrl.contains("?")) m.manifestUrl.substring(m.manifestUrl.indexOf('?')) else ""
            val ids = buildList {
                if (lc.imdbId.startsWith("tt")) add(lc.imdbId)
                lc.candidateIds.forEach { add("tmdb:$it"); add(it) }
            }.distinct()

            for (rawId in ids) {
                val sid = if (lc.isTv) "$rawId:$sNum:$eNum" else rawId
                val epUrl = "$base/stream/$stremioType/${safeUriEncode(sid, ":")}.json$qSuffix"
                val raw = safeGetText(epUrl, timeoutSec = lc.timeoutSec).orEmpty()
                val arr = runCatching { JSONObject(raw).optJSONArray("streams") }.getOrNull() ?: continue
                for (i in 0 until arr.length()) {
                    val sObj = arr.optJSONObject(i) ?: continue
                    val u = normalizeUrl(sObj.safeStr("url"), base)
                    if (u.startsWith("http")) {
                        val lbl = sObj.safeStr("name", m.label)
                        val titleLine = sObj.safeStr("title").lines().firstOrNull().orEmpty()
                        emitStream(lc, buildSourceLabel("Nuvio • ${m.label}", lbl, titleLine), u, isM3u8Stream(u, ""), "$lbl $titleLine", mapOf("Referer" to "$base/", "User-Agent" to USER_AGENT), emitLink)
                    }
                    sObj.optJSONArray("subtitles")?.let { subs ->
                        for (k in 0 until subs.length()) {
                            val sub = subs.optJSONObject(k) ?: continue
                            safeSubtitle(sub.safeStr("lang", "English"), sub.safeStr("url"), base)?.let { emitSub(it) }
                        }
                    }
                }
                break
            }
        }
    }

    private suspend fun fetchHiCine(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val headers = mapOf("Referer" to "$hicineApiBase/", "Accept" to "application/json")
        val searchRaw = safeGetText("$hicineApiBase/hapi/search?q=${safeEncode(lc.pageTitle)}&page=1", headers, lc.timeoutSec).orEmpty()
        val items = runCatching { JSONObject(searchRaw).optJSONArray("items") }.getOrNull() ?: return
        var pickedId: String? = null
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val id = obj.safeStr("id")
            val t = obj.safeStr("title")
            if (id.isNotBlank() && t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)) {
                pickedId = id
                break
            }
        }
        val titleId = pickedId ?: return
        val detailRaw = safeGetText("$hicineApiBase/hapi/t?c=${safeEncode(titleId)}", headers, lc.timeoutSec).orEmpty()
        val groups = runCatching { JSONObject(detailRaw).optJSONArray("groups") }.getOrNull() ?: return
        val targetEp = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1

        for (gIdx in 0 until minOf(groups.length(), 4)) {
            val grp = groups.optJSONObject(gIdx) ?: continue
            val gKey = grp.safeStr("k")
            if (gKey.isBlank()) continue
            val head = grp.safeStr("head")
            val qStr = listOf(grp.safeStr("quality", head), extractAudioSummary(head)).filter { it.isNotBlank() }.joinToString(" ")
            val grpRaw = safeGetText("$hicineApiBase/hapi/g?k=${safeEncode(gKey)}", headers, lc.timeoutSec).orEmpty()
            val gItems = runCatching { JSONObject(grpRaw).optJSONArray("items") }.getOrNull() ?: continue
            val chosenItem = (0 until gItems.length()).mapNotNull { gItems.optJSONObject(it) }
                .firstOrNull { !lc.isTv || it.optInt("ep", -1) == targetEp }
                ?: gItems.optJSONObject(0)
                ?: continue
            val hosts = chosenItem.optJSONArray("hosts") ?: continue
            for (hIdx in 0 until minOf(hosts.length(), 2)) {
                val hKey = hosts.optJSONObject(hIdx)?.safeStr("k").orEmpty()
                if (hKey.isBlank()) continue
                val dlRaw = safeGetText("$hicineApiBase/hapi/dl?k=${safeEncode(hKey)}", headers, lc.timeoutSec).orEmpty()
                val opts = runCatching { JSONObject(dlRaw).optJSONArray("options") }.getOrNull() ?: continue
                for (oIdx in 0 until minOf(opts.length(), 2)) {
                    val opt = opts.optJSONObject(oIdx) ?: continue
                    val goKey = opt.safeStr("ks").ifBlank { opt.safeStr("k") }
                    if (goKey.isBlank()) continue
                    val goRaw = safeGetText("$hicineApiBase/hapi/go?k=${safeEncode(goKey)}&m=u", headers, lc.timeoutSec).orEmpty()
                    val resolved = runCatching { JSONObject(goRaw).safeStr("url") }.getOrNull().orEmpty()
                    if (resolved.startsWith("http")) {
                        val srvLbl = opt.safeStr("name", "HiCine")
                        if (resolved.contains("gofile.io", true)) {
                            loadExtractor(resolved, "$hicineApiBase/", emitSub) { ex ->
                                if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                            }
                        } else {
                            emitStream(lc, buildSourceLabel("HiCine", srvLbl, qStr), resolved, isM3u8Stream(resolved, ""), qStr, headers, emitLink)
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchHindMoviez(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val q = safeEncode(lc.pageTitle)
        for (dom in hindmoviezDomains) {
            val html = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (html.isBlank()) continue
            val matchedAnchor = Jsoup.parse(html, dom).select("h2.entry-title > a[href], article a[href]")
                .firstOrNull { titleMatchesCandidate(it.text().trim(), lc.pageTitle) }
            val postHref = matchedAnchor?.attr("href")?.let { normalizeUrl(it, dom) }.orEmpty()
            if (postHref.isBlank()) continue
            val audioTag = extractAudioSummary(matchedAnchor?.text())
            val postHtml = safeGetText(postHref, mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (postHtml.isBlank()) continue
            extractCandidateMediaAndEmbedUrls(postHtml, postHref).take(8).forEach { (u, kind) ->
                if (kind == "DIRECT") {
                    emitStream(lc, buildSourceLabel("HindMoviez", "", audioTag), u, isM3u8Stream(u, ""), audioTag, mapOf("Referer" to postHref, "User-Agent" to USER_AGENT), emitLink)
                } else {
                    loadExtractor(u, postHref, emitSub) { ex ->
                        if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                    }
                }
            }
            break
        }
    }

    private suspend fun fetchMoviesLinkBd(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val q = safeUriEncode(lc.pageTitle)
        val domains = (movielinkbdDomains + movielinkbdHubUrl).distinct()
        for (dom in domains) {
            val html = safeGetText("$dom/search?q=$q", mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (html.isBlank()) continue
            val doc = Jsoup.parse(html, dom)
            val card = doc.select("div.movie-card, article").firstOrNull { el ->
                val img = el.selectFirst("img")
                val t = img?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                    ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: el.selectFirst("a")?.text()?.trim().orEmpty()
                t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
            } ?: continue
            val detailHref = card.selectFirst("a[href]")?.attr("href")?.let { normalizeUrl(it, dom) }.orEmpty()
            if (detailHref.isBlank()) continue
            val detailHtml = safeGetText(detailHref, mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (detailHtml.isBlank()) continue
            extractCandidateMediaAndEmbedUrls(detailHtml, detailHref).take(8).forEach { (u, kind) ->
                if (kind == "DIRECT") {
                    emitStream(lc, "MovieLinkBD", u, isM3u8Stream(u, ""), null, mapOf("Referer" to detailHref, "User-Agent" to USER_AGENT), emitLink)
                } else {
                    loadExtractor(u, detailHref, emitSub) { ex ->
                        if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                    }
                }
            }
            break
        }
    }

    private suspend fun fetchNetMirror(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val q = safeUriEncode(lc.pageTitle)
        val catalogs = listOf("nf" to "/mobile", "hs" to "/mobile/hs", "pv" to "/mobile/pv")
        val headers = mapOf("Referer" to "$netmirrorBaseUrl/home", "User-Agent" to USER_AGENT)
        for ((_, path) in catalogs) {
            val raw = safeGetText("$netmirrorBaseUrl$path/search.php?s=$q", headers, lc.timeoutSec).orEmpty()
            val arr = runCatching { JSONObject(raw).optJSONArray("searchResult") }.getOrNull() ?: continue
            for (i in 0 until minOf(arr.length(), 3)) {
                val item = arr.optJSONObject(i) ?: continue
                val t = item.safeStr("t")
                val id = item.safeStr("id")
                if (id.isNotBlank() && t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)) {
                    val plRaw = safeGetText("$netmirrorBaseUrl$path/playlist.php?id=${safeEncode(id)}&t=${safeEncode(t)}", headers, lc.timeoutSec).orEmpty()
                    extractCandidateMediaAndEmbedUrls(plRaw, netmirrorBaseUrl).take(6).forEach { (u, kind) ->
                        if (kind == "DIRECT") emitStream(lc, "NetMirror", u, isM3u8Stream(u, ""), null, headers, emitLink)
                    }
                    break
                }
            }
        }
    }

    private object MovyDecoder {
        private const val GOLDEN = -1640531527
        private fun fmix(value: Int): Int {
            var v = value
            v = v xor (v ushr 16)
            v *= 0x85ebca6b.toInt()
            v = v xor (v ushr 13)
            v *= 0xc2b2ae35.toInt()
            v = v xor (v ushr 16)
            return v
        }
        private fun rotl(value: Int, shift: Int): Int {
            val s = shift and 31
            return if (s == 0) value else (value shl s) or (value ushr (32 - s))
        }
        private fun mod61(value: Int): Int = ((value.toLong() and 0xFFFFFFFFL) % 61L).toInt()
        private fun fnv1aMixed(text: String): Int {
            var hash = -2128831035
            for (ch in text) {
                hash = hash xor ch.code
                hash *= 0x1000193
            }
            return fmix(hash)
        }
        private fun toUint32(text: String): Int {
            val d = text.trim().toDoubleOrNull() ?: return 0
            if (d.isNaN() || d.isInfinite()) return 0
            return (d.toLong() and 0xFFFFFFFFL).toInt()
        }
        private class State(val slots: IntArray, val filled: BooleanArray, var acc: Int)
        private fun initState(seed: String, mediaId: String): State {
            val slots = IntArray(61)
            val filled = BooleanArray(61)
            var n = fmix(fnv1aMixed(seed) xor fmix(toUint32(mediaId) xor GOLDEN))
            for (round in 0 until 8) {
                val slot = mod61(n)
                n = rotl(n + GOLDEN, 7 + (7 and round))
                slots[slot] = n xor fmix(n)
                filled[slot] = true
                n = fmix(n + slot)
            }
            return State(slots, filled, fmix(0xa5a5a5a5.toInt() xor n))
        }
        private fun nextWord(state: State, counter: Int): Int {
            val slot = mod61(state.acc)
            val known = state.filled[slot]
            val picked = if (known) state.slots[slot] else 0
            val salt = picked xor (GOLDEN * (counter + 1))
            val blended = if (known) state.acc or salt else state.acc xor salt
            val rotated = rotl(blended + state.acc, slot and 31) xor rotl(state.acc, 31 and (slot * 7))
            val word = fmix(rotated + GOLDEN)
            state.slots[slot] = word
            state.filled[slot] = true
            state.acc = word
            return word
        }
        private fun keystream(seed: String, mediaId: String, length: Int): ByteArray {
            val state = initState(seed, mediaId)
            val out = ByteArray(length)
            var idx = 0; var ctr = 0
            while (idx < length) {
                val w = nextWord(state, ctr++)
                out[idx++] = w.toByte()
                if (idx < length) out[idx++] = (w ushr 8).toByte()
                if (idx < length) out[idx++] = (w ushr 16).toByte()
                if (idx < length) out[idx++] = (w ushr 24).toByte()
            }
            return out
        }
        fun decrypt(payload: String, seed: String, mediaId: String): String? = runCatching {
            var b64 = payload.replace("-", "+").replace("_", "/").trimEnd('=')
            while (b64.length % 4 != 0) b64 += "="
            val raw = Base64.decode(b64, Base64.DEFAULT)
            if (raw.size <= 4) return@runCatching null
            val mask = keystream(seed, mediaId, raw.size)
            val plain = ByteArray(raw.size) { i -> (raw[i].toInt() xor mask[i].toInt()).toByte() }
            if (plain[0] != 109.toByte() || plain[1] != 118.toByte() || plain[2] != 109.toByte() || plain[3] != 49.toByte()) return@runCatching null
            String(plain, 4, plain.size - 4, Charsets.UTF_8)
        }.getOrNull()
    }

    private suspend fun fetchMovy(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        val tmdbId = lc.candidateIds.firstOrNull() ?: return
        if (lc.pageTitle.isBlank()) return
        val headers = mapOf("Referer" to "$movyMainUrl/", "Origin" to movyMainUrl, "User-Agent" to USER_AGENT)
        val seedRaw = safeGetText("$movyStreamApiBase/seed?mediaId=${safeEncode(tmdbId)}", headers, lc.timeoutSec).orEmpty()
        val seed = runCatching { JSONObject(seedRaw).safeStr("seed") }.getOrNull().orEmpty()
        if (seed.isBlank()) return

        val mType = if (lc.isTv) "tv" else "movie"
        val sNum = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val eNum = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val encTitle = safeEncode(lc.pageTitle).replace("+", "%20")
        val cities = listOf("miami", "boise", "vegas", "delhi", "atlanta", "dallas")

        for (city in cities) {
            val u = "$movyStreamApiBase/$city/sources?title=$encTitle&mediaType=$mType&year=0&episodeId=$eNum&seasonId=$sNum&tmdbId=${safeEncode(tmdbId)}&enc=2&seed=${safeEncode(seed)}"
            val encBody = safeGetText(u, headers, lc.timeoutSec).orEmpty()
            if (encBody.isBlank()) continue
            val jsonText = MovyDecoder.decrypt(encBody, seed, tmdbId) ?: continue
            val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
            val sources = root.optJSONArray("sources") ?: continue
            for (i in 0 until sources.length()) {
                val sObj = sources.optJSONObject(i) ?: continue
                val sUrl = sObj.safeStr("url")
                val qLbl = sObj.safeStr("quality", "HD")
                if (sUrl.startsWith("http")) {
                    emitStream(lc, buildSourceLabel("Movy", city.replaceFirstChar { it.uppercase() }, qLbl), sUrl, !sUrl.contains("/mp4/", true), qLbl, headers, emitLink)
                }
            }
            root.optJSONArray("subtitles")?.let { subs ->
                for (k in 0 until subs.length()) {
                    val sub = subs.optJSONObject(k) ?: continue
                    val lang = sub.safeStr("language").ifBlank { sub.safeStr("lang", "English") }
                    safeSubtitle(lang, sub.safeStr("url"), movyMainUrl)?.let { emitSub(it) }
                }
            }
        }
    }

    private suspend fun fetchHdHub4u(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val q = safeUriEncode(lc.pageTitle)
        val tsUrl = "$hdhub4uTypesenseUrl?q=$q&query_by=post_title,category&query_by_weights=4,2&sort_by=sort_by_date:desc&limit=15&highlight_fields=none&use_cache=true&page=1"
        val tsRaw = safeGetText(tsUrl, mapOf("Referer" to "${hdhub4uDomains.first()}/"), lc.timeoutSec).orEmpty()
        val hits = runCatching { JSONObject(tsRaw).optJSONArray("hits") }.getOrNull()
        val detailEntries = mutableListOf<Pair<String, String>>()

        if (hits != null) {
            for (i in 0 until hits.length()) {
                val d = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                val t = d.safeStr("post_title")
                val p = d.safeStr("permalink")
                if (t.isNotBlank() && p.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)) {
                    val fixed = if (p.startsWith("http", true)) {
                        val path = runCatching { java.net.URI(p).path }.getOrNull().orEmpty()
                        if (path.isNotBlank()) hdhub4uDomains.first() + path else p
                    } else {
                        normalizeUrl(p, hdhub4uDomains.first())
                    }
                    detailEntries.add(fixed to extractAudioSummary(t))
                }
            }
        }

        if (detailEntries.isEmpty()) {
            for (dom in hdhub4uDomains) {
                val html = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
                if (html.isBlank()) continue
                val hit = Jsoup.parse(html, dom).select("a[href]").firstOrNull { a ->
                    val t = a.attr("title").ifBlank { a.text() }.trim()
                    t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                }
                if (hit != null) {
                    val t = hit.attr("title").ifBlank { hit.text() }.trim()
                    detailEntries.add(normalizeUrl(hit.attr("href"), dom) to extractAudioSummary(t))
                }
                if (detailEntries.isNotEmpty()) break
            }
        }

        for ((dUrl, audioTag) in detailEntries.distinctBy { it.first }.take(2)) {
            val html = safeGetText(dUrl, mapOf("Referer" to "${hdhub4uDomains.first()}/"), lc.timeoutSec).orEmpty()
            if (html.isBlank()) continue
            extractCandidateMediaAndEmbedUrls(html, dUrl).take(8).forEach { (u, kind) ->
                if (kind == "DIRECT") {
                    emitStream(lc, buildSourceLabel("HDHub4u", "", audioTag), u, isM3u8Stream(u, ""), audioTag, mapOf("Referer" to dUrl, "User-Agent" to USER_AGENT), emitLink)
                } else {
                    loadExtractor(u, dUrl, emitSub) { ex ->
                        if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                    }
                }
            }
        }
    }

    private suspend fun fetch4KhdHub(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val q = safeUriEncode(lc.pageTitle)
        for (dom in fourKhdHubDomains) {
            val html = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (html.isBlank()) continue
            val doc = Jsoup.parse(html, dom)
            val card = doc.select("div.card-grid > a.movie-card, a.movie-card[href], a[href]").firstOrNull { el ->
                val text = el.selectFirst("div.movie-card-content")?.text()?.trim().orEmpty().ifBlank { el.attr("title").ifBlank { el.text() } }
                text.isNotBlank() && titleMatchesCandidate(text, lc.pageTitle)
            } ?: continue
            val cardText = card.selectFirst("div.movie-card-content")?.text()?.trim().orEmpty().ifBlank { card.attr("title").ifBlank { card.text() } }
            val audioTag = extractAudioSummary(cardText)
            val detailHref = card.attr("href").trim().let { normalizeUrl(it, dom) }
            val detailHtml = safeGetText(detailHref, mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (detailHtml.isBlank()) continue
            extractCandidateMediaAndEmbedUrls(detailHtml, detailHref).take(8).forEach { (u, kind) ->
                if (kind == "DIRECT") {
                    emitStream(lc, buildSourceLabel("4KHDHub", "", listOfNotNull("4K", audioTag.takeIf { it.isNotBlank() }).joinToString(" • ")), u, isM3u8Stream(u, ""), "4K $audioTag", mapOf("Referer" to detailHref, "User-Agent" to USER_AGENT), emitLink)
                } else {
                    loadExtractor(u, detailHref, emitSub) { ex ->
                        if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                    }
                }
            }
            break
        }
    }

    private suspend fun fetchMovies4u(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val q = safeUriEncode(lc.pageTitle)
        for (dom in movies4uDomains) {
            val raw = safeGetText("$dom/lookup.php?q=$q&page=1&per_page=25", mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            val hits = runCatching { JSONObject(raw).optJSONArray("hits") }.getOrNull() ?: continue
            val chosen = (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }
                .firstOrNull { hit ->
                    val t = hit.safeStr("post_title")
                    val p = hit.safeStr("permalink")
                    t.isNotBlank() && p.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                } ?: continue
            val audioTag = extractAudioSummary(chosen.safeStr("post_title"))
            val postUrl = normalizeUrl(chosen.safeStr("permalink"), dom)
            val postHtml = safeGetText(postUrl, mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (postHtml.isBlank()) continue
            extractCandidateMediaAndEmbedUrls(postHtml, postUrl).take(8).forEach { (u, kind) ->
                if (kind == "DIRECT") {
                    emitStream(lc, buildSourceLabel("Movies4u", "", audioTag), u, isM3u8Stream(u, ""), audioTag, mapOf("Referer" to postUrl, "User-Agent" to USER_AGENT), emitLink)
                } else {
                    loadExtractor(u, postUrl, emitSub) { ex ->
                        if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                    }
                }
            }
            break
        }
    }

    private suspend fun fetchTmf(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val q = safeUriEncode(lc.pageTitle)
        for (dom in tmfDomains) {
            val html = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (html.isBlank()) continue
            val matchedEl = Jsoup.parse(html, dom).select("article.latestpost a[id=featured-thumbnail], article a[href]")
                .firstOrNull { el ->
                    val t = el.attr("title").trim().ifBlank { el.selectFirst("img")?.attr("alt")?.trim().orEmpty() }.ifBlank { el.text().trim() }
                    t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                }
            val postHref = matchedEl?.attr("href")?.let { normalizeUrl(it, dom) }.orEmpty()
            if (postHref.isBlank()) continue
            val matchedTitle = matchedEl?.attr("title").orEmpty().ifBlank { matchedEl?.text().orEmpty() }
            val audioTag = extractAudioSummary(matchedTitle)
            val postHtml = safeGetText(postHref, mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (postHtml.isBlank()) continue
            extractCandidateMediaAndEmbedUrls(postHtml, postHref).take(8).forEach { (u, kind) ->
                if (kind == "DIRECT") {
                    emitStream(lc, buildSourceLabel("TMF", "", audioTag), u, isM3u8Stream(u, ""), audioTag, mapOf("Referer" to postHref, "User-Agent" to USER_AGENT), emitLink)
                } else {
                    loadExtractor(u, postHref, emitSub) { ex ->
                        if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                    }
                }
            }
            break
        }
    }

    private suspend fun fetchMultimovies(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.pageTitle.isBlank()) return
        val q = safeUriEncode(lc.pageTitle)
        for (dom in multimoviesDomains) {
            val html = safeGetText("$dom/search?q=$q", mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (html.isBlank()) continue
            val doc = Jsoup.parse(html, dom)
            val matchedAnchor = doc.select("article.poster-card a[href], a[href*=/movies/], a[href*=/series/]")
                .firstOrNull { a ->
                    val t = a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }.ifBlank { a.text() }.trim()
                    t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                }
            val cardHref = matchedAnchor?.attr("href")?.let { normalizeUrl(it, dom) }.orEmpty()
            if (cardHref.isBlank()) continue
            val audioTag = extractAudioSummary(matchedAnchor?.attr("title"), matchedAnchor?.text())
            val postHtml = safeGetText(cardHref, mapOf("Referer" to "$dom/"), lc.timeoutSec).orEmpty()
            if (postHtml.isBlank()) continue
            extractCandidateMediaAndEmbedUrls(postHtml, cardHref).take(8).forEach { (u, kind) ->
                if (kind == "DIRECT") {
                    emitStream(lc, buildSourceLabel("Multimovies", "", audioTag), u, isM3u8Stream(u, ""), audioTag, mapOf("Referer" to cardHref, "User-Agent" to USER_AGENT), emitLink)
                } else {
                    loadExtractor(u, cardHref, emitSub) { ex ->
                        if (lc.seenUrls.add(ex.url)) emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                    }
                }
            }
            break
        }
    }

    private suspend fun fetchCustomCs3Server(
        srvKey: String,
        lc: LinkContext,
        isCasting: Boolean,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        val ctx = CloudStreamApp.context ?: return
        val cs3Srv = MovieFoxSettingsDialog.getEnabledCs3Servers(ctx).firstOrNull { it.key == srvKey } ?: return
        val baseProbe = normalizeUrl(cs3Srv.probeUrl, mainUrl)
        if (baseProbe.isBlank()) return

        if (baseProbe.substringBefore("?").endsWith(".cs3", true)) {
            val plugins = cs3PluginCache.getOrPut(baseProbe) {
                runCatching {
                    val dir = File(ctx.codeCacheDir, "mf_cs3_plugins").apply { mkdirs() }
                    val hash = MessageDigest.getInstance("MD5").digest(baseProbe.toByteArray()).joinToString("") { "%02x".format(it) }
                    val file = File(dir, "$hash.cs3")
                    if (!file.exists() || file.length() == 0L) {
                        val res = app.get(baseProbe, headers = mapOf("User-Agent" to USER_AGENT), timeout = lc.timeoutSec)
                        if (res.code in 200..299) {
                            val bytes = res.body.bytes()
                            if (bytes.isNotEmpty()) file.writeBytes(bytes)
                        } else {
                            runCatching { res.okhttpResponse.close() }
                        }
                    }
                    if (!file.exists() || file.length() == 0L) return@runCatching emptyList()
                    val clsNames = mutableListOf<String>()
                    ZipFile(file).use { zf ->
                        val en = zf.entries()
                        while (en.hasMoreElements()) {
                            val entry = en.nextElement()
                            if (!entry.isDirectory && entry.name.endsWith(".dex", true)) {
                                val dexStr = String(zf.getInputStream(entry).readBytes(), Charsets.ISO_8859_1)
                                Regex("""L([a-zA-Z0-9_/]+/[A-Z][a-zA-Z0-9_]+(?:Provider|Plugin|Source));""").findAll(dexStr).forEach {
                                    clsNames.add(it.groupValues[1].replace('/', '.'))
                                }
                            }
                        }
                    }
                    val optDir = File(dir, "opt_$hash").apply { mkdirs() }
                    val loader = DexClassLoader(file.absolutePath, optDir.absolutePath, null, MovieFoxProvider::class.java.classLoader)
                    clsNames.distinct().mapNotNull { name ->
                        runCatching {
                            val clz = loader.loadClass(name)
                            if (!Modifier.isAbstract(clz.modifiers) && MainAPI::class.java.isAssignableFrom(clz)) {
                                clz.getDeclaredConstructor().newInstance() as MainAPI
                            } else null
                        }.getOrNull()
                    }
                }.getOrDefault(emptyList())
            }

            withContext(Dispatchers.IO) {
                for (plugin in plugins) {
                    if (lc.pageTitle.isBlank()) continue
                    val hits = runCatching { plugin.search(lc.pageTitle) }.getOrNull().orEmpty()
                    val hit = hits.firstOrNull { titleMatchesCandidate(it.name, lc.pageTitle) } ?: hits.firstOrNull() ?: continue
                    val loadRes = runCatching { plugin.load(hit.url) }.getOrNull() ?: continue
                    val targetData = if (loadRes is TvSeriesLoadResponse) {
                        val sN = lc.season.toIntOrNull() ?: 1
                        val eN = lc.episode.toIntOrNull() ?: 1
                        loadRes.episodes.firstOrNull { it.season == sN && it.episode == eN }?.data ?: loadRes.episodes.firstOrNull()?.data
                    } else (loadRes as? MovieLoadResponse)?.dataUrl ?: hit.url
                    if (targetData != null) {
                        runCatching {
                            plugin.loadLinks(targetData, isCasting, emitSub) { ex ->
                                if (lc.seenUrls.add(ex.url) && isQualityAllowed(lc, ex.quality, ex.name)) {
                                    emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                                }
                            }
                        }
                    }
                }
            }
            return
        }

        val sep = if (baseProbe.contains("?")) "&" else "?"
        val queryUrl = if (lc.pageTitle.isNotBlank()) "$baseProbe${sep}q=${safeEncode(lc.pageTitle)}" else baseProbe
        val body = safeGetText(queryUrl, timeoutSec = lc.timeoutSec)?.trim().orEmpty()
        if (body.isBlank()) return
        if (body.startsWith("{")) {
            parseAndEmitApiJson("CS3 • ${cs3Srv.name}", body, lc, lc.baseHeaders, emitSub, emitLink)
        } else {
            extractCandidateMediaAndEmbedUrls(body, queryUrl).take(8).forEach { (u, kind) ->
                if (kind == "DIRECT") emitStream(lc, "CS3 • ${cs3Srv.name}", u, isM3u8Stream(u, ""), null, lc.baseHeaders, emitLink)
            }
        }
    }
}

