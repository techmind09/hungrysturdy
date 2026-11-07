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

class AnimeDubIndiaProvider : MainAPI() {
    override var mainUrl = "https://www.animehubindia.com"
    override var name = "Anime Dub India"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val userAgent = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "_ga_CPLDHBTQGX=GS2.1.s1794065397\$o1\$g0\$t1794065397\$j60\$l0\$h0; _ga=GA1.1.389048499.1794065398"
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
        MainPageData("Newly Released", "$mainUrl/"),
        MainPageData("Shounen", "$mainUrl/#shounen-section"),
        MainPageData("Isekai", "$mainUrl/#isekai-section"),
        MainPageData("Supernatural", "$mainUrl/#supernatural-section")
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
        for (card in doc.select("a.anime-card, div.grid a, .anime-item")) {
            val href = card.attr("href")
            if (href.isBlank() || href.contains("#")) continue
            val url = fixUrl(href)

            val title = card.selectFirst("h3")?.text()?.trim()
                ?: card.selectFirst(".title")?.text()?.trim()
                ?: card.attr("data-title")
                ?: card.text().trim()
            if (title.isBlank()) continue

            val poster = fixUrlNull(card.selectFirst("img")?.attr("src") ?: card.selectFirst("img")?.attr("data-src"))

            items.add(
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.dubStatus = mutableSetOf(DubStatus.Dubbed)
                }
            )
        }
        return items.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = getPage(request.data)?.text ?: return newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
        val items = extractItems(html)
        return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = getPage(mainUrl)?.text ?: return emptyList()
        val allItems = extractItems(html)
        return allItems.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load page")
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Anime Dub India"

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img.object-cover")?.attr("src")
        )

        val description = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".synopsis, .description")?.text()?.trim()

        val genreList = doc.select(".genre-tag, .tags a").map { it.text().trim() }

        val episodes = mutableListOf<Episode>()
        doc.select("a[href*='drive'], a[href*='mega'], a[href*='embed'], .episodelist a, .ep-card").forEachIndexed { index, el ->
            val epUrl = el.attr("href")
            if (epUrl.isNotBlank() && !epUrl.startsWith("#")) {
                val epName = el.text().ifBlank { "Episode ${index + 1}" }
                episodes.add(
                    newEpisode(fixUrl(epUrl)) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Full Episode / Stream"
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genreList
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http")) {
            loadExtractor(data, subtitleCallback, callback)
        }
        return true
    }
}
