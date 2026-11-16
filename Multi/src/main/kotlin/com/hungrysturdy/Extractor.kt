package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URI

private val katCfKiller by lazy { CloudflareKiller() }

private val katBrowserHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Cookie" to "xla=s4t"
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
// 2. GDFlix Extractors & Mirrors (Including new9 & new10)
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
// Core Server Logic Object (Handles JSON queue, modals, and CDN links)
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

            // 1. FSL Server
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

            // 2. HBPlay
            gamerDoc.select("a[href*='hbplay.pages.dev']").forEach { a ->
                extractHbPlay(cleanUrl(a.attr("href")), detectedQuality, callback)
            }

            // 3. PixelServer
            gamerDoc.select("a#pxl-1, a[href*='pixeldrain']").forEach { a ->
                extractPixelDrain(cleanUrl(a.attr("href")), detectedQuality, callback)
            }

            // 4. 10Gbps Server
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

            // 5. Buzz Server
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

    // Handles JSON queue status ("In Queue"), telemetry, and HTML cloud subpages
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

            // Handle JSON queue status polling (e.g. {"status": "In Queue", "done": false})
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
