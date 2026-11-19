package com.hungrysturdy

import android.net.Uri
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetUrl = if (page > 1) {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page"
        } else {
            request.data
        }

        val rawHtml = app.get(targetUrl, interceptor = cfKiller).text
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
        val document = app.get("$mainUrl/search?q=$query", interceptor = cfKiller).document
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
        val responseText = app.get(url, interceptor = cfKiller).text
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

    private suspend fun isM3u8Stream(url: String, typeHint: String, headers: Map<String, String>): Boolean {
        if (
            typeHint.equals("hls", true) ||
            typeHint.equals("m3u8", true) ||
            url.contains(".m3u8", true) ||
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
        ) {
            return true
        }

        return runCatching {
            val res = app.get(url, headers = headers, interceptor = cfKiller)
            val contentType = res.headers["Content-Type"].orEmpty()
            contentType.contains("mpegurl", true) || res.text.trimStart().startsWith("#EXTM3U")
        }.getOrDefault(false)
    }

    // Prmovies Helpers
    private fun prmoviesMutexFor(url: String): Mutex =
        prmoviesMutexMap.getOrPut(runCatching { Uri.parse(url).host }.getOrNull() ?: activePrmoviesBase) { Mutex() }

    private fun prmoviesKillerFor(url: String): CloudflareKiller =
        prmoviesCfMap.getOrPut(runCatching { Uri.parse(url).host }.getOrNull() ?: activePrmoviesBase) { CloudflareKiller() }

    private suspend fun fetchPrmoviesSingleUrl(targetUrl: String, referer: String? = null): NiceResponse? {
        return runCatching {
            val response = app.get(targetUrl, headers = prmoviesHeaders, referer = referer)
            val resolved = if (response.code !in listOf(403, 503)) {
                response
            } else {
                prmoviesMutexFor(targetUrl).withLock {
                    val killer = prmoviesKillerFor(targetUrl)
                    val retry = app.get(targetUrl, headers = prmoviesHeaders, interceptor = killer, referer = referer)
                    if (retry.code in listOf(403, 503)) {
                        killer.savedCookies.clear()
                        app.get(targetUrl, headers = prmoviesHeaders, interceptor = killer, referer = referer)
                    } else {
                        retry
                    }
                }
            }
            if (resolved.code in 200..299) resolved else null
        }.getOrNull()
    }

    private suspend fun getPrmoviesPage(url: String, referer: String? = null): NiceResponse? {
        val matchedDomain = prmoviesMirrors.firstOrNull { url.startsWith(it, ignoreCase = true) }
            ?: return fetchPrmoviesSingleUrl(url, referer)

        val candidates = (listOf(activePrmoviesBase.trimEnd('/')) + prmoviesMirrors).distinct()
        for (domain in candidates) {
            val candidateUrl = domain + url.substring(matchedDomain.length)
            val response = fetchPrmoviesSingleUrl(candidateUrl, referer)
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
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val clean = normalizeUrl(embedUrl, activePrmoviesBase)
            if (clean.isBlank() || isAdUrl(clean)) return false
            val host = runCatching { Uri.parse(clean).host }.getOrNull() ?: "speedostream1.com"
            val r = getPrmoviesPage(clean, referer = "https://$host/") ?: return false
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
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val clean = normalizeUrl(embedUrl, activePrmoviesBase)
            if (clean.isBlank() || isAdUrl(clean)) return false
            val r1 = getPrmoviesPage(clean, referer = referer) ?: return false
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

            val r2 = getPrmoviesPage(playerUrl, referer = clean) ?: return false
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
                if (resolvePrmoviesSpeedostream(src, label, seenUrls, callback)) foundLink = true
            }
        }

        if (!foundLink) {
            for (a in doc.select("#list-downloads a[href*=speedostream]")) {
                val href = a.attr("href")
                if (href.isBlank() || href.contains("embed-")) continue
                val label = a.selectFirst(".serv_tit")?.text()?.ifBlank { null } ?: "Prmovies"
                if (resolvePrmoviesSpeedostream(href, "$label • Download", seenUrls, callback)) foundLink = true
            }
        }
        return foundLink
    }

    private suspend fun resolvePrmoviesEpisodePage(
        doc: Element,
        pageUrl: String,
        seenUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLink = false
        for (iframe in doc.select("#player2 iframe[src], div.movieplay iframe[src]")) {
            val src = iframe.attr("src")
            if (src.contains("vidsrc", true)) {
                if (resolvePrmoviesVidsrc(src, pageUrl, seenUrls, callback)) foundLink = true
            } else if (src.contains("speedostream", true) && src.contains("embed-")) {
                if (resolvePrmoviesSpeedostream(src, "Prmovies", seenUrls, callback)) foundLink = true
            }
        }
        return foundLink
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val appCtx = CloudStreamApp.context
            val cleanPageUrl = data.substringBefore("?")
            val rawHtml = app.get(cleanPageUrl, interceptor = cfKiller).text
            val document = Jsoup.parse(rawHtml, mainUrl)
            val flightText = extractUnescapedFlightText(rawHtml)

            val pageTitle = document.selectFirst("h1")?.text()?.trim()
                .orEmpty()
                .ifEmpty { Regex("\"title\":\"([^\"]+)\"").find(flightText)?.groupValues?.get(1).orEmpty() }
                .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
                .trim()

            // Extract both internal ID and TMDB ID so Purstream/Castle/Watchout/FSL/GDrive get the exact ID
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

            // Build endpoints for all candidate IDs
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

            val maxConcurrency = appCtx?.let { MovieFoxSettingsDialog.getParallelServers(it) } ?: 10
            val downloadOnly = appCtx?.let { MovieFoxSettingsDialog.isDownloadOnly(it) } ?: false

            val activeEndpoints = allEndpoints
                .distinctBy { it.second }
                .filter { (key, _) -> appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, key) } ?: true }
                .take(maxConcurrency * candidateIds.size)

            val baseHeaders = mapOf(
                "Referer" to watchUrl,
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/plain, */*"
            )

            val seenUrls = mutableSetOf<String>()
            var found = false

            suspend fun emitServerLink(
                serverName: String,
                streamUrl: String,
                isHls: Boolean,
                qualityStr: String? = null,
                customHeaders: Map<String, String> = baseHeaders
            ) {
                if (isAdUrl(streamUrl) || !seenUrls.add(streamUrl)) return
                if (downloadOnly && isHls) return

                callback(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = streamUrl,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = customHeaders["Referer"] ?: watchUrl
                        this.quality = getQuality(qualityStr)
                        this.headers = customHeaders
                    }
                )
                found = true
            }

            // 1. Built-in MovieFox API Endpoints
            for ((endpointName, apiUrl) in activeEndpoints) {
                val response = runCatching {
                    app.get(apiUrl, headers = baseHeaders, interceptor = cfKiller)
                }.getOrNull() ?: continue

                val rawText = response.text
                if (rawText.isBlank()) continue

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
                            val extracted = runCatching {
                                loadExtractor(streamUrl, watchUrl, subtitleCallback, callback)
                            }.getOrDefault(false)
                            if (extracted) found = true
                        }
                        continue
                    }

                    val mergedHeaders = baseHeaders + (stream.headers ?: emptyMap())
                    val isHls = isM3u8Stream(streamUrl, stream.type, mergedHeaders)

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
                    app.get(watchUrl, headers = baseHeaders, interceptor = cfKiller).text
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

                hostScanRegex.findAll(combinedScanText).forEach { m ->
                    val hostUrl = normalizeUrl(m.value)
                    if (isAdUrl(hostUrl) || !seenUrls.add(hostUrl)) return@forEach

                    if (hostUrl.contains("workers.dev", true)) {
                        emitServerLink(
                            serverName = "WorkerHLS",
                            streamUrl = hostUrl,
                            isHls = true,
                            qualityStr = "1080p",
                            customHeaders = baseHeaders + mapOf("Referer" to watchUrl)
                        )
                    } else {
                        val extracted = runCatching {
                            loadExtractor(hostUrl, watchUrl, subtitleCallback, callback)
                        }.getOrDefault(false)
                        if (extracted) found = true
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
                            } else {
                                val extracted = runCatching {
                                    loadExtractor(embedUrl, watchUrl, subtitleCallback, callback)
                                }.getOrDefault(false)
                                if (extracted) found = true
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
                        app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT), interceptor = cfKiller).text
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
                            interceptor = cfKiller
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
                    extDoc.select("iframe[src], [data-src], [data-link], [data-url]").forEach { el ->
                        val iframeUrl = normalizeUrl(
                            el.attr("src")
                                .ifEmpty { el.attr("data-src") }
                                .ifEmpty { el.attr("data-link") }
                                .ifEmpty { el.attr("data-url") },
                            baseSiteUrl
                        )
                        if (iframeUrl.startsWith("http") && !isAdUrl(iframeUrl) && seenUrls.add(iframeUrl)) {
                            val extracted = runCatching {
                                loadExtractor(iframeUrl, targetPageUrl, subtitleCallback, callback)
                            }.getOrDefault(false)
                            if (extracted) found = true
                        }
                    }

                    val extStreamRegex = Regex(
                        """["'](https?://[^"'\s<>]+?(?:\.(?:m3u8|mp4)|workers\.dev/[^"'\s<>]+|itsnitrox\.tech/[^"'\s<>]+|vibuxer\.com[^"'\s<>]*|type=hls)[^"'\s<>]*)["']""",
                        RegexOption.IGNORE_CASE
                    )
                    extStreamRegex.findAll(extHtml).forEach { m ->
                        val foundStream = normalizeUrl(m.groupValues[1], baseSiteUrl)
                        if (isAdUrl(foundStream)) return@forEach
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
                    extHostRegex.findAll(extHtml).forEach { m ->
                        val hostUrl = normalizeUrl(m.value, baseSiteUrl)
                        if (!isAdUrl(hostUrl) && seenUrls.add(hostUrl)) {
                            val extracted = runCatching {
                                loadExtractor(hostUrl, targetPageUrl, subtitleCallback, callback)
                            }.getOrDefault(false)
                            if (extracted) found = true
                        }
                    }
                }
            }

            // 4. Sflix Servers Integration inside MovieFoxProvider
            val sflixEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "sflix") } ?: true
            if (sflixEnabled && pageTitle.isNotBlank()) {
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
                        interceptor = cfKiller
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
                            interceptor = cfKiller
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

                                    val playResp = runCatching { app.get(playUrl, headers = webHeaders) }.getOrNull() ?: continue
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

                                            callback.invoke(
                                                newExtractorLink(
                                                    source = "Sflix ($srvLabel)",
                                                    name = "Sflix - $srvLabel [$kind $qualityLabel]",
                                                    url = streamUrl,
                                                    type = linkType
                                                ) {
                                                    this.referer = "$sflixPlayerUrl/"
                                                    this.headers = streamHeaders
                                                    this.quality = maxRes
                                                }
                                            )
                                            found = true
                                            serverMatched = true
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
            }

            // 5. Prmovies Integration inside MovieFoxProvider
            val prmoviesEnabled = appCtx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "prmovies") } ?: true
            if (prmoviesEnabled && pageTitle.isNotBlank()) {
                runCatching {
                    val query = pageTitle.trim().replace(Regex("\\s+"), "+")
                    val searchHtml = getPrmoviesPage("$activePrmoviesBase/?s=$query")?.text.orEmpty()
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
                            val detailHtml = getPrmoviesPage(matchedUrl)?.text.orEmpty()
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
                                        val epHtml = getPrmoviesPage(epHref, referer = matchedUrl)?.text.orEmpty()
                                        if (epHtml.isNotBlank()) {
                                            targetPageUrl = epHref
                                            targetDoc = Jsoup.parse(epHtml, activePrmoviesBase)
                                        }
                                    }
                                }

                                val prmoviesFound = if (targetPageUrl.contains("/episode/", true)) {
                                    resolvePrmoviesEpisodePage(targetDoc, targetPageUrl, seenUrls, callback)
                                } else {
                                    resolvePrmoviesPagePlayer(targetDoc, seenUrls, callback)
                                }
                                if (prmoviesFound) found = true
                            }
                        }
                    }
                }
            }

            found
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
