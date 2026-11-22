package com.hungrysturdy

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MovieFoxCineStream {

    const val CATALOG_BASE_URL = "https://cinemeta-catalogs.strem.io"
    const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
    const val KITSU_URL = "https://anime-kitsu.strem.fun"
    const val HAGLUND_URL = "https://arm.haglund.dev/api/v2"
    const val IMAGE_PROXY = "https://wsrv.nl/?url="
    const val TORRENTIO_URL = "https://torrentio.strem.fun/limit=4"
    const val TORRENTSDB_URL = "https://torrentsdb.com/eyJsaW1pdCI6IjMiLCJkZWJyaWRvcHRpb25zIjpbIm5vZG93bmxvYWRsaW5rcyJdfQ=="
    const val VIDEASY_API = "https://api.speedracelight.com"
    const val HEXA_API = "https://theemoviedb.hexa.su"
    const val VIDZEE_API = "https://player.vidzee.wtf"
    const val VIDROCK_API = "https://vidrock.ru"
    const val KISSKH_API = "https://kisskh.nl"
    const val ANIMESALT_API = "https://animesalt.ac"
    const val MULTI_DECRYPT_API = "https://enc-dec.app/api"

    private const val CS_SCHEME = "cstream://"

    data class CineStreamCatalog(
        val id: String,
        val name: String,
        val templateUrl: String,
        val isAnime: Boolean = false
    )

    data class CineStreamPassData(
        val id: String,
        val type: String,
        val isAnime: Boolean = false,
        val title: String = "",
        val poster: String? = null,
        val rating: String? = null
    )

    val CATALOGS: List<CineStreamCatalog> = listOf(
        CineStreamCatalog("cs_top_movies", "Top Movies", "$CATALOG_BASE_URL/top/catalog/movie/top/skip=###.json"),
        CineStreamCatalog("cs_top_series", "Top Series", "$CATALOG_BASE_URL/top/catalog/series/top/skip=###.json"),
        CineStreamCatalog("cs_airing_anime", "Airing Anime", "$KITSU_URL/catalog/anime/kitsu-anime-airing/skip=###.json", isAnime = true),
        CineStreamCatalog("cs_top_anime", "Top Anime", "$KITSU_URL/catalog/anime/kitsu-anime-trending/skip=###.json", isAnime = true),
        CineStreamCatalog("cs_action_movies", "Top Action Movies", "$CATALOG_BASE_URL/top/catalog/movie/top/skip=###&genre=Action.json"),
        CineStreamCatalog("cs_action_series", "Top Action Series", "$CATALOG_BASE_URL/top/catalog/series/top/skip=###&genre=Action.json"),
        CineStreamCatalog("cs_comedy_movies", "Top Comedy Movies", "$CATALOG_BASE_URL/top/catalog/movie/top/skip=###&genre=Comedy.json"),
        CineStreamCatalog("cs_comedy_series", "Top Comedy Series", "$CATALOG_BASE_URL/top/catalog/series/top/skip=###&genre=Comedy.json"),
        CineStreamCatalog("cs_romance_movies", "Top Romance Movies", "$CATALOG_BASE_URL/top/catalog/movie/top/skip=###&genre=Romance.json"),
        CineStreamCatalog("cs_romance_series", "Top Romance Series", "$CATALOG_BASE_URL/top/catalog/series/top/skip=###&genre=Romance.json"),
        CineStreamCatalog("cs_horror_movies", "Top Horror Movies", "$CATALOG_BASE_URL/top/catalog/movie/top/skip=###&genre=Horror.json"),
        CineStreamCatalog("cs_horror_series", "Top Horror Series", "$CATALOG_BASE_URL/top/catalog/series/top/skip=###&genre=Horror.json"),
        CineStreamCatalog("cs_thriller_movies", "Top Thriller Movies", "$CATALOG_BASE_URL/top/catalog/movie/top/skip=###&genre=Thriller.json"),
        CineStreamCatalog("cs_thriller_series", "Top Thriller Series", "$CATALOG_BASE_URL/top/catalog/series/top/skip=###&genre=Thriller.json"),
        CineStreamCatalog("cs_scifi_movies", "Top Sci-Fi Movies", "$CATALOG_BASE_URL/top/catalog/movie/top/skip=###&genre=Sci-Fi.json"),
        CineStreamCatalog("cs_scifi_series", "Top Sci-Fi Series", "$CATALOG_BASE_URL/top/catalog/series/top/skip=###&genre=Sci-Fi.json"),
        CineStreamCatalog("cs_fantasy_movies", "Top Fantasy Movies", "$CATALOG_BASE_URL/top/catalog/movie/top/skip=###&genre=Fantasy.json"),
        CineStreamCatalog("cs_fantasy_series", "Top Fantasy Series", "$CATALOG_BASE_URL/top/catalog/series/top/skip=###&genre=Fantasy.json"),
        CineStreamCatalog("cs_mystery_movies", "Top Mystery Movies", "$CATALOG_BASE_URL/top/catalog/movie/top/skip=###&genre=Mystery.json"),
        CineStreamCatalog("cs_mystery_series", "Top Mystery Series", "$CATALOG_BASE_URL/top/catalog/series/top/skip=###&genre=Mystery.json"),
        CineStreamCatalog("cs_crime_movies", "Top Crime Movies", "$CATALOG_BASE_URL/top/catalog/movie/top/skip=###&genre=Crime.json"),
        CineStreamCatalog("cs_crime_series", "Top Crime Series", "$CATALOG_BASE_URL/top/catalog/series/top/skip=###&genre=Crime.json")
    )

    fun isCineStreamData(data: String): Boolean = data.startsWith(CS_SCHEME)

    fun encodePassData(
        id: String,
        type: String,
        isAnime: Boolean = false,
        title: String = "",
        poster: String? = null,
        rating: String? = null
    ): String {
        val json = JSONObject().apply {
            put("id", id)
            put("type", type)
            put("isAnime", isAnime)
            put("title", title)
            if (!poster.isNullOrBlank()) put("poster", poster)
            if (!rating.isNullOrBlank()) put("rating", rating)
        }.toString()
        return "$CS_SCHEME${Uri.encode(json)}"
    }

    fun decodePassData(raw: String): CineStreamPassData? {
        if (!raw.startsWith(CS_SCHEME)) return null
        val decoded = runCatching { Uri.decode(raw.removePrefix(CS_SCHEME)) }.getOrNull().orEmpty()
        val obj = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        val id = obj.optString("id").trim()
        if (id.isBlank()) return null
        val type = obj.optString("type").trim().ifBlank { "movie" }
        val isAnime = obj.optBoolean("isAnime", id.startsWith("kitsu:", true))
        val title = obj.optString("title").trim()
        val poster = obj.optString("poster").trim().takeIf { it.isNotBlank() && it != "null" }
        val rating = obj.optString("rating").trim().takeIf { it.isNotBlank() && it != "null" }
        return CineStreamPassData(id, type, isAnime, title, poster, rating)
    }

    private fun posterOf(raw: String?): String? {
        val u = raw?.trim().orEmpty()
        if (u.isBlank() || u.equals("null", ignoreCase = true)) return null
        return if (u.contains("metahub.space", true) || u.contains("kitsu.", true)) IMAGE_PROXY + u else u
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { idx ->
            arr.optString(idx)?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        }
    }

    private fun qualityOf(str: String?): Int {
        if (str.isNullOrBlank()) return Qualities.Unknown.value
        Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val low = str.lowercase()
        return when {
            low.contains("8k") -> 4320
            low.contains("4k") || low.contains("uhd") -> 2160
            low.contains("2k") -> 1440
            else -> Qualities.Unknown.value
        }
    }

    private fun decryptVidrockPayload(payload: String): String? = runCatching {
        val keyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
        val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var b64 = payload.replace("-", "+").replace("_", "/")
        while (b64.length % 4 != 0) b64 += "="
        val data = Base64.decode(b64, Base64.DEFAULT)
        val nonce = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    private suspend fun <A, B> Iterable<A>.csSafeMap(concurrency: Int = 6, block: suspend (A) -> B?): List<B> =
        supervisorScope {
            val sem = Semaphore(concurrency)
            map { item ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        try {
                            block(item)
                        } catch (e: CancellationException) {
                            if (!isActive) throw e
                            null
                        } catch (e: Exception) {
                            Log.d("MovieFoxCineStream", "csSafeMap error: ${e.message}")
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

    suspend fun getHomeCategories(
        provider: MovieFoxProvider,
        ctx: Context,
        page: Int,
        timeoutSec: Long = 10L
    ): List<HomePageList> {
        if (!MovieFoxSettingsDialog.isServerEnabled(ctx, "cinestream")) return emptyList()

        val perPage = 8
        val startIdx = ((page - 1).coerceAtLeast(0)) * perPage
        val slice = if (startIdx < CATALOGS.size) {
            CATALOGS.subList(startIdx, minOf(startIdx + perPage, CATALOGS.size))
        } else {
            emptyList()
        }
        if (slice.isEmpty()) return emptyList()

        val skip = ((page - 1).coerceAtLeast(0)) * 20
        val headers = mapOf(
            "User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA,
            "Accept" to "application/json, text/plain, */*"
        )

        return supervisorScope {
            slice.map { cat ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(timeoutSec * 1000L) {
                        try {
                            val url = cat.templateUrl.replace("###", skip.toString())
                            val text = MovieFoxInAppCfBypass.get(url, headers, timeoutSec = timeoutSec)
                            val root = JSONObject(text)
                            val metas: JSONArray = root.optJSONArray("metas") ?: return@withTimeoutOrNull null
                            val items = ArrayList<SearchResponse>(metas.length())
                            for (i in 0 until metas.length()) {
                                val m = metas.optJSONObject(i) ?: continue
                                val id = m.optString("id").trim()
                                val aliases = m.optJSONArray("aliases")
                                val firstAlias = aliases?.optString(0)?.trim().orEmpty()
                                val title = firstAlias.ifBlank { m.optString("name").trim() }
                                if (id.isBlank() || title.isBlank()) continue
                                val rawType = m.optString("type").trim().ifBlank { if (cat.isAnime) "anime" else "movie" }
                                val isTv = cat.isAnime || rawType.equals("series", true) || rawType.equals("tv", true) || rawType.equals("anime", true)
                                val tvType = when {
                                    cat.isAnime || rawType.equals("anime", true) -> TvType.Anime
                                    isTv -> TvType.TvSeries
                                    else -> TvType.Movie
                                }
                                val poster = posterOf(m.optString("poster"))
                                val rating = m.optString("imdbRating").takeIf { it.isNotBlank() && it != "null" }
                                val encoded = encodePassData(id, rawType, cat.isAnime || tvType == TvType.Anime, title, poster, rating)
                                if (isTv) {
                                    items.add(
                                        provider.newTvSeriesSearchResponse(title, encoded, tvType) {
                                            this.posterUrl = poster
                                            this.score = Score.from10(rating)
                                        }
                                    )
                                } else {
                                    items.add(
                                        provider.newMovieSearchResponse(title, encoded, tvType) {
                                            this.posterUrl = poster
                                            this.score = Score.from10(rating)
                                        }
                                    )
                                }
                            }
                            if (items.isEmpty()) null else HomePageList("🎬 ${cat.name}", items)
                        } catch (e: Exception) {
                            Log.d("MovieFoxCineStream", "Catalog ${cat.name} failed: ${e.message}")
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    suspend fun search(provider: MovieFoxProvider, query: String): List<SearchResponse> {
        val q = Uri.encode(query.trim())
        if (q.isBlank()) return emptyList()
        val endpoints = listOf(
            "$CINEMETA_URL/catalog/movie/top/search=$q.json" to false,
            "$CINEMETA_URL/catalog/series/top/search=$q.json" to false,
            "$KITSU_URL/catalog/anime/kitsu-anime-airing/search=$q.json" to true
        )
        val headers = mapOf("User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA, "Accept" to "application/json")

        val lists = supervisorScope {
            endpoints.map { (url, isAnime) ->
                async(Dispatchers.IO) {
                    runCatching {
                        val text = MovieFoxInAppCfBypass.get(url, headers, timeoutSec = 8L)
                        val metas = JSONObject(text).optJSONArray("metas") ?: return@runCatching emptyList<SearchResponse>()
                        (0 until metas.length()).mapNotNull { idx ->
                            val m = metas.optJSONObject(idx) ?: return@mapNotNull null
                            val id = m.optString("id").trim()
                            val aliases = m.optJSONArray("aliases")
                            val title = aliases?.optString(0)?.trim().orEmpty().ifBlank { m.optString("name").trim() }
                            if (id.isBlank() || title.isBlank()) return@mapNotNull null
                            val rawType = m.optString("type").trim().ifBlank { if (isAnime) "anime" else "movie" }
                            val poster = if (id.startsWith("tt")) posterOf("https://images.metahub.space/poster/medium/$id/img")
                            else posterOf(m.optString("poster"))
                            val rating = m.optString("imdbRating").takeIf { it.isNotBlank() && it != "null" }
                            val encoded = encodePassData(id, rawType, isAnime, title, poster, rating)
                            provider.newMovieSearchResponse(title, encoded) {
                                this.posterUrl = poster
                                this.score = Score.from10(rating)
                            }
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        return buildList {
            for (i in 0 until maxLen) {
                for (l in lists) {
                    if (i < l.size) add(l[i])
                }
            }
        }
    }

    suspend fun load(provider: MovieFoxProvider, rawUrl: String, timeoutSec: Long): LoadResponse? {
        val pass = decodePassData(rawUrl) ?: return null
        val isKitsu = pass.id.contains("kitsu", true) || pass.id.contains("mal", true) || pass.isAnime
        val metaBase = if (isKitsu) KITSU_URL else CINEMETA_URL
        val kitsuId = if (pass.id.startsWith("kitsu:", true)) pass.id.substringAfter(":") else null
        val cleanMetaType = if (pass.type.equals("anime", true)) "anime" else if (pass.type.equals("series", true) || pass.type.equals("tv", true)) "series" else "movie"
        val encodedId = if (isKitsu) pass.id.replace(":", "%3A") else Uri.encode(pass.id)

        val headers = mapOf("User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA, "Accept" to "application/json")
        val metaText = MovieFoxInAppCfBypass.get("$metaBase/meta/$cleanMetaType/$encodedId.json", headers, timeoutSec = timeoutSec)
        val meta = runCatching { JSONObject(metaText).optJSONObject("meta") }.getOrNull()

        var resolvedImdb = meta?.optString("imdb_id")?.trim().orEmpty().ifBlank {
            if (pass.id.startsWith("tt")) pass.id else ""
        }
        var resolvedTmdb = meta?.optInt("moviedb_id", 0)?.takeIf { it > 0 }

        if (isKitsu && !kitsuId.isNullOrBlank()) {
            runCatching {
                val extText = MovieFoxInAppCfBypass.get("$HAGLUND_URL/ids?source=kitsu&id=$kitsuId", headers, timeoutSec = 6L)
                val extObj = JSONObject(extText)
                val extImdb = extObj.optString("imdb").trim()
                val extTmdb = extObj.optInt("themoviedb", 0).takeIf { it > 0 }
                if (extImdb.startsWith("tt")) resolvedImdb = extImdb
                if (extTmdb != null) resolvedTmdb = extTmdb
            }
        }

        val aliases = parseStringList(meta?.optJSONArray("aliases"))
        val title = aliases.firstOrNull()
            ?: meta?.optString("name")?.trim().orEmpty().ifBlank { pass.title }.ifBlank { "CineStream Title" }
        val posterUrl = posterOf(meta?.optString("poster")) ?: pass.poster
        val backgroundUrl = posterOf(meta?.optString("background"))
        val logoUrl = posterOf(meta?.optString("logo"))
        val plot = meta?.optString("description")?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        val genres = parseStringList(meta?.optJSONArray("genre")).ifEmpty { parseStringList(meta?.optJSONArray("genres")) }
        val ratingStr = meta?.optString("imdbRating")?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: pass.rating
        val yearStr = meta?.optString("year")?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            ?: meta?.optString("releaseInfo")?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        val yearInt = yearStr?.substringBefore("-")?.substringBefore("–")?.trim()?.toIntOrNull()

        val isMovieType = !isKitsu && cleanMetaType == "movie"
        val tvType = when {
            isKitsu && isMovieType -> TvType.AnimeMovie
            isKitsu -> TvType.Anime
            isMovieType -> TvType.Movie
            else -> TvType.TvSeries
        }

        if (isMovieType) {
            val dataPayload = JSONObject().apply {
                put("url", rawUrl)
                put("title", title)
                put("imdbId", resolvedImdb)
                if (resolvedTmdb != null) put("tmdbId", resolvedTmdb.toString())
                put("isTv", false)
                put("season", 1)
                put("episode", 1)
            }.toString()
            return provider.newMovieLoadResponse(title, rawUrl, tvType, dataPayload) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
                this.logoUrl = logoUrl
                this.plot = plot
                this.tags = genres
                this.score = Score.from10(ratingStr)
                this.year = yearInt
            }
        }

        val videos = meta?.optJSONArray("videos")
        val episodes = mutableListOf<Episode>()
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val sNum = v.optInt("season", 1)
                val eNum = v.optInt("episode", 0)
                if (sNum <= 0 || eNum <= 0) continue
                val epName = v.optString("name").trim().ifBlank { v.optString("title").trim() }.ifBlank { "Episode $eNum" }
                val epThumb = posterOf(v.optString("thumbnail"))
                val epDesc = v.optString("overview").trim().takeIf { it.isNotBlank() && it != "null" }
                val epData = JSONObject().apply {
                    put("url", rawUrl)
                    put("title", title)
                    put("imdbId", resolvedImdb)
                    if (resolvedTmdb != null) put("tmdbId", resolvedTmdb.toString())
                    put("isTv", true)
                    put("season", sNum)
                    put("episode", eNum)
                }.toString()
                episodes.add(
                    provider.newEpisode(epData) {
                        this.name = epName
                        this.season = sNum
                        this.episode = eNum
                        this.posterUrl = epThumb
                        this.description = epDesc
                    }
                )
            }
        }
        if (episodes.isEmpty()) {
            val epData = JSONObject().apply {
                put("url", rawUrl)
                put("title", title)
                put("imdbId", resolvedImdb)
                if (resolvedTmdb != null) put("tmdbId", resolvedTmdb.toString())
                put("isTv", true)
                put("season", 1)
                put("episode", 1)
            }.toString()
            episodes.add(
                provider.newEpisode(epData) {
                    this.name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                }
            )
        }

        return provider.newTvSeriesLoadResponse(title, rawUrl, tvType, episodes) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.logoUrl = logoUrl
            this.plot = plot
            this.tags = genres
            this.score = Score.from10(ratingStr)
            this.year = yearInt
        }
    }

    private suspend fun fetchExtraCineStreamServers(
        title: String,
        tmdbInt: Int?,
        imdbId: String,
        isTv: Boolean,
        season: Int,
        episode: Int,
        timeoutSec: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        onEmitted: () -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA,
            "Accept" to "*/*",
            "Origin" to "https://player.videasy.to",
            "Referer" to "https://player.videasy.to/"
        )

        if (tmdbInt != null && title.isNotBlank()) {
            val seedText = MovieFoxInAppCfBypass.get("$VIDEASY_API/seed?mediaId=$tmdbInt", headers, timeoutSec = 6L)
            val seed = runCatching { JSONObject(seedText).optString("seed") }.getOrDefault("")
            if (seed.isNotBlank()) {
                val encTitle = URLEncoder.encode(URLEncoder.encode(title, "UTF-8").replace("+", "%20"), "UTF-8").replace("+", "%20")
                listOf("myflixerzupcloud", "downloader2", "m4uhd", "hdmovie", "cdn", "superflix").csSafeMap(5) { srv ->
                    val u = if (!isTv) {
                        "$VIDEASY_API/$srv/sources-with-title?title=$encTitle&mediaType=movie&tmdbId=$tmdbInt&imdbId=$imdbId&enc=2&seed=$seed"
                    } else {
                        "$VIDEASY_API/$srv/sources-with-title?title=$encTitle&mediaType=tv&tmdbId=$tmdbInt&episodeId=$episode&seasonId=$season&imdbId=$imdbId&enc=2&seed=$seed"
                    }
                    val encData = MovieFoxInAppCfBypass.get(u, headers, timeoutSec = minOf(timeoutSec, 8L))
                    if (encData.isBlank()) return@csSafeMap null
                    val decRaw = MovieFoxInAppCfBypass.post(
                        "$MULTI_DECRYPT_API/dec-videasy",
                        json = mapOf("text" to encData, "id" to tmdbInt, "seed" to seed),
                        timeoutSec = minOf(timeoutSec, 8L)
                    )
                    val resObj = runCatching { JSONObject(decRaw).optJSONObject("result") }.getOrNull() ?: return@csSafeMap null
                    val arr = resObj.optJSONArray("sources") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val q = o.optString("quality", "HD")
                        val sUrl = o.optString("url").trim()
                        if (!sUrl.startsWith("http") || mfIsBrokenContainerUrl(sUrl)) continue
                        val t = if (sUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else if (sUrl.contains(".mp4") || sUrl.contains(".mkv")) ExtractorLinkType.VIDEO else INFER_TYPE
                        callback(
                            newExtractorLink("CineStream • Videasy[${srv.replaceFirstChar { it.uppercase() }}]", "CineStream • Videasy[${srv.replaceFirstChar { it.uppercase() }}] $q", sUrl, t) {
                                this.quality = qualityOf(q)
                                this.headers = headers
                            }
                        )
                        onEmitted()
                    }
                    resObj.optJSONArray("subtitles")?.let { subs ->
                        for (k in 0 until subs.length()) {
                            val sub = subs.optJSONObject(k) ?: continue
                            val subUrl = sub.optString("url").trim()
                            if (subUrl.startsWith("http")) subtitleCallback(newSubtitleFile(sub.optString("language", "English"), subUrl))
                        }
                    }
                    Unit
                }
            }
        }

        if (tmdbInt != null) {
            runCatching {
                val vrType = if (isTv) "tv" else "movie"
                val vrQuery = if (isTv) "${tmdbInt}_${season}_${episode}" else "$tmdbInt"
                val vrHeaders = mapOf("Origin" to VIDROCK_API, "Referer" to "$VIDROCK_API/")
                val vrRaw = MovieFoxInAppCfBypass.get("$VIDROCK_API/api/$vrType/$vrQuery/", vrHeaders, timeoutSec = minOf(timeoutSec, 8L))
                val vrObj = runCatching { JSONObject(vrRaw) }.getOrNull()
                vrObj?.keys()?.forEach { srv ->
                    val encStr = vrObj.optJSONObject(srv)?.optString("url").orEmpty()
                    if (encStr.isBlank() || encStr == "error" || encStr == "null") return@forEach
                    val dec = decryptVidrockPayload(encStr) ?: return@forEach
                    if (dec.startsWith("http") && !mfIsBrokenContainerUrl(dec)) {
                        callback(
                            newExtractorLink("CineStream • Vidrock[$srv]", "CineStream • Vidrock[$srv]", dec, if (dec.contains("m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                                this.headers = vrHeaders
                            }
                        )
                        onEmitted()
                    }
                }
            }
        }
    }

    suspend fun loadStreams(
        provider: MovieFoxProvider,
        title: String,
        imdbId: String,
        tmdbId: String?,
        isTv: Boolean,
        season: Int,
        episode: Int,
        timeoutSec: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ctx = CloudStreamApp.context
        if (ctx != null && !MovieFoxSettingsDialog.isServerEnabled(ctx, "cinestream")) return false

        val headers = mapOf(
            "User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA,
            "Accept" to "application/json, text/plain, */*"
        )
        val ids = buildList {
            if (imdbId.startsWith("tt")) add(imdbId)
            if (!tmdbId.isNullOrBlank()) add("tmdb:$tmdbId")
        }.distinct()
        if (ids.isEmpty() && title.isBlank()) return false

        val stremioType = if (isTv) "series" else "movie"
        val defaultAddons = listOf(
            TORRENTIO_URL,
            CINEMETA_URL
        )
        val customManifestBases = ctx?.let { c ->
            MovieFoxSettingsDialog.getNuvioManifests(c)
                .filter { it.enabled }
                .mapNotNull { m ->
                    val clean = m.manifestUrl.substringBefore("?")
                    if (clean.endsWith("/manifest.json", true)) clean.dropLast("/manifest.json".length)
                    else clean.trimEnd('/').takeIf { it.startsWith("http") }
                }
        }.orEmpty()

        val addonBases = (customManifestBases + defaultAddons).distinct()
        val gate = Semaphore(6)
        var emitted = false
        val lock = Any()

        supervisorScope {
            val extraJob = async(Dispatchers.IO) {
                withTimeoutOrNull(timeoutSec * 1000L) {
                    fetchExtraCineStreamServers(
                        title = title,
                        tmdbInt = tmdbId?.toIntOrNull(),
                        imdbId = imdbId,
                        isTv = isTv,
                        season = season,
                        episode = episode,
                        timeoutSec = timeoutSec,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                        onEmitted = { synchronized(lock) { emitted = true } }
                    )
                }
            }

            val addonJobs = addonBases.map { base ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        withTimeoutOrNull(timeoutSec * 1000L) {
                            for (rawId in ids) {
                                val sid = if (isTv) "$rawId:$season:$episode" else rawId
                                val url = "$base/stream/$stremioType/${Uri.encode(sid, ":")}.json"
                                val text = try {
                                    MovieFoxInAppCfBypass.get(url, headers, timeoutSec = timeoutSec)
                                } catch (_: Exception) {
                                    ""
                                }
                                val arr = runCatching { JSONObject(text).optJSONArray("streams") }.getOrNull() ?: continue
                                for (i in 0 until arr.length()) {
                                    val obj = arr.optJSONObject(i) ?: continue
                                    val sUrl = obj.optString("url").trim()
                                    val infoHash = obj.optString("infoHash").trim()
                                    val name = obj.optString("name").trim().ifBlank { "CineStream" }
                                    val desc = obj.optString("title").lines().firstOrNull()?.trim().orEmpty()
                                    val label = if (desc.isBlank()) "CineStream • $name" else "CineStream • $name ($desc)"
                                    val q = Regex("""(\d{3,4})[pP]""").find("$name $desc")?.groupValues?.getOrNull(1)?.toIntOrNull()
                                        ?: Qualities.Unknown.value

                                    if (sUrl.startsWith("http")) {
                                        val isM3u8 = sUrl.contains(".m3u8", true)
                                        callback(
                                            newExtractorLink(
                                                source = "CineStream",
                                                name = label,
                                                url = sUrl,
                                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.quality = q
                                                this.headers = mapOf("User-Agent" to MovieFoxSettingsDialog.CF_BYPASS_UA, "Referer" to "$base/")
                                            }
                                        )
                                        synchronized(lock) { emitted = true }
                                    } else if (infoHash.isNotBlank()) {
                                        val fileIdx = obj.optInt("fileIdx", 0)
                                        val dn = URLEncoder.encode(title.ifBlank { name }, "UTF-8")
                                        val magnet = "magnet:?xt=urn:btih:$infoHash&dn=$dn&index=$fileIdx"
                                        callback(
                                            newExtractorLink(
                                                source = "CineStream",
                                                name = "$label [Magnet]",
                                                url = magnet,
                                                type = ExtractorLinkType.MAGNET
                                            ) {
                                                this.quality = q
                                            }
                                        )
                                        synchronized(lock) { emitted = true }
                                    }

                                    obj.optJSONArray("subtitles")?.let { subs ->
                                        for (k in 0 until subs.length()) {
                                            val sub = subs.optJSONObject(k) ?: continue
                                            val subUrl = sub.optString("url").trim()
                                            val lang = sub.optString("lang").trim().ifBlank { "English" }
                                            if (subUrl.startsWith("http")) {
                                                subtitleCallback(newSubtitleFile(lang, subUrl))
                                            }
                                        }
                                    }
                                }
                                if (emitted) break
                            }
                        }
                    }
                }
            }
            (listOf(extraJob) + addonJobs).awaitAll()
        }
        return emitted
    }
}

