package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

private val katCfKiller by lazy { CloudflareKiller() }

private val katBrowserHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Cookie" to "_ga=GA1.1.583239637.1794483658; _ga_0TNV9GL2DB=GS2.1.s1794492289\$o2\$g1\$t1794492315\$j34\$l0\$h0; xla=s4t"
)

class Filemoon : Filesim() {
    override val name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
}

// =========================================================================
// 1. HubCloud Extractors & Mirrors
// =========================================================================
open class KatHubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.pw"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        KatServerLogic.extractHubCloudServers(url, referer, callback)
    }
}

class HubCloudDrive : KatHubCloudExtractor() {
    override val mainUrl = "https://hubcloud.dad"
}

class HubCloudClub : KatHubCloudExtractor() {
    override val mainUrl = "https://hubcloud.club"
}

class HubCloudOne : KatHubCloudExtractor() {
    override val mainUrl = "https://hubcloud.one"
}

class GamerXYTExtractor : KatHubCloudExtractor() {
    override val name = "GamerXYT"
    override val mainUrl = "https://gamerxyt.com"
}

// =========================================================================
// 2. GDFlix Extractors & Mirrors
// =========================================================================
open class KatGDFlixExtractor : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.vip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        KatServerLogic.extractGdFlixServers(url, referer, subtitleCallback, callback)
    }
}

class GDFlixCfd : KatGDFlixExtractor() {
    override val mainUrl = "https://gdflix.cfd"
}

class GDFlixdad : KatGDFlixExtractor() {
    override val mainUrl = "https://gdflix.dad"
}

class GDFlixNew9 : KatGDFlixExtractor() {
    override val mainUrl = "https://new9.gdflix.io"
}

class GDFlixNew10 : KatGDFlixExtractor() {
    override val mainUrl = "https://new10.gdflix.io"
}

// =========================================================================
// 3. Standalone Extractors
// =========================================================================
class KatPixelDrainExtractor : ExtractorApi() {
    override val name = "PixelServer"
    override val mainUrl = "https://pixeldrain.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        KatServerLogic.extractPixelDrain(url, getQualityFromName(referer.orEmpty()), callback)
    }
}

class KatHBPlayExtractor : ExtractorApi() {
    override val name = "HBPlay"
    override val mainUrl = "https://hbplay.pages.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        KatServerLogic.extractHbPlay(url, getQualityFromName(referer.orEmpty()), callback)
    }
}

class KatFuckingFastExtractor : ExtractorApi() {
    override val name = "BuzzServer"
    override val mainUrl = "https://fuckingfast.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        KatServerLogic.extractFuckingFast(url, getQualityFromName(referer.orEmpty()), callback)
    }
}

class KatGoFlixExtractor : ExtractorApi() {
    override val name = "GoFlix"
    override val mainUrl = "https://goflix.sbs"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        KatServerLogic.extractGoFlixMirror(url, getQualityFromName(referer.orEmpty()), subtitleCallback, callback)
    }
}

// =========================================================================
// Core Server Logic Object
// =========================================================================
object KatServerLogic {

    private fun cleanUrl(raw: String): String {
        return raw.replace("&amp;", "&").replace("\\/", "/").trim()
    }

