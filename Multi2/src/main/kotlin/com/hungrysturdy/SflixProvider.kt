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
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

class SflixProvider : MainAPI() {

    companion object {
        const val SPORT_API = "https://h5-sport-api.aoneroom.com/wefeed-h5api-bff"
        const val SPORT_URL = "https://www.sportslive.wine/"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

        val WEB_DOMAINS = listOf(
            "https://sflix.film",
            "https://123moviesfree.club",
            "https://movie-box.co",
            "https://movieboxonline.net",
            "https://h5-api.aoneroom.com",
            "https://netnaija.film",
            "https://movieboxhd.net"
        )

        val HOST_POOL = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com"
        )

        private const val TOKEN_BOOTSTRAP_URL =
            "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"

        private const val SECRET_DEFAULT_B64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
        private const val SECRET_ALT_B64 = "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="

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
            val exp = decodeJwtExpiry(token)
            return exp > (System.currentTimeMillis() / 1000) + 3600
        }
    }

    override var mainUrl = HOST_POOL[0]
    private val playerUrl = "https://mzfi.me"
    override var name = "Sflix"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val userCookies = "sflix_i18n_lang=en; _ga=GA1.1.622965780.1794742147; sflix_token=%22eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjE5NjY4NTU1NTcyNzgwNjIyNzYsImF0cCI6MywiZXh0IjoiMTc5NDc0MjE0NyIsImV4cCI6MTgwMjUxODE0NywiaWF0IjoxNzk0NzQxODQ3fQ.8aOIckQOSKyISQdl85SAJvuMlhXfWodHmcPOIvlg0pM%22; vrk4n8fqhwc3jzy7pbsmgt6dx5lha2u9=01a1b98c-7aec-7761-b581-b09b7730a724_2; dom3ic8zudi28v8lr6fgphwffqoz0j6c=01a1b98c-7aec-7761-b581-b09b7730a724; imprCounter_53d6f18bc2561bc4f4ecc19cefe348bf_expiry=Invalid Date; imprCounter_53d6f18bc2561bc4f4ecc19cefe348bf=3; _ga_CJSG3GJM85=GS2.1.s1794753588\$o3\$g1\$t1794754299\$j60\$l0\$h0; sb_main_29ea333f714080c847539fb7690ac422=1; pp_main_505b167260767d732d39389d9b6ebed9=1; sb_count_29ea333f714080c847539fb7690ac422=1"

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

    private val random = SecureRandom()
    private val deviceId: String = generateDeviceId()
    private val PREF_TOKEN_KEY = "sflix_app_bearer_token"
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper =
        com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

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
            val bodyHash: String
            val bodyLength: String
            if (body != null) {
                val bodyBytes = body.toByteArray()
                bodyHash = md5(if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes)
                bodyLength = bodyBytes.size.toString()
            } else {
                bodyHash = ""
                bodyLength = ""
            }
            method.uppercase(Locale.ROOT) + "\n" + (accept ?: "") + "\n" + (contentType ?: "") + "\n" +
                bodyLength + "\n" + timestamp + "\n" + bodyHash + "\n" + canonicalUrl
        } catch (e: Exception) {
            method.uppercase(Locale.ROOT) + "\n" + (accept ?: "") + "\n" + (contentType ?: "") + "\n\n" +
                timestamp + "\n\n"
        }
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        useAltKey: Boolean = false,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) SECRET_ALT_B64 else SECRET_DEFAULT_B64
        val secretBytes = Base64.getDecoder().decode(
            String(Base64.getDecoder().decode(secret))
        )
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray()))
        return "$timestamp|2|$signature"
    }

    private fun saveToken(token: String) {
        if (!token.isBlank() && isTokenValid(token)) {
            bearerToken = token
        }
    }

    private suspend fun fetchAnonymousToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            if (isTokenValid(bearerToken)) return bearerToken!!
        }
        return try {
            val ts = System.currentTimeMillis()
            val xClientToken = generateXClientToken(ts)
            val xTrSig = generateXTrSignature("GET", "application/json", "application/json", TOKEN_BOOTSTRAP_URL, null, false, ts)
            val headers = mapOf(
                "user-agent" to modernUserAgent,
                "accept" to "application/json",
                "content-type" to "application/json",
                "connection" to "keep-alive",
                "x-client-token" to xClientToken,
                "x-tr-signature" to xTrSig,
                "x-client-info" to modernClientInfo,
                "x-client-status" to "0"
            )
            val resp = app.get(TOKEN_BOOTSTRAP_URL, headers = headers)
            val xUser = resp.headers["x-user"]
            val token = xUser?.let { runCatching { JSONObject(it).optString("token") }.getOrNull() }
            if (!token.isNullOrBlank()) {
                saveToken(token)
                token
            } else {
                bearerToken ?: ""
            }
        } catch (e: Exception) {
            bearerToken ?: ""
        }
    }

    private fun persistTokenFromXUser(xUserHeader: String?) {
        if (xUserHeader.isNullOrBlank()) return
        try {
            val token = JSONObject(xUserHeader).optString("token")
            if (token.isNotBlank()) saveToken(token)
        } catch (_: Exception) {}
    }

    private suspend fun buildAuthHeaders(
        method: String,
        url: String,
        contentType: String = "application/json",
        accept: String = "application/json",
        body: String? = null,
        useToken: Boolean = true
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        val xClientToken = generateXClientToken(ts)
        val xTrSig = generateXTrSignature(method, accept, contentType, url, body, false, ts)
        val headers = mutableMapOf(
            "user-agent" to modernUserAgent,
            "accept" to accept,
            "content-type" to contentType,
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSig,
            "x-client-info" to modernClientInfo,
            "x-client-status" to "0",
            "Cookie" to userCookies
        )
        if (useToken) {
            val token = fetchAnonymousToken()
            if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    private fun extractPolicyResource(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null
        val edgeCacheMatch = Regex("Edge-Cache-Cookie=urlprefix=([^:;\\s]+)").find(signCookie)
        if (edgeCacheMatch != null) {
            val urlPrefixB64 = edgeCacheMatch.groupValues[1]
            return try {
                val std = urlPrefixB64.replace('_', '/').replace('-', '+')
                val padded = std + "=".repeat((4 - (std.length % 4)) % 4)
                String(Base64.getUrlDecoder().decode(padded)).trimEnd('/') + "/index.mpd"
            } catch (e: Exception) {
                null
            }
        }
        val match = Regex("CloudFront-Policy=([^;]+)").find(signCookie) ?: return null
        val policyRaw = match.groupValues[1]
        val candidates = listOf(
            policyRaw.replace('-', '+').replace('~', '/').replace('_', '='),
            policyRaw.replace('-', '+').replace('_', '/')
        )
        for (cfB64 in candidates) {
            try {
                val rem = cfB64.length % 4
                val padded = if (rem > 0) cfB64 + "=".repeat(4 - rem) else cfB64
                val decoded = String(Base64.getDecoder().decode(padded))
                val root = runCatching { mapper.readTree(decoded) }.getOrNull()
                val resource = root?.get("Statement")?.get(0)?.get("Resource")?.asText()
                if (resource != null) {
                    val trimmed = resource.trimEnd('*', '/')
                    return if (trimmed.endsWith(".mpd", true)) trimmed else "$trimmed/index.mpd"
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
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
                            if (!coverUrl.isNullOrEmpty()) {
                                map[path] = coverUrl
                            }
                        }
                    }
                }
            }
        }
        return map
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page"
        }

        val document = fetchDocument(targetUrl)
        val nuxtPosters = extractNuxtPosterMap(document)

        if (request.data.trimEnd('/') == mainUrl) {
            val homeLists = document.select("section.comp-box").mapNotNull { section ->
                val sectionTitle = section.selectFirst("h2.action-title")?.text()?.trim()
                    ?: return@mapNotNull null
                val items = section.select("a.video-item")
                    .mapNotNull { it.toSearchResult(nuxtPosters) }
                    .distinctBy { it.url }
                if (items.isEmpty()) null else HomePageList(sectionTitle, items)
            }
            if (homeLists.isNotEmpty()) {
                return newHomePageResponse(homeLists, hasNext = false)
            }
        }

        var items = document.select("a.video-item, a[href*='/detail/']")
            .mapNotNull { it.toSearchResult(nuxtPosters) }
            .distinctBy { it.url }

        if (items.isEmpty()) {
            items = extractItemsFromNuxt(document)
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(posterMap: Map<String, String> = emptyMap()): SearchResponse? {
        val rawHref = if (this.tagName() == "a") {
            this.attr("href")
        } else {
            this.selectFirst("a")?.attr("href") ?: ""
        }
        if (rawHref.isEmpty() || !rawHref.contains("/detail/")) return null

        val title = this.selectFirst("h3.text, .video-name h3, .video-name, h3")?.text()?.trim()
            ?.ifEmpty {
                this.attr("title")
                    .removePrefix("go to ")
                    .removeSuffix(" detail page")
                    .trim()
            } ?: return null
        if (title.isEmpty()) return null

        val detailSlug = rawHref.substringAfter("/detail/").substringBefore("?")
        val href = fixUrl(rawHref)

        val img = this.selectFirst("img.banner, .video-item-cover img, img")
        val rawImgUrl = img?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
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

                val subjectId = obj.optInt("subjectId", -1).takeIf { it >= 0 }
                    ?.let { nuxtArr.optString(it) } ?: ""

                var posterUrl: String? = null
                val coverIdx = obj.optInt("cover", -1)
                if (coverIdx >= 0) {
                    val coverObj = nuxtArr.optJSONObject(coverIdx)
                    val urlIdx = coverObj?.optInt("url", -1) ?: -1
                    if (urlIdx >= 0) {
                        posterUrl = normalizePosterUrl(nuxtArr.optString(urlIdx))
                    }
                }

                val itemUrl = if (subjectId.isNotEmpty()) {
                    "$mainUrl/detail/$detailPath?id=$subjectId"
                } else {
                    "$mainUrl/detail/$detailPath"
                }

                if (seenUrls.add(itemUrl)) {
                    results.add(
                        newMovieSearchResponse(title, itemUrl, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            this.posterHeaders = defaultPosterHeaders
                        }
                    )
                }
            }
        }
        return results
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search-result?keyword=$encoded"
        val document = fetchDocument(searchUrl)
        val nuxtPosters = extractNuxtPosterMap(document)

        var items = document.select("a.video-item, a[href*='/detail/']")
            .mapNotNull { it.toSearchResult(nuxtPosters) }
            .distinctBy { it.url }

        if (items.isEmpty()) {
            items = extractItemsFromNuxt(document)
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = fetchDocument(url)
        val nuxtPosters = extractNuxtPosterMap(document)

        var title = document.selectFirst("h1.content-title")?.text()?.trim()
            ?: document.selectFirst("h2.title")?.text()?.trim()
            ?: return null

        var poster = document.selectFirst("div.info-image img")?.let {
            normalizePosterUrl(it.attr("data-src").ifEmpty { it.attr("src") })
        } ?: normalizePosterUrl(document.selectFirst("meta[property=og:image]")?.attr("content"))

        var description = document.selectFirst("p.content-desc")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        var ratingStr = document.selectFirst("div.rate-num")?.text()?.let {
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
        }.distinct()

        val recommendations = document.select("section.more-like-warp a.video-item")
            .mapNotNull { it.toSearchResult(nuxtPosters) }

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

                val subjectObj = rootObj?.optInt("subject", -1)?.takeIf { it >= 0 }
                    ?.let { nuxtArr.optJSONObject(it) }

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
                        if (urlIdx >= 0) {
                            poster = normalizePosterUrl(nuxtArr.optString(urlIdx)) ?: poster
                        }
                    }

                    val trailerObj = subjectObj.optInt("trailer", -1).takeIf { it >= 0 }
                        ?.let { nuxtArr.optJSONObject(it) }
                    val videoAddrObj = trailerObj?.optInt("videoAddress", -1)?.takeIf { it >= 0 }
                        ?.let { nuxtArr.optJSONObject(it) }
                    trailerUrl = videoAddrObj?.optInt("url", -1)?.takeIf { it >= 0 }
                        ?.let { nuxtArr.optString(it) }
                        ?.takeIf { it.startsWith("http") }

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

                val resourceObj = rootObj?.optInt("resource", -1)?.takeIf { it >= 0 }
                    ?.let { nuxtArr.optJSONObject(it) }
                val seasonsArr = resourceObj?.optInt("seasons", -1)?.takeIf { it >= 0 }
                    ?.let { nuxtArr.optJSONArray(it) }

                if (seasonsArr != null) {
                    for (s in 0 until seasonsArr.length()) {
                        val seObj = nuxtArr.optJSONObject(seasonsArr.optInt(seasonsArr.optInt(s, -1), -1)) ?: nuxtArr.optJSONObject(seasonsArr.optInt(s, -1)) ?: continue
                        val seasonNum = seObj.optInt("se", -1).takeIf { it >= 0 }
                            ?.let { nuxtArr.optInt(it, s + 1) } ?: (s + 1)
                        val maxEp = seObj.optInt("maxEp", -1).takeIf { it >= 0 }
                            ?.let { nuxtArr.optInt(it, 1) } ?: 1

                        if (subjectType == 2 || maxEp > 1 || seasonsArr.length() > 1) {
                            for (ep in 1..maxEp) {
                                val playData = buildEpisodePayload(
                                    detailPath = detailPath,
                                    subjectId = subjectId,
                                    season = if (seasonNum <= 0) 1 else seasonNum,
                                    episode = ep,
                                    isSeries = true,
                                    trailerUrl = trailerUrl,
                                    dubs = dubServers
                                )
                                episodes.add(
                                    newEpisode(playData) {
                                        this.name = "Episode $ep"
                                        this.season = if (seasonNum <= 0) 1 else seasonNum
                                        this.episode = ep
                                    }
                                )
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

    private data class CapturedStream(
        val url: String,
        val headers: Map<String, String>
    )

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
        if (defaultId.isNotEmpty()) {
            servers.add(ServerEntry("Primary", defaultId, defaultPath))
        }
        if (dubsArr != null && dubsArr.length() > 0) {
            for (i in 0 until dubsArr.length()) {
                val d = dubsArr.optJSONObject(i) ?: continue
                val dId = d.optString("id")
                val dPath = d.optString("path").ifEmpty { defaultPath }
                val dName = d.optString("name").ifEmpty { "Server ${i + 1}" }
                if (dId.isNotEmpty() && servers.none { it.id == dId }) {
                    servers.add(ServerEntry(dName, dId, dPath))
                } else if (dId == defaultId && servers.isNotEmpty() && servers[0].label == "Primary") {
                    servers[0] = ServerEntry(dName, dId, dPath)
                }
            }
        }

        var anyFound = false
        val emittedUrls = mutableSetOf<String>()
        val emittedSubs = mutableSetOf<String>()

        for (server in servers.take(2)) {
            val targetPlayerUrl = if (isSeries) {
                "$playerUrl/spa/videoPlayPage/movies/${server.path}?id=${server.id}&se=$season&ep=$episode&type=/movie/detail&lang=en"
            } else {
                "$playerUrl/spa/videoPlayPage/movies/${server.path}?id=${server.id}&type=/movie/detail&lang=en"
            }

            val (streams, subs) = resolveStreamsViaWebView(
                pageUrl = targetPlayerUrl,
                referer = "$mainUrl/",
                excludeTrailer = trailerUrl
            )

            streams.forEach { captured ->
                val streamUrl = captured.url
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
                        streamUrl.contains("360") -> "360p"
                        streamUrl.contains(".mpd", true) -> "DASH"
                        else -> "HD"
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - ${server.label} ($qualityTag)",
                            url = streamUrl,
                            type = streamType
                        ) {
                            this.referer = captured.headers["Referer"] ?: "$playerUrl/"
                            this.headers = captured.headers
                            this.quality = getQualityFromName(qualityTag)
                        }
                    )
                }
            }

            subs.forEach { subUrl ->
                if (emittedSubs.add(subUrl)) {
                    val langLabel = when {
                        subUrl.contains("-hi", true) || subUrl.contains("hindi", true) -> "Hindi"
                        subUrl.contains("-es", true) -> "Spanish"
                        subUrl.contains("-fr", true) -> "French"
                        subUrl.contains("-ar", true) -> "Arabic"
                        subUrl.contains("-id", true) -> "Indonesian"
                        subUrl.contains("-pt", true) -> "Portuguese"
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

            if (anyFound) break
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
    ): Pair<List<CapturedStream>, List<String>> {
        val context = getAppContext() ?: return Pair(emptyList(), emptyList())
        val foundStreams = CopyOnWriteArrayList<CapturedStream>()
        val seenStreamUrls = ConcurrentHashMap.newKeySet<String>()
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
            "5gvci.com",
            "nap5k.com",
            "richinfo.co",
            "ifunny-promo",
            "/other/"
        )

        withTimeoutOrNull(15_000L) {
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

                        val cookieManager = CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(wv, true)
                            setCookie(mainUrl, userCookies)
                            setCookie(playerUrl, userCookies)
                        }

                        val webViewUa = wv.settings.userAgentString.ifBlank { WEB_USER_AGENT }

                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null

                                if (adDomains.any { reqUrl.contains(it, ignoreCase = true) }) {
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }

                                if (reqUrl.contains(".vtt", ignoreCase = true) ||
                                    reqUrl.contains(".srt", ignoreCase = true)
                                ) {
                                    foundSubs.add(reqUrl)
                                }

                                val isVideoStream = reqUrl.contains(".mpd", ignoreCase = true) ||
                                    reqUrl.contains(".m3u8", ignoreCase = true) ||
                                    (reqUrl.contains(".mp4", ignoreCase = true) && !reqUrl.contains("loading", true))

                                val isTrailer = excludeTrailer.isNotEmpty() &&
                                    reqUrl.substringBefore("?") == excludeTrailer.substringBefore("?")

                                if (isVideoStream && !isTrailer && !reqUrl.startsWith("blob:")) {
                                    if (seenStreamUrls.add(reqUrl)) {
                                        val capturedHeaders = mutableMapOf<String, String>()
                                        request.requestHeaders?.forEach { (k, v) ->
                                            if (!k.equals("Range", ignoreCase = true)) {
                                                capturedHeaders[k] = v
                                            }
                                        }
                                        capturedHeaders.putIfAbsent("Origin", playerUrl)
                                        capturedHeaders.putIfAbsent("Referer", "$playerUrl/")
                                        capturedHeaders.putIfAbsent("User-Agent", webViewUa)

                                        val cookies = cookieManager.getCookie(reqUrl)
                                            ?: cookieManager.getCookie(playerUrl)
                                            ?: userCookies
                                        if (!cookies.isNullOrBlank()) {
                                            capturedHeaders["Cookie"] = cookies
                                        }

                                        foundStreams.add(CapturedStream(reqUrl, capturedHeaders))
                                        mainHandler.postDelayed({ finish() }, 1000L)
                                    }

                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }

                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        wv.loadUrl(pageUrl, mapOf("Referer" to referer, "Cookie" to userCookies))
                    }.onFailure {
                        finish()
                    }
                }
            }
        }

        return Pair(foundStreams.toList(), foundSubs.toList())
    }
}
