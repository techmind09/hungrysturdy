package com.hungrysturdy

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class SflixProvider : MainAPI() {
    override var mainUrl = "https://sflix.film"
    private val playerUrl = "https://mzfi.me"
    override var name = "Sflix"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val defaultPosterHeaders = mapOf("Referer" to "$mainUrl/")

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Featured",
        "$mainUrl/ranking-list?id=1232643093049001320" to "Popular Series",
        "$mainUrl/ranking-list?id=997144265920760504" to "Popular Movie",
        "$mainUrl/search-result?keyword=Trending" to "Trending Now",
        "$mainUrl/search-result?keyword=Cinema" to "Cinema",
        "$mainUrl/upcoming" to "Coming Soon",
        "$mainUrl/ranking-list?id=3130672938110833528" to "Adult Animation",
        "$mainUrl/ranking-list?id=7219449993227633120" to "Epic Fantasy",
        "$mainUrl/ranking-list?id=62133389738001440" to "Anime [English Dubbed]",
        "$mainUrl/ranking-list?id=4380734070238626200" to "K-Drama",
        "$mainUrl/ranking-list?id=173752404280836544" to "C-Drama",
        "$mainUrl/ranking-list?id=7269188813871016696" to "Action Movies",
        "$mainUrl/ranking-list?id=8785384881686725944" to "Comedy",
        "$mainUrl/search-result?keyword=Horror" to "Horror Movies",
        "$mainUrl/ranking-list?id=1746633591129342248" to "Teen Romance",
        "$mainUrl/ranking-list?id=7317586330186645624" to "Superhero Series",
        "$mainUrl/search-result?keyword=Hot" to "Hot stort TV",
        "$mainUrl/search-result?keyword=Short" to "Trending Short Drama",
        "$mainUrl/ranking-list?id=8892055635953697752" to "Bl Story",
        "$mainUrl/ranking-list?id=3741804521853438336" to "Bet+",
        "$mainUrl/ranking-list?id=6978603205429526968" to "Action&Thriller",
        "$mainUrl/ranking-list?id=5475136475249497544" to "Gangster",
        "$mainUrl/ranking-list?id=6934224112055632896" to "Teen Fantasy",
        "$mainUrl/search-result?keyword=Romance" to "Romance",
        "$mainUrl/search-result?keyword=Suggestions" to "Suggestions"
    )

    private fun cleanPosterUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().substringBefore("?x-oss-process=")
        return fixUrlNull(cleaned)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val separator = if (request.data.contains("?")) "&" else "?"
            "${request.data}${separator}page=$page"
        }

        val document = app.get(targetUrl, referer = "$mainUrl/").document

        if (request.data.trimEnd('/') == mainUrl) {
            val lists = document.select("section.comp-box").mapNotNull { section ->
                val sectionTitle = section.selectFirst("h2.action-title")?.text()?.trim()
                    ?: return@mapNotNull null
                val items = section.select("a.video-item").mapNotNull { it.toSearchResult() }
                if (items.isEmpty()) null else HomePageList(sectionTitle, items)
            }
            if (lists.isNotEmpty()) {
                return newHomePageResponse(lists, hasNext = false)
            }
        }

        val items = document.select("a.video-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.text, .video-name h3, .video-name")?.text()?.trim()
            ?: return null
        val rawHref = this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") ?: "" }
        if (rawHref.isEmpty()) return null
        val href = fixUrl(rawHref)

        val img = this.selectFirst("img")
        val posterUrl = img?.let {
            cleanPosterUrl(it.attr("data-src").ifEmpty { it.attr("src") })
        }

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = defaultPosterHeaders
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search-result?keyword=$encoded"
        val document = app.get(searchUrl, referer = "$mainUrl/").document
        return document.select("a.video-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document

        val title = document.selectFirst("h1.content-title")?.text()?.trim()
            ?: document.selectFirst("h2.title")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst("div.info-image img")?.let {
            cleanPosterUrl(it.attr("data-src").ifEmpty { it.attr("src") })
        }
        val description = document.selectFirst("p.content-desc")?.text()?.trim()

        val ratingStr = document.selectFirst("div.rate-num")?.text()?.let {
            Regex("""(\d+(\.\d+)?)""").find(it)?.groupValues?.get(1)
        }

        val labelTexts = document.select("div.content-label-desc > div")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        var year = labelTexts.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        var genres = labelTexts.dropWhile { !it.matches(Regex("""\d{4}""")) }
            .drop(1)
            .filter { it.length > 2 && it != "United States" && it != "United Kingdom" }

        val actors = document.select("section.top-cast-warp a.star-item").mapNotNull { star ->
            star.selectFirst(".star-name")?.text()?.trim()
        }

        val recommendations = document.select("section.more-like-warp a.video-item")
            .mapNotNull { it.toSearchResult() }

        var subjectId = Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.get(1) ?: ""
        var detailPath = url.substringAfter("/detail/").substringBefore("?")
        var subjectType = 1 // 1 = Movie, 2 = TvSeries
        var trailerUrl: String? = null
        val dubServers = JSONArray()
        val episodes = mutableListOf<Episode>()

        val nuxtRaw = document.selectFirst("script#__NUXT_DATA__")?.data()
        if (!nuxtRaw.isNullOrEmpty()) {
            runCatching {
                val nuxtArr = JSONArray(nuxtRaw)
                var rootObj = nuxtArr.optJSONObject(3)
                for (i in 0 until nuxtArr.length()) {
                    val obj = nuxtArr.optJSONObject(i) ?: continue
                    if (obj.has("subject") && obj.has("resource")) {
                        rootObj = obj
                        break
                    }
                }

                val subjectObj = rootObj?.optInt("subject", -1)?.let { nuxtArr.optJSONObject(it) }
                if (subjectObj != null) {
                    subjectObj.optInt("subjectId", -1).takeIf { it >= 0 }?.let {
                        subjectId = nuxtArr.optString(it, subjectId)
                    }
                    subjectObj.optInt("subjectType", -1).takeIf { it >= 0 }?.let {
                        subjectType = nuxtArr.optInt(it, 1)
                    }
                    subjectObj.optInt("detailPath", -1).takeIf { it >= 0 }?.let {
                        detailPath = nuxtArr.optString(it, detailPath)
                    }
                    subjectObj.optInt("genre", -1).takeIf { it >= 0 }?.let { idx ->
                        val g = nuxtArr.optString(idx)
                        if (g.isNotEmpty()) genres = g.split(",").map { it.trim() }
                    }
                    subjectObj.optInt("releaseDate", -1).takeIf { it >= 0 }?.let { idx ->
                        val rd = nuxtArr.optString(idx)
                        if (rd.length >= 4) year = rd.take(4).toIntOrNull() ?: year
                    }

                    // Extract Trailer URL (only for trailer button, never as main movie)
                    val trailerObj = subjectObj.optInt("trailer", -1).takeIf { it >= 0 }
                        ?.let { nuxtArr.optJSONObject(it) }
                    val videoAddrObj = trailerObj?.optInt("videoAddress", -1)?.takeIf { it >= 0 }
                        ?.let { nuxtArr.optJSONObject(it) }
                    trailerUrl = videoAddrObj?.optInt("url", -1)?.takeIf { it >= 0 }
                        ?.let { nuxtArr.optString(it) }
                        ?.takeIf { it.startsWith("http") }

                    // Extract Audio/Dub Servers (e.g. Original Audio, Hindi dub, etc.)
                    val dubsArr = subjectObj.optInt("dubs", -1).takeIf { it >= 0 }
                        ?.let { nuxtArr.optJSONArray(it) }
                    if (dubsArr != null) {
                        for (d in 0 until dubsArr.length()) {
                            val dubObj = nuxtArr.optJSONObject(dubsArr.optInt(d, -1)) ?: continue
                            val dubId = dubObj.optInt("subjectId", -1).takeIf { it >= 0 }
                                ?.let { nuxtArr.optString(it) } ?: continue
                            val lanName = dubObj.optInt("lanName", -1).takeIf { it >= 0 }
                                ?.let { nuxtArr.optString(it) } ?: "Server ${d + 1}"
                            val dubPath = dubObj.optInt("detailPath", -1).takeIf { it >= 0 }
                                ?.let { nuxtArr.optString(it) } ?: detailPath
                            dubServers.put(
                                JSONObject().apply {
                                    put("name", lanName)
                                    put("id", dubId)
                                    put("path", dubPath)
                                }
                            )
                        }
                    }
                }

                val resourceObj = rootObj?.optInt("resource", -1)?.let { nuxtArr.optJSONObject(it) }
                val seasonsArr = resourceObj?.optInt("seasons", -1)?.takeIf { it >= 0 }
                    ?.let { nuxtArr.optJSONArray(it) }

                if (seasonsArr != null) {
                    for (s in 0 until seasonsArr.length()) {
                        val seObj = nuxtArr.optJSONObject(seasonsArr.optInt(s, -1)) ?: continue
                        val seasonNum = nuxtArr.optInt(seObj.optInt("se", -1), s + 1)
                        val maxEp = nuxtArr.optInt(seObj.optInt("maxEp", -1), 1)
                        for (ep in 1..maxEp) {
                            val playData = buildEpisodePayload(
                                detailPath = detailPath,
                                subjectId = subjectId,
                                season = seasonNum,
                                episode = ep,
                                isSeries = true,
                                trailerUrl = trailerUrl,
                                dubs = dubServers
                            )
                            episodes.add(
                                newEpisode(playData) {
                                    this.name = "Episode $ep"
                                    this.season = seasonNum
                                    this.episode = ep
                                }
                            )
                        }
                    }
                }
            }
        }

        val isSeries = subjectType == 2 || episodes.size > 1

        return if (isSeries) {
            if (episodes.isEmpty()) {
                val playData = buildEpisodePayload(
                    detailPath = detailPath,
                    subjectId = subjectId,
                    season = 1,
                    episode = 1,
                    isSeries = true,
                    trailerUrl = trailerUrl,
                    dubs = dubServers
                )
                episodes.add(
                    newEpisode(playData) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = defaultPosterHeaders
                this.year = year
                this.plot = description
                this.tags = genres
                this.score = Score.from10(ratingStr)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailerUrl)
            }
        } else {
            val moviePlayData = buildEpisodePayload(
                detailPath = detailPath,
                subjectId = subjectId,
                season = 1,
                episode = 1,
                isSeries = false,
                trailerUrl = trailerUrl,
                dubs = dubServers
            )
            newMovieLoadResponse(title, url, TvType.Movie, moviePlayData) {
                this.posterUrl = poster
                this.posterHeaders = defaultPosterHeaders
                this.year = year
                this.plot = description
                this.tags = genres
                this.score = Score.from10(ratingStr)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailerUrl)
            }
        }
    }

    private fun buildEpisodePayload(
        detailPath: String,
        subjectId: String,
        season: Int,
        episode: Int,
        isSeries: Boolean,
        trailerUrl: String?,
        dubs: JSONArray
    ): String {
        return JSONObject().apply {
            put("detailPath", detailPath)
            put("subjectId", subjectId)
            put("season", season)
            put("episode", episode)
            put("isSeries", isSeries)
            put("trailerUrl", trailerUrl ?: "")
            put("dubs", dubs)
        }.toString()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val defaultPath = payload.optString("detailPath")
        val defaultId = payload.optString("subjectId")
        val season = payload.optInt("season", 1)
        val episode = payload.optInt("episode", 1)
        val isSeries = payload.optBoolean("isSeries", false)
        val trailerUrl = payload.optString("trailerUrl")
        val dubsArr = payload.optJSONArray("dubs")

        data class ServerEntry(val label: String, val id: String, val path: String)

        val servers = mutableListOf<ServerEntry>()
        if (dubsArr != null && dubsArr.length() > 0) {
            for (i in 0 until dubsArr.length()) {
                val d = dubsArr.optJSONObject(i) ?: continue
                val dId = d.optString("id")
                val dPath = d.optString("path").ifEmpty { defaultPath }
                val dName = d.optString("name").ifEmpty { "Server ${i + 1}" }
                if (dId.isNotEmpty()) {
                    servers.add(ServerEntry(dName, dId, dPath))
                }
            }
        }
        if (servers.isEmpty() && defaultId.isNotEmpty()) {
            servers.add(ServerEntry("Original Audio", defaultId, defaultPath))
        }

        var anyFound = false
        val emittedUrls = mutableSetOf<String>()
        val emittedSubs = mutableSetOf<String>()

        // Resolve primary server first (and additional dubs if present, capped at 2 for speed)
        for (server in servers.take(2)) {
            val targetPlayerUrl = if (isSeries) {
                "$playerUrl/spa/videoPlayPage/movies/${server.path}?id=${server.id}&se=$season&ep=$episode&type=/series/detail&lang=en"
            } else {
                "$playerUrl/spa/videoPlayPage/movies/${server.path}?id=${server.id}&type=/movie/detail&lang=en"
            }

            val (streams, subs) = resolveStreamsViaWebView(
                pageUrl = targetPlayerUrl,
                referer = "$mainUrl/",
                excludeTrailer = trailerUrl
            )

            streams.forEach { streamUrl ->
                if (emittedUrls.add(streamUrl)) {
                    anyFound = true
                    val streamType = when {
                        streamUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                        streamUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                        else -> ExtractorLinkType.VIDEO
                    }
                    val qualityTag = when {
                        streamUrl.contains("1080") -> "1080p"
                        streamUrl.contains("720") -> "720p"
                        streamUrl.contains("480") -> "480p"
                        streamUrl.contains(".mpd", true) -> "Auto/DASH"
                        else -> "HD"
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - ${server.label} ($qualityTag)",
                            url = streamUrl,
                            type = streamType
                        ) {
                            this.referer = "$playerUrl/"
                            this.headers = mapOf(
                                "Origin" to playerUrl,
                                "Referer" to "$playerUrl/"
                            )
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }

            subs.forEach { subUrl ->
                if (emittedSubs.add(subUrl)) {
                    val langLabel = when {
                        subUrl.contains("hi", true) || subUrl.contains("hindi", true) -> "Hindi"
                        subUrl.contains("es", true) -> "Spanish"
                        subUrl.contains("fr", true) -> "French"
                        subUrl.contains("ar", true) -> "Arabic"
                        subUrl.contains("id", true) -> "Indonesian"
                        else -> "English"
                    }
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = langLabel,
                            url = subUrl
                        )
                    )
                }
            }
        }

        return anyFound
    }

    private fun getAppContext(): Context? {
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApp = activityThread.getMethod("currentApplication").invoke(null)
            currentApp as? Context
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveStreamsViaWebView(
        pageUrl: String,
        referer: String,
        excludeTrailer: String
    ): Pair<List<String>, List<String>> {
        val context = getAppContext() ?: return Pair(emptyList(), emptyList())
        val foundStreams = ConcurrentHashMap.newKeySet<String>()
        val foundSubs = ConcurrentHashMap.newKeySet<String>()
        val mainHandler = Handler(Looper.getMainLooper())

        val adDomains = listOf(
            "surlyplants.com",
            "createlouisville.org",
            "hisavana.com",
            "pufted.com",
            "shallspark.com",
            "googletagmanager.com",
            "google-analytics.com",
            "doubleclick.net",
            "googlesyndication.com",
            "popads.net",
            "adskeeper.com",
            "tsyndicate.com",
            "exoclick.com",
            "ifunny-promo"
        )

        withTimeoutOrNull(18_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val resumed = AtomicBoolean(false)
                var webView: WebView? = null

                fun finish() {
                    if (resumed.compareAndSet(false, true)) {
                        mainHandler.post {
                            runCatching {
                                webView?.stopLoading()
                                webView?.destroy()
                                webView = null
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    mainHandler.post {
                        runCatching {
                            webView?.stopLoading()
                            webView?.destroy()
                            webView = null
                        }
                    }
                }

                mainHandler.post {
                    runCatching {
                        val wv = WebView(context)
                        webView = wv

                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(wv, true)
                        }

                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = USER_AGENT
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null

                                // Block third-party ad networks & game popups
                                if (adDomains.any { reqUrl.contains(it, ignoreCase = true) }) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }

                                // Capture subtitles (.vtt / .srt)
                                if (reqUrl.contains(".vtt", ignoreCase = true) ||
                                    reqUrl.contains(".srt", ignoreCase = true)
                                ) {
                                    foundSubs.add(reqUrl)
                                }

                                // Capture actual video streams (.mpd / .m3u8 / .mp4), ignoring trailers
                                val isVideoStream = reqUrl.contains(".mpd", ignoreCase = true) ||
                                    reqUrl.contains(".m3u8", ignoreCase = true) ||
                                    reqUrl.contains(".mp4", ignoreCase = true)

                                val isTrailer = excludeTrailer.isNotEmpty() &&
                                    reqUrl.substringBefore("?") == excludeTrailer.substringBefore("?")

                                if (isVideoStream && !isTrailer && !reqUrl.startsWith("blob:")) {
                                    foundStreams.add(reqUrl)
                                    // Wait 1.5s to allow subtitle tracks (.vtt) to finish requesting
                                    mainHandler.postDelayed({ finish() }, 1500L)
                                }

                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // Trigger Artplayer initialization if waiting for user interaction
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        var btn = document.querySelector('.art-state, .watch-btn, video');
                                        if (btn && btn.click) btn.click();
                                        var v = document.querySelector('video');
                                        if (v && v.play) v.play().catch(function(){});
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }

                        wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                    }.onFailure {
                        finish()
                    }
                }
            }
        }

        return Pair(foundStreams.toList(), foundSubs.toList())
    }
}
