package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnimeDubHindiProvider : MainAPI() {
    override var mainUrl = "https://www.animedubhindi.link"
    override var name = "AnimeDubHindi"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "wp-dark-mode-device=dark; _gid=GA1.2.584315715.1794155054; pum-2506=true; _ga_QHTC5XZ817=GS2.1.s1794155053\$o1\$g1\$t1794155079\$j34\$l0\$h0; _ga=GA1.2.2086196109.1794155053"
    )

    private val cfMutexMap = ConcurrentHashMap<String, Mutex>()
    private val cfKillerMap = ConcurrentHashMap<String, CloudflareKiller>()

    private fun mutexFor(url: String): Mutex =
        cfMutexMap.getOrPut(try { Uri.parse(url).host ?: mainUrl } catch (e: Exception) { mainUrl }) { Mutex() }

    private fun killerFor(url: String): CloudflareKiller =
        cfKillerMap.getOrPut(try { Uri.parse(url).host ?: mainUrl } catch (e: Exception) { mainUrl }) { CloudflareKiller() }

    private fun isCloudflarePage(response: NiceResponse): Boolean {
        return response.code in listOf(403, 503)
    }

    override val mainPage = listOf(
        MainPageData("Latest Updates", "$mainUrl/"),
        MainPageData("Hindi Dubbed Anime", "$mainUrl/genre/hindi-dubbed/"),
        MainPageData("Anime Movies", "$mainUrl/genre/anime-movies/")
    )

    private suspend fun getPage(url: String, referer: String? = null): NiceResponse? {
        return try {
            val response = app.get(url, headers = browserHeaders, referer = referer)
            if (!isCloudflarePage(response)) {
                response
            } else {
                mutexFor(url).withLock {
                    val cfKiller = killerFor(url)
                    val retryResponse = app.get(url, headers = browserHeaders, interceptor = cfKiller, referer = referer)
                    if (isCloudflarePage(retryResponse)) {
                        cfKiller.savedCookies.clear()
                        app.get(url, headers = browserHeaders, interceptor = cfKiller, referer = referer)
                    } else {
                        retryResponse
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractItems(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()
        for (card in doc.select("article.ast-article-post, div.post-thumb-img-content, div.ast-article-inner")) {
            val a = card.selectFirst("h2.entry-title a, h1.entry-title a, a") ?: continue
            val url = fixUrlNull(a.attr("href")) ?: continue
            val title = (a.attr("title")
                ?: a.text()
                ?: card.selectFirst("img")?.attr("alt"))
                ?.trim() ?: ""
            if (title.isBlank()) continue
            
            val img = card.selectFirst("img")
            val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: ""

            val isSeries = url.contains("/episode/", true) ||
                Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\s*\d+""").containsMatchIn(title)

            val response = if (isSeries) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, url, TvType.AnimeMovie) {
                    this.posterUrl = poster
                }
            }
            items.add(response)
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) {
            request.data
        } else {
            if (request.data.endsWith("/")) {
                "${request.data}page/$page/"
            } else {
                "${request.data}/page/$page/"
            }
        }
        val html = getPage(pageUrl)?.text ?: return newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
        val items = extractItems(html)
        return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = getPage("$mainUrl/?s=${query.trim().replace(" ", "+")}")?.text ?: return emptyList()
        return extractItems(html)
    }

    override suspend fun load(url: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load page")
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown Anime"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("div.entry-content img")?.attr("src")
            ?: ""

        val plot = doc.select("div.entry-content p").joinToString("\n") { it.text() }.trim()

        val tags = doc.select(
            "span.ast-taxonomy-container a, " +
            "span.cat-links a, " +
            "span.tags-links a, " +
            "div.entry-meta span.ast-inline-meta a, " +
            "p.ast-tag-links a"
        ).mapNotNull { it.text().trim().ifBlank { null } }

        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.groupValues?.get(0)?.toIntOrNull()

        // Check if page contains episode links or list of episodes
        val episodeElements = doc.select("div.entry-content a[href*='episode'], div.entry-content a")
        val episodes = mutableListOf<Episode>()

        if (episodeElements.isNotEmpty()) {
            for ((index, a) in episodeElements.withIndex()) {
                val href = fixUrlNull(a.attr("href")) ?: continue
                if (!href.contains(mainUrl)) continue
                val label = a.text().trim().ifBlank { "Episode ${index + 1}" }
                val epNum = Regex("""(?i)\bepisode\s*[-:]?\s*(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                
                episodes.add(
                    newEpisode(href) {
                        this.name = label
                        this.season = 1
                        this.episode = epNum
                    }
                )
            }
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        // Default to Movie/Single Episode view if no explicit episode list found
        return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val html = getPage(data)?.text ?: return false
            val doc = Jsoup.parse(html)

            // Extract iframes (embedded video players)
            for (iframe in doc.select("iframe[src]")) {
                val src = iframe.attr("src") ?: continue
                if (src.isNotBlank()) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }

            // Extract direct video source links (.mp4, .mxl, .m3u8)
            for (el in doc.select("source[src], a[href$='.mp4'], a[href$='.mkv'], a[href$='.m3u8']")) {
                val videoUrl = el.attr("href").ifBlank { el.attr("src") }
                if (videoUrl.isNotBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = data
                        }
                    )
                    found = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }
}
