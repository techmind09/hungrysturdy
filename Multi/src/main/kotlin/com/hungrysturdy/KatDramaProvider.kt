package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import java.net.URI

private val cfKiller by lazy { CloudflareKiller() }

// Browser jaisi stealth headers taaki Cloudflare / GDFlix block na kare
private val defaultHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Accept-Encoding" to "gzip, deflate, br",
    "Connection" to "keep-alive",
    "Upgrade-Insecure-Requests" to "1",
    "Sec-Ch-Ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\"",
    "Sec-Ch-Ua-Mobile" to "?0",
    "Sec-Ch-Ua-Platform" to "\"Windows\"",
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "none",
    "Sec-Fetch-User" to "?1"
)

class Filemoon : Filesim() {
    override val name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
}

// =========================================================================
// 1. HubCloud Extractor
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
        try {
            val response = app.get(url, headers = defaultHeaders, referer = referer, interceptor = cfKiller)
            val doc = response.document
            val htmlText = response.text

            val fileHeader = doc.selectFirst("div.card-header, title")?.text()?.trim().orEmpty()
            val detectedQuality = getQualityFromName(fileHeader).let {
                if (it == Qualities.Unknown.value) getQualityFromName(referer.orEmpty()) else it
            }

            val gamerLink = if (url.contains("hubcloud.php")) {
                url
            } else {
                doc.selectFirst("a#download, a[href*='hubcloud.php']")?.attr("href")
                    ?: Regex("""var\s+url\s*=\s*['"](https://[^'"]*hubcloud\.php[^'"]*)['"]""")
                        .find(htmlText)?.groupValues?.get(1)
                    ?: return
            }

            val gamerDoc = app.get(gamerLink, headers = defaultHeaders, referer = response.url, interceptor = cfKiller).document

            // 1. FSL Server
            gamerDoc.selectFirst("a#fsl, a:contains(FSL Server), a[href*='r2.cloudflarestorage.com']")?.attr("href")?.let { fslUrl ->
                if (fslUrl.isNotBlank()) {
                    val fslQuality = if (detectedQuality != Qualities.Unknown.value) detectedQuality else getQualityFromName(fslUrl)
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name [FSL Server]",
                            url = fslUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = gamerLink
                            this.quality = fslQuality
                        }
                    )
                }
            }

            // 2. HBPlay Direct Link
            gamerDoc.selectFirst("a[href*='hbplay.pages.dev']")?.attr("href")?.let { hbUrl ->
                extractHbPlayLink(hbUrl, detectedQuality, callback)
            }

            // 3. PixelDrain Server
            gamerDoc.selectFirst("a#pxl-1, a[href*='pixeldrain']")?.attr("href")?.let { pxlUrl ->
                extractPixelDrainLink(pxlUrl, detectedQuality, callback)
            }

            // 4. 10Gbps Server
            gamerDoc.selectFirst("a[href*='pixel.hubcloud'], a:contains(10Gbps)")?.attr("href")?.let { tenGbpsUrl ->
                if (tenGbpsUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name [Server : 10Gbps]",
                            url = tenGbpsUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = gamerLink
                            this.quality = detectedQuality
                        }
                    )
                }
            }

            // 5. Buzz Server
            gamerDoc.selectFirst("a[href*='fuckingfast.net'], a:contains(Buzz Server)")?.attr("href")?.let { buzzUrl ->
                if (buzzUrl.isNotBlank()) {
                    extractFuckingFastLink(buzzUrl, detectedQuality, callback)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class HubCloudDrive : KatHubCloudExtractor() {
    override val mainUrl = "https://hubcloud.dad"
}

class HubCloudClub : KatHubCloudExtractor() {
    override val mainUrl = "https://hubcloud.club"
}

class GamerXYTExtractor : KatHubCloudExtractor() {
    override val name = "GamerXYT"
    override val mainUrl = "https://gamerxyt.com"
}

// =========================================================================
// 2. GDFlix Extractor
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
        try {
            val response = app.get(url, headers = defaultHeaders, referer = referer, interceptor = cfKiller)
            val doc = response.document
            val finalUrl = response.url.ifEmpty { url }
            val baseOrigin = try {
                val uri = URI(finalUrl)
                "${uri.scheme}://${uri.host}"
            } catch (_: Exception) {
                mainUrl
            }

            val pageInfo = "${doc.title()} ${doc.selectFirst("div.card-body, ul.list-group")?.text().orEmpty()}"
            val quality = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(pageInfo)?.groupValues?.get(1)
                ?.let { getQualityFromName(it) }
                ?: getQualityFromName(referer.orEmpty())

            doc.select("a.btn, a[href]").forEach { btn ->
                val rawHref = btn.attr("href").trim()
                if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.contains("/login")) return@forEach

                val absHref = if (rawHref.startsWith("http")) rawHref else "$baseOrigin${if (rawHref.startsWith("/")) "" else "/"}$rawHref"
                val text = btn.text().trim()

                when {
                    rawHref.contains("/cloud/") || text.contains("FAST CLOUD", true) || text.contains("ZIPDISK", true) -> {
                        extractGdFlixCloudPage(absHref, finalUrl, quality, callback)
                    }
                    absHref.contains("pixeldrain") -> {
                        extractPixelDrainLink(absHref, quality, callback)
                    }
                    absHref.contains("hbplay.pages.dev") -> {
                        extractHbPlayLink(absHref, quality, callback)
                    }
                    absHref.contains("fuckingfast.net") -> {
                        extractFuckingFastLink(absHref, quality, callback)
                    }
                    absHref.contains("goflix.sbs") || absHref.contains("gofile") -> {
                        loadExtractor(absHref, finalUrl, subtitleCallback, callback)
                    }
                    absHref.contains("busycdn") ||
                    absHref.contains("cloudpaglu") ||
                    absHref.contains("workers.dev") ||
                    absHref.contains("r2.cloudflarestorage.com") ||
                    text.contains("INSTANT DL", true) -> {
                        if (!absHref.contains("gdflix") && !absHref.contains("t.me")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "$name [${text.ifEmpty { "Instant DL" }}]",
                                    url = absHref,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = finalUrl
                                    this.quality = quality
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun extractGdFlixCloudPage(
        cloudUrl: String,
        refererUrl: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cloudResp = app.get(cloudUrl, headers = defaultHeaders, referer = refererUrl, interceptor = cfKiller)
            val cloudDoc = cloudResp.document

            cloudDoc.select("a[href]").forEach { a ->
                val dlHref = a.attr("href").trim()
                val btnText = a.text().trim()

                if (dlHref.startsWith("http") &&
                    !dlHref.contains("gdflix") &&
                    !dlHref.contains("t.me") &&
                    !dlHref.contains("/login")
                ) {
                    val label = when {
                        dlHref.contains("workers.dev") -> "Fast Cloud [Workers.dev]"
                        dlHref.contains("cloudpaglu") -> "Fast Cloud [CloudPaglu]"
                        btnText.isNotEmpty() -> "Fast Cloud [$btnText]"
                        else -> "Fast Cloud [Direct]"
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name - $label",
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
        extractPixelDrainLink(url, getQualityFromName(referer.orEmpty()), callback)
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
        extractHbPlayLink(url, getQualityFromName(referer.orEmpty()), callback)
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
        extractFuckingFastLink(url, getQualityFromName(referer.orEmpty()), callback)
    }
}

// =========================================================================
// Helper Functions
// =========================================================================
suspend fun extractPixelDrainLink(
    url: String,
    quality: Int,
    callback: (ExtractorLink) -> Unit
) {
    val fileId = url.substringAfter("/u/").substringAfter("/file/").substringBefore("?").substringBefore("/")
    if (fileId.isNotBlank()) {
        val host = if (url.contains("pixeldrain.dev")) "https://pixeldrain.dev" else "https://pixeldrain.com"
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

suspend fun extractHbPlayLink(
    url: String,
    quality: Int,
    callback: (ExtractorLink) -> Unit
) {
    val encodedU = url.substringAfter("u=", "").substringBefore("&")
    if (encodedU.isNotBlank()) {
        try {
            val decodedUrl = String(Base64.decode(encodedU, Base64.DEFAULT)).trim()
            if (decodedUrl.startsWith("http")) {
                val finalQuality = if (quality != Qualities.Unknown.value) quality else getQualityFromName(decodedUrl)
                callback.invoke(
                    newExtractorLink(
                        source = "HBPlay",
                        name = "HubCloud [HBPlay Direct]",
                        url = decodedUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://hbplay.pages.dev/"
                        this.quality = finalQuality
                    }
                )
            }
        } catch (_: Exception) {}
    }
}

suspend fun extractFuckingFastLink(
    url: String,
    quality: Int,
    callback: (ExtractorLink) -> Unit
) {
    try {
        val responseText = app.get(url, headers = defaultHeaders, interceptor = cfKiller).text
        val directLink = Regex("""window\.open\(["'](https://fuckingfast\.net/dl/[^"']+)["']\)""")
            .find(responseText)?.groupValues?.get(1)
        if (!directLink.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = "BuzzServer",
                    name = "HubCloud [Buzz Server]",
                    url = directLink,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = quality
                }
            )
        }
    } catch (_: Exception) {}
}
