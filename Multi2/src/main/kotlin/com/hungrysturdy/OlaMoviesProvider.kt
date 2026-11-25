package com.hungrysturdy

import android.util.Base64 as AndroidBase64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class OlaMoviesProvider : MainAPI() {
    override var mainUrl = "https://v6.olamovies.mov"
    private val driveBaseUrl = "https://drive.olamovies.download"

    // TMDb public key for episode list resolution via IMDb ID
    private val tmdbApiUrl = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "439c478a771f35c05022f9feabcca01c"

    // Sflix & HindiDrama server configs
    private val sflixUrl = "https://sflix.film"
    private val sflixPlayerUrl = "https://mzfi.me"
    private val sflixDomains = listOf(
        "https://sflix.film",
        "https://h5-api.aoneroom.com",
        "https://movie-box.co",
        "https://123moviesfree.club"
    )
    private val hindiDramaUrl = "https://hindidrama.net"

    override var name = "OlaMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private val mapper = jacksonObjectMapper()
    private val random = SecureRandom()
    private val deviceId: String = generateDeviceId()

    private val webUserAgent =
        "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private val sflixCookies =
        "sflix_i18n_lang=en; _ga=GA1.1.622965780.1794742147; " +
            "sflix_token=%22eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjE5NjY4NTU1NTcyNzgwNjIyNzYsImF0cCI6MywiZXh0IjoiMTc5NDc0MjE0NyIsImV4cCI6MTgwMjUxODE0NywiaWF0IjoxNzk0NzQxODQ3fQ.8aOIckQOSKyISQdl85SAJvuMlhXfWodHmcPOIvlg0pM%22; " +
            "vrk4n8fqhwc3jzy7pbsmgt6dx5lha2u9=01a1b98c-7aec-7761-b581-b09b7730a724_2; " +
            "dom3ic8zudi28v8lr6fgphwffqoz0j6c=01a1b98c-7aec-7761-b581-b09b7730a724"

    private val modernUserAgent =
        "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"

    private val modernClientInfo =
        "{\"package_name\":\"com.community.mbox.in\",\"version_name\":\"4.0.03.0920.03\",\"version_code\":50020130," +
            "\"os\":\"android\",\"os_version\":\"14\",\"device_id\":\"$deviceId\",\"install_store\":\"official\"," +
            "\"gaid\":\"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d\",\"brand\":\"Google\",\"model\":\"Pixel 8\"," +
            "\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"IN\",\"timezone\":\"Asia/Calcutta\",\"sp_code\":\"\"}"

    private var mainCookie: String = ""
    private var driveCookie: String = ""

    fun setCookies(v6Cookie: String, omDriveCookie: String) {
        mainCookie = v6Cookie.trim()
        driveCookie = omDriveCookie.trim()
    }

    private fun getHeaders(referer: String = "$mainUrl/", isDrive: Boolean = false): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to webUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer
        )
        val cookieToUse = if (isDrive) driveCookie else mainCookie
        if (cookieToUse.isNotBlank()) {
            headers["Cookie"] = cookieToUse
        }
        return headers
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Uploads",
        "$mainUrl/?sort=trending" to "Trending",
        "$mainUrl/?sort=most_viewed" to "Most Viewed",
        "$mainUrl/movies/imdb-top-250-collection-download-links-google-drive" to "IMDb Top 250",

        "$mainUrl/categories/movies" to "All Movies",
        "$mainUrl/categories/hollywood" to "Hollywood Movies",
        "$mainUrl/categories/bollywood" to "Bollywood Movies",
        "$mainUrl/categories/south-indian" to "South Indian Movies",
        "$mainUrl/categories/korean-movies" to "Korean Movies",
        "$mainUrl/categories/chinese-movies" to "Chinese Movies",
        "$mainUrl/categories/anime-movies" to "Anime Movies",
        "$mainUrl/categories/japanese-movies" to "Japanese Movies",
        "$mainUrl/categories/documentary-movies" to "Documentary Movies",
        "$mainUrl/categories/short-films" to "Short Films",
        "$mainUrl/categories/bangla" to "Bengali Movies",
        "$mainUrl/categories/other" to "Other Movies",

        "$mainUrl/categories/tv-series" to "All TV Series",
        "$mainUrl/categories/english-tv-series" to "English TV Series",
        "$mainUrl/categories/hindi-tv-series" to "Hindi TV Series",
        "$mainUrl/categories/korean-tv-series" to "Korean TV Series",
        "$mainUrl/categories/south-tv-series" to "South TV Series",
        "$mainUrl/categories/anime-tv-series" to "Anime TV Series",
        "$mainUrl/categories/chinese-tv-series" to "Chinese TV Series",
        "$mainUrl/categories/bangla-tv-series" to "Bengali TV Series",
        "$mainUrl/categories/other-tv-series" to "Other TV Series",

        "$mainUrl/categories/tv-shows" to "All TV Shows",
        "$mainUrl/categories/hindi-tv-shows" to "Hindi TV Shows",
        "$mainUrl/categories/documentary" to "Documentary Shows",
        "$mainUrl/categories/standup-tours-and-concerts" to "StandUp + Tours + Concerts",
        "$mainUrl/categories/award-shows" to "Award Shows",
        "$mainUrl/categories/cartoon-tvs" to "Cartoon TVs",
        "$mainUrl/categories/survival" to "Survival Shows",
        "$mainUrl/categories/specials-tv-shows" to "Specials",
        "$mainUrl/categories/talk-shows" to "Talk Shows",

        "$mainUrl/tags/collection" to "All Collections",
        "$mainUrl/movies/the-marvel-cinematic-universe-movies-collection-720p-1080p-2160p-4k-bluray-x265-10bit-hevc-dual-audio-esub" to "Marvel Cinematic Universe",
        "$mainUrl/movies/dc-extended-universe-collection" to "DC Extended Universe",
        "$mainUrl/movies/x-men-universe-collection" to "X-Men Universe",
        "$mainUrl/movies/spider-man-all-movies-collection" to "Spider-Man Collection",
        "$mainUrl/movies/dc-universe-animated-original-movies-collection" to "DC Animated Movies",
        "$mainUrl/movies/harry-potter-complete-collection-2001-2011" to "Harry Potter Series",
        "$mainUrl/movies/collection-of-star-wars-films" to "Star Wars Saga",
        "$mainUrl/movies/fast-furious-collection" to "Fast & Furious",
        "$mainUrl/movies/james-bond-25-movies-the-complete-collection" to "James Bond Collection",
        "$mainUrl/movies/ip-man-series-complete-collection" to "IP Man Collection",
        "$mainUrl/movies/jackie-chan-all-movies-complete-collection" to "Jackie Chan Collection",
        "$mainUrl/movies/bruce-lee-the-movie-collection-1971-1981-1080p-bluray-x265-10bit-hevc-english-chinese-esubs" to "Bruce Lee Collection"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page == 1) request.data else "${request.data}${separator}page=$page"

        val res = app.get(url, headers = getHeaders())
        val items = parseListingResponse(res.text)

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun parseListingResponse(body: String): List<SearchResponse> {
        val doc = Jsoup.parse(body, mainUrl)
        doc.select("header, nav, aside, footer").remove()

        val cardResults = doc.select("main div[data-listing-results] div.group\\/card > a, main div.grid > div.group\\/card > a, div.group\\/card > a")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        if (cardResults.isNotEmpty()) return cardResults

        return doc.select("main a[href*='/movies/'], main a[href*='/tv-series/'], main a[href*='/tv-shows/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val rawTitle = anchor.selectFirst("span.text-\\[13\\.5px\\]")?.text()?.trim()
                    ?: anchor.text().trim()
                if (rawTitle.length < 3) return@mapNotNull null

                val cleanTitle = cleanReleaseTitle(rawTitle)
                val imgSrc = anchor.selectFirst("img")?.attr("src") ?: ""
                val poster = extractRealImageUrl(imgSrc)
                val isSeries = href.contains("/tv-series/") || href.contains("/tv-shows/")

                if (isSeries) {
                    newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.quality = parseQuality(rawTitle)
                    }
                } else {
                    newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                        this.posterUrl = poster
                        this.quality = parseQuality(rawTitle)
                    }
                }
            }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (href.contains("/sso-login")) return null

        val imgEl = this.selectFirst("img") ?: return null
        val imgSrc = imgEl.attr("src").ifBlank { imgEl.attr("data-src") }
        val posterUrl = extractRealImageUrl(imgSrc) ?: return null

        val titleSpan = this.selectFirst("span.text-\\[13\\.5px\\]") ?: return null
        val clonedTitle = titleSpan.clone()
        clonedTitle.select("span[aria-label*='Paid'], span[title='Ongoing']").remove()
        val rawTitle = clonedTitle.text().trim()
        if (rawTitle.isBlank()) return null

        val cleanTitle = cleanReleaseTitle(rawTitle)
        val qualityBadges = this.select("span.top-2\\.5.left-2\\.5 span").text()
        val quality = parseQuality("$qualityBadges $rawTitle")
        val isSeries = href.contains("/tv-series/") || href.contains("/tv-shows/")

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    private fun cleanReleaseTitle(rawTitle: String): String {
        return rawTitle
            .substringBefore("720p")
            .substringBefore("1080p")
            .substringBefore("2160p")
            .substringBefore("480p")
            .replace(Regex("^\\[Season.*?\\]\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bSeason\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bS\\d{1,2}(?:-S\\d{1,2})?\\b.*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { rawTitle }
    }

    private fun parseQuality(text: String): SearchQuality? {
        return when {
            text.contains("2160p", true) || text.contains("4K", true) -> SearchQuality.UHD
            text.contains("1080p", true) || text.contains("720p", true) || text.contains("1280x", true) -> SearchQuality.HD
            else -> null
        }
    }

    private fun extractRealImageUrl(src: String): String? {
        if (src.isBlank()) return null
        return try {
            if (src.contains("/_next/image") && src.contains("url=")) {
                val encodedUrl = src.substringAfter("url=").substringBefore("&")
                URLDecoder.decode(encodedUrl, "UTF-8")
            } else {
                fixUrlNull(src)
            }
        } catch (_: Exception) {
            fixUrlNull(src)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${query.encodeUri()}"
        val res = app.get(searchUrl, headers = getHeaders())
        return parseListingResponse(res.text)
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("#")
        val response = app.get(cleanUrl, headers = getHeaders())
        val html = response.text
        val doc = response.document

        val fullTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: cleanUrl.substringAfterLast("/").replace("-", " ")
        val year = Regex("\\((\\d{4})\\)").find(fullTitle)?.groupValues?.get(1)?.toIntOrNull()
        val pureName = cleanReleaseTitle(fullTitle)
            .replace(Regex("\\(\\d{4}\\)"), "")
            .trim()
        val displayTitle = cleanReleaseTitle(fullTitle)

        val imdbId = Regex("""(tt\d{6,10})""").find(html)?.groupValues?.get(1)
        val postId = extractPostId(html)
        val backdrops = if (postId != null) fetchPostBackdrops(postId, cleanUrl) else emptyList()

        val rawImg = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("main img")?.attr("src") ?: ""
        val poster = extractRealImageUrl(rawImg) ?: backdrops.firstOrNull()
        val background = backdrops.firstOrNull() ?: poster

        val description = doc.selectFirst("meta[name='description']")?.attr("content")
            ?: doc.select("main p").firstOrNull { it.text().length > 60 }?.text()

        val mainDoc = doc.selectFirst("main") ?: doc
        val tags = mainDoc.select("a[href*='/tags/'], a[href*='/categories/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("All ") && it.length < 25 }
            .distinct()

        val isSeries = cleanUrl.contains("/tv-series/") || cleanUrl.contains("/tv-shows/")

        return if (isSeries) {
            val sflixMatch = findSflixSubject(pureName, year)
            val episodes = mutableListOf<Episode>()

            // 1. TMDb API (IMDb ID ya Title se) — saare real seasons & episodes fetch karein
            val tmdbEpisodes = fetchTmdbAllEpisodes(
                imdbId = imdbId,
                title = pureName,
                year = year,
                olaUrl = "$cleanUrl#downloads",
                sflixMatch = sflixMatch,
                defaultPoster = poster
            )
            if (tmdbEpisodes.isNotEmpty()) {
                episodes.addAll(tmdbEpisodes)
            }

            // 2. Agar TMDb se episodes nahi mile, toh Sflix seasons metadata se episodes banayein
            if (episodes.isEmpty() && sflixMatch != null && sflixMatch.seasons.isNotEmpty()) {
                for ((seasonNum, maxEp) in sflixMatch.seasons) {
                    val safeSeason = if (seasonNum <= 0) 1 else seasonNum
                    for (ep in 1..maxEp) {
                        val epPayload = JSONObject().apply {
                            put("olaUrl", "$cleanUrl#downloads")
                            put("title", pureName)
                            put("year", year ?: 0)
                            put("isSeries", true)
                            put("season", safeSeason)
                            put("episode", ep)
                            put("sflixId", sflixMatch.subjectId)
                            put("sflixPath", sflixMatch.detailPath)
                            put("sflixDubs", sflixMatch.dubs)
                        }.toString()

                        episodes.add(
                            newEpisode(epPayload) {
                                this.name = "Episode $ep"
                                this.season = safeSeason
                                this.episode = ep
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }

            // 3. OlaMovies ke direct links check karein
            if (episodes.isEmpty()) {
                val downloadSection = mainDoc.selectFirst("#downloads") ?: mainDoc
                val directEpCandidates = downloadSection.select("a[href], button[data-url], button[data-href]")
                    .mapNotNull { el ->
                        val rawLink = el.attr("href")
                            .ifBlank { el.attr("data-url") }
                            .ifBlank { el.attr("data-href") }
                        val fixed = fixUrlNull(rawLink) ?: return@mapNotNull null
                        if (fixed.contains("/sso-login") || fixed.startsWith("#")) return@mapNotNull null
                        val label = el.text().trim().ifBlank { el.attr("aria-label").trim() }
                        fixed to label
                    }
                    .distinctBy { it.first }

                directEpCandidates.forEachIndexed { index, (epUrl, label) ->
                    val seasonNum = Regex("S(\\d{1,2})|Season\\s*(\\d{1,2})", RegexOption.IGNORE_CASE)
                        .find(label)?.groupValues?.firstOrNull { it.isNotBlank() && it.all { c -> c.isDigit() } }?.toIntOrNull() ?: 1
                    val epNum = Regex("E(\\d{1,3})|Episode\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
                        .find(label)?.groupValues?.firstOrNull { it.isNotBlank() && it.all { c -> c.isDigit() } }?.toIntOrNull() ?: (index + 1)

                    val epPayload = JSONObject().apply {
                        put("olaUrl", epUrl)
                        put("title", pureName)
                        put("year", year ?: 0)
                        put("isSeries", true)
                        put("season", seasonNum)
                        put("episode", epNum)
                    }.toString()

                    episodes.add(
                        newEpisode(epPayload) {
                            this.name = label.ifBlank { "Episode $epNum" }
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    )
                }
            }

            // 4. Final fallback: Season range (e.g. [Season 1-2]) ke hisaab se episodes generate karein
            if (episodes.isEmpty()) {
                val seasonRangeMatch = Regex("Season\\s*(\\d+)(?:\\s*-\\s*(\\d+))?", RegexOption.IGNORE_CASE).find(fullTitle)
                val startSeason = seasonRangeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val endSeason = seasonRangeMatch?.groupValues?.get(2)?.toIntOrNull() ?: startSeason

                for (s in startSeason..endSeason) {
                    for (ep in 1..10) {
                        val fallbackPayload = JSONObject().apply {
                            put("olaUrl", "$cleanUrl#downloads")
                            put("title", pureName)
                            put("year", year ?: 0)
                            put("isSeries", true)
                            put("season", s)
                            put("episode", ep)
                        }.toString()

                        episodes.add(
                            newEpisode(fallbackPayload) {
                                this.name = "Episode $ep"
                                this.season = s
                                this.episode = ep
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(displayTitle, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            val moviePayload = JSONObject().apply {
                put("olaUrl", "$cleanUrl#downloads")
                put("title", pureName)
                put("year", year ?: 0)
                put("isSeries", false)
                put("season", 0)
                put("episode", 0)
            }.toString()

            newMovieLoadResponse(displayTitle, cleanUrl, TvType.Movie, moviePayload) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    private suspend fun fetchTmdbAllEpisodes(
        imdbId: String?,
        title: String,
        year: Int?,
        olaUrl: String,
        sflixMatch: SflixSubjectInfo?,
        defaultPoster: String?
    ): List<Episode> {
        return try {
            var tmdbTvId: Int? = null

            // 1. Pehle IMDb ID se exact TMDb TV ID dhundhein
            if (!imdbId.isNullOrBlank()) {
                val findUrl = "$tmdbApiUrl/find/$imdbId?api_key=$tmdbApiKey&external_source=imdb_id"
                val findJson = JSONObject(app.get(findUrl).text)
                val tvResults = findJson.optJSONArray("tv_results")
                if (tvResults != null && tvResults.length() > 0) {
                    tmdbTvId = tvResults.getJSONObject(0).optInt("id", 0).takeIf { it > 0 }
                }
            }

            // 2. Fallback: Title + Year se TMDb TV search
            if (tmdbTvId == null && title.isNotBlank()) {
                val yearParam = if (year != null && year > 1900) "&first_air_date_year=$year" else ""
                val searchUrl = "$tmdbApiUrl/search/tv?api_key=$tmdbApiKey&query=${title.encodeUri()}$yearParam"
                val searchJson = JSONObject(app.get(searchUrl).text)
                val results = searchJson.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    tmdbTvId = results.getJSONObject(0).optInt("id", 0).takeIf { it > 0 }
                }
            }

            if (tmdbTvId == null) return emptyList()

            // 3. TV show details se saare seasons list nikalna
            val tvDetailsUrl = "$tmdbApiUrl/tv/$tmdbTvId?api_key=$tmdbApiKey"
            val tvDetails = JSONObject(app.get(tvDetailsUrl).text)
            val seasonsArr = tvDetails.optJSONArray("seasons") ?: return emptyList()

            val allEpisodes = mutableListOf<Episode>()
            for (i in 0 until seasonsArr.length()) {
                val sObj = seasonsArr.getJSONObject(i)
                val seasonNum = sObj.optInt("season_number", 0)
                if (seasonNum <= 0) continue // Specials (Season 0) skip

                val seasonUrl = "$tmdbApiUrl/tv/$tmdbTvId/season/$seasonNum?api_key=$tmdbApiKey"
                val seasonJson = JSONObject(app.get(seasonUrl).text)
                val epArr = seasonJson.optJSONArray("episodes") ?: continue

                for (j in 0 until epArr.length()) {
                    val epObj = epArr.getJSONObject(j)
                    val epNum = epObj.optInt("episode_number", j + 1)
                    val epName = epObj.optString("name").ifBlank { "Episode $epNum" }
                    val overview = epObj.optString("overview").takeIf { it.isNotBlank() }
                    val stillPath = epObj.optString("still_path").takeIf { it.isNotBlank() && it != "null" }
                    val epPoster = stillPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: defaultPoster

                    val epPayload = JSONObject().apply {
                        put("olaUrl", olaUrl)
                        put("title", title)
                        put("year", year ?: 0)
                        put("isSeries", true)
                        put("season", seasonNum)
                        put("episode", epNum)
                        if (sflixMatch != null) {
                            put("sflixId", sflixMatch.subjectId)
                            put("sflixPath", sflixMatch.detailPath)
                            put("sflixDubs", sflixMatch.dubs)
                        }
                    }.toString()

                    allEpisodes.add(
                        newEpisode(epPayload) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.description = overview
                            this.posterUrl = epPoster
                        }
                    )
                }
            }

            allEpisodes
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractPostId(html: String): String? {
        return Regex("""/api/posts/(\d+)/""").find(html)?.groupValues?.get(1)
            ?: Regex(""""postId"\s*:\s*"?(\d+)"?""").find(html)?.groupValues?.get(1)
    }

    private suspend fun fetchPostBackdrops(postId: String, referer: String): List<String> {
        if (postId.isBlank()) return emptyList()
        return try {
            val apiUrl = "$mainUrl/api/posts/$postId/backdrops"
            val jsonText = app.get(apiUrl, headers = getHeaders(referer)).text
            Regex("""https?://[^\s"'\\<>]+?\.(?:jpg|jpeg|png|webp)(?:\?[^\s"'\\<>]*)?""", RegexOption.IGNORE_CASE)
                .findAll(jsonText)
                .map { it.value.replace("\\/", "/") }
                .distinct()
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { JSONObject(data) }.getOrNull()
        val olaUrl = payload?.optString("olaUrl")?.ifBlank { data } ?: data
        val title = payload?.optString("title") ?: ""
        val year = payload?.optInt("year", 0)?.takeIf { it > 0 }
        val isSeries = payload?.optBoolean("isSeries", false) ?: false
        val season = payload?.optInt("season", 1) ?: 1
        val episode = payload?.optInt("episode", 1) ?: 1
        val presetSflixId = payload?.optString("sflixId") ?: ""
        val presetSflixPath = payload?.optString("sflixPath") ?: ""
        val presetSflixDubs = payload?.optJSONArray("sflixDubs")

        var foundAny = false

        // 1. OlaMovies links
        if (olaUrl.isNotBlank()) {
            if (loadOlaMoviesLinks(olaUrl, subtitleCallback, callback)) {
                foundAny = true
            }
        }

        // 2. Sflix Multi-Server (Original + Hindi/Regional Dubs: DASH / HLS / MP4 + Captions)
        if (title.isNotBlank()) {
            val sflixFound = loadSflixServers(
                title = title,
                year = year,
                isSeries = isSeries,
                season = season,
                episode = episode,
                presetId = presetSflixId,
                presetPath = presetSflixPath,
                presetDubs = presetSflixDubs,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            if (sflixFound) foundAny = true
        }

        // 3. HindiDrama Servers (StreamTape + Direct m3u8/mp4)
        if (title.isNotBlank()) {
            val hdFound = loadHindiDramaServers(
                title = title,
                isSeries = isSeries,
                episode = episode,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            if (hdFound) foundAny = true
        }

        return foundAny
    }

    // ==================== 1. OLAMOVIES RESOLVER ====================

    private suspend fun loadOlaMoviesLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val cleanData = data.substringBefore("#")

        if (isDirectVideoUrl(cleanData)) {
            emitDirectVideo(cleanData, "OlaMovies Direct", "$driveBaseUrl/", callback)
            return true
        }

        if (cleanData.contains("drive.olamovies.download")) {
            return resolveDrivePage(cleanData, subtitleCallback, callback)
        }

        if (!cleanData.startsWith(mainUrl)) {
            loadExtractor(cleanData, "$mainUrl/", subtitleCallback, callback)
            return true
        }

        val autoClickJs = """
            (function() {
                let attempts = 0;
                const timer = setInterval(() => {
                    attempts++;
                    const container = document.querySelector('#downloads') || document.body;
                    const btns = Array.from(container.querySelectorAll('button, a'));
                    const dlBtn = btns.find(b => {
                        const t = (b.innerText || '').toLowerCase();
                        return (t.includes('1080p') || t.includes('720p') || t.includes('2160p') || t.includes('download') || t.includes('get link'))
                            && !t.includes('how to') && !b.href?.includes('/sso-login');
                    });
                    if (dlBtn) dlBtn.click();
                    const driveLink = Array.from(document.querySelectorAll('a[href*="drive.olamovies.download/file/"]'))[0];
                    if (driveLink) {
                        clearInterval(timer);
                        window.location.href = driveLink.href;
                    }
                    if (attempts > 15) clearInterval(timer);
                }, 500);
            })();
        """.trimIndent()

        val interceptedUrl = try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""drive\.olamovies\.download/file/|video-downloads\.googleusercontent\.com/|\.(?:mp4|mkv|m3u8)"""),
                script = autoClickJs,
                scriptCallback = { _ -> },
                useOkhttp = false,
                timeout = 10_000L
            )
            app.get(url = data, headers = getHeaders(), interceptor = resolver).url
        } catch (_: Exception) {
            ""
        }

        if (interceptedUrl.isNotBlank()) {
            when {
                isDirectVideoUrl(interceptedUrl) -> {
                    emitDirectVideo(interceptedUrl, "OlaMovies WebView", data, callback)
                    found = true
                }
                interceptedUrl.contains("drive.olamovies.download/file/") -> {
                    if (resolveDrivePage(interceptedUrl, subtitleCallback, callback)) found = true
                }
            }
        }

        val response = app.get(cleanData, headers = getHeaders())
        val doc = response.document
        val downloadSection = doc.selectFirst("#downloads") ?: doc
        val htmlText = downloadSection.html() + "\n" + doc.select("script").joinToString("\n") { it.html() }

        val urlRegex = Regex("""https?://[^\s"'\\<>]+""")
        val allUrls = urlRegex.findAll(htmlText)
            .map { it.value.trimEnd('.', ',', ';', ')').replace("\\/", "/") }
            .distinct()
            .toList()

        for (target in allUrls) {
            when {
                isDirectVideoUrl(target) -> {
                    emitDirectVideo(target, "OlaMovies Direct", cleanData, callback)
                    found = true
                }
                target.contains("drive.olamovies.download/file/") -> {
                    if (resolveDrivePage(target, subtitleCallback, callback)) found = true
                }
                !target.startsWith(mainUrl) &&
                    !target.contains("/sso-login") &&
                    !target.contains("telegram.me") &&
                    !target.contains("youtube.com") &&
                    !target.contains("olam18plus") &&
                    !target.contains("rarbgdump") &&
                    !target.contains("graph.org") -> {
                    loadExtractor(target, cleanData, subtitleCallback, callback)
                    found = true
                }
            }
        }

        return found
    }

    private suspend fun resolveDrivePage(
        driveUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val res = app.get(driveUrl, headers = getHeaders(referer = "$mainUrl/", isDrive = true))
        val body = res.text

        val fileId = driveUrl.substringAfter("/file/").substringBefore("?").substringBefore("#").trim()
        val fileName = Regex(""""fileName"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
        val fileSize = Regex(""""fileSize"\s*:\s*([0-9.]+)""").find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        if (fileId.isNotBlank()) {
            try {
                val payload = """{"fileId":"$fileId","fileSize":$fileSize,"fileName":"$fileName"}"""
                val progressRes = app.post(
                    "$driveBaseUrl/api/file/cloud/progress",
                    headers = getHeaders(referer = driveUrl, isDrive = true) + mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to driveBaseUrl
                    ),
                    requestBody = payload.toRequestBody("application/json".toMediaType())
                ).text

                extractDirectUrls(progressRes).forEach { streamUrl ->
                    emitDirectVideo(streamUrl, "OM Fast Cloud", driveUrl, callback)
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        extractDirectUrls(body).forEach { streamUrl ->
            emitDirectVideo(streamUrl, "OM Drive", driveUrl, callback)
            found = true
        }

        val mirrorRegex = Regex(
            """https?://(?:[^\s"'\\<>]*(?:vikingfile|pixeldrain|gdflix|buzzheavier|gofile|hubcloud|gdlink|workers\.dev)[^\s"'\\<>]*)""",
            RegexOption.IGNORE_CASE
        )
        mirrorRegex.findAll(body).map { it.value.replace("\\/", "/") }.distinct().forEach { mirrorUrl ->
            loadExtractor(mirrorUrl, driveUrl, subtitleCallback, callback)
            found = true
        }

        return found
    }

    private fun extractDirectUrls(text: String): List<String> {
        val directRegex = Regex(
            """https?://video-downloads\.googleusercontent\.com/[^\s"'\\<>]+|https?://[^\s"'\\<>]+\.(?:mp4|mkv|m3u8)(?:\?[^\s"'\\<>]*)?""",
            RegexOption.IGNORE_CASE
        )
        return directRegex.findAll(text)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()
    }

    private suspend fun emitDirectVideo(
        videoUrl: String,
        sourceName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
        val quality = when {
            videoUrl.contains("2160", ignoreCase = true) || videoUrl.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            videoUrl.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            videoUrl.contains("720", ignoreCase = true) -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }

        callback.invoke(
            newExtractorLink(
                source = sourceName,
                name = sourceName,
                url = videoUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = getHeaders(referer = referer, isDrive = true)
            }
        )
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        return url.contains("video-downloads.googleusercontent.com", ignoreCase = true) ||
            url.contains(".mp4", ignoreCase = true) ||
            url.contains(".mkv", ignoreCase = true) ||
            url.contains(".m3u8", ignoreCase = true)
    }

    // ==================== 2. SFLIX MULTI-SERVER INTEGRATION ====================

    private data class SflixSubjectInfo(
        val subjectId: String,
        val detailPath: String,
        val dubs: JSONArray,
        val seasons: List<Pair<Int, Int>>
    )

    private val sflixWebHeaders = mapOf(
        "Referer" to "$sflixUrl/",
        "User-Agent" to webUserAgent,
        "Cookie" to sflixCookies,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    private suspend fun findSflixSubject(title: String, year: Int?): SflixSubjectInfo? {
        return try {
            val searchUrl = "$sflixUrl/search-result?keyword=${title.encodeUri()}"
            val searchDoc = app.get(searchUrl, headers = sflixWebHeaders).document

            var bestHref: String? = null
            val links = searchDoc.select("a.video-item, a[href*='/detail/']")
            for (el in links) {
                val href = el.attr("href").takeIf { it.contains("/detail/") } ?: continue
                val cardTitle = el.selectFirst("h3.text, .video-name h3, .video-name, h3")?.text()?.trim()
                    ?: el.attr("title")
                if (cardTitle.contains(title, ignoreCase = true)) {
                    bestHref = href
                    break
                }
                if (bestHref == null) bestHref = href
            }

            if (bestHref == null) {
                val nuxtRaw = searchDoc.selectFirst("script#__NUXT_DATA__")?.data()
                if (!nuxtRaw.isNullOrEmpty()) {
                    val arr = JSONArray(nuxtRaw)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val tIdx = obj.optInt("title", -1)
                        val pIdx = obj.optInt("detailPath", -1)
                        if (tIdx >= 0 && pIdx >= 0) {
                            val t = arr.optString(tIdx)
                            val dp = arr.optString(pIdx)
                            val sid = obj.optInt("subjectId", -1).takeIf { it >= 0 }?.let { arr.optString(it) } ?: ""
                            if (t.contains(title, ignoreCase = true) && dp.isNotEmpty()) {
                                bestHref = if (sid.isNotEmpty()) "/detail/$dp?id=$sid" else "/detail/$dp"
                                break
                            }
                        }
                    }
                }
            }

            val detailUrl = bestHref?.let {
                if (it.startsWith("http")) it else "$sflixUrl${if (it.startsWith("/")) "" else "/"}$it"
            } ?: return null

            val detailDoc = app.get(detailUrl, headers = sflixWebHeaders).document
            var subjectId = Regex("""[?&]id=(\d+)""").find(detailUrl)?.groupValues?.get(1) ?: ""
            var detailPath = detailUrl.substringAfter("/detail/").substringBefore("?")
            val dubServers = JSONArray()
            val seasonsList = mutableListOf<Pair<Int, Int>>()

            val nuxtRaw = detailDoc.selectFirst("script#__NUXT_DATA__")?.data() ?: return null
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
                    val v = nuxtArr.optString(it)
                    if (v.isNotEmpty()) subjectId = v
                }
                subjectObj.optInt("detailPath", -1).takeIf { it >= 0 }?.let {
                    val v = nuxtArr.optString(it)
                    if (v.isNotEmpty()) detailPath = v
                }
                val dubsArr = subjectObj.optInt("dubs", -1).takeIf { it >= 0 }?.let { nuxtArr.optJSONArray(it) }
                if (dubsArr != null) {
                    for (d in 0 until dubsArr.length()) {
                        val dubObj = nuxtArr.optJSONObject(dubsArr.optInt(d, -1)) ?: continue
                        val dubId = dubObj.optInt("subjectId", -1).takeIf { it >= 0 }?.let { nuxtArr.optString(it) } ?: continue
                        val lanName = dubObj.optInt("lanName", -1).takeIf { it >= 0 }?.let { nuxtArr.optString(it) } ?: "Server ${d + 1}"
                        val dubPath = dubObj.optInt("detailPath", -1).takeIf { it >= 0 }?.let { nuxtArr.optString(it) } ?: detailPath
                        dubServers.put(JSONObject().apply {
                            put("name", lanName)
                            put("id", dubId)
                            put("path", dubPath)
                        })
                    }
                }
            }

            val resourceObj = rootObj?.optInt("resource", -1)?.takeIf { it >= 0 }?.let { nuxtArr.optJSONObject(it) }
            val seasonsArr = resourceObj?.optInt("seasons", -1)?.takeIf { it >= 0 }?.let { nuxtArr.optJSONArray(it) }
            if (seasonsArr != null) {
                for (s in 0 until seasonsArr.length()) {
                    val seObj = nuxtArr.optJSONObject(seasonsArr.optInt(s, -1)) ?: continue
                    val seNum = seObj.optInt("se", -1).takeIf { it >= 0 }?.let { nuxtArr.optInt(it, s + 1) } ?: (s + 1)
                    val maxEp = seObj.optInt("maxEp", -1).takeIf { it >= 0 }?.let { nuxtArr.optInt(it, 1) } ?: 1
                    seasonsList.add(seNum to maxEp)
                }
            }

            if (subjectId.isBlank()) return null
            SflixSubjectInfo(subjectId, detailPath, dubServers, seasonsList)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadSflixServers(
        title: String,
        year: Int?,
        isSeries: Boolean,
        season: Int,
        episode: Int,
        presetId: String,
        presetPath: String,
        presetDubs: JSONArray?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = if (presetId.isNotBlank() && presetPath.isNotBlank()) {
            SflixSubjectInfo(presetId, presetPath, presetDubs ?: JSONArray(), emptyList())
        } else {
            findSflixSubject(title, year) ?: return false
        }

        data class ServerEntry(val label: String, val id: String, val path: String)

        val servers = mutableListOf<ServerEntry>()
        servers.add(ServerEntry("Original", info.subjectId, info.detailPath))
        for (i in 0 until info.dubs.length()) {
            val d = info.dubs.optJSONObject(i) ?: continue
            val dId = d.optString("id")
            val dPath = d.optString("path").ifEmpty { info.detailPath }
            val dName = d.optString("name").ifEmpty { "Dub ${i + 1}" }
            if (dId.isNotEmpty() && servers.none { it.id == dId }) {
                servers.add(ServerEntry(dName, dId, dPath))
            } else if (dId == info.subjectId && servers.isNotEmpty()) {
                servers[0] = ServerEntry(dName, dId, dPath)
            }
        }

        val seParam = if (isSeries) season.coerceAtLeast(1) else 0
        val epParam = if (isSeries) episode.coerceAtLeast(1) else 0
        var anyFound = false
        val emittedUrls = mutableSetOf<String>()

        for (server in servers) {
            for (domain in sflixDomains) {
                try {
                    val playUrl = "$domain/wefeed-h5api-bff/subject/play?subjectId=${server.id}" +
                        "&se=$seParam&ep=$epParam&detailPath=${server.path}" +
                        "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
                    val referer = "$sflixPlayerUrl/spa/videoPlayPage/movies/${server.path}?id=${server.id}&type=/movie/detail&lang=en"
                    val headers = mapOf(
                        "User-Agent" to webUserAgent,
                        "Referer" to referer,
                        "Origin" to sflixPlayerUrl,
                        "Accept" to "application/json",
                        "Cookie" to sflixCookies,
                        "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                        "x-request-lang" to "en",
                        "x-vip-restrict" to "0",
                        "x-no-high-risk-restrict" to "0"
                    )

                    val resp = app.get(playUrl, headers = headers)
                    if (resp.code != 200) continue
                    val dataNode = mapper.readTree(resp.text).get("data") ?: continue

                    suspend fun emitStream(item: JsonNode, kind: String) {
                        val streamUrl = item.get("url")?.asText()?.takeUnless { it.isBlank() } ?: return
                        if (!emittedUrls.add(streamUrl)) return

                        val signCookie = item.get("signCookie")?.asText()?.takeUnless { it.isBlank() }
                        val signHeaderKey = item.get("signHeaderKey")?.asText()?.takeUnless { it.isBlank() } ?: "X-MB-Token"
                        val resolutions = item.get("resolutions")?.asText() ?: ""
                        val streamId = item.get("id")?.asText() ?: server.id

                        val isDash = kind == "DASH" || streamUrl.contains(".mpd", true)
                        val isHls = kind == "HLS" || streamUrl.contains(".m3u8", true)
                        val linkType = when {
                            isDash -> ExtractorLinkType.DASH
                            isHls -> ExtractorLinkType.M3U8
                            else -> ExtractorLinkType.VIDEO
                        }

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
                            "User-Agent" to webUserAgent,
                            "Accept" to "*/*"
                        )
                        if (signCookie != null) {
                            streamHeaders[signHeaderKey] = signCookie
                            streamHeaders["Cookie"] = signCookie
                        }

                        anyFound = true
                        callback.invoke(
                            newExtractorLink(
                                source = "Sflix",
                                name = "Sflix - ${server.label} [$kind $qualityLabel]",
                                url = streamUrl,
                                type = linkType
                            ) {
                                this.referer = "$sflixPlayerUrl/"
                                this.headers = streamHeaders
                                this.quality = maxRes
                            }
                        )

                        loadSflixCaptions(streamId, server.label, server.id, subtitleCallback)
                    }

                    dataNode.get("dash")?.takeIf { it.isArray }?.forEach { emitStream(it, "DASH") }
                    dataNode.get("hls")?.takeIf { it.isArray }?.forEach { emitStream(it, "HLS") }
                    dataNode.get("streams")?.takeIf { it.isArray }?.forEach { emitStream(it, "MP4") }

                    if (anyFound) break
                } catch (_: Exception) {
                    continue
                }
            }
        }

        return anyFound
    }

    private suspend fun loadSflixCaptions(
        streamId: String,
        languageLabel: String,
        subjectId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val seen = HashSet<String>()
        try {
            val subLink = "https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId"
            val ts = System.currentTimeMillis()
            val secretBytes = Base64.getDecoder().decode(String(Base64.getDecoder().decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")))
            val parsed = URI(subLink)
            val canonical = "GET\napplication/json\napplication/json\n\n$ts\n\n${parsed.path}?${parsed.rawQuery}"
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
            val sig = "$ts|2|" + Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray()))

            val subHeaders = mapOf(
                "user-agent" to modernUserAgent,
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to "$ts,${md5(ts.toString().reversed().toByteArray())}",
                "x-tr-signature" to sig,
                "x-client-info" to modernClientInfo,
                "x-client-status" to "0"
            )
            val subResponse = app.get(subLink, headers = subHeaders)
            val subRoot = mapper.readTree(subResponse.text).get("data")?.get("extCaptions")
            if (subRoot != null && subRoot.isArray) {
                for (caption in subRoot) {
                    val url = caption.get("url")?.asText()?.takeUnless { it.isBlank() } ?: continue
                    if (!seen.add(url)) continue
                    val rawLang = caption.get("lanName")?.asText()?.takeUnless { it.isBlank() }
                        ?: caption.get("language")?.asText()?.takeUnless { it.isBlank() }
                        ?: "English"
                    subtitleCallback.invoke(newSubtitleFile("$rawLang ($languageLabel)", url))
                }
            }
        } catch (_: Exception) {
        }
    }

    // ==================== 3. HINDIDRAMA SERVER INTEGRATION ====================

    private suspend fun loadHindiDramaServers(
        title: String,
        isSeries: Boolean,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val searchUrl = "$hindiDramaUrl/search.php?q=${title.encodeUri()}"
            val searchDoc = app.get(searchUrl, headers = mapOf("User-Agent" to webUserAgent)).document

            val showHref = searchDoc.select(".grid .card a, .ac a.it")
                .firstOrNull { el ->
                    val t = el.selectFirst("h3, .t")?.text()?.trim() ?: el.attr("title")
                    t.contains(title, ignoreCase = true)
                }?.attr("href")
                ?: searchDoc.selectFirst(".grid .card a, .ac a.it")?.attr("href")
                ?: return false

            val showUrl = if (showHref.startsWith("http")) showHref else "$hindiDramaUrl${if (showHref.startsWith("/")) "" else "/"}$showHref"
            val showDoc = app.get(showUrl, headers = mapOf("User-Agent" to webUserAgent)).document

            val targetWatchUrl = if (isSeries) {
                val epLinks = showDoc.select(".eplist ul li a, a[href*='/hindi-dubbed/watch-']")
                val matchedEp = epLinks.firstOrNull { el ->
                    val txt = el.text().trim()
                    val href = el.attr("href")
                    val num = Regex("""(?:Episode|Ep|E|e)\s*(\d+)""").find(txt)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""-e(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    num == episode
                }?.attr("href") ?: epLinks.getOrNull(episode - 1)?.attr("href")

                if (!matchedEp.isNullOrBlank()) {
                    if (matchedEp.startsWith("http")) matchedEp else "$hindiDramaUrl${if (matchedEp.startsWith("/")) "" else "/"}$matchedEp"
                } else {
                    showUrl
                }
            } else {
                showUrl
            }

            val watchDoc = app.get(targetWatchUrl, headers = mapOf("User-Agent" to webUserAgent)).document
            val pageHtml = watchDoc.html()
            var foundLinks = false

            val internalEmbedSrc = watchDoc.selectFirst("#player iframe, iframe[src*='/embed/']")?.attr("src")?.trim()
                ?: Regex("""["']((?:https?:\\?/\\?/[^"'\s<>]+)?\\?/embed\\?/[^"'\s<>]+)["']""")
                    .find(pageHtml)?.groupValues?.get(1)?.replace("\\/", "/")

            val playerPageUrl = if (!internalEmbedSrc.isNullOrEmpty()) {
                if (internalEmbedSrc.startsWith("http")) internalEmbedSrc else "$hindiDramaUrl${if (internalEmbedSrc.startsWith("/")) "" else "/"}$internalEmbedSrc"
            } else {
                targetWatchUrl
            }

            val playerHtml = if (!internalEmbedSrc.isNullOrEmpty()) {
                app.get(playerPageUrl, headers = mapOf("User-Agent" to webUserAgent, "Referer" to targetWatchUrl)).text
            } else {
                pageHtml
            }

            val playerDoc = Jsoup.parse(playerHtml, playerPageUrl)
            val serverList = linkedMapOf<String, String>()

            playerDoc.select(".srv, [data-src], [data-url], [data-embed], [data-link], [data-video]").forEach { el ->
                val sName = el.text().trim().ifEmpty { "HindiDrama" }
                val rawUrl = listOf("data-src", "data-url", "data-embed", "data-link", "data-video")
                    .firstNotNullOfOrNull { attr -> el.attr(attr).trim().takeIf { it.isNotEmpty() } }
                    ?: Regex("""['"]((?:https?:)?//[^'"]+|/[^'"]+)['"]""").find(el.attr("onclick"))?.groupValues?.get(1)

                if (!rawUrl.isNullOrEmpty() && (rawUrl.startsWith("http") || rawUrl.startsWith("//") || rawUrl.startsWith("/"))) {
                    val fullUrl = when {
                        rawUrl.startsWith("//") -> "https:$rawUrl"
                        rawUrl.startsWith("/") -> "$hindiDramaUrl$rawUrl"
                        else -> rawUrl
                    }
                    if (!fullUrl.contains("hindidrama.net/hindi-dubbed")) {
                        serverList[fullUrl] = "HindiDrama - $sName"
                    }
                }
            }

            playerDoc.select("iframe").forEach { el ->
                val src = el.attr("data-src").ifEmpty { el.attr("src") }.trim()
                if (src.isNotEmpty() && !src.startsWith("about:")) {
                    val fullSrc = if (src.startsWith("//")) "https:$src" else if (src.startsWith("/")) "$hindiDramaUrl$src" else src
                    serverList.putIfAbsent(fullSrc, "HindiDrama Embed")
                }
            }

            for ((rawEmbedUrl, serverLabel) in serverList) {
                val embedUrl = normalizeStreamTapeUrl(rawEmbedUrl)
                val loaded = loadExtractor(embedUrl, playerPageUrl, subtitleCallback, callback) ||
                    loadExtractor(embedUrl, "$hindiDramaUrl/", subtitleCallback, callback)

                if (loaded) {
                    foundLinks = true
                    continue
                }

                try {
                    val embedHtml = app.get(embedUrl, headers = mapOf("User-Agent" to webUserAgent, "Referer" to playerPageUrl)).text
                    val unpacked = JsUnpacker(embedHtml).unpack() ?: embedHtml
                    val decodedChunks = StringBuilder("$embedHtml\n$unpacked")
                    Regex("""["']([A-Za-z0-9+/=]{60,})["']""").findAll(embedHtml).forEach { b64Match ->
                        try {
                            val decoded = String(AndroidBase64.decode(b64Match.groupValues[1], AndroidBase64.DEFAULT))
                            if (decoded.contains("http")) decodedChunks.append("\n").append(decoded)
                        } catch (_: Exception) {
                        }
                    }

                    val streamUrl = Regex("""["'](https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
                        .findAll(decodedChunks.toString())
                        .map { it.groupValues[1].replace("\\/", "/") }
                        .firstOrNull { !it.contains("preview") && !it.contains("thumb") }

                    if (!streamUrl.isNullOrEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                source = serverLabel,
                                name = serverLabel,
                                url = streamUrl
                            ) {
                                this.referer = embedUrl
                                this.quality = 0
                                this.headers = mapOf("User-Agent" to webUserAgent, "Referer" to embedUrl)
                            }
                        )
                        foundLinks = true
                    }
                } catch (_: Exception) {
                }
            }

            foundLinks
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeStreamTapeUrl(rawUrl: String): String {
        val mirrors = listOf("streamtape.to", "streamtape.net", "streamtape.xyz", "shavetape.cash", "strcloud.link", "strtape.tech", "stape.fun")
        var updated = rawUrl
        for (m in mirrors) {
            if (updated.contains(m, ignoreCase = true)) {
                updated = updated.replace(m, "streamtape.com", ignoreCase = true)
            }
        }
        return updated.replace("streamtape.com/v/", "streamtape.com/e/", ignoreCase = true)
    }

    private fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { String.format("%02x", it) }
    }

    private fun String.encodeUri(): String =
        URLEncoder.encode(this, "UTF-8")
}