    suspend fun extractHubCloudServers(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanedInputUrl = cleanUrl(url)
            val response = app.get(cleanedInputUrl, headers = katBrowserHeaders, referer = referer, interceptor = katCfKiller)
            val doc = response.document
            val htmlText = response.text
            val currentUrl = response.url.ifEmpty { cleanedInputUrl }

            val headerText = "${doc.title()} ${doc.selectFirst("div.card-header, h5, h6")?.text().orEmpty()} ${referer.orEmpty()}"
            val detectedQuality = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(headerText)?.groupValues?.get(1)
                ?.let { getQualityFromName(it) } ?: Qualities.Unknown.value

            val gamerLinkRaw = if (currentUrl.contains("hubcloud.php")) {
                currentUrl
            } else {
                doc.selectFirst("a#download, a[href*='hubcloud.php']")?.attr("href")
                    ?: Regex("""(?:href|url)\s*=\s*['"](https?://[^'"]*hubcloud\.php[^'"]*)['"]""")
                        .find(htmlText)?.groupValues?.get(1)
            }

            val gamerDoc = if (!gamerLinkRaw.isNullOrBlank()) {
                val finalGamerUrl = cleanUrl(gamerLinkRaw)
                app.get(finalGamerUrl, headers = katBrowserHeaders, referer = currentUrl, interceptor = katCfKiller).document
            } else {
                doc
            }

            val gamerRef = cleanUrl(gamerLinkRaw ?: currentUrl)

            gamerDoc.select("a#fsl, a:contains(FSL Server), a[href*='r2.cloudflarestorage.com']").forEach { a ->
                val fslUrl = cleanUrl(a.attr("href"))
                if (fslUrl.startsWith("http")) {
                    val q = if (detectedQuality != Qualities.Unknown.value) detectedQuality else getQualityFromName(fslUrl)
                    callback.invoke(
                        newExtractorLink("HubCloud", "HubCloud [FSL Server]", fslUrl, ExtractorLinkType.VIDEO) {
                            this.referer = ""
                            this.quality = q
                        }
                    )
                }
            }

            gamerDoc.select("a[href*='hbplay.pages.dev']").forEach { a ->
                extractHbPlay(cleanUrl(a.attr("href")), detectedQuality, callback)
            }

            gamerDoc.select("a#pxl-1, a[href*='pixeldrain']").forEach { a ->
                extractPixelDrain(cleanUrl(a.attr("href")), detectedQuality, callback)
            }

            gamerDoc.select("a[href*='pixel.hubcloud'], a:contains(10Gbps)").forEach { a ->
                val tenUrl = cleanUrl(a.attr("href"))
                if (tenUrl.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink("HubCloud", "HubCloud [Server : 10Gbps]", tenUrl, ExtractorLinkType.VIDEO) {
                            this.referer = gamerRef
                            this.quality = detectedQuality
                        }
                    )
                }
            }

