package com.hungrysturdy

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
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
import java.util.concurrent.ConcurrentHashMap

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
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"

    // 1. Prmovies Mirrors
    private val prmoviesMirrors = listOf(
        "https://prmovies.coupons",
        "https://prmovies.kids",
        "https://prmovies.beer",
        "https://prmovies.com.cv",
        "https://prmovies.com"
    )
    @Volatile
    private var activePrmoviesBase = "https://prmovies.coupons"

    // 2. HindiDrama Host
    private val hindiDramaBase = "https://hindidrama.net"

    // 3. Sflix API & Player Config
    private val sflixDomains = listOf(
        "https://sflix.film",
        "https://h5-api.aoneroom.com",
        "https://movie-box.co",
        "https://123moviesfree.club"
    )
    private val sflixPlayerUrl = "https://mzfi.me"
    private val sflixCookies =
        "sflix_i18n_lang=en; _ga=GA1.1.622965780.1794742147; " +
            "sflix_token=%22eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjE5NjY4NTU1NTcyNzgwNjIyNzYsImF0cCI6MywiZXh0IjoiMTc5NDc0MjE0NyIsImV4cCI6MTgwMjUxODE0NywiaWF0IjoxNzk0NzQxODQ3fQ.8aOIckQOSKyISQdl85SAJvuMlhXfWodHmcPOIvlg0pM%22"

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

    private fun logError(source: String, url: String, err: Throwable?, extra: String = "") {
        if (err is CancellationException) return
        Log.e(logTag, "[$source] $extra | $url -> ${err?.message.orEmpty()}", err)
        MovieFoxSettingsDialog.logSiteError(source, url, err, extra)
    }

    private fun isExtOn(key: String): Boolean =
        ctxOrNull()?.let { MovieFoxSettingsDialog.isExtractorEnabled(it, key) } ?: true

    private fun currentTimeoutSec(minSec: Long = 8L): Long {
        val ctx = ctxOrNull() ?: return minSec
        return MovieFoxSettingsDialog.getTimeoutSeconds(ctx).toLong().coerceAtLeast(minSec)
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
                    val body = res.text
                    MovieFoxSettingsDialog.reportServerStatus(sourceTag, "LIVE", true, res.code, ms, url)
                    return body
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == retries - 1) {
                    logError(sourceTag, url, e, "attempt=${attempt + 1}")
                }
            }
            if (attempt < retries - 1) delay(300L)
        }
        return null
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
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (!targetUrl.startsWith("http") || isJunkCandidateUrl(targetUrl)) return

        if (targetUrl.contains(".m3u8", true) || targetUrl.contains(".mp4", true) || targetUrl.contains(".mpd", true)) {
            if (!lc.seenUrls.add(targetUrl)) return
            val linkType = when {
                targetUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                targetUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
            emitLink(
                newExtractorLink(
                    source = sourceTag,
                    name = "$sourceTag [$serverLabel]",
                    url = targetUrl,
                    type = linkType
                ) {
                    this.referer = referer
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("User-Agent" to effectiveUa, "Referer" to referer)
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
                        quality = if (ex.quality > 0) ex.quality else Qualities.P1080.value,
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
            runCatching {
                custom.getUrl(targetUrl, referer, emitSub) { ex ->
                    any = true
                    wrappedCb(ex)
                }
            }
        }
        if (!any) {
            runCatching {
                loadExtractor(targetUrl, referer, emitSub, wrappedCb)
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
        val q = lc.searchQueryTitle.trim().replace(" ", "+")
        val domains = (listOf(activePrmoviesBase) + prmoviesMirrors).distinct()

        for (dom in domains) {
            val searchHtml = safeGetText("$dom/?s=$q", mapOf("Referer" to "$dom/"), timeoutSec = 6L, retries = 1, sourceTag = "prmovies").orEmpty()
            if (searchHtml.isBlank()) continue
            activePrmoviesBase = dom

            val searchDoc = Jsoup.parse(searchHtml, dom)
            val matchedPageUrl = searchDoc.select(".ml-item a.ml-mask[href]")
                .firstOrNull { a ->
                    val t = (a.attr("oldtitle").ifBlank { a.attr("title") }.ifBlank { a.text() }).trim()
                    titleMatchesCandidate(t, lc.pageTitle)
                }?.attr("href")?.let { normalizeUrl(it, dom) } ?: continue

            val detailHtml = safeGetText(matchedPageUrl, mapOf("Referer" to "$dom/"), timeoutSec = 6L, retries = 1, sourceTag = "prmovies").orEmpty()
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
                    val epHtml = safeGetText(epHref, mapOf("Referer" to matchedPageUrl), timeoutSec = 6L, retries = 1, sourceTag = "prmovies").orEmpty()
                    if (epHtml.isNotBlank()) {
                        targetHtml = epHtml
                        targetUrl = epHref
                    }
                }
            }

            val targetDoc = Jsoup.parse(targetHtml, targetUrl)
            for (iframe in targetDoc.select("#player2 iframe[src], #content-embed iframe[src], div.movieplay iframe[src]")) {
                val src = iframe.attr("src").trim()
                if (src.isBlank()) continue
                val cleanEmbed = normalizeUrl(src, dom)
                when {
                    cleanEmbed.contains("speedostream", true) -> {
                        val host = runCatching { URI(cleanEmbed).host }.getOrNull() ?: "speedostream1.com"
                        val rText = safeGetText(cleanEmbed, mapOf("Referer" to "https://$host/"), timeoutSec = 5L, retries = 1, sourceTag = "prmovies").orEmpty()
                        val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(rText)?.groupValues?.get(1)
                            ?: Regex("""sources\s*:\s*\[\s*\{?\s*file\s*:\s*["']([^"']+)["']""").find(rText)?.groupValues?.get(1)
                        if (!m3u8.isNullOrBlank()) {
                            dispatchEmbedOrDirect("Prmovies", "Speedostream", m3u8, "https://$host/", lc, emitSub, emitLink)
                        }
                    }
                    cleanEmbed.contains("vidsrc", true) -> {
                        val r1 = safeGetText(cleanEmbed, mapOf("Referer" to targetUrl), timeoutSec = 5L, retries = 1, sourceTag = "prmovies").orEmpty()
                        val innerSrc = Jsoup.parse(r1).selectFirst("iframe[src]")?.attr("src").orEmpty()
                        if (innerSrc.isNotBlank()) {
                            val playerUrl = normalizeUrl(innerSrc, cleanEmbed)
                            val r2 = safeGetText(playerUrl, mapOf("Referer" to cleanEmbed), timeoutSec = 5L, retries = 1, sourceTag = "prmovies").orEmpty()
                            val m3u8 = Regex("""https:[^"' ]+\.m3u8[^"' ]*""").find(r2)?.value
                            if (!m3u8.isNullOrBlank()) {
                                dispatchEmbedOrDirect("Prmovies", "Vidsrc", m3u8, playerUrl, lc, emitSub, emitLink)
                            }
                        }
                    }
                    else -> dispatchEmbedOrDirect("Prmovies", "Embed", cleanEmbed, targetUrl, lc, emitSub, emitLink)
                }
            }
            break
        }
    }

    // =========================================================================
    // SERVER 2: HINDIDRAMA
    // =========================================================================
    private suspend fun fetchFromHindiDrama(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val q = safeEncode(lc.searchQueryTitle)
        val searchHtml = safeGetText("$hindiDramaBase/search.php?q=$q", mapOf("Referer" to "$hindiDramaBase/"), timeoutSec = 6L, retries = 1, sourceTag = "hindidrama").orEmpty()
        if (searchHtml.isBlank()) return

        val searchDoc = Jsoup.parse(searchHtml, hindiDramaBase)
        val matchedUrl = searchDoc.select(".grid .card a, .ac a.it").firstOrNull { anchor ->
            val t = anchor.selectFirst("h3, .t")?.text()?.trim().orEmpty().ifEmpty { anchor.attr("title").trim() }
            titleMatchesCandidate(t, lc.pageTitle)
        }?.attr("href")?.let { normalizeUrl(it, hindiDramaBase) } ?: return

        val detailHtml = safeGetText(matchedUrl, mapOf("Referer" to "$hindiDramaBase/"), timeoutSec = 6L, retries = 1, sourceTag = "hindidrama").orEmpty()
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
            safeGetText(watchPageUrl, mapOf("Referer" to matchedUrl), timeoutSec = 6L, retries = 1, sourceTag = "hindidrama").orEmpty()
        }
        if (pageHtml.isBlank()) return
        val doc = Jsoup.parse(pageHtml, watchPageUrl)

        val internalEmbedSrc = doc.selectFirst("#player iframe, iframe[src*='/embed/']")?.attr("src")?.trim()
            ?: Regex("""["']((?:https?:\\?/\\?/[^"'\s<>]+)?\\?/embed\\?/[^"'\s<>]+)["']""")
                .find(pageHtml)?.groupValues?.get(1)?.replace("\\/", "/")

        val playerPageUrl = if (!internalEmbedSrc.isNullOrEmpty()) normalizeUrl(internalEmbedSrc, hindiDramaBase) else watchPageUrl
        val playerHtml = if (!internalEmbedSrc.isNullOrEmpty()) {
            safeGetText(playerPageUrl, mapOf("Referer" to watchPageUrl), timeoutSec = 6L, retries = 1, sourceTag = "hindidrama").orEmpty()
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
    // SERVER 3: SFLIX
    // =========================================================================
    private suspend fun emitSflixArray(
        arr: JSONArray?,
        kind: String,
        lc: LinkContext,
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
        }
        return foundAny
    }

    private suspend fun fetchFromSflix(
        lc: LinkContext,
        emitSub: (SubtitleFile) -> Unit,
        emitLink: (ExtractorLink) -> Unit
    ) {
        if (lc.searchQueryTitle.isBlank()) return
        val activeHost = ctxOrNull()?.let { MovieFoxSettingsDialog.getSflixHost(it) } ?: sflixDomains.first()
        val searchUrl = "$activeHost/search-result?keyword=${safeEncode(lc.searchQueryTitle)}"
        val sHeaders = mapOf(
            "Referer" to "$activeHost/",
            "User-Agent" to effectiveUa,
            "Cookie" to sflixCookies
        )

        val searchHtml = safeGetText(searchUrl, sHeaders, timeoutSec = 6L, retries = 1, sourceTag = "sflix").orEmpty()
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

            val jsonText = safeGetText(playUrl, webHeaders, timeoutSec = 6L, retries = 1, sourceTag = "sflix").orEmpty()
            if (jsonText.isBlank()) continue

            val dataObj = runCatching { JSONObject(jsonText).optJSONObject("data") }.getOrNull() ?: continue
            val d1 = emitSflixArray(dataObj.optJSONArray("dash"), "DASH", lc, emitLink)
            val d2 = emitSflixArray(dataObj.optJSONArray("hls"), "HLS", lc, emitLink)
            val d3 = emitSflixArray(dataObj.optJSONArray("streams"), "MP4", lc, emitLink)

            if (d1 || d2 || d3) break
        }
    }

    // =========================================================================
    // LOAD LINKS: Runs Prmovies, HindiDrama & Sflix Servers in Parallel
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val ctx = ctxOrNull()
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
                seenUrls = ConcurrentHashMap.newKeySet()
            )

            val emitLock = Any()
            val subLock = Any()
            var found = false

            val emitSub: (SubtitleFile) -> Unit = { sub ->
                synchronized(subLock) { subtitleCallback(sub) }
            }
            val emitLink: (ExtractorLink) -> Unit = { link ->
                if (!isJunkCandidateUrl(link.url) && link.url.startsWith("http")) {
                    synchronized(emitLock) {
                        found = true
                        callback(link)
                    }
                }
            }

            val orderedKeys = (ctx?.let { MovieFoxSettingsDialog.getOrderedEnabledKeys(it) }
                ?: listOf("prmovies", "hindidrama", "sflix")).distinct()

            val sem = Semaphore(6)
            supervisorScope {
                val tasks = mutableListOf<Deferred<Unit>>()
                orderedKeys.forEach { key ->
                    tasks.add(async(Dispatchers.IO) {
                        sem.withPermit {
                            withTimeoutOrNull(14_000L) {
                                when (key.lowercase()) {
                                    "prmovies" -> fetchFromPrmovies(lc, emitSub, emitLink)
                                    "hindidrama" -> fetchFromHindiDrama(lc, emitSub, emitLink)
                                    "sflix" -> fetchFromSflix(lc, emitSub, emitLink)
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
            logError("loadLinks", data.take(120), e, "Fatal loadLinks error")
            false
        }
    }
}

