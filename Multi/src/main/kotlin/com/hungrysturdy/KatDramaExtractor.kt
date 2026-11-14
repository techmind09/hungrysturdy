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
// Core Server Logic Object (Handles all URL sanitization & extraction)
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

            // 1. FSL Server (r2.cloudflarestorage.com)
            gamerDoc.select("a#fsl, a:contains(FSL Server), a[href*='r2.cloudflarestorage.com']").forEach { a ->
                val fslUrl = cleanUrl(a.attr("href"))
                if (fslUrl.startsWith("http")) {
                    val q = if (detectedQuality != Qualities.Unknown.value) detectedQuality else getQualityFromName(fslUrl)
                    callback.invoke(
                        newExtractorLink(
                            source = "HubCloud",
                            name = "HubCloud [FSL Server]",
                            url = fslUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = q
                        }
                    )
                }
            }

            // 2. HBPlay Watch Online
            gamerDoc.select("a[href*='hbplay.pages.dev']").forEach { a ->
                val hbUrl = cleanUrl(a.attr("href"))
                extractHbPlay(hbUrl, detectedQuality, callback)
            }

            // 3. PixelServer
            gamerDoc.select("a#pxl-1, a[href*='pixeldrain']").forEach { a ->
                val pxlUrl = cleanUrl(a.attr("href"))
                extractPixelDrain(pxlUrl, detectedQuality, callback)
            }

            // 4. Server : 10Gbps
            gamerDoc.select("a[href*='pixel.hubcloud'], a:contains(10Gbps)").forEach { a ->
                val tenUrl = cleanUrl(a.attr("href"))
                if (tenUrl.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "HubCloud",
                            name = "HubCloud [Server : 10Gbps]",
                            url = tenUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
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
                "https://new9.gdflix.io"
            }

            val pageInfo = "${doc.title()} ${doc.text()} ${referer.orEmpty()}"
            val detectedQuality = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(pageInfo)?.groupValues?.get(1)
                ?.let { getQualityFromName(it) } ?: Qualities.Unknown.value

            doc.select("a[href]").forEach { a ->
                val rawHref = cleanUrl(a.attr("href"))
                if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript") || rawHref.contains("/login")) return@forEach

                val absHref = if (rawHref.startsWith("http")) rawHref else "$baseOrigin${if (rawHref.startsWith("/")) "" else "/"}$rawHref"
                val btnText = a.text().trim()

                when {
                    rawHref.contains("/cloud/") || btnText.contains("FAST CLOUD", true) || btnText.contains("ZIPDISK", true) -> {
                        extractGdFlixCloudSubpage(absHref, finalUrl, detectedQuality, callback)
                    }

                    absHref.contains("instadl.rest") ||
                    absHref.contains("busycdn") ||
                    absHref.contains("cloudpaglu.site") ||
                    absHref.contains("workers.dev") ||
                    absHref.contains("r2.cloudflarestorage.com") ||
                    btnText.contains("INSTANT DL", true) -> {
                        if (!absHref.contains("gdflix") && !absHref.contains("t.me")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "GDFlix",
                                    name = "GDFlix [Instant DL 10Gbps]",
                                    url = absHref,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = finalUrl
                                    this.quality = detectedQuality
                                }
                            )
                        }
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
                                newExtractorLink(
                                    source = "GDFlix",
                                    name = "GDFlix [DriveBot]",
                                    url = dl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
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

    private suspend fun extractGdFlixCloudSubpage(
        cloudUrl: String,
        refererUrl: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(cloudUrl, headers = katBrowserHeaders, referer = refererUrl, interceptor = katCfKiller)
            val doc = resp.document

            doc.select("a[href]").forEach { a ->
                val dlHref = cleanUrl(a.attr("href"))
                val text = a.text().trim()

                if (dlHref.startsWith("http") &&
                    !dlHref.contains("gdflix") &&
                    !dlHref.contains("/login") &&
                    !dlHref.contains("t.me")
                ) {
                    val serverLabel = when {
                        dlHref.contains("workers.dev") -> "GDFlix [Fast Cloud - Workers.dev]"
                        dlHref.contains("cloudpaglu.site") -> "GDFlix [Fast Cloud - CloudPaglu]"
                        dlHref.contains("instadl.rest") || dlHref.contains("busycdn") -> "GDFlix [Fast Cloud - BusyCDN]"
                        text.isNotEmpty() -> "GDFlix [Fast Cloud - $text]"
                        else -> "GDFlix [Fast Cloud]"
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = "GDFlix",
                            name = serverLabel,
                            url = dlHref,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = cloudUrl
                            this.quality = quality
                        }
                    )
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun extractPixelDrain(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleaned = cleanUrl(url)
        val fileId = cleaned.substringAfter("/u/").substringAfter("/file/").substringBefore("?").substringBefore("/")
        if (fileId.isNotBlank()) {
            val host = if (cleaned.contains("pixeldrain.dev")) "https://pixeldrain.dev" else "https://pixeldrain.com"
            callback.invoke(
                newExtractorLink(
                    source = "PixelServer",
                    name = "PixelServer [Direct]",
                    url = "$host/api/file/$fileId?download",
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$host/"
                    this.quality = quality
                }
            )
        }
    }

    suspend fun extractHbPlay(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
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
                        newExtractorLink(
                            source = "HBPlay",
                            name = "HubCloud [HBPlay Watch Online]",
                            url = decodedUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = finalQuality
                        }
                    )
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun extractFuckingFast(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleaned = cleanUrl(url)
            val responseText = app.get(cleaned, headers = katBrowserHeaders, interceptor = katCfKiller).text
            val directLink = Regex("""window\.open\(["'](https?://fuckingfast\.net/dl/[^"']+)["']\)""")
                .find(responseText)?.groupValues?.get(1)
                ?: Regex("""["'](https?://fuckingfast\.net/dl/[^"']+)["']""")
                    .find(responseText)?.groupValues?.get(1)

            if (!directLink.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "BuzzServer",
                        name = "HubCloud [Buzz Server]",
                        url = cleanUrl(directLink),
                        type = ExtractorLinkType.VIDEO
                    ) {
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
