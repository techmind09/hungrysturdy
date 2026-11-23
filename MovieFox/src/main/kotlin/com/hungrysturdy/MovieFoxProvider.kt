package com.hungrysturdy

import android.content.Context
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uSearchPage(
    val ok: Boolean? = false,
    val items: List<P4uSearchItem> = emptyList(),
    val found: Int? = 0,
    val page: Int? = 1,
    val pages: Int? = 1
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uSearchItem(
    val id: Int? = null,
    val title: String? = null,
    val imdb_id: String? = null,
    val year: String? = null,
    val overview: String? = null,
    val poster_url: String? = null,
    val backdrop: String? = null,
    val media_type: String? = null,
    val watch: String? = null,
    val quality: String? = null,
    val age_rating: String? = null,
    val langs: List<String>? = null,
    val genre_names: List<String>? = null,
    val runtime_minutes: Int? = null,
    val series_badge: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uPlayPayload(
    val ok: Boolean? = false,
    val item: P4uPlayItem? = null,
    val streams: List<P4uPlayStream> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uPlayItem(
    val viewkey: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val release_date: String? = null,
    val runtime_minutes: Int? = null,
    val poster_url: String? = null,
    val cover_url: String? = null,
    val tags: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val cast: List<P4uPerson>? = null,
    val directors: List<P4uPerson>? = null,
    val trailer_key: String? = null,
    val languages: List<P4uAudioLang>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uPerson(
    val name: String? = null,
    val character: String? = null,
    val profile: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uPlayStream(
    val id: Long? = null,
    val label: String? = null,
    val m3u8_url: String? = null,
    val m3u8_path: String? = null,
    val domain_name: String? = null,
    val languages: List<P4uAudioLang>? = null,
    val qualities: List<P4uStreamQuality>? = null,
    val print_name: String? = null,
    val has_imax: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uAudioLang(
    val code: String? = null,
    val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uStreamQuality(
    val slug: String? = null,
    val label: String? = null,
    val height: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uSeriesPayload(
    val key: String? = null,
    val imdb_id: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val seasons: Map<String, P4uSeasonInfo> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uSeasonInfo(
    val number: Int? = null,
    val name: String? = null,
    val year: Int? = null,
    val completed: Boolean? = null,
    val poster: String? = null,
    val episodes: Map<String, P4uEpisodeInfo> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class P4uEpisodeInfo(
    val number: Int? = null,
    val title: String? = null,
    val runtime: Int? = null,
    val overview: String? = null,
    val still: String? = null,
    val hls: String? = null,
    val languages: List<P4uAudioLang>? = null,
    val qualities: List<P4uStreamQuality>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AwZephyrixVideo(
    @JsonProperty("videoSource") val videoSource: String? = null,
    @JsonProperty("securedLink") val securedLink: String? = null
)

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
            if (dispatcher.maxRequestsPerHost < 60) dispatcher.maxRequestsPerHost = 60
            if (dispatcher.maxRequests < 240) dispatcher.maxRequests = 240
        }
    }

    private val logTag = "MovieFoxProvider"
    private val effectiveUa: String
        get() = MovieFoxSettingsDialog.CF_BYPASS_UA

    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"

    private val cfMutexMap = ConcurrentHashMap<String, Mutex>()
    private val cfKillerMap = ConcurrentHashMap<String, CloudflareKiller>()

    // Server 1: Prmovies Mirrors
    private val prmoviesMirrors = listOf(
        "https://prmovies.coupons",
        "https://prmovies.beer",
        "https://prmovies.com.cv",
        "https://prmovies.kids"
    )
    @Volatile
    private var activePrmoviesBase = "https://prmovies.coupons"

    // Server 2: DramaVerse
    private val dramaVerseBase = "https://mydverse.com"
    private val xcloudReferer = "https://xcloud.tattoo/"
    private val xcloudStreamReferer = "https://stream.xcloud1.lol/"

    // Server 3: HindiDrama
    private val hindiDramaBase = "https://hindidrama.net"

    // Server 4: KatDrama
    private val katDramaBase = "https://katdrama.org"

    // Server 5: Sflix
    private val sflixDomains = listOf(
        "https://sflix.film",
        "https://h5-api.aoneroom.com"
    )
    private val sflixPlayerUrl = "https://mzfi.me"
    private val sflixSecretB64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
    private val sflixCookies =
        "sflix_i18n_lang=en; _ga=GA1.1.622965780.1794742147; " +
            "sflix_token=%22eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjE5NjY4NTU1NTcyNzgwNjIyNzYsImF0cCI6MywiZXh0IjoiMTc5NDc0MjE0NyIsImV4cCI6MTgwMjUxODE0NywiaWF0IjoxNzk0NzQxODQ3fQ.8aOIckQOSKyISQdl85SAJvuMlhXfWodHmcPOIvlg0pM%22"

    // Server 6: Play4u
    @Volatile
    private var activePlay4uBase = "https://play4u.org"
    private val p4uSealPrefix = "pa!~"
    private val p4uDefaultSealKey = "pa4u-stream-seal-v1"
    private val p4uSealKeyRegex = Regex("""window\.__PA_SEAL_KEY__\s*=\s*"([^"]*)"""")
    private val p4uUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36"

    // Server 7: AnimeWorldIndia
    @Volatile
    private var activeAnimeWorldBase = "https://watchanimeworld.top"
    private val awBrowserUa =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // Server 8: TheMoviesFlix
    private val tmfFallbackDomains = listOf(
        "https://one.1moviesflix.lol",
        "https://themoviesflixhq.com",
        "https://moviesflixhq.com"
    )
    @Volatile
    private var activeTmfBase = "https://one.1moviesflix.lol"
    private val tmfDesktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val tmfDriveCache = ConcurrentHashMap<String, Pair<Long, TmfDriveData?>>()
    private val tmfDriveMutex = Mutex()
    private val tmfPackRegex = Regex("""(?i)\b(zip|rar|7z|batch)\b""")
    private val tmfFilePressIdRegex = Regex("""/file/([a-f0-9]{16,40})""")

    // Server 9: VegaMovies (fast domains first)
    private val vegaFallbackDomains = listOf(
        "https://vegamovies.ps",
        "https://vegamovies.pm",
        "https://vegamovies.rsvp"
    )
    @Volatile
    private var activeVegaBase = "https://vegamovies.ps"

    private val firebaseDomainsCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var lastFirebaseFetchMs = 0L
    private val firebaseMutex = Mutex()

    private val random = SecureRandom()
    private val sflixDeviceId: String by lazy {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes.joinToString("") { String.format("%02x", it) }
    }

    private val titleStopWords = setOf(
        "the", "a", "an", "of", "in", "on", "at", "to", "for", "and", "or", "is", "are", "with", "by"
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
        "$mainUrl/movies?list=now_playing" to "Now Playing",
        "$mainUrl/movies?list=upcoming" to "Upcoming Movies",
        "$mainUrl/tv?list=popular" to "Popular TV Shows",
        "$mainUrl/tv?list=top_rated" to "Top Rated TV Shows",
        "$mainUrl/tv?list=airing_today" to "Airing Today TV",
        "$mainUrl/movies?lang=hi" to "Indian Movies (Hindi)",
        "$mainUrl/movies?region=IN" to "Bollywood",
        "$mainUrl/movies?lang=ta,te,ml,kn" to "South Indian",
        "$mainUrl/movies?lang=en" to "Hollywood",
        "$mainUrl/anime" to "Anime",
        "$mainUrl/dramas/korean" to "K-Drama",
        "$mainUrl/dramas/chinese" to "C-Drama",
        "$mainUrl/movies?genre=action" to "Action & Thriller",
        "$mainUrl/movies?genre=horror" to "Horror & Mystery",
        "$mainUrl/movies?genre=romance" to "Romance"
    )

    private val ignoredLinkTexts = setOf(
        "where to watch", "more info", "watch now", "view all",
        "play", "play now", "trailer", "all options", "go home", "search"
    )

    private fun ctxOrNull(): Context? =
        MovieFoxSettingsDialog.currentContext() ?: CloudStreamApp.context

    private fun mutexFor(url: String): Mutex {
        val host = runCatching { URI(url).host ?: mainUrl }.getOrDefault(mainUrl)
        return cfMutexMap.getOrPut(host) { Mutex() }
    }

    private fun killerFor(url: String): CloudflareKiller {
        val host = runCatching { URI(url).host ?: mainUrl }.getOrDefault(mainUrl)
        return cfKillerMap.getOrPut(host) { CloudflareKiller() }
    }

    private fun logError(source: String, url: String, err: Throwable?, extra: String = "") {
        if (err is CancellationException) return
        Log.e(logTag, "[$source] $extra | $url -> ${err?.message.orEmpty()}", err)
        MovieFoxSettingsDialog.logSiteError(source, url, err, extra)
    }

    private fun isExtOn(key: String): Boolean =
        ctxOrNull()?.let { MovieFoxSettingsDialog.isExtractorEnabled(it, key) } ?: true

    private fun fastTimeoutSec(cap: Long = 5L): Long {
        val ctx = ctxOrNull() ?: return cap
        return MovieFoxSettingsDialog.getTimeoutSeconds(ctx).toLong().coerceIn(3L, cap)
    }

    private fun directHeaders(url: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val savedCookie = MovieFoxSettingsDialog.getCookieForDomain(ctxOrNull(), url)
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
        timeoutSec: Long = fastTimeoutSec(5L),
        sourceTag: String = "HTTP",
        useCloudflareBypass: Boolean = false
    ): String? {
        val headers = directHeaders(url, extraHeaders)
        val start = System.currentTimeMillis()
        try {
            var res = app.get(url, headers = headers, timeout = timeoutSec)
            if (useCloudflareBypass && res.code in listOf(403, 503)) {
                res = mutexFor(url).withLock {
                    app.get(url, headers = headers, interceptor = killerFor(url), timeout = timeoutSec)
                }
            }
            val ms = System.currentTimeMillis() - start
            if (res.code in 200..299) {
                val body = res.text
                MovieFoxSettingsDialog.reportServerStatus(sourceTag, "LIVE", true, res.code, ms, url)
                return body
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(sourceTag, url, e, "fast_get")
        }
        return null
    }

    private suspend fun getFirebaseDomain(key: String): String? {
        val now = System.currentTimeMillis()
        if (firebaseDomainsCache.isEmpty() || now - lastFirebaseFetchMs > 10 * 60 * 1000L) {
            firebaseMutex.withLock {
                val curNow = System.currentTimeMillis()
                if (firebaseDomainsCache.isEmpty() || curNow - lastFirebaseFetchMs > 10 * 60 * 1000L) {
                    withTimeoutOrNull(3_500L) {
                        runCatching {
                            val raw = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json", timeout = 3L).text
                            val obj = JSONObject(raw)
                            obj.keys().forEach { k ->
                                val v = obj.optString(k, "").trim().trim('"', '\'').trim().trimEnd('/')
                                if (v.isNotBlank()) firebaseDomainsCache[k] = v
                            }
                            lastFirebaseFetchMs = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
        return firebaseDomainsCache[key] ?: firebaseDomainsCache["${key}_url"] ?: firebaseDomainsCache["${key}_domain"]
    }

    private fun isJunkCandidateUrl(url: String): Boolean {
        val low = url.lowercase()
        return low.contains("backhoemoya") ||
            low.contains("/cx/") ||
            low.contains("exoclick") ||
            low.contains("popads") ||
            low.contains("propeller") ||
            low.contains("ardance.org") ||
            low.contains("fifa2026") ||
            low.contains("adsboosters") ||
            low.contains("googlevideo.com") ||
            low.contains("youtube.com") ||
            low.contains("youtu.be") ||
            low.contains("gravatar.com") ||
            low.contains("/assets/img/") ||
            low.contains("/images/") ||
            low.contains(".svg") ||
            low.contains(".ico") ||
            low.contains(".woff")
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
        val low = requestUrl.lowercase()
        val isTv = low.contains("/tv") || low.contains("/anime") || low.contains("/dramas/")
        val endpoint = when {
            low.contains("/trending") -> "$tmdbBaseUrl/trending/all/day?api_key=$tmdbApiKey&page=$page"
            low.contains("list=top_rated") && isTv -> "$tmdbBaseUrl/tv/top_rated?api_key=$tmdbApiKey&page=$page"
            low.contains("list=top_rated") -> "$tmdbBaseUrl/movie/top_rated?api_key=$tmdbApiKey&page=$page"
            low.contains("list=now_playing") -> "$tmdbBaseUrl/movie/now_playing?api_key=$tmdbApiKey&page=$page"
            low.contains("list=upcoming") -> "$tmdbBaseUrl/movie/upcoming?api_key=$tmdbApiKey&page=$page"
            low.contains("list=airing_today") -> "$tmdbBaseUrl/tv/airing_today?api_key=$tmdbApiKey&page=$page"
            low.contains("/anime") -> "$tmdbBaseUrl/discover/tv?api_key=$tmdbApiKey&with_genres=16&with_original_language=ja&sort_by=popularity.desc&page=$page"
            low.contains("/dramas/korean") -> "$tmdbBaseUrl/discover/tv?api_key=$tmdbApiKey&with_original_language=ko&sort_by=popularity.desc&page=$page"
            low.contains("/dramas/chinese") -> "$tmdbBaseUrl/discover/tv?api_key=$tmdbApiKey&with_original_language=zh&sort_by=popularity.desc&page=$page"
            low.contains("lang=hi") -> "$tmdbBaseUrl/discover/movie?api_key=$tmdbApiKey&with_original_language=hi&sort_by=popularity.desc&page=$page"
            low.contains("region=in") -> "$tmdbBaseUrl/discover/movie?api_key=$tmdbApiKey&region=IN&with_original_language=hi&sort_by=popularity.desc&page=$page"
            low.contains("lang=ta,te,ml,kn") -> "$tmdbBaseUrl/discover/movie?api_key=$tmdbApiKey&with_original_language=ta|te|ml|kn&sort_by=popularity.desc&page=$page"
            low.contains("lang=en") -> "$tmdbBaseUrl/discover/movie?api_key=$tmdbApiKey&with_original_language=en&sort_by=popularity.desc&page=$page"
            low.contains("genre=action") -> "$tmdbBaseUrl/discover/movie?api_key=$tmdbApiKey&with_genres=28,53&sort_by=popularity.desc&page=$page"
            low.contains("genre=horror") -> "$tmdbBaseUrl/discover/movie?api_key=$tmdbApiKey&with_genres=27,9648&sort_by=popularity.desc&page=$page"
            low.contains("genre=romance") -> "$tmdbBaseUrl/discover/movie?api_key=$tmdbApiKey&with_genres=10749&sort_by=popularity.desc&page=$page"
            isTv -> "$tmdbBaseUrl/tv/popular?api_key=$tmdbApiKey&page=$page"
            else -> "$tmdbBaseUrl/movie/popular?api_key=$tmdbApiKey&page=$page"
        }

        val raw = safeGetText(endpoint, timeoutSec = 5L, sourceTag = "TMDB").orEmpty()
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
            val poster = if (pPath.startsWith("/")) "$tmdbImageBase$pPath" else null
            val detailUrl = when {
                low.contains("/anime") -> "$mainUrl/anime/anime-$id"
                low.contains("/dramas/") -> "$mainUrl/dramas/drama-$id"
                itemIsTv -> "$mainUrl/tv/series-$id"
                else -> "$mainUrl/movie/movie-$id"
            }
            val tvType = when {
                low.contains("/anime") -> TvType.Anime
                low.contains("/dramas/") -> TvType.AsianDrama
                itemIsTv -> TvType.TvSeries
                else -> TvType.Movie
            }
            if (tvType != TvType.Movie) {
                out.add(newTvSeriesSearchResponse(title, detailUrl, tvType) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, detailUrl, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val ctx = ctxOrNull()
        val timeoutSec = fastTimeoutSec(6L)
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
            emptyList()
        }

        if (items.isEmpty()) {
            items = fetchTmdbFallbackHomeItems(request.data, page)
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
        val timeoutSec = fastTimeoutSec(6L)
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

        val tmdbRaw = safeGetText("$tmdbBaseUrl/search/multi?api_key=$tmdbApiKey&query=$encoded&page=1", timeoutSec = 5L, sourceTag = "TMDB").orEmpty()
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
            val poster = if (pPath.startsWith("/")) "$tmdbImageBase$pPath" else null
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
        val timeoutSec = fastTimeoutSec(6L)
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
            val tmdbJsonStr = safeGetText("$tmdbBaseUrl/$tmdbPath?api_key=$tmdbApiKey&append_to_response=external_ids", timeoutSec = 5L, sourceTag = "TMDB").orEmpty()
            val obj = runCatching { JSONObject(tmdbJsonStr) }.getOrNull() ?: JSONObject()
            val title = obj.safeStr("title").ifBlank { obj.safeStr("name") }.ifBlank { fallbackTitle }
            val imdbId = obj.optJSONObject("external_ids")?.safeStr("imdb_id").orEmpty().ifBlank { obj.safeStr("imdb_id") }
            val pPath = obj.safeStr("poster_path")
            val poster = if (pPath.startsWith("/")) "$tmdbImageBase$pPath" else null
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
        val poster: String? = if (!posterPath.isNullOrBlank()) "$tmdbImageBase$posterPath" else null
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
                (1..16).map { e ->
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
        val season: Int,
        val episode: Int,
        val imdbId: String,
        val candidateIds: List<String>,
        val watchUrl: String,
        val baseHeaders: Map<String, String>,
        val reqTimeoutSec: Long,
        val downloadOnly: Boolean,
        val allow4k: Boolean,
        val allow1080: Boolean,
        val allow720: Boolean,
        val allow480: Boolean,
        val seenUrls: MutableSet<String>
    )

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

    private fun titleMatchesCandidate(candidateTitle: String?, targetTitle: String): Boolean {
        val cNorm = normalizeTitleKey(candidateTitle)
        val tNorm = normalizeTitleKey(targetTitle)
        if (tNorm.isBlank()) return true
        if (cNorm.isBlank()) return false
        if (cNorm == tNorm || cNorm.contains(tNorm) || tNorm.contains(cNorm)) return true
        val tWords = tNorm.split(" ").filter { it.length >= 2 && it !in titleStopWords }
        val cWords = cNorm.split(" ").filter { it.length >= 2 && it !in titleStopWords }.toSet()
        if (tWords.isEmpty()) return cNorm.isNotBlank()
        return tWords.count { it in cWords } >= minOf(2, tWords.size)
    }

    private fun normalizeUrl(rawUrl: String, baseHost: String): String {
        val trimmed = rawUrl.replace("\\/", "/").replace("&amp;", "&").trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "${baseHost.trimEnd('/')}/${trimmed.trimStart('/')}"
        }
    }

    private fun isQualityAllowed(lc: LinkContext, qualityValue: Int, label: String): Boolean {
        if (!lc.allow4k && !lc.allow1080 && !lc.allow720 && !lc.allow480) return true
        val hasTag = Regex("""\b(2160|1440|1080|720|480|360|4k|uhd|fhd|hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(label)
        if (!hasTag) return true
        return when {
            qualityValue >= Qualities.P1440.value -> lc.allow4k
            qualityValue >= Qualities.P1080.value -> lc.allow1080
            qualityValue >= Qualities.P720.value -> lc.allow720
            else -> lc.allow480
        }
    }

    private fun pickCustomExtractor(url: String): ExtractorApi? {
        val h = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
        if (h.isBlank()) return null
        return when {
            h.contains("pixeldrain.dev") && isExtOn("pixeldrain") -> PixeldrainDevExtractor()
            h.contains("pixeldrain") && isExtOn("pixeldrain") -> PixeldrainExtractor()
            h.contains("hubcloud") && isExtOn("hubcloud") -> KatHubCloudExtractor()
            h.contains("gamerxyt") && isExtOn("hubcloud") -> GamerXYTExtractor()
            h.contains("gdflix") && isExtOn("gdflix") -> KatGDFlixExtractor()
            h.contains("gdlink") && isExtOn("gdflix") -> GDLink()
            h.contains("fastdlserver") && isExtOn("fastdlserver") -> KatFastDlServer()
            h.contains("fuckingfast") && isExtOn("buzz_hb_go") -> KatFuckingFastExtractor()
            h.contains("hbplay.pages.dev") && isExtOn("buzz_hb_go") -> KatHBPlayExtractor()
            h.contains("goflix.sbs") && isExtOn("buzz_hb_go") -> KatGoFlixExtractor()
            h.contains("voe.") && isExtOn("voe") -> VoeExtractor()
            (h.contains("streamtape") || h.contains("stape.") || h.contains("strtape") || h.contains("shavetape")) && isExtOn("streamtape") -> StreamTapeExtractor()
            h.contains("nxstreamhub") && isExtOn("nxstreamhub") -> NxStreamHubExtractor()
            h.contains("itsnitrox") && isExtOn("itsnitrox") -> NitroxExtractor()
            h.contains("vibuxer") && isExtOn("vibuxer") -> VibuxerExtractor()
            h.contains("vidprime") && isExtOn("jwp_hosts") -> VidPrimeExtractor()
            (h.contains("luluvid") || h.contains("lulustream") || h.contains("luluvdo")) && isExtOn("jwp_embeds") -> LuluVidExtractor()
            h.contains("vidara") && isExtOn("jwp_embeds") -> VidaraExtractor()
            h.contains("rabbitstream") && isExtOn("jwp_embeds") -> RabbitstreamExtractor()
            h.contains("upcloud") && isExtOn("jwp_embeds") -> UpCloudExtractor()
            h.contains("megacloud") && isExtOn("jwp_embeds") -> MegaCloudExtractor()
            h.contains("streamwish") && isExtOn("jwp_embeds") -> StreamWishExtractor()
            h.contains("filemoon") && isExtOn("jwp_embeds") -> FilemoonExtractor()
            (h.contains("earnvids") || h.contains("dingtezuni")) && isExtOn("jwp_embeds") -> EarnVidsExtractor()
            h.contains("upbolt") && isExtOn("jwp_embeds") -> UpBoltExtractor()
            (h.contains("abyss") || h.contains("short.icu")) && isExtOn("jwp_embeds") -> AbyssExtractor()
            h.contains("lplaygo") && isExtOn("jwp_embeds") -> LPlayGoExtractor()
            h.contains("vidmoly") && isExtOn("jwp_embeds") -> VidMolyExtractor()
            else -> null
        }
    }

    private suspend fun dispatchEmbedOrDirect(
        sourceTag: String,
        serverLabel: String,
        targetUrl: String,
        referer: String,
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit,
        qualityHint: Int = Qualities.P1080.value,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        if (!targetUrl.startsWith("http") || isJunkCandidateUrl(targetUrl)) return

        if (targetUrl.contains("xcloud", true) || targetUrl.contains(".workers.dev/hls/", true) || targetUrl.contains(".workers.dev/m3u8/", true)) {
            if (isExtOn("xcloud")) {
                extractXCloudForMovieFox(sourceTag, targetUrl, lc, emitSub, emitLink)
            }
            return
        }

        if (targetUrl.contains(".m3u8", true) || targetUrl.contains(".mp4", true) || targetUrl.contains(".mpd", true)) {
            if (!lc.seenUrls.add(targetUrl)) return
            val linkType = when {
                targetUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                targetUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
            val hdrs = mutableMapOf("User-Agent" to effectiveUa, "Referer" to referer)
            hdrs.putAll(extraHeaders)
            emitLink(
                newExtractorLink(
                    source = sourceTag,
                    name = "$sourceTag [$serverLabel]",
                    url = targetUrl,
                    type = linkType
                ) {
                    this.referer = referer
                    this.quality = qualityHint
                    this.headers = hdrs
                }
            )
            return
        }

        val wrappedCb: (ExtractorLink) -> Unit = { ex ->
            if (!isJunkCandidateUrl(ex.url) && lc.seenUrls.add(ex.url)) {
                val hostLabel = ex.name.ifBlank { serverLabel }
                emitLink(
                    ExtractorLink(
                        source = sourceTag,
                        name = "$sourceTag [$hostLabel]",
                        url = ex.url,
                        referer = ex.referer.ifBlank { referer },
                        quality = if (ex.quality > 0) ex.quality else qualityHint,
                        headers = ex.headers,
                        extractorData = ex.extractorData,
                        type = ex.type
                    )
                )
            }
        }

        val custom = pickCustomExtractor(targetUrl)
        var any = false
        if (custom != null) {
            withTimeoutOrNull(4_500L) {
                runCatching {
                    custom.getUrl(targetUrl, referer, emitSub) { ex ->
                        any = true
                        wrappedCb(ex)
                    }
                }
            }
        }
        if (!any) {
            withTimeoutOrNull(4_500L) {
                runCatching {
                    loadExtractor(targetUrl, referer, emitSub, wrappedCb)
                }
            }
        }
    }

    // =========================================================================
    // SERVER 1: PRMOVIES (Fast Speedostream first, skip slow vidsrc chain)
    // =========================================================================
    private suspend fun fetchFromPrmovies(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = lc.searchQueryTitle.trim().replace(" ", "+")
        val domains = (listOf(activePrmoviesBase) + prmoviesMirrors).distinct().take(3)

        val winner = supervisorScope {
            val jobs = domains.map { dom ->
                async(Dispatchers.IO) {
                    val html = safeGetText(
                        "$dom/?s=$q",
                        mapOf("Referer" to "$dom/"),
                        timeoutSec = 4L,
                        sourceTag = "prmovies",
                        useCloudflareBypass = true
                    ).orEmpty()
                    if (html.contains("ml-item")) dom to html else null
                }
            }
            var foundPair: Pair<String, String>? = null
            for (job in jobs) {
                val res = runCatching { job.await() }.getOrNull()
                if (res != null) {
                    foundPair = res
                    break
                }
            }
            jobs.forEach { if (it.isActive) it.cancel() }
            foundPair
        } ?: return

        val (dom, searchHtml) = winner
        activePrmoviesBase = dom

        val searchDoc = Jsoup.parse(searchHtml, dom)
        val matchedPageUrl = searchDoc.select(".ml-item a.ml-mask[href]")
            .firstOrNull { a ->
                val t = (a.attr("oldtitle").ifBlank { a.attr("title") }.ifBlank { a.text() }).trim()
                titleMatchesCandidate(t, lc.pageTitle)
            }?.attr("href")?.let { normalizeUrl(it, dom) } ?: return

        val detailHtml = safeGetText(
            matchedPageUrl,
            mapOf("Referer" to "$dom/"),
            timeoutSec = 4L,
            sourceTag = "prmovies",
            useCloudflareBypass = true
        ).orEmpty()
        if (detailHtml.isBlank()) return
        val doc = Jsoup.parse(detailHtml, matchedPageUrl)

        var targetHtml = detailHtml
        var targetUrl = matchedPageUrl

        if (lc.isTv && matchedPageUrl.contains("/series/", true)) {
            val epAnchor = doc.select("#seasons .tvseason .les-content a[href]").firstOrNull { a ->
                val epNum = Regex("""(?i)\bepisode\s*[-:]?\s*(\d+)""").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                epNum == lc.episode
            }
            val epHref = epAnchor?.attr("href")?.let { normalizeUrl(it, dom) }
            if (!epHref.isNullOrBlank()) {
                val epHtml = safeGetText(
                    epHref,
                    mapOf("Referer" to matchedPageUrl),
                    timeoutSec = 4L,
                    sourceTag = "prmovies",
                    useCloudflareBypass = true
                ).orEmpty()
                if (epHtml.isNotBlank()) {
                    targetHtml = epHtml
                    targetUrl = epHref
                }
            }
        }

        val targetDoc = Jsoup.parse(targetHtml, targetUrl)
        val tabLabels = mutableMapOf<String, String>()
        for (li in targetDoc.select(".player_nav .idTabs li")) {
            val anchor = li.selectFirst("a[href^=#]") ?: continue
            val tabId = anchor.attr("href").removePrefix("#")
            val label = li.selectFirst(".les-content")?.text()?.trim()
            if (tabId.isNotBlank() && !label.isNullOrBlank()) tabLabels[tabId] = label
        }

        for (iframe in targetDoc.select("#player2 iframe[src], #content-embed iframe[src], div.movieplay iframe[src]").take(4)) {
            val src = iframe.attr("src").trim()
            if (src.isBlank()) continue
            val cleanEmbed = normalizeUrl(src, dom)
            val tabId = iframe.parents().firstOrNull { it.id().startsWith("tab") }?.id()
            val label = tabLabels[tabId] ?: "Speedostream"

            if (cleanEmbed.contains("speedostream", true)) {
                val host = runCatching { URI(cleanEmbed).host }.getOrNull() ?: "speedostream1.com"
                val rText = safeGetText(cleanEmbed, mapOf("Referer" to "https://$host/"), timeoutSec = 4L, sourceTag = "prmovies").orEmpty()
                if (rText.isNotBlank() && !rText.contains("Embeds disabled", true)) {
                    val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(rText)?.groupValues?.get(1)
                        ?: Regex("""sources\s*:\s*\[\s*\{?\s*file\s*:\s*["']([^"']+)["']""").find(rText)?.groupValues?.get(1)
                    if (!m3u8.isNullOrBlank()) {
                        dispatchEmbedOrDirect("Prmovies", label, m3u8, "https://$host/", lc, emitSub, emitLink)
                    }
                }
            } else if (!cleanEmbed.contains("vidsrc", true)) {
                dispatchEmbedOrDirect("Prmovies", label, cleanEmbed, targetUrl, lc, emitSub, emitLink)
            }
        }
    }

    // =========================================================================
    // SERVER 2: DRAMAVERSE
    // =========================================================================
    private fun isDramaVerseHlsUrl(url: String): Boolean {
        val lower = url.lowercase().substringBefore("#").substringBefore("?")
        return lower.contains(".workers.dev/hls/") || lower.contains(".workers.dev/m3u8/") || lower.endsWith(".m3u8")
    }

    private fun extractXCloudConfigValue(html: String, key: String): String? {
        val escapedKey = Regex.escape(key)
        val patterns = listOf(
            Regex("""["']?$escapedKey["']?\s*:\s*["']((?:\\.|[^"'\\])+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:var|let|const)\s+$escapedKey\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""$escapedKey\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val value = pattern.find(html)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?.replace("\\u003d", "=")
                ?.replace("&amp;", "&")
                ?.trim()
            if (!value.isNullOrBlank() && value != "null" && value != "undefined") return value
        }
        return null
    }

    private suspend fun extractXCloudForMovieFox(
        sourceTag: String,
        url: String,
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (isDramaVerseHlsUrl(url)) {
            if (lc.seenUrls.add(url)) {
                emitLink(
                    newExtractorLink(sourceTag, "$sourceTag [XCloud Direct HLS]", url, ExtractorLinkType.M3U8) {
                        this.referer = xcloudStreamReferer
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to effectiveUa, "Referer" to xcloudStreamReferer, "Origin" to "https://stream.xcloud1.lol")
                    }
                )
            }
            return
        }

        val cleanPath = url.substringBefore("?").substringBefore("#").trimEnd('/')
        val mediaId = cleanPath.substringAfterLast("/").removePrefix("stream_").trim()
        val streamPages = linkedSetOf<String>()
        if (mediaId.length >= 6) streamPages.add("https://stream.xcloud1.lol/$mediaId")
        streamPages.add(url)

        for (pageUrl in streamPages) {
            val rawHtml = safeGetText(pageUrl, mapOf("Referer" to xcloudReferer), timeoutSec = 4L, sourceTag = "dramaverse").orEmpty()
            if (rawHtml.isBlank()) continue
            val html = rawHtml.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&").replace("\\u003d", "=")

            Regex(""""lang"\s*:\s*"([^"]+)"[^}]*?"tgid"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                emitSub(newSubtitleFile(m.groupValues[1], "https://sub.xcloud.icu/${m.groupValues[2]}"))
            }

            val workerDomains = Regex("""https?://[a-zA-Z0-9.-]+\.workers\.dev""", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.value.trimEnd('/') }.distinct().toList()

            val hlsLinks = linkedSetOf<String>()
            listOf("hlsUrl", "photosHlsUrl", "HLS_URL", "PHOTOS_HLS_URL").forEach { key ->
                val rawVal = extractXCloudConfigValue(html, key) ?: return@forEach
                if (rawVal.startsWith("http")) hlsLinks.add(rawVal)
                else if (rawVal.startsWith("/hls/") || rawVal.startsWith("/m3u8/")) {
                    if (workerDomains.isNotEmpty()) workerDomains.forEach { hlsLinks.add("$it$rawVal") }
                    else hlsLinks.add(normalizeUrl(rawVal, pageUrl))
                }
            }

            Regex("""https?://[a-zA-Z0-9.-]+\.workers\.dev/(?:hls|m3u8)/[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { hlsLinks.add(it.value.trimEnd(',', ';', ')', ']', '}')) }

            for (hlsUrl in hlsLinks) {
                if (isDramaVerseHlsUrl(hlsUrl) && lc.seenUrls.add(hlsUrl)) {
                    emitLink(
                        newExtractorLink(sourceTag, "$sourceTag [XCloud HLS]", hlsUrl, ExtractorLinkType.M3U8) {
                            this.referer = pageUrl
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("User-Agent" to effectiveUa, "Referer" to pageUrl)
                        }
                    )
                }
            }
        }
    }

    private suspend fun fetchFromDramaVerse(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeEncode(lc.searchQueryTitle)
        val searchHtml = safeGetText(
            "$dramaVerseBase/?s=$q",
            mapOf("Referer" to "$dramaVerseBase/"),
            timeoutSec = 4L,
            sourceTag = "dramaverse",
            useCloudflareBypass = true
        ).orEmpty()
        if (searchHtml.isBlank()) return

        val searchDoc = Jsoup.parse(searchHtml, dramaVerseBase)
        val matchedUrl = searchDoc.select("li.post-item, article.item-list, a.post-thumb").firstNotNullOfOrNull { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("h2.post-title a, h3.post-title a, a.post-thumb")
            val href = a?.attr("href")?.trim().orEmpty()
            val title = a?.text()?.trim().orEmpty().ifEmpty { a?.attr("aria-label")?.substringBefore("|")?.trim().orEmpty() }
            if (href.isNotEmpty() && titleMatchesCandidate(title, lc.pageTitle)) normalizeUrl(href, dramaVerseBase) else null
        } ?: return

        val detailHtml = safeGetText(
            matchedUrl,
            mapOf("Referer" to "$dramaVerseBase/"),
            timeoutSec = 4L,
            sourceTag = "dramaverse",
            useCloudflareBypass = true
        ).orEmpty()
        if (detailHtml.isBlank()) return
        val doc = Jsoup.parse(detailHtml, matchedUrl)

        val btnSlides = doc.select(
            "a.btn-slide, .entry-content a[href*='sohojgyan'], .entry-content a[href*='blogspot'], " +
                ".entry-content a[href*='xcloud'], .entry-content a[href*='pixeldrain'], " +
                ".entry-content a[href*='drive.google.com'], .entry-content a[href*='gdflix'], .entry-content a[href*='hubcloud']"
        ).take(4)

        val targetEp = if (lc.isTv) lc.episode else 1
        for (slide in btnSlides) {
            val slideUrl = normalizeUrl(slide.attr("href"), matchedUrl)
            if (!slideUrl.startsWith("http")) continue
            val slideTitle = slide.selectFirst(".btn-title")?.text()?.trim().orEmpty().ifEmpty { slide.text().trim() }
            val epNum = Regex("""(?i)(?:episode|ep|e)\s*0*(\d+)""").find(slideTitle)?.groupValues?.get(1)?.toIntOrNull()
            if (!lc.isTv || epNum == null || epNum == targetEp) {
                dispatchEmbedOrDirect("DramaVerse", slideTitle.ifBlank { "Server" }, slideUrl, matchedUrl, lc, emitSub, emitLink)
            }
        }
    }

    // =========================================================================
    // SERVER 3: HINDIDRAMA
    // =========================================================================
    private suspend fun fetchFromHindiDrama(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeEncode(lc.searchQueryTitle)
        val searchHtml = safeGetText("$hindiDramaBase/search.php?q=$q", mapOf("Referer" to "$hindiDramaBase/"), timeoutSec = 4L, sourceTag = "hindidrama").orEmpty()
        if (searchHtml.isBlank()) return

        val searchDoc = Jsoup.parse(searchHtml, hindiDramaBase)
        val matchedUrl = searchDoc.select(".grid .card a, .ac a.it").firstOrNull { anchor ->
            val t = anchor.selectFirst("h3, .t")?.text()?.trim().orEmpty().ifEmpty { anchor.attr("title").trim() }
            titleMatchesCandidate(t, lc.pageTitle)
        }?.attr("href")?.let { normalizeUrl(it, hindiDramaBase) } ?: return

        val detailHtml = safeGetText(matchedUrl, mapOf("Referer" to "$hindiDramaBase/"), timeoutSec = 4L, sourceTag = "hindidrama").orEmpty()
        if (detailHtml.isBlank()) return
        val detailDoc = Jsoup.parse(detailHtml, matchedUrl)

        var watchPageUrl = matchedUrl
        if (lc.isTv) {
            val epLink = detailDoc.select(".eplist ul li a, a[href*='/hindi-dubbed/watch-']").firstOrNull { a ->
                val epNum = Regex("""(?:Episode|Ep|E|e)\s*(\d+)""").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""-e(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                epNum == lc.episode
            }?.attr("href")
            if (!epLink.isNullOrBlank()) {
                watchPageUrl = normalizeUrl(epLink, hindiDramaBase)
            }
        }

        val pageHtml = if (watchPageUrl == matchedUrl) detailHtml else {
            safeGetText(watchPageUrl, mapOf("Referer" to matchedUrl), timeoutSec = 4L, sourceTag = "hindidrama").orEmpty()
        }
        if (pageHtml.isBlank()) return
        val doc = Jsoup.parse(pageHtml, watchPageUrl)

        val internalEmbedSrc = doc.selectFirst("#player iframe, iframe[src*='/embed/']")?.attr("src")?.trim()
            ?: Regex("""["']((?:https?:\\?/\\?/[^"'\s<>]+)?\\?/embed\\?/[^"'\s<>]+)["']""")
                .find(pageHtml)?.groupValues?.get(1)?.replace("\\/", "/")

        val playerPageUrl = if (!internalEmbedSrc.isNullOrEmpty()) normalizeUrl(internalEmbedSrc, hindiDramaBase) else watchPageUrl
        val playerHtml = if (!internalEmbedSrc.isNullOrEmpty()) {
            safeGetText(playerPageUrl, mapOf("Referer" to watchPageUrl), timeoutSec = 4L, sourceTag = "hindidrama").orEmpty()
        } else {
            pageHtml
        }
        if (playerHtml.isBlank()) return

        val playerDoc = Jsoup.parse(playerHtml, playerPageUrl)
        val serverList = linkedMapOf<String, String>()

        playerDoc.select(".srv, [data-src], [data-url], [data-embed], [data-link], [data-video]").forEach { el ->
            val sName = el.text().trim().ifEmpty { "Server" }
            val rawUrl = listOf("data-src", "data-url", "data-embed", "data-link", "data-video")
                .firstNotNullOfOrNull { attr -> el.attr(attr).trim().takeIf { it.isNotEmpty() } }
                ?: Regex("""['"]((?:https?:)?//[^'"]+|/[^'"]+)['"]""").find(el.attr("onclick"))?.groupValues?.get(1)

            if (!rawUrl.isNullOrEmpty() && (rawUrl.startsWith("http") || rawUrl.startsWith("//") || rawUrl.startsWith("/"))) {
                val fullUrl = normalizeUrl(rawUrl, hindiDramaBase)
                if (!fullUrl.contains("hindidrama.net/hindi-dubbed")) {
                    serverList[fullUrl] = sName
                }
            }
        }

        playerDoc.select("iframe").forEach { el ->
            val src = el.attr("data-src").ifEmpty { el.attr("src") }.trim()
            if (src.isNotEmpty() && !src.startsWith("about:")) {
                serverList.putIfAbsent(normalizeUrl(src, hindiDramaBase), "Embed")
            }
        }

        for ((rawEmbedUrl, label) in serverList.entries.take(4)) {
            val normalizedEmbed = rawEmbedUrl
                .replace("streamtape.to", "streamtape.com", true)
                .replace("streamtape.net", "streamtape.com", true)
                .replace("shavetape.cash", "streamtape.com", true)
                .replace("streamtape.com/v/", "streamtape.com/e/", true)
            dispatchEmbedOrDirect("HindiDrama", label, normalizedEmbed, playerPageUrl, lc, emitSub, emitLink)
        }
    }

    // =========================================================================
    // SERVER 4: KATDRAMA
    // =========================================================================
    private fun isKatSupportedHost(href: String): Boolean {
        val lower = href.lowercase()
        return lower.contains("hubcloud") ||
            lower.contains("gamerxyt") ||
            lower.contains("gdflix") ||
            lower.contains("filebee") ||
            lower.contains("goflix") ||
            lower.contains("pixeldrain") ||
            lower.contains("hbplay.pages.dev") ||
            lower.contains("fuckingfast.net") ||
            lower.contains("drive.google.com")
    }

    private suspend fun fetchFromKatDrama(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeEncode(lc.searchQueryTitle)
        val searchHtml = safeGetText(
            "$katDramaBase/?s=$q",
            mapOf("Referer" to "$katDramaBase/"),
            timeoutSec = 4L,
            sourceTag = "katdrama",
            useCloudflareBypass = true
        ).orEmpty()
        if (searchHtml.isBlank()) return

        val searchDoc = Jsoup.parse(searchHtml, katDramaBase)
        val matchedUrl = searchDoc.select("article.latest-card h3.latest-title a, article.post h3 a, article.post h2 a")
            .firstOrNull { titleMatchesCandidate(it.text(), lc.pageTitle) }
            ?.attr("href")?.let { normalizeUrl(it, katDramaBase) } ?: return

        val pageHtml = safeGetText(
            matchedUrl,
            mapOf("Referer" to "$katDramaBase/"),
            timeoutSec = 4L,
            sourceTag = "katdrama",
            useCloudflareBypass = true
        ).orEmpty()
        if (pageHtml.isBlank()) return

        val doc = Jsoup.parse(pageHtml, matchedUrl)
        val mainArticle = doc.selectFirst("article.post, article, main, div.site-main, div.entry-content") ?: doc
        val targetEp = if (lc.isTv) lc.episode else 1
        val epRegex = Regex("""(?i)\b(?:episode|ep|e)\s*0*(\d+)\b""")
        val qualRegex = Regex("""(?i)\b(480p|720p|1080p|2160p)\b""")

        var currentEp = 1
        var currentQuality = "1080p"
        var emittedCount = 0

        for (el in mainArticle.select("*")) {
            if (emittedCount >= 4) break
            val ownText = el.ownText().trim()
            if (ownText.isNotEmpty() && ownText.length < 120 && el.tagName() != "a" && el.tagName() != "button") {
                epRegex.find(ownText)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 1..500) currentEp = it }
                qualRegex.find(ownText)?.groupValues?.get(1)?.let { currentQuality = it }
            }
            if (el.tagName() == "a" || el.tagName() == "button" || el.hasAttr("data-href") || el.hasAttr("data-url")) {
                val raw = el.attr("href").ifEmpty { el.attr("data-href") }.ifEmpty { el.attr("data-url") }.replace("&amp;", "&").trim()
                val href = if (raw.isNotEmpty()) normalizeUrl(raw, matchedUrl) else ""
                if (href.startsWith("http") && isKatSupportedHost(href)) {
                    val linkText = el.text().trim()
                    val epFromLink = epRegex.find(linkText)?.groupValues?.get(1)?.toIntOrNull() ?: currentEp
                    val qualFromLink = qualRegex.find(linkText)?.groupValues?.get(1) ?: currentQuality
                    if (!lc.isTv || epFromLink == targetEp) {
                        emittedCount++
                        dispatchEmbedOrDirect("KatDrama", qualFromLink, href, matchedUrl, lc, emitSub, emitLink, getQualityFromName(qualFromLink))
                    }
                }
            }
        }
    }

    // =========================================================================
    // SERVER 5: SFLIX
    // =========================================================================
    private fun md5Hex(input: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(input).joinToString("") { String.format("%02x", it) }

    private fun generateSflixSignature(url: String, timestamp: Long): String {
        val canonical = runCatching {
            val parsed = URI(url)
            val path = parsed.path ?: ""
            val rawQuery = parsed.rawQuery
            val query = if (!rawQuery.isNullOrBlank()) {
                rawQuery.split("&").mapNotNull {
                    val p = it.split("=")
                    if (p.isEmpty()) null else p[0] to (p.getOrNull(1) ?: "")
                }.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
            } else ""
            val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
            "GET\napplication/json\napplication/json\n\n$timestamp\n\n$canonicalUrl"
        }.getOrDefault("GET\napplication/json\napplication/json\n\n$timestamp\n\n")

        val secretBytes = Base64.decode(String(Base64.decode(sflixSecretB64, Base64.DEFAULT)), Base64.DEFAULT)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = Base64.encodeToString(mac.doFinal(canonical.toByteArray()), Base64.NO_WRAP)
        return "$timestamp|2|$signature"
    }

    private suspend fun loadSflixCaptions(
        streamId: String,
        subjectId: String,
        emitSub: (SubtitleFile) -> Unit
    ) {
        runCatching {
            val subLink = "https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId"
            val ts = System.currentTimeMillis()
            val subHeaders = mapOf(
                "user-agent" to "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)",
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to "$ts,${md5Hex(ts.toString().reversed().toByteArray())}",
                "x-tr-signature" to generateSflixSignature(subLink, ts),
                "x-client-info" to "{\"package_name\":\"com.community.mbox.in\",\"os\":\"android\",\"device_id\":\"$sflixDeviceId\"}",
                "x-client-status" to "0"
            )
            val body = safeGetText(subLink, subHeaders, timeoutSec = 4L, sourceTag = "sflix").orEmpty()
            val captions = JSONObject(body).optJSONObject("data")?.optJSONArray("extCaptions") ?: return
            val seen = HashSet<String>()
            for (i in 0 until captions.length()) {
                val cap = captions.optJSONObject(i) ?: continue
                val capUrl = cap.safeStr("url")
                if (capUrl.isBlank() || !seen.add(capUrl)) continue
                val langLabel = cap.safeStr("lanName").ifBlank { cap.safeStr("language", "English") }
                emitSub(newSubtitleFile("Sflix • $langLabel", capUrl))
            }
        }
    }

    private suspend fun emitSflixArray(
        arr: JSONArray?,
        kind: String,
        subjectId: String,
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ): Boolean {
        if (arr == null) return false
        var foundAny = false
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val streamUrl = item.safeStr("url")
            if (streamUrl.isBlank() || !lc.seenUrls.add(streamUrl)) continue

            val signCookie = item.safeStr("signCookie")
            val signHeaderKey = item.safeStr("signHeaderKey", "X-MB-Token")
            val resolutions = item.safeStr("resolutions")
            val streamId = item.safeStr("id", subjectId)
            val maxRes = resolutions.split(",").mapNotNull { it.trim().toIntOrNull() }.maxOrNull() ?: 720

            val linkType = when {
                kind == "DASH" || streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                kind == "HLS" || streamUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            val streamHeaders = mutableMapOf(
                "Origin" to sflixPlayerUrl,
                "Referer" to "$sflixPlayerUrl/",
                "User-Agent" to effectiveUa,
                "Accept" to "*/*"
            )
            if (signCookie.isNotBlank()) {
                streamHeaders[signHeaderKey] = signCookie
                streamHeaders["Cookie"] = signCookie
            }

            foundAny = true
            emitLink(
                newExtractorLink(
                    source = "Sflix",
                    name = "Sflix [$kind ${maxRes}p]",
                    url = streamUrl,
                    type = linkType
                ) {
                    this.referer = "$sflixPlayerUrl/"
                    this.headers = streamHeaders
                    this.quality = maxRes
                }
            )
            loadSflixCaptions(streamId, subjectId, emitSub)
        }
        return foundAny
    }

    private suspend fun fetchFromSflix(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val savedHost = ctxOrNull()?.let { MovieFoxSettingsDialog.getSflixHost(it) }
        val activeHost = if (!savedHost.isNullOrBlank() && savedHost in sflixDomains) savedHost else sflixDomains.first()
        val searchUrl = "$activeHost/search-result?keyword=${safeEncode(lc.searchQueryTitle)}"
        val sHeaders = mapOf(
            "Referer" to "$activeHost/",
            "User-Agent" to effectiveUa,
            "Cookie" to sflixCookies
        )

        val searchHtml = safeGetText(searchUrl, sHeaders, timeoutSec = 4L, sourceTag = "sflix").orEmpty()
        if (searchHtml.isBlank()) return
        val doc = Jsoup.parse(searchHtml, activeHost)

        var subjectId = ""
        var detailPath = ""

        val nuxtRaw = doc.selectFirst("script#__NUXT_DATA__")?.data().orEmpty()
        if (nuxtRaw.isNotEmpty()) {
            runCatching {
                val arr = JSONArray(nuxtRaw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (!obj.has("title") || !obj.has("detailPath")) continue
                    val title = arr.optString(obj.optInt("title", -1)).trim()
                    val path = arr.optString(obj.optInt("detailPath", -1)).trim()
                    val sid = obj.optInt("subjectId", -1).takeIf { it >= 0 }?.let { arr.optString(it) }.orEmpty()
                    if (title.isNotEmpty() && path.isNotEmpty() && sid.isNotEmpty() && titleMatchesCandidate(title, lc.pageTitle)) {
                        subjectId = sid
                        detailPath = path
                        break
                    }
                }
            }
        }

        if (subjectId.isEmpty() || detailPath.isEmpty()) return

        val se = if (lc.isTv) lc.season else 0
        val ep = if (lc.isTv) lc.episode else 0

        for (domain in (listOf(activeHost) + sflixDomains).distinct()) {
            val playUrl = "$domain/wefeed-h5api-bff/subject/play?subjectId=$subjectId" +
                "&se=$se&ep=$ep&detailPath=$detailPath" +
                "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
            val referer = "$sflixPlayerUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"
            val webHeaders = mapOf(
                "User-Agent" to effectiveUa,
                "Referer" to referer,
                "Origin" to sflixPlayerUrl,
                "Accept" to "application/json",
                "Cookie" to sflixCookies,
                "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                "x-request-lang" to "en",
                "x-vip-restrict" to "0",
                "x-no-high-risk-restrict" to "0"
            )

            val jsonText = safeGetText(playUrl, webHeaders, timeoutSec = 4L, sourceTag = "sflix").orEmpty()
            if (jsonText.isBlank()) continue

            val dataObj = runCatching { JSONObject(jsonText).optJSONObject("data") }.getOrNull() ?: continue
            val d1 = emitSflixArray(dataObj.optJSONArray("dash"), "DASH", subjectId, lc, emitSub, emitLink)
            val d2 = emitSflixArray(dataObj.optJSONArray("hls"), "HLS", subjectId, lc, emitSub, emitLink)
            val d3 = emitSflixArray(dataObj.optJSONArray("streams"), "MP4", subjectId, lc, emitSub, emitLink)

            if (d1 || d2 || d3) break
        }
    }

    // =========================================================================
    // SERVER 6: PLAY4U
    // =========================================================================
    private fun p4uUnseal(value: String?, key: String = p4uDefaultSealKey): String {
        if (value.isNullOrBlank()) return ""
        if (!value.startsWith(p4uSealPrefix)) return value
        var b64 = value.substring(p4uSealPrefix.length).replace("-", "+").replace("_", "/")
        when (b64.length % 4) {
            1 -> return ""
            2 -> b64 += "=="
            3 -> b64 += "="
        }
        return runCatching {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            val kb = key.toByteArray(Charsets.UTF_8)
            val out = StringBuilder(raw.size)
            for (i in raw.indices) {
                out.append(((raw[i].toInt() and 0xFF) xor (kb[i % kb.size].toInt() and 0xFF)).toChar())
            }
            val text = out.toString()
            if (text.isEmpty() || text.any { it < ' ' || it > '~' }) "" else text
        }.getOrDefault("")
    }

    private fun p4uExtractVar(html: String, varName: String): String? {
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
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private suspend fun fetchFromPlay4u(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        getFirebaseDomain("play4u")?.takeIf { it.contains("play4u", true) }?.let { activePlay4uBase = it }

        val p4uHeaders = mapOf(
            "User-Agent" to p4uUa,
            "Accept" to "text/html,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$activePlay4uBase/"
        )
        val searchUrl = "$activePlay4uBase/api/search.php?q=${safeEncode(lc.searchQueryTitle)}&page=1&per_page=15"
        val searchJson = safeGetText(searchUrl, p4uHeaders, timeoutSec = 4L, sourceTag = "play4u").orEmpty()
        if (searchJson.isBlank()) return

        val pageObj = runCatching { parseJson<P4uSearchPage>(searchJson) }.getOrNull() ?: return
        val matched = pageObj.items.firstOrNull { item ->
            !item.watch.isNullOrBlank() && titleMatchesCandidate(item.title, lc.pageTitle)
        } ?: return

        val watchPath = matched.watch.orEmpty()
        val pageHtml = safeGetText(normalizeUrl(watchPath, activePlay4uBase), p4uHeaders, timeoutSec = 4L, sourceTag = "play4u").orEmpty()
        if (pageHtml.isBlank()) return

        val sealKey = p4uSealKeyRegex.find(pageHtml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: p4uDefaultSealKey
        val streamHeaders = mapOf("Referer" to "$activePlay4uBase/", "Origin" to activePlay4uBase)

        if (lc.isTv && !watchPath.contains("/watch/")) {
            val series = p4uExtractVar(pageHtml, "__PA_SERIES__")?.let { runCatching { parseJson<P4uSeriesPayload>(it) }.getOrNull() }
            val targetSeason = series?.seasons?.values?.firstOrNull { (it.number ?: 0) == lc.season }
                ?: series?.seasons?.values?.firstOrNull()
            val epInfo = targetSeason?.episodes?.values?.firstOrNull { it.number == lc.episode }
                ?: targetSeason?.episodes?.values?.firstOrNull()
            val rawHls = p4uUnseal(epInfo?.hls, sealKey)
            val streamUrl = normalizeUrl(rawHls, activePlay4uBase)
            if (streamUrl.startsWith("http")) {
                val topQ = epInfo?.qualities.orEmpty().mapNotNull {
                    it.height ?: it.slug?.removeSuffix("p")?.toIntOrNull()
                }.maxOrNull() ?: Qualities.P1080.value
                val audioLabel = epInfo?.languages.orEmpty().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                    .joinToString("/").ifBlank { "HLS" }
                dispatchEmbedOrDirect("Play4u", audioLabel, streamUrl, "$activePlay4uBase/", lc, emitSub, emitLink, topQ, streamHeaders)
            }
            return
        }

        val play = p4uExtractVar(pageHtml, "__PA_PLAY__")?.let { runCatching { parseJson<P4uPlayPayload>(it) }.getOrNull() }
        if (play?.ok == true) {
            for (stream in play.streams) {
                val unsealed = p4uUnseal(stream.m3u8_url, sealKey)
                val link = if (unsealed.isNotBlank()) {
                    normalizeUrl(unsealed, activePlay4uBase)
                } else {
                    val host = stream.domain_name?.takeIf { it.isNotBlank() }
                    val path = stream.m3u8_path?.takeIf { it.startsWith("/") }
                    if (host != null && path != null) "https://$host$path" else null
                } ?: continue

                val topQ = stream.qualities.orEmpty().mapNotNull {
                    it.height ?: it.slug?.removeSuffix("p")?.toIntOrNull()
                }.maxOrNull() ?: Qualities.P1080.value
                val audio = stream.languages.orEmpty().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }.joinToString("/")
                val label = buildString {
                    append(stream.label?.takeIf { it.isNotBlank() } ?: "Play4u")
                    if (audio.isNotBlank()) append(" · $audio")
                }
                dispatchEmbedOrDirect("Play4u", label, link, "$activePlay4uBase/", lc, emitSub, emitLink, topQ, streamHeaders)
            }
        }
    }

    // =========================================================================
    // SERVER 7: ANIMEWORLDINDIA
    // =========================================================================
    private suspend fun fetchFromAnimeWorld(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        getFirebaseDomain("animeworldindia")?.takeIf { it.contains("animeworld", true) }?.let { activeAnimeWorldBase = it }

        val awHeaders = mapOf(
            "User-Agent" to awBrowserUa,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$activeAnimeWorldBase/"
        )
        val searchHtml = safeGetText(
            "$activeAnimeWorldBase/page/1/?s=${safeEncode(lc.searchQueryTitle)}",
            awHeaders,
            timeoutSec = 4L,
            sourceTag = "animeworldindia",
            useCloudflareBypass = true
        ).orEmpty()
        if (searchHtml.isBlank()) return

        val searchDoc = Jsoup.parse(searchHtml, activeAnimeWorldBase)
        val matchedUrl = searchDoc.select("li.post, article.post").firstNotNullOfOrNull { el ->
            val link = el.selectFirst("a[href*=/series/], a[href*=/movies/]")?.attr("href")?.trim().orEmpty()
            val title = el.selectFirst("h2, h3")?.text()?.trim().orEmpty()
            if (link.isNotEmpty() && !link.endsWith("/series/") && !link.endsWith("/movies/") && titleMatchesCandidate(title, lc.pageTitle)) {
                normalizeUrl(link, activeAnimeWorldBase)
            } else null
        } ?: return

        val detailHtml = safeGetText(
            matchedUrl,
            awHeaders,
            timeoutSec = 4L,
            sourceTag = "animeworldindia",
            useCloudflareBypass = true
        ).orEmpty()
        if (detailHtml.isBlank()) return
        val detailDoc = Jsoup.parse(detailHtml, matchedUrl)

        var watchPageUrl = matchedUrl
        if (lc.isTv && !matchedUrl.contains("/movies/")) {
            val epLinks = detailDoc.select("a[href*=/episode/]").map { normalizeUrl(it.attr("href"), activeAnimeWorldBase) }.distinct()
            epLinks.firstOrNull { href ->
                val m = Regex("""(\d+)x(\d+)""").find(href)
                m?.groupValues?.get(2)?.toIntOrNull() == lc.episode
            }?.let { watchPageUrl = it }
        }

        val pageHtml = if (watchPageUrl == matchedUrl) detailHtml else {
            safeGetText(watchPageUrl, awHeaders, timeoutSec = 4L, sourceTag = "animeworldindia", useCloudflareBypass = true).orEmpty()
        }
        if (pageHtml.isBlank()) return
        val pageDoc = Jsoup.parse(pageHtml, watchPageUrl)

        val iframes = pageDoc.select("iframe").mapNotNull { el ->
            val src = el.attr("data-src").ifBlank { el.attr("src") }.trim()
            if (src.isNotBlank()) normalizeUrl(src, activeAnimeWorldBase) else null
        }.distinct()

        for (iframe in iframes) {
            if (iframe.contains("zephyrix", true) || iframe.contains("zephyrflick", true)) {
                val videoId = Regex("/video/([a-f0-9]+)").find(iframe)?.groupValues?.get(1) ?: continue
                val playerBase = Regex("(https?://[^/]+)").find(iframe)?.groupValues?.get(1) ?: continue
                val postRes = runCatching {
                    app.post(
                        "$playerBase/player/index.php?data=$videoId&do=getVideo",
                        data = mapOf("hash" to videoId, "r" to "$activeAnimeWorldBase/"),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to playerBase,
                            "Referer" to "$playerBase/video/$videoId",
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "User-Agent" to awBrowserUa
                        ),
                        timeout = 5L
                    )
                }.getOrNull() ?: continue
                val vid = runCatching { parseJson<AwZephyrixVideo>(postRes.text) }.getOrNull() ?: continue
                val rawStream = vid.videoSource ?: vid.securedLink ?: continue
                val streamUrl = if (rawStream.startsWith("http")) rawStream else "$playerBase$rawStream"
                dispatchEmbedOrDirect(
                    "AnimeWorld",
                    "Multi-Audio HLS",
                    streamUrl,
                    "$playerBase/",
                    lc,
                    emitSub,
                    emitLink,
                    Qualities.P1080.value,
                    mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                )
            }
        }
    }

    // =========================================================================
    // SERVER 8: THEMOVIESFLIX (Fast first group only, skip slow filebee hang)
    // =========================================================================
    private data class TmfStreamItem(
        val name: String,
        val url: String,
        val type: ExtractorLinkType,
        val headers: Map<String, String> = emptyMap()
    )

    private data class TmfDriveData(
        val title: String,
        val quality: Int?,
        val info: String,
        val links: List<String>,
        val episodes: Map<Int, List<String>>
    )

    private fun tmfHeaders(referer: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = tmfDesktopUa
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        h["Accept-Language"] = "en-US,en;q=0.9"
        h["Upgrade-Insecure-Requests"] = "1"
        referer?.let { h["Referer"] = it }
        return h
    }

    private suspend fun tmfDomain(): String {
        val fb = getFirebaseDomain("themoviesflix")?.removeSuffix("/")
        val candidates = (listOfNotNull(fb, activeTmfBase) + tmfFallbackDomains).distinct()
        return supervisorScope {
            val jobs = candidates.map { c ->
                async(Dispatchers.IO) {
                    val txt = safeGetText("$c/?s=the", tmfHeaders(), timeoutSec = 4L, sourceTag = "themoviesflix").orEmpty()
                    if (txt.contains("featured-thumbnail", true) || txt.contains("latestpost", true)) c else null
                }
            }
            var chosen: String? = null
            for (job in jobs) {
                val res = runCatching { job.await() }.getOrNull()
                if (res != null) {
                    chosen = res
                    break
                }
            }
            jobs.forEach { if (it.isActive) it.cancel() }
            val result = chosen ?: activeTmfBase
            activeTmfBase = result
            result
        }
    }

    private fun tmfQualityOf(text: String): Int? =
        Regex("""(\d{3,4})[pP]\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (text.contains("2160", true) || text.contains("4K", true) || text.contains("UHD", true)) 2160 else null

    private fun tmfInfoOf(title: String): String =
        Regex("""[\[{(]([^\]})]+)[\]})]""").findAll(title)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.equals("links", true) && !it.matches(Regex("""\d{4}""")) }
            .joinToString(" · ")

    private fun tmfParseDriveDoc(doc: Document): TmfDriveData? {
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" – ").substringBefore(" - ").trim()
        if (title.contains("zip", true)) return null

        val junk = listOf(
            "nexdrive", "mobilejsr", "vglist", "w.org", "wordpress", "gmpg",
            "googleapis", "googletagmanager", "font-awesome", "schema", "category/",
            "t.me/+", "telegram"
        )
        fun isJunk(href: String) = junk.any { href.contains(it, true) }

        var encodedHtml: String? = null
        for (script in doc.select("script")) {
            val data = script.data()
            if (!data.contains("encoded")) continue
            val blob = Regex("""const\s+encoded\s*=\s*["]([A-Za-z0-9+/=]+)["]""").find(data)?.groupValues?.get(1) ?: continue
            val decoded = runCatching { base64Decode(blob) }.getOrNull()
            if (decoded != null && decoded.contains("<")) {
                encodedHtml = decoded
                break
            }
        }

        val root = if (encodedHtml != null) {
            Jsoup.parse(encodedHtml).body()
        } else {
            doc.selectFirst("article") ?: doc.selectFirst("div.entry-content")
                ?: return TmfDriveData(title, tmfQualityOf(title), tmfInfoOf(title), emptyList(), emptyMap())
        }

        val links = root.select("a[href]").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && !isJunk(href)) href else null
        }.distinct()

        val episodes = mutableMapOf<Int, MutableList<String>>()
        for (h4 in root.select("h4")) {
            val text = h4.text().trim()
            if (!text.contains("Episode", true)) continue
            val epNum = Regex("""Episodes?\s*:?\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val epLinks = mutableListOf<String>()
            var sibling = h4.nextElementSibling()
            var attempts = 0
            while (sibling != null && attempts < 3) {
                for (a in sibling.select("a[href]")) {
                    val href = a.attr("href").trim()
                    if (href.startsWith("http") && !isJunk(href)) epLinks.add(href)
                }
                if (epLinks.isNotEmpty()) break
                sibling = sibling.nextElementSibling()
                attempts++
            }
            if (epLinks.isNotEmpty()) {
                episodes.getOrPut(epNum) { mutableListOf() }.addAll(epLinks.distinct())
            }
        }

        return TmfDriveData(title, tmfQualityOf(title), tmfInfoOf(title), links, episodes)
    }

    private suspend fun tmfFetchDriveOnce(rawUrl: String, referer: String): Document? {
        val jar = mutableMapOf<String, String>()
        var current = rawUrl
        val seen = mutableSetOf<String>()
        repeat(6) {
            if (!seen.add(current)) return null
            val res = runCatching {
                app.get(current, headers = tmfHeaders(referer), cookies = jar, allowRedirects = false, timeout = 4L)
            }.getOrNull() ?: return null
            jar.putAll(res.cookies)
            val loc = res.headers["location"]?.trim().orEmpty()
            if (loc.isEmpty()) {
                val ok = res.code == 200
                val body = if (ok) runCatching { res.text }.getOrNull().orEmpty() else ""
                runCatching { res.okhttpResponse.close() }
                if (!ok || body.contains("challenge-platform") || body.contains("Just a moment", true)) return null
                return Jsoup.parse(body, current)
            }
            runCatching { res.okhttpResponse.close() }
            current = when {
                loc.startsWith("http") -> loc
                loc.startsWith("/") -> {
                    val u = URI(current)
                    "${u.scheme}://${u.host}$loc"
                }
                else -> "${current.trimEnd('/')}/$loc"
            }
        }
        return null
    }

    private suspend fun tmfFetchDrivePage(url: String, referer: String): TmfDriveData? {
        val key = url.replace("nexdrive.beer", "mobilejsr.best").replace("nexdrive.fit", "mobilejsr.best")
        tmfDriveCache[key]?.let { (ts, cached) ->
            if (System.currentTimeMillis() - ts < 5 * 60 * 1000L) return cached
        }
        return tmfDriveMutex.withLock {
            tmfDriveCache[key]?.let { (ts, cached) ->
                if (System.currentTimeMillis() - ts < 5 * 60 * 1000L) return cached
            }
            val mirror = if (key.contains("mobilejsr.best")) key.replace("mobilejsr.best", "nexdrive.beer") else key
            val doc = tmfFetchDriveOnce(key, referer) ?: tmfFetchDriveOnce(mirror, referer)
            val parsed = doc?.let { tmfParseDriveDoc(it) }
            tmfDriveCache[key] = System.currentTimeMillis() to parsed
            parsed
        }
    }

    private suspend fun tmfRedirectOf(url: String, referer: String?): String? {
        var current = url
        repeat(4) {
            val res = runCatching {
                app.get(current, headers = tmfHeaders(referer), allowRedirects = false, timeout = 4L)
            }.getOrNull() ?: return null
            val loc = res.headers["location"]?.trim().orEmpty()
            runCatching { res.okhttpResponse.close() }
            if (loc.isEmpty()) return current
            current = when {
                loc.startsWith("http") -> loc
                loc.startsWith("/") -> {
                    val u = URI(current)
                    "${u.scheme}://${u.host}$loc"
                }
                else -> return null
            }
        }
        return current
    }

    private suspend fun tmfResolveFastDl(url: String): List<TmfStreamItem> {
        val text = safeGetText(url, tmfHeaders(), timeoutSec = 4L, sourceTag = "themoviesflix").orEmpty()
        if (text.isBlank() || text.contains("File is Deleted") || text.contains("Something went wrong")) return emptyList()
        val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: Regex("""'(https://fastdl\.[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
            ?: Regex("""'(https://hubcdn\.[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
            ?: return emptyList()
        val carrier = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(reurl)?.groupValues?.get(1)?.let { wrapped ->
            val padded = if (wrapped.length % 4 > 0) wrapped + "=".repeat(4 - wrapped.length % 4) else wrapped
            runCatching { base64Decode(padded) }.getOrNull()
        } ?: reurl
        val googleUrl = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1) ?: return emptyList()
        val direct = URLDecoder.decode(googleUrl, "UTF-8")
        if (!direct.startsWith("http")) return emptyList()
        val u = runCatching { URI(url) }.getOrNull()
        val ref = if (u != null && u.host != null) "${u.scheme}://${u.host}/" else ""
        return listOf(TmfStreamItem("G-Direct", direct, ExtractorLinkType.VIDEO, if (ref.isNotBlank()) mapOf("Referer" to ref) else emptyMap()))
    }

    private suspend fun tmfResolveVegaDrive(url: String): List<TmfStreamItem> {
        val html = safeGetText(url, tmfHeaders(), timeoutSec = 4L, sourceTag = "themoviesflix").orEmpty()
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, url)
        val token = url.substringAfter("/s/").substringBefore("?").trimEnd('/')
        val vegaBase = doc.select("a[href]").map { it.attr("href") }
            .firstOrNull { it.contains("/d/") || it.contains("/go/") }
            ?.let { runCatching { URI(it).let { u -> "${u.scheme}://${u.host}" } }.getOrNull() }
            ?: "https://one.vegadrive.me"

        val out = mutableListOf<TmfStreamItem>()
        val drop = tmfRedirectOf("$vegaBase/go/$token/skydrop", "$vegaBase/")
        if (drop != null && drop.contains("googleusercontent") && !drop.substringAfterLast("/").contains(".zip", true)) {
            out.add(TmfStreamItem("V-Drive Vegadrop (10Gbps)", drop, ExtractorLinkType.VIDEO))
        }
        val pixel = tmfRedirectOf("$vegaBase/go/$token/pixeldrain", "$vegaBase/")
        val pHost = pixel?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }.orEmpty()
        if (pixel != null && pHost.contains("pixeldrain")) {
            val id = pixel.substringBefore("?").substringBefore("#").substringAfterLast("/")
            if (id.isNotBlank()) out.add(TmfStreamItem("V-Drive Pixeldrain", "https://pixeldrain.com/api/file/$id", ExtractorLinkType.VIDEO))
        }
        return out.distinctBy { it.url }
    }

    private suspend fun tmfResolveFilePress(url: String): List<TmfStreamItem> = withTimeoutOrNull(3_500L) {
        val id = tmfFilePressIdRegex.find(url)?.groupValues?.get(1) ?: return@withTimeoutOrNull emptyList()
        val apiBase = "https://filebee.xyz/api"
        val infoText = safeGetText("$apiBase/file/get/$id", tmfHeaders("https://filebee.xyz/"), timeoutSec = 3L, sourceTag = "themoviesflix").orEmpty()
        val info = runCatching { JSONObject(infoText).optJSONObject("data") }.getOrNull() ?: return@withTimeoutOrNull emptyList()
        val fileName = info.optString("name")
        if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(fileName.trim())) return@withTimeoutOrNull emptyList()

        suspend fun postStep(endpoint: String, taskOrId: String, method: String): String? {
            val body = JSONObject().put("captchaValue", "").put("id", taskOrId).put("method", method).toString()
            val res = runCatching {
                app.post(
                    "$apiBase/$endpoint/",
                    headers = tmfHeaders("https://filebee.xyz/") + mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "Origin" to "https://filebee.xyz"
                    ),
                    json = body,
                    timeout = 3L
                )
            }.getOrNull() ?: return null
            val parsed = runCatching { JSONObject(res.text) }.getOrNull() ?: return null
            if (!parsed.optBoolean("status")) return null
            return when (val d = parsed.opt("data")) {
                is String -> d.takeIf { it.isNotBlank() }
                is JSONArray -> (0 until d.length()).firstNotNullOfOrNull { i -> d.optString(i).takeIf { it.startsWith("http") } }
                else -> null
            }
        }

        val out = mutableListOf<TmfStreamItem>()
        val telegram = postStep("file/downlaod", id, "telegramDownload")
        if (telegram != null && telegram.startsWith("http")) {
            out.add(TmfStreamItem("FilePress Telegram", telegram, ExtractorLinkType.VIDEO))
        }
        out
    } ?: emptyList()

    private suspend fun fetchFromTheMoviesFlix(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val dom = tmfDomain()
        val searchHtml = safeGetText(
            "$dom/?s=${safeEncode(lc.searchQueryTitle)}",
            tmfHeaders(),
            timeoutSec = 4L,
            sourceTag = "themoviesflix",
            useCloudflareBypass = true
        ).orEmpty()
        if (searchHtml.isBlank()) return

        val searchDoc = Jsoup.parse(searchHtml, dom)
        val matchedUrl = searchDoc.select("article.latestpost a[id=featured-thumbnail]").firstNotNullOfOrNull { a ->
            val href = a.attr("href").trim()
            val tRaw = a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
            if (href.isNotEmpty() && titleMatchesCandidate(tRaw, lc.pageTitle)) normalizeUrl(href, dom) else null
        } ?: return

        val detailHtml = safeGetText(
            matchedUrl,
            tmfHeaders(dom),
            timeoutSec = 4L,
            sourceTag = "themoviesflix",
            useCloudflareBypass = true
        ).orEmpty()
        if (detailHtml.isBlank()) return

        val doc = Jsoup.parse(detailHtml, matchedUrl)
        val entry = doc.selectFirst("div.entry-content") ?: return

        data class TmfGroup(val label: String, val redirectUrl: String)
        val groups = entry.select("div.mfx-download-group").flatMap { div ->
            val label = div.selectFirst("h3")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (tmfPackRegex.containsMatchIn(label)) return@flatMap emptyList()
            div.select("a[href]").mapNotNull { a ->
                val txt = a.text().trim()
                val href = a.attr("href").trim()
                if (!href.startsWith("http") || txt.contains("Batch", true) || txt.contains("Zip", true) || tmfPackRegex.containsMatchIn(txt)) null
                else TmfGroup(label, href)
            }
        }.distinctBy { it.redirectUrl }

        if (groups.isEmpty()) return

        val selectedGroups = if (lc.isTv) {
            val bySeason = groups.filter { g ->
                val sNum = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(g.label)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\bS(\d+)\b""", RegexOption.IGNORE_CASE).find(g.label)?.groupValues?.get(1)?.toIntOrNull()
                sNum == lc.season
            }
            bySeason.ifEmpty { groups }.take(1)
        } else {
            groups.take(1)
        }

        for (grp in selectedGroups) {
            val drive = tmfFetchDrivePage(grp.redirectUrl, matchedUrl) ?: continue
            val hrefs = if (lc.isTv) drive.episodes[lc.episode].orEmpty() else drive.links
            if (hrefs.isEmpty()) continue
            val qHint = drive.quality ?: Qualities.P1080.value
            val suffix = if (drive.info.isBlank()) "" else " · ${drive.info}"

            for (href in hrefs.distinct().take(3)) {
                val resolvedItems = when {
                    href.contains("fastdl.", true) || href.contains("hubcdn.", true) -> tmfResolveFastDl(href)
                    href.contains("vegadrive.", true) || href.contains("m-drive.", true) -> tmfResolveVegaDrive(href)
                    href.contains("filebee.", true) || href.contains("filepress.", true) || href.contains("fpgo.", true) -> tmfResolveFilePress(href)
                    else -> emptyList()
                }
                if (resolvedItems.isNotEmpty()) {
                    for (s in resolvedItems) {
                        dispatchEmbedOrDirect("TheMoviesFlix", "${s.name}$suffix", s.url, "", lc, emitSub, emitLink, qHint, s.headers)
                    }
                } else {
                    dispatchEmbedOrDirect("TheMoviesFlix", "Mirror$suffix", href, "https://mobilejsr.best/", lc, emitSub, emitLink, qHint)
                }
            }
        }
    }

    // =========================================================================
    // SERVER 9: VEGAMOVIES (Fast parallel mirror race)
    // =========================================================================
    private suspend fun fetchFromVegaMovies(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val fbDom = getFirebaseDomain("vegamovies")?.removeSuffix("/")
        val candidates = (listOfNotNull(fbDom, activeVegaBase) + vegaFallbackDomains)
            .filter { !it.contains("vegamovies.latino", true) }
            .distinct()
            .take(3)

        val winner = supervisorScope {
            val jobs = candidates.map { dom ->
                async(Dispatchers.IO) {
                    val html = safeGetText(
                        "$dom/?s=${safeEncode(lc.searchQueryTitle)}",
                        mapOf("Referer" to "$dom/"),
                        timeoutSec = 4L,
                        sourceTag = "vegamovies",
                        useCloudflareBypass = true
                    ).orEmpty()
                    if (html.isNotBlank()) dom to html else null
                }
            }
            var foundPair: Pair<String, String>? = null
            for (job in jobs) {
                val res = runCatching { job.await() }.getOrNull()
                if (res != null) {
                    foundPair = res
                    break
                }
            }
            jobs.forEach { if (it.isActive) it.cancel() }
            foundPair
        } ?: return

        val (activeDom, searchHtml) = winner
        activeVegaBase = activeDom

        val searchDoc = Jsoup.parse(searchHtml, activeDom)
        val matchedUrl = searchDoc.select("article.post a[href], .blog-posts article a[href], a.post-thumb[href], h2.entry-title a[href]")
            .firstNotNullOfOrNull { el ->
                val href = el.attr("href").trim()
                val tRaw = el.attr("title").ifBlank { el.selectFirst("img")?.attr("alt").orEmpty() }.ifBlank { el.text() }
                if (href.startsWith("http") && titleMatchesCandidate(tRaw, lc.pageTitle)) href else null
            } ?: return

        val detailHtml = safeGetText(
            matchedUrl,
            mapOf("Referer" to "$activeDom/"),
            timeoutSec = 4L,
            sourceTag = "vegamovies",
            useCloudflareBypass = true
        ).orEmpty()
        if (detailHtml.isBlank()) return

        val doc = Jsoup.parse(detailHtml, matchedUrl)
        val entry = doc.selectFirst("div.entry-content, article") ?: doc
        val targetEp = if (lc.isTv) lc.episode else 1
        val epRegex = Regex("""(?i)\b(?:episode|ep|e)\s*0*(\d+)\b""")
        val qualRegex = Regex("""(?i)\b(2160p|1080p|720p|480p)\b""")

        var currentEp = 1
        var currentQual = "1080p"
        val hostTargets = linkedMapOf<String, Pair<String, Int>>()

        for (el in entry.select("*")) {
            val own = el.ownText().trim()
            if (own.isNotEmpty() && own.length < 120 && el.tagName() != "a") {
                epRegex.find(own)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 1..500) currentEp = it }
                qualRegex.find(own)?.groupValues?.get(1)?.let { currentQual = it }
            }
            if (el.tagName() == "a") {
                val href = el.attr("href").trim()
                val low = href.lowercase()
                val isHost = low.contains("hubcloud") || low.contains("gdflix") || low.contains("gdlink") ||
                    low.contains("vegadrive") || low.contains("m-drive") || low.contains("vcloud") ||
                    low.contains("mcloud") || low.contains("pixeldrain") || low.contains("fastdl") ||
                    low.contains("filebee") || low.contains("goflix")
                if (href.startsWith("http") && isHost) {
                    val txt = el.text().trim()
                    val ep = epRegex.find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: currentEp
                    val qual = qualRegex.find(txt)?.groupValues?.get(1) ?: currentQual
                    if (!lc.isTv || ep == targetEp) {
                        hostTargets.putIfAbsent(href, qual to getQualityFromName(qual))
                    }
                }
            }
        }

        for ((targetLink, qPair) in hostTargets.entries.take(4)) {
            val (qLabel, qVal) = qPair
            if (targetLink.contains("vegadrive.", true) || targetLink.contains("m-drive.", true)) {
                for (s in tmfResolveVegaDrive(targetLink)) {
                    dispatchEmbedOrDirect("VegaMovies", "${s.name} [$qLabel]", s.url, matchedUrl, lc, emitSub, emitLink, qVal, s.headers)
                }
            } else {
                dispatchEmbedOrDirect("VegaMovies", qLabel, targetLink, matchedUrl, lc, emitSub, emitLink, qVal)
            }
        }
    }

    // =========================================================================
    // LOAD LINKS: Fast Parallel Execution With Tight Per-Server Cap (11s)
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val ctx = ctxOrNull()
            val reqTimeoutSec = fastTimeoutSec(5L)
            val serverCapMs = 11_000L

            var rawPageUrl = data
            var pageTitle = ""
            var tmdbId: String? = null
            var imdbId = ""
            var isTv = false
            var season = 1
            var episode = 1

            if (data.trimStart().startsWith("{")) {
                runCatching {
                    val obj = JSONObject(data)
                    rawPageUrl = obj.safeStr("url", data)
                    pageTitle = obj.safeStr("title")
                    tmdbId = obj.safeStr("tmdbId").takeIf { it.isNotBlank() }
                    imdbId = obj.safeStr("imdbId")
                    isTv = obj.optBoolean("isTv", false)
                    season = obj.optInt("season", 1).coerceAtLeast(1)
                    episode = obj.optInt("episode", 1).coerceAtLeast(1)
                }
            }

            val cleanPageUrl = rawPageUrl.substringBefore("?")
            val urlId = extractSeriesId(cleanPageUrl)
            val candidateIds = listOfNotNull(tmdbId, urlId).distinct()
            val watchUrl = if (candidateIds.isNotEmpty()) {
                if (isTv) "$mainUrl/watch/tv-${candidateIds.first()}?s=$season&e=$episode"
                else "$mainUrl/watch/movie-${candidateIds.first()}"
            } else {
                cleanPageUrl
            }

            val lc = LinkContext(
                pageTitle = pageTitle,
                searchQueryTitle = cleanSearchTitle(pageTitle).ifBlank { pageTitle },
                isTv = isTv,
                season = season,
                episode = episode,
                imdbId = imdbId,
                candidateIds = candidateIds,
                watchUrl = watchUrl,
                baseHeaders = directHeaders(mainUrl, mapOf("Referer" to watchUrl)),
                reqTimeoutSec = reqTimeoutSec,
                downloadOnly = ctx?.let { MovieFoxSettingsDialog.isDownloadOnly(it) } ?: false,
                allow4k = ctx?.let { MovieFoxSettingsDialog.isQuality4kEnabled(it) } ?: true,
                allow1080 = ctx?.let { MovieFoxSettingsDialog.isQuality1080Enabled(it) } ?: true,
                allow720 = ctx?.let { MovieFoxSettingsDialog.isQuality720Enabled(it) } ?: true,
                allow480 = ctx?.let { MovieFoxSettingsDialog.isQuality480Enabled(it) } ?: true,
                seenUrls = ConcurrentHashMap.newKeySet()
            )

            val emitLock = Any()
            val subLock = Any()
            val foundFlag = AtomicBoolean(false)

            val emitSub: (SubtitleFile) -> Unit = { sub ->
                synchronized(subLock) { subtitleCallback(sub) }
            }
            val emitLink: (ExtractorLink) -> Unit = { link ->
                if (!isJunkCandidateUrl(link.url) && link.url.startsWith("http")) {
                    val isStream = link.type == ExtractorLinkType.M3U8 || link.type == ExtractorLinkType.DASH
                    val allowedMode = !(lc.downloadOnly && isStream)
                    val allowedQual = isQualityAllowed(lc, link.quality, link.name)
                    if (allowedMode && (allowedQual || isStream)) {
                        synchronized(emitLock) {
                            foundFlag.set(true)
                            callback(link)
                        }
                    }
                }
            }

            val orderedKeys = (ctx?.let { MovieFoxSettingsDialog.getOrderedEnabledKeys(it) }
                ?: listOf("sflix", "play4u", "prmovies", "hindidrama", "themoviesflix", "dramaverse", "katdrama", "animeworld", "vegamovies")).distinct()

            val concurrency = (ctx?.let { MovieFoxSettingsDialog.getConcurrency(it) } ?: 24).coerceIn(8, 50)
            val sem = Semaphore(concurrency)
            supervisorScope {
                val tasks = mutableListOf<Deferred<Unit>>()
                orderedKeys.forEach { key ->
                    tasks.add(async(Dispatchers.IO) {
                        sem.withPermit {
                            withTimeoutOrNull(serverCapMs) {
                                when (key.lowercase()) {
                                    "sflix" -> fetchFromSflix(lc, emitSub, emitLink)
                                    "play4u" -> fetchFromPlay4u(lc, emitSub, emitLink)
                                    "prmovies" -> fetchFromPrmovies(lc, emitSub, emitLink)
                                    "hindidrama" -> fetchFromHindiDrama(lc, emitSub, emitLink)
                                    "themoviesflix" -> fetchFromTheMoviesFlix(lc, emitSub, emitLink)
                                    "dramaverse" -> fetchFromDramaVerse(lc, emitSub, emitLink)
                                    "katdrama" -> fetchFromKatDrama(lc, emitSub, emitLink)
                                    "animeworld" -> fetchFromAnimeWorld(lc, emitSub, emitLink)
                                    "vegamovies" -> fetchFromVegaMovies(lc, emitSub, emitLink)
                                }
                            }
                        }
                        Unit
                    })
                }
                tasks.awaitAll()
            }

            foundFlag.get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("loadLinks", data.take(120), e, "Fatal loadLinks error")
            false
        }
    }
}

