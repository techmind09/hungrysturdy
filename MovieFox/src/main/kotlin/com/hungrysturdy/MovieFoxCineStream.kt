package com.hungrysturdy

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object MovieFoxCineStream {

    const val CATALOG_BASE_URL = "https://cinemeta-catalogs.strem.io"
    const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
    const val KITSU_URL = "https://anime-kitsu.strem.fun"
    const val HAGLUND_URL = "https://arm.haglund.dev/api/v2"
    const val IMAGE_PROXY = "https://wsrv.nl/?url="

    data class CineStreamCatalog(
        val id: String,
        val name: String,
        val templateUrl: String,
        val isAnime: Boolean = false
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

    private fun posterOf(raw: String?): String? {
        val u = raw?.trim().orEmpty()
        if (u.isBlank() || u.equals("null", ignoreCase = true)) return null
        return if (u.contains("metahub.space", true) || u.contains("kitsu.", true)) IMAGE_PROXY + u else u
    }

    private fun buildEncodedData(
        provider: MovieFoxProvider,
        id: String,
        title: String,
        isTv: Boolean,
        season: Int = 1,
        episode: Int = 1
    ): String {
        val tmdbMatch = Regex("(?:tmdb:|movie-|series-|tv-)(\\d+)").find(id)?.groupValues?.get(1)
        val imdbMatch = Regex("(tt\\d{6,10})").find(id)?.groupValues?.get(1)
        val url = if (tmdbMatch != null) {
            if (isTv) "${provider.mainUrl}/tv/series-$tmdbMatch" else "${provider.mainUrl}/movie/movie-$tmdbMatch"
        } else {
            "$CINEMETA_URL/meta/${if (isTv) "series" else "movie"}/${Uri.encode(id)}.json"
        }
        return JSONObject().apply {
            put("url", url)
            put("title", title)
            if (tmdbMatch != null) put("tmdbId", tmdbMatch)
            if (imdbMatch != null) put("imdbId", imdbMatch)
            put("isTv", isTv)
            put("season", season)
            put("episode", episode)
        }.toString()
    }

    suspend fun getHomeCategories(
        provider: MovieFoxProvider,
        ctx: Context,
        page: Int,
        timeoutSec: Long = 10L
    ): List<HomePageList> {
        if (!MovieFoxSettingsDialog.isServerEnabled(ctx, "cinestream")) return emptyList()

        val slice = when {
            page <= 1 -> CATALOGS.take(8)
            page == 2 -> CATALOGS.drop(8).take(7)
            else -> CATALOGS.drop(15).take(7)
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
                            val text = app.get(url, headers = headers, timeout = timeoutSec).text
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
                                val mType = m.optString("type").trim()
                                val isTv = cat.isAnime || mType.equals("series", true) || mType.equals("tv", true)
                                val tvType = when {
                                    cat.isAnime -> TvType.Anime
                                    isTv -> TvType.TvSeries
                                    else -> TvType.Movie
                                }
                                val poster = posterOf(m.optString("poster"))
                                val rating = m.optString("imdbRating").takeIf { it.isNotBlank() && it != "null" }
                                val encoded = buildEncodedData(provider, id, title, isTv)
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
                            if (items.isEmpty()) null else HomePageList("🎬 CineStream • ${cat.name}", items)
                        } catch (e: Exception) {
                            Log.d("MovieFoxCineStream", "Catalog ${cat.name} failed: ${e.message}")
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
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
            "https://torrentio.strem.fun/limit=4",
            "https://v3-cinemeta.strem.io"
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
            addonBases.map { base ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        withTimeoutOrNull(timeoutSec * 1000L) {
                            for (rawId in ids) {
                                val sid = if (isTv) "$rawId:$season:$episode" else rawId
                                val url = "$base/stream/$stremioType/${Uri.encode(sid, ":")}.json"
                                val text = try {
                                    app.get(url, headers = headers, timeout = timeoutSec).text
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
            }.awaitAll()
        }
        return emitted
    }
}

