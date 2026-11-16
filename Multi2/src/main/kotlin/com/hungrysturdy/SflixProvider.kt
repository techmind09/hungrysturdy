package com.hungrysturdy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SflixProvider : MainAPI() {

    companion object {
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

        val WEB_DOMAINS = listOf(
            "https://sflix.film",
            "https://h5-api.aoneroom.com",
            "https://movie-box.co",
            "https://123moviesfree.club"
        )

        private const val TOKEN_BOOTSTRAP_URL =
            "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"

        private const val SECRET_DEFAULT_B64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
        var bearerToken: String? = null

        fun decodeJwtExpiry(token: String): Long {
            return try {
                val payload = token.split(".").getOrNull(1) ?: return 0L
                val std = payload.replace("-", "+").replace("_", "/")
                val padded = std + "=".repeat((4 - (std.length % 4)) % 4)
                val json = String(Base64.getUrlDecoder().decode(padded))
                JSONObject(json).getLong("exp")
            } catch (e: Exception) {
                0L
            }
        }

        fun isTokenValid(token: String?): Boolean {
            if (token.isNullOrBlank()) return false
            return decodeJwtExpiry(token) > (System.currentTimeMillis() / 1000) + 3600
        }
    }

    override var mainUrl = "https://sflix.film"
    private val playerUrl = "https://mzfi.me"
    override var name = "Sflix"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val random = SecureRandom()
    private val deviceId: String = generateDeviceId()
    private val mapper = jacksonObjectMapper()

    private val modernUserAgent =
        "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"

    private val modernClientInfo =
        "{\"package_name\":\"com.community.mbox.in\",\"version_name\":\"4.0.03.0920.03\",\"version_code\":50020130," +
            "\"os\":\"android\",\"os_version\":\"14\",\"device_id\":\"$deviceId\",\"install_store\":\"official\"," +
            "\"gaid\":\"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d\",\"brand\":\"Google\",\"model\":\"Pixel 8\"," +
            "\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"IN\",\"timezone\":\"Asia/Calcutta\",\"sp_code\":\"\"}"

    private val userCookies =
        "sflix_i18n_lang=en; _ga=GA1.1.622965780.1794742147; " +
            "sflix_token=%22eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjE5NjY4NTU1NTcyNzgwNjIyNzYsImF0cCI6MywiZXh0IjoiMTc5NDc0MjE0NyIsImV4cCI6MTgwMjUxODE0NywiaWF0IjoxNzk0NzQxODQ3fQ.8aOIckQOSKyISQdl85SAJvuMlhXfWodHmcPOIvlg0pM%22; " +
            "vrk4n8fqhwc3jzy7pbsmgt6dx5lha2u9=01a1b98c-7aec-7761-b581-b09b7730a724_2; " +
            "dom3ic8zudi28v8lr6fgphwffqoz0j6c=01a1b98c-7aec-7761-b581-b09b7730a724"

    private val defaultPosterHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to WEB_USER_AGENT,
        "Cookie" to userCookies,
        "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    )

    private val requestHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to WEB_USER_AGENT,
        "Cookie" to userCookies,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Featured",
        "$mainUrl/ranking-list?id=5514044369772445984&page_from=more_SUBJECTS_MOVIE" to "Trending Now",
        "$mainUrl/ranking-list?id=5692654647815587592&page_from=more_SUBJECTS_MOVIE" to "Cinema",
        "$mainUrl/upcoming" to "Coming Soon",
        "$mainUrl/ranking-list?id=1067246098818943288&page_from=more_SUBJECTS_MOVIE" to "Top Series",
        "$mainUrl/ranking-list?id=8019599703232971616&page_from=more_SUBJECTS_MOVIE" to "Hollywood",
        "$mainUrl/ranking-list?id=414907768299210008&page_from=more_SUBJECTS_MOVIE" to "Bollywood",
        "$mainUrl/ranking-list?id=3859721901924910512&page_from=more_SUBJECTS_MOVIE" to "South Indian",
        "$mainUrl/ranking-list?id=4903182713986896328&page_from=more_SUBJECTS_MOVIE" to "Indian Drama",
        "$mainUrl/ranking-list?id=537172945375056936&page_from=more_SUBJECTS_MOVIE" to "Hot Short TV",
        "$mainUrl/ranking-list?id=4380734070238626200&page_from=more_SUBJECTS_MOVIE" to "K-Drama",
        "$mainUrl/ranking-list?id=8624142774394406504&page_from=more_SUBJECTS_MOVIE" to "C-Drama",
        "$mainUrl/ranking-list?id=8434602210994128512&page_from=more_SUBJECTS_MOVIE" to "Top Anime",
        "$mainUrl/ranking-list?id=5177200225164885656&page_from=more_SUBJECTS_MOVIE" to "Turkish Drama",
        "$mainUrl/ranking-list?id=1440506765396822800&page_from=more_SUBJECTS_MOVIE" to "Western TV",
        "$mainUrl/ranking-list?id=2602566563471442096&page_from=more_SUBJECTS_MOVIE" to "Trending Punjabi",
        "$mainUrl/ranking-list?id=6843072545655603600&page_from=more_SUBJECTS_MOVIE" to "Popular Movies",
        "$mainUrl/ranking-list?id=4516404531735022304&page_from=more_SUBJECTS_MOVIE" to "Action & Thriller"
    )

    private fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { String.format("%02x", it) }
    }

    private fun generateXClientToken(timestamp: Long = System.currentTimeMillis()): String {
        val ts = timestamp.toString()
        return ts + "," + md5(ts.reversed().toByteArray())
    }

    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        return try {
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
            val bodyHash = if (body != null) md5(body.toByteArray()) else ""
            val bodyLength = body?.toByteArray()?.size?.toString() ?: ""
            method.uppercase(Locale.ROOT) + "\n" + (accept ?: "") + "\n" + (contentType ?: "") + "\n" +
                bodyLength + "\n" + timestamp + "\n" + bodyHash + "\n" + canonicalUrl
        } catch (e: Exception) {
            method.uppercase(Locale.ROOT) + "\n" + (accept ?: "") + "\n" + (contentType ?: "") + "\n\n" + timestamp + "\n\n"
        }
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secretBytes = Base64.getDecoder().decode(String(Base64.getDecoder().decode(SECRET_DEFAULT_B64)))
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray()))
        return "$timestamp|2|$signature"
    }

    private suspend fun fetchAnonymousToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh && isTokenValid(bearerToken)) return bearerToken!!
        return try {
            val ts = System.currentTimeMillis()
            val headers = mapOf(
                "user-agent" to modernUserAgent,
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to generateXClientToken(ts),
                "x-tr-signature" to generateXTrSignature("GET", "application/json", "application/json", TOKEN_BOOTSTRAP_URL, null, ts),
                "x-client-info" to modernClientInfo,
                "x-client-status" to "0"
            )
            val resp = app.get(TOKEN_BOOTSTRAP_URL, headers = headers)
            val token = resp.headers["x-user"]?.let { runCatching { JSONObject(it).optString("token") }.getOrNull() }
            if (!token.isNullOrBlank()) {
                bearerToken = token
                token
            } else {
                bearerToken ?: ""
            }
        } catch (e: Exception) {
            bearerToken ?: ""
        }
    }

    private suspend fun buildMobileAuthHeaders(url: String): Map<String, String> {
        val ts = System.currentTimeMillis()
        val headers = mutableMapOf(
            "user-agent" to modernUserAgent,
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-client-token" to generateXClientToken(ts),
            "x-tr-signature" to generateXTrSignature("GET", "application/json", "application/json", url, null, ts),
            "x-client-info" to modernClientInfo,
            "x-client-status" to "0"
        )
        val token = fetchAnonymousToken()
        if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
        return headers
    }

    private fun normalizePosterUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("data:")) return null
        return fixUrlNull(trimmed.substringBefore("?x-oss-process=").substringBefore("?"))
    }

    private suspend fun fetchDocument(url: String): Document {
        val res = app.get(url, headers = requestHeaders)
        if (res.code == 429 || res.text.contains("robot", true)) {
            delay(1000L)
            return app.get(url, headers = requestHeaders).document
        }
        return res.document
    }

    private fun extractNuxtPosterMap(document: Document): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val nuxtRaw = document.selectFirst("script#__NUXT_DATA__")?.data() ?: return map
        runCatching {
            val arr = JSONArray(nuxtRaw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.has("detailPath") && obj.has("cover")) {
                    val pathIdx = obj.optInt("detailPath", -1)
                    val coverIdx = obj.optInt("cover", -1)
                    if (pathIdx >= 0 && coverIdx >= 0) {
                        val path = arr.optString(pathIdx)
                        val coverObj = arr.optJSONObject(coverIdx)
                        val urlIdx = coverObj?.optInt("url", -1) ?: -1
                        if (path.isNotEmpty() && urlIdx >= 0) {
                            val coverUrl = normalizePosterUrl(arr.optString(urlIdx))
                            if (!coverUrl.isNullOrEmpty()) map[path] = coverUrl
                        }
                    }
                }
            }
        }
        return map
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) request.data else "${request.data}${if (request.data.contains("?")) "&" else "?"}page=$page"
        val document = fetchDocument(targetUrl)
        val nuxtPosters = extractNuxtPosterMap(document)

        if (request.data.trimEnd('/') == mainUrl) {
            val homeLists = document.select("section.comp-box").mapNotNull { section ->
                val sectionTitle = section.selectFirst("h2.action-title")?.text()?.trim() ?: return@mapNotNull null
                val items = section.select("a.video-item").mapNotNull { it.toSearchResult(nuxtPosters) }.distinctBy { it.url }
                if (items.isEmpty()) null else HomePageList(sectionTitle, items)
            }
            if (homeLists.isNotEmpty()) return newHomePageResponse(homeLists, hasNext = false)
        }

        var items = document.select("a.video-item, a[href*='/detail/']").mapNotNull { it.toSearchResult(nuxtPosters) }.distinctBy { it.url }
        if (items.isEmpty()) items = extractItemsFromNuxt(document)

        return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(posterMap: Map<String, String> = emptyMap()): SearchResponse? {
        val rawHref = if (this.tagName() == "a") this.attr("href") else this.selectFirst("a")?.attr("href") ?: ""
        if (rawHref.isEmpty() || !rawHref.contains("/detail/")) return null

        val title = this.selectFirst("h3.text, .video-name h3, .video-name, h3")?.text()?.trim()?.ifEmpty {
            this.attr("title").removePrefix("go to ").removeSuffix(" detail page").trim()
        } ?: return null

        val detailSlug = rawHref.substringAfter("/detail/").substringBefore("?")
        val href = fixUrl(rawHref)
        val img = this.selectFirst("img.banner, .video-item-cover img, img")
        val rawImgUrl = img?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        val posterUrl = normalizePosterUrl(rawImgUrl) ?: posterMap[detailSlug]

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = defaultPosterHeaders
        }
    }

    private fun extractItemsFromNuxt(document: Document): List<SearchResponse> {
        val nuxtRaw = document.selectFirst("script#__NUXT_DATA__")?.data() ?: return emptyList()
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        runCatching {
            val nuxtArr = JSONArray(nuxtRaw)
            for (i in 0 until nuxtArr.length()) {
                val obj = nuxtArr.optJSONObject(i) ?: continue
                if (!obj.has("title") || !obj.has("detailPath")) continue
                val titleIdx = obj.optInt("title", -1)
                val pathIdx = obj.optInt("detailPath", -1)
                if (titleIdx < 0 || pathIdx < 0) continue

                val title = nuxtArr.optString(titleIdx).trim()
                val detailPath = nuxtArr.optString(pathIdx).trim()
                if (title.isEmpty() || detailPath.isEmpty() || title.contains("{movietitle}")) continue

                val subjectId = obj.optInt("subjectId", -1).takeIf { it >= 0 }?.let { nuxtArr.optString(it) } ?: ""
                var posterUrl: String? = null
                val coverIdx = obj.optInt("cover", -1)
                if (coverIdx >= 0) {
                    val coverObj = nuxtArr.optJSONObject(coverIdx)
                    val urlIdx = coverObj?.optInt("url", -1) ?: -1
                    if (urlIdx >= 0) posterUrl = normalizePosterUrl(nuxtArr.optString(urlIdx))
                }

                val itemUrl = if (subjectId.isNotEmpty()) "$mainUrl/detail/$detailPath?id=$subjectId" else "$mainUrl/detail/$detailPath"
                if (seenUrls.add(itemUrl)) {
                    results.add(newMovieSearchResponse(title, itemUrl, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.posterHeaders = defaultPosterHeaders
                    })
                }
            }
        }
        return results
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search-result?keyword=${URLEncoder.encode(query, "UTF-8")}"
        val document = fetchDocument(searchUrl)
        val nuxtPosters = extractNuxtPosterMap(document)
        var items = document.select("a.video-item, a[href*='/detail/']").mapNotNull { it.toSearchResult(nuxtPosters) }.distinctBy { it.url }
        if (items.isEmpty()) items = extractItemsFromNuxt(document)
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = fetchDocument(url)
        val nuxtPosters = extractNuxtPosterMap(document)

        var title = document.selectFirst("h1.content-title")?.text()?.trim() ?: document.selectFirst("h2.title")?.text()?.trim() ?: return null
        var poster = document.selectFirst("div.info-image img")?.let { normalizePosterUrl(it.attr("data-src").ifEmpty { it.attr("src") }) }
            ?: normalizePosterUrl(document.selectFirst("meta[property=og:image]")?.attr("content"))
        var description = document.selectFirst("p.content-desc")?.text()?.trim() ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        var ratingStr = document.selectFirst("div.rate-num")?.text()?.let { Regex("""(\d+(\.\d+)?)""").find(it)?.groupValues?.get(1) }

        val labelTexts = document.select("div.content-label-desc > div").map { it.text().trim() }.filter { it.isNotEmpty() }
        var year = labelTexts.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        var genres = labelTexts.dropWhile { !it.matches(Regex("""\d{4}""")) }.drop(1).filter { it.length > 2 && it != "United States" && it != "United Kingdom" }

        val actors = document.select("section.top-cast-warp a.star-item").mapNotNull { star -> star.selectFirst(".star-name")?.text()?.trim() }.distinct()
        val recommendations = document.select("section.more-like-warp a.video-item").mapNotNull { it.toSearchResult(nuxtPosters) }

        var subjectId = Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.get(1) ?: ""
        var detailPath = url.substringAfter("/detail/").substringBefore("?")
        var subjectType = 1
        var trailerUrl: String? = null
        val dubServers = JSONArray()
        val episodes = mutableListOf<Episode>()

        val nuxtRaw = document.selectFirst("script#__NUXT_DATA__")?.data()
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
                    subjectObj.optInt("subjectType", -1).takeIf { it >= 0 }?.let {
                        subjectType = nuxtArr.optInt(it, subjectType)
                    }
                    subjectObj.optInt("detailPath", -1).takeIf { it >= 0 }?.let {
                        val dp = nuxtArr.optString(it)
                        if (dp.isNotEmpty()) detailPath = dp
                    }
                    subjectObj.optInt("title", -1).takeIf { it >= 0 }?.let {
                        val t = nuxtArr.optString(it)
                        if (t.isNotEmpty() && !t.contains("{movietitle}")) title = t
                    }
                    subjectObj.optInt("description", -1).takeIf { it >= 0 }?.let {
                        val d = nuxtArr.optString(it)
                        if (d.isNotEmpty()) description = d
                    }
                    subjectObj.optInt("imdbRatingValue", -1).takeIf { it >= 0 }?.let {
                        val r = nuxtArr.optString(it)
                        if (r.isNotEmpty()) ratingStr = r
                    }
                    subjectObj.optInt("genre", -1).takeIf { it >= 0 }?.let { idx ->
                        val g = nuxtArr.optString(idx)
                        if (g.isNotEmpty()) genres = g.split(",").map { it.trim() }
                    }
                    subjectObj.optInt("releaseDate", -1).takeIf { it >= 0 }?.let { idx ->
                        val rd = nuxtArr.optString(idx)
                        if (rd.length >= 4) year = rd.take(4).toIntOrNull() ?: year
                    }
                    subjectObj.optInt("cover", -1).takeIf { it >= 0 }?.let { coverIdx ->
                        val coverObj = nuxtArr.optJSONObject(coverIdx)
                        val urlIdx = coverObj?.optInt("url", -1) ?: -1
                        if (urlIdx >= 0) poster = normalizePosterUrl(nuxtArr.optString(urlIdx)) ?: poster
                    }

                    val trailerObj = subjectObj.optInt("trailer", -1).takeIf { it >= 0 }?.let { nuxtArr.optJSONObject(it) }
                    val videoAddrObj = trailerObj?.optInt("videoAddress", -1)?.takeIf { it >= 0 }?.let { nuxtArr.optJSONObject(it) }
                    trailerUrl = videoAddrObj?.optInt("url", -1)?.takeIf { it >= 0 }?.let { nuxtArr.optString(it) }?.takeIf { it.startsWith("http") }

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
                        val seasonNum = seObj.optInt("se", -1).takeIf { it >= 0 }?.let { nuxtArr.optInt(it, s + 1) } ?: (s + 1)
                        val maxEp = seObj.optInt("maxEp", -1).takeIf { it >= 0 }?.let { nuxtArr.optInt(it, 1) } ?: 1

                        if (subjectType == 2 || maxEp > 1 || seasonsArr.length() > 1) {
                            for (ep in 1..maxEp) {
                                val playData = JSONObject().apply {
                                    put("detailPath", detailPath)
                                    put("subjectId", subjectId)
                                    put("season", if (seasonNum <= 0) 1 else seasonNum)
                                    put("episode", ep)
                                    put("isSeries", true)
                                    put("dubs", dubServers)
                                }.toString()

                                episodes.add(newEpisode(playData) {
                                    this.name = "Episode $ep"
                                    this.season = if (seasonNum <= 0) 1 else seasonNum
                                    this.episode = ep
                                })
                            }
                        }
                    }
                }
            }
        }

        if (subjectId.isEmpty()) {
            val canonicalHref = document.selectFirst("link[rel=canonical]")?.attr("href") ?: ""
            subjectId = Regex("""[?&]id=(\d+)""").find(canonicalHref)?.groupValues?.get(1) ?: ""
        }

        val isSeries = subjectType == 2 || episodes.size > 1

        return if (isSeries) {
            if (episodes.isEmpty()) {
                val playData = JSONObject().apply {
                    put("detailPath", detailPath)
                    put("subjectId", subjectId)
                    put("season", 1)
                    put("episode", 1)
                    put("isSeries", true)
                    put("dubs", dubServers)
                }.toString()

                episodes.add(newEpisode(playData) {
                    this.name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                })
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
            val moviePlayData = JSONObject().apply {
                put("detailPath", detailPath)
                put("subjectId", subjectId)
                put("season", 0)
                put("episode", 0)
                put("isSeries", false)
                put("dubs", dubServers)
            }.toString()

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

    private suspend fun loadCaptions(
        streamId: String,
        languageLabel: String,
        subjectId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val seen = HashSet<String>()
        try {
            val subLink = "https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId"
            val subHeaders = buildMobileAuthHeaders(subLink)
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
        } catch (_: Exception) {}
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
        val isSeries = payload.optBoolean("isSeries", false)
        val season = if (isSeries) payload.optInt("season", 1).coerceAtLeast(1) else 0
        val episode = if (isSeries) payload.optInt("episode", 1).coerceAtLeast(1) else 0
        val dubsArr = payload.optJSONArray("dubs")

        data class ServerEntry(val label: String, val id: String, val path: String)

        val servers = mutableListOf<ServerEntry>()
        if (defaultId.isNotEmpty()) {
            servers.add(ServerEntry("Original", defaultId, defaultPath))
        }
        if (dubsArr != null && dubsArr.length() > 0) {
            for (i in 0 until dubsArr.length()) {
                val d = dubsArr.optJSONObject(i) ?: continue
                val dId = d.optString("id")
                val dPath = d.optString("path").ifEmpty { defaultPath }
                val dName = d.optString("name").ifEmpty { "Dub ${i + 1}" }
                if (dId.isNotEmpty() && servers.none { it.id == dId }) {
                    servers.add(ServerEntry(dName, dId, dPath))
                } else if (dId == defaultId && servers.isNotEmpty()) {
                    servers[0] = ServerEntry(dName, dId, dPath)
                }
            }
        }

        var anyFound = false
        val emittedUrls = mutableSetOf<String>()

        for (server in servers) {
            for (domain in WEB_DOMAINS) {
                try {
                    val playUrl = "$domain/wefeed-h5api-bff/subject/play?subjectId=${server.id}" +
                        "&se=$season&ep=$episode&detailPath=${server.path}" +
                        "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
                    val referer = "$playerUrl/spa/videoPlayPage/movies/${server.path}?id=${server.id}&type=/movie/detail&lang=en"
                    val webHeaders = mapOf(
                        "User-Agent" to WEB_USER_AGENT,
                        "Referer" to referer,
                        "Origin" to playerUrl,
                        "Accept" to "application/json",
                        "Cookie" to userCookies,
                        "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                        "x-request-lang" to "en",
                        "x-vip-restrict" to "0",
                        "x-no-high-risk-restrict" to "0"
                    )

                    val resp = app.get(playUrl, headers = webHeaders)
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
                            "Origin" to playerUrl,
                            "Referer" to "$playerUrl/",
                            "User-Agent" to WEB_USER_AGENT,
                            "Accept" to "*/*"
                        )
                        if (signCookie != null) {
                            streamHeaders[signHeaderKey] = signCookie
                            streamHeaders["Cookie"] = signCookie
                        }

                        anyFound = true
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name - ${server.label} [$kind $qualityLabel]",
                                url = streamUrl,
                                type = linkType
                            ) {
                                this.referer = "$playerUrl/"
                                this.headers = streamHeaders
                                this.quality = maxRes
                            }
                        )

                        loadCaptions(streamId, server.label, server.id, subtitleCallback)
                    }

                    // 1. DASH streams (1080p, 720p, 480p with X-MB-Token)
                    dataNode.get("dash")?.takeIf { it.isArray }?.forEach { emitStream(it, "DASH") }
                    // 2. HLS streams
                    dataNode.get("hls")?.takeIf { it.isArray }?.forEach { emitStream(it, "HLS") }
                    // 3. Direct MP4 streams (480p free stream)
                    dataNode.get("streams")?.takeIf { it.isArray }?.forEach { emitStream(it, "MP4") }

                    if (anyFound) break
                } catch (_: Exception) {
                    continue
                }
            }
        }

        return anyFound
    }
}
