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

class PrmoviesProvider : MainAPI() {
    override var mainUrl = "https://prmovies.kids"
    override var name = "Prmovies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "_ga=GA1.1.1629448397.1794059276; domain-alert=1; fpestid=qKEJFcR_c3RLFa6Kn--eeRQW2dShUUkgk5lB1Hx76DaGCwYwnn5wDmCy7qWmMUlFPnD5Zw; _ga_7BWGJ9MXSS=GS2.1.s1794059275\$o1\$g1\$t1794059680\$j60\$l0\$h0"
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
        MainPageData("Latest", "$mainUrl/"),
        MainPageData("Trending", "$mainUrl/genre/top-rated/"),
        MainPageData("New Movies", "$mainUrl/release-year/2026/"),
        MainPageData("Hollywood Dubbed", "$mainUrl/genre/hollywood-dubbed/"),
        MainPageData("South Dubbed", "$mainUrl/genre/south-special/"),
        MainPageData("Unofficial Hindi Dubbed", "https://hhdmovies.forum/genre/unofficial-dubbed/"),
        MainPageData("Bollywood (2026)", "$mainUrl/account/?ptype=post&tax_category%5B0%5D=bollywood&tax_release-year=2026&wpas=1"),
        MainPageData("Bollywood (2025)", "$mainUrl/account/?ptype=post&tax_category%5B0%5D=bollywood&tax_release-year=2025&wpas=1"),
        MainPageData("Bollywood (2024)", "$mainUrl/account/?ptype=post&tax_category%5B0%5D=bollywood&tax_release-year=2024&wpas=1"),
        MainPageData("Hollywood (2026)", "$mainUrl/account/?ptype=post&tax_category%5B0%5D=hollywood&tax_release-year=2026&wpas=1"),
        MainPageData("Hollywood (2025)", "$mainUrl/account/?ptype=post&tax_category%5B0%5D=hollywood&tax_release-year=2025&wpas=1"),
        MainPageData("Hollywood (2024)", "$mainUrl/account/?ptype=post&tax_category%5B0%5D=hollywood&tax_release-year=2024&wpas=1"),
        MainPageData("English Series", "$mainUrl/series/"),
        MainPageData("Netflix", "$mainUrl/director/netflix/"),
        MainPageData("Amazon Prime", "$mainUrl/director/amazon-prime/"),
        MainPageData("Altbalaji", "$mainUrl/director/altbalaji/"),
        MainPageData("Zee5", "$mainUrl/director/zee5/"),
        MainPageData("Voot", "$mainUrl/director/voot-originals/"),
        MainPageData("Mx Player", "$mainUrl/director/mx-player/"),
        MainPageData("Hotstar", "$mainUrl/director/hotstar/"),
        MainPageData("Viu", "$mainUrl/director/viu-originals/"),
        MainPageData("Sony Liv", "$mainUrl/director/sonyliv-original/")
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
        for (card in doc.select(".ml-item")) {
            val a = card.selectFirst("a.ml-mask") ?: continue
            val url = fixUrlNull(a.attr("href")) ?: continue
            val title = (a.attr("oldtitle")
                ?: a.attr("title")
                ?: card.selectFirst(".mli-info h2")?.text()
                ?: card.selectFirst("img.mli-thumb")?.attr("alt"))
                ?.trim() ?: ""
            if (title.isBlank()) continue
            val thumb = card.selectFirst("img.mli-thumb")
            val poster = thumb?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: thumb?.attr("src")?.takeIf { it.isNotBlank() }
                ?: ""
            val isSeries = url.contains("/series/", true) ||
                Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\d{1,2}\b""").containsMatchIn(title)
            val response = if (isSeries) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
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

        val title = doc.selectFirst("h3[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("div.mvic-thumb")?.attr("style")?.let { style ->
                Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
            }
            ?: ""

        val plot = doc.selectFirst("p.f-desc")?.text()?.trim() ?: ""

        val year = doc.select("a[href*=/release-year/]").firstOrNull()?.text()?.trim()
            ?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.groupValues?.get(0)?.toIntOrNull()

        val tags = doc.select(".mvici-left a[href*=/genre/]").mapNotNull { it.text().trim().ifBlank { null } }

        val duration = doc.selectFirst(".mvici-right span[itemprop=duration]")?.text()?.trim()
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        if (url.contains("/series/", true)) {
            val episodes = mutableListOf<Episode>()
            for (tvseason in doc.select("#seasons .tvseason")) {
                val seasonTitle = tvseason.selectFirst(".les-title strong")?.text() ?: "Season 1"
                val season = Regex("""(?i)\bseason\s*(\d+)""").find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                for (a in tvseason.select(".les-content a[href]")) {
                    val href = fixUrlNull(a.attr("href")) ?: continue
                    val label = a.text().trim()
                    val epNum = Regex("""(?i)\bepisode\s*[-:]?\s*(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(
                        newEpisode(href) {
                            this.name = label
                            this.season = season
                            this.episode = epNum ?: 0
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
                    this.duration = duration
                }
            }
        }

        val isSeries = Regex("""(?i)\bseason\s*\d|\bepisode\s*\d|\bs\d{1,2}\b""").containsMatchIn(title)
        if (isSeries) {
            val episodes = listOf(
                newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                }
            )
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = duration
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
            val pageUrl = data.substringBefore("#").substringBefore("?")
            val html = getPage(pageUrl)?.text ?: return false
            val doc = Jsoup.parse(html)

            if (pageUrl.contains("/episode/", true)) {
                found = resolveEpisodePage(doc, pageUrl, callback)
            } else {
                found = resolvePagePlayer(doc, pageUrl, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }

    private suspend fun resolvePagePlayer(doc: Element, pageUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        val tabLabels = mutableMapOf<String, String>()
        for (li in doc.select(".player_nav .idTabs li")) {
            val anchor = li.selectFirst("a[href^=#]") ?: continue
            val tabId = anchor.attr("href").removePrefix("#")
            val label = li.selectFirst(".les-content")?.text()?.trim()
            if (tabId.isNotBlank() && !label.isNullOrBlank()) tabLabels[tabId] = label
        }

        for (iframe in doc.select("#player2 iframe[src], #content-embed iframe[src], div.movieplay iframe[src]")) {
            val src = iframe.attr("src") ?: continue
            if (src.contains("speedostream", true) && src.contains("embed-")) {
                val tabId = iframe.parents().firstOrNull { it.id().startsWith("tab") }?.id()
                val label = tabLabels[tabId] ?: "Prmovies"
                if (resolveSpeedostream(src, label, callback)) found = true
            }
        }

        if (!found) {
            for (a in doc.select("#list-downloads a[href*=speedostream]")) {
                val href = a.attr("href")
                if (href.isBlank() || href.contains("embed-")) continue
                val label = a.selectFirst(".serv_tit")?.text()?.ifBlank { null } ?: "Prmovies"
                if (resolveSpeedostream(href, "$label • Download", callback)) found = true
            }
        }
        return found
    }

    private suspend fun resolveEpisodePage(doc: Element, pageUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        for (iframe in doc.select("#player2 iframe[src], div.movieplay iframe[src]")) {
            val src = iframe.attr("src") ?: continue
            if (src.contains("vidsrc", true)) {
                if (resolveVidsrc(src, pageUrl, callback)) found = true
            } else if (src.contains("speedostream", true) && src.contains("embed-")) {
                if (resolveSpeedostream(src, "Prmovies", callback)) found = true
            }
        }
        return found
    }

    private suspend fun resolveSpeedostream(embedUrl: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val clean = fixUrlNull(embedUrl) ?: return false
            val host = try { Uri.parse(clean).host ?: "speedostream1.com" } catch (e: Exception) { "speedostream1.com" }
            val r = getPage(clean, referer = "https://$host/") ?: return false
            if (!r.isSuccessful) return false
            val text = r.text
            if (text.contains("Embeds disabled", true)) return false

            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""sources\s*:\s*\[\s*\{?\s*file\s*:\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
            if (m3u8.isNullOrBlank()) return false

            callback(
                newExtractorLink(
                    source = name,
                    name = label.take(40).ifBlank { name },
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://$host/"
                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to "https://$host/")
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun resolveVidsrc(embedUrl: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val clean = fixUrlNull(embedUrl) ?: return false
            val r1 = getPage(clean, referer = referer) ?: return false
            val d1 = Jsoup.parse(r1.text)

            var target: String? = null
            d1.select("iframe[src]").forEach {
                val s = it.attr("src")
                if (s.isNotBlank()) { target = if (s.startsWith("http")) s else fixUrlNull(s); return@forEach }
            }
            val playerUrl = target ?: return false
            if (!playerUrl.startsWith("http")) return false

            val r2 = getPage(playerUrl, referer = clean) ?: return false
            val text = r2.text

            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""file["']?\s*["']?["']?([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""https:[^"' ]+\.m3u8[^"' ]*""").find(text)?.groupValues?.get(0)
            if (m3u8.isNullOrBlank()) return false

            callback(
                newExtractorLink(
                    source = name,
                    name = "Prmovies",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = playerUrl
                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to playerUrl)
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
