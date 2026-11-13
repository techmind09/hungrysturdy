package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*

private val cfKiller by lazy { CloudflareKiller() }

private val defaultHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)

// =========================================================================
// 1. HubCloud Extractor (hubcloud.pw -> gamerxyt.com -> Direct Servers)
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
            val gamerLink = if (url.contains("hubcloud.php")) {
                url
            } else {
                val doc = app.get(url, headers = defaultHeaders, referer = referer, interceptor = cfKiller).document
                doc.selectFirst("a#download, a[href*='hubcloud.php']")?.attr("href") ?: return
            }

            val gamerDoc = app.get(gamerLink, headers = defaultHeaders, referer = url).document

            val pageTitle = gamerDoc.selectFirst("title, div.card-header, h5, h6")?.text().orEmpty()
            val detectedQuality = getQualityFromName(pageTitle).let {
                if (it == Qualities.Unknown.value) getQualityFromName(referer.orEmpty()) else it
            }

            // 1. FSL Server
            gamerDoc.selectFirst("a#fsl, a:contains(FSL Server), a[href*='r2.cloudflarestorage.com']")?.attr("href")?.let { fslUrl ->
                if (fslUrl.isNotBlank()) {
                    val fslQuality = if (detectedQuality != Qualities.Unknown.value) detectedQuality else getQualityFromName(fslUrl)
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$name [FSL Server]",
                            url = fslUrl,
                            quality = fslQuality,
                            headers = mapOf("referer" to gamerLink)
                        )
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
                            quality = detectedQuality,
                            headers = mapOf("referer" to gamerLink)
                        )
                    )
                }
            }

            // 5. Buzz Server
            gamerDoc.selectFirst("a[href*='fuckingfast.net'], a:contains(Buzz Server)")?.attr("href")?.let { buzzUrl ->
                extractFuckingFastLink(buzzUrl, detectedQuality, callback)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// Mirrors of HubCloud & GamerXYT
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
// 2. GDFlix Extractor (gdflix.vip / gdflix.cfd)
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
            val doc = app.get(url, headers = defaultHeaders, referer = referer, interceptor = cfKiller).document
            val pageTitle = doc.selectFirst("title, .card-header")?.text().orEmpty()
            val quality = getQualityFromName(pageTitle).let {
                if (it == Qualities.Unknown.value) getQualityFromName(referer.orEmpty()) else it
            }

            doc.select("a.btn, a[href]").forEach { btn ->
                val href = btn.attr("href")
                val text = btn.text().trim()

                when {
                    href.contains("pixeldrain") -> {
                        extractPixelDrainLink(href, quality, callback)
                    }
                    href.contains("hbplay.pages.dev") -> {
                        extractHbPlayLink(href, quality, callback)
                    }
                    href.contains("fuckingfast.net") -> {
                        extractFuckingFastLink(href, quality, callback)
                    }
                    href.contains("r2.cloudflarestorage.com") ||
                    text.contains("Instant DL", true) ||
                    text.contains("Fast Cloud", true) ||
                    text.contains("Direct", true) -> {
                        if (href.startsWith("http") && !href.contains("gdflix")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "$name [${text.ifEmpty { "Direct" }}]",
                                    url = href,
                                    quality = quality,
                                    headers = mapOf("referer" to url)
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class GDFlixCfd : KatGDFlixExtractor() {
    override val mainUrl = "https://gdflix.cfd"
}

class GDFlixdad : KatGDFlixExtractor() {
    override val mainUrl = "https://gdflix.dad"
}

// =========================================================================
// 3. Standalone Extractors (PixelDrain, HBPlay, FuckingFast)
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
                name = "HubCloud [PixelServer : 2]",
                url = "$host/api/file/$fileId?download",
                quality = quality,
                headers = mapOf("referer" to "$host/")
            )
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
                        quality = finalQuality,
                        headers = mapOf("referer" to "https://hbplay.pages.dev/")
                    )
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
        val responseText = app.get(url, headers = defaultHeaders).text
        val directLink = Regex("""window\.open\(["'](https://fuckingfast\.net/dl/[^"']+)["']\)""")
            .find(responseText)?.groupValues?.get(1)
        if (!directLink.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = "BuzzServer",
                    name = "HubCloud [Buzz Server]",
                    url = directLink,
                    quality = quality,
                    headers = mapOf("referer" to url)
                )
            )
        }
    } catch (_: Exception) {}
}
