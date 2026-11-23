package com.hungrysturdy

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
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
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

class MovieFoxProvider : MainAPI() {

    override var mainUrl = "https://moviefox.pro"
    override var name = "MovieFox"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true

    init {
        runCatching {
            val dispatcher = app.baseClient.dispatcher
            if (dispatcher.maxRequestsPerHost < 40) dispatcher.maxRequestsPerHost = 40
            if (dispatcher.maxRequests < 160) dispatcher.maxRequests = 160
        }
    }

    private val logTag = "MovieFoxProvider"
    private val effectiveUa: String
        get() = MovieFoxSettingsDialog.CF_BYPASS_UA

    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"
    private val tmdbBaseUrl = MfRegistries.TMDB_API_BASE
    private val firebaseDomainsUrl = MfRegistries.FIREBASE_RTDB_URL
    private val phisherDomainsUrl = MfRegistries.PHISHER_DOMAINS_JSON
    private val kaperwanUrlsJson = MfRegistries.KAPERWAN_URLS_JSON

    private val pixeldrainDevUrl = "https://pixeldrain.dev"
    private val pixeldrainApiBase = "https://pixeldrain.dev/api/file"

    private val prmoviesMirrors = listOf(
        "https://prmovies.kids",
        "https://prmovies.beer",
        "https://prmovies.repair",
        "https://prmovies.com.cv",
        "https://prmovies.coupons",
        "https://prmovies.com"
    )
    @Volatile
    private var activePrmoviesBase = "https://prmovies.kids"

    private val fastWaveKeys = setOf(
        "pixeldrain_dev", "fsl_fastdl", "hubcloud_vcloud", "gdflix_drive",
        "gofile", "voe_streamtape", "nxstreamhub_nitrox", "wootly_embeds", "prmovies"
    )

    private val dynamicDomainCache = ConcurrentHashMap<String, String>()
    private val cs3PluginCache = ConcurrentHashMap<String, List<MainAPI>>()
    private val cs3DownloadLocks = ConcurrentHashMap<String, Mutex>()

    private val titleStopWords = setOf(
        "the", "a", "an", "of", "in", "on", "at", "to", "for", "and", "or", "is", "are", "with", "by"
    )

    private val multiAudioRegex = Regex(
        """(?i)\b(hindi|english|tamil|telugu|kannada|malayalam|punjabi|marathi|bengali|urdu|gujarati|""" +
            """japanese|korean|chinese|spanish|french|german|russian|turkish|arabic|""" +
            """dual[ -]?audio|multi[ -]?audio|original|hin|eng|tam|tel|mal|kan|kor|jpn|chi|spa|fre|ger)\b"""
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
        "$mainUrl/tv?list=popular" to "Popular TV Shows",
        "$mainUrl/movies?lang=hi" to "Indian Movies (Hindi)",
        "$mainUrl/anime" to "Anime"
    )

    private val ignoredLinkTexts = setOf(
        "where to watch", "more info", "watch now", "view all",
        "play", "play now", "trailer", "all options", "go home", "search"
    )

    private fun logError(source: String, url: String, err: Throwable?, extra: String = "") {
        if (err is CancellationException) return
        Log.e(logTag, "[$source] $extra | $url -> ${err?.message.orEmpty()}", err)
        MovieFoxSettingsDialog.logSiteError(source, url, err, extra)
    }

    private fun isExtOn(key: String): Boolean =
        CloudStreamApp.context?.let { MovieFoxSettingsDialog.isExtractorEnabled(it, key) } ?: true

    private fun isJunkDynamicUrl(url: String): Boolean {
        val low = url.lowercase()
        return low.contains("google.com") ||
            low.contains("gstatic.com") ||
            low.contains("gravatar.com") ||
            low.contains("cloudflare.com") ||
            low.contains("vegamovies.latino") ||
            low.length < 10
    }

