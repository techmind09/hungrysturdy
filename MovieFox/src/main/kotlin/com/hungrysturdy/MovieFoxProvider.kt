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
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.onAwait
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
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

    // CineStream endpoints
    private val csMultiDecryptApi = "https://enc-dec.app/api"
    private val csVideasyApi = "https://api.speedracelight.com"
    private val csHexaApi = "https://theemoviedb.hexa.su"
    private val csVidzeeApi = "https://player.vidzee.wtf"
    private val csVidfastApi = "https://vidfast.vc"
    private val csCinejoyApi = "https://api.shegu.st"
    private val csCinejoyBaseApi = "https://cinejoy.to"
    private val csKisskhApi = "https://kisskh.nl"
    private val csAnimesaltApi = "https://animesalt.cx"
    private val csToonstreamApi = "https://toonstream.one"
    private val csWyzieSubsApi = "https://sub.wyzie.io"
    private val csUrlsJsonUrl = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"

    private val cfMutexMap = ConcurrentHashMap<String, Mutex>()
    private val cfKillerMap = ConcurrentHashMap<String, CloudflareKiller>()

    // Server 1: Prmovies Mirrors
    private val prmoviesMirrors = listOf(
        "https://prmovies.coupons",
        "https://prmovies.kids",
        "https://prmovies.beer"
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
        "https://themoviesflix.eat",
        "https://themoviesflix.xin",
        "https://themoviesflixhq.com"
    )
    @Volatile
    private var activeTmfBase = "https://themoviesflix.eat"
    private val tmfDesktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val tmfDriveCache = ConcurrentHashMap<String, Pair<Long, TmfDriveData?>>()
    private val tmfDriveMutex = Mutex()
    private val tmfPackRegex = Regex("""(?i)\b(zip|rar|7z|batch)\b""")
    private val tmfFilePressIdRegex = Regex("""/file/([a-f0-9]{16,40})""")

    // Server 9: VegaMovies
    private val vegaFallbackDomains = listOf(
        "https://vegamovies11.in",
        "https://vegamoviessi.ink",
        "https://vegamovies4u.top",
        "https://vegamoviece.biz"
    )
    @Volatile
    private var activeVegaBase = "https://vegamovies11.in"

    // Server 10: BollyFlix
    private val bollyFlixFallbackDomains = listOf(
        "https://new1.bollyflix.tours",
        "https://bollyflix.frl"
    )
    @Volatile
    private var activeBollyFlixBase = "https://new1.bollyflix.tours"

    private val firebaseDomainsCache = ConcurrentHashMap<String, String>()
    private val csDynamicUrlsCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var lastFirebaseFetchMs = 0L
    @Volatile
    private var lastCsUrlsFetchMs = 0L
    private val firebaseMutex = Mutex()
    private val csUrlsMutex = Mutex()

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

    private fun currentTimeoutSec(defaultSec: Long = 25L): Long {
        val ctx = ctxOrNull() ?: return defaultSec
        return MovieFoxSettingsDialog.getTimeoutSeconds(ctx).toLong().coerceIn(10L, 60L)
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
        timeoutSec: Long = currentTimeoutSec(25L),
        retries: Int = 1,
        sourceTag: String = "HTTP",
        useCloudflareBypass: Boolean = false
    ): String? {
        val headers = directHeaders(url, extraHeaders)
        repeat(retries.coerceAtLeast(1)) { attempt ->
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
                } else if (attempt == retries - 1) {
                    val statusTag = if (res.code in listOf(403, 503)) "CF ${res.code}" else "HTTP ${res.code}"
                    MovieFoxSettingsDialog.reportServerStatus(sourceTag, statusTag, false, res.code, ms, url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == retries - 1) {
                    val isTimeout = e.message?.contains("timeout", true) == true ||
                        e.javaClass.simpleName.contains("Timeout", true) ||
                        e.javaClass.simpleName.contains("InterruptedIO", true)
                    if (isTimeout) {
                        MovieFoxSettingsDialog.logTimeout(sourceTag, url, timeoutSec, "attempt=${attempt + 1}")
                    } else {
                        logError(sourceTag, url, e, "attempt=${attempt + 1}")
                    }
                }
            }
            if (attempt < retries - 1) delay(250L)
        }
        return null
    }

    private suspend fun getFirebaseDomain(key: String): String? {
        val now = System.currentTimeMillis()
        if (firebaseDomainsCache.isEmpty() || now - lastFirebaseFetchMs > 5 * 60 * 1000L) {
            firebaseMutex.withLock {
                val curNow = System.currentTimeMillis()
                if (firebaseDomainsCache.isEmpty() || curNow - lastFirebaseFetchMs > 5 * 60 * 1000L) {
                    withTimeoutOrNull(12_000L) {
                        runCatching {
                            val raw = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json", timeout = 12L).text
                            val obj = JSONObject(raw)
                            obj.keys().forEach { k ->
                                val v = obj.optString(k, "").trim().trim('"', '\'').trim().trimEnd('/')
                                if (v.isNotBlank()) firebaseDomainsCache[k] = v
                            }
                            lastFirebaseFetchMs = System.currentTimeMillis()
                        }.onFailure {
                            MovieFoxSettingsDialog.logParsing("Firebase", "rtdb", "DOMAIN_JSON", it)
                        }
                    }
                }
            }
        }
        return firebaseDomainsCache[key] ?: firebaseDomainsCache["${key}_url"] ?: firebaseDomainsCache["${key}_domain"]
    }

    private suspend fun getCsDynamicUrl(key: String, fallback: String): String {
        val now = System.currentTimeMillis()
        if (csDynamicUrlsCache.isEmpty() || now - lastCsUrlsFetchMs > 10 * 60 * 1000L) {
            csUrlsMutex.withLock {
                val curNow = System.currentTimeMillis()
                if (csDynamicUrlsCache.isEmpty() || curNow - lastCsUrlsFetchMs > 10 * 60 * 1000L) {
                    withTimeoutOrNull(12_000L) {
                        runCatching {
                            val raw = app.get(csUrlsJsonUrl, timeout = 12L).text
                            val obj = JSONObject(raw)
                            obj.keys().forEach { k ->
                                val v = obj.optString(k, "").trim().trimEnd('/')
                                if (v.isNotBlank()) csDynamicUrlsCache[k] = v
                            }
                            lastCsUrlsFetchMs = System.currentTimeMillis()
                        }.onFailure {
                            MovieFoxSettingsDialog.logParsing("CineStream", csUrlsJsonUrl, "URLS_JSON", it)
                        }
                    }
                }
            }
        }
        return csDynamicUrlsCache[key]?.takeIf { it.isNotBlank() } ?: fallback
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

    private fun slugify(raw: String?): String? {
        return raw?.filter { it.isWhitespace() || it.isLetterOrDigit() }
            ?.trim()
            ?.replace("\\s+".toRegex(), "-")
            ?.lowercase()
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

        val raw = safeGetText(endpoint, timeoutSec = currentTimeoutSec(25L), sourceTag = "TMDB").orEmpty()
        val results = runCatching { JSONObject(raw).optJSONArray("results") }.onFailure {
            if (raw.isNotBlank()) MovieFoxSettingsDialog.logParsing("TMDB", endpoint, "HOME_JSON", it)
        }.getOrNull() ?: return emptyList()

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
        val timeoutSec = currentTimeoutSec(25L)
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
        val searchStart = System.currentTimeMillis()
        val timeoutSec = currentTimeoutSec(25L)
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
            if (parsed.isNotEmpty()) {
                MovieFoxSettingsDialog.logSearch("moviefox", query, parsed.size, System.currentTimeMillis() - searchStart)
                return parsed
            }
        }

        val tmdbUrl = "$tmdbBaseUrl/search/multi?api_key=$tmdbApiKey&query=$encoded&page=1"
        val tmdbRaw = safeGetText(tmdbUrl, timeoutSec = timeoutSec, sourceTag = "TMDB").orEmpty()
        val results = runCatching { JSONObject(tmdbRaw).optJSONArray("results") }.onFailure {
            if (tmdbRaw.isNotBlank()) MovieFoxSettingsDialog.logParsing("TMDB", tmdbUrl, "SEARCH_JSON", it)
        }.getOrNull() ?: return emptyList()

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
        MovieFoxSettingsDialog.logSearch("TMDB", query, fallbackList.size, System.currentTimeMillis() - searchStart, "fallback")
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
        val timeoutSec = currentTimeoutSec(25L)
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
            val tmdbEndpoint = "$tmdbBaseUrl/$tmdbPath?api_key=$tmdbApiKey&append_to_response=external_ids"
            val tmdbJsonStr = safeGetText(tmdbEndpoint, timeoutSec = timeoutSec, sourceTag = "TMDB").orEmpty()
            val obj = runCatching { JSONObject(tmdbJsonStr) }.onFailure {
                if (tmdbJsonStr.isNotBlank()) MovieFoxSettingsDialog.logParsing("TMDB", tmdbEndpoint, "LOAD_JSON", it)
            }.getOrNull() ?: JSONObject()

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
            h.contains("vcloud") && isExtOn("hubcloud") -> VCloud()
            h.contains("hubcloud") && isExtOn("hubcloud") -> KatHubCloudExtractor()
            h.contains("gamerxyt") && isExtOn("hubcloud") -> GamerXYTExtractor()
            h.contains("gdflix") && isExtOn("gdflix") -> KatGDFlixExtractor()
            h.contains("gdlink") && isExtOn("gdflix") -> GDLink()
            h.contains("fastdlserver") && isExtOn("gdflix") -> KatFastDlServer()
            h.contains("fuckingfast") && isExtOn("buzz_hb_go") -> KatFuckingFastExtractor()
            h.contains("hbplay.pages.dev") && isExtOn("buzz_hb_go") -> KatHBPlayExtractor()
            h.contains("goflix.sbs") && isExtOn("buzz_hb_go") -> KatGoFlixExtractor()
            h.contains("voe.") && isExtOn("voe") -> VoeExtractor()
            (h.contains("streamtape") || h.contains("stape.") || h.contains("strtape") || h.contains("shavetape")) && isExtOn("streamtape") -> StreamTapeExtractor()
            h.contains("nxstreamhub") && isExtOn("basejwp") -> NxStreamHubExtractor()
            h.contains("itsnitrox") && isExtOn("basejwp") -> NitroxExtractor()
            h.contains("vibuxer") && isExtOn("basejwp") -> VibuxerExtractor()
            h.contains("vidprime") && isExtOn("basejwp") -> VidPrimeExtractor()
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

        val extCapMs = (lc.reqTimeoutSec * 1000L).coerceAtLeast(25_000L)
        val custom = pickCustomExtractor(targetUrl)
        var any = false
        if (custom != null) {
            withTimeoutOrNull(extCapMs) {
                runCatching {
                    custom.getUrl(targetUrl, referer, emitSub) { ex ->
                        any = true
                        wrappedCb(ex)
                    }
                }.onFailure {
                    MovieFoxSettingsDialog.logParsing(sourceTag, targetUrl, "EXTRACTOR", it)
                }
            }
        }
        if (!any) {
            withTimeoutOrNull(extCapMs) {
                runCatching {
                    loadExtractor(targetUrl, referer, emitSub, wrappedCb)
                }
            }
        }
    }

    // =========================================================================
    // CUSTOM NUVIO / STREMIO MANIFEST STREAM FETCHER
    // =========================================================================
    private suspend fun fetchFromNuvioManifest(
        srv: MovieFoxSettingsDialog.ServerItem,
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.imdbId.isBlank()) return
        val base = srv.probeUrl.substringBefore("/manifest.json").trimEnd('/')
        if (!base.startsWith("http")) return
        val streamUrl = if (lc.isTv) {
            "$base/stream/series/${lc.imdbId}:${lc.season}:${lc.episode}.json"
        } else {
            "$base/stream/movie/${lc.imdbId}.json"
        }
        val raw = safeGetText(streamUrl, timeoutSec = lc.reqTimeoutSec, retries = 2, sourceTag = srv.key).orEmpty()
        if (raw.isBlank()) return

        val streamsArr = runCatching { JSONObject(raw).optJSONArray("streams") }.onFailure {
            MovieFoxSettingsDialog.logParsing(srv.name, streamUrl, "MANIFEST_STREAM", it)
        }.getOrNull() ?: return

        for (i in 0 until streamsArr.length()) {
            val s = streamsArr.optJSONObject(i) ?: continue
            val mediaUrl = s.optString("url", "").trim()
            if (!mediaUrl.startsWith("http") || mediaUrl.contains("github.com", true)) continue

            val sTitle = s.optString("description").ifBlank { s.optString("title") }.ifBlank { s.optString("name", "Stream") }
            val hints = s.optJSONObject("behaviorHints")
            val proxyReq = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            val stdHdrs = hints?.optJSONObject("headers")

            val hdrs = mutableMapOf<String, String>()
            val ref = proxyReq?.optString("Referer").orEmpty()
                .ifBlank { stdHdrs?.optString("Referer").orEmpty() }
                .ifBlank { stdHdrs?.optString("referer").orEmpty() }
            val origin = proxyReq?.optString("Origin").orEmpty()
                .ifBlank { stdHdrs?.optString("Origin").orEmpty() }
                .ifBlank { stdHdrs?.optString("origin").orEmpty() }
            val ua = proxyReq?.optString("User-Agent").orEmpty()
                .ifBlank { stdHdrs?.optString("User-Agent").orEmpty() }
                .ifBlank { effectiveUa }

            hdrs["User-Agent"] = ua
            if (ref.isNotBlank()) hdrs["Referer"] = ref
            if (origin.isNotBlank()) hdrs["Origin"] = origin

            val qVal = getQualityFromName(sTitle).takeIf { it > 0 } ?: Qualities.P1080.value
            val lType = when {
                mediaUrl.contains(".m3u8", true) || sTitle.contains("hls", true) -> ExtractorLinkType.M3U8
                mediaUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            if (lc.seenUrls.add(mediaUrl)) {
                emitLink(
                    newExtractorLink(
                        source = srv.name,
                        name = "${srv.name} [$sTitle]",
                        url = mediaUrl,
                        type = lType
                    ) {
                        this.referer = ref
                        this.quality = qVal
                        this.headers = hdrs
                    }
                )
            }
            s.optJSONArray("subtitles")?.let { subArr ->
                for (j in 0 until subArr.length()) {
                    val sub = subArr.optJSONObject(j) ?: continue
                    val su = sub.optString("url", "")
                    val sl = sub.optString("lang", "Sub")
                    if (su.startsWith("http")) emitSub(newSubtitleFile("${srv.name} • $sl", su))
                }
            }
        }
    }

    // =========================================================================
    // SERVER 1: PRMOVIES
    // =========================================================================
    private suspend fun fetchFromPrmovies(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val sStart = System.currentTimeMillis()
        val q = lc.searchQueryTitle.trim().replace(" ", "+")
        val fbDom = getFirebaseDomain("prmovies")?.removeSuffix("/")
        val domains = (listOfNotNull(fbDom, activePrmoviesBase) + prmoviesMirrors).distinct().take(4)

        val winner = supervisorScope {
            val jobs = domains.map { dom ->
                async(Dispatchers.IO) {
                    val html = safeGetText(
                        "$dom/?s=$q",
                        mapOf("Referer" to "$dom/"),
                        timeoutSec = lc.reqTimeoutSec,
                        retries = 1,
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
        val matchedPages = searchDoc.select(".ml-item a.ml-mask[href]")
            .filter { a ->
                val t = (a.attr("oldtitle").ifBlank { a.attr("title") }.ifBlank { a.text() }).trim()
                titleMatchesCandidate(t, lc.pageTitle)
            }
            .map { normalizeUrl(it.attr("href"), dom) }
            .distinct()
            .take(2)
        MovieFoxSettingsDialog.logSearch("prmovies", lc.searchQueryTitle, matchedPages.size, System.currentTimeMillis() - sStart, dom)
        if (matchedPages.isEmpty()) return

        for (matchedPageUrl in matchedPages) {
            val detailHtml = safeGetText(
                matchedPageUrl,
                mapOf("Referer" to "$dom/"),
                timeoutSec = lc.reqTimeoutSec,
                retries = 1,
                sourceTag = "prmovies",
                useCloudflareBypass = true
            ).orEmpty()
            if (detailHtml.isBlank()) continue
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
                        timeoutSec = lc.reqTimeoutSec,
                        retries = 1,
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

                when {
                    cleanEmbed.contains("speedostream", true) -> {
                        val host = runCatching { URI(cleanEmbed).host }.getOrNull() ?: "speedostream1.com"
                        val rText = safeGetText(cleanEmbed, mapOf("Referer" to "https://$host/"), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "prmovies").orEmpty()
                        if (rText.isNotBlank() && !rText.contains("Embeds disabled", true)) {
                            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(rText)?.groupValues?.get(1)
                                ?: Regex("""sources\s*:\s*\[\s*\{?\s*file\s*:\s*["']([^"']+)["']""").find(rText)?.groupValues?.get(1)
                            if (!m3u8.isNullOrBlank()) {
                                dispatchEmbedOrDirect("Prmovies", label, m3u8, "https://$host/", lc, emitSub, emitLink)
                            }
                        }
                    }
                    else -> dispatchEmbedOrDirect("Prmovies", label, cleanEmbed, targetUrl, lc, emitSub, emitLink)
                }
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
        if (mediaId.length >= 6) streamPages.add("https://xcloud.tattoo/stream_$mediaId")

        for (pageUrl in streamPages) {
            val rawHtml = safeGetText(pageUrl, mapOf("Referer" to xcloudReferer), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "dramaverse").orEmpty()
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
        val sStart = System.currentTimeMillis()
        val q = safeEncode(lc.searchQueryTitle)
        val searchHtml = safeGetText(
            "$dramaVerseBase/?s=$q",
            mapOf("Referer" to "$dramaVerseBase/"),
            timeoutSec = lc.reqTimeoutSec,
            retries = 1,
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
        }
        MovieFoxSettingsDialog.logSearch("dramaverse", lc.searchQueryTitle, if (matchedUrl != null) 1 else 0, System.currentTimeMillis() - sStart)
        if (matchedUrl == null) return

        val detailHtml = safeGetText(
            matchedUrl,
            mapOf("Referer" to "$dramaVerseBase/"),
            timeoutSec = lc.reqTimeoutSec,
            retries = 1,
            sourceTag = "dramaverse",
            useCloudflareBypass = true
        ).orEmpty()
        if (detailHtml.isBlank()) return
        val doc = Jsoup.parse(detailHtml, matchedUrl)

        val btnSlides = doc.select(
            "a.btn-slide, .entry-content a[href*='sohojgyan'], .entry-content a[href*='blogspot'], " +
                ".entry-content a[href*='xcloud'], .entry-content a[href*='pixeldrain'], " +
                ".entry-content a[href*='drive.google.com'], .entry-content a[href*='gdflix'], .entry-content a[href*='hubcloud']"
        )

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
        val sStart = System.currentTimeMillis()
        val q = safeEncode(lc.searchQueryTitle)
        val searchHtml = safeGetText("$hindiDramaBase/search.php?q=$q", mapOf("Referer" to "$hindiDramaBase/"), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "hindidrama").orEmpty()
        if (searchHtml.isBlank()) return

        val searchDoc = Jsoup.parse(searchHtml, hindiDramaBase)
        val matchedUrl = searchDoc.select(".grid .card a, .ac a.it").firstOrNull { anchor ->
            val t = anchor.selectFirst("h3, .t")?.text()?.trim().orEmpty().ifEmpty { anchor.attr("title").trim() }
            titleMatchesCandidate(t, lc.pageTitle)
        }?.attr("href")?.let { normalizeUrl(it, hindiDramaBase) }
        MovieFoxSettingsDialog.logSearch("hindidrama", lc.searchQueryTitle, if (matchedUrl != null) 1 else 0, System.currentTimeMillis() - sStart)
        if (matchedUrl == null) return

        val detailHtml = safeGetText(matchedUrl, mapOf("Referer" to "$hindiDramaBase/"), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "hindidrama").orEmpty()
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
            safeGetText(watchPageUrl, mapOf("Referer" to matchedUrl), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "hindidrama").orEmpty()
        }
        if (pageHtml.isBlank()) return
        val doc = Jsoup.parse(pageHtml, watchPageUrl)

        val internalEmbedSrc = doc.selectFirst("#player iframe, iframe[src*='/embed/']")?.attr("src")?.trim()
            ?: Regex("""["']((?:https?:\\?/\\?/[^"'\s<>]+)?\\?/embed\\?/[^"'\s<>]+)["']""")
                .find(pageHtml)?.groupValues?.get(1)?.replace("\\/", "/")

        val playerPageUrl = if (!internalEmbedSrc.isNullOrEmpty()) normalizeUrl(internalEmbedSrc, hindiDramaBase) else watchPageUrl
        val playerHtml = if (!internalEmbedSrc.isNullOrEmpty()) {
            safeGetText(playerPageUrl, mapOf("Referer" to watchPageUrl), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "hindidrama").orEmpty()
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

        for ((rawEmbedUrl, label) in serverList) {
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
        val sStart = System.currentTimeMillis()
        val q = safeEncode(lc.searchQueryTitle)
        val searchHtml = safeGetText(
            "$katDramaBase/?s=$q",
            mapOf("Referer" to "$katDramaBase/"),
            timeoutSec = lc.reqTimeoutSec,
            retries = 1,
            sourceTag = "katdrama",
            useCloudflareBypass = true
        ).orEmpty()
        if (searchHtml.isBlank()) return

        val searchDoc = Jsoup.parse(searchHtml, katDramaBase)
        val matchedUrl = searchDoc.select("article.latest-card h3.latest-title a, article.post h3 a, article.post h2 a")
            .firstOrNull { titleMatchesCandidate(it.text(), lc.pageTitle) }
            ?.attr("href")?.let { normalizeUrl(it, katDramaBase) }
        MovieFoxSettingsDialog.logSearch("katdrama", lc.searchQueryTitle, if (matchedUrl != null) 1 else 0, System.currentTimeMillis() - sStart)
        if (matchedUrl == null) return

        val pageHtml = safeGetText(
            matchedUrl,
            mapOf("Referer" to "$katDramaBase/"),
            timeoutSec = lc.reqTimeoutSec,
            retries = 1,
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

        for (el in mainArticle.select("*")) {
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
                        dispatchEmbedOrDirect("KatDrama", qualFromLink, href, matchedUrl, lc, emitSub, emitLink, getQualityFromName(qualFromLink))
                    }
                }
            }
        }
    }

    // =========================================================================
    // SERVER 5: SFLIX (Play + Download endpoints, Top 3 Subject Matches)
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
            val body = safeGetText(subLink, subHeaders, timeoutSec = currentTimeoutSec(25L), retries = 1, sourceTag = "sflix").orEmpty()
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
            if (item.optBoolean("vipLocked", false)) continue
            val streamUrl = item.safeStr("url")
            if (streamUrl.isBlank() || !lc.seenUrls.add(streamUrl)) continue

            val signCookie = item.safeStr("signCookie")
            val signHeaderKey = item.safeStr("signHeaderKey", "X-MB-Token")
            val resolutions = item.safeStr("resolutions")
            val streamId = item.safeStr("id", subjectId)
            val maxRes = resolutions.split(",").mapNotNull { it.trim().toIntOrNull() }.maxOrNull()
                ?: item.optInt("resolution", 720).takeIf { it > 0 } ?: 720

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
        val sStart = System.currentTimeMillis()
        val savedHost = ctxOrNull()?.let { MovieFoxSettingsDialog.getSflixHost(it) }
        val activeHost = if (!savedHost.isNullOrBlank() && savedHost in sflixDomains) savedHost else sflixDomains.first()
        val searchUrl = "$activeHost/search-result?keyword=${safeEncode(lc.searchQueryTitle)}"
        val sHeaders = mapOf(
            "Referer" to "$activeHost/",
            "User-Agent" to effectiveUa,
            "Cookie" to sflixCookies
        )

        val searchHtml = safeGetText(searchUrl, sHeaders, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "sflix").orEmpty()
        if (searchHtml.isBlank()) return
        val doc = Jsoup.parse(searchHtml, activeHost)

        val matchedPairs = mutableListOf<Pair<String, String>>()
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
                        matchedPairs.add(sid to path)
                    }
                }
            }.onFailure { MovieFoxSettingsDialog.logParsing("sflix", searchUrl, "NUXT_DATA", it) }
        }
        val uniqueMatches = matchedPairs.distinctBy { it.first }.take(3)
        MovieFoxSettingsDialog.logSearch("sflix", lc.searchQueryTitle, uniqueMatches.size, System.currentTimeMillis() - sStart)
        if (uniqueMatches.isEmpty()) return

        val se = if (lc.isTv) lc.season else 0
        val ep = if (lc.isTv) lc.episode else 0

        for ((subjectId, detailPath) in uniqueMatches) {
            var any = false
            for (domain in (listOf(activeHost) + sflixDomains).distinct()) {
                val baseQuery = "subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath" +
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

                val playText = safeGetText("$domain/wefeed-h5api-bff/subject/play?$baseQuery", webHeaders, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "sflix").orEmpty()
                val playData = runCatching { JSONObject(playText).optJSONObject("data") }.getOrNull()
                if (playData != null) {
                    if (emitSflixArray(playData.optJSONArray("dash"), "DASH", subjectId, lc, emitSub, emitLink)) any = true
                    if (emitSflixArray(playData.optJSONArray("hls"), "HLS", subjectId, lc, emitSub, emitLink)) any = true
                    if (emitSflixArray(playData.optJSONArray("streams"), "MP4", subjectId, lc, emitSub, emitLink)) any = true
                }

                if (!any) {
                    val dlText = safeGetText("$domain/wefeed-h5api-bff/subject/download?$baseQuery", webHeaders, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "sflix").orEmpty()
                    val dlData = runCatching { JSONObject(dlText).optJSONObject("data") }.getOrNull()
                    if (dlData != null && emitSflixArray(dlData.optJSONArray("downloads"), "DL", subjectId, lc, emitSub, emitLink)) {
                        any = true
                    }
                }
                if (any) break
            }
            if (any) break
        }
    }

    // =========================================================================
    // SERVER 6: PLAY4U (__PA_PLAY__ + __PA_DATA__ + raw pa!~ / m3u8 fallback)
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
            val text = out.toString().trim()
            if (text.isEmpty() || text.any { it.code < 9 }) "" else text
        }.getOrDefault("")
    }

    private fun p4uExtractVar(html: String, varName: String): String? {
        val markers = listOf("window.$varName = ", "window.$varName=", "$varName = ", "$varName=")
        val at = markers.map { html.indexOf(it) }.firstOrNull { it >= 0 } ?: return null
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
        val sStart = System.currentTimeMillis()
        getFirebaseDomain("play4u")?.takeIf { it.contains("play4u", true) }?.let { activePlay4uBase = it }

        val p4uHeaders = mapOf(
            "User-Agent" to p4uUa,
            "Accept" to "text/html,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$activePlay4uBase/"
        )
        val searchUrl = "$activePlay4uBase/api/search.php?q=${safeEncode(lc.searchQueryTitle)}&page=1&per_page=15"
        val searchJson = safeGetText(searchUrl, p4uHeaders, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "play4u").orEmpty()
        if (searchJson.isBlank()) return

        val pageObj = runCatching { parseJson<P4uSearchPage>(searchJson) }.onFailure {
            MovieFoxSettingsDialog.logParsing("play4u", searchUrl, "SEARCH_JSON", it)
        }.getOrNull() ?: return
        val matched = pageObj.items.firstOrNull { item ->
            !item.watch.isNullOrBlank() && titleMatchesCandidate(item.title, lc.pageTitle)
        }
        MovieFoxSettingsDialog.logSearch("play4u", lc.searchQueryTitle, if (matched != null) 1 else 0, System.currentTimeMillis() - sStart)
        if (matched == null) return

        val watchPath = matched.watch.orEmpty()
        val pageUrl = normalizeUrl(watchPath, activePlay4uBase)
        val pageHtml = safeGetText(pageUrl, p4uHeaders, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "play4u").orEmpty()
        if (pageHtml.isBlank()) return

        val sealKey = p4uSealKeyRegex.find(pageHtml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: p4uDefaultSealKey
        val streamHeaders = mapOf("Referer" to "$activePlay4uBase/", "Origin" to activePlay4uBase)

        if (lc.isTv && !watchPath.contains("/watch/")) {
            val series = p4uExtractVar(pageHtml, "__PA_SERIES__")?.let {
                runCatching { parseJson<P4uSeriesPayload>(it) }.getOrNull()
            }
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
                return
            }
        }

        val playJson = p4uExtractVar(pageHtml, "__PA_PLAY__") ?: p4uExtractVar(pageHtml, "__PA_DATA__")
        val play = playJson?.let { runCatching { parseJson<P4uPlayPayload>(it) }.getOrNull() }
        var emitted = false
        if (play != null && play.streams.isNotEmpty()) {
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
                emitted = true
                dispatchEmbedOrDirect("Play4u", label, link, "$activePlay4uBase/", lc, emitSub, emitLink, topQ, streamHeaders)
            }
        }

        if (!emitted) {
            Regex("""["'](pa!~[A-Za-z0-9+/_=-]+)["']""").findAll(pageHtml).forEach { m ->
                val unsealed = p4uUnseal(m.groupValues[1], sealKey)
                val full = normalizeUrl(unsealed, activePlay4uBase)
                if (full.startsWith("http") && (full.contains(".m3u8") || full.contains(".mp4"))) {
                    emitted = true
                    dispatchEmbedOrDirect("Play4u", "HLS", full, "$activePlay4uBase/", lc, emitSub, emitLink, Qualities.P1080.value, streamHeaders)
                }
            }
        }
        if (!emitted) {
            Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""").findAll(pageHtml).take(4).forEach { m ->
                val clean = m.value.replace("\\/", "/")
                dispatchEmbedOrDirect("Play4u", "Stream", clean, "$activePlay4uBase/", lc, emitSub, emitLink, Qualities.P1080.value, streamHeaders)
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
        val sStart = System.currentTimeMillis()
        getFirebaseDomain("animeworldindia")?.takeIf { it.contains("animeworld", true) }?.let { activeAnimeWorldBase = it }

        val awHeaders = mapOf(
            "User-Agent" to awBrowserUa,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$activeAnimeWorldBase/"
        )
        val searchHtml = safeGetText(
            "$activeAnimeWorldBase/page/1/?s=${safeEncode(lc.searchQueryTitle)}",
            awHeaders,
            timeoutSec = lc.reqTimeoutSec,
            retries = 1,
            sourceTag = "animeworld",
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
        }
        MovieFoxSettingsDialog.logSearch("animeworld", lc.searchQueryTitle, if (matchedUrl != null) 1 else 0, System.currentTimeMillis() - sStart)
        if (matchedUrl == null) return

        val detailHtml = safeGetText(
            matchedUrl,
            awHeaders,
            timeoutSec = lc.reqTimeoutSec,
            retries = 1,
            sourceTag = "animeworld",
            useCloudflareBypass = true
        ).orEmpty()
        if (detailHtml.isBlank()) return
        val detailDoc = Jsoup.parse(detailHtml, matchedUrl)

        var watchPageUrl = matchedUrl
        if (lc.isTv && !matchedUrl.contains("/movies/")) {
            val epLinks = detailDoc.select("a[href*=/episode/]")
                .map { normalizeUrl(it.attr("href"), activeAnimeWorldBase) }
                .distinct()

            val matchedEp = epLinks.firstOrNull { href ->
                val m = Regex("""(\d+)x(\d+)""").find(href)
                val sNum = m?.groupValues?.get(1)?.toIntOrNull()
                val eNum = m?.groupValues?.get(2)?.toIntOrNull()
                eNum == lc.episode && (sNum == null || sNum == lc.season)
            }

            if (matchedEp != null) {
                watchPageUrl = matchedEp
            } else {
                val postId = detailDoc.select("[data-post]").firstOrNull()?.attr("data-post").orEmpty()
                if (postId.isNotBlank()) {
                    val ajaxUrl = "$activeAnimeWorldBase/wp-admin/admin-ajax.php?action=action_select_season&season=${lc.season}&post=$postId"
                    val ajaxHtml = safeGetText(
                        ajaxUrl,
                        awHeaders + ("X-Requested-With" to "XMLHttpRequest"),
                        timeoutSec = lc.reqTimeoutSec,
                        retries = 1,
                        sourceTag = "animeworld"
                    ).orEmpty()
                    val ajaxEpLinks = Jsoup.parse(ajaxHtml, activeAnimeWorldBase)
                        .select("a[href*=/episode/]")
                        .map { normalizeUrl(it.attr("href"), activeAnimeWorldBase) }
                        .distinct()
                    ajaxEpLinks.firstOrNull { href ->
                        val m = Regex("""(\d+)x(\d+)""").find(href)
                        val eNum = m?.groupValues?.get(2)?.toIntOrNull()
                        eNum == lc.episode || ajaxEpLinks.size == 1
                    }?.let { watchPageUrl = it }
                }
            }
        }

        val pageHtml = if (watchPageUrl == matchedUrl) {
            detailHtml
        } else {
            safeGetText(
                watchPageUrl,
                awHeaders,
                timeoutSec = lc.reqTimeoutSec,
                retries = 1,
                sourceTag = "animeworld",
                useCloudflareBypass = true
            ).orEmpty()
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
                val endpoint = "$playerBase/player/index.php?data=$videoId&do=getVideo"

                val postRes = try {
                    app.post(
                        endpoint,
                        data = mapOf("hash" to videoId, "r" to "$activeAnimeWorldBase/"),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to playerBase,
                            "Referer" to "$playerBase/video/$videoId",
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "User-Agent" to awBrowserUa
                        ),
                        timeout = lc.reqTimeoutSec
                    )
                } catch (e: Exception) {
                    val isTimeout = e.message?.contains("timeout", true) == true ||
                        e.javaClass.simpleName.contains("Timeout", true) ||
                        e.javaClass.simpleName.contains("InterruptedIO", true)
                    if (isTimeout) {
                        MovieFoxSettingsDialog.logTimeout("animeworld", endpoint, lc.reqTimeoutSec, "GET_VIDEO")
                    } else {
                        logError("animeworld", endpoint, e, "GET_VIDEO")
                    }
                    continue
                }

                val vid = runCatching { parseJson<AwZephyrixVideo>(postRes.text) }.onFailure {
                    MovieFoxSettingsDialog.logParsing("animeworld", iframe, "ZEPHYRIX_JSON", it)
                }.getOrNull() ?: continue

                val rawStream = vid.videoSource ?: vid.securedLink ?: continue
                val streamUrl = normalizeUrl(rawStream, playerBase)
                if (streamUrl.startsWith("http")) {
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
    }

    // =========================================================================
    // SERVER 8: THEMOVIESFLIX (themoviesflix.eat + Fallback Group Parser)
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
        if (!fb.isNullOrBlank()) {
            activeTmfBase = fb
            return fb
        }
        return activeTmfBase
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

    private suspend fun tmfFetchDriveOnce(rawUrl: String, referer: String, timeoutSec: Long = currentTimeoutSec(25L)): Document? {
        val jar = mutableMapOf<String, String>()
        var current = rawUrl
        val seen = mutableSetOf<String>()
        repeat(10) {
            if (!seen.add(current)) return null
            val res = runCatching {
                app.get(current, headers = tmfHeaders(referer), cookies = jar, allowRedirects = false, timeout = timeoutSec)
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

    private suspend fun tmfFetchDrivePage(url: String, referer: String, timeoutSec: Long = currentTimeoutSec(25L)): TmfDriveData? {
        val key = url.replace("nexdrive.beer", "mobilejsr.best").replace("nexdrive.fit", "mobilejsr.best")
        tmfDriveCache[key]?.let { (ts, cached) ->
            if (System.currentTimeMillis() - ts < 5 * 60 * 1000L) return cached
        }
        return tmfDriveMutex.withLock {
            tmfDriveCache[key]?.let { (ts, cached) ->
                if (System.currentTimeMillis() - ts < 5 * 60 * 1000L) return cached
            }
            val mirror = if (key.contains("mobilejsr.best")) key.replace("mobilejsr.best", "nexdrive.beer") else key
            val parsed = withTimeoutOrNull((timeoutSec * 1000L).coerceAtLeast(25_000L)) {
                supervisorScope {
                    val j1 = async(Dispatchers.IO) { tmfFetchDriveOnce(key, referer, timeoutSec) }
                    val j2 = async(Dispatchers.IO) { tmfFetchDriveOnce(mirror, referer, timeoutSec) }
                    val doc = j1.await() ?: j2.await()
                    j1.cancel()
                    j2.cancel()
                    doc?.let { tmfParseDriveDoc(it) }
                }
            }
            tmfDriveCache[key] = System.currentTimeMillis() to parsed
            parsed
        }
    }

    private suspend fun tmfRedirectOf(url: String, referer: String?, timeoutSec: Long = currentTimeoutSec(25L)): String? {
        var current = url
        repeat(6) {
            val res = runCatching {
                app.get(current, headers = tmfHeaders(referer), allowRedirects = false, timeout = timeoutSec)
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

    private suspend fun tmfResolveFastDl(url: String, timeoutSec: Long = currentTimeoutSec(25L)): List<TmfStreamItem> {
        val text = safeGetText(url, tmfHeaders(), timeoutSec = timeoutSec, retries = 1, sourceTag = "themoviesflix").orEmpty()
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

    private suspend fun tmfResolveVegaDrive(url: String, timeoutSec: Long = currentTimeoutSec(25L)): List<TmfStreamItem> {
        val html = safeGetText(url, tmfHeaders(), timeoutSec = timeoutSec, retries = 1, sourceTag = "themoviesflix").orEmpty()
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, url)
        val token = url.substringAfter("/s/").substringBefore("?").trimEnd('/')
        val vegaBase = doc.select("a[href]").map { it.attr("href") }
            .firstOrNull { it.contains("/d/") || it.contains("/go/") }
            ?.let { runCatching { URI(it).let { u -> "${u.scheme}://${u.host}" } }.getOrNull() }
            ?: "https://one.vegadrive.me"

        val out = mutableListOf<TmfStreamItem>()
        val drop = tmfRedirectOf("$vegaBase/go/$token/skydrop", "$vegaBase/", timeoutSec)
        if (drop != null && drop.contains("googleusercontent") && !drop.substringAfterLast("/").contains(".zip", true)) {
            out.add(TmfStreamItem("V-Drive Vegadrop (10Gbps)", drop, ExtractorLinkType.VIDEO))
        }
        val pixel = tmfRedirectOf("$vegaBase/go/$token/pixeldrain", "$vegaBase/", timeoutSec)
        val pHost = pixel?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }.orEmpty()
        if (pixel != null && pHost.contains("pixeldrain")) {
            val id = pixel.substringBefore("?").substringBefore("#").substringAfterLast("/")
            if (id.isNotBlank()) out.add(TmfStreamItem("V-Drive Pixeldrain", "https://pixeldrain.com/api/file/$id", ExtractorLinkType.VIDEO))
        }
        return out.distinctBy { it.url }
    }

    private suspend fun tmfResolveFilePress(url: String, timeoutSec: Long = currentTimeoutSec(25L)): List<TmfStreamItem> =
        withTimeoutOrNull((timeoutSec * 1000L).coerceAtLeast(25_000L)) {
            val id = tmfFilePressIdRegex.find(url)?.groupValues?.get(1) ?: return@withTimeoutOrNull emptyList()
            val apiBase = "https://filebee.xyz/api"
            val infoText = safeGetText("$apiBase/file/get/$id", tmfHeaders("https://filebee.xyz/"), timeoutSec = timeoutSec, retries = 1, sourceTag = "themoviesflix").orEmpty()
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
                        timeout = timeoutSec
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
        val sStart = System.currentTimeMillis()
        val dom = tmfDomain()
        val searchHtml = safeGetText(
            "$dom/?s=${safeEncode(lc.searchQueryTitle)}",
            tmfHeaders(),
            timeoutSec = lc.reqTimeoutSec.coerceAtMost(12L),
            retries = 1,
            sourceTag = "themoviesflix",
            useCloudflareBypass = true
        ).orEmpty()
        if (searchHtml.isBlank()) return

        val searchDoc = Jsoup.parse(searchHtml, dom)
        val matchedUrl = searchDoc.select("article.latestpost a[id=featured-thumbnail]").firstNotNullOfOrNull { a ->
            val href = a.attr("href").trim()
            val tRaw = a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
            if (href.isNotEmpty() && titleMatchesCandidate(tRaw, lc.pageTitle)) normalizeUrl(href, dom) else null
        }
        MovieFoxSettingsDialog.logSearch("themoviesflix", lc.searchQueryTitle, if (matchedUrl != null) 1 else 0, System.currentTimeMillis() - sStart, dom)
        if (matchedUrl == null) return

        val detailHtml = safeGetText(
            matchedUrl,
            tmfHeaders(dom),
            timeoutSec = lc.reqTimeoutSec,
            retries = 1,
            sourceTag = "themoviesflix",
            useCloudflareBypass = true
        ).orEmpty()
        if (detailHtml.isBlank()) return

        val doc = Jsoup.parse(detailHtml, matchedUrl)
        val entry = doc.selectFirst("div.entry-content") ?: return

        data class TmfGroup(val label: String, val redirectUrl: String)
        var groups = entry.select("div.mfx-download-group").flatMap { div ->
            val label = div.selectFirst("h3")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (tmfPackRegex.containsMatchIn(label)) return@flatMap emptyList()
            div.select("a[href]").mapNotNull { a ->
                val txt = a.text().trim()
                val href = a.attr("href").trim()
                if (!href.startsWith("http") || txt.contains("Batch", true) || txt.contains("Zip", true) || tmfPackRegex.containsMatchIn(txt)) null
                else TmfGroup(label, href)
            }
        }.distinctBy { it.redirectUrl }

        if (groups.isEmpty()) {
            groups = entry.select("a[href]").mapNotNull { a ->
                val href = a.attr("href").trim()
                val txt = a.text().trim()
                val heading = a.parent()?.previousElementSibling()?.text().orEmpty()
                val label = "$heading $txt".trim()
                val isDriveOrHost = href.contains("nexdrive", true) || href.contains("mobilejsr", true) ||
                    href.contains("vglist", true) || href.contains("fastdl", true) ||
                    href.contains("hubcloud", true) || href.contains("gdflix", true) ||
                    href.contains("vegadrive", true) || href.contains("filebee", true)
                if (!href.startsWith("http") || !isDriveOrHost || txt.contains("Batch", true) || txt.contains("Zip", true) || tmfPackRegex.containsMatchIn(label)) {
                    null
                } else {
                    TmfGroup(label, href)
                }
            }.distinctBy { it.redirectUrl }
        }

        if (groups.isEmpty()) return

        val selectedGroups = if (lc.isTv) {
            val bySeason = groups.filter { g ->
                val sNum = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(g.label)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\bS(\d+)\b""", RegexOption.IGNORE_CASE).find(g.label)?.groupValues?.get(1)?.toIntOrNull()
                sNum == lc.season
            }
            bySeason.ifEmpty { groups }.take(2)
        } else {
            groups.take(2)
        }

        for (grp in selectedGroups) {
            val drive = tmfFetchDrivePage(grp.redirectUrl, matchedUrl, lc.reqTimeoutSec) ?: continue
            val hrefs = if (lc.isTv) drive.episodes[lc.episode].orEmpty() else drive.links
            if (hrefs.isEmpty()) continue
            val qHint = drive.quality ?: Qualities.P1080.value
            val suffix = if (drive.info.isBlank()) "" else " · ${drive.info}"

            for (href in hrefs.distinct().take(4)) {
                val resolvedItems = when {
                    href.contains("fastdl.", true) || href.contains("hubcdn.", true) -> tmfResolveFastDl(href, lc.reqTimeoutSec)
                    href.contains("vegadrive.", true) || href.contains("m-drive.", true) -> tmfResolveVegaDrive(href, lc.reqTimeoutSec)
                    href.contains("filebee.", true) || href.contains("filepress.", true) || href.contains("fpgo.", true) -> tmfResolveFilePress(href, lc.reqTimeoutSec)
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
    // SERVER 9: VEGAMOVIES (Parallel Domain Race -> V-Cloud FSL + Pixeldrain)
    // =========================================================================
    private suspend fun fetchFromVegaMovies(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank() && lc.imdbId.isBlank()) return
        val fbDom = getFirebaseDomain("vegamovies")?.removeSuffix("/")
        val csDom = getCsDynamicUrl("vegamovies", "").removeSuffix("/").takeIf { it.startsWith("http") }
        val candidates = (listOf(activeVegaBase) + vegaFallbackDomains + listOfNotNull(fbDom, csDom))
            .distinct()
            .take(5)

        val searchWinner = supervisorScope {
            val jobs = candidates.map { dom ->
                async(Dispatchers.IO) {
                    val sStart = System.currentTimeMillis()
                    val found = linkedSetOf<String>()
                    val tsQuery = lc.imdbId.ifBlank { lc.searchQueryTitle }
                    val tsJson = safeGetText(
                        "$dom/search.php?q=${safeEncode(tsQuery)}&page=1",
                        mapOf("Referer" to "$dom/"),
                        timeoutSec = lc.reqTimeoutSec.coerceAtMost(12L),
                        retries = 1,
                        sourceTag = "vegamovies",
                        useCloudflareBypass = true
                    ).orEmpty()
                    if (tsJson.startsWith("{")) {
                        runCatching {
                            val hits = JSONObject(tsJson).optJSONArray("hits")
                            if (hits != null) {
                                for (i in 0 until hits.length()) {
                                    val docObj = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                                    val perm = docObj.optString("permalink", "").trim()
                                    val postTitle = docObj.optString("post_title", "").replace("Download ", "", true).trim()
                                    val hitImdb = docObj.optString("imdb_id", "").trim()
                                    if (perm.isNotBlank() && ((lc.imdbId.isNotBlank() && hitImdb == lc.imdbId) || titleMatchesCandidate(postTitle, lc.pageTitle))) {
                                        found.add(normalizeUrl(perm, dom))
                                    }
                                }
                            }
                        }
                    }
                    if (found.isEmpty() && lc.imdbId.isNotBlank() && lc.searchQueryTitle.isNotBlank()) {
                        val tsTitleJson = safeGetText(
                            "$dom/search.php?q=${safeEncode(lc.searchQueryTitle)}&page=1",
                            mapOf("Referer" to "$dom/"),
                            timeoutSec = lc.reqTimeoutSec,
                            retries = 1,
                            sourceTag = "vegamovies",
                            useCloudflareBypass = true
                        ).orEmpty()
                        if (tsTitleJson.startsWith("{")) {
                            runCatching {
                                val hits = JSONObject(tsTitleJson).optJSONArray("hits")
                                if (hits != null) {
                                    for (i in 0 until hits.length()) {
                                        val docObj = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                                        val perm = docObj.optString("permalink", "").trim()
                                        val postTitle = docObj.optString("post_title", "").replace("Download ", "", true).trim()
                                        if (perm.isNotBlank() && titleMatchesCandidate(postTitle, lc.pageTitle)) {
                                            found.add(normalizeUrl(perm, dom))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    MovieFoxSettingsDialog.logSearch("vegamovies", lc.searchQueryTitle, found.size, System.currentTimeMillis() - sStart, dom)
                    if (found.isNotEmpty()) dom to found.first() else null
                }
            }
            var winner: Pair<String, String>? = null
            val pending = jobs.toMutableList()
            while (pending.isNotEmpty()) {
                val completed = kotlinx.coroutines.selects.select<Pair<Deferred<Pair<String, String>?>, Pair<String, String>?>> {
                    pending.forEach { job ->
                        job.onAwait { result -> job to result }
                    }
                }
                pending.remove(completed.first)
                if (completed.second != null) {
                    winner = completed.second
                    pending.forEach { it.cancel() }
                    break
                }
            }
            winner
        } ?: return

        val (dom, matchedUrl) = searchWinner
        activeVegaBase = dom

        val detailHtml = safeGetText(
            matchedUrl,
            mapOf("Referer" to "$dom/"),
            timeoutSec = lc.reqTimeoutSec,
            retries = 1,
            sourceTag = "vegamovies",
            useCloudflareBypass = true
        ).orEmpty()
        if (detailHtml.isBlank()) return

        val document = Jsoup.parse(detailHtml, matchedUrl)
        val vcloudTargets = linkedSetOf<String>()

        if (lc.isTv) {
            val hTags = document.select("main > h3:matches((?i)(4K|[0-9]*0p)), main > h5:matches((?i)(4K|[0-9]*0p)), h3:matches((?i)(4K|[0-9]*0p)), h4:matches((?i)(4K|[0-9]*0p))")
                .filter { !it.text().contains("Zip", true) }

            for (tag in hTags) {
                val realSeason = Regex("""(?:Season\s*|S)0*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(tag.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (realSeason != 0 && realSeason != lc.season) continue

                val pTag = tag.nextElementSibling()
                val aTags = if (pTag != null && pTag.tagName() == "p") pTag.select("a") else tag.select("a")
                val unilink = aTags.firstOrNull {
                    it.text().contains("V-Cloud", true) ||
                        it.text().contains("Episode", true) ||
                        it.text().contains("Download", true)
                } ?: aTags.firstOrNull { it.text().contains("G-Direct", true) }

                val eUrl = unilink?.attr("href")?.trim().orEmpty()
                if (eUrl.isBlank()) continue

                val epPageHtml = safeGetText(
                    normalizeUrl(eUrl, dom),
                    mapOf("Referer" to matchedUrl),
                    timeoutSec = lc.reqTimeoutSec,
                    retries = 1,
                    sourceTag = "vegamovies",
                    useCloudflareBypass = true
                ).orEmpty()
                if (epPageHtml.isBlank()) continue

                val epDoc = Jsoup.parse(epPageHtml, eUrl)
                val epVcloudList = epDoc.select("p > a, a[href]").mapNotNull { a ->
                    val href = a.attr("href").trim()
                    if (href.contains("vcloud", true) || href.contains("hubcloud", true)) href else null
                }.distinct()

                val targetIdx = (lc.episode - 1).coerceAtLeast(0)
                epVcloudList.getOrNull(targetIdx)?.let { vcloudTargets.add(it) }
            }
        } else {
            val buttons = document.select("a:has(button.dwd-button)")
            for (btn in buttons.take(5)) {
                val link = normalizeUrl(btn.attr("href"), dom)
                if (!link.startsWith("http")) continue
                val interHtml = safeGetText(
                    link,
                    mapOf("Referer" to matchedUrl),
                    timeoutSec = lc.reqTimeoutSec,
                    retries = 1,
                    sourceTag = "vegamovies",
                    useCloudflareBypass = true
                ).orEmpty()
                if (interHtml.isBlank()) continue

                val interDoc = Jsoup.parse(interHtml, link)
                val vcloudHref = interDoc.select("a:contains(V-Cloud), a[href*=vcloud], a[href*=hubcloud]")
                    .firstOrNull()?.attr("href")?.trim().orEmpty()
                if (vcloudHref.startsWith("http")) {
                    vcloudTargets.add(vcloudHref)
                }
            }
        }

        for (vLink in vcloudTargets.take(5)) {
            dispatchEmbedOrDirect("VegaMovies", "V-Cloud", vLink, matchedUrl, lc, emitSub, emitLink)
        }
    }

    // =========================================================================
    // SERVER 10: BOLLYFLIX (new1.bollyflix.tours + Season Filter First)
    // =========================================================================
    private suspend fun bypassBollyFlixSidexfee(id: String, timeoutSec: Long): String {
        val raw = safeGetText("https://web.sidexfee.com/?id=$id", timeoutSec = timeoutSec, retries = 1, sourceTag = "bollyflix").orEmpty()
        val encoded = Regex("""link":"([^"]+)""").find(raw)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty()
        if (encoded.isBlank()) return ""
        return runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrDefault("")
    }

    private suspend fun fetchFromBollyFlix(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank() && lc.imdbId.isBlank()) return
        val csDom = getCsDynamicUrl("bollyflix", "").removeSuffix("/").takeIf { it.startsWith("http") }
        val fbDom = getFirebaseDomain("bollyflix")?.removeSuffix("/")
        val candidates = (listOf(activeBollyFlixBase) + listOfNotNull(csDom, fbDom) + bollyFlixFallbackDomains)
            .distinct()
            .take(3)

        for (dom in candidates) {
            val sStart = System.currentTimeMillis()
            val queryStr = lc.imdbId.ifBlank { lc.searchQueryTitle }
            val searchHtml = safeGetText(
                "$dom/search/${safeEncode(queryStr)}/page/1/",
                mapOf("Referer" to "$dom/"),
                timeoutSec = lc.reqTimeoutSec.coerceAtMost(12L),
                retries = 1,
                sourceTag = "bollyflix",
                useCloudflareBypass = true
            ).orEmpty()

            var searchDoc = if (searchHtml.isNotBlank()) Jsoup.parse(searchHtml, dom) else null
            var articles = searchDoc?.select("div.post-cards > article, article").orEmpty()

            if (articles.isEmpty() && lc.imdbId.isNotBlank() && lc.searchQueryTitle.isNotBlank()) {
                val titleHtml = safeGetText(
                    "$dom/search/${safeEncode(lc.searchQueryTitle)}/page/1/",
                    mapOf("Referer" to "$dom/"),
                    timeoutSec = lc.reqTimeoutSec.coerceAtMost(8L),
                    retries = 1,
                    sourceTag = "bollyflix",
                    useCloudflareBypass = true
                ).orEmpty()
                if (titleHtml.isNotBlank()) {
                    searchDoc = Jsoup.parse(titleHtml, dom)
                    articles = searchDoc.select("div.post-cards > article, article")
                }
            }

            val matchedUrl = articles.firstNotNullOfOrNull { art ->
                val a = art.selectFirst("a[href]") ?: return@firstNotNullOfOrNull null
                val href = a.attr("href").trim()
                val t = a.attr("title").replace("Download ", "", true).trim().ifBlank { art.text().trim() }
                if (href.startsWith("http") && ((lc.imdbId.isNotBlank() && queryStr == lc.imdbId) || titleMatchesCandidate(t, lc.pageTitle))) {
                    href
                } else null
            }
            MovieFoxSettingsDialog.logSearch("bollyflix", lc.searchQueryTitle, if (matchedUrl != null) 1 else 0, System.currentTimeMillis() - sStart, dom)
            if (matchedUrl == null) continue
            activeBollyFlixBase = dom

            val detailHtml = safeGetText(
                matchedUrl,
                mapOf("Referer" to "$dom/"),
                timeoutSec = lc.reqTimeoutSec,
                retries = 1,
                sourceTag = "bollyflix",
                useCloudflareBypass = true
            ).orEmpty()
            if (detailHtml.isBlank()) continue

            val document = Jsoup.parse(detailHtml, matchedUrl)
            val resolvedTargets = linkedSetOf<String>()

            if (lc.isTv) {
                val allButtons = document.select("a.maxbutton-download-links, a.dl, a.btnn")
                val matchingButtons = allButtons.filter { btn ->
                    val heading = btn.parent()?.previousElementSibling()?.text().orEmpty()
                        .ifBlank { btn.previousElementSibling()?.text().orEmpty() }
                    val combined = "$heading ${btn.text()}"
                    if (combined.contains("Zip", true) || combined.contains("Batch", true)) return@filter false
                    val realSeason = Regex("""(?:Season\s*|S)0*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(combined)?.groupValues?.get(1)?.toIntOrNull()
                    realSeason == null || realSeason == lc.season
                }.ifEmpty { allButtons }.take(4)

                for (btn in matchingButtons) {
                    var link = btn.attr("href").trim()
                    if (link.isBlank()) continue
                    if (!link.contains("fastdlserver", true) && link.contains("?id=")) {
                        val id = link.substringAfterLast("id=").substringBefore("&")
                        val bypassed = bypassBollyFlixSidexfee(id, lc.reqTimeoutSec)
                        if (bypassed.startsWith("http")) link = bypassed
                    }
                    if (!link.startsWith("http")) continue

                    val seasonHtml = safeGetText(link, mapOf("Referer" to matchedUrl), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "bollyflix").orEmpty()
                    if (seasonHtml.isBlank()) continue
                    val seasonDoc = Jsoup.parse(seasonHtml, link)
                    val epTag = "Episode ${lc.episode.toString().padStart(2, '0')}"
                    val epLinks = seasonDoc.select("h3 > a, article h3 a, h4 > a, p > a")
                        .filter { !it.text().contains("Zip", true) && it.attr("href").startsWith("http") }

                    val exactEp = epLinks.firstOrNull {
                        it.text().contains(epTag, true) ||
                            Regex("""(?i)\b(?:episode|ep)\s*0*${lc.episode}\b""").containsMatchIn(it.text())
                    }?.attr("href")

                    val chosen = exactEp ?: epLinks.getOrNull((lc.episode - 1).coerceAtLeast(0))?.attr("href")
                    if (!chosen.isNullOrBlank()) resolvedTargets.add(chosen)
                }
            } else {
                val links = document.select("a.dl, a.maxbutton-download-links, a.btnn")
                for (el in links.take(6)) {
                    var decodeUrl = el.attr("href").trim()
                    if (decodeUrl.isBlank()) continue
                    if (!decodeUrl.contains("fastdlserver", true) && decodeUrl.contains("?id=")) {
                        val id = decodeUrl.substringAfterLast("id=").substringBefore("&")
                        val bypassed = bypassBollyFlixSidexfee(id, lc.reqTimeoutSec)
                        if (bypassed.startsWith("http")) decodeUrl = bypassed
                    }
                    if (decodeUrl.startsWith("http")) {
                        resolvedTargets.add(decodeUrl)
                    }
                }
            }

            for (target in resolvedTargets.take(6)) {
                dispatchEmbedOrDirect("BollyFlix", "GDFlix", target, matchedUrl, lc, emitSub, emitLink)
            }
            break
        }
    }

    // =========================================================================
    // CINESTREAM SERVERS (High Timeout, Deduplicated)
    // =========================================================================
    private suspend fun fetchFromCsVideasy(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, emitLink: (ExtractorLink) -> Unit) {
        val tmdb = lc.candidateIds.firstOrNull()?.toIntOrNull() ?: return
        if (lc.searchQueryTitle.isBlank()) return
        val hdrs = mapOf("Accept" to "*/*", "User-Agent" to effectiveUa, "Origin" to "https://player.videasy.to", "Referer" to "https://player.videasy.to/")
        val seedJson = safeGetText("$csVideasyApi/seed?mediaId=$tmdb", hdrs, timeoutSec = lc.reqTimeoutSec.coerceAtMost(10L), retries = 1, sourceTag = "videasy").orEmpty()
        val seed = runCatching { JSONObject(seedJson).optString("seed") }.getOrNull().orEmpty()
        if (seed.isBlank()) return

        val encTitle = safeEncode(safeEncode(lc.searchQueryTitle))
        val servers = listOf("myflixerzupcloud", "m4uhd", "hdmovie", "cdn", "superflix", "lamovie")
        for (srv in servers) {
            val apiUrl = if (!lc.isTv) {
                "$csVideasyApi/$srv/sources-with-title?title=$encTitle&mediaType=movie&tmdbId=$tmdb&imdbId=${lc.imdbId}&enc=2&seed=$seed"
            } else {
                "$csVideasyApi/$srv/sources-with-title?title=$encTitle&mediaType=tv&tmdbId=$tmdb&episodeId=${lc.episode}&seasonId=${lc.season}&imdbId=${lc.imdbId}&enc=2&seed=$seed"
            }
            val encData = safeGetText(apiUrl, hdrs, timeoutSec = lc.reqTimeoutSec.coerceAtMost(8L), retries = 1, sourceTag = "videasy").orEmpty()
            if (encData.isBlank()) continue
            val decRes = runCatching {
                app.post("$csMultiDecryptApi/dec-videasy", json = mapOf("text" to encData, "id" to tmdb, "seed" to seed), timeout = lc.reqTimeoutSec.coerceAtMost(8L))
            }.getOrNull() ?: continue
            if (!decRes.isSuccessful) continue
            val resultObj = runCatching { JSONObject(decRes.text).optJSONObject("result") }.getOrNull() ?: continue
            val sources = resultObj.optJSONArray("sources") ?: continue
            for (i in 0 until sources.length()) {
                val obj = sources.optJSONObject(i) ?: continue
                val qLabel = obj.optString("quality", "1080p")
                val srcUrl = obj.optString("url", "")
                if (srcUrl.startsWith("http")) {
                    dispatchEmbedOrDirect("Videasy", "${srv.replaceFirstChar { it.uppercase() }} $qLabel", srcUrl, "https://player.videasy.to/", lc, emitSub, emitLink, getQualityFromName(qLabel), hdrs)
                }
            }
            resultObj.optJSONArray("subtitles")?.let { subs ->
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    val sUrl = s.optString("url", "")
                    if (sUrl.startsWith("http")) emitSub(newSubtitleFile("Videasy • ${s.optString("language", "Sub")}", sUrl))
                }
            }
        }
    }

    private suspend fun fetchFromCsVidzee(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, emitLink: (ExtractorLink) -> Unit) {
        val tmdb = lc.candidateIds.firstOrNull()?.toIntOrNull() ?: return
        val secretBytes = Base64.decode("QTdrUDl4TTJRdjhMcjROejFIdDZZYzNCdzVKZjBEc1U=", Base64.DEFAULT)
        val secretKey = String(secretBytes, Charsets.UTF_8).padEnd(32, '\u0000').toByteArray(Charsets.UTF_8)

        supervisorScope {
            listOf(0, 1, 2, 4, 5, 6, 7).map { sr ->
                async(Dispatchers.IO) {
                    val apiUrl = if (!lc.isTv) "$csVidzeeApi/api/server?id=$tmdb&sr=$sr" else "$csVidzeeApi/api/server?id=$tmdb&sr=$sr&ss=${lc.season}&ep=${lc.episode}"
                    val raw = safeGetText(apiUrl, mapOf("Referer" to "$csVidzeeApi/", "User-Agent" to effectiveUa), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "vidzee").orEmpty()
                    if (raw.isBlank()) return@async
                    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@async
                    val urls = json.optJSONArray("url") ?: return@async
                    for (i in 0 until urls.length()) {
                        val obj = urls.optJSONObject(i) ?: continue
                        val encLink = obj.optString("link", "")
                        if (encLink.isBlank()) continue
                        val decUrl = runCatching {
                            val decoded = Base64.decode(encLink, Base64.DEFAULT)
                            val str = String(decoded, Charsets.UTF_8)
                            val parts = str.split(":", limit = 2)
                            val iv = Base64.decode(parts[0], Base64.DEFAULT)
                            val ct = Base64.decode(parts[1], Base64.DEFAULT)
                            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), IvParameterSpec(iv))
                            String(cipher.doFinal(ct), Charsets.UTF_8)
                        }.getOrNull().orEmpty()
                        if (decUrl.startsWith("https://")) {
                            val label = "${obj.optString("name", "Srv")} (${obj.optString("lang", "Multi")})"
                            dispatchEmbedOrDirect("VidZee", label, decUrl, "$csVidzeeApi/", lc, emitSub, emitLink)
                        }
                    }
                    json.optJSONArray("tracks")?.let { subs ->
                        for (i in 0 until subs.length()) {
                            val s = subs.optJSONObject(i) ?: continue
                            val sUrl = s.optString("url", "")
                            if (sUrl.startsWith("http")) emitSub(newSubtitleFile("VidZee • ${s.optString("lang", "Sub")}", sUrl))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun fetchFromCsHexa(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, emitLink: (ExtractorLink) -> Unit) {
        val tmdb = lc.candidateIds.firstOrNull()?.toIntOrNull() ?: return
        val tokenJson = safeGetText("$csMultiDecryptApi/enc-hexa", timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "hexa").orEmpty()
        val token = runCatching { JSONObject(tokenJson).optJSONObject("result")?.optString("token") }.getOrNull().orEmpty()
        if (token.isBlank()) return

        val keyBytes = ByteArray(32).also { random.nextBytes(it) }
        val keyHex = keyBytes.joinToString("") { "%02x".format(it) }
        val mediaPath = if (!lc.isTv) "movie/$tmdb" else "tv/$tmdb/season/${lc.season}/episode/${lc.episode}"
        val encUrl = "$csHexaApi/api/tmdb/$mediaPath/images"
        val encData = safeGetText(
            encUrl,
            mapOf(
                "User-Agent" to effectiveUa,
                "Accept" to "text/plain",
                "X-Api-Key" to keyHex,
                "X-Fingerprint-Lite" to "e9136c41504646444",
                "Referer" to "https://hexa.su/",
                "X-Cap-Token" to token
            ),
            timeoutSec = lc.reqTimeoutSec,
            retries = 1,
            sourceTag = "hexa"
        ).orEmpty()
        if (encData.isBlank()) return

        val decRes = runCatching {
            app.post("$csMultiDecryptApi/dec-hexa", json = mapOf("text" to encData, "key" to keyHex), headers = mapOf("Content-Type" to "application/json"), timeout = lc.reqTimeoutSec)
        }.getOrNull() ?: return
        if (!decRes.isSuccessful) return
        val sources = runCatching { JSONObject(decRes.text).optJSONObject("result")?.optJSONArray("sources") }.getOrNull() ?: return
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val m3u8 = src.optString("url", "")
            if (m3u8.startsWith("http")) {
                dispatchEmbedOrDirect("Hexa", src.optString("server", "Server").replaceFirstChar { it.uppercase() }, m3u8, "https://hexa.su/", lc, emitSub, emitLink)
            }
        }
    }

    private suspend fun fetchFromCsVidfastAndVidcore(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, emitLink: (ExtractorLink) -> Unit) {
        val tmdb = lc.candidateIds.firstOrNull()?.toIntOrNull() ?: return
        val hdrs = mapOf("User-Agent" to effectiveUa, "Referer" to "$csVidfastApi/", "X-Requested-With" to "XMLHttpRequest")
        val pageUrl = if (!lc.isTv) "$csVidfastApi/movie/$tmdb/" else "$csVidfastApi/tv/$tmdb/${lc.season}/${lc.episode}/"
        val html = safeGetText(pageUrl, hdrs, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
        val encToken = Regex("""\\"(?:en|token)\\":\\"(.*?)\\"""").find(html)?.groupValues?.get(1).orEmpty()
        if (encToken.isBlank()) return

        val decInfoRaw = safeGetText("$csMultiDecryptApi/enc-vidfast?text=${safeEncode(encToken)}", hdrs, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
        val resObj = runCatching { JSONObject(decInfoRaw).optJSONObject("result") }.getOrNull() ?: return
        val serversUrl = resObj.optString("servers", "")
        val streamBase = resObj.optString("stream", "")
        val csrf = resObj.optString("token", "")
        if (serversUrl.isBlank() || streamBase.isBlank()) return

        val postHdrs = hdrs + mapOf("X-CSRF-Token" to csrf)
        val srvEnc = runCatching { app.post(serversUrl, headers = postHdrs, timeout = lc.reqTimeoutSec).text }.getOrNull().orEmpty()
        val srvDec = runCatching { app.post("$csMultiDecryptApi/dec-vidfast", json = mapOf("text" to srvEnc), timeout = lc.reqTimeoutSec).text }.getOrNull().orEmpty()
        val srvArr = runCatching { JSONObject(srvDec).optJSONArray("result") }.getOrNull() ?: return

        for (i in 0 until srvArr.length().coerceAtMost(4)) {
            val sObj = srvArr.optJSONObject(i) ?: continue
            val hash = sObj.optString("data", "")
            val sName = sObj.optString("name", "Server")
            if (hash.isBlank()) continue
            val stEnc = runCatching { app.post("$streamBase/$hash", headers = postHdrs, timeout = lc.reqTimeoutSec).text }.getOrNull().orEmpty()
            if (stEnc.isBlank()) continue
            val stDec = runCatching { app.post("$csMultiDecryptApi/dec-vidfast", json = mapOf("text" to stEnc), timeout = lc.reqTimeoutSec).text }.getOrNull().orEmpty()
            val stRes = runCatching { JSONObject(stDec).optJSONObject("result") }.getOrNull() ?: continue
            val fUrl = stRes.optString("url", "")
            if (fUrl.startsWith("http")) {
                dispatchEmbedOrDirect("Vidfast", "$sName ${stRes.optString("description", "")}".trim(), fUrl, "$csVidfastApi/", lc, emitSub, emitLink, if (stRes.optBoolean("4kAvailable", false)) Qualities.P2160.value else Qualities.P1080.value)
            }
            stRes.optJSONArray("tracks")?.let { tracks ->
                for (t in 0 until tracks.length()) {
                    val tr = tracks.optJSONObject(t) ?: continue
                    val file = tr.optString("file", "")
                    if (file.startsWith("http")) emitSub(newSubtitleFile("Vidfast • ${tr.optString("label", "Sub")}", file))
                }
            }
        }
    }

    private suspend fun fetchFromCsCinejoy(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, emitLink: (ExtractorLink) -> Unit) {
        if (lc.searchQueryTitle.isBlank()) return
        val tmdb = lc.candidateIds.firstOrNull()?.toIntOrNull() ?: return
        val hdrs = mapOf("Accept" to "*/*", "Origin" to csCinejoyBaseApi, "Referer" to "$csCinejoyBaseApi/", "User-Agent" to effectiveUa)
        val srvRaw = safeGetText("$csCinejoyApi/servers", hdrs, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
        val srvArr = runCatching { JSONObject(srvRaw).optJSONArray("servers") }.getOrNull() ?: return
        val type = if (lc.isTv) "series" else "movie"

        for (i in 0 until srvArr.length().coerceAtMost(4)) {
            val srv = srvArr.optJSONObject(i)?.optString("name", "").orEmpty()
            if (srv.isBlank()) continue
            val targetUrl = buildString {
                append("$csCinejoyApi/?title=${safeEncode(lc.searchQueryTitle)}&type=$type&imdb=${lc.imdbId}&tmdb=$tmdb&server=$srv")
                if (lc.isTv) append("&season=${lc.season}&episode=${lc.episode}")
            }
            val encRaw = safeGetText("$csMultiDecryptApi/enc-cinejoy?url=${safeEncode(targetUrl)}", timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
            val encResult = runCatching { JSONObject(encRaw).optJSONObject("result") }.getOrNull() ?: continue
            val dataB64 = encResult.optString("data", "")
            val stateObj = encResult.optJSONObject("state") ?: continue
            if (dataB64.isBlank()) continue

            val pad = when (dataB64.length % 4) { 2 -> "=="; 3 -> "="; else -> "" }
            val bytes = runCatching { Base64.decode(dataB64 + pad, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull() ?: continue
            val postRes = runCatching {
                app.post("$csCinejoyApi/g", requestBody = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()), headers = hdrs, timeout = lc.reqTimeoutSec)
            }.getOrNull() ?: continue
            val respBytes = runCatching { postRes.okhttpResponse.body.bytes() }.getOrNull() ?: continue
            val b64Out = Base64.encodeToString(respBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

            val decRaw = runCatching {
                app.post("$csMultiDecryptApi/dec-cinejoy", json = mapOf("text" to b64Out, "state" to JSONObject(stateObj.toString())), timeout = lc.reqTimeoutSec).text
            }.getOrNull().orEmpty()
            val streams = runCatching {
                JSONObject(decRaw).optJSONObject("result")?.optJSONObject("data")?.optJSONArray("stream")
            }.getOrNull() ?: continue

            for (sIdx in 0 until streams.length()) {
                val st = streams.optJSONObject(sIdx) ?: continue
                val pl = st.optString("playlist", "")
                if (pl.startsWith("http")) {
                    dispatchEmbedOrDirect("Cinejoy", srv.replaceFirstChar { it.uppercase() }, pl, "$csCinejoyBaseApi/", lc, emitSub, emitLink)
                }
                st.optJSONObject("qualities")?.let { qMap ->
                    qMap.keys().forEach { qKey ->
                        val qUrl = qMap.optJSONObject(qKey)?.optString("url", "").orEmpty()
                        if (qUrl.startsWith("http")) {
                            dispatchEmbedOrDirect("Cinejoy", "${srv.replaceFirstChar { it.uppercase() }} ($qKey)", qUrl, "$csCinejoyBaseApi/", lc, emitSub, emitLink, getQualityFromName(qKey))
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchFromCsMoviebox(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, emitLink: (ExtractorLink) -> Unit) {
        if (lc.searchQueryTitle.isBlank()) return
        val sStart = System.currentTimeMillis()
        val host = "h5-api.aoneroom.com"
        val base = "https://$host"
        val pkgRes = runCatching { app.get("$base/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox", timeout = lc.reqTimeoutSec) }.getOrNull() ?: return
        val xUser = pkgRes.headers["x-user"].orEmpty()
        val token = runCatching { JSONObject(xUser).optString("token", "") }.getOrNull().orEmpty()
        if (token.isBlank()) return

        val hdrs = mapOf(
            "X-Client-Info" to "{\"timezone\":\"Asia/Kolkata\"}",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept" to "application/json",
            "Referer" to "https://moviebox01.com/",
            "Host" to host,
            "Authorization" to "Bearer $token",
            "User-Agent" to effectiveUa
        )
        val searchRaw = runCatching {
            app.post("$base/wefeed-h5api-bff/subject/search", headers = hdrs, json = mapOf("keyword" to lc.searchQueryTitle, "page" to 1, "perPage" to 15, "subjectType" to if (lc.isTv) 2 else 1), timeout = lc.reqTimeoutSec).text
        }.getOrNull().orEmpty()
        val rootSearch = runCatching { JSONObject(searchRaw) }.getOrNull() ?: return
        val dataSearch = rootSearch.optJSONObject("data")?.optJSONObject("data") ?: rootSearch.optJSONObject("data") ?: return
        val items = dataSearch.optJSONArray("items") ?: return
        MovieFoxSettingsDialog.logSearch("moviebox", lc.searchQueryTitle, items.length(), System.currentTimeMillis() - sStart)

        for (i in 0 until items.length().coerceAtMost(4)) {
            val item = items.optJSONObject(i) ?: continue
            val t = item.optString("title", "")
            if (!titleMatchesCandidate(t, lc.pageTitle)) continue
            val sid = item.optString("subjectId", "")
            if (sid.isBlank()) continue

            var detailPath = item.optString("detailPath", "").trim()
            if (detailPath.isBlank()) {
                val detailRaw = safeGetText("https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=$sid", hdrs, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "moviebox").orEmpty()
                detailPath = runCatching {
                    JSONObject(detailRaw).optJSONObject("data")?.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("subject")?.optString("detailPath", "")
                }.getOrNull().orEmpty()
            }
            if (detailPath.isBlank()) continue

            val params = buildString {
                append("subjectId=$sid")
                if (lc.isTv) append("&se=${lc.season}&ep=${lc.episode}")
                append("&detailPath=$detailPath")
            }
            val reqHdrs = hdrs + mapOf("Referer" to "https://moviebox01.com/spa/videoPlayPage/movies/$detailPath?id=$sid&type=/movie/detail", "Origin" to "https://moviebox01.com")

            var gotStream = false
            val dlRaw = safeGetText("$base/wefeed-h5api-bff/subject/download?$params", reqHdrs, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "moviebox").orEmpty()
            val dlRoot = runCatching { JSONObject(dlRaw) }.getOrNull()
            val dlData = dlRoot?.optJSONObject("data")?.optJSONObject("data") ?: dlRoot?.optJSONObject("data")
            dlData?.optJSONArray("downloads")?.let { dArr ->
                for (dIdx in 0 until dArr.length()) {
                    val dObj = dArr.optJSONObject(dIdx) ?: continue
                    if (dObj.optBoolean("vipLocked", false)) continue
                    val u = dObj.optString("url", "")
                    if (u.startsWith("http")) {
                        gotStream = true
                        val q = dObj.optInt("resolution", 720).takeIf { it > 0 } ?: 720
                        dispatchEmbedOrDirect("MovieBox", "DL ${q}p", u, "https://moviebox01.com/", lc, emitSub, emitLink, q)
                    }
                }
            }

            val playRaw = safeGetText("$base/wefeed-h5api-bff/subject/play?$params", reqHdrs, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "moviebox").orEmpty()
            val playRoot = runCatching { JSONObject(playRaw) }.getOrNull()
            val playData = playRoot?.optJSONObject("data")?.optJSONObject("data") ?: playRoot?.optJSONObject("data")
            playData?.optJSONArray("streams")?.let { sArr ->
                for (sIdx in 0 until sArr.length()) {
                    val sObj = sArr.optJSONObject(sIdx) ?: continue
                    if (sObj.optBoolean("vipLocked", false)) continue
                    val u = sObj.optString("url", "")
                    if (u.startsWith("http")) {
                        gotStream = true
                        val q = sObj.optString("resolutions", "").toIntOrNull() ?: sObj.optInt("resolution", 720)
                        dispatchEmbedOrDirect("MovieBox", "${q}p", u, "https://moviebox01.com/", lc, emitSub, emitLink, q)
                    }
                }
            }
            if (gotStream) break
        }
    }

    private suspend fun fetchFromCsMirrorSites(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, emitLink: (ExtractorLink) -> Unit) {
        val hdhubApi = getCsDynamicUrl("hdhub4u", "https://new16.hdhub4u.cl").substringBefore("?")
        val fourkhdhubApi = getCsDynamicUrl("4khdhub", "https://4khdhub.dad")
        val movies4uApi = getCsDynamicUrl("movies4u", "https://movies4u.st")
        val moviesdriveApi = getCsDynamicUrl("moviesdrive", "https://moviesdrive.club")
        val uhdApi = getCsDynamicUrl("uhdmovies", "https://uhdmovies.zip")
        val modApi = getCsDynamicUrl("moviesmod", "https://moviesmod.build")

        if (lc.imdbId.isNotBlank()) {
            val searchJson = safeGetText(
                "https://search.pingora.fyi/collections/post/documents/search?q=${lc.imdbId}&query_by=post_title,category,stars,director,imdb_id&limit=4",
                mapOf("Referer" to "$hdhubApi/"),
                timeoutSec = lc.reqTimeoutSec,
                retries = 1,
                sourceTag = "cinestream"
            ).orEmpty()
            val hits = runCatching { JSONObject(searchJson).optJSONArray("hits") }.getOrNull()
            if (hits != null) {
                for (i in 0 until hits.length().coerceAtMost(2)) {
                    val dObj = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                    val rawPerm = dObj.optString("permalink", "").orEmpty()
                    if (rawPerm.isBlank()) continue
                    val fullPerm = if (rawPerm.startsWith("http")) rawPerm else "$hdhubApi/${rawPerm.removePrefix("/")}"
                    val doc = safeGetText(fullPerm, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
                    if (doc.isBlank()) continue
                    val parsed = Jsoup.parse(doc, fullPerm)
                    parsed.select("div.mod a[data-wpel-link=external]").map { it.attr("href") }.filter { it.startsWith("http") }.distinct().take(3).forEach { href ->
                        dispatchEmbedOrDirect("HDHub4u", "Mirror", href, fullPerm, lc, emitSub, emitLink)
                    }
                }
            }
        }

        if (lc.imdbId.isNotBlank()) {
            val mdJson = safeGetText("$moviesdriveApi/search.php?q=${lc.imdbId}", timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
            val hits = runCatching { JSONObject(mdJson).optJSONArray("hits") }.getOrNull()
            if (hits != null) {
                for (i in 0 until hits.length().coerceAtMost(2)) {
                    val dObj = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                    if (dObj.optString("imdb_id") != lc.imdbId) continue
                    val itemUrl = normalizeUrl(dObj.optString("permalink", ""), moviesdriveApi)
                    val html = safeGetText(itemUrl, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
                    if (html.isBlank()) continue
                    val doc = Jsoup.parse(html, itemUrl)
                    doc.select("a[href]").map { it.attr("href") }.filter {
                        it.contains("hubcloud", true) || it.contains("gdflix", true) || it.contains("gdlink", true) || it.contains("gofile", true)
                    }.distinct().take(3).forEach { href ->
                        dispatchEmbedOrDirect("MoviesDrive", "Drive", href, itemUrl, lc, emitSub, emitLink)
                    }
                }
            }
        }

        if (lc.searchQueryTitle.isNotBlank()) {
            val q = safeEncode(lc.searchQueryTitle)
            val titleSites = listOf(
                "4KHDHub" to "$fourkhdhubApi/?s=$q",
                "Movies4u" to "$movies4uApi/?s=$q",
                "UHDMovies" to "$uhdApi/search/$q",
                "Moviesmod" to "$modApi/search/$q"
            )
            for ((tag, sUrl) in titleSites) {
                val sHtml = safeGetText(sUrl, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
                if (sHtml.isBlank()) continue
                val sDoc = Jsoup.parse(sHtml, sUrl)
                val matchHref = sDoc.select("div.card-grid > a, article h3 a, article > a, div > article > a").firstOrNull { el ->
                    val t = el.attr("title").ifBlank { el.text() }
                    el.attr("href").isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                }?.attr("href")?.let { normalizeUrl(it, sUrl) } ?: continue

                val pHtml = safeGetText(matchHref, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
                if (pHtml.isBlank()) continue
                val pDoc = Jsoup.parse(pHtml, matchHref)
                pDoc.select("a[href]").map { it.attr("href") }.filter {
                    it.contains("hubcloud", true) || it.contains("hubdrive", true) || it.contains("gdflix", true) || it.contains("gofile", true)
                }.distinct().take(3).forEach { href ->
                    dispatchEmbedOrDirect(tag, "Mirror", href, matchHref, lc, emitSub, emitLink)
                }
            }
        }
    }

    private suspend fun fetchFromCsAnimeAndDrama(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, emitLink: (ExtractorLink) -> Unit) {
        val slug = slugify(lc.searchQueryTitle).orEmpty()
        if (slug.isBlank()) return

        if (lc.searchQueryTitle.isNotBlank()) {
            val khRaw = safeGetText("$csKisskhApi/api/DramaList/Search?q=${safeEncode(lc.searchQueryTitle)}&type=${if (lc.isTv) 1 else 2}", mapOf("Referer" to "$csKisskhApi/"), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
            val khArr = runCatching { JSONArray(khRaw) }.getOrNull()
            if (khArr != null) {
                for (i in 0 until khArr.length().coerceAtMost(3)) {
                    val obj = khArr.optJSONObject(i) ?: continue
                    if (!titleMatchesCandidate(obj.optString("title", ""), lc.pageTitle)) continue
                    val dramaId = obj.optInt("id", 0)
                    if (dramaId <= 0) continue
                    val detailRaw = safeGetText("$csKisskhApi/api/DramaList/Drama/$dramaId?isq=false", mapOf("Referer" to "$csKisskhApi/"), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
                    val eps = runCatching { JSONObject(detailRaw).optJSONArray("episodes") }.getOrNull() ?: continue
                    var targetEpId = 0
                    for (e in 0 until eps.length()) {
                        val epObj = eps.optJSONObject(e) ?: continue
                        if (!lc.isTv || epObj.optInt("number", 1) == lc.episode) {
                            targetEpId = epObj.optInt("id", 0)
                            break
                        }
                    }
                    if (targetEpId > 0) {
                        val vidKeyRaw = safeGetText("$csMultiDecryptApi/enc-kisskh?text=$targetEpId&type=vid", timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
                        val vidKey = runCatching { JSONObject(vidKeyRaw).optString("result", "") }.getOrNull().orEmpty()
                        if (vidKey.isNotBlank()) {
                            val srcRaw = safeGetText("$csKisskhApi/api/DramaList/Episode/$targetEpId.png?err=false&ts=&time=&kkey=$vidKey", mapOf("Referer" to "$csKisskhApi/"), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
                            val srcObj = runCatching { JSONObject(srcRaw) }.getOrNull()
                            listOfNotNull(srcObj?.optString("Video"), srcObj?.optString("ThirdParty")).filter { it.startsWith("http") }.forEach { vUrl ->
                                dispatchEmbedOrDirect("KissKH", "Asian", vUrl, "$csKisskhApi/", lc, emitSub, emitLink)
                            }
                        }
                    }
                    break
                }
            }
        }

        val saltApi = getCsDynamicUrl("animesalt", csAnimesaltApi)
        val saltUrl = if (!lc.isTv) "$saltApi/movies/$slug/" else "$saltApi/episode/$slug-${lc.season}x${lc.episode}/"
        val saltHtml = safeGetText(saltUrl, mapOf("Referer" to "$saltApi/"), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
        if (saltHtml.isNotBlank()) {
            Regex("""src="(https://as-cdn\d+\.top/video/([a-f0-9]+))\"""").find(saltHtml)?.let { m ->
                val pUrl = m.groupValues[1]
                val hash = m.groupValues[2]
                val cdn = pUrl.substringBefore("/video/")
                val postRes = runCatching {
                    app.post(
                        "$cdn/player/index.php?data=$hash&do=getVideo",
                        data = mapOf("hash" to hash, "r" to "$saltApi/"),
                        headers = mapOf("Referer" to "$saltApi/", "Origin" to cdn, "X-Requested-With" to "XMLHttpRequest"),
                        timeout = lc.reqTimeoutSec
                    ).text
                }.getOrNull().orEmpty()
                val vObj = runCatching { JSONObject(postRes) }.getOrNull()
                val m3u8 = vObj?.optString("videoSource")?.takeIf { it.isNotBlank() } ?: vObj?.optString("securedLink").orEmpty()
                if (m3u8.startsWith("http")) {
                    dispatchEmbedOrDirect("AnimeSalt", "Multi", m3u8, "$saltApi/", lc, emitSub, emitLink)
                }
            }
        }

        val toonApi = getCsDynamicUrl("toonstream", csToonstreamApi)
        val toonUrl = if (!lc.isTv) "$toonApi/movies/$slug/" else "$toonApi/episode/$slug-${lc.season}x${lc.episode}/"
        val toonHtml = safeGetText(toonUrl, mapOf("Referer" to toonApi), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
        if (toonHtml.isNotBlank()) {
            val tDoc = Jsoup.parse(toonHtml, toonUrl)
            tDoc.select("div.video > iframe").map { it.attr("data-src").ifBlank { it.attr("src") } }.filter { it.isNotBlank() }.take(2).forEach { iUrl ->
                val subHtml = safeGetText(normalizeUrl(iUrl, toonApi), timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
                Jsoup.parse(subHtml).select("div.Video > iframe").map { it.attr("src") }.filter { it.startsWith("http") }.forEach { emb ->
                    dispatchEmbedOrDirect("Toonstream", "Toon", emb, "$toonApi/", lc, emitSub, emitLink)
                }
            }
        }
    }

    private suspend fun fetchFromCsSubtitles(lc: LinkContext, emitSub: (SubtitleFile) -> Unit) {
        if (lc.imdbId.isBlank()) return
        val subUrl = if (lc.isTv) {
            "$csWyzieSubsApi/search?id=${lc.imdbId}&season=${lc.season}&episode=${lc.episode}&source=all"
        } else {
            "$csWyzieSubsApi/search?id=${lc.imdbId}&source=all"
        }
        val raw = safeGetText(subUrl, timeoutSec = lc.reqTimeoutSec, retries = 1, sourceTag = "cinestream").orEmpty()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until arr.length().coerceAtMost(25)) {
            val item = arr.optJSONObject(i) ?: continue
            val u = item.optString("url", "")
            val lang = item.optString("display").ifBlank { item.optString("language", "Sub") }
            if (u.startsWith("http")) emitSub(newSubtitleFile("WYZIE • $lang", u))
        }
    }

    private suspend fun fetchFromCineStreamServers(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        val subCapMs = (lc.reqTimeoutSec * 1000L).coerceIn(10_000L, 25_000L)
        supervisorScope {
            val tasks = listOf(
                async(Dispatchers.IO) { withTimeoutOrNull(subCapMs) { fetchFromCsVidfastAndVidcore(lc, emitSub, emitLink) } },
                async(Dispatchers.IO) { withTimeoutOrNull(subCapMs) { fetchFromCsCinejoy(lc, emitSub, emitLink) } },
                async(Dispatchers.IO) { withTimeoutOrNull(subCapMs) { fetchFromCsMirrorSites(lc, emitSub, emitLink) } },
                async(Dispatchers.IO) { withTimeoutOrNull(subCapMs) { fetchFromCsAnimeAndDrama(lc, emitSub, emitLink) } },
                async(Dispatchers.IO) { withTimeoutOrNull(subCapMs) { fetchFromCsSubtitles(lc, emitSub) } }
            )
            tasks.awaitAll()
        }
    }

    // =========================================================================
    // LOAD LINKS: Bounded per-server timeout + isolated provider execution
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadStartMs = System.currentTimeMillis()
        return try {
            val ctx = ctxOrNull()
            val reqTimeoutSec = currentTimeoutSec(25L)
            val serverCapMs = (reqTimeoutSec * 1000L).coerceIn(15_000L, 35_000L)

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
                }.onFailure {
                    MovieFoxSettingsDialog.logParsing("loadLinks", data.take(80), "DATA_JSON", it)
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
            val totalEmitted = AtomicInteger(0)

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
                            totalEmitted.incrementAndGet()
                            callback(link)
                        }
                    }
                }
            }

            val orderedKeys = (ctx?.let { MovieFoxSettingsDialog.getOrderedEnabledKeys(it) }
                ?: listOf(
                    "sflix", "play4u", "prmovies", "hindidrama", "themoviesflix",
                    "dramaverse", "katdrama", "animeworld", "vegamovies", "bollyflix",
                    "cinestream", "videasy", "vidzee", "hexa", "moviebox"
                )).distinct()

            val nuvioByKey = MovieFoxSettingsDialog.getNuvioManifestServers(ctx).associateBy { it.key.lowercase() }

            val concurrency = (ctx?.let { MovieFoxSettingsDialog.getConcurrency(it) } ?: 24).coerceIn(6, 50)
            val sem = Semaphore(concurrency)
            supervisorScope {
                val tasks = mutableListOf<Deferred<Unit>>()
                orderedKeys.forEach { key ->
                    tasks.add(async(Dispatchers.IO) {
                        sem.withPermit {
                            val srvStart = System.currentTimeMillis()
                            val srvCount = AtomicInteger(0)
                            val trackedEmitLink: (ExtractorLink) -> Unit = { ex ->
                                srvCount.incrementAndGet()
                                emitLink(ex)
                            }
                            val completed = withTimeoutOrNull(serverCapMs) {
                                val lowKey = key.lowercase()
                                when (lowKey) {
                                    "sflix" -> fetchFromSflix(lc, emitSub, trackedEmitLink)
                                    "play4u" -> fetchFromPlay4u(lc, emitSub, trackedEmitLink)
                                    "prmovies" -> fetchFromPrmovies(lc, emitSub, trackedEmitLink)
                                    "hindidrama" -> fetchFromHindiDrama(lc, emitSub, trackedEmitLink)
                                    "themoviesflix" -> fetchFromTheMoviesFlix(lc, emitSub, trackedEmitLink)
                                    "dramaverse" -> fetchFromDramaVerse(lc, emitSub, trackedEmitLink)
                                    "katdrama" -> fetchFromKatDrama(lc, emitSub, trackedEmitLink)
                                    "animeworld" -> fetchFromAnimeWorld(lc, emitSub, trackedEmitLink)
                                    "vegamovies" -> fetchFromVegaMovies(lc, emitSub, trackedEmitLink)
                                    "bollyflix" -> fetchFromBollyFlix(lc, emitSub, trackedEmitLink)
                                    "cinestream" -> fetchFromCineStreamServers(lc, emitSub, trackedEmitLink)
                                    "videasy" -> fetchFromCsVideasy(lc, emitSub, trackedEmitLink)
                                    "vidzee" -> fetchFromCsVidzee(lc, emitSub, trackedEmitLink)
                                    "hexa" -> fetchFromCsHexa(lc, emitSub, trackedEmitLink)
                                    "moviebox" -> fetchFromCsMoviebox(lc, emitSub, trackedEmitLink)
                                    else -> nuvioByKey[lowKey]?.let { fetchFromNuvioManifest(it, lc, emitSub, trackedEmitLink) }
                                }
                                true
                            }
                            val srvMs = System.currentTimeMillis() - srvStart
                            if (completed == null) {
                                MovieFoxSettingsDialog.logTimeout(key, lc.pageTitle, serverCapMs / 1000L, "SERVER_CAP")
                            } else {
                                MovieFoxSettingsDialog.logLoadLink(key, lc.pageTitle, srvCount.get(), srvMs)
                            }
                        }
                        Unit
                    })
                }
                tasks.awaitAll()
            }

            val totalMs = System.currentTimeMillis() - loadStartMs
            MovieFoxSettingsDialog.logLoadLink(
                "MovieFox",
                lc.pageTitle.ifBlank { cleanPageUrl },
                totalEmitted.get(),
                totalMs,
                "servers=${orderedKeys.size}"
            )
            foundFlag.get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("loadLinks", data.take(120), e, "Fatal loadLinks error")
            false
        }
    }
}

