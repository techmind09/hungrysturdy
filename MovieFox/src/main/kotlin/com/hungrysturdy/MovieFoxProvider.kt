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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val firebaseDomainsUrl = "https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json"
    private val phisherDomainsUrl = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
    private val kaperwanUrlsJson = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"

    private val cineTvUrls = listOf("https://cinetv.app/en", "https://cinetv.app")
    private val cineTvBaseUrl = "https://cinetv.app"
    private val cineTvHlsWorker = "https://silent-frost-f2dbv.nxstreamhub.workers.dev/hls"

    private val prmoviesMirrors = listOf(
        "https://prmovies.beer",
        "https://prmovies.repair",
        "https://prmovies.com.cv",
        "https://prmovies.coupons"
    )
    private var activePrmoviesBase = "https://prmovies.beer"

    private val hindiDramaBaseUrl = "https://hindidrama.net"
    private val animeWorldBaseUrl = "https://watchanimeworld.top"
    private val sportApiBaseUrl = "https://h5-sport-api.aoneroom.com/wefeed-h5api-bff"
    private val sportSiteUrl = "https://www.sportslive.wine/"

    private val vegamoviesDomains = listOf(
        "https://vegamovies.lease",
        "https://new1.vegamovies.lease",
        "https://rogmovies.vip"
    )

    private val bollyflixMirrors = listOf(
        "https://new1.bollyflix.tours",
        "https://bollyflix.frl"
    )

    private val singleIndianMirrors = mapOf(
        "bollyhq" to ("BollyHQ" to "https://bolly-hq.info"),
        "moviesdrive" to ("MoviesDrive" to "https://new4.moviesdrive.cyou"),
        "filmyfly" to ("FilmyFly" to "https://filmyfly.kred"),
        "filmyking" to ("FilmyKing" to "https://filmyking.co"),
        "myfilms" to ("MyFilms" to "https://myfilms.pro"),
        "vipfilm" to ("VipFilm" to "https://vipfilm.pro"),
        "kmmovies" to ("KMmovies" to "https://kmmovies.quest"),
        "moviesmod" to ("Moviesmod" to "https://moviesleech.rest"),
        "topmovies" to ("TopMovies" to "https://topmovies.pet")
    )

    private val sflixWebDomains = listOf(
        "https://sflix.film",
        "https://h5-api.aoneroom.com",
        "https://movie-box.co",
        "https://movieboxonline.net",
        "https://movieboxhd.net",
        "https://netnaija.film",
        "https://123moviesfree.club"
    )
    private val sflixPlayerUrl = "https://mzfi.me"
    private val sflixSecretB64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="

    private val movieBoxSiteUrl = "https://netnaija.film"
    private val movieBoxUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val netnaijaDomains = listOf(
        "https://netnaija.film",
        "https://www.thenetnaija.net"
    )
    private var activeNetnaijaBase = "https://netnaija.film"

    private val play4uBaseUrl = "https://play4u.org"
    private val play4uSealPrefix = "pa!~"
    private val play4uDefaultSealKey = "pa4u-stream-seal-v1"

    private val hdhub4uDomains = listOf(
        "https://new15.hdhub4u.cl",
        "https://new13.hdhub4u.cl"
    )
    private val hdhub4uTypesenseUrl = "https://search.pingora.fyi/collections/post/documents/search"
    private val dramaVerseBaseUrl = "https://mydverse.com"
    private val gokuHdBaseUrl = "https://gokuhd.nl"

    private val hicineApiBases = listOf("https://hicine.biz", "https://hicine.lol")
    private val hindmoviezDomains = listOf("https://v3.hindmovie.im")
    private val movielinkbdDomains = listOf(
        "https://drjuea.movielinkbd.at",
        "https://movielinkbd.com"
    )
    private val netmirrorDomains = listOf("https://netmirror.gold", "https://net52.cc")
    private val movyMainUrl = "https://www.movy.sx"
    private val movyStreamApiBase = "https://api.wecollege.net"
    private val fourKhdHubDomains = listOf("https://4khdhub.one")
    private val movies4uDomains = listOf(
        "https://new8.movies4u.garden",
        "https://new6.movies4u.garden",
        "https://movies4u.vet"
    )
    private val multimoviesDomains = listOf("https://m1.multimovies.courses", "https://multimovies.garden")

    private val fastWaveKeys = setOf(
        "moviefox", "cinestream", "purstream", "castle", "watchout", "cinetv_api", "cinetv_hls",
        "play4u", "sflix", "moviebox", "movy", "nuvio", "hicine", "hindidrama",
        "animeworldindia", "sportslive"
    )

    private val dynamicDomainCache = ConcurrentHashMap<String, String>()
    private val cs3PluginCache = ConcurrentHashMap<String, List<MainAPI>>()

    private val multiAudioRegex = Regex(
        """(?i)\b(hindi|english|tamil|telugu|kannada|malayalam|punjabi|marathi|bengali|urdu|gujarati|""" +
            """japanese|korean|chinese|spanish|french|german|russian|turkish|arabic|""" +
            """dual[ -]?audio|multi[ -]?audio|hin|eng|tam|tel|mal|kan|kor|jpn|chi|spa|fre|ger)\b"""
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
        "where to watch", "more info", "watch now", "view all",
        "play", "play now", "trailer", "all options", "go home", "search"
    )

    private fun logError(source: String, url: String, err: Throwable?, extra: String = "") {
        if (err is CancellationException) return
        Log.e(logTag, "[$source] $extra | $url -> ${err?.message.orEmpty()}", err)
        MovieFoxSettingsDialog.logSiteError(source, url, err, extra)
    }

    private fun activeMovieBoxHosts(): List<String> {
        val selected = CloudStreamApp.context?.let { MovieFoxSettingsDialog.getSelectedApiHost(it) } ?: "h5-api.aoneroom.com"
        return (listOf(
            selected,
            "h5-api.aoneroom.com",
            "api6.aoneroom.com",
            "api5.aoneroom.com",
            "api4.aoneroom.com",
            "api4sg.aoneroom.com",
            "api3.aoneroom.com"
        )).distinct()
    }

    private fun isExtOn(key: String): Boolean =
        CloudStreamApp.context?.let { MovieFoxSettingsDialog.isExtractorEnabled(it, key) } ?: true

    private fun isJunkDynamicUrl(url: String): Boolean {
        val low = url.lowercase()
        return low.contains("google.com") ||
            low.contains("gstatic.com") ||
            low.contains("gravatar.com") ||
            low.contains("cloudflare.com") ||
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
        } catch (_: Exception) {
            fallback
        }
    }

    private fun currentTimeoutSec(minSec: Long = 8L): Long {
        val ctx = CloudStreamApp.context ?: return minSec
        return MovieFoxSettingsDialog.getTimeoutSeconds(ctx).toLong().coerceAtLeast(minSec)
    }

    private fun directHeaders(url: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val ctx = CloudStreamApp.context
        val savedCookie = MovieFoxSettingsDialog.getCookieForDomain(ctx, url)
        val out = mutableMapOf<String, String>()
        out["User-Agent"] = MovieFoxSettingsDialog.CF_BYPASS_UA
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
        val isMovieFoxSite = url.contains("moviefox.pro", true)
        val isFastFailSource = sourceTag.lowercase() in setOf("cinetv_hls", "movy", "rogmovies", "topmovies")
        val effectiveTimeout = when {
            isMovieFoxSite -> minOf(timeoutSec, 4L)
            isFastFailSource -> minOf(timeoutSec, 4L)
            else -> timeoutSec
        }
        val effectiveRetries = if (isMovieFoxSite || isFastFailSource) 1 else retries.coerceAtLeast(1)

        repeat(effectiveRetries) { attempt ->
            val start = System.currentTimeMillis()
            try {
                val res = app.get(url, headers = headers, timeout = effectiveTimeout)
                val ms = System.currentTimeMillis() - start
                if (res.code in 200..299) {
                    MovieFoxSettingsDialog.reportServerStatus(sourceTag, "LIVE", true, res.code, ms, url)
                    return res.text
                }
                val code = res.code
                runCatching { res.okhttpResponse.close() }
                if (isMovieFoxSite && code == 404) {
                    return null
                }
                val reason = if (code == 401) "TOKEN EXPIRED (401)" else "HTTP $code"
                logError(sourceTag, url, null, "$reason attempt=${attempt + 1}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isMovieFoxSite) {
                    logError(sourceTag, url, e, "attempt=${attempt + 1}")
                }
            }
            if (attempt < effectiveRetries - 1) delay(250L)
        }
        return null
    }

    private suspend fun isValidManifestResponse(url: String, headers: Map<String, String>): Boolean {
        val isManifest = url.contains(".m3u8", true) || url.contains(".mpd", true) || url.contains("/playlist", true)
        if (!isManifest) return true
        return try {
            val res = app.get(url, headers = headers, timeout = 5L)
            if (res.code !in 200..299) {
                logError("Manifest", url, null, "HTTP ${res.code}")
                runCatching { res.okhttpResponse.close() }
                return false
            }
            val head = res.text.trimStart().take(150)
            runCatching { res.okhttpResponse.close() }
            val valid = head.startsWith("#EXTM3U") || head.contains("<MPD", ignoreCase = true)
            if (!valid) logError("Manifest", url, null, "Invalid manifest head: ${head.take(60)}")
            valid
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("Manifest", url, e, "Manifest check error")
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

    private fun safeUriEncode(raw: String?, allow: String? = null): String {
        val src = raw.orEmpty()
        return if (allow != null) Uri.encode(src, allow).orEmpty() else Uri.encode(src).orEmpty()
    }

    private suspend fun safeSubtitle(langRaw: String?, urlRaw: String?, baseHost: String = mainUrl): SubtitleFile? {
        val cleanUrl = normalizeUrl(urlRaw.orEmpty().trim(), baseHost)
        if (!cleanUrl.startsWith("http") || mfIsBrokenContainerUrl(cleanUrl)) return null
        val cleanLang = langRaw.orEmpty().trim().ifBlank { "English" }
        return newSubtitleFile(lang = cleanLang, url = cleanUrl)
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

    private suspend fun fetchTmdbFallbackHomeItems(requestUrl: String, page: Int): List<SearchResponse> {
        val isTv = requestUrl.contains("/tv", true) || requestUrl.contains("/anime", true) || requestUrl.contains("/dramas", true)
        val endpoint = when {
            requestUrl.contains("/trending", true) -> "$tmdbBaseUrl/trending/all/day?api_key=$tmdbApiKey&page=$page"
            requestUrl.contains("list=top_rated", true) && isTv -> "$tmdbBaseUrl/tv/top_rated?api_key=$tmdbApiKey&page=$page"
            requestUrl.contains("list=top_rated", true) -> "$tmdbBaseUrl/movie/top_rated?api_key=$tmdbApiKey&page=$page"
            requestUrl.contains("list=now_playing", true) -> "$tmdbBaseUrl/movie/now_playing?api_key=$tmdbApiKey&page=$page"
            requestUrl.contains("/anime", true) -> "$tmdbBaseUrl/discover/tv?api_key=$tmdbApiKey&with_genres=16&with_original_language=ja&sort_by=popularity.desc&page=$page"
            requestUrl.contains("lang=", true) -> {
                val langCode = Regex("lang=([a-z]{2})").find(requestUrl)?.groupValues?.get(1) ?: "hi"
                val typePath = if (isTv) "discover/tv" else "discover/movie"
                "$tmdbBaseUrl/$typePath?api_key=$tmdbApiKey&with_original_language=$langCode&sort_by=popularity.desc&page=$page"
            }
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
            val poster = if (pPath.startsWith("/")) "https://image.tmdb.org/t/p/w500$pPath" else null
            val detailUrl = if (itemIsTv) "$mainUrl/tv/series-$id" else "$mainUrl/movie/movie-$id"
            if (itemIsTv) {
                out.add(newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, detailUrl, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    private suspend fun fetchStremioHomeLists(ctx: Context, timeoutSec: Long): List<HomePageList> {
        if (!MovieFoxSettingsDialog.isServerEnabled(ctx, "nuvio")) return emptyList()
        val manifests = MovieFoxSettingsDialog.getNuvioManifests(ctx).filter { it.enabled }
        if (manifests.isEmpty()) return emptyList()
        val out = mutableListOf<HomePageList>()

        for (m in manifests) {
            try {
                val withoutQ = m.manifestUrl.substringBefore("?")
                val base = if (withoutQ.endsWith("/manifest.json", true)) {
                    withoutQ.dropLast("/manifest.json".length)
                } else {
                    withoutQ.trimEnd('/')
                }
                val qSuffix = if (m.manifestUrl.contains("?")) m.manifestUrl.substring(m.manifestUrl.indexOf('?')) else ""
                val manifestRaw = safeGetText(m.manifestUrl, timeoutSec = timeoutSec, sourceTag = "nuvio").orEmpty()
                val catalogs = runCatching { JSONObject(manifestRaw).optJSONArray("catalogs") }.getOrNull() ?: continue

                for (cIdx in 0 until minOf(catalogs.length(), 4)) {
                    val catObj = catalogs.optJSONObject(cIdx) ?: continue
                    val cType = catObj.safeStr("type", "movie")
                    val cId = catObj.safeStr("id")
                    val cName = catObj.safeStr("name", "Stremio").ifBlank { "Stremio" }
                    if (cId.isBlank()) continue

                    val catUrl = "$base/catalog/$cType/${safeUriEncode(cId)}.json$qSuffix"
                    val catRaw = safeGetText(catUrl, timeoutSec = timeoutSec, sourceTag = "nuvio").orEmpty()
                    val metas = runCatching { JSONObject(catRaw).optJSONArray("metas") }.getOrNull() ?: continue

                    val searchItems = mutableListOf<SearchResponse>()
                    for (mIdx in 0 until minOf(metas.length(), 20)) {
                        val meta = metas.optJSONObject(mIdx) ?: continue
                        val mTitle = meta.safeStr("name")
                        if (mTitle.isBlank()) continue
                        val mId = meta.safeStr("id")
                        val mPoster = meta.safeStr("poster").ifBlank { meta.safeStr("background") }.takeIf { it.startsWith("http") }
                        val itemUrl = if (mId.startsWith("tmdb:")) {
                            val cleanId = mId.removePrefix("tmdb:")
                            if (cType.equals("series", true) || cType.equals("tv", true)) "$mainUrl/tv/series-$cleanId"
                            else "$mainUrl/movie/movie-$cleanId"
                        } else {
                            "$base/meta/$cType/${safeUriEncode(mId)}.json$qSuffix"
                        }
                        if (cType.equals("series", true) || cType.equals("tv", true)) {
                            searchItems.add(newTvSeriesSearchResponse(mTitle, itemUrl, TvType.TvSeries) {
                                this.posterUrl = mPoster
                            })
                        } else {
                            searchItems.add(newMovieSearchResponse(mTitle, itemUrl, TvType.Movie) {
                                this.posterUrl = mPoster
                            })
                        }
                    }
                    if (searchItems.isNotEmpty()) {
                        out.add(HomePageList("$cName (${m.label})", searchItems))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("nuvio", m.manifestUrl, e, "Failed loading Stremio catalog")
            }
        }
        return out
    }

    private suspend fun fetchImportedCollectionHomeLists(ctx: Context, timeoutSec: Long): List<HomePageList> {
        val folders = MovieFoxSettingsDialog.getImportedCollectionFolders(ctx)
        if (folders.isEmpty()) return emptyList()
        val out = mutableListOf<HomePageList>()

        for (folder in folders.take(8)) {
            try {
                val isTv = folder.mediaType.equals("TV", true)
                val apiUrl = if (folder.sourceType == "COLLECTION" && folder.tmdbCollectionId > 0) {
                    "$tmdbBaseUrl/collection/${folder.tmdbCollectionId}?api_key=$tmdbApiKey"
                } else {
                    val endpoint = if (isTv) "discover/tv" else "discover/movie"
                    val sb = StringBuilder("$tmdbBaseUrl/$endpoint?api_key=$tmdbApiKey&sort_by=${safeEncode(folder.sortBy)}&page=1")
                    if (folder.withGenres.isNotBlank()) sb.append("&with_genres=${safeEncode(folder.withGenres)}")
                    if (folder.withKeywords.isNotBlank()) sb.append("&with_keywords=${safeEncode(folder.withKeywords)}")
                    if (folder.withLang.isNotBlank()) sb.append("&with_original_language=${safeEncode(folder.withLang)}")
                    sb.toString()
                }

                val raw = safeGetText(apiUrl, timeoutSec = timeoutSec, sourceTag = "Collection").orEmpty()
                val root = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                val results = root.optJSONArray("results") ?: root.optJSONArray("parts") ?: continue
                val responses = mutableListOf<SearchResponse>()
                for (i in 0 until minOf(results.length(), 18)) {
                    val r = results.optJSONObject(i) ?: continue
                    val id = r.optInt("id", 0)
                    if (id <= 0) continue
                    val t = r.safeStr("title").ifBlank { r.safeStr("name") }
                    if (t.isBlank()) continue
                    val pPath = r.safeStr("poster_path")
                    val poster = if (pPath.startsWith("/")) "https://image.tmdb.org/t/p/w500$pPath" else null
                    val detailUrl = if (isTv) "$mainUrl/tv/series-$id" else "$mainUrl/movie/movie-$id"
                    if (isTv) {
                        responses.add(newTvSeriesSearchResponse(t, detailUrl, TvType.TvSeries) { this.posterUrl = poster })
                    } else {
                        responses.add(newMovieSearchResponse(t, detailUrl, TvType.Movie) { this.posterUrl = poster })
                    }
                }
                if (responses.isNotEmpty()) {
                    out.add(HomePageList("⭐ ${folder.folderTitle}", responses))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("Collection", folder.folderTitle, e, "Collection row render failed")
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val ctx = CloudStreamApp.context
        val movieFoxEnabled = ctx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "moviefox") } ?: true
        val timeoutSec = currentTimeoutSec()

        if (!movieFoxEnabled) {
            if (ctx == null) {
                return newHomePageResponse(emptyList(), hasNext = false)
            }
            val combined = mutableListOf<HomePageList>()
            combined.addAll(MovieFoxCineStream.getHomeCategories(this, ctx, page, timeoutSec))
            if (page <= 1 && request.data.contains("/trending", true)) {
                combined.addAll(fetchStremioHomeLists(ctx, timeoutSec))
                combined.addAll(fetchImportedCollectionHomeLists(ctx, timeoutSec))
            }
            return newHomePageResponse(combined, hasNext = combined.isNotEmpty())
        }

        val categoryLabel = resolveCategoryLabel(request.name, request.data)
            ?: return newHomePageResponse(emptyList(), hasNext = false)

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

        val home = mutableListOf<HomePageList>()
        if (items.isNotEmpty()) home.add(HomePageList(categoryLabel, items))
        if (page <= 1 && ctx != null && request.data.contains("/trending", true)) {
            home.addAll(fetchStremioHomeLists(ctx, timeoutSec))
            home.addAll(fetchImportedCollectionHomeLists(ctx, timeoutSec))
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
        val ctx = CloudStreamApp.context
        val movieFoxEnabled = ctx?.let { MovieFoxSettingsDialog.isServerEnabled(it, "moviefox") } ?: true
        if (!movieFoxEnabled && ctx != null && MovieFoxSettingsDialog.isServerEnabled(ctx, "cinestream")) {
            val csResults = MovieFoxCineStream.search(this, query)
            if (csResults.isNotEmpty()) return csResults
        }

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
            val poster = if (pPath.startsWith("/")) "https://image.tmdb.org/t/p/w500$pPath" else null
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
        if (MovieFoxCineStream.isCineStreamData(url)) {
            MovieFoxCineStream.load(this, url, timeoutSec)?.let { return it }
        }

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
            if (id.isBlank()) {
                val fallbackTitle = url.substringAfterLast("/").substringBefore("?").ifBlank { "Title" }
                return if (!isTv) {
                    newMovieLoadResponse(fallbackTitle, url, TvType.Movie, encodeLoadData(url, fallbackTitle, null, null, false))
                } else {
                    newTvSeriesLoadResponse(fallbackTitle, url, type, emptyList())
                }
            }
            val tmdbPath = if (isTv) "tv/$id" else "movie/$id"
            val tmdbJsonStr = safeGetText("$tmdbBaseUrl/$tmdbPath?api_key=$tmdbApiKey&append_to_response=external_ids", timeoutSec = 7L, sourceTag = "TMDB").orEmpty()
            val obj = runCatching { JSONObject(tmdbJsonStr) }.getOrNull() ?: JSONObject()
            val title = obj.safeStr("title").ifBlank { obj.safeStr("name") }.ifBlank { "Title $id" }
            val imdbId = obj.optJSONObject("external_ids")?.safeStr("imdb_id").orEmpty().ifBlank { obj.safeStr("imdb_id") }
            val pPath = obj.safeStr("poster_path")
            val poster = if (pPath.startsWith("/")) "https://image.tmdb.org/t/p/w500$pPath" else null
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
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(encodeLoadData(url, title, id, imdbId, true, 1, 1)) {
                    this.name = "Episode 1"; this.season = 1; this.episode = 1
                })
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
            url.contains("fifa2026", true)
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

    private fun cleanSearchTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*\((?:19|20)\d{2}\)\s*"""), " ")
            .replace(Regex("""\s*\[[^\]]*\]\s*"""), " ")
            .replace(Regex("""(?i)\b(?:hindi\s*dubbed|dual\s*audio|full\s*movie|watch\s*online)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeTitleKey(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.lowercase()
            .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .replace(Regex("""(?i)\b(season|part|chapter|vol|volume|s|ep|episode)\s*\d+\b"""), " ")
            .replace(Regex("""(?i)\b(480p|720p|1080p|2160p|4k|web-?dl|webrip|bluray|hdrip|hevc|x264|x265|hindi|english|dubbed|dual\s*audio|multi\s*audio|watch|download|online|free)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun titleMatchesCandidate(candidateTitle: String?, targetTitle: String): Boolean {
        val cNorm = normalizeTitleKey(candidateTitle)
        val tNorm = normalizeTitleKey(targetTitle)
        if (cNorm.isBlank() || tNorm.isBlank()) return false
        if (cNorm == tNorm || cNorm.contains(tNorm) || tNorm.contains(cNorm)) return true

        val tBase = normalizeTitleKey(targetTitle.substringBefore(":").substringBefore(" - "))
        val cBase = normalizeTitleKey(candidateTitle?.substringBefore(":")?.substringBefore(" - "))
        if (tBase.length >= 3 && cBase.isNotBlank() && (cBase == tBase || cBase.contains(tBase) || tBase.contains(cBase))) {
            return true
        }

        val tWords = tNorm.split(" ").filter { it.length > 1 }.toSet()
        val cWords = cNorm.split(" ").filter { it.length > 1 }.toSet()
        if (tWords.isEmpty() || cWords.isEmpty()) return false
        return (tWords.intersect(cWords).size.toDouble() / tWords.size.toDouble()) >= 0.55
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
        return when {
            cleanServer.isNotBlank() && cleanInfo.isNotBlank() -> "$siteTag • $cleanServer ($cleanInfo)"
            cleanServer.isNotBlank() -> "$siteTag • $cleanServer"
            cleanInfo.isNotBlank() -> "$siteTag ($cleanInfo)"
            else -> siteTag
        }.take(68)
    }

    private fun extractCandidateMediaAndEmbedUrls(text: String, baseUrl: String): List<Pair<String, String>> {
        val found = LinkedHashMap<String, String>()
        Regex("""https?://[^\s"'<>\\)\]]+\.(?:m3u8|mpd|mp4|mkv|webm)(?:\?[^\s"'<>\\)\]]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m ->
                val u = normalizeUrl(m.value, baseUrl)
                if (!isJunkCandidateUrl(u)) found.putIfAbsent(u, "DIRECT")
            }

        val hostPattern = Regex(
            """https?://[^\s"'<>\\)\]]*(?:filemoon|streamtape|voe|dood|mixdrop|upstream|vidhide|streamwish|luluvdo|pixeldrain|gofile|hubcloud|vcloud|nexdrive|mobilejsr|xcloud|fastdl|fsl|speedostream|gdflix|gdlink|gdtot|driveseed|driveleech|filepress|dotflix|hblinks|hubdrive|howblogs|linksmod|wootly|videostr|streameeeeee|megaplay|vidwish|vidtube|modiplay|vibeplayer|vivibebe|bibiemb|iqsmartgames|vidsrc|embedsu|autoembed|nxstreamhub|itsnitrox|vibuxer|zephyrix|zephyrflick|watchanimeworld|acek-cdn)[^\s"'<>\\)\]]*""",
            RegexOption.IGNORE_CASE
        )
        hostPattern.findAll(text).forEach { m ->
            val u = normalizeUrl(m.value.trimEnd('.', ',', ';', ')', ']', '"', '\''), baseUrl)
            if (!isJunkCandidateUrl(u)) found.putIfAbsent(u, "HOST")
        }

        Regex("""["']?(?:url|file|streamUrl|videoUrl|hls|dash|link|src|videoSource|securedLink)["']?\s*[:=]\s*["'](https?://[^"'\s<>\\]+|/[^"'\s<>\\]+)["']""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m ->
                val u = normalizeUrl(m.groupValues[1], baseUrl)
                if (u.startsWith("http") && !isJunkCandidateUrl(u)) {
                    val kind = if (Regex("""(?i)\.(m3u8|mpd|mp4|mkv|webm)(\?|$)""").containsMatchIn(u)) "DIRECT" else "HOST"
                    found.putIfAbsent(u, kind)
                }
            }
        return found.entries.map { it.key to it.value }
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
                buildSourceLabel(siteTag, "Iqsmartgames", audioTag),
                m3u8,
                m3u8.contains(".m3u8", true),
                "1080p $audioTag",
                mapOf("Referer" to embedUrl, "Origin" to "https://streams.iqsmartgames.com", "User-Agent" to USER_AGENT),
                emitLink
            )
        }
    }

    private fun pickCustomExtractor(url: String): ExtractorApi? {
        val h = URI(url).host?.lowercase().orEmpty()
        if (h.isBlank() || h.contains("iqsmartgames")) return null
        return when {
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
            h.contains("pixeldrain.dev") && isExtOn("pixeldrain") -> PixeldrainDevExtractor()
            h.contains("pixeldrain") && isExtOn("pixeldrain") -> PixeldrainExtractor()
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
        emitLink: (ExtractorLink, Boolean) -> Unit,
        vegaOnlyFilter: Boolean = false
    ) {
        if (!embedUrl.startsWith("http") || isJunkCandidateUrl(embedUrl)) return
        if (embedUrl.contains("iqsmartgames", true)) {
            resolveIqsmartgamesDirect(siteTag, embedUrl, referer, audioTag, lc, emitLink)
            return
        }

        val wrappedCallback: (ExtractorLink) -> Unit = { ex ->
            val passVega = !vegaOnlyFilter ||
                MovieFoxSettingsDialog.isVegaAllowedHost("${ex.name} ${ex.source} ${ex.url}")
            if (passVega && !isJunkCandidateUrl(ex.url) && lc.seenUrls.add(ex.url)) {
                val labeled = buildSourceLabel(siteTag, ex.name.ifBlank { ex.source }, audioTag)
                val isStream = isStreamOrHlsLink(ex.url, ex.type)
                val outLink = ExtractorLink(
                    source = siteTag,
                    name = if (lc.streamOnly && isStream) "⚡ $labeled" else labeled,
                    url = ex.url,
                    referer = ex.referer.ifBlank { referer },
                    quality = if (ex.quality > 0) ex.quality else getQuality(ex.name),
                    headers = ex.headers,
                    extractorData = ex.extractorData,
                    type = ex.type
                )
                emitLink(outLink, isStream)
            }
        }

        if (GokuHDSources.isServerLink(embedUrl) && isExtOn("gokuhd")) {
            val epNum = if (lc.isTv) lc.episode.toIntOrNull()?.coerceAtLeast(1) else null
            val directList = try {
                GokuHDSources.resolve(embedUrl, epNum)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(siteTag, embedUrl, e, "GokuHDSources.resolve failed")
                emptyList()
            }
            if (directList.isNotEmpty()) {
                directList.forEach { srv ->
                    val passVega = !vegaOnlyFilter ||
                        MovieFoxSettingsDialog.isVegaAllowedHost("${srv.name} ${srv.url}")
                    if (passVega && !isJunkCandidateUrl(srv.url)) {
                        emitStream(
                            lc,
                            buildSourceLabel(siteTag, srv.name, audioTag),
                            srv.url,
                            isM3u8Stream(srv.url, ""),
                            "${srv.name} $audioTag",
                            srv.headers.ifEmpty { mapOf("Referer" to referer, "User-Agent" to USER_AGENT) },
                            emitLink
                        )
                    }
                }
                return
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
                logError(siteTag, embedUrl, e, "Global loadExtractor failed")
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
        maxItems: Int = 6,
        vegaOnlyFilter: Boolean = false
    ) {
        val gate = Semaphore(5)
        supervisorScope {
            candidates.filterNot { isJunkCandidateUrl(it.first) }.take(maxItems).map { (u, kind) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        withTimeoutOrNull(7_500L) {
                            try {
                                if (kind == "DIRECT") {
                                    if (!vegaOnlyFilter || MovieFoxSettingsDialog.isVegaAllowedHost(u)) {
                                        emitStream(
                                            lc,
                                            buildSourceLabel(siteTag, "", audioTag),
                                            u,
                                            isM3u8Stream(u, ""),
                                            audioTag,
                                            mapOf("Referer" to referer, "User-Agent" to USER_AGENT),
                                            emitLink
                                        )
                                    }
                                } else {
                                    resolveWithAllExtractors(siteTag, u, referer, audioTag, lc, emitSub, emitLink, vegaOnlyFilter)
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

        val isCs = MovieFoxCineStream.isCineStreamData(rawPageUrl) || MovieFoxCineStream.isCineStreamData(data)
        val cleanPageUrl = if (isCs) rawPageUrl else rawPageUrl.substringBefore("?")
        val urlId = if (isCs) null else extractSeriesId(cleanPageUrl)
        var jsonId: String? = null

        if (!isCs && (pageTitle.isBlank() || (tmdbId.isNullOrBlank() && urlId.isNullOrBlank() && imdbId.isBlank()))) {
            val rawHtml = withTimeoutOrNull(4500L) {
                safeGetText(cleanPageUrl, timeoutSec = 4L, retries = 1, sourceTag = "moviefox")
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

        if (!data.trimStart().startsWith("{") && !isCs) {
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

        val cleanedQuery = cleanSearchTitle(pageTitle).ifBlank { pageTitle }

        return LinkContext(
            pageTitle = pageTitle,
            searchQueryTitle = cleanedQuery,
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
            val fastBudgetMs = 10_000L
            val deepBudgetMs = 18_000L
            val maxConcurrency = (appCtx?.let { MovieFoxSettingsDialog.getParallelServers(it) } ?: 18).coerceIn(8, 36)
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
                    if (allowedByMode && allowedByQuality && link.url.startsWith("http")) {
                        synchronized(emitLock) {
                            found = true
                            callback(link)
                        }
                    }
                }
            }

            val orderedKeys = (appCtx?.let { MovieFoxSettingsDialog.getOrderedEnabledKeys(it) }
                ?: MovieFoxDialogHelperFallbackKeys).distinct()

            val prioritizedKeys = orderedKeys.filter { it.lowercase() in fastWaveKeys } +
                orderedKeys.filterNot { it.lowercase() in fastWaveKeys }

            supervisorScope {
                val tasks = mutableListOf<Deferred<Unit>>()
                prioritizedKeys.forEach { srvKey ->
                    val budget = if (srvKey.lowercase() in fastWaveKeys) fastBudgetMs else deepBudgetMs
                    tasks.add(async(Dispatchers.IO) {
                        workerSemaphore.withPermit {
                            withTimeoutOrNull(budget) {
                                try {
                                    runServerKey(srvKey, lc, isCasting, emitSub, emitLink)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logError(srvKey, lc.watchUrl, e, "Server error")
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

    private val MovieFoxDialogHelperFallbackKeys: List<String>
        get() = MovieFoxSettingsDialog.BASE_SERVERS.map { it.key }

    private suspend fun runServerKey(
        srvKey: String,
        lc: LinkContext,
        isCasting: Boolean,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        val keyLow = srvKey.lowercase()
        when (keyLow) {
            "moviefox" -> fetchMovieFoxApi("purstream", lc, emitSub, emitLink)
            "cinestream" -> {
                MovieFoxCineStream.loadStreams(
                    provider = this,
                    title = lc.searchQueryTitle.ifBlank { lc.pageTitle },
                    imdbId = lc.imdbId,
                    tmdbId = lc.candidateIds.firstOrNull(),
                    isTv = lc.isTv,
                    season = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    episode = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    timeoutSec = lc.timeoutSec,
                    subtitleCallback = emitSub,
                    callback = { ex -> emitLink(ex, isStreamOrHlsLink(ex.url, ex.type)) }
                )
            }
            "purstream", "castle", "watchout" -> fetchMovieFoxApi(keyLow, lc, emitSub, emitLink)
            "cinetv_api" -> fetchCineTvApi(lc, emitSub, emitLink)
            "cinetv_hls" -> fetchCineTvHls(lc, emitSub, emitLink)
            "vegamovies", "rogmovies" -> fetchVegaMoviesResolved(lc, emitSub, emitLink)
            "bollyflix" -> fetchBollyflix(lc, emitSub, emitLink)
            "sflix" -> fetchSflixFull(lc, emitSub, emitLink)
            "prmovies" -> fetchPrmoviesFull(lc, emitSub, emitLink)
            "hindidrama" -> fetchHindiDrama(lc, emitSub, emitLink)
            "animeworldindia" -> fetchAnimeWorldIndia(lc, emitSub, emitLink)
            "play4u" -> fetchPlay4u(lc, emitLink)
            "moviebox" -> fetchMovieBox(lc, emitSub, emitLink)
            "netnaija" -> fetchNetNaija(lc, emitSub, emitLink)
            "sportslive" -> fetchSportsLive(lc, emitLink)
            "nuvio" -> fetchNuvioAddons(lc, emitSub, emitLink)
            "hdhub4u" -> fetchHdHub4u(lc, emitSub, emitLink)
            "dramaverse" -> fetchDramaVerse(lc, emitSub, emitLink)
            "gokuhd" -> fetchGokuHd(lc, emitSub, emitLink)
            "hicine" -> fetchHiCine(lc, emitSub, emitLink)
            "hindmoviez" -> fetchHindMoviez(lc, emitSub, emitLink)
            "movielinkbd" -> fetchMoviesLinkBd(lc, emitSub, emitLink)
            "netmirror" -> fetchNetMirror(lc, emitSub, emitLink)
            "movy" -> fetchMovy(lc, emitSub, emitLink)
            "4khdhub" -> fetch4KhdHub(lc, emitSub, emitLink)
            "movies4u" -> fetchMovies4u(lc, emitSub, emitLink)
            "multimovies" -> fetchMultimovies(lc, emitSub, emitLink)
            in singleIndianMirrors -> {
                val (tag, base) = singleIndianMirrors[keyLow] ?: return
                fetchSingleIndianMirror(keyLow, tag, base, lc, emitSub, emitLink)
            }
            else -> fetchCustomCs3Server(srvKey, lc, isCasting, emitSub, emitLink)
        }
    }

    private suspend fun fetchAnimeWorldIndia(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val awBase = fetchDynamicDomain(animeWorldBaseUrl, "animeworldindia")
            val q = safeEncode(lc.searchQueryTitle)
            val searchHtml = safeGetText("$awBase/page/1/?s=$q", mapOf("Referer" to "$awBase/"), lc.timeoutSec, retries = 1, sourceTag = "animeworldindia").orEmpty()
            if (searchHtml.isBlank()) return

            val doc = Jsoup.parse(searchHtml, awBase)
            val matchedHref = doc.select("li.post a[href*=/series/], li.post a[href*=/movies/], article.post a[href*=/series/], article.post a[href*=/movies/]")
                .firstOrNull { a ->
                    val href = a.attr("href").trim()
                    val t = (a.closest("li.post, article.post")?.selectFirst("h2, h3")?.text() ?: a.attr("title") ?: a.text()).trim()
                    !href.endsWith("/series/") && !href.endsWith("/movies/") && t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                }?.attr("href")?.let { normalizeUrl(it, awBase) }.orEmpty()
            if (matchedHref.isBlank()) return

            val detailHtml = safeGetText(matchedHref, mapOf("Referer" to "$awBase/"), lc.timeoutSec, retries = 1, sourceTag = "animeworldindia").orEmpty()
            if (detailHtml.isBlank()) return
            val detailDoc = Jsoup.parse(detailHtml, matchedHref)

            val targetWatchUrl = if (lc.isTv) {
                val sNum = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val eNum = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
                var epHref = detailDoc.select("a[href*=/episode/]").firstOrNull { a ->
                    val h = a.attr("href")
                    val m = Regex("""(\d+)x(\d+)""").find(h)
                    m?.groupValues?.get(1)?.toIntOrNull() == sNum && m.groupValues.get(2).toIntOrNull() == eNum
                }?.attr("href")

                if (epHref.isNullOrBlank()) {
                    val postId = detailDoc.select("[data-post]").firstOrNull()?.attr("data-post").orEmpty()
                    if (postId.isNotBlank()) {
                        val ajaxHtml = safeGetText(
                            "$awBase/wp-admin/admin-ajax.php?action=action_select_season&season=$sNum&post=$postId",
                            mapOf("Referer" to awBase, "X-Requested-With" to "XMLHttpRequest"),
                            lc.timeoutSec,
                            retries = 1,
                            sourceTag = "animeworldindia"
                        ).orEmpty()
                        epHref = Jsoup.parse(ajaxHtml, awBase).select("a[href*=/episode/]").firstOrNull { a ->
                            val h = a.attr("href")
                            val m = Regex("""(\d+)x(\d+)""").find(h)
                            m?.groupValues?.get(2)?.toIntOrNull() == eNum
                        }?.attr("href")
                    }
                }
                epHref?.let { normalizeUrl(it, awBase) } ?: matchedHref
            } else {
                matchedHref
            }

            val watchHtml = if (targetWatchUrl == matchedHref) detailHtml
            else safeGetText(targetWatchUrl, mapOf("Referer" to matchedHref), lc.timeoutSec, retries = 1, sourceTag = "animeworldindia").orEmpty()
            if (watchHtml.isBlank()) return

            val iframes = Jsoup.parse(watchHtml, targetWatchUrl).select("iframe")
                .mapNotNull { el -> el.attr("data-src").ifBlank { el.attr("src") }.trim().takeIf { it.isNotBlank() } }
                .distinct()

            for (iframe in iframes) {
                if (iframe.contains("zephyrix", true) || iframe.contains("zephyrflick", true)) {
                    val fullIframe = normalizeUrl(iframe, awBase)
                    val videoId = Regex("/video/([a-f0-9]+)", RegexOption.IGNORE_CASE).find(fullIframe)?.groupValues?.get(1) ?: continue
                    val playerBase = Regex("(https?://[^/]+)").find(fullIframe)?.groupValues?.get(1) ?: continue
                    val postRes = runCatching {
                        app.post(
                            "$playerBase/player/index.php?data=$videoId&do=getVideo",
                            data = mapOf("hash" to videoId, "r" to "$awBase/"),
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to playerBase,
                                "Referer" to "$playerBase/video/$videoId",
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                                "User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA
                            ),
                            timeout = 12_000L
                        )
                    }.getOrNull() ?: continue
                    val vObj = runCatching { JSONObject(postRes.text) }.getOrNull() ?: continue
                    val rawSrc = vObj.safeStr("videoSource").ifBlank { vObj.safeStr("securedLink") }
                    if (rawSrc.isBlank()) continue
                    val streamUrl = if (rawSrc.startsWith("http")) rawSrc else "$playerBase$rawSrc"
                    emitStream(
                        lc = lc,
                        serverName = buildSourceLabel("Anime World India", "Zephyrix", "Multi Audio"),
                        streamUrl = streamUrl,
                        isHls = true,
                        qualityStr = "1080p Multi Audio",
                        customHeaders = mapOf(
                            "Accept" to "*/*",
                            "Referer" to "$playerBase/",
                            "User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA
                        ),
                        emitLink = emitLink
                    )
                } else {
                    resolveWithAllExtractors("Anime World India", normalizeUrl(iframe, awBase), targetWatchUrl, "Anime", lc, emitSub, emitLink)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("animeworldindia", animeWorldBaseUrl, e, "Anime World India fetch failed")
        }
    }

    private suspend fun fetchSportsLive(lc: LinkContext, emitLink: (ExtractorLink, Boolean) -> Unit) {
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val headers = mapOf(
                "User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA,
                "Accept" to "application/json",
                "Origin" to sportSiteUrl.trimEnd('/'),
                "Referer" to sportSiteUrl,
                "X-Device-Info" to "{}"
            )
            val raw = safeGetText("$sportApiBaseUrl/live/match-list-v5", headers, timeoutSec = 5L, retries = 1, sourceTag = "sportslive").orEmpty()
            val list = runCatching { JSONObject(raw).optJSONObject("data")?.optJSONArray("list") }.getOrNull() ?: return
            for (i in 0 until list.length()) {
                val m = list.optJSONObject(i) ?: continue
                val t1 = m.optJSONObject("team1")?.safeStr("name").orEmpty()
                val t2 = m.optJSONObject("team2")?.safeStr("name").orEmpty()
                val matchTitle = "$t1 vs $t2".trim()
                if (matchTitle.length < 4 || !titleMatchesCandidate(matchTitle, lc.pageTitle)) continue
                val playPath = m.safeStr("playPath")
                if (playPath.startsWith("http")) {
                    emitStream(lc, buildSourceLabel("SportsLive", "Live Main", "1080p"), playPath, true, "1080p", headers, emitLink)
                }
                m.optJSONArray("playSource")?.let { pArr ->
                    for (j in 0 until pArr.length()) {
                        val src = pArr.optJSONObject(j) ?: continue
                        val sPath = src.safeStr("path")
                        if (sPath.startsWith("http")) {
                            val chName = src.safeStr("title", "Channel ${j + 1}")
                            emitStream(lc, buildSourceLabel("SportsLive", chName, "720p"), sPath, sPath.contains("m3u8", true), "720p", headers, emitLink)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("sportslive", sportApiBaseUrl, e, "SportsLive fetch failed")
        }
    }

    private suspend fun fetchSingleIndianMirror(
        key: String,
        tag: String,
        base: String,
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val resolvedBase = fetchDynamicDomain(base, key)
            val q = safeEncode(lc.searchQueryTitle)
            val searchUrl = "$resolvedBase/?s=$q"
            val searchHtml = safeGetText(searchUrl, mapOf("Referer" to "$resolvedBase/"), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = key).orEmpty()
            if (searchHtml.isBlank()) return
            val doc = Jsoup.parse(searchHtml, resolvedBase)
            val matched = doc.select("article a[href], .post a[href], .ml-item a[href], h2 a[href], h3 a[href]")
                .firstOrNull { a ->
                    val t = (a.attr("title").ifBlank { a.selectFirst("h2, h3")?.text().orEmpty() }.ifBlank { a.text() }).trim()
                    val href = a.attr("href").trim()
                    href.startsWith("http") && !href.contains("/category/") && !href.contains("/tag/") &&
                        t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                } ?: return

            val postUrl = normalizeUrl(matched.attr("href"), resolvedBase)
            val postHtml = safeGetText(postUrl, mapOf("Referer" to "$resolvedBase/"), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = key).orEmpty()
            if (postHtml.isBlank()) return
            val audioTag = extractAudioSummary(matched.text(), lc.pageTitle)
            val candidates = extractCandidateMediaAndEmbedUrls(postHtml, postUrl)
            resolveCandidatesInParallel(tag, candidates, postUrl, audioTag, lc, emitSub, emitLink, maxItems = 6)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(key, base, e, "$tag fetch failed")
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
        val withAudio = if (audioSummary.isNotBlank() && !serverName.contains(audioSummary, ignoreCase = true)) {
            "$serverName [$audioSummary]"
        } else {
            serverName
        }

        val linkType = forceType ?: when {
            streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
            isHls || streamUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        val label = if (lc.streamOnly && isHls) "⚡ $withAudio" else withAudio
        val link = newExtractorLink(
            source = serverName,
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

    private suspend fun parseAndEmitApiJson(
        endpointName: String,
        rawText: String,
        lc: LinkContext,
        baseHeaders: Map<String, String>,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ): Boolean {
        var emitted = false
        val root = try {
            JSONObject(rawText)
        } catch (e: Exception) {
            logError(endpointName, lc.watchUrl, e, "JSON parse error")
            return false
        }
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
            if (isJunkCandidateUrl(streamUrl)) continue

            st.optJSONArray("subtitles")?.let { subs ->
                for (k in 0 until subs.length()) {
                    val sub = subs.optJSONObject(k) ?: continue
                    val rawSubUrl = sub.safeStr("url").ifBlank { sub.safeStr("file") }
                    val lang = sub.safeStr("label").ifBlank { sub.safeStr("language") }.ifBlank { sub.safeStr("lang", "English") }
                    safeSubtitle(lang, rawSubUrl)?.let { emitSub(it) }
                }
            }

            val extraHeaders = mutableMapOf<String, String>()
            st.optJSONObject("headers")?.let { hObj ->
                val keysArr = hObj.names()
                if (keysArr != null) {
                    for (ki in 0 until keysArr.length()) {
                        val k = keysArr.optString(ki)
                        val v = hObj.safeStr(k)
                        if (v.isNotBlank()) extraHeaders[k] = v
                    }
                }
            }

            val mergedHeaders = baseHeaders + extraHeaders
            val isHls = isM3u8Stream(streamUrl, st.safeStr("type"))
            val qualityStr = st.safeStr("quality", "HD")
            val finalLabel = st.safeStr("label").ifBlank { st.safeStr("title") }.ifBlank { "${endpointName.uppercase()} ($qualityStr)" }

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
            val rawText = safeGetText(apiUrl, lc.baseHeaders, timeoutSec = 4L, retries = 1, sourceTag = srvKey) ?: continue
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
        val headers = mapOf("Referer" to "${cineTvUrls.first()}", "Origin" to cineTvBaseUrl, "Accept" to "application/json, text/html, */*")
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
            val body = safeGetText(ep, headers, timeoutSec = 5L, retries = 1, sourceTag = "cinetv_api")?.trim().orEmpty()
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
        val headers = mapOf("Referer" to "$cineTvBaseUrl/en", "Origin" to cineTvBaseUrl, "Accept" to "*/*")
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
            val body = safeGetText(wUrl, headers, timeoutSec = 3L, retries = 1, sourceTag = "cinetv_hls")?.trim().orEmpty()
            if (body.isBlank()) continue
            if (body.startsWith("#EXTM3U")) {
                emitStream(lc, "CineTV HLS", wUrl, true, "1080p", headers, emitLink)
                break
            } else if (body.startsWith("{") && parseAndEmitApiJson("CineTV HLS", body, lc, headers, emitSub, emitLink)) {
                break
            }
        }
    }

    private suspend fun fetchVegaMoviesResolved(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeUriEncode(lc.searchQueryTitle)

        supervisorScope {
            vegamoviesDomains.map { dom ->
                async(Dispatchers.IO) {
                    try {
                        val sTag = if (dom.contains("rogmovies", true)) "rogmovies" else "vegamovies"
                        val raw = safeGetText("$dom/ts-search.php?q=$q&page=1", mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = sTag).orEmpty()
                        val hits = runCatching { JSONObject(raw).optJSONArray("hits") }.getOrNull() ?: return@async
                        val matchedPosts = mutableListOf<Pair<String, String>>()
                        for (i in 0 until hits.length()) {
                            val doc = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                            val t = doc.safeStr("post_title")
                            val p = doc.safeStr("permalink")
                            if (t.isNotBlank() && p.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)) {
                                matchedPosts.add(t to normalizeUrl(p, dom))
                            }
                        }

                        for ((chosenTitle, chosenUrl) in matchedPosts.take(2)) {
                            val postHtml = safeGetText(chosenUrl, mapOf("Referer" to "$dom/"), timeoutSec = 6L, retries = 1, sourceTag = sTag).orEmpty()
                            if (postHtml.isBlank()) continue
                            val audioTag = extractAudioSummary(chosenTitle)
                            val siteTag = if (dom.contains("rogmovies", true)) "RogMovies" else "VegaMovies"

                            val candidateHrefs = Jsoup.parse(postHtml, chosenUrl).select("a[href]")
                                .mapNotNull { el ->
                                    val href = el.attr("href").trim()
                                    val btnText = el.text().trim()
                                    if (href.startsWith("http") && !isJunkCandidateUrl(href) &&
                                        (MovieFoxSettingsDialog.isVegaAllowedHost("$btnText $href") || GokuHDSources.isServerLink(href))
                                    ) href else null
                                }
                                .distinct()
                                .take(8)

                            val pairs = candidateHrefs.map { it to "HOST" }
                            resolveCandidatesInParallel(
                                siteTag = siteTag,
                                candidates = pairs,
                                referer = chosenUrl,
                                audioTag = audioTag,
                                lc = lc,
                                emitSub = emitSub,
                                emitLink = emitLink,
                                maxItems = 8,
                                vegaOnlyFilter = true
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logError("vegamovies", dom, e, "VegaMovies fetch failed")
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun fetchHindiDrama(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val encodedQuery = safeEncode(lc.searchQueryTitle)
            val searchHtml = safeGetText("$hindiDramaBaseUrl/search.php?q=$encodedQuery", mapOf("Referer" to "$hindiDramaBaseUrl/"), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "hindidrama").orEmpty()
            if (searchHtml.isBlank()) return

            val doc = Jsoup.parse(searchHtml, hindiDramaBaseUrl)
            val matchedHref = doc.select(".grid .card a, .ac a.it").firstOrNull { el ->
                val t = el.selectFirst("h3, .t")?.text()?.trim().orEmpty().ifBlank { el.attr("title").trim() }
                t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
            }?.attr("href")?.let { normalizeUrl(it, hindiDramaBaseUrl) }.orEmpty()
            if (matchedHref.isBlank()) return

            val detailHtml = safeGetText(matchedHref, mapOf("Referer" to "$hindiDramaBaseUrl/"), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "hindidrama").orEmpty()
            if (detailHtml.isBlank()) return
            val detailDoc = Jsoup.parse(detailHtml, matchedHref)

            val targetPageUrl = if (lc.isTv) {
                val targetEp = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
                detailDoc.select(".eplist ul li a, a[href*='/hindi-dubbed/watch-']").firstOrNull { a ->
                    val epTxt = a.text().trim()
                    val num = Regex("""(?:Episode|Ep|E|e)\s*(\d+)""").find(epTxt)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""-e(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                    num == targetEp
                }?.attr("href")?.let { normalizeUrl(it, hindiDramaBaseUrl) } ?: matchedHref
            } else {
                matchedHref
            }

            val watchHtml = if (targetPageUrl == matchedHref) detailHtml
            else safeGetText(targetPageUrl, mapOf("Referer" to matchedHref), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "hindidrama").orEmpty()
            if (watchHtml.isBlank()) return
            val watchDoc = Jsoup.parse(watchHtml, targetPageUrl)

            val internalEmbedSrc = watchDoc.selectFirst("#player iframe, iframe[src*='/embed/']")?.attr("src")?.trim()
                ?: Regex("""["']((?:https?:\\?/\\?/[^"'\s<>]+)?\\?/embed\\?/[^"'\s<>]+)["']""")
                    .find(watchHtml)?.groupValues?.get(1)?.replace("\\/", "/")

            val playerPageUrl = if (!internalEmbedSrc.isNullOrEmpty()) normalizeUrl(internalEmbedSrc, hindiDramaBaseUrl) else targetPageUrl
            val playerHtml = if (!internalEmbedSrc.isNullOrEmpty()) {
                safeGetText(playerPageUrl, mapOf("Referer" to targetPageUrl), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "hindidrama").orEmpty()
            } else {
                watchHtml
            }
            if (playerHtml.isBlank()) return

            val playerDoc = Jsoup.parse(playerHtml, playerPageUrl)
            val serverUrls = linkedMapOf<String, String>()

            playerDoc.select(".srv, [data-src], [data-url], [data-embed], [data-link], [data-video]").forEach { el ->
                val sName = el.text().trim().ifEmpty { "HindiDrama" }
                val rawUrl = listOf("data-src", "data-url", "data-embed", "data-link", "data-video")
                    .firstNotNullOfOrNull { attr -> el.attr(attr).trim().takeIf { it.isNotEmpty() } }
                    ?: Regex("""['"]((?:https?:)?//[^'"]+|/[^'"]+)['"]""").find(el.attr("onclick"))?.groupValues?.get(1)
                if (!rawUrl.isNullOrEmpty()) {
                    val full = normalizeUrl(rawUrl, hindiDramaBaseUrl)
                    if (!full.contains("hindidrama.net/hindi-dubbed")) serverUrls[full] = sName
                }
            }
            playerDoc.select("iframe").forEach { el ->
                val src = el.attr("data-src").ifEmpty { el.attr("src") }.trim()
                if (src.isNotEmpty() && !src.startsWith("about:")) {
                    serverUrls.putIfAbsent(normalizeUrl(src, hindiDramaBaseUrl), "Embed")
                }
            }

            for ((rawEmbed, label) in serverUrls) {
                val cleanEmbed = rawEmbed
                    .replace(Regex("""(?i)(streamtape\.(?:to|net|xyz)|shavetape\.cash|strcloud\.link|strtape\.tech|stape\.fun)"""), "streamtape.com")
                    .replace("streamtape.com/v/", "streamtape.com/e/", ignoreCase = true)

                resolveWithAllExtractors("HindiDrama", cleanEmbed, playerPageUrl, label, lc, emitSub, emitLink)

                val embedHtml = safeGetText(cleanEmbed, mapOf("Referer" to playerPageUrl), minOf(lc.timeoutSec, 5L), retries = 1, sourceTag = "hindidrama").orEmpty()
                if (embedHtml.isBlank()) continue
                val unpacked = JsUnpacker(embedHtml).unpack() ?: embedHtml
                val combined = StringBuilder("$embedHtml\n$unpacked")
                Regex("""["']([A-Za-z0-9+/=]{60,})["']""").findAll(embedHtml).forEach { b64 ->
                    runCatching {
                        val dec = String(Base64.decode(b64.groupValues[1], Base64.DEFAULT))
                        if (dec.contains("http")) combined.append("\n").append(dec)
                    }
                }
                Regex("""["'](https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
                    .findAll(combined.toString())
                    .map { it.groupValues[1].replace("\\/", "/") }
                    .filterNot { it.contains("preview") || it.contains("thumb") }
                    .forEach { stUrl ->
                        emitStream(
                            lc,
                            buildSourceLabel("HindiDrama", label, "Hindi"),
                            stUrl,
                            stUrl.contains(".m3u8", true),
                            "1080p Hindi",
                            mapOf("Referer" to cleanEmbed, "User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA),
                            emitLink
                        )
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("hindidrama", hindiDramaBaseUrl, e, "HindiDrama fetch failed")
        }
    }

    private suspend fun fetchBollyflix(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeEncode(lc.searchQueryTitle)
        val dynUrl = fetchDynamicDomain("https://new1.bollyflix.tours", "bollyflix")
        val domains = (bollyflixMirrors + listOf(dynUrl)).distinct()

        for (dom in domains) {
            try {
                val searchHtml = safeGetText("$dom/search/$q/page/1/", mapOf("Referer" to "$dom/"), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "bollyflix").orEmpty()
                if (searchHtml.isBlank()) continue
                val doc = Jsoup.parse(searchHtml, dom)
                val card = doc.select("div.post-cards > article, article").firstOrNull { art ->
                    val t = art.select("a").attr("title").replace("Download ", "").trim()
                        .ifBlank { art.selectFirst("img")?.attr("alt").orEmpty() }
                    t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                } ?: continue

                val cardTitle = card.select("a").attr("title").replace("Download ", "").trim()
                val audioTag = extractAudioSummary(cardTitle)
                val postUrl = normalizeUrl(card.select("a").attr("href"), dom)
                if (postUrl.isBlank()) continue

                val postHtml = safeGetText(postUrl, mapOf("Referer" to "$dom/"), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "bollyflix").orEmpty()
                if (postHtml.isBlank()) continue
                val postDoc = Jsoup.parse(postHtml, postUrl)

                val resolvedTargets = supervisorScope {
                    postDoc.select("a.dl, a.maxbutton-download-links, a.btnn").map { el ->
                        async(Dispatchers.IO) {
                            var href = el.attr("href").trim()
                            if (href.isBlank()) return@async null
                            if (!href.contains("fastdlserver", true) && href.contains("?id=")) {
                                val id = href.substringAfterLast("id=")
                                val bypassHtml = safeGetText("https://web.sidexfee.com/?id=$id", mapOf("Referer" to postUrl), timeoutSec = 5L, retries = 1, sourceTag = "bollyflix").orEmpty()
                                val encUrl = Regex("""link":"([^"]+)""").find(bypassHtml)?.groupValues?.get(1).orEmpty()
                                if (encUrl.isNotBlank()) {
                                    val decoded = runCatching { base64Decode(encUrl.replace("\\/", "/")) }.getOrNull().orEmpty()
                                    if (decoded.startsWith("http")) href = decoded
                                }
                            }
                            normalizeUrl(href, dom)
                        }
                    }.awaitAll().filterNotNull().distinct().take(6)
                }

                resolveCandidatesInParallel(
                    "Bollyflix",
                    resolvedTargets.map { it to "HOST" },
                    postUrl,
                    audioTag,
                    lc,
                    emitSub,
                    emitLink,
                    maxItems = 6
                )
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("bollyflix", dom, e, "Bollyflix mirror failed")
            }
        }
    }

    private fun sflixMd5(input: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }

    private fun sflixClientToken(ts: Long = System.currentTimeMillis()): String {
        val s = ts.toString()
        return "$s,${sflixMd5(s.reversed().toByteArray())}"
    }

    private fun sflixSignature(url: String, ts: Long): String {
        return runCatching {
            val parsed = URI(url)
            val path = parsed.path.orEmpty()
            val query = parsed.rawQuery.orEmpty().split("&").filter { it.isNotBlank() }.sorted().joinToString("&")
            val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
            val canonical = "GET\napplication/json\napplication/json\n\n$ts\n\n$canonicalUrl"
            val secretBytes = Base64.decode(String(Base64.decode(sflixSecretB64, Base64.DEFAULT)), Base64.DEFAULT)
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
            val sig = Base64.encodeToString(mac.doFinal(canonical.toByteArray()), Base64.NO_WRAP)
            "$ts|2|$sig"
        }.getOrDefault("")
    }

    private suspend fun fetchSflixFull(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val sflixMainUrl = "https://sflix.film"
            val encodedTitle = safeEncode(lc.searchQueryTitle)
            val searchHtml = safeGetText(
                "$sflixMainUrl/search-result?keyword=$encodedTitle",
                mapOf("Referer" to "$sflixMainUrl/"),
                minOf(lc.timeoutSec, 6L),
                retries = 1,
                sourceTag = "sflix"
            ).orEmpty()
            if (searchHtml.isBlank()) return

            val doc = Jsoup.parse(searchHtml, sflixMainUrl)
            val detailLinks = doc.select("a.video-item[href*='/detail/'], a[href*='/detail/']")
                .filter { el ->
                    val t = (el.selectFirst("h3.text, .video-name, h3")?.text() ?: el.attr("title")).trim()
                    titleMatchesCandidate(t, lc.pageTitle)
                }
                .map { it.attr("href") }
                .distinct()
                .take(2)

            for (detailLink in detailLinks) {
                val fullDetailUrl = normalizeUrl(detailLink, sflixMainUrl)
                val subjectId = Regex("""[?&]id=(\d+)""").find(fullDetailUrl)?.groupValues?.get(1).orEmpty()
                val detailPath = fullDetailUrl.substringAfter("/detail/").substringBefore("?")
                if (subjectId.isEmpty()) continue

                val se = if (lc.isTv) lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
                val ep = if (lc.isTv) lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
                val referer = "$sflixPlayerUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"

                for (domain in sflixWebDomains) {
                    val playUrl = "$domain/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath&streamSignType=1"
                    val ts = System.currentTimeMillis()
                    val playHeaders = mapOf(
                        "User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA,
                        "Referer" to referer,
                        "Origin" to sflixPlayerUrl,
                        "Accept" to "application/json",
                        "x-client-token" to sflixClientToken(ts),
                        "x-tr-signature" to sflixSignature(playUrl, ts),
                        "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                        "x-request-lang" to "en"
                    )
                    val playText = safeGetText(playUrl, playHeaders, minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "sflix").orEmpty()
                    val dataObj = runCatching { JSONObject(playText).optJSONObject("data") }.getOrNull() ?: continue
                    var any = false

                    listOf("hls" to "HLS", "streams" to "MP4", "dash" to "DASH").forEach { (k, kind) ->
                        dataObj.optJSONArray(k)?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                val sUrl = item.safeStr("url")
                                if (sUrl.isBlank() || isYouTubeOrBlockedStream(sUrl)) continue
                                val signCookie = item.safeStr("signCookie")
                                val signHeaderKey = item.safeStr("signHeaderKey", "X-MB-Token")
                                val maxRes = item.safeStr("resolutions").split(",").mapNotNull { it.trim().toIntOrNull() }.maxOrNull() ?: 720
                                val stHeaders = mutableMapOf(
                                    "Origin" to sflixPlayerUrl,
                                    "Referer" to "$sflixPlayerUrl/",
                                    "User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA
                                )
                                if (signCookie.isNotBlank()) {
                                    stHeaders[signHeaderKey] = signCookie
                                    stHeaders["Cookie"] = signCookie
                                }
                                val fType = when (kind) {
                                    "DASH" -> ExtractorLinkType.DASH
                                    "HLS" -> ExtractorLinkType.M3U8
                                    else -> ExtractorLinkType.VIDEO
                                }
                                emitStream(
                                    lc = lc,
                                    serverName = "Sflix [$kind ${maxRes}p]",
                                    streamUrl = sUrl,
                                    isHls = kind != "MP4",
                                    qualityStr = "${maxRes}p",
                                    customHeaders = stHeaders,
                                    emitLink = emitLink,
                                    forceType = fType
                                )
                                any = true
                            }
                        }
                    }
                    if (any) return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("sflix", "https://sflix.film", e, "Sflix fetch failed")
        }
    }

    private suspend fun fetchPrmoviesFull(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = lc.searchQueryTitle.trim().replace(Regex("\\s+"), "+")
        val candidates = (listOf(activePrmoviesBase) + prmoviesMirrors).distinct()

        for (dom in candidates) {
            try {
                val searchHtml = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "prmovies").orEmpty()
                if (searchHtml.isBlank()) continue
                activePrmoviesBase = dom

                val matchedCards = Jsoup.parse(searchHtml, dom).select(".ml-item").filter { c ->
                    val a = c.selectFirst("a.ml-mask") ?: return@filter false
                    val t = (a.attr("oldtitle").ifBlank { a.attr("title") }.ifBlank { c.selectFirst(".mli-info h2")?.text().orEmpty() }).trim()
                    t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                }.take(2)
                if (matchedCards.isEmpty()) continue

                for (card in matchedCards) {
                    val detailUrl = card.selectFirst("a.ml-mask")?.attr("href")?.let { normalizeUrl(it, dom) }.orEmpty()
                    if (detailUrl.isBlank()) continue
                    val detailHtml = safeGetText(detailUrl, mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "prmovies").orEmpty()
                    if (detailHtml.isBlank()) continue
                    val doc = Jsoup.parse(detailHtml, detailUrl)

                    val embedPairs = mutableListOf<Pair<String, String>>()
                    doc.select("#player2 iframe[src], #content-embed iframe[src], div.movieplay iframe[src], #list-downloads a[href*=speedostream]")
                        .forEach { el ->
                            val src = (el.attr("src").ifBlank { el.attr("href") }).trim()
                            if (src.isBlank()) return@forEach
                            val cleanEmbed = normalizeUrl(src, dom)
                            if (cleanEmbed.contains("speedostream", true)) {
                                val spHtml = safeGetText(cleanEmbed, mapOf("Referer" to detailUrl), timeoutSec = 5L, retries = 1, sourceTag = "prmovies").orEmpty()
                                val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(spHtml)?.groupValues?.get(1)
                                if (!m3u8.isNullOrBlank()) {
                                    emitStream(lc, "Prmovies • Speedostream", m3u8, true, "1080p", mapOf("Referer" to cleanEmbed), emitLink)
                                }
                            } else if (!isYouTubeOrBlockedStream(cleanEmbed)) {
                                embedPairs.add(cleanEmbed to "HOST")
                            }
                        }

                    val allPairs = (embedPairs + extractCandidateMediaAndEmbedUrls(detailHtml, detailUrl)).distinctBy { it.first }
                    resolveCandidatesInParallel("Prmovies", allPairs, detailUrl, "", lc, emitSub, emitLink, maxItems = 6)
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("prmovies", dom, e, "Prmovies mirror failed")
            }
        }
    }

    private suspend fun fetchDramaVerse(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val q = safeEncode(lc.searchQueryTitle)
            val searchHtml = safeGetText("$dramaVerseBaseUrl/?s=$q", mapOf("Referer" to "$dramaVerseBaseUrl/"), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "dramaverse").orEmpty()
            if (searchHtml.isBlank()) return
            val doc = Jsoup.parse(searchHtml, dramaVerseBaseUrl)
            val postUrl = doc.select("h2.post-title a, h3.post-title a, a.post-thumb")
                .firstOrNull { el ->
                    val t = el.text().trim().ifEmpty { el.attr("aria-label").substringBefore("|").trim() }
                    t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                }?.attr("href")?.let { normalizeUrl(it, dramaVerseBaseUrl) }.orEmpty()
            if (postUrl.isBlank()) return

            val postHtml = safeGetText(postUrl, mapOf("Referer" to "$dramaVerseBaseUrl/"), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "dramaverse").orEmpty()
            if (postHtml.isBlank()) return
            val candidates = extractCandidateMediaAndEmbedUrls(postHtml, postUrl)
            resolveCandidatesInParallel("DramaVerse", candidates, postUrl, "", lc, emitSub, emitLink, maxItems = 6)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("dramaverse", dramaVerseBaseUrl, e, "DramaVerse fetch failed")
        }
    }

    private suspend fun fetchGokuHd(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val q = safeEncode(lc.searchQueryTitle)
            val raw = safeGetText("$gokuHdBaseUrl/search.php?q=$q&page=1", mapOf("Referer" to "$gokuHdBaseUrl/"), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "gokuhd").orEmpty()
            val hits = runCatching { JSONObject(raw).optJSONArray("hits") }.getOrNull() ?: return

            for (i in 0 until hits.length()) {
                val d = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                val t = d.safeStr("post_title")
                val p = d.safeStr("permalink")
                if (t.isBlank() || p.isBlank() || !titleMatchesCandidate(t, lc.pageTitle)) continue
                val postUrl = normalizeUrl(p, gokuHdBaseUrl)
                val postHtml = safeGetText(postUrl, mapOf("Referer" to "$gokuHdBaseUrl/"), minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "gokuhd").orEmpty()
                if (postHtml.isBlank()) continue
                val audioTag = extractAudioSummary(t)
                val links = Jsoup.parse(postHtml, postUrl).select("a[href]")
                    .map { it.attr("href").trim() }
                    .filter { GokuHDSources.isServerLink(it) }
                    .distinct()
                    .map { it to "HOST" }
                resolveCandidatesInParallel("GokuHD", links, postUrl, audioTag, lc, emitSub, emitLink, maxItems = 6)
                break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("gokuhd", gokuHdBaseUrl, e, "GokuHD fetch failed")
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
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val p4Base = fetchDynamicDomain(play4uBaseUrl, "play4u")
            val targetSeason = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val targetEpisode = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val p4Headers = mapOf("Referer" to "$p4Base/", "Accept" to "application/json, text/html;q=0.9, */*;q=0.8")
            val p4StreamHeaders = mapOf("Referer" to "$p4Base/", "Origin" to p4Base, "User-Agent" to USER_AGENT)

            var pageHtml: String? = null
            if (lc.imdbId.startsWith("tt") && lc.imdbId.length > 2) {
                val directUrl = if (lc.isTv) "$p4Base/series/${lc.imdbId}" else "$p4Base/watch/${lc.imdbId}"
                val cand = safeGetText(directUrl, p4Headers, timeoutSec = 4L, retries = 1, sourceTag = "play4u")
                if (!cand.isNullOrBlank()) {
                    val ok = if (lc.isTv) extractWindowJsonObject(cand, "__PA_SERIES__") != null
                    else extractWindowJsonObject(cand, "__PA_PLAY__")?.optBoolean("ok") == true
                    if (ok) pageHtml = cand
                }
            }

            if (pageHtml == null) {
                val searchText = safeGetText("$p4Base/api/search.php?q=${safeEncode(lc.searchQueryTitle)}&page=1&per_page=30", p4Headers, timeoutSec = 5L, retries = 1, sourceTag = "play4u").orEmpty()
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
                        val cand = safeGetText("$p4Base$watchPath", p4Headers, timeoutSec = 4L, retries = 1, sourceTag = "play4u") ?: continue
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
                    }.let { if (it.isNotBlank()) normalizeUrl(it, p4Base) else "" }
                    if (linkUrl.isBlank() || !linkUrl.startsWith("http")) continue
                    val srvLabel = sObj.safeStr("label", "Play4u")
                    emitStream(lc, buildSourceLabel("Play4u", srvLabel, ""), linkUrl, true, null, p4StreamHeaders, emitLink)
                }
            } else {
                val seasonsObj = extractWindowJsonObject(pageHtml, "__PA_SERIES__")?.optJSONObject("seasons") ?: return
                var matchedSeason: JSONObject? = null
                val sKeys = seasonsObj.names()
                if (sKeys != null) {
                    for (si in 0 until sKeys.length()) {
                        val k = sKeys.optString(si)
                        val o = seasonsObj.optJSONObject(k) ?: continue
                        if (o.optInt("number", -1) == targetSeason || k.toIntOrNull() == targetSeason) { matchedSeason = o; break }
                    }
                }
                val episodesObj = (matchedSeason ?: if (seasonsObj.length() == 1 && sKeys != null) seasonsObj.optJSONObject(sKeys.optString(0)) else null)
                    ?.optJSONObject("episodes") ?: return
                val eKeys = episodesObj.names()
                if (eKeys != null) {
                    for (ei in 0 until eKeys.length()) {
                        val epObj = episodesObj.optJSONObject(eKeys.optString(ei)) ?: continue
                        if (epObj.optInt("number", -1) != targetEpisode) continue
                        val linkUrl = play4uUnseal(epObj.safeStr("hls"), sealKey).let { if (it.isNotBlank()) normalizeUrl(it, p4Base) else "" }
                        if (linkUrl.startsWith("http")) {
                            emitStream(lc, buildSourceLabel("Play4u", "HLS", "Ep $targetEpisode"), linkUrl, true, null, p4StreamHeaders, emitLink)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("play4u", play4uBaseUrl, e, "Play4u fetch failed")
        }
    }

    private suspend fun fetchHdHub4u(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val q = safeUriEncode(lc.searchQueryTitle)
            val activeBase = fetchDynamicDomain(hdhub4uDomains.first(), "hdhub4u", "HDHUB4u")
            val tsUrl = "$hdhub4uTypesenseUrl?q=$q&query_by=post_title,category&query_by_weights=4,2&sort_by=sort_by_date:desc&limit=15&highlight_fields=none&use_cache=true&page=1"
            val tsRaw = safeGetText(tsUrl, mapOf("Referer" to "$activeBase/"), timeoutSec = 5L, retries = 1, sourceTag = "hdhub4u").orEmpty()
            val hits = runCatching { JSONObject(tsRaw).optJSONArray("hits") }.getOrNull()
            val detailEntries = mutableListOf<Pair<String, String>>()

            if (hits != null) {
                for (i in 0 until hits.length()) {
                    val d = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                    val t = d.safeStr("post_title")
                    val p = d.safeStr("permalink")
                    if (t.isNotBlank() && p.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)) {
                        val fixed = if (p.startsWith("http", true)) {
                            val path = runCatching { URI(p).path }.getOrNull().orEmpty()
                            if (path.isNotBlank()) activeBase + path else p
                        } else {
                            normalizeUrl(p, activeBase)
                        }
                        detailEntries.add(fixed to extractAudioSummary(t))
                    }
                }
            }

            if (detailEntries.isEmpty()) {
                for (dom in (listOf(activeBase) + hdhub4uDomains).distinct()) {
                    val html = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "hdhub4u").orEmpty()
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
                val html = safeGetText(dUrl, mapOf("Referer" to "$activeBase/"), timeoutSec = 5L, retries = 1, sourceTag = "hdhub4u").orEmpty()
                if (html.isBlank()) continue
                val candidates = extractCandidateMediaAndEmbedUrls(html, dUrl)
                resolveCandidatesInParallel("HDHub4u", candidates, dUrl, audioTag, lc, emitSub, emitLink, maxItems = 6)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("hdhub4u", hdhub4uDomains.first(), e, "HDHub4u fetch failed")
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
        if (lc.searchQueryTitle.isBlank()) return
        val se = if (lc.isTv) lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
        val ep = if (lc.isTv) lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0

        for (apiHost in activeMovieBoxHosts()) {
            val bffBase = "https://$apiHost/wefeed-h5api-bff"
            try {
                val headers = mapOf(
                    "User-Agent" to movieBoxUa, "Accept" to "application/json",
                    "Origin" to movieBoxSiteUrl, "Referer" to "$movieBoxSiteUrl/",
                    "X-Request-Lang" to "en", "X-Client-Info" to "{\"timezone\":\"Asia/Kolkata\"}",
                    "X-Client-Token" to movieBoxClientToken(), "Content-Type" to "application/json"
                )
                val searchRes = runCatching {
                    app.post("$bffBase/subject/search", headers = headers, json = mapOf("keyword" to lc.searchQueryTitle, "page" to 1, "perPage" to 15), timeout = 5L)
                }.getOrNull() ?: continue
                if (searchRes.code !in 200..299) {
                    runCatching { searchRes.okhttpResponse.close() }
                    continue
                }
                val searchRaw = searchRes.text
                val itemsArr = runCatching { JSONObject(searchRaw).optJSONObject("data")?.optJSONArray("items") }.getOrNull() ?: continue

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
                val detailPath = matchedPath ?: continue
                val detailRaw = safeGetText("$bffBase/detail?detailPath=${safeEncode(detailPath)}", headers, timeoutSec = 5L, retries = 1, sourceTag = "moviebox").orEmpty()
                val subjectObj = runCatching { JSONObject(detailRaw).optJSONObject("data")?.optJSONObject("subject") }.getOrNull() ?: continue
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
                        "$bffBase/subject/play?subjectId=${safeEncode(dubId)}&se=$se&ep=$ep&detailPath=${safeEncode(dubPath)}",
                        headers + mapOf("X-Source" to "webNetnaijaSite"),
                        timeoutSec = 5L,
                        retries = 1,
                        sourceTag = "moviebox"
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
                                "$bffBase/subject/caption?format=MP4&id=${safeEncode(firstId)}&subjectId=${safeEncode(dubId)}&detailPath=${safeEncode(dubPath)}",
                                headers,
                                timeoutSec = 5L,
                                retries = 1,
                                sourceTag = "moviebox"
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
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("moviebox", bffBase, e, "MovieBox host failed")
            }
        }
    }

    private suspend fun fetchNetNaija(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeEncode(lc.searchQueryTitle)
        for (dom in (listOf(activeNetnaijaBase) + netnaijaDomains).distinct()) {
            try {
                val html = safeGetText("$dom/search?t=$q", mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "netnaija").orEmpty()
                if (html.isBlank()) continue
                activeNetnaijaBase = dom
                val postHref = Jsoup.parse(html, dom).select("article.sr-one h2 a, article h2.entry-title a, .search-results article a[href]")
                    .firstOrNull { titleMatchesCandidate(it.text().trim(), lc.pageTitle) }
                    ?.attr("href")?.let { normalizeUrl(it, dom) }.orEmpty()
                if (postHref.isBlank()) continue
                val postHtml = safeGetText(postHref, mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "netnaija").orEmpty()
                if (postHtml.isBlank()) continue
                resolveCandidatesInParallel("NetNaija", extractCandidateMediaAndEmbedUrls(postHtml, postHref), postHref, "", lc, emitSub, emitLink)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("netnaija", dom, e, "NetNaija fetch failed")
            }
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
            try {
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
                    val raw = safeGetText(epUrl, timeoutSec = minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = "nuvio").orEmpty()
                    val arr = runCatching { JSONObject(raw).optJSONArray("streams") }.getOrNull() ?: continue
                    for (i in 0 until arr.length()) {
                        val sObj = arr.optJSONObject(i) ?: continue
                        val u = normalizeUrl(sObj.safeStr("url"), base)
                        if (u.startsWith("http")) {
                            val lbl = sObj.safeStr("name", m.label)
                            val titleLine = sObj.safeStr("title").lines().firstOrNull().orEmpty()
                            emitStream(lc, buildSourceLabel("Stremio • ${m.label}", lbl, titleLine), u, isM3u8Stream(u, ""), "$lbl $titleLine", mapOf("Referer" to "$base/", "User-Agent" to USER_AGENT), emitLink)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("nuvio", m.manifestUrl, e, "Manifest stream fetch failed")
            }
        }
    }

    private suspend fun fetchHiCine(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val remoteBase = fetchDynamicDomain(hicineApiBases.first(), "hicine")
            val bases = (listOf(remoteBase) + hicineApiBases).distinct()
            var activeBase: String? = null
            var searchRaw = ""
            for (b in bases) {
                val h = mapOf("Referer" to "$b/", "Accept" to "application/json")
                val cand = safeGetText("$b/hapi/search?q=${safeEncode(lc.searchQueryTitle)}&page=1", h, timeoutSec = 4L, retries = 1, sourceTag = "hicine").orEmpty()
                if (cand.trimStart().startsWith("{")) {
                    activeBase = b
                    searchRaw = cand
                    break
                }
            }
            val base = activeBase ?: return
            val headers = mapOf("Referer" to "$base/", "Accept" to "application/json")
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
            val detailRaw = safeGetText("$base/hapi/t?c=${safeEncode(titleId)}", headers, timeoutSec = 4L, retries = 1, sourceTag = "hicine").orEmpty()
            val groups = runCatching { JSONObject(detailRaw).optJSONArray("groups") }.getOrNull() ?: return
            val targetEp = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1

            for (gIdx in 0 until minOf(groups.length(), 2)) {
                val grp = groups.optJSONObject(gIdx) ?: continue
                val gKey = grp.safeStr("k")
                if (gKey.isBlank()) continue
                val head = grp.safeStr("head")
                val qStr = grp.safeStr("quality", head)
                val grpRaw = safeGetText("$base/hapi/g?k=${safeEncode(gKey)}", headers, timeoutSec = 4L, retries = 1, sourceTag = "hicine").orEmpty()
                val gItems = runCatching { JSONObject(grpRaw).optJSONArray("items") }.getOrNull() ?: continue
                val chosenItem = (0 until gItems.length()).mapNotNull { gItems.optJSONObject(it) }
                    .firstOrNull { !lc.isTv || it.optInt("ep", -1) == targetEp }
                    ?: gItems.optJSONObject(0)
                    ?: continue
                val hosts = chosenItem.optJSONArray("hosts") ?: continue
                for (hIdx in 0 until minOf(hosts.length(), 1)) {
                    val hKey = hosts.optJSONObject(hIdx)?.safeStr("k").orEmpty()
                    if (hKey.isBlank()) continue
                    val dlRaw = safeGetText("$base/hapi/dl?k=${safeEncode(hKey)}", headers, timeoutSec = 4L, retries = 1, sourceTag = "hicine").orEmpty()
                    val opts = runCatching { JSONObject(dlRaw).optJSONArray("options") }.getOrNull() ?: continue
                    for (oIdx in 0 until minOf(opts.length(), 1)) {
                        val opt = opts.optJSONObject(oIdx) ?: continue
                        val goKey = opt.safeStr("ks").ifBlank { opt.safeStr("k") }
                        if (goKey.isBlank()) continue
                        val goRaw = safeGetText("$base/hapi/go?k=${safeEncode(goKey)}&m=u", headers, timeoutSec = 4L, retries = 1, sourceTag = "hicine").orEmpty()
                        val resolved = runCatching { JSONObject(goRaw).safeStr("url") }.getOrNull().orEmpty()
                        if (resolved.startsWith("http")) {
                            val srvLbl = opt.safeStr("name", "HiCine")
                            if (resolved.contains("pixeldrain", true)) {
                                val pid = resolved.substringBefore("?").trimEnd('/').substringAfterLast("/")
                                if (pid.isNotBlank()) emitStream(lc, buildSourceLabel("HiCine", "PixelDrain", qStr), "https://pixeldrain.dev/api/file/$pid", false, qStr, headers, emitLink)
                            } else if (resolved.contains("gofile.io", true)) {
                                resolveWithAllExtractors("HiCine", resolved, "$base/", qStr, lc, emitSub, emitLink)
                            } else {
                                emitStream(lc, buildSourceLabel("HiCine", srvLbl, qStr), resolved, isM3u8Stream(resolved, ""), qStr, headers, emitLink)
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("hicine", hicineApiBases.first(), e, "HiCine fetch failed")
        }
    }

    private suspend fun fetchHindMoviez(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeEncode(lc.searchQueryTitle)
        for (dom in hindmoviezDomains) {
            try {
                val html = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "hindmoviez").orEmpty()
                if (html.isBlank()) continue
                val postHref = Jsoup.parse(html, dom).select("h2.entry-title > a[href], article a[href]")
                    .firstOrNull { titleMatchesCandidate(it.text().trim(), lc.pageTitle) }
                    ?.attr("href")?.let { normalizeUrl(it, dom) }.orEmpty()
                if (postHref.isBlank()) continue
                val postHtml = safeGetText(postHref, mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "hindmoviez").orEmpty()
                if (postHtml.isBlank()) continue
                resolveCandidatesInParallel("HindMoviez", extractCandidateMediaAndEmbedUrls(postHtml, postHref), postHref, "", lc, emitSub, emitLink)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("hindmoviez", dom, e, "HindMoviez fetch failed")
            }
        }
    }

    private suspend fun fetchMoviesLinkBd(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeUriEncode(lc.searchQueryTitle)
        val dynDom = fetchDynamicDomain(movielinkbdDomains.first(), "movielinkbd")
        for (dom in (listOf(dynDom) + movielinkbdDomains).distinct()) {
            try {
                val html = safeGetText("$dom/search?q=$q", mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "movielinkbd").orEmpty()
                if (html.isBlank()) continue
                val card = Jsoup.parse(html, dom).select("div.movie-card, article").firstOrNull { el ->
                    val img = el.selectFirst("img")
                    val t = img?.attr("title")?.trim()?.takeIf { it.isNotBlank() } ?: el.selectFirst("a")?.text()?.trim().orEmpty()
                    t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
                } ?: continue
                val detailHref = card.selectFirst("a[href]")?.attr("href")?.let { normalizeUrl(it, dom) }.orEmpty()
                if (detailHref.isBlank()) continue
                val detailHtml = safeGetText(detailHref, mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "movielinkbd").orEmpty()
                if (detailHtml.isBlank()) continue
                resolveCandidatesInParallel("MovieLinkBD", extractCandidateMediaAndEmbedUrls(detailHtml, detailHref), detailHref, "", lc, emitSub, emitLink)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("movielinkbd", dom, e, "MovieLinkBD fetch failed")
            }
        }
    }

    private suspend fun fetchNetMirror(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeUriEncode(lc.searchQueryTitle)
        val dynDom = fetchDynamicDomain(netmirrorDomains.first(), "netmirror")
        for (dom in (listOf(dynDom) + netmirrorDomains).distinct()) {
            try {
                val headers = mapOf("Referer" to "$dom/home", "User-Agent" to USER_AGENT)
                for (path in listOf("/mobile", "/mobile/hs", "/mobile/pv")) {
                    val raw = safeGetText("$dom$path/search.php?s=$q", headers, timeoutSec = 5L, retries = 1, sourceTag = "netmirror").orEmpty()
                    val arr = runCatching { JSONObject(raw).optJSONArray("searchResult") }.getOrNull() ?: continue
                    for (i in 0 until minOf(arr.length(), 3)) {
                        val item = arr.optJSONObject(i) ?: continue
                        val t = item.safeStr("t")
                        val id = item.safeStr("id")
                        if (id.isNotBlank() && t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)) {
                            val plRaw = safeGetText("$dom$path/playlist.php?id=${safeEncode(id)}&t=${safeEncode(t)}", headers, timeoutSec = 5L, retries = 1, sourceTag = "netmirror").orEmpty()
                            extractCandidateMediaAndEmbedUrls(plRaw, dom).take(6).forEach { (u, kind) ->
                                if (kind == "DIRECT") emitStream(lc, "NetMirror", u, isM3u8Stream(u, ""), null, headers, emitLink)
                            }
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("netmirror", dom, e, "NetMirror fetch failed")
            }
        }
    }

    private suspend fun fetchMovy(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        val tmdbId = lc.candidateIds.firstOrNull() ?: return
        if (lc.searchQueryTitle.isBlank()) return
        try {
            val activeMovyUrl = fetchDynamicDomain(movyMainUrl, "movy")
            val headers = mapOf("Referer" to "$activeMovyUrl/", "Origin" to activeMovyUrl, "User-Agent" to USER_AGENT)
            val seedRaw = safeGetText("$movyStreamApiBase/seed?mediaId=${safeEncode(tmdbId)}", headers, timeoutSec = 4L, retries = 1, sourceTag = "movy").orEmpty()
            val seed = runCatching { JSONObject(seedRaw).safeStr("seed") }.getOrNull().orEmpty()
            if (seed.isBlank()) return

            val mType = if (lc.isTv) "tv" else "movie"
            val sNum = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val eNum = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val encTitle = safeEncode(lc.searchQueryTitle).replace("+", "%20")

            supervisorScope {
                listOf("delhi", "miami").map { city ->
                    async(Dispatchers.IO) {
                        val u = "$movyStreamApiBase/$city/sources?title=$encTitle&mediaType=$mType&year=0&episodeId=$eNum&seasonId=$sNum&tmdbId=${safeEncode(tmdbId)}&enc=2&seed=${safeEncode(seed)}"
                        val body = safeGetText(u, headers, timeoutSec = 4L, retries = 1, sourceTag = "movy").orEmpty()
                        if (body.startsWith("{")) parseAndEmitApiJson("Movy • $city", body, lc, headers, emitSub, emitLink)
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("movy", movyStreamApiBase, e, "Movy fetch failed")
        }
    }

    private suspend fun fetch4KhdHub(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeUriEncode(lc.searchQueryTitle)
        val dynDom = fetchDynamicDomain(fourKhdHubDomains.first(), "4khdhub")
        for (dom in (listOf(dynDom) + fourKhdHubDomains).distinct()) {
            try {
                val html = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "4khdhub").orEmpty()
                if (html.isBlank()) continue
                val card = Jsoup.parse(html, dom).select("div.card-grid > a.movie-card, a.movie-card[href], a[href]").firstOrNull { el ->
                    val text = el.selectFirst("div.movie-card-content")?.text()?.trim().orEmpty().ifBlank { el.attr("title").ifBlank { el.text() } }
                    text.isNotBlank() && titleMatchesCandidate(text, lc.pageTitle)
                } ?: continue
                val detailHref = normalizeUrl(card.attr("href").trim(), dom)
                val detailHtml = safeGetText(detailHref, mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "4khdhub").orEmpty()
                if (detailHtml.isBlank()) continue
                resolveCandidatesInParallel("4KHDHub", extractCandidateMediaAndEmbedUrls(detailHtml, detailHref), detailHref, "4K", lc, emitSub, emitLink)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("4khdhub", dom, e, "4KHDHub fetch failed")
            }
        }
    }

    private suspend fun fetchMovies4u(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeUriEncode(lc.searchQueryTitle)
        val dynDom = fetchDynamicDomain(movies4uDomains.first(), "movies4u")
        for (dom in (listOf(dynDom) + movies4uDomains).distinct()) {
            try {
                val raw = safeGetText("$dom/lookup.php?q=$q&page=1&per_page=25", mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "movies4u").orEmpty()
                val hits = runCatching { JSONObject(raw).optJSONArray("hits") }.getOrNull() ?: continue
                val chosen = (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }
                    .firstOrNull { hit -> titleMatchesCandidate(hit.safeStr("post_title"), lc.pageTitle) } ?: continue
                val postUrl = normalizeUrl(chosen.safeStr("permalink"), dom)
                val postHtml = safeGetText(postUrl, mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "movies4u").orEmpty()
                if (postHtml.isBlank()) continue
                resolveCandidatesInParallel("Movies4u", extractCandidateMediaAndEmbedUrls(postHtml, postUrl), postUrl, "", lc, emitSub, emitLink)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("movies4u", dom, e, "Movies4u fetch failed")
            }
        }
    }

    private suspend fun fetchMultimovies(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink, Boolean) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeUriEncode(lc.searchQueryTitle)
        val dynDom = fetchDynamicDomain(multimoviesDomains.first(), "multimovies")
        for (dom in (listOf(dynDom) + multimoviesDomains).distinct()) {
            try {
                val html = safeGetText("$dom/search?q=$q", mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "multimovies").orEmpty()
                if (html.isBlank()) continue
                val cardHref = Jsoup.parse(html, dom).select("article.poster-card a[href], a[href*=/movies/], a[href*=/series/]")
                    .firstOrNull { a -> titleMatchesCandidate(a.attr("title").ifBlank { a.text() }, lc.pageTitle) }
                    ?.attr("href")?.let { normalizeUrl(it, dom) }.orEmpty()
                if (cardHref.isBlank()) continue
                val postHtml = safeGetText(cardHref, mapOf("Referer" to "$dom/"), timeoutSec = 5L, retries = 1, sourceTag = "multimovies").orEmpty()
                if (postHtml.isBlank()) continue
                resolveCandidatesInParallel("Multimovies", extractCandidateMediaAndEmbedUrls(postHtml, cardHref), cardHref, "", lc, emitSub, emitLink)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("multimovies", dom, e, "Multimovies fetch failed")
            }
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
                try {
                    val dir = File(ctx.codeCacheDir, "mf_cs3_plugins").apply { mkdirs() }
                    val hash = MessageDigest.getInstance("MD5").digest(baseProbe.toByteArray()).joinToString("") { "%02x".format(it) }
                    val file = File(dir, "$hash.cs3")
                    if (!file.exists() || file.length() == 0L) {
                        val res = app.get(baseProbe, headers = mapOf("User-Agent" to USER_AGENT), timeout = lc.timeoutSec)
                        if (res.code in 200..299) {
                            val bytes = res.body.bytes()
                            if (bytes.isNotEmpty()) file.writeBytes(bytes)
                        } else {
                            logError("CS3Plugin", baseProbe, null, "HTTP ${res.code}")
                            runCatching { res.okhttpResponse.close() }
                        }
                    }
                    if (!file.exists() || file.length() == 0L) return@getOrPut emptyList()
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError("CS3Plugin", baseProbe, e, "DexClassLoader load failed")
                    emptyList()
                }
            }

            withContext(Dispatchers.IO) {
                for (plugin in plugins) {
                    if (lc.searchQueryTitle.isBlank()) continue
                    try {
                        val hits = runCatching { plugin.search(lc.searchQueryTitle) }.getOrNull().orEmpty()
                        val hit = hits.firstOrNull { titleMatchesCandidate(it.name, lc.pageTitle) } ?: hits.firstOrNull() ?: continue
                        val loadRes = runCatching { plugin.load(hit.url) }.getOrNull() ?: continue
                        val targetData = if (loadRes is TvSeriesLoadResponse) {
                            val sN = lc.season.toIntOrNull() ?: 1
                            val eN = lc.episode.toIntOrNull() ?: 1
                            loadRes.episodes.firstOrNull { it.season == sN && it.episode == eN }?.data ?: loadRes.episodes.firstOrNull()?.data
                        } else (loadRes as? MovieLoadResponse)?.dataUrl ?: hit.url
                        if (targetData != null) {
                            plugin.loadLinks(targetData, isCasting, emitSub) { ex ->
                                if (!isJunkCandidateUrl(ex.url) && lc.seenUrls.add(ex.url) && isQualityAllowed(lc, ex.quality, ex.name)) {
                                    emitLink(ex, isStreamOrHlsLink(ex.url, ex.type))
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logError("CS3•${plugin.name}", plugin.mainUrl, e, "Plugin execution failed")
                    }
                }
            }
            return
        }

        val sep = if (baseProbe.contains("?")) "&" else "?"
        val queryUrl = if (lc.searchQueryTitle.isNotBlank()) "$baseProbe${sep}q=${safeEncode(lc.searchQueryTitle)}" else baseProbe
        val body = safeGetText(queryUrl, timeoutSec = minOf(lc.timeoutSec, 6L), retries = 1, sourceTag = srvKey)?.trim().orEmpty()
        if (body.isBlank()) return
        if (body.startsWith("{")) {
            parseAndEmitApiJson("CS3 • ${cs3Srv.name}", body, lc, lc.baseHeaders, emitSub, emitLink)
        } else {
            resolveCandidatesInParallel("CS3 • ${cs3Srv.name}", extractCandidateMediaAndEmbedUrls(body, queryUrl), queryUrl, "", lc, emitSub, emitLink)
        }
    }
}

