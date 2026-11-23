package com.hungrysturdy

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MovieFoxProvider : MainAPI() {

    override var mainUrl = "https://moviefox.pro"
    override var name = "MovieFox"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true

    private val logTag = "MovieFoxProvider"
    private val effectiveUa: String
        get() = MovieFoxSettingsDialog.CF_BYPASS_UA

    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"
    private val tmdbBaseUrl = MfRegistries.TMDB_API_BASE

    private val vegamoviesDomains = listOf(
        "https://new1.vegamovies.lease",
        "https://vegamovies.lease",
        "https://rogmovies.autos",
        "https://rogmovies.im",
        "https://rogmovies.one",
        "https://rogmovies.asia"
    )

    private val bollyflixMirrors = listOf(
        "https://new1.bollyflix.tours",
        "https://bollyflix.frl"
    )

    private val hicineApiBases = listOf(
        "https://hicine.biz",
        "https://hicine.lol"
    )

    private val fourKhdHubDomains = listOf("https://4khdhub.one")
    private val moviesDriveDomains = listOf("https://new4.moviesdrive.cyou", "https://new3.moviesdrive.cyou")

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

    private val activeServerKeys = listOf(
        "cinestream",
        "sflix",
        "4khdhub",
        "moviesdrive",
        "vegamovies",
        "bollyflix",
        "hicine"
    )

    private val titleStopWords = setOf(
        "the", "a", "an", "of", "in", "on", "at", "to", "for", "and", "or", "is", "are", "with", "by"
    )

    private val allowedHostTokens = listOf(
        "pixeldrain", "fsl2", "fsl", "fastdlserver", "fastdl",
        "hubcloud", "vcloud", "voe.sx", "voe", "gofile", "gdflix", "gdlink", "driveseed", "driveleech"
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
        "$mainUrl/tv?list=popular" to "Popular TV Shows",
        "$mainUrl/movies?lang=hi" to "Indian Movies (Hindi)",
        "$mainUrl/tv?lang=hi" to "Indian TV Shows",
        "$mainUrl/tv?lang=ko" to "K-Drama",
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

    private fun safeEncode(raw: String?): String {
        val src = raw.orEmpty()
        return runCatching { URLEncoder.encode(src, "UTF-8") }.getOrDefault(src)
    }

    private fun safeUriEncode(raw: String?, allow: String? = null): String {
        val src = raw.orEmpty()
        return if (allow != null) Uri.encode(src, allow).orEmpty() else Uri.encode(src).orEmpty()
    }

    private fun JSONObject.safeStr(key: String, fallback: String = ""): String {
        if (!has(key) || isNull(key)) return fallback
        val v = optString(key, "").trim()
        return if (v.isBlank() || v.equals("null", ignoreCase = true)) fallback else v
    }

    private fun splitMultiUrls(raw: String): List<String> {
        return raw.trim()
            .split(Regex("""\s+"""))
            .map { it.trim().trimEnd(',', ';', ')', ']', '"', '\'') }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("magnet:", true) }
    }

    private fun normalizeUrl(rawUrl: String, baseHost: String = mainUrl): String {
        val firstPart = splitMultiUrls(rawUrl).firstOrNull() ?: rawUrl.replace("\\/", "/").replace("&amp;", "&").trim()
        val trimmed = firstPart.replace("\\/", "/").replace("&amp;", "&").trim()
        return when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) || trimmed.startsWith("magnet:", true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "${baseHost.trimEnd('/')}/${trimmed.trimStart('/')}"
        }
    }

    private fun isBlockedTrackerOrJunk(url: String): Boolean {
        val low = url.lowercase()
        return mfIsBrokenContainerUrl(low) ||
            low.contains("googletagmanager") ||
            low.contains("cloudflareinsights") ||
            low.contains("doubleclick") ||
            low.contains("google-analytics") ||
            low.contains("gravatar.com")
    }

    private fun isAllowedMirrorOrStream(urlOrLabel: String): Boolean {
        val low = urlOrLabel.lowercase()
        if (isBlockedTrackerOrJunk(low)) return false
        return allowedHostTokens.any { low.contains(it) } ||
            low.contains(".m3u8") ||
            low.contains(".mp4") ||
            low.contains(".mkv")
    }

    private fun cleanSearchTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*\((?:19|20)\d{2}\)\s*"""), " ")
            .replace(Regex("""\s*\[[^\]]*\]\s*"""), " ")
            .replace(Regex("""(?i)\b(?:hindi\s*dubbed|dual\s*audio|multi\s*audio|full\s*movie|watch\s*online|web-?dl|bluray|hdrip)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun titleFromUrlSlug(url: String): String {
        val lastSeg = url.substringBefore("?").trimEnd('/').substringAfterLast("/")
        if (lastSeg.isBlank() || lastSeg.matches(Regex("""^(?:movie|series|tv|anime)-\d+$""", RegexOption.IGNORE_CASE))) {
            return ""
        }
        return lastSeg
            .replace(Regex("""-(?:movie|tv|series|anime)-\d+$""", RegexOption.IGNORE_CASE), "")
            .replace("-", " ")
            .replace("_", " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .trim()
    }

    private fun normalizeTitleKey(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.lowercase()
            .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .replace(Regex("""(?i)\b(?:season|part|chapter|vol|volume|s|ep|episode)\s*\d+\b"""), " ")
            .replace(Regex("""(?i)\b(?:480p|720p|1080p|2160p|4k|web-?dl|webrip|bluray|hdrip|hevc|x264|x265|hindi|english|korean|japanese|chinese|tamil|telugu|dubbed|dual\s*audio|multi\s*audio|watch|download|online|free|series|movie|show)\b"""), " ")
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
        if (cWords.isEmpty()) return false

        val matchedCount = tWords.count { it in cWords }
        val ratio = matchedCount.toDouble() / tWords.size.toDouble()

        return when {
            tWords.size == 1 -> tWords.first() in cWords
            tWords.size == 2 -> matchedCount == 2 || (ratio >= 0.5 && tWords.last() in cWords)
            else -> matchedCount >= 2 && ratio >= 0.5
        }
    }

    private fun getQuality(value: String?): Int {
        val q = value?.uppercase().orEmpty()
        return when {
            q.contains("2160") || q.contains("4K") -> Qualities.P2160.value
            q.contains("1440") -> Qualities.P1440.value
            q.contains("1080") -> Qualities.P1080.value
            q.contains("720") -> Qualities.P720.value
            q.contains("480") -> Qualities.P480.value
            else -> Qualities.P1080.value
        }
    }

    private suspend fun safeGetText(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutSec: Long = 6L,
        retries: Int = 2,
        sourceTag: String = "HTTP"
    ): String? {
        if (isBlockedTrackerOrJunk(url)) return null
        val headers = mutableMapOf(
            "User-Agent" to effectiveUa,
            "Accept" to "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        ).apply { putAll(extraHeaders) }

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
                if (attempt == retries - 1 && sourceTag.equals("tmdb", true)) {
                    logError(sourceTag, url, e, "attempt=${attempt + 1}")
                }
            }
            if (attempt < retries - 1) delay(450L)
        }
        return null
    }

    private suspend fun fetchTmdbHomeItems(requestUrl: String, page: Int): List<SearchResponse> {
        val isTv = requestUrl.contains("/tv", true) || requestUrl.contains("/anime", true)
        val endpoint = when {
            requestUrl.contains("/trending", true) -> "$tmdbBaseUrl/trending/all/day?api_key=$tmdbApiKey&page=$page"
            requestUrl.contains("/anime", true) -> "$tmdbBaseUrl/discover/tv?api_key=$tmdbApiKey&with_genres=16&sort_by=popularity.desc&page=$page"
            requestUrl.contains("lang=", true) -> {
                val langCode = Regex("lang=([a-z]{2})").find(requestUrl)?.groupValues?.get(1) ?: "hi"
                val typePath = if (isTv) "discover/tv" else "discover/movie"
                "$tmdbBaseUrl/$typePath?api_key=$tmdbApiKey&with_original_language=$langCode&sort_by=popularity.desc&page=$page"
            }
            isTv -> "$tmdbBaseUrl/tv/popular?api_key=$tmdbApiKey&page=$page"
            else -> "$tmdbBaseUrl/movie/popular?api_key=$tmdbApiKey&page=$page"
        }
        val raw = safeGetText(endpoint, timeoutSec = 6L, retries = 3, sourceTag = "TMDB").orEmpty()
        val arr = runCatching { JSONObject(raw).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
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

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.attr("href").trim()
        if (href.isBlank()) return null
        val rawText = element.text().trim()
        if (rawText.lowercase() in ignoredLinkTexts) return null
        val article = element.closest("article")
        val img = element.selectFirst("img") ?: article?.selectFirst("img") ?: return null
        val title = element.attr("aria-label")
            .replace(Regex("^Number \\d+:\\s*"), "")
            .substringBefore(" (20")
            .substringBefore(" (19")
            .trim()
            .ifEmpty { img.attr("alt").trim() }
            .ifEmpty { rawText }
        if (title.isBlank() || title.lowercase() in ignoredLinkTexts) return null
        val poster = img.attr("src").ifEmpty { img.attr("data-src") }.takeIf { it.isNotBlank() }
        val fixedUrl = normalizeUrl(href, mainUrl)
        val isTv = fixedUrl.contains("/tv/") || fixedUrl.contains("/anime/") || article?.attr("data-cf-type").equals("tv", true)
        return if (isTv) {
            newTvSeriesSearchResponse(title, fixedUrl, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, fixedUrl, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val targetUrl = if (page > 1) "${request.data}${sep}page=$page" else request.data
        val rawHtml = safeGetText(targetUrl, timeoutSec = 6L, retries = 1, sourceTag = "moviefox").orEmpty()
        val items = if (rawHtml.isNotBlank()) {
            val doc = Jsoup.parse(rawHtml, mainUrl)
            doc.select("article[data-cf] a[aria-label][href], a[href*=/movie/], a[href*=/tv/], a[href*=/anime/]")
                .mapNotNull { toSearchResult(it) }
                .distinctBy { it.url }
        } else {
            fetchTmdbHomeItems(request.data, page)
        }
        val list = if (items.isNotEmpty()) listOf(HomePageList(request.name, items)) else emptyList()
        return newHomePageResponse(list, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = safeEncode(query.trim())
        if (encoded.isBlank()) return emptyList()
        val tmdbRaw = safeGetText("$tmdbBaseUrl/search/multi?api_key=$tmdbApiKey&query=$encoded&page=1", timeoutSec = 6L, retries = 3, sourceTag = "TMDB").orEmpty()
        val results = runCatching { JSONObject(tmdbRaw).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
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
                out.add(newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, detailUrl, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return out
    }

    private fun extractSeriesId(url: String): String? {
        return Regex("-(?:movie|tv|anime|series)-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: Regex("/(?:movie|tv|anime|series)/(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)
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

    override suspend fun load(url: String): LoadResponse {
        val isTv = url.contains("/tv/", true) || url.contains("/anime/", true) || url.contains("season=", true)
        val id = extractSeriesId(url).orEmpty()
        val tmdbPath = if (isTv) "tv/$id" else "movie/$id"
        val raw = if (id.isNotBlank()) {
            safeGetText("$tmdbBaseUrl/$tmdbPath?api_key=$tmdbApiKey&append_to_response=external_ids", timeoutSec = 6L, retries = 3, sourceTag = "TMDB").orEmpty()
        } else ""
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()

        var title = obj.safeStr("title").ifBlank { obj.safeStr("name") }
        var imdbId = obj.optJSONObject("external_ids")?.safeStr("imdb_id").orEmpty().ifBlank { obj.safeStr("imdb_id") }
        var poster: String? = obj.safeStr("poster_path").takeIf { it.startsWith("/") }?.let { "${MfRegistries.TMDB_IMAGE_W500}$it" }
        var plot = obj.safeStr("overview")
        val episodes = mutableListOf<Episode>()

        val seasonsArr = obj.optJSONArray("seasons")
        if (seasonsArr != null) {
            for (i in 0 until seasonsArr.length()) {
                val sObj = seasonsArr.optJSONObject(i) ?: continue
                val sNum = sObj.optInt("season_number", 0)
                val epCount = sObj.optInt("episode_count", 0)
                if (sNum <= 0 || epCount <= 0) continue
                for (eNum in 1..epCount) {
                    val epData = encodeLoadData("${url.substringBefore("?")}?season=$sNum&episode=$eNum", title.ifBlank { "Series" }, id, imdbId, true, sNum, eNum)
                    episodes.add(newEpisode(epData) {
                        this.name = "Episode $eNum"
                        this.season = sNum
                        this.episode = eNum
                    })
                }
            }
        }

        if (title.isBlank() || plot.isBlank() || (isTv && episodes.isEmpty())) {
            val slugTitle = titleFromUrlSlug(url)
            val cineType = if (isTv) "series" else "movie"
            val lookupId = if (imdbId.startsWith("tt")) imdbId
            else if (id.isNotBlank()) {
                val searchType = if (isTv) "tv" else "movie"
                val extRaw = safeGetText("$tmdbBaseUrl/$searchType/$id/external_ids?api_key=$tmdbApiKey", timeoutSec = 5L, retries = 1, sourceTag = "TMDB").orEmpty()
                runCatching { JSONObject(extRaw).safeStr("imdb_id") }.getOrNull().orEmpty().ifBlank { id }
            } else ""

            if (lookupId.isNotBlank()) {
                val cineRaw = safeGetText("${MfRegistries.CINEMETA_BASE}/$cineType/${safeUriEncode(lookupId)}.json", timeoutSec = 5L, retries = 1, sourceTag = "cinemeta").orEmpty()
                val metaObj = runCatching { JSONObject(cineRaw).optJSONObject("meta") }.getOrNull()
                if (metaObj != null) {
                    if (title.isBlank()) title = metaObj.safeStr("name")
                    if (imdbId.isBlank()) imdbId = metaObj.safeStr("imdb_id").ifBlank { lookupId.takeIf { it.startsWith("tt") }.orEmpty() }
                    if (poster.isNullOrBlank()) poster = metaObj.safeStr("poster").takeIf { it.startsWith("http") }
                    if (plot.isBlank()) plot = metaObj.safeStr("description")
                    if (isTv && episodes.isEmpty()) {
                        metaObj.optJSONArray("videos")?.let { vids: JSONArray ->
                            for (vIdx in 0 until vids.length()) {
                                val vObj = vids.optJSONObject(vIdx) ?: continue
                                val sNum = vObj.optInt("season", 1)
                                val eNum = vObj.optInt("episode", 0)
                                if (sNum <= 0 || eNum <= 0) continue
                                val epName = vObj.safeStr("name").ifBlank { vObj.safeStr("title", "Episode $eNum") }
                                val epThumb = vObj.safeStr("thumbnail").takeIf { it.startsWith("http") } ?: poster
                                val epData = encodeLoadData("${url.substringBefore("?")}?season=$sNum&episode=$eNum", title.ifBlank { slugTitle.ifBlank { "Series" } }, id, imdbId, true, sNum, eNum)
                                episodes.add(newEpisode(epData) {
                                    this.name = epName
                                    this.season = sNum
                                    this.episode = eNum
                                    this.posterUrl = epThumb
                                })
                            }
                        }
                    }
                }
            }

            if (title.isBlank()) {
                title = slugTitle.ifBlank { if (id.isNotBlank()) "Title #$id" else "MovieFox Title" }
            }
            if (plot.isBlank()) {
                plot = "$title (${if (isTv) "Series" else "Movie"})"
            }
        }

        if (!isTv) {
            return newMovieLoadResponse(title, url, TvType.Movie, encodeLoadData(url, title, id, imdbId, false)) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(encodeLoadData(url, title, id, imdbId, true, 1, 1)) {
                this.name = "Episode 1"
                this.season = 1
                this.episode = 1
            })
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
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
        val seenUrls: MutableSet<String>
    )

    private suspend fun buildLinkContext(data: String): LinkContext? {
        var rawUrl = data
        var title = ""
        var tmdbId: String? = null
        var imdbId = ""
        var isTv = false
        var season = "1"
        var episode = "1"

        if (data.trimStart().startsWith("{")) {
            runCatching {
                val obj = JSONObject(data)
                rawUrl = obj.safeStr("url", data)
                title = obj.safeStr("title")
                tmdbId = obj.safeStr("tmdbId").takeIf { it.isNotBlank() }
                imdbId = obj.safeStr("imdbId")
                isTv = obj.optBoolean("isTv", false)
                season = obj.optInt("season", 1).coerceAtLeast(1).toString()
                episode = obj.optInt("episode", 1).coerceAtLeast(1).toString()
            }
        }

        val id = tmdbId ?: extractSeriesId(rawUrl)
        if (title.isBlank() && !id.isNullOrBlank()) {
            val path = if (isTv) "tv/$id" else "movie/$id"
            val raw = safeGetText("$tmdbBaseUrl/$path?api_key=$tmdbApiKey&append_to_response=external_ids", timeoutSec = 6L, retries = 3, sourceTag = "TMDB").orEmpty()
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null) {
                title = obj.safeStr("title").ifBlank { obj.safeStr("name") }
                if (imdbId.isBlank()) imdbId = obj.optJSONObject("external_ids")?.safeStr("imdb_id").orEmpty()
            }
        }
        if (title.isBlank()) title = titleFromUrlSlug(rawUrl)
        if (title.isBlank()) return null

        val cleanQ = cleanSearchTitle(title).ifBlank { title }
        return LinkContext(
            pageTitle = title,
            searchQueryTitle = cleanQ,
            isTv = isTv,
            season = season,
            episode = episode,
            imdbId = imdbId,
            candidateIds = listOfNotNull(id).distinct(),
            watchUrl = rawUrl,
            baseHeaders = mapOf("User-Agent" to effectiveUa, "Accept" to "application/json, text/html, */*"),
            seenUrls = ConcurrentHashMap.newKeySet()
        )
    }

    private fun extractCandidateUrls(html: String, baseUrl: String): List<String> {
        val found = LinkedHashSet<String>()
        val doc = Jsoup.parse(html, baseUrl)
        doc.select("a[href], iframe[src], source[src], [data-href], [data-url], [data-src]").forEach { el ->
            val rawAttr = listOf("href", "src", "data-href", "data-url", "data-src")
                .firstNotNullOfOrNull { el.attr(it).trim().takeIf { v -> v.isNotBlank() } }
                .orEmpty()
            for (part in splitMultiUrls(rawAttr)) {
                val norm = normalizeUrl(part, baseUrl)
                if (isAllowedMirrorOrStream("$norm ${el.text()}")) {
                    found.add(norm)
                }
            }
        }
        Regex("""https?://[^\s"'<>\\)\]]*(?:pixeldrain|fsl2|fsl|fastdlserver|fastdl|hubcloud|vcloud|voe\.sx|voe|gofile|gdflix|gdlink|driveseed|driveleech)[^\s"'<>\\)\]]*""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                for (part in splitMultiUrls(m.value)) {
                    val norm = normalizeUrl(part, baseUrl)
                    if (isAllowedMirrorOrStream(norm)) {
                        found.add(norm)
                    }
                }
            }
        return found.toList()
    }

    private suspend fun resolveUrlViaExtractorOrDirect(
        lc: LinkContext,
        sourceTag: String,
        label: String,
        rawUrl: String,
        referer: String,
        emitSub: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (singleUrl in splitMultiUrls(rawUrl)) {
            val norm = normalizeUrl(singleUrl, referer)
            if (!norm.startsWith("http", true) || isBlockedTrackerOrJunk(norm)) continue
            if (!lc.seenUrls.add(norm)) continue

            var emittedByExtractor = false
            val exCb: (ExtractorLink) -> Unit = { ex ->
                if (!isBlockedTrackerOrJunk(ex.url) && lc.seenUrls.add(ex.url)) {
                    emittedByExtractor = true
                    runCatching {
                        callback(
                            ExtractorLink(
                                source = sourceTag,
                                name = "$sourceTag • ${ex.name.ifBlank { label }}",
                                url = ex.url,
                                referer = ex.referer.ifBlank { referer },
                                quality = if (ex.quality > 0) ex.quality else getQuality(ex.name),
                                headers = ex.headers,
                                extractorData = ex.extractorData,
                                type = ex.type
                            )
                        )
                    }
                }
            }

            when {
                norm.contains("pixeldrain", true) -> {
                    runCatching { PixeldrainExtractor().getUrl(norm, referer, emitSub, exCb) }
                }
                norm.contains("hubcloud", true) -> {
                    runCatching { HubCloud().getUrl(norm, referer, emitSub, exCb) }
                }
                norm.contains("vcloud", true) -> {
                    runCatching { VCloud().getUrl(norm, referer, emitSub, exCb) }
                }
                norm.contains("voe", true) -> {
                    runCatching { VoeExtractor().getUrl(norm, referer, emitSub, exCb) }
                }
                else -> {
                    runCatching { loadExtractor(norm, referer, emitSub, exCb) }
                }
            }

            if (!emittedByExtractor && isAllowedMirrorOrStream("$norm $label")) {
                val linkType = if (norm.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val link = newExtractorLink(
                    source = sourceTag,
                    name = "$sourceTag • $label",
                    url = norm,
                    type = linkType
                ) {
                    this.referer = referer
                    this.quality = getQuality(label)
                    this.headers = mapOf("User-Agent" to effectiveUa, "Referer" to referer)
                }
                runCatching { callback(link) }
            }
        }
    }

    private suspend fun fetchVegaMovies(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val q = safeUriEncode(lc.searchQueryTitle)
        for (dom in vegamoviesDomains) {
            val searchUrl = "$dom/ts-search.php?q=$q&page=1"
            val raw = safeGetText(searchUrl, mapOf("Referer" to "$dom/"), 5L, 1, "vegamovies").orEmpty()
            val hits = runCatching { JSONObject(raw).optJSONArray("hits") }.getOrNull()
            var postUrl: String? = null
            if (hits != null) {
                for (i in 0 until hits.length()) {
                    val d = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
                    val t = d.safeStr("post_title")
                    val p = d.safeStr("permalink")
                    if (t.isNotBlank() && p.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)) {
                        postUrl = normalizeUrl(p, dom)
                        break
                    }
                }
            }
            if (postUrl == null) {
                val fbHtml = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), 5L, 1, "vegamovies").orEmpty()
                postUrl = Jsoup.parse(fbHtml, dom).select("article a[href], h2 a[href], h3 a[href]")
                    .firstOrNull { titleMatchesCandidate(it.attr("title").ifBlank { it.text() }, lc.pageTitle) }
                    ?.attr("href")?.let { normalizeUrl(it, dom) }
            }
            if (postUrl.isNullOrBlank()) continue

            val postHtml = safeGetText(postUrl, mapOf("Referer" to "$dom/"), 6L, 1, "vegamovies").orEmpty()
            if (postHtml.isBlank()) continue
            val links = extractCandidateUrls(postHtml, postUrl).take(8)
            for (u in links) {
                resolveUrlViaExtractorOrDirect(lc, "VegaMovies", "Mirror", u, postUrl, emitSub, callback)
            }
            if (links.isNotEmpty()) break
        }
    }

    private suspend fun fetchBollyflix(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val q = safeEncode(lc.searchQueryTitle)
        for (dom in bollyflixMirrors) {
            val searchUrl = "$dom/search/$q/page/1/"
            val html = safeGetText(searchUrl, mapOf("Referer" to "$dom/"), 6L, 1, "bollyflix").orEmpty()
            if (html.isBlank()) continue
            val card = Jsoup.parse(html, dom).select("div.post-cards > article, article").firstOrNull { art ->
                val t = art.select("a").attr("title").replace("Download ", "").trim()
                t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
            } ?: continue
            val postUrl = normalizeUrl(card.select("a").attr("href"), dom)
            if (postUrl.isBlank()) continue

            val postHtml = safeGetText(postUrl, mapOf("Referer" to "$dom/"), 6L, 1, "bollyflix").orEmpty()
            if (postHtml.isBlank()) continue
            val links = extractCandidateUrls(postHtml, postUrl).take(8)
            for (u in links) {
                resolveUrlViaExtractorOrDirect(lc, "Bollyflix", "Mirror", u, postUrl, emitSub, callback)
            }
            if (links.isNotEmpty()) break
        }
    }

    private suspend fun fetchHiCine(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val q = safeEncode(lc.searchQueryTitle)
        for (b in hicineApiBases) {
            val h = mapOf("Referer" to "$b/", "Accept" to "application/json")
            val searchRaw = safeGetText("$b/hapi/search?q=$q&page=1", h, 5L, 1, "hicine").orEmpty()
            val items = runCatching { JSONObject(searchRaw).optJSONArray("items") }.getOrNull() ?: continue
            val titleId = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .firstOrNull { titleMatchesCandidate(it.safeStr("title"), lc.pageTitle) }
                ?.safeStr("id").orEmpty()
            if (titleId.isBlank()) continue

            val detailRaw = safeGetText("$b/hapi/t?c=${safeEncode(titleId)}", h, 5L, 1, "hicine").orEmpty()
            val groups = runCatching { JSONObject(detailRaw).optJSONArray("groups") }.getOrNull() ?: continue
            val targetEp = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1

            for (gIdx in 0 until groups.length()) {
                val grp = groups.optJSONObject(gIdx) ?: continue
                val gKey = grp.safeStr("k")
                if (gKey.isBlank()) continue
                val qStr = grp.safeStr("quality", grp.safeStr("head"))
                val grpRaw = safeGetText("$b/hapi/g?k=${safeEncode(gKey)}", h, 5L, 1, "hicine").orEmpty()
                val gItems = runCatching { JSONObject(grpRaw).optJSONArray("items") }.getOrNull() ?: continue
                val chosen = (0 until gItems.length()).mapNotNull { gItems.optJSONObject(it) }
                    .firstOrNull { !lc.isTv || it.optInt("ep", -1) == targetEp }
                    ?: gItems.optJSONObject(0)
                    ?: continue
                val hosts = chosen.optJSONArray("hosts") ?: continue
                for (hIdx in 0 until hosts.length()) {
                    val hKey = hosts.optJSONObject(hIdx)?.safeStr("k").orEmpty()
                    if (hKey.isBlank()) continue
                    val dlRaw = safeGetText("$b/hapi/dl?k=${safeEncode(hKey)}", h, 5L, 1, "hicine").orEmpty()
                    val opts = runCatching { JSONObject(dlRaw).optJSONArray("options") }.getOrNull() ?: continue
                    for (oIdx in 0 until opts.length()) {
                        val opt = opts.optJSONObject(oIdx) ?: continue
                        val goKey = opt.safeStr("ks").ifBlank { opt.safeStr("k") }
                        if (goKey.isBlank()) continue
                        val goRaw = safeGetText("$b/hapi/go?k=${safeEncode(goKey)}&m=u", h, 5L, 1, "hicine").orEmpty()
                        val resolved = runCatching { JSONObject(goRaw).safeStr("url") }.getOrNull().orEmpty()
                        if (resolved.isBlank()) continue
                        resolveUrlViaExtractorOrDirect(lc, "HiCine", "${opt.safeStr("name", "HiCine")} $qStr".trim(), resolved, "$b/", emitSub, callback)
                    }
                }
            }
            break
        }
    }

    private suspend fun fetch4KhdHub(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val q = safeUriEncode(lc.searchQueryTitle)
        for (dom in fourKhdHubDomains) {
            val html = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), 6L, 1, "4khdhub").orEmpty()
            if (html.isBlank()) continue
            val card = Jsoup.parse(html, dom).select("div.card-grid > a.movie-card, a.movie-card[href], a[href]").firstOrNull { el ->
                val text = el.selectFirst("div.movie-card-content")?.text()?.trim().orEmpty().ifBlank { el.attr("title").ifBlank { el.text() } }
                text.isNotBlank() && titleMatchesCandidate(text, lc.pageTitle)
            } ?: continue
            val detailHref = normalizeUrl(card.attr("href").trim(), dom)
            val detailHtml = safeGetText(detailHref, mapOf("Referer" to "$dom/"), 6L, 1, "4khdhub").orEmpty()
            if (detailHtml.isBlank()) continue
            val links = extractCandidateUrls(detailHtml, detailHref).take(8)
            for (u in links) {
                resolveUrlViaExtractorOrDirect(lc, "4KHDHub", "4K", u, detailHref, emitSub, callback)
            }
            if (links.isNotEmpty()) break
        }
    }

    private suspend fun fetchMoviesDrive(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val q = safeEncode(lc.searchQueryTitle)
        for (dom in moviesDriveDomains) {
            val html = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), 6L, 1, "moviesdrive").orEmpty()
            if (html.isBlank()) continue
            val anchor = Jsoup.parse(html, dom).select("article a[href], h2 a[href], h3 a[href]").firstOrNull { a ->
                val t = a.attr("title").ifBlank { a.text() }.trim()
                t.isNotBlank() && titleMatchesCandidate(t, lc.pageTitle)
            } ?: continue
            val postUrl = normalizeUrl(anchor.attr("href"), dom)
            val postHtml = safeGetText(postUrl, mapOf("Referer" to "$dom/"), 6L, 1, "moviesdrive").orEmpty()
            if (postHtml.isBlank()) continue
            val links = extractCandidateUrls(postHtml, postUrl).take(8)
            for (u in links) {
                resolveUrlViaExtractorOrDirect(lc, "MoviesDrive", "Drive", u, postUrl, emitSub, callback)
            }
            if (links.isNotEmpty()) break
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

    private suspend fun fetchSflix(lc: LinkContext, emitSub: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val sflixMainUrl = "https://sflix.film"
        val q = safeEncode(lc.searchQueryTitle)
        val searchHtml = safeGetText("$sflixMainUrl/search-result?keyword=$q", mapOf("Referer" to "$sflixMainUrl/"), 6L, 1, "sflix").orEmpty()
        if (searchHtml.isBlank()) return
        val anchors = Jsoup.parse(searchHtml, sflixMainUrl).select("a.video-item[href*='/detail/'], a[href*='/detail/']")
        val matched = anchors.filter { el ->
            val t = (el.selectFirst("h3.text, .video-name, h3")?.text() ?: el.attr("title")).trim()
            titleMatchesCandidate(t, lc.pageTitle)
        }.map { it.attr("href") }.distinct().take(2)
        if (matched.isEmpty()) return

        val seenSubs = Collections.synchronizedSet(mutableSetOf<String>())
        for (detailLink in matched) {
            val fullDetail = normalizeUrl(detailLink, sflixMainUrl)
            val subjectId = Regex("""[?&]id=(\d+)""").find(fullDetail)?.groupValues?.get(1).orEmpty()
            val detailPath = fullDetail.substringAfter("/detail/").substringBefore("?")
            if (subjectId.isBlank()) continue

            val se = if (lc.isTv) lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
            val ep = if (lc.isTv) lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0
            val referer = "$sflixPlayerUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"

            for (domain in sflixWebDomains) {
                val playUrl = "$domain/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath&streamSignType=1"
                val ts = System.currentTimeMillis()
                val headers = mapOf(
                    "User-Agent" to effectiveUa,
                    "Referer" to referer,
                    "Origin" to sflixPlayerUrl,
                    "Accept" to "application/json",
                    "x-client-token" to sflixClientToken(ts),
                    "x-tr-signature" to sflixSignature(playUrl, ts),
                    "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                    "x-request-lang" to "en"
                )
                val playText = safeGetText(playUrl, headers, 6L, 1, "sflix").orEmpty()
                val dataObj = runCatching { JSONObject(playText).optJSONObject("data") }.getOrNull() ?: continue
                var any = false

                listOf("hls" to "HLS", "streams" to "MP4", "dash" to "DASH").forEach { (k, kind) ->
                    dataObj.optJSONArray(k)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val sUrl = item.safeStr("url")
                            if (sUrl.isBlank() || isBlockedTrackerOrJunk(sUrl)) continue
                            val maxRes = item.safeStr("resolutions").split(",").mapNotNull { it.trim().toIntOrNull() }.maxOrNull() ?: 720
                            val linkType = when (kind) {
                                "DASH" -> ExtractorLinkType.DASH
                                "HLS" -> ExtractorLinkType.M3U8
                                else -> ExtractorLinkType.VIDEO
                            }
                            if (lc.seenUrls.add(sUrl)) {
                                callback(
                                    newExtractorLink(
                                        source = "Sflix",
                                        name = "Sflix • $kind ${maxRes}p",
                                        url = sUrl,
                                        type = linkType
                                    ) {
                                        this.referer = "$sflixPlayerUrl/"
                                        this.quality = getQuality("${maxRes}p")
                                        this.headers = mapOf("Origin" to sflixPlayerUrl, "Referer" to "$sflixPlayerUrl/", "User-Agent" to effectiveUa)
                                    }
                                )
                                any = true
                            }
                        }
                    }
                }

                dataObj.optJSONArray("captions")?.let { caps ->
                    for (c in 0 until caps.length()) {
                        val cObj = caps.optJSONObject(c) ?: continue
                        val subUrl = cObj.safeStr("url")
                        if (subUrl.startsWith("http", true) && seenSubs.add(subUrl)) {
                            emitSub(newSubtitleFile(cObj.safeStr("lanName", "English"), subUrl))
                        }
                    }
                }
                if (any) return
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val lc = buildLinkContext(data) ?: return false
        val lock = Any()
        var found = false

        val wrappedCallback: (ExtractorLink) -> Unit = { ex ->
            if (!isBlockedTrackerOrJunk(ex.url) && lc.seenUrls.add(ex.url)) {
                synchronized(lock) {
                    found = true
                    callback(ex)
                }
            }
        }

        val sem = Semaphore(6)
        supervisorScope {
            activeServerKeys.map { srvKey ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        withTimeoutOrNull(14_000L) {
                            when (srvKey) {
                                "cinestream" -> {
                                    MovieFoxCineStream.loadStreams(
                                        provider = this@MovieFoxProvider,
                                        title = lc.searchQueryTitle.ifBlank { lc.pageTitle },
                                        imdbId = lc.imdbId,
                                        tmdbId = lc.candidateIds.firstOrNull(),
                                        isTv = lc.isTv,
                                        season = lc.season.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                        episode = lc.episode.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                        timeoutSec = 10L,
                                        subtitleCallback = subtitleCallback,
                                        callback = wrappedCallback
                                    )
                                }
                                "sflix" -> fetchSflix(lc, subtitleCallback, wrappedCallback)
                                "4khdhub" -> fetch4KhdHub(lc, subtitleCallback, wrappedCallback)
                                "moviesdrive" -> fetchMoviesDrive(lc, subtitleCallback, wrappedCallback)
                                "vegamovies" -> fetchVegaMovies(lc, subtitleCallback, wrappedCallback)
                                "bollyflix" -> fetchBollyflix(lc, subtitleCallback, wrappedCallback)
                                "hicine" -> fetchHiCine(lc, subtitleCallback, wrappedCallback)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        return found
    }
}