            gamerDoc.select("a[href*='fuckingfast.net'], a:contains(Buzz Server)").forEach { a ->
                val buzzUrl = cleanUrl(a.attr("href"))
                if (buzzUrl.startsWith("http")) {
                    extractFuckingFast(buzzUrl, detectedQuality, callback)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun extractGdFlixServers(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanedUrl = cleanUrl(url)
            val response = app.get(cleanedUrl, headers = katBrowserHeaders, referer = referer, interceptor = katCfKiller)
            val doc = response.document
            val htmlText = response.text
            val finalUrl = response.url.ifEmpty { cleanedUrl }

            val baseOrigin = try {
                val u = URI(finalUrl)
                "${u.scheme}://${u.host}"
            } catch (_: Exception) {
                "https://new10.gdflix.io"
            }

            val pageInfo = "${doc.title()} ${doc.text()} ${referer.orEmpty()}"
            val detectedQuality = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(pageInfo)?.groupValues?.get(1)
                ?.let { getQualityFromName(it) } ?: Qualities.Unknown.value

            val visitedSubpages = mutableSetOf<String>()

            doc.select("a[href]").forEach { a ->
                val rawHref = cleanUrl(a.attr("href"))
                if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript") || rawHref.contains("/login")) return@forEach

                val absHref = if (rawHref.startsWith("http")) rawHref else "$baseOrigin${if (rawHref.startsWith("/")) "" else "/"}$rawHref"
                val btnText = a.text().trim()

                when {
                    rawHref.contains("/cloud/") || btnText.contains("FAST CLOUD", true) || btnText.contains("ZIPDISK", true) -> {
                        if (visitedSubpages.add(absHref)) {
                            extractGdFlixCloudSubpage(absHref, finalUrl, detectedQuality, callback)
                        }
                    }

                    absHref.contains("instadl.rest") || absHref.contains("busycdn") || btnText.contains("INSTANT DL", true) -> {
                        if (visitedSubpages.add(absHref)) {
                            resolveInstantDl(absHref, finalUrl, detectedQuality, callback)
                        }
                    }

                    absHref.contains("workers.dev") || absHref.contains("cloudpaglu.site") || absHref.contains("r2.cloudflarestorage.com") -> {
                        callback.invoke(
                            newExtractorLink("GDFlix", "GDFlix [Direct CDN]", absHref, ExtractorLinkType.VIDEO) {
                                this.referer = ""
                                this.quality = detectedQuality
                            }
                        )
                    }

                    absHref.contains("pixeldrain") -> extractPixelDrain(absHref, detectedQuality, callback)
                    absHref.contains("hbplay.pages.dev") -> extractHbPlay(absHref, detectedQuality, callback)
                    absHref.contains("fuckingfast.net") -> extractFuckingFast(absHref, detectedQuality, callback)
                    absHref.contains("goflix.sbs") || absHref.contains("gofile") -> {
                        extractGoFlixMirror(absHref, detectedQuality, subtitleCallback, callback)
                    }
                }
            }

            val driveBotMatch = Regex("""href=["'](https?://drivebot\.[a-z]+/download\?id=[^"']+)["']""")
                .find(htmlText)?.groupValues?.get(1)
            if (!driveBotMatch.isNullOrBlank()) {
                val dbUrl = cleanUrl(driveBotMatch)
                try {
                    val dbDoc = app.get(dbUrl, headers = katBrowserHeaders, referer = finalUrl).document
                    dbDoc.select("a.btn, a[href*='download']").forEach { btn ->
                        val dl = cleanUrl(btn.attr("href"))
                        if (dl.startsWith("http") && !dl.contains("drivebot")) {
                            callback.invoke(
                                newExtractorLink("GDFlix", "GDFlix [DriveBot]", dl, ExtractorLinkType.VIDEO) {
                                    this.referer = dbUrl
                                    this.quality = detectedQuality
                                }
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun resolveInstantDl(
        instantUrl: String,
        refererUrl: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(instantUrl, headers = katBrowserHeaders, referer = refererUrl, interceptor = katCfKiller)
            val finalPageUrl = resp.url.ifEmpty { instantUrl }
            val doc = resp.document
            val html = resp.text

            if (finalPageUrl.contains("googleusercontent.com") || finalPageUrl.contains("r2.cloudflarestorage.com") || finalPageUrl.contains("workers.dev")) {
                callback.invoke(
                    newExtractorLink("GDFlix", "GDFlix [Instant DL 10Gbps]", finalPageUrl, ExtractorLinkType.VIDEO) {
                        this.referer = ""
                        this.quality = quality
                    }
                )
                return
            }

            val directGoogleUrl = doc.selectFirst("a[href*='googleusercontent.com'], a:contains(Download Here)")?.attr("href")
                ?: Regex("""https?://video-downloads\.googleusercontent\.com/[^\s"'<>\\]+""").find(html)?.value
                ?: Regex("""https?://[^\s"'<>\\]*googleusercontent\.com/[^\s"'<>\\]+""").find(html)?.value

            if (!directGoogleUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink("GDFlix", "GDFlix [Instant DL 10Gbps]", cleanUrl(directGoogleUrl), ExtractorLinkType.VIDEO) {
                        this.referer = ""
                        this.quality = quality
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun extractGdFlixCloudSubpage(
        cloudUrl: String,
        refererUrl: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            var currentUrl = cloudUrl
            var resp = app.get(currentUrl, headers = katBrowserHeaders, referer = refererUrl, interceptor = katCfKiller)
            var respText = resp.text.trim()

            if (respText.startsWith("{") || respText.startsWith("[")) {
                repeat(4) {
                    if (respText.contains("In Queue") || respText.contains("queue") || respText.contains("false")) {
                        kotlinx.coroutines.delay(2000)
                        try {
                            resp = app.get(currentUrl, headers = katBrowserHeaders, referer = refererUrl, interceptor = katCfKiller)
                            respText = resp.text.trim()
                        } catch (_: Exception) {}
                    }
                }
                val jsonUrlRegex = Regex("https?://[^\\s\"'<>\\s]+").findAll(respText)
                for (m in jsonUrlRegex) {
                    val foundUrl = cleanUrl(m.value)
                    if (foundUrl.startsWith("http") && !foundUrl.contains("gdflix") && !foundUrl.contains("t.me")) {
                        callback.invoke(
                            newExtractorLink("GDFlix", "GDFlix [Cloud Task Direct]", foundUrl, ExtractorLinkType.VIDEO) {
                                this.referer = ""
                                this.quality = quality
                            }
                        )
                        return
                    }
                }
            }

            val doc = resp.document
            val html = resp.text
            val emitted = mutableSetOf<String>()

            doc.select("a[href*='workers.dev'], a:contains(CLOUD RESUME DOWNLOAD)").forEach { a ->
                val workersUrl = cleanUrl(a.attr("href"))
                if (workersUrl.startsWith("http") && !workersUrl.contains("gdflix") && emitted.add(workersUrl)) {
                    callback.invoke(
                        newExtractorLink("GDFlix", "GDFlix [Cloud Resume - Workers]", workersUrl, ExtractorLinkType.VIDEO) {
                            this.referer = ""
                            this.quality = quality
                        }
                    )
                }
            }

            if (emitted.isEmpty()) {
                Regex("""https?://[^\s"'<>\\]*workers\.dev/[^\s"'<>\\]+""").findAll(html).forEach { m ->
                    val wUrl = cleanUrl(m.value)
                    if (emitted.add(wUrl)) {
                        callback.invoke(
                            newExtractorLink("GDFlix", "GDFlix [Cloud Resume - Workers]", wUrl, ExtractorLinkType.VIDEO) {
                                this.referer = ""
                                this.quality = quality
                            }
                        )
                    }
                }
            }

            doc.select("a[href*='cloudpaglu'], a:contains(GENERATE QUICK LINK)").forEach { a ->
                val quickHref = cleanUrl(a.attr("href"))
                if (quickHref.startsWith("http") && !quickHref.contains("gdflix/cloud")) {
                    try {
                        val quickResp = app.get(quickHref, headers = katBrowserHeaders, referer = cloudUrl, allowRedirects = true)
                        val finalQuickUrl = cleanUrl(quickResp.url.ifEmpty { quickHref })
                        if (finalQuickUrl.startsWith("http") && emitted.add(finalQuickUrl)) {
                            callback.invoke(
                                newExtractorLink("GDFlix", "GDFlix [Quick Link]", finalQuickUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = ""
                                    this.quality = quality
                                }
                            )
                        }
                    } catch (_: Exception) {
                        if (emitted.add(quickHref)) {
                            callback.invoke(
                                newExtractorLink("GDFlix", "GDFlix [Quick Link]", quickHref, ExtractorLinkType.VIDEO) {
                                    this.referer = cloudUrl
                                    this.quality = quality
                                }
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun extractPixelDrain(url: String, quality: Int, callback: (ExtractorLink) -> Unit) {
        val cleaned = cleanUrl(url)
        val fileId = cleaned.substringAfter("/u/").substringAfter("/file/").substringBefore("?").substringBefore("/")
        if (fileId.isNotBlank()) {
            val host = if (cleaned.contains("pixeldrain.dev")) "https://pixeldrain.dev" else "https://pixeldrain.com"
            callback.invoke(
                newExtractorLink("PixelServer", "PixelServer [Direct]", "$host/api/file/$fileId?download", ExtractorLinkType.VIDEO) {
                    this.referer = "$host/"
                    this.quality = quality
                }
            )
        }
    }

    suspend fun extractHbPlay(url: String, quality: Int, callback: (ExtractorLink) -> Unit) {
        val cleaned = cleanUrl(url)
        val encodedU = cleaned.substringAfter("u=", "").substringBefore("&").trim()
        if (encodedU.isNotBlank()) {
            try {
                val decodedBytes = try {
                    Base64.decode(encodedU, Base64.DEFAULT)
                } catch (_: Exception) {
                    Base64.decode(encodedU, Base64.URL_SAFE)
                }
                val decodedUrl = cleanUrl(String(decodedBytes))
                if (decodedUrl.startsWith("http")) {
                    val finalQuality = if (quality != Qualities.Unknown.value) quality else getQualityFromName(decodedUrl)
                    callback.invoke(
                        newExtractorLink("HBPlay", "HubCloud [HBPlay Watch Online]", decodedUrl, ExtractorLinkType.VIDEO) {
                            this.referer = ""
                            this.quality = finalQuality
                        }
                    )
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun extractFuckingFast(url: String, quality: Int, callback: (ExtractorLink) -> Unit) {
        try {
            val cleaned = cleanUrl(url)
            val responseText = app.get(cleaned, headers = katBrowserHeaders, interceptor = katCfKiller).text
            val directLink = Regex("""window\.open\(["'](https?://fuckingfast\.net/dl/[^"']+)["']\)""")
                .find(responseText)?.groupValues?.get(1)
                ?: Regex("""["'](https?://fuckingfast\.net/dl/[^"']+)["']""")
                    .find(responseText)?.groupValues?.get(1)

            if (!directLink.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink("BuzzServer", "HubCloud [Buzz Server]", cleanUrl(directLink), ExtractorLinkType.VIDEO) {
                        this.referer = cleaned
                        this.quality = quality
                    }
                )
            }
        } catch (_: Exception) {}
    }

    suspend fun extractGoFlixMirror(
        url: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleaned = cleanUrl(url)
            val doc = app.get(cleaned, headers = katBrowserHeaders, interceptor = katCfKiller).document
            doc.select("a[href]").forEach { a ->
                val href = cleanUrl(a.attr("href"))
                if (href.startsWith("http") && !href.contains("goflix.sbs") && !href.contains("t.me")) {
                    when {
                        href.contains("pixeldrain") -> extractPixelDrain(href, quality, callback)
                        href.contains("fuckingfast") -> extractFuckingFast(href, quality, callback)
                        else -> loadExtractor(href, cleaned, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

// =========================================================================
// Main KatDrama API Provider
// =========================================================================
class KatDrama : MainAPI() {
    override var mainUrl = "https://k-drama.in"
    override var name = "KatDrama"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/explore?type=kdrama&page=" to "K-Drama",
        "$mainUrl/explore?type=cdrama&page=" to "C-Drama",
        "$mainUrl/explore?type=jdrama&page=" to "J-Drama",
        "$mainUrl/explore?type=movie&page=" to "Movies",
        "$mainUrl/explore?provider=hindi&page=" to "Hindi Dubbed",
        "$mainUrl/ranking?page=" to "Top 10"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == "$mainUrl/") mainUrl else "${request.data}$page"
        val document = app.get(url, headers = katBrowserHeaders, interceptor = katCfKiller).document
        val homePageList = mutableListOf<HomePageList>()
        val rows = document.select(".cineapse-row-wrapper")

        if (request.data == "$mainUrl/" && rows.isNotEmpty()) {
            rows.forEach { row ->
                val title = row.selectFirst("h3.cineapse-row-title")?.text()
                    ?.replace("Explore all →", "")?.trim() ?: return@forEach
                val elements = row.select(".cineapse-row-track .cineapse-card")
                val searchResponses = elements.mapNotNull { it.toSearchResult() }

                if (searchResponses.isNotEmpty()) {
                    homePageList.add(HomePageList(title, searchResponses))
                }
            }
        } else {
            val elements = document.select(".cineapse-card, a[href*='/detail/']")
            val searchResponses = elements.mapNotNull { it.toSearchResult() }

            if (searchResponses.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, searchResponses))
            }
        }

        return newHomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val onClickAttr = this.attr("onclick")
        val urlPath = if (onClickAttr.contains("href='")) {
            onClickAttr.substringAfter("href='").substringBefore("'")
        } else {
            this.attr("href")
        }

        if (urlPath.isBlank() || !urlPath.contains("/detail/")) return null

        val title = this.selectFirst(".cineapse-card-title, h4")?.text()?.trim() ?: return null
        if (title.equals("x", ignoreCase = true)) return null

        val posterUrl = fixUrlNull(this.selectFirst("img.cineapse-poster-img, img")?.attr("src"))
        val year = this.selectFirst(".cineapse-card-meta span")?.text()?.trim()?.toIntOrNull()
        val fullUrl = fixUrl(urlPath)

        val isMovie = fullUrl.contains("/detail/movie/") ||
                this.select(".cineapse-card-meta span").any { it.text().equals("Movie", true) }

        return if (isMovie) {
            newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, fullUrl, TvType.AsianDrama) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val apiUrl = "$mainUrl/api.php?action=search&q=$encodedQuery"
        val response = app.get(apiUrl, headers = katBrowserHeaders, interceptor = katCfKiller).parsedSafe<SearchApiResponse>() ?: return emptyList()

        return response.results.mapNotNull { item ->
            val title = item.name ?: item.title ?: return@mapNotNull null
            val id = item.id ?: return@mapNotNull null
            val isMovie = item.media_type == "movie" || (!item.title.isNullOrBlank() && item.name.isNullOrBlank())

            val slugBase = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val slug = "$slugBase-$id"

            val url = if (isMovie) "$mainUrl/detail/movie/$slug" else "$mainUrl/detail/tv/$slug"
            val poster = item.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            val year = (item.first_air_date ?: item.release_date)?.take(4)?.toIntOrNull()

            if (isMovie) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
    }

    data class SearchApiResponse(val results: List<SearchApiResult> = emptyList())
    data class SearchApiResult(
        val id: Int?,
        val title: String?,
        val name: String?,
        val media_type: String?,
        val poster_path: String?,
        val first_air_date: String?,
        val release_date: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = katBrowserHeaders, interceptor = katCfKiller).document

        val title = document.selectFirst("h1, .cineapse-title, .detail-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("img.poster, img.detail-poster")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val plot = document.selectFirst(".plot, .synopsis, p.overview, p.description")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")

        val isMovie = url.contains("/detail/movie/") || url.contains("/movie/")
        val slug = url.substringAfterLast("/")

        return if (isMovie) {
            val watchUrl = "$mainUrl/watch/movie-$slug"
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val episodeElements = document.select(".episode-btn, .ep-list button, a[href*='-episode-'], a[href*='/watch/']")

            if (episodeElements.isNotEmpty()) {
                episodeElements.forEachIndexed { index, epElement ->
                    val href = epElement.attr("href")
                    val epNum = Regex("episode-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                        ?: epElement.text().filter { it.isDigit() }.toIntOrNull()
                        ?: (index + 1)

                    val epUrl = if (href.isNotBlank() && href.contains("/watch/")) {
                        fixUrl(href)
                    } else {
                        "$mainUrl/watch/$slug-episode-$epNum"
                    }

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                        }
                    )
                }
            } else {
                val totalEpsText = document.selectFirst(".total-episodes")?.text() ?: "1"
                val totalEps = totalEpsText.filter { it.isDigit() }.toIntOrNull() ?: 1

                for (i in 1..totalEps) {
                    episodes.add(
                        newEpisode("$mainUrl/watch/$slug-episode-$i") {
                            this.name = "Episode $i"
                            this.episode = i
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes.distinctBy { it.episode }) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // Data classes for CinemaOS / Worker JSON streams & Subtitles
    data class CinemaOsResponse(
        val source: CinemaOsSource? = null,
        val sources: List<CinemaOsSource>? = null,
        val subtitles: List<SubtitleItem>? = null
    )

    data class CinemaOsSource(
        val id: String? = null,
        val scraperName: String? = null,
        val label: String? = null,
        val quality: String? = null,
        val type: String? = null,
        val url: String? = null
    )

    data class SubtitleItem(
        val url: String? = null,
        val label: String? = null,
        val lang: String? = null,
        val language: String? = null,
        val format: String? = null,
        val source: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = katBrowserHeaders, interceptor = katCfKiller).document

        // 1. switchServer buttons
        document.select("button[onclick*='switchServer']").forEach { btn ->
            val onclick = btn.attr("onclick")
            val serverUrl = onclick.substringAfter("'", "").substringAfter("'", "").substringAfter("', '").substringBefore("'")
            if (serverUrl.isNotBlank() && serverUrl.startsWith("http")) {
                handleServerUrl(fixUrl(serverUrl), data, subtitleCallback, callback)
            }
        }

        // 2. Generic data attributes / buttons
        document.select(".server-btn, button[data-server], [data-url], [data-src]").forEach { serverBtn ->
            val serverUrl = serverBtn.attr("data-url").ifBlank { serverBtn.attr("data-src") }
            if (serverUrl.isNotBlank()) {
                handleServerUrl(fixUrl(serverUrl), data, subtitleCallback, callback)
            }
        }

        // 3. Direct iframes & video sources
        document.select("iframe[src], video source[src]").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) {
                handleServerUrl(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun handleServerUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.contains("cinemaos") || url.contains("workers.dev") || url.contains(".json")) {
            val parsed = app.get(url, headers = katBrowserHeaders, interceptor = katCfKiller).parsedSafe<CinemaOsResponse>()
            
            parsed?.subtitles?.forEach { sub ->
                val subUrl = sub.url ?: return@forEach
                val langLabel = sub.label ?: sub.language ?: sub.lang ?: "Subtitle"
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = langLabel,
                        url = subUrl
                    )
                )
            }

            val allSources = listOfNotNull(parsed?.source) + (parsed?.sources ?: emptyList())
            if (allSources.isNotEmpty()) {
                allSources.forEach { item ->
                    val streamUrl = item.url ?: return@forEach
                    val linkType = when (item.type?.lowercase()) {
                        "dash" -> ExtractorLinkType.DASH
                        "hls", "m3u8" -> ExtractorLinkType.M3U8
                        else -> INFER_TYPE
                    }
                    val displayName = listOfNotNull(item.scraperName, item.label)
                        .joinToString(" - ")
                        .ifBlank { "KatDrama Stream" }

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = displayName,
                            url = streamUrl,
                            type = linkType
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = getQualityFromName(item.quality)
                        }
                    )
                }
                return
            }
        }

        loadExtractor(url, referer, subtitleCallback, callback)
    }
}
