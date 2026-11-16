
package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

class ToonPlayProvider : MainAPI() {

    override var mainUrl = "https://toonplay.in"
    override var name = "ToonPlay"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "anime" to "Anime",
        "latest" to "Latest Episodes & Releases",
        "series" to "Most Popular TV Series",
        "movies" to "Blockbuster Anime Movies",
        "cartoons" to "Cartoons",
        "lang:hin" to "Language: Hindi",
        "lang:tel" to "Language: Telugu",
        "lang:tam" to "Language: Tamil",
        "lang:mal" to "Language: Malayalam",
        "lang:kan" to "Language: Kannada",
        "lang:ben" to "Language: Bengali",
        "lang:eng" to "Language: English",
        "lang:jpn" to "Language: Japanese",
        "lang:kor" to "Language: Korean"
    )

    private fun createSlug(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
    }

    // ---------------------------------------------------------
    // RENDERED DOM
    // ---------------------------------------------------------

    private suspend fun fetchRenderedDom(url: String): Document {
        return try {
            val response = app.get(
                url,
                interceptor = WebViewResolver(
                    interceptUrl = Regex(
                        """(api|graphql|supabase|firebase|json|watch|assets|ravok|flixcloud|abyss)""",
                        RegexOption.IGNORE_CASE
                    ),
                    additionalUrls = listOf(
                        Regex(""".*""")
                    ),
                    script = """
                        new Promise((resolve) => {
                            let attempts = 0;

                            const check = setInterval(() => {
                                attempts++;

                                const player = document.querySelector(
                                    '#watchpage-active-iframe[src]'
                                );

                                const cards = document.querySelector(
                                    '#trending, h3[title]'
                                );

                                if (player || cards || attempts >= 30) {
                                    clearInterval(check);
                                    resolve();
                                }
                            }, 400);
                        });
                    """.trimIndent(),
                    timeout = 15000L
                )
            )

            Jsoup.parse(response.text, url)

        } catch (e: Exception) {
            println("TOONPLAY DOM ERROR: ${e.message}")

            try {
                app.get(url).document
            } catch (ex: Exception) {
                println("TOONPLAY FALLBACK ERROR: ${ex.message}")
                Jsoup.parse("", url)
            }
        }
    }

    // ---------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val sectionId = request.data
        val results = mutableListOf<SearchResponse>()

        if (page == 1 && !sectionId.startsWith("lang:")) {

            val doc = fetchRenderedDom(mainUrl)

            val sectionSelector = when (sectionId) {
                "trending" -> "section#trending .group"
                "latest" -> "section#latest .group"
                "series" -> "section#series .group"
                "movies" -> "section#movies .group"
                "anime" -> "section#anime .group, section .group"
                "cartoons" -> "section#cartoons .group, section .group"
                else -> "section .group"
            }

            for (card in doc.select(sectionSelector)) {
                card.toToonPlayCard()?.let {
                    results.add(it)
                }
            }
        }

        val anilistItems = fetchCatalogForToonPlay(sectionId, page)
        results.addAll(anilistItems)

        val uniqueResults = results.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            uniqueResults,
            hasNext = anilistItems.isNotEmpty()
        )
    }

    // ---------------------------------------------------------
    // CARD PARSER
    // ---------------------------------------------------------

    private fun Element.toToonPlayCard(): SearchResponse? {

        val titleEl = selectFirst("h3[title], h3") ?: return null

        val title = titleEl.attr("title")
            .ifBlank { titleEl.text() }
            .trim()

        if (title.isBlank()) return null

        val img = select("img").firstOrNull {
            val src = it.attr("src")
            src.isNotBlank() && !src.contains("placeholder.webp")
        }

        val poster = img?.let {
            it.attr("src")
                .ifBlank { it.attr("data-src") }
                .ifBlank { it.attr("data-lazy-src") }
        }?.trim() ?: ""

        val badgeText = selectFirst(
            "span:contains(MOVIE), span:contains(SERIES), span:contains(ONA), span:contains(TV_SHORT)"
        )?.text().orEmpty()

        val isMovie =
            badgeText.contains("MOVIE", true) ||
            title.contains("Movie", true)

        val slug = createSlug(title)
        val prefix = if (isMovie) "movie" else "series"

        val anilistId = Regex("""/bx?(\d+)-""")
            .find(poster)
            ?.groupValues
            ?.get(1)
            ?: ""

        val watchUrl = if (anilistId.isNotBlank()) {
            "$mainUrl/watch/$prefix-$slug?season=1&ep=1&server=options-0-hin&lang=hin&anilistid=$anilistId"
        } else {
            "$mainUrl/watch/$prefix-$slug?season=1&ep=1&server=options-0-hin&lang=hin"
        }

        val epSubText = selectFirst(
            "div.font-mono.text-indigo-300"
        )?.text().orEmpty()

        val epCount = Regex("""EP:(\d+)""")
            .find(epSubText)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

        return newAnimeSearchResponse(
            title,
            watchUrl,
            if (isMovie) TvType.AnimeMovie else TvType.Anime
        ) {
            this.posterUrl = fixUrl(poster)
            this.addDubStatus(DubStatus.Dubbed, epCount)
            this.addDubStatus(DubStatus.Subbed, epCount)
        }
    }

    // ---------------------------------------------------------
    // ANILIST CATALOG
    // ---------------------------------------------------------

    private suspend fun fetchCatalogForToonPlay(
        section: String,
        page: Int
    ): List<SearchResponse> {

        val sort = when (section) {
            "trending" -> "TRENDING_DESC"
            "latest" -> "UPDATED_AT_DESC"
            "movies" -> "POPULARITY_DESC"
            else -> "POPULARITY_DESC"
        }

        val format = when (section) {
            "movies" -> "MOVIE"
            "cartoons" -> "TV_SHORT"
            else -> "TV"
        }

        val query = """
            query {
              Page(page: $page, perPage: 30) {
                media(type: ANIME, format: $format, sort: [$sort]) {
                  id
                  title { english romaji }
                  coverImage { extraLarge large }
                  format
                  episodes
                }
              }
            }
        """.trimIndent()

        return try {

            val res = app.post(
                "https://graphql.anilist.co",
                headers = mapOf(
                    "Content-Type" to "application/json"
                ),
                json = mapOf("query" to query)
            ).text

            val root = org.json.JSONObject(res)

            val mediaArray = root.optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
                ?: return emptyList()

            val list = mutableListOf<SearchResponse>()

            for (i in 0 until mediaArray.length()) {

                val item = mediaArray.optJSONObject(i) ?: continue
                val id = item.optInt("id")

                val titleObj = item.optJSONObject("title")

                val title = titleObj
                    ?.optString("english")
                    ?.takeIf {
                        it.isNotBlank() && it != "null"
                    }
                    ?: titleObj?.optString("romaji").orEmpty()

                if (title.isBlank()) continue

                val poster = item.optJSONObject("coverImage")?.let {
                    it.optString("extraLarge")
                        .ifBlank { it.optString("large") }
                }.orEmpty()

                val isMovie = item.optString("format") == "MOVIE"

                val slug = createSlug(title)
                val prefix = if (isMovie) "movie" else "series"

                val langCode = if (section.startsWith("lang:")) {
                    section.substringAfter("lang:")
                } else {
                    "hin"
                }

                val url =
                    "$mainUrl/watch/$prefix-$slug?season=1&ep=1&server=options-0-hin&lang=$langCode&anilistid=$id"

                list.add(
                    newAnimeSearchResponse(
                        title,
                        url,
                        if (isMovie) TvType.AnimeMovie else TvType.Anime
                    ) {
                        this.posterUrl = poster
                        this.addDubStatus(true, true)
                    }
                )
            }

            list

        } catch (e: Exception) {
            println("TOONPLAY CATALOG ERROR: ${e.message}")
            emptyList()
        }
    }

    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {

        val cleanQuery = query.trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        if (cleanQuery.isBlank()) return emptyList()

        val gql = """
            query {
              Page(page: 1, perPage: 30) {
                media(search: "$cleanQuery", type: ANIME, sort: [POPULARITY_DESC]) {
                  id
                  title { english romaji }
                  coverImage { extraLarge large }
                  format
                }
              }
            }
        """.trimIndent()

        return try {

            val res = app.post(
                "https://graphql.anilist.co",
                headers = mapOf(
                    "Content-Type" to "application/json"
                ),
                json = mapOf("query" to gql)
            ).text

            val mediaArray = org.json.JSONObject(res)
                .optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
                ?: return emptyList()

            val results = mutableListOf<SearchResponse>()

            for (i in 0 until mediaArray.length()) {

                val item = mediaArray.optJSONObject(i) ?: continue
                val id = item.optInt("id")

                val titleObj = item.optJSONObject("title")

                val title = titleObj
                    ?.optString("english")
                    ?.takeIf {
                        it.isNotBlank() && it != "null"
                    }
                    ?: titleObj?.optString("romaji").orEmpty()

                if (title.isBlank()) continue

                val poster = item.optJSONObject("coverImage")?.let {
                    it.optString("extraLarge")
                        .ifBlank { it.optString("large") }
                }.orEmpty()

                val isMovie = item.optString("format") == "MOVIE"

                val slug = createSlug(title)
                val prefix = if (isMovie) "movie" else "series"

                val watchUrl =
                    "$mainUrl/watch/$prefix-$slug?season=1&ep=1&server=options-0-hin&lang=hin&anilistid=$id"

                results.add(
                    newAnimeSearchResponse(
                        title,
                        watchUrl,
                        if (isMovie) TvType.AnimeMovie else TvType.Anime
                    ) {
                        this.posterUrl = poster
                        this.addDubStatus(true, true)
                    }
                )
            }

            results

        } catch (e: Exception) {
            println("TOONPLAY SEARCH ERROR: ${e.message}")
            emptyList()
        }
    }

    // ---------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {

        val doc = fetchRenderedDom(url)

        val fallbackSlugTitle = url.substringAfter("/watch/")
            .substringBefore("?")
            .removePrefix("series-")
            .removePrefix("movie-")
            .replace("-", " ")
            .replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }

        var title =
            doc.selectFirst("h1.text-xl, h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property='og:title']")
                    ?.attr("content")
                    ?.substringBefore(" - ToonPlay")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: fallbackSlugTitle

        var poster =
            doc.selectFirst("div.aspect-\\[3\\/4\\] img")?.let {
                it.attr("src")
                    .ifBlank { it.attr("data-src") }
                    .ifBlank { it.attr("data-lazy-src") }
            }?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property='og:image']")
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                ?: ""

        var plot =
            doc.selectFirst("p.line-clamp-3, p.text-slate-300")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property='og:description']")
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                ?: "Watch $title online in HD with multi-audio on ToonPlay."

        val tags = doc.select("div.pt-1.flex.flex-wrap span")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val anilistId = Regex("""anilistid=(\d+)""")
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

        var totalEpCount = 24

        if (anilistId != null) {
            try {

                val gql = """
                    query {
                      Media(id: $anilistId) {
                        title { english romaji }
                        description
                        coverImage { extraLarge }
                        episodes
                      }
                    }
                """.trimIndent()

                val res = app.post(
                    "https://graphql.anilist.co",
                    json = mapOf("query" to gql)
                ).text

                val media = org.json.JSONObject(res)
                    .optJSONObject("data")
                    ?.optJSONObject("Media")

                if (media != null) {

                    if (title.equals("ToonPlay", true) || title.isBlank()) {

                        val engTitle = media.optJSONObject("title")
                            ?.optString("english").orEmpty()

                        val romajiTitle = media.optJSONObject("title")
                            ?.optString("romaji").orEmpty()

                        title = engTitle
                            .takeIf { it.isNotBlank() && it != "null" }
                            ?: romajiTitle.ifBlank { title }
                    }

                    if (poster.isBlank() || poster.contains("og-image.jpg")) {
                        poster = media.optJSONObject("coverImage")
                            ?.optString("extraLarge").orEmpty()
                    }

                    val desc = media.optString("description")

                    if (desc.isNotBlank() && desc != "null") {
                        plot = Jsoup.parse(desc).text()
                    }

                    val eps = media.optInt("episodes", 0)

                    if (eps > 0) {
                        totalEpCount = eps
                    }
                }

            } catch (e: Exception) {
                println("TOONPLAY ANILIST LOAD ERROR: ${e.message}")
            }
        }

        val isMovie = url.contains("/watch/movie-")
        val baseWatchUrl = url.substringBefore("?")

        val anilistParam = if (anilistId != null) {
            "&anilistid=$anilistId"
        } else {
            ""
        }

        val domEpButtons = doc.select(
            "aside button[title]:has(span:contains(EP))"
        )

        val episodes = mutableListOf<Episode>()

        if (domEpButtons.isNotEmpty()) {

            for (btn in domEpButtons) {

                val epTitle = btn.attr("title").trim()

                val epNum = btn.selectFirst("span.tabular-nums")
                    ?.text()
                    ?.trim()
                    ?.toIntOrNull()
                    ?: (episodes.size + 1)

                val epData = EpisodeData(
                    watchUrl = "$baseWatchUrl?season=1&ep=$epNum&server=options-0-hin&lang=hin$anilistParam",
                    season = 1,
                    episode = epNum,
                    anilistId = anilistId
                ).toJson()

                episodes.add(
                    newEpisode(epData) {
                        this.name = epTitle.ifBlank {
                            "Episode $epNum"
                        }
                        this.season = 1
                        this.episode = epNum
                    }
                )
            }

        } else if (!isMovie) {

            for (epNum in 1..totalEpCount) {

                val epData = EpisodeData(
                    watchUrl = "$baseWatchUrl?season=1&ep=$epNum&server=options-0-hin&lang=hin$anilistParam",
                    season = 1,
                    episode = epNum,
                    anilistId = anilistId
                ).toJson()

                episodes.add(
                    newEpisode(epData) {
                        this.name = "Episode $epNum"
                        this.season = 1
                        this.episode = epNum
                    }
                )
            }
        }

        return if (isMovie) {

            val movieData = EpisodeData(
                watchUrl = url,
                season = 1,
                episode = 1,
                anilistId = anilistId
            ).toJson()

            newMovieLoadResponse(
                title,
                url,
                TvType.AnimeMovie,
                movieData
            ) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                this.tags = tags
            }

        } else {

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.Anime,
                episodes
            ) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // ---------------------------------------------------------
    // LOAD LINKS
    // ---------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println("TOONPLAY: loadLinks started")
        println("TOONPLAY: received data = $data")

        val episodeData = tryParseJson<EpisodeData>(data)

        val targetUrl = episodeData?.watchUrl
            ?.takeIf { it.isNotBlank() }
            ?: data.trim()

        if (targetUrl.isBlank()) {
            println("TOONPLAY: target URL is blank")
            return false
        }

        println("TOONPLAY: target URL = $targetUrl")

        // Try the Hindi server variants first.
        val serverOptions = listOf(
            "options-0-hin",
            "options-1-hin",
            "options-2-hin",
            "hd-1-hin",
            "hd-2-hin",
            "options-0",
            "options-1",
            "options-2",
            "hd-1",
            "hd-2"
        )

        val attemptedPages = linkedSetOf<String>()
        val emittedUrls = hashSetOf<String>()

        var foundAny = false

        fun withServer(url: String, server: String): String {

            val withoutFragment = url.substringBefore("#")

            val fragment = url.substringAfter("#", "").let {
                if (it.isNotEmpty()) "#$it" else ""
            }

            val serverRegex = Regex("[?&]server=[^&]*")
            val match = serverRegex.find(withoutFragment)

            val updated = if (match != null) {

                val separator = match.value.substring(0, 1)

                withoutFragment.replaceRange(
                    match.range,
                    "${separator}server=$server"
                )

            } else {

                withoutFragment +
                    (if (withoutFragment.contains("?")) "&" else "?") +
                    "server=$server"
            }

            return updated + fragment
        }

        // -----------------------------------------------------
        // DECODE BASE64 PLAYER LIST
        // -----------------------------------------------------

        fun decodePlayerList(
            iframeUrl: String
        ): List<Pair<String, String>> {

            val dataValue = Regex("""[?&]data=([^&#]+)""")
                .find(iframeUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?: return emptyList()

            val decodedParameter = try {
                URLDecoder.decode(dataValue, "UTF-8")
                    .replace(" ", "+")
            } catch (_: Exception) {
                dataValue.replace(" ", "+")
            }

            val decodedJson = try {

                val bytes = try {
                    Base64.decode(
                        decodedParameter,
                        Base64.URL_SAFE or Base64.NO_WRAP
                    )
                } catch (_: Exception) {
                    Base64.decode(
                        decodedParameter,
                        Base64.DEFAULT
                    )
                }

                String(bytes, Charsets.UTF_8)

            } catch (e: Exception) {
                println("TOONPLAY BASE64 ERROR: ${e.message}")
                return emptyList()
            }

            return try {

                val array = org.json.JSONArray(decodedJson)

                buildList {

                    for (index in 0 until array.length()) {

                        val item = array.optJSONObject(index)
                            ?: continue

                        val playerUrl = item.optString("link")
                            .replace("\\/", "/")
                            .trim()

                        if (
                            playerUrl.isBlank() ||
                            playerUrl == "null"
                        ) continue

                        val language = item.optString("language")
                            .takeIf {
                                it.isNotBlank() && it != "null"
                            }
                            ?: "Multi"

                        add(playerUrl to language)
                    }
                }

            } catch (e: Exception) {
                println("TOONPLAY PLAYER JSON ERROR: ${e.message}")
                emptyList()
            }
        }

        // -----------------------------------------------------
        // RESOLVE PLAYER URL
        // -----------------------------------------------------

        suspend fun resolve(
            url: String,
            label: String,
            referer: String
        ) {

            val normalizedUrl = fixUrl(url.trim())

            if (
                normalizedUrl.isBlank() ||
                normalizedUrl in emittedUrls
            ) return

            println("TOONPLAY: resolving $normalizedUrl")

            val extracted = mutableListOf<ExtractorLink>()

            try {

                loadExtractor(
                    normalizedUrl,
                    referer,
                    subtitleCallback
                ) { link ->

                    if (link.url !in emittedUrls) {
                        emittedUrls.add(link.url)
                        extracted.add(link)
                    }
                }

            } catch (e: Exception) {
                println(
                    "TOONPLAY EXTRACTOR ERROR [$normalizedUrl]: ${e.message}"
                )
            }

            extracted.forEach { link ->

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = if (label.isBlank()) {
                            link.name
                        } else {
                            "${link.name} - $label"
                        },
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer
                        this.quality = link.quality
                        this.headers = link.headers
                    }
                )

                foundAny = true

                println("TOONPLAY: link found = ${link.url}")
            }
        }

        // -----------------------------------------------------
        // TRY ORIGINAL SERVER FIRST, THEN FALLBACKS
        // -----------------------------------------------------

        val pagesToTry = mutableListOf(targetUrl)

        for (server in serverOptions) {
            pagesToTry.add(withServer(targetUrl, server))
        }

        for (pageUrl in pagesToTry.distinct()) {

            if (!attemptedPages.add(pageUrl)) continue

            println("TOONPLAY: checking server page = $pageUrl")

            val doc = try {
                fetchRenderedDom(pageUrl)
            } catch (e: Exception) {
                println("TOONPLAY PAGE ERROR: ${e.message}")
                continue
            }

            // Prefer the actual active player iframe.
            val activeIframe = doc.selectFirst(
                "#watchpage-active-iframe[src]"
            )

            // Do not select arbitrary blank/ad iframes.
            val fallbackIframe = doc.select("iframe[src]")
                .firstOrNull { element ->

                    val src = element.absUrl("src")
                        .ifBlank { element.attr("src") }
                        .trim()

                    src.isNotBlank() &&
                        (
                            src.contains("ravok", true) ||
                            src.contains("flixcloud", true) ||
                            src.contains("abyss", true) ||
                            src.contains("animesalt", true)
                        )
                }

            val iframe = activeIframe ?: fallbackIframe

            val iframeSrc = iframe?.let {
                it.absUrl("src")
                    .ifBlank { it.attr("src").trim() }
            }.orEmpty()

            if (iframeSrc.isBlank()) {
                println("TOONPLAY: no active iframe on $pageUrl")
                continue
            }

            println("TOONPLAY: iframe found = $iframeSrc")

            // First handle Base64 encoded player lists.
            val playerEntries = decodePlayerList(iframeSrc)

            for ((playerUrl, language) in playerEntries) {
                resolve(
                    playerUrl,
                    language,
                    iframeSrc
                )
            }

            // Also resolve the active iframe itself.
            resolve(
                iframeSrc,
                "",
                pageUrl
            )

            if (foundAny) {
                println("TOONPLAY: links successfully extracted")
                return true
            }
        }

        println("TOONPLAY: no playable links found")

        return foundAny
    }

    // ---------------------------------------------------------
    // EPISODE DATA
    // ---------------------------------------------------------

    data class EpisodeData(
        val watchUrl: String,
        val season: Int = 1,
        val episode: Int = 1,
        val anilistId: Int? = null
    )
}