    private suspend fun fetchDynamicDomain(fallback: String, key: String, registryKey: String = key): String {
        dynamicDomainCache[key]?.let { if (!isJunkDynamicUrl(it)) return it }
        return try {
            val fbRaw = runCatching { app.get(firebaseDomainsUrl, timeout = 4L).text }.getOrNull().orEmpty()
            val fbObj = runCatching { JSONObject(fbRaw) }.getOrNull()
            val fromFb = listOf(key, "${key}_url", "${key}_domain", registryKey)
                .firstNotNullOfOrNull { k ->
                    fbObj?.optString(k)?.trim()?.trimEnd('/')
                        ?.takeIf { it.startsWith("http") && !isJunkDynamicUrl(it) }
                }
            if (fromFb != null) {
                dynamicDomainCache[key] = fromFb
                return fromFb
            }

            val raw = app.get(kaperwanUrlsJson, timeout = 5L).text
            val resolved = JSONObject(raw).optString(registryKey).trim().trimEnd('/')
            if (resolved.startsWith("http") && !isJunkDynamicUrl(resolved)) {
                dynamicDomainCache[key] = resolved
                return resolved
            }

            val phRaw = runCatching { app.get(phisherDomainsUrl, timeout = 5L).text }.getOrNull().orEmpty()
            val phResolved = runCatching { JSONObject(phRaw).optString(registryKey).trim().trimEnd('/') }.getOrNull().orEmpty()
            if (phResolved.startsWith("http") && !isJunkDynamicUrl(phResolved)) {
                dynamicDomainCache[key] = phResolved
                phResolved
            } else {
                fallback
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }
    }

    private suspend fun buildDomainCandidates(
        defaults: List<String>,
        dynamicKey: String,
        registryKey: String = dynamicKey,
        preferredFirst: String? = null
    ): List<String> {
        val dyn = runCatching {
            fetchDynamicDomain(defaults.firstOrNull().orEmpty(), dynamicKey, registryKey)
        }.getOrNull().orEmpty()
        return (listOfNotNull(preferredFirst, dyn) + defaults)
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http") && !isJunkDynamicUrl(it) }
            .distinct()
    }

    private suspend fun <T> tryMirrorDomains(
        sourceTag: String,
        domains: List<String>,
        perDomainTimeoutMs: Long = 6_500L,
        block: suspend (domain: String) -> T?
    ): Pair<String, T>? {
        for (dom in domains) {
            val res = withTimeoutOrNull(perDomainTimeoutMs) {
                runCatching { block(dom) }
                    .onFailure { e ->
                        if (e !is CancellationException) {
                            logError(sourceTag, dom, e, "Mirror failed")
                        }
                    }
                    .getOrNull()
            }
            if (res != null && !(res is Collection<*> && res.isEmpty())) {
                return dom to res
            }
        }
        return null
    }

    private fun currentTimeoutSec(minSec: Long = 8L): Long {
        val ctx = CloudStreamApp.context ?: return minSec
        return MovieFoxSettingsDialog.getTimeoutSeconds(ctx).toLong().coerceAtLeast(minSec)
    }

    private fun directHeaders(url: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val ctx = CloudStreamApp.context
        val savedCookie = MovieFoxSettingsDialog.getCookieForDomain(ctx, url)
        val out = mutableMapOf<String, String>()
        out["User-Agent"] = effectiveUa
        out["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        out["Accept-Language"] = "en-US,en;q=0.9"
        if (!savedCookie.isNullOrBlank()) out["Cookie"] = savedCookie
        out.putAll(extra)
        return out
    }

    private suspend fun safeGetText(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutSec: Long = currentTimeoutSec(),
        retries: Int = 2,
        sourceTag: String = "HTTP"
    ): String? {
        val headers = directHeaders(url, extraHeaders)
        repeat(retries.coerceAtLeast(1)) { attempt ->
            val start = System.currentTimeMillis()
            try {
                val res = app.get(url, headers = headers, timeout = timeoutSec)
                val ms = System.currentTimeMillis() - start
                if (res.code in 200..299) {
                    val body = try {
                        res.text
                    } finally {
                        res.closeQuietly()
                    }
                    MovieFoxSettingsDialog.reportServerStatus(sourceTag, "LIVE", true, res.code, ms, url)
                    return body
                }
                res.closeQuietly()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == retries - 1) {
                    logError(sourceTag, url, e, "attempt=${attempt + 1}")
                }
            }
            if (attempt < retries - 1) delay(350L)
        }
        return null
    }

    private suspend fun isValidManifestResponse(url: String, headers: Map<String, String>): Boolean {
        val isManifest = url.contains(".m3u8", true) || url.contains(".mpd", true) || url.contains("/playlist", true)
        if (!isManifest) return true
        return try {
            val res = app.get(url, headers = headers, timeout = 5L)
            if (res.code !in 200..299) {
                res.closeQuietly()
                return false
            }
            val head = try {
                res.text.trimStart().take(150)
            } finally {
                res.closeQuietly()
            }
            head.startsWith("#EXTM3U") || head.contains("<MPD", ignoreCase = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun isYouTubeOrBlockedStream(url: String, label: String = ""): Boolean {
        val lowUrl = url.lowercase()
        val lowLbl = label.lowercase()
        return mfIsBrokenContainerUrl(url) ||
            lowUrl.contains("googlevideo.com") ||
            lowUrl.contains("youtube.com") ||
            lowUrl.contains("youtu.be") ||
            lowLbl.contains("youtube")
    }

    private fun isJunkCandidateUrl(url: String): Boolean {
        val low = url.lowercase()
        return isAdUrl(url) ||
            isYouTubeOrBlockedStream(url) ||
            low.contains("gravatar.com") ||
            low.contains("/assets/img/") ||
            low.contains("/images/") ||
            low.contains(".svg") ||
            low.contains(".ico") ||
            low.contains(".woff") ||
            low.contains("&#038;") ||
            low.contains("&amp;#038;")
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
        "japanese", "jpn" -> "Japanese"
        "korean", "kor" -> "Korean"
        "chinese", "chi" -> "Chinese"
        "spanish", "spa" -> "Spanish"
        "original" -> "Original"
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

    private fun extractQualityTag(vararg texts: String?): String {
        val combined = texts.filterNot { it.isNullOrBlank() }.joinToString(" ")
        return when {
            Regex("""(?i)\b(2160p?|4k|uhd)\b""").containsMatchIn(combined) -> "4K"
            Regex("""(?i)\b1440p?\b""").containsMatchIn(combined) -> "1440p"
            Regex("""(?i)\b(1080p?|fhd)\b""").containsMatchIn(combined) -> "1080p"
            Regex("""(?i)\b720p?\b""").containsMatchIn(combined) -> "720p"
            Regex("""(?i)\b480p?\b""").containsMatchIn(combined) -> "480p"
            Regex("""(?i)\b360p?\b""").containsMatchIn(combined) -> "360p"
            else -> ""
        }
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

    private suspend fun fetchTmdbFallbackHomeItems(requestUrl: String, page: Int): List<SearchResponse> {
        val isTv = requestUrl.contains("/tv", true) || requestUrl.contains("/anime", true)
        val endpoint = when {
            requestUrl.contains("/trending", true) -> "$tmdbBaseUrl/trending/all/day?api_key=$tmdbApiKey&page=$page"
            requestUrl.contains("list=top_rated", true) -> "$tmdbBaseUrl/movie/top_rated?api_key=$tmdbApiKey&page=$page"
            requestUrl.contains("/anime", true) -> "$tmdbBaseUrl/discover/tv?api_key=$tmdbApiKey&with_genres=16&with_original_language=ja&sort_by=popularity.desc&page=$page"
            requestUrl.contains("lang=hi", true) -> "$tmdbBaseUrl/discover/movie?api_key=$tmdbApiKey&with_original_language=hi&sort_by=popularity.desc&page=$page"
            isTv -> "$tmdbBaseUrl/tv/popular?api_key=$tmdbApiKey&page=$page"
            else -> "$tmdbBaseUrl/movie/popular?api_key=$tmdbApiKey&page=$page"
        }

        val raw = safeGetText(endpoint, timeoutSec = 7L, sourceTag = "TMDB").orEmpty()
        val results = runCatching { JSONObject(raw).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optInt("id", 0)
            if (id <= 0) continue
            val mType = item.safeStr("media_type")
            val itemIsTv = when {
                mType == "tv" -> true
                mType == "movie" -> false
                else -> isTv
            }
            val title = item.safeStr("title").ifBlank { item.safeStr("name") }
            if (title.isBlank()) continue
            val pPath = item.safeStr("poster_path")
            val poster = if (pPath.startsWith("/")) "${MfRegistries.TMDB_IMAGE_W500}$pPath" else null
            val detailUrl = if (itemIsTv) "$mainUrl/tv/series-$id" else "$mainUrl/movie/movie-$id"
            if (itemIsTv) {
                out.add(newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, detailUrl, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val ctx = CloudStreamApp.context
        val timeoutSec = currentTimeoutSec()
        val targetUrl = if (page > 1) {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page"
        } else {
            request.data
        }

        val rawHtml = safeGetText(targetUrl, timeoutSec = timeoutSec, sourceTag = "moviefox").orEmpty()
        var items = if (rawHtml.isNotBlank()) {
            val document = Jsoup.parse(rawHtml, mainUrl)
            val cardSelector =
                "article[data-cf] a[aria-label][href], a[href*=/universes/], a[href*=/universe/], a[href*=/channel/]"
            val fallbackLinkSelector =
                "a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/], a[href*=/series/]"
            val elements = document.select(cardSelector).ifEmpty { document.select(fallbackLinkSelector) }
            elements.mapNotNull { toSearchResult(it) }.distinctBy { it.url }
        } else {
            fetchTmdbFallbackHomeItems(request.data, page)
        }

        if (ctx != null && MovieFoxSettingsDialog.isShuffleHomeCategories(ctx)) {
            items = items.shuffled()
        }

        val home = if (items.isNotEmpty()) listOf(HomePageList(request.name, items)) else emptyList()
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
        val html = safeGetText("$mainUrl/search?q=$encoded", timeoutSec = timeoutSec, sourceTag = "moviefox").orEmpty()
        if (html.isNotBlank()) {
            val document = Jsoup.parse(html, mainUrl)
            val elements = document.select(
                "article[data-cf] a[aria-label][href], a[href*=/universes/], a[href*=/universe/], a[href*=/channel/]"
            ).ifEmpty {
                document.select("a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/], a[href*=/series/]")
            }
            val parsed = elements.mapNotNull { toSearchResult(it) }.distinctBy { it.url }
            if (parsed.isNotEmpty()) return parsed
        }

        val tmdbRaw = safeGetText("$tmdbBaseUrl/search/multi?api_key=$tmdbApiKey&query=$encoded&page=1", timeoutSec = 7L, sourceTag = "TMDB").orEmpty()
        val results = runCatching { JSONObject(tmdbRaw).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val fallbackList = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val mType = item.safeStr("media_type")
            if (mType != "movie" && mType != "tv") continue
            val id = item.optInt("id", 0)
            if (id <= 0) continue
            val title = item.safeStr("title").ifBlank { item.safeStr("name") }
            if (title.isBlank()) continue
            val pPath = item.safeStr("poster_path")
            val poster = if (pPath.startsWith("/")) "${MfRegistries.TMDB_IMAGE_W500}$pPath" else null
            val isTv = mType == "tv"
            val detailUrl = if (isTv) "$mainUrl/tv/series-$id" else "$mainUrl/movie/movie-$id"
            if (isTv) {
                fallbackList.add(newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                fallbackList.add(newMovieSearchResponse(title, detailUrl, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return fallbackList
    }

    private fun extractSeriesId(url: String): String? {
        return Regex("-(?:movie|tv|dramas|anime|series)-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: Regex("/(?:movie|tv|dramas|anime|series)/(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
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

    override suspend fun load(url: String): LoadResponse {
        val timeoutSec = currentTimeoutSec()
        val responseText = safeGetText(url, timeoutSec = timeoutSec, sourceTag = "moviefox").orEmpty()
        val type = when {
            url.contains("/anime/") -> TvType.Anime
            url.contains("/dramas/") || url.contains("/channel/") -> TvType.AsianDrama
            url.contains("/tv/") || url.contains("/series/") || url.contains("/universe") -> TvType.TvSeries
            else -> TvType.Movie
        }

        if (responseText.isBlank()) {
            val id = extractSeriesId(url).orEmpty()
            val isTv = type != TvType.Movie
            val fallbackTitle = url.substringAfterLast("/").substringBefore("?").ifBlank { "Title" }
            if (id.isBlank()) {
                return if (!isTv) {
                    newMovieLoadResponse(fallbackTitle, url, TvType.Movie, encodeLoadData(url, fallbackTitle, null, null, false))
                } else {
                    newTvSeriesLoadResponse(fallbackTitle, url, type, emptyList())
                }
            }
            val tmdbPath = if (isTv) "tv/$id" else "movie/$id"
            val tmdbJsonStr = safeGetText("$tmdbBaseUrl/$tmdbPath?api_key=$tmdbApiKey&append_to_response=external_ids", timeoutSec = 7L, sourceTag = "TMDB").orEmpty()
            val obj = runCatching { JSONObject(tmdbJsonStr) }.getOrNull() ?: JSONObject()
            val title = obj.safeStr("title").ifBlank { obj.safeStr("name") }.ifBlank { fallbackTitle }
            val imdbId = obj.optJSONObject("external_ids")?.safeStr("imdb_id").orEmpty().ifBlank { obj.safeStr("imdb_id") }
            val pPath = obj.safeStr("poster_path")
            val poster = if (pPath.startsWith("/")) "${MfRegistries.TMDB_IMAGE_W500}$pPath" else null
            val plot = obj.safeStr("overview")

            if (!isTv) {
                return newMovieLoadResponse(title, url, TvType.Movie, encodeLoadData(url, title, id, imdbId, false)) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            val episodes = mutableListOf<Episode>()
            val seasonsArr = obj.optJSONArray("seasons")
            if (seasonsArr != null) {
                for (i in 0 until seasonsArr.length()) {
                    val sObj = seasonsArr.optJSONObject(i) ?: continue
                    val sNum = sObj.optInt("season_number", 0)
                    val epCount = sObj.optInt("episode_count", 0)
                    if (sNum <= 0 || epCount <= 0) continue
                    for (eNum in 1..epCount) {
                        val epData = encodeLoadData("${url.substringBefore("?")}?season=$sNum&episode=$eNum", title, id, imdbId, true, sNum, eNum)
                        episodes.add(newEpisode(epData) {
                            this.name = "Episode $eNum"
                            this.season = sNum
                            this.episode = eNum
                        })
                    }
                }
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val document = Jsoup.parse(responseText, mainUrl)
        val flightText = extractUnescapedFlightText(responseText)
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifEmpty { Regex("\"title\":\"([^\"]+)\"").find(flightText)?.groupValues?.get(1).orEmpty() }
            .ifEmpty { document.select("title").text().substringBefore("—").substringBefore("|").trim() }
            .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
            .trim()
        val tmdbId = Regex("\"tmdbId\":(\\d+)").find(flightText)?.groupValues?.get(1) ?: extractSeriesId(url)
        val imdbId = Regex("\"imdbId\":\"(tt\\d+)\"").find(flightText)?.groupValues?.get(1)
            ?: Regex("tt\\d{6,10}").find(responseText)?.value
        val posterPath: String? = Regex("\"posterPath\":\"(/[^\"\\\\]+)\"").find(flightText)?.groupValues?.get(1)
        val poster: String? = if (!posterPath.isNullOrBlank()) "${MfRegistries.TMDB_IMAGE_W500}$posterPath" else null
        val description = Regex("\"overview\":\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(flightText)?.groupValues?.get(1)
            ?.let { raw -> runCatching { JSONObject("{\"v\":\"$raw\"}").getString("v") }.getOrDefault(raw) }
            ?.trim().orEmpty()

        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, encodeLoadData(url, title, tmdbId, imdbId, false)) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val baseUrlWithoutQuery = url.substringBefore("?")
            val episodes = (1..3).flatMap { s ->
                (1..8).map { e ->
                    newEpisode(encodeLoadData("$baseUrlWithoutQuery?season=$s&episode=$e", title, tmdbId, imdbId, true, s, e)) {
                        this.name = "Episode $e"
                        this.season = s
                        this.episode = e
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    private data class LinkContext(
        val pageTitle: String,
        val searchQueryTitle: String,
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
            quality.contains("360") -> Qualities.P360.value
            else -> Qualities.P1080.value
        }
    }

    private fun isQualityAllowed(lc: LinkContext, qualityValue: Int, qualityStr: String?): Boolean {
        val anyToggleEnabled = lc.allow4k || lc.allow1080 || lc.allow720 || lc.allow480
        if (!anyToggleEnabled) return true
        val raw = qualityStr.orEmpty()
        val hasExplicitTag = Regex("""\b(2160|1440|1080|720|480|360|4k|uhd|fhd|hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (!hasExplicitTag) return true
        return when {
            qualityValue >= Qualities.P1440.value -> lc.allow4k
            qualityValue >= Qualities.P1080.value -> lc.allow1080
            qualityValue >= Qualities.P720.value -> lc.allow720
            else -> lc.allow480
        }
    }

    private fun normalizeUrl(rawUrl: String, baseHost: String = mainUrl): String {
        val trimmed = rawUrl.replace("\\/", "/").replace("&amp;", "&").trim()
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
            url.contains("propeller", true) ||
            url.contains("ardance.org", true) ||
            url.contains("fifa2026", true) ||
            url.contains("adsboosters", true)
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
            url.contains("vibuxer.com", true) ||
            url.contains("workers.dev", true) ||
            url.contains("/playlist", true) ||
            url.contains("/manifest", true) ||
            url.contains("/proxy", true)
    }

    private fun isM3u8Stream(url: String, typeHint: String): Boolean =
        isStreamOrHlsLink(url, ExtractorLinkType.M3U8, typeHint)

    private fun cleanSearchTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*\((?:19|20)\d{2}\)\s*"""), " ")
            .replace(Regex("""\s*\[[^\]]*\]\s*"""), " ")
            .replace(Regex("""(?i)\b(?:hindi\s*dubbed|dual\s*audio|multi\s*audio|full\s*movie|watch\s*online|web-?dl|bluray|hdrip)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeTitleKey(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.lowercase()
            .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .replace(Regex("""(?i)\b(?:season|part|chapter|vol|volume|s|ep|episode)\s*\d+\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun significantTitleWords(raw: String?): List<String> {
        val norm = normalizeTitleKey(raw)
        if (norm.isBlank()) return emptyList()
        return norm.split(" ")
            .map { it.trim() }
            .filter { it.length >= 2 && it !in titleStopWords }
    }

    private fun titleMatchesCandidate(candidateTitle: String?, targetTitle: String): Boolean {
        val cNorm = normalizeTitleKey(candidateTitle)
        val tNorm = normalizeTitleKey(targetTitle)
        if (tNorm.isBlank()) return true
        if (cNorm.isBlank()) return false
        if (cNorm == tNorm || cNorm.contains(tNorm) || tNorm.contains(cNorm)) return true
        val tWords = significantTitleWords(targetTitle)
        val cWords = significantTitleWords(candidateTitle).toSet()
        if (tWords.isEmpty()) return cNorm.isNotBlank()
        val matchedCount = tWords.count { it in cWords }
        return matchedCount >= minOf(2, tWords.size)
    }

    private fun buildSourceLabel(siteTag: String, server: String, info: String): String {
        val cleanSite = siteTag.trim()
        val cleanServer = server.replace(Regex("(?i)^null$"), "").trim()
        val audioTag = extractAudioSummary(info, cleanServer, cleanSite)
        val qualityTag = extractQualityTag(info, cleanServer)

        val brackets = mutableListOf<String>()
        if (cleanServer.isNotBlank() && !cleanServer.equals(cleanSite, ignoreCase = true)) {
            brackets.add(cleanServer)
        }
        if (audioTag.isNotBlank() && brackets.none { it.contains(audioTag, ignoreCase = true) }) {
            brackets.add(audioTag)
        }
        val bracketPart = if (brackets.isNotEmpty()) " [${brackets.joinToString(" • ")}]" else ""
        val tail = qualityTag.takeIf { it.isNotBlank() }.orEmpty()
        return "$cleanSite$bracketPart${if (tail.isNotBlank()) " $tail" else ""}".trim().take(80)
    }

    private fun extractCandidateMediaAndEmbedUrls(text: String, baseUrl: String): List<Pair<String, String>> {
        val found = LinkedHashMap<String, String>()
        Regex("""https?://[^\s"'<>\\)\]]+\.(?:m3u8|mpd|mp4|mkv|webm)(?:\?[^\s"'<>\\)\]]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m ->
                val u = normalizeUrl(m.value, baseUrl)
                if (!isJunkCandidateUrl(u)) found.putIfAbsent(u, "DIRECT")
            }

        val hostPattern = Regex(
            """https?://[^\s"'<>\\)\]]*(?:pixeldrain\.dev|pixeldrain|fsl2|fsl|fastdlserver|fastdl|hubcdn\.wiki|hubcloud|vcloud|mcloud|gdflix|gdlink|gdtot|gdrive|nexdrive|vegadrive|hubdrive|hblinks|linksmod|howblogs|driveleech|driveseed|filebee|filepress|fpgo|gofile|gpdl|\.r2\.dev|\.r2\.cloudflarestorage\.com|buzzserver|10gbps|voe\.|streamtape|stape\.|strtape|nxstreamhub|itsnitrox|vibuxer|wootly|videostr|streameeeeee|megaplay|vidwish|vidtube|vibeplayer|vivibebe|bibiemb|modiplay|iqsmartgames|filemoon|dood|mixdrop|upstream|vidhide|streamwish|luluvdo|speedostream|zephyrix|zephyrflick|workers\.dev)[^\s"'<>\\)\]]*""",
            RegexOption.IGNORE_CASE
        )
        hostPattern.findAll(text).forEach { m ->
            val u = normalizeUrl(m.value.trimEnd('.', ',', ';', ')', ']', '"', '\''), baseUrl)
            if (!isJunkCandidateUrl(u)) found.putIfAbsent(u, "HOST")
        }
        return found.entries.map { it.key to it.value }
    }

    private suspend fun unwrapIntermediateChain(
        startUrl: String,
        referer: String,
        sourceTag: String,
        maxHops: Int = 3
    ): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        val visited = HashSet<String>()
        queue.add(startUrl to 0)

        while (queue.isNotEmpty()) {
            val (curUrl, hop) = queue.removeFirst()
            if (hop > maxHops || !visited.add(curUrl) || isJunkCandidateUrl(curUrl)) continue

            val isDirect = Regex("""(?i)\.(?:m3u8|mpd|mp4|mkv|webm)(?:\?|$)""").containsMatchIn(curUrl)
            if (isDirect) {
                out.putIfAbsent(curUrl, "DIRECT")
                continue
            }
            if (pickCustomExtractor(curUrl) != null) {
                out.putIfAbsent(curUrl, "HOST")
            }
            if (hop < maxHops) {
                val html = safeGetText(curUrl, mapOf("Referer" to referer), timeoutSec = 4L, retries = 1, sourceTag = sourceTag).orEmpty()
                if (html.isNotBlank()) {
                    val unpacked = runCatching { JsUnpacker(html).unpack() }.getOrNull().orEmpty()
                    extractCandidateMediaAndEmbedUrls("$html\n$unpacked", curUrl).forEach { (u, kind) ->
                        out.putIfAbsent(u, kind)
                        if (u != curUrl && u !in visited) queue.add(u to (hop + 1))
                    }
                }
            }
        }
        return out.entries.map { it.key to it.value }
    }

    private suspend fun resolveIqsmartgamesDirect(
        siteTag: String,
        embedUrl: String,
        referer: String,
        audioTag: String,
        lc: LinkContext,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (!isExtOn("wootly")) return
        val html = safeGetText(embedUrl, mapOf("Referer" to referer), 6L, retries = 1, sourceTag = siteTag).orEmpty()
        if (html.isBlank()) return
        val m3u8 = Regex("""["'](https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        if (!m3u8.isNullOrBlank()) {
            emitStream(
                lc,
                buildSourceLabel(siteTag, "Iqsmartgames", "1080p $audioTag"),
                m3u8,
                m3u8.contains(".m3u8", true),
                "1080p $audioTag",
                mapOf("Referer" to embedUrl, "Origin" to "https://streams.iqsmartgames.com", "User-Agent" to effectiveUa),
                emitLink
            )
        }
    }

    private fun pickCustomExtractor(url: String): ExtractorApi? {
        val h = URI(url).host?.lowercase().orEmpty()
        if (h.isBlank() || h.contains("iqsmartgames")) return null
        return when {
            h.contains("pixeldrain.dev") && isExtOn("pixeldrain") -> PixeldrainDevExtractor()
            h.contains("pixeldrain") && isExtOn("pixeldrain") -> PixeldrainExtractor()
            h.contains("hubcloud") && isExtOn("hubcloud") -> HubCloud()
            h.contains("vcloud") && isExtOn("vcloud") -> VCloud()
            h.contains("gdflix") && isExtOn("gdflix") -> GDFlix()
            h.contains("gdlink") && isExtOn("gdlink") -> GDLink()
            h.contains("fastdlserver") && isExtOn("fastdlserver") -> fastdlserver()
            h.contains("hubdrive") && isExtOn("hubdrive") -> Hubdrive()
            h.contains("driveleech") && isExtOn("driveleech") -> Driveleech()
            h.contains("driveseed") && isExtOn("driveleech") -> Driveseed()
            h.contains("hblinks") && isExtOn("hubdrive") -> Hblinks()
            h.contains("howblogs") && isExtOn("hubdrive") -> Howblogs()
            h.contains("linksmod") && isExtOn("hubdrive") -> Linksmod()
            h.contains("gofile") && isExtOn("gofile") -> Gofile()
            h.contains("voe.") && isExtOn("voe") -> VoeExtractor()
            (h.contains("streamtape") || h.contains("stape.") || h.contains("strtape")) && isExtOn("streamtape") -> StreamTapeExtractor()
            h.contains("nxstreamhub") && isExtOn("nxstreamhub") -> NxStreamHubExtractor()
            h.contains("itsnitrox") && isExtOn("itsnitrox") -> NitroxExtractor()
            h.contains("vibuxer") && isExtOn("vibuxer") -> VibuxerExtractor()
            h.contains("wootly") && isExtOn("wootly") -> Wootly()
            h.contains("streameeeeee") && isExtOn("wootly") -> Streameeeeee()
            h.contains("videostr") && isExtOn("wootly") -> Videostr()
            h.contains("megaplay") && isExtOn("wootly") -> MegaPlay()
            h.contains("vidwish") && isExtOn("wootly") -> Vidwish()
            h.contains("vidtube") && isExtOn("wootly") -> Vidtube()
            h.contains("modiplay") && isExtOn("wootly") -> Modiplay()
            h.contains("vibeplayer") && isExtOn("wootly") -> VibePlayer()
            h.contains("vivibebe") && isExtOn("wootly") -> Vivibebe()
            h.contains("bibiemb") && isExtOn("wootly") -> Bibiemb()
            h.contains("nexdrive") && isExtOn("gokuhd") -> GokuHDExtractor()
            else -> null
        }
    }

    private suspend fun resolveWithAllExtractors(
        siteTag: String,
        embedUrl: String,
        referer: String,
        audioTag: String,
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (!embedUrl.startsWith("http") || isJunkCandidateUrl(embedUrl)) return
        if (embedUrl.contains("iqsmartgames", true)) {
            resolveIqsmartgamesDirect(siteTag, embedUrl, referer, audioTag, lc, emitLink)
            return
        }

        val wrappedCallback: (ExtractorLink) -> Unit = { ex ->
            if (!isJunkCandidateUrl(ex.url) && lc.seenUrls.add(ex.url)) {
                val rawHostName = ex.name.ifBlank { ex.source }
                val qTag = extractQualityTag(rawHostName, ex.url)
                val labeled = buildSourceLabel(siteTag, rawHostName, "$audioTag $qTag")
                val isStream = isStreamOrHlsLink(ex.url, ex.type)
                val outLink = ExtractorLink(
                    source = siteTag,
                    name = if (lc.streamOnly && isStream) "⚡ $labeled" else labeled,
                    url = ex.url,
                    referer = ex.referer.ifBlank { referer },
                    quality = if (ex.quality > 0) ex.quality else getQuality(rawHostName),
                    headers = ex.headers,
                    extractorData = ex.extractorData,
                    type = ex.type
                )
                emitLink(outLink, isStream)
            }
        }

        val customApi = pickCustomExtractor(embedUrl)
        var anyFromCustom = false
        if (customApi != null) {
            try {
                customApi.getUrl(embedUrl, referer, emitSub) { ex ->
                    anyFromCustom = true
                    wrappedCallback(ex)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(siteTag, embedUrl, e, "Custom extractor failed: ${customApi.name}")
            }
        }

        if (!anyFromCustom) {
            try {
                loadExtractor(embedUrl, referer, emitSub, wrappedCallback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(siteTag, embedUrl, e, "Extractor failed")
            }
        }
    }

    private suspend fun resolveCandidatesInParallel(
        siteTag: String,
        candidates: List<Pair<String, String>>,
        referer: String,
        audioTag: String,
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit,
        maxItems: Int = 8
    ) {
        val gate = Semaphore(5)
        supervisorScope {
            candidates.filterNot { isJunkCandidateUrl(it.first) }.take(maxItems).map { (u, kind) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        withTimeoutOrNull(7_500L) {
                            try {
                                if (kind == "DIRECT") {
                                    emitStream(
                                        lc,
                                        buildSourceLabel(siteTag, "Direct", audioTag),
                                        u,
                                        isM3u8Stream(u, ""),
                                        audioTag,
                                        mapOf("Referer" to referer, "User-Agent" to effectiveUa),
                                        emitLink
                                    )
                                } else {
                                    resolveWithAllExtractors(siteTag, u, referer, audioTag, lc, emitSub, emitLink)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logError(siteTag, u, e, "Candidate resolution failed")
                            }
                        }
                    }
                }
            }.awaitAll()
        }
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
        val candidateIds = listOfNotNull(tmdbId, urlId).distinct()
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
            searchQueryTitle = cleanSearchTitle(pageTitle).ifBlank { pageTitle },
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
            val maxConcurrency = (appCtx?.let { MovieFoxSettingsDialog.getParallelServers(it) } ?: 16).coerceIn(4, 36)
            val workerSemaphore = Semaphore(maxConcurrency)
            val lc = buildLinkContext(data, timeoutSec) ?: return false

            val emitLock = Any()
            val subLock = Any()
            var found = false

            val emitSub: (SubtitleFile) -> Unit = { sub ->
                synchronized(subLock) { subtitleCallback(sub) }
            }
            val emitLink: (ExtractorLink, Boolean) -> Unit = { link, isStream ->
                if (!isJunkCandidateUrl(link.url)) {
                    val allowedByMode = !(lc.downloadOnly && isStream)
                    val allowedByQuality = isQualityAllowed(lc, link.quality, link.name)
                    if (allowedByMode && (allowedByQuality || isStream) && link.url.startsWith("http")) {
                        synchronized(emitLock) {
                            found = true
                            callback(link)
                        }
                    }
                }
            }

            val orderedKeys = (appCtx?.let { MovieFoxSettingsDialog.getOrderedEnabledKeys(it) }
                ?: MovieFoxSettingsDialog.BASE_SERVERS.map { it.key }).distinct()

            supervisorScope {
                val tasks = mutableListOf<Deferred<Unit>>()
                orderedKeys.forEach { srvKey ->
                    tasks.add(async(Dispatchers.IO) {
                        workerSemaphore.withPermit {
                            withTimeoutOrNull(12_000L) {
                                try {
                                    runServerKey(srvKey, lc, isCasting, emitSub, emitLink)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logError(srvKey, lc.watchUrl, e, "Loadlinks server error")
                                }
                            }
                        }
                        Unit
                    })
                }
                tasks.awaitAll()
            }

            found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("loadLinks", data.take(120), e, "Fatal loadLinks crash")
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
            "pixeldrain_dev" -> fetchPixeldrainServer(lc, emitSub, emitLink)
            "fsl_fastdl" -> fetchHostGroup("FSL / FastDL", listOf("fsl2", "fsl", "fastdlserver", "fastdl", "hubcdn.wiki", ".r2.dev", ".r2.cloudflarestorage.com", "buzzserver", "10gbps", "gpdl"), lc, emitSub, emitLink)
            "hubcloud_vcloud" -> fetchHostGroup("HubCloud / VCloud", listOf("hubcloud", "vcloud", "mcloud", "workers.dev"), lc, emitSub, emitLink)
            "gdflix_drive" -> fetchHostGroup("GDFlix / Drive", listOf("gdflix", "gdlink", "gdtot", "gdrive", "nexdrive", "vegadrive", "hubdrive", "hblinks", "linksmod", "howblogs", "driveleech", "driveseed", "filebee", "filepress", "fpgo"), lc, emitSub, emitLink)
            "gofile" -> fetchHostGroup("Gofile", listOf("gofile"), lc, emitSub, emitLink)
            "voe_streamtape" -> fetchHostGroup("Voe / StreamTape", listOf("voe.", "streamtape", "stape.", "strtape"), lc, emitSub, emitLink)
            "nxstreamhub_nitrox" -> fetchHostGroup("NxStreamHub / Nitrox", listOf("nxstreamhub", "itsnitrox", "vibuxer"), lc, emitSub, emitLink)
            "wootly_embeds" -> fetchHostGroup("Wootly / Embeds", listOf("wootly", "videostr", "streameeeeee", "megaplay", "vidwish", "vidtube", "vibeplayer", "vivibebe", "bibiemb", "modiplay", "iqsmartgames", "filemoon", "dood", "mixdrop", "upstream", "vidhide", "streamwish", "luluvdo", "speedostream", "zephyrix", "zephyrflick"), lc, emitSub, emitLink)
            "prmovies" -> fetchPrmoviesFull(lc, emitSub, emitLink)
            else -> fetchCustomCs3Server(srvKey, lc, isCasting, emitSub, emitLink)
        }
    }

    private suspend fun fetchPixeldrainServer(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeEncode(lc.searchQueryTitle)
        val html = safeGetText("$activePrmoviesBase/?s=$q", mapOf("Referer" to "$activePrmoviesBase/"), 5L, retries = 1, sourceTag = "pixeldrain_dev").orEmpty()
        if (html.isBlank()) return
        val matches = extractCandidateMediaAndEmbedUrls(html, activePrmoviesBase)
            .filter { it.first.contains("pixeldrain", true) }
            .map { (u, _) ->
                val pid = u.substringBefore("?").trimEnd('/').substringAfterLast("/")
                val devUrl = if (pid.isNotBlank() && !u.contains("/api/file/")) "$pixeldrainDevUrl/u/$pid" else u
                devUrl to "HOST"
            }
        if (matches.isNotEmpty()) {
            resolveCandidatesInParallel("Pixeldrain Dev", matches, activePrmoviesBase, "", lc, emitSub, emitLink)
        }
    }

    private suspend fun fetchHostGroup(
        siteTag: String,
        hostTokens: List<String>,
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeEncode(lc.searchQueryTitle)
        val html = safeGetText("$activePrmoviesBase/?s=$q", mapOf("Referer" to "$activePrmoviesBase/"), 5L, retries = 1, sourceTag = siteTag).orEmpty()
        if (html.isBlank()) return
        val filtered = extractCandidateMediaAndEmbedUrls(html, activePrmoviesBase)
            .filter { (u, _) -> hostTokens.any { tok -> u.contains(tok, ignoreCase = true) } }
        if (filtered.isNotEmpty()) {
            resolveCandidatesInParallel(siteTag, filtered, activePrmoviesBase, "", lc, emitSub, emitLink)
        }
    }

    private suspend fun fetchPrmoviesFull(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = lc.searchQueryTitle.trim().replace(Regex("\\s+"), "+")
        val candidates = buildDomainCandidates(prmoviesMirrors, "prmovies", "Prmovies", preferredFirst = activePrmoviesBase)

        tryMirrorDomains("prmovies", candidates, perDomainTimeoutMs = 7_500L) { dom ->
            val searchUrl = "$dom/?s=$q"
            val searchHtml = safeGetText(searchUrl, mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "prmovies").orEmpty()
            if (searchHtml.isBlank()) return@tryMirrorDomains null
            activePrmoviesBase = dom

            val searchDoc = Jsoup.parse(searchHtml, dom)
            val detailUrls = searchDoc.select(".ml-item a.ml-mask[href], article a[href], h2 a[href]")
                .filter { a ->
                    val href = a.attr("href").trim()
                    val t = (a.attr("oldtitle").ifBlank { a.attr("title") }.ifBlank { a.text() }).trim()
                    href.startsWith("http") && !href.contains("/category/") && t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                }
                .map { normalizeUrl(it.attr("href"), dom) }
                .distinct()
                .take(2)

            if (detailUrls.isEmpty()) return@tryMirrorDomains null

            var emittedForMirror = false
            for (detailUrl in detailUrls) {
                val detailHtml = safeGetText(detailUrl, mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "prmovies").orEmpty()
                if (detailHtml.isBlank()) continue
                val pairs = extractCandidateMediaAndEmbedUrls(detailHtml, detailUrl)
                if (pairs.isNotEmpty()) {
                    resolveCandidatesInParallel("Prmovies", pairs, detailUrl, "", lc, emitSub, emitLink, maxItems = 8)
                    emittedForMirror = true
                }
            }
            if (emittedForMirror) true else null
        }
    }

    private suspend fun emitStream(
        lc: LinkContext,
        serverName: String,
        streamUrl: String,
        isHls: Boolean,
        qualityStr: String? = null,
        customHeaders: Map<String, String> = lc.baseHeaders,
        emitLink: (ExtractorLink, Boolean) -> Unit,
        forceType: ExtractorLinkType? = null
    ) {
        if (streamUrl.isBlank() || !streamUrl.startsWith("http") || isJunkCandidateUrl(streamUrl) || serverName.contains("youtube", true)) return
        if (!lc.seenUrls.add(streamUrl)) return
        if (lc.downloadOnly && isHls) return

        val resolvedQuality = getQuality(qualityStr ?: serverName)
        if (!isQualityAllowed(lc, resolvedQuality, qualityStr ?: serverName)) return
        if (isHls && !isValidManifestResponse(streamUrl, customHeaders)) return

        val audioSummary = extractAudioSummary(qualityStr, serverName, lc.pageTitle)
        val qTag = extractQualityTag(qualityStr, serverName)
        val baseLabel = if (serverName.contains("[") && serverName.contains("]")) {
            serverName
        } else {
            val cleanBase = serverName.trim()
            if (audioSummary.isNotBlank() && !cleanBase.contains(audioSummary, ignoreCase = true)) {
                "$cleanBase [$audioSummary]"
            } else {
                cleanBase
            }
        }
        val withQuality = if (qTag.isNotBlank() && !baseLabel.contains(qTag, ignoreCase = true)) {
            "$baseLabel $qTag"
        } else {
            baseLabel
        }

        val linkType = forceType ?: when {
            streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
            isHls || streamUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        val label = if (lc.streamOnly && isHls) "⚡ $withQuality" else withQuality
        val link = newExtractorLink(
            source = serverName.substringBefore("[").trim().ifBlank { serverName },
            name = label,
            url = streamUrl,
            type = linkType
        ) {
            this.referer = customHeaders["Referer"] ?: lc.watchUrl
            this.quality = resolvedQuality
            this.headers = customHeaders
        }
        emitLink(link, isHls)
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

        val cleanArchiveUrl = baseProbe.substringBefore("?")
        if (cleanArchiveUrl.endsWith(".cs3", true) || cleanArchiveUrl.endsWith(".sky", true)) {
            val lock = cs3DownloadLocks.getOrPut(cleanArchiveUrl) { Mutex() }
            val plugins = lock.withPermitCompat {
                cs3PluginCache[cleanArchiveUrl]?.let { return@withPermitCompat it }
                val loadedList = try {
                    val dir = File(ctx.codeCacheDir, "mf_cs3_plugins").apply { mkdirs() }
                    val hash = MessageDigest.getInstance("MD5").digest(cleanArchiveUrl.toByteArray()).joinToString("") { "%02x".format(it) }
                    val ext = if (cleanArchiveUrl.endsWith(".sky", true)) "sky" else "cs3"
                    val file = File(dir, "$hash.$ext")
                    if (!file.exists() || file.length() == 0L) {
                        val res = runCatching {
                            app.get(cleanArchiveUrl, headers = mapOf("User-Agent" to effectiveUa, "Accept" to "application/octet-stream, */*"), timeout = 10L)
                        }.getOrNull()
                        if (res != null) {
                            if (res.code in 200..299) {
                                val bytes = try { res.body.bytes() } finally { res.closeQuietly() }
                                if (bytes.isNotEmpty()) file.writeBytes(bytes)
                            } else {
                                res.closeQuietly()
                            }
                        }
                    }
                    if (!file.exists() || file.length() == 0L) emptyList()
                    else {
                        val clsNames = mutableListOf<String>()
                        runCatching {
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
                        }
                        if (clsNames.isEmpty()) emptyList()
                        else {
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
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError("CS3Plugin", cleanArchiveUrl, e, "DexClassLoader load failed")
                    emptyList()
                }
                if (loadedList.isNotEmpty()) cs3PluginCache[cleanArchiveUrl] = loadedList
                loadedList
            }

            if (plugins.isEmpty()) return
            val targetPlugin = plugins.firstOrNull { p ->
                p.name.equals(cs3Srv.name, ignoreCase = true) ||
                    p.javaClass.simpleName.contains(cs3Srv.name.replace(" ", ""), ignoreCase = true)
            } ?: plugins.first()

            withContext(Dispatchers.IO) {
                if (lc.searchQueryTitle.isBlank()) return@withContext
                try {
                    val hits = runCatching { targetPlugin.search(lc.searchQueryTitle) }.getOrNull().orEmpty()
                    val hit = hits.firstOrNull { titleMatchesCandidate(it.name, lc.pageTitle) } ?: hits.firstOrNull() ?: return@withContext
                    val loadRes = runCatching { targetPlugin.load(hit.url) }.getOrNull() ?: return@withContext
                    val targetData = if (loadRes is TvSeriesLoadResponse) {
                        val sN = lc.season.toIntOrNull() ?: 1
                        val eN = lc.episode.toIntOrNull() ?: 1
                        loadRes.episodes.firstOrNull { it.season == sN && it.episode == eN }?.data ?: loadRes.episodes.firstOrNull()?.data
                    } else (loadRes as? MovieLoadResponse)?.dataUrl ?: hit.url
                    if (targetData != null) {
                        targetPlugin.loadLinks(targetData, isCasting, emitSub) { ex ->
                            if (!isJunkCandidateUrl(ex.url) && lc.seenUrls.add(ex.url) && isQualityAllowed(lc, ex.quality, ex.name)) {
                                emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError("CS3•${targetPlugin.name}", targetPlugin.mainUrl, e, "Plugin execution failed")
                }
            }
        }
    }

    private suspend inline fun <T> Mutex.withPermitCompat(action: () -> T): T {
        lock()
        return try {
            action()
        } finally {
            unlock()
        }
    }
}

