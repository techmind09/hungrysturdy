package com.hungrysturdy

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

private const val DEFAULT_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

private fun safeBaseHost(url: String, fallback: String = "https://example.com"): String {
    return try {
        val u = URI(url)
        val port = if (u.port in 1..65535) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port"
    } catch (_: Exception) {
        fallback
    }
}

private fun safeB64Decode(value: String): String {
    var s = value.replace("-", "+").replace("_", "/")
    val rem = s.length % 4
    if (rem != 0) s += "=".repeat(4 - rem)
    return try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (_: Exception) {
        ""
    }
}

private fun isExtractorAllowed(serverKey: String): Boolean {
    val ctx: Context = CloudStreamApp.context ?: return true
    return MovieFoxSettingsDialog.isServerEnabled(ctx, serverKey)
}

private fun recordExtractorHealth(serverKey: String, ok: Boolean, elapsedMs: Long) {
    val ctx: Context? = CloudStreamApp.context
    MovieFoxSettingsDialog.reportServerResult(ctx, serverKey, ok, elapsedMs)
}

class DirectMediaExtractor : ExtractorApi() {

    override val name = "DirectMedia"
    override val mainUrl = "https://example.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return
        }

        if (uri.scheme !in listOf("http", "https")) return

        val path = uri.path.orEmpty().lowercase()
        val host = uri.host.orEmpty().lowercase()

        val type = when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mp4") ||
                path.endsWith(".mkv") ||
                host.contains(".r2.dev") ||
                host.contains(".r2.cloudflarestorage.com") -> ExtractorLinkType.VIDEO
            else -> return
        }

        val label = when {
            type == ExtractorLinkType.M3U8 -> "Direct HLS"
            host.contains(".r2.cloudflarestorage.com") -> "FSLv2 Direct"
            host.contains(".r2.dev") -> "FSL Direct"
            else -> "Direct MP4"
        }

        val headers = mapOf(
            "User-Agent" to DEFAULT_MOBILE_UA,
            "Accept" to "*/*"
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = label,
                url = url,
                type = type
            ) {
                this.referer = referer.orEmpty()
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
    }
}

open class BaseJWPExtractor : ExtractorApi() {
    override var name = "BaseJWP"
    override var mainUrl = "https://example.com"
    override val requiresReferer = true

    protected val defaultUserAgent = DEFAULT_MOBILE_UA

    protected fun getOrigin(targetUrl: String): String = safeBaseHost(targetUrl, mainUrl)

    protected fun isIgnoredUrl(url: String): Boolean {
        return url.contains("preview", true) ||
            url.contains("thumb", true) ||
            url.contains("backhoemoya", true) ||
            url.contains("/cx/", true) ||
            url.contains("tiktokcdn.com", true)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "$mainUrl/"
        val embedOrigin = getOrigin(url)
        val emittedUrls = mutableSetOf<String>()

        val pageHeaders = mapOf(
            "User-Agent" to defaultUserAgent,
            "Referer" to pageReferer,
            "Origin" to embedOrigin,
            "Accept" to "*/*"
        )

        val responseObj = app.get(url, referer = pageReferer, headers = pageHeaders)
        val response = responseObj.text

        val streamHeaders = mapOf(
            "User-Agent" to defaultUserAgent,
            "Referer" to url,
            "Origin" to embedOrigin,
            "Accept" to "*/*"
        )

        val contentType = responseObj.headers["Content-Type"].orEmpty()
        if (contentType.contains("mpegurl", true) || response.trimStart().startsWith("#EXTM3U")) {
            if (emittedUrls.add(url)) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} HLS",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                        this.headers = streamHeaders
                    }
                )
            }
            return
        }

        val unpacked = JsUnpacker(response).unpack() ?: response
        val combinedBuilder = StringBuilder("$response\n$unpacked")

        Regex("""["']([A-Za-z0-9+/=]{40,})["']""").findAll(response).forEach { match ->
            try {
                val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                if (decoded.contains("http")) {
                    combinedBuilder.append("\n").append(decoded)
                }
            } catch (_: Exception) {
            }
        }
        val combinedHtml = combinedBuilder.toString()

        val streamRegex = Regex(
            """["']((?:https?://|/stream_proxy\.php\?)[^"'\s<>]+?(?:\.(?:m3u8|mp4|mkv)|workers\.dev/[^"'\s<>]+|itsnitrox\.tech/[^"'\s<>]+|vibuxer\.com[^"'\s<>]*|type=hls)[^"'\s<>]*)["']""",
            RegexOption.IGNORE_CASE
        )

        val foundUrls = streamRegex.findAll(combinedHtml)
            .map { raw ->
                val cleaned = raw.groupValues[1].replace("\\/", "/").trim()
                if (cleaned.startsWith("/")) "$embedOrigin$cleaned" else cleaned
            }
            .distinct()
            .toList()

        for (streamUrl in foundUrls) {
            if (isIgnoredUrl(streamUrl) || !emittedUrls.add(streamUrl)) continue

            val isHls = streamUrl.contains(".m3u8", true) ||
                streamUrl.contains("type=hls", true) ||
                streamUrl.contains("workers.dev", true) ||
                streamUrl.contains("itsnitrox.tech", true) ||
                streamUrl.contains("vibuxer.com", true) ||
                streamUrl.contains("stream_proxy.php", true)

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = if (isHls) "${this.name} HLS" else this.name,
                    url = streamUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                    this.headers = streamHeaders
                }
            )
        }

        val emittedSubs = mutableSetOf<String>()
        val subRegex = Regex("""["'](https?://[^"'\s<>]+\.(?:vtt|srt)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
        subRegex.findAll(combinedHtml)
            .map { it.groupValues[1].replace("\\/", "/").trim() }
            .filter { emittedSubs.add(it) }
            .forEach { subUrl ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        lang = "Hindi / English",
                        url = subUrl
                    )
                )
            }
    }
}

// Google Drive
open class GDriveExtractor : ExtractorApi() {
    override var name = "GDrive"
    override var mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""(?:id=|/d/|/file/d/)([a-zA-Z0-9_-]{15,})""")
            .find(url)?.groupValues?.get(1) ?: return

        val directDownloadUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "GDrive Direct",
                url = directDownloadUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://drive.google.com/"
                this.quality = Qualities.P1080.value
            }
        )
    }
}

// StreamTape
open class StreamTapeExtractor : ExtractorApi() {
    override var name = "StreamTape"
    override var mainUrl = "https://streamtape.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = url
            .replace(mainUrl, "https://streamtape.com", ignoreCase = true)
            .replace("/v/", "/e/", ignoreCase = true)
        val pageReferer = referer ?: "https://hindidrama.net/"

        val html = app.get(
            normalizedUrl,
            referer = pageReferer,
            headers = mapOf(
                "User-Agent" to DEFAULT_MOBILE_UA,
                "Referer" to pageReferer
            )
        ).text

        val linkRegex = Regex(
            """document\.getElementById\(['"](?:no)?robotlink['"]\)\.innerHTML\s*=\s*["']([^"']+)["']\s*\+\s*\(?["']([^"']+)["']\)?\.substring\((\d+)\)(?:\.substring\((\d+)\))?"""
        )
        val match = linkRegex.find(html)
        if (match != null) {
            val part1 = match.groupValues[1]
            var part2 = match.groupValues[2]
            val sub1 = match.groupValues[3].toIntOrNull() ?: 0
            val sub2 = match.groupValues[4].toIntOrNull() ?: 0

            if (sub1 > 0 && part2.length > sub1) part2 = part2.substring(sub1)
            if (sub2 > 0 && part2.length > sub2) part2 = part2.substring(sub2)

            var finalUrl = "$part1$part2"
            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            else if (finalUrl.startsWith("/")) finalUrl = "https:/$finalUrl"

            if (!finalUrl.contains("stream=1")) {
                finalUrl += if (finalUrl.contains("?")) "&stream=1" else "?stream=1"
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = normalizedUrl
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}

class StreamTapeToExtractor : StreamTapeExtractor() { override var mainUrl = "https://streamtape.to" }
class StreamTapeNetExtractor : StreamTapeExtractor() { override var mainUrl = "https://streamtape.net" }
class StreamTapeXyzExtractor : StreamTapeExtractor() { override var mainUrl = "https://streamtape.xyz" }
class ShaveTapeExtractor : StreamTapeExtractor() { override var mainUrl = "https://shavetape.cash" }
class StrCloudExtractor : StreamTapeExtractor() { override var mainUrl = "https://strcloud.link" }
class StrTapeTechExtractor : StreamTapeExtractor() { override var mainUrl = "https://strtape.tech" }
class StapeFunExtractor : StreamTapeExtractor() { override var mainUrl = "https://stape.fun" }

// Voe
open class VoeExtractor : BaseJWPExtractor() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "$mainUrl/"
        val response = app.get(
            url,
            referer = pageReferer,
            headers = mapOf("User-Agent" to defaultUserAgent, "Referer" to pageReferer)
        ).text

        val redirectUrl = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""")
            .find(response)?.groupValues?.get(1)
        val html = if (!redirectUrl.isNullOrBlank()) {
            app.get(redirectUrl, referer = url, headers = mapOf("User-Agent" to defaultUserAgent)).text
        } else {
            response
        }

        val activeUrl = redirectUrl ?: url
        val streamHeaders = mapOf(
            "User-Agent" to defaultUserAgent,
            "Referer" to activeUrl,
            "Origin" to getOrigin(activeUrl)
        )

        var extractedAny = false
        val hlsB64 = Regex("""['"]hls['"]\s*:\s*['"]([A-Za-z0-9+/=]+)['"]""").find(html)?.groupValues?.get(1)
        val mp4B64 = Regex("""['"]mp4['"]\s*:\s*['"]([A-Za-z0-9+/=]+)['"]""").find(html)?.groupValues?.get(1)

        if (!hlsB64.isNullOrBlank()) {
            runCatching {
                val decoded = String(Base64.decode(hlsB64, Base64.DEFAULT)).trim()
                if (decoded.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(this.name, "${this.name} HLS", decoded, ExtractorLinkType.M3U8) {
                            this.referer = activeUrl
                            this.quality = Qualities.P1080.value
                            this.headers = streamHeaders
                        }
                    )
                    extractedAny = true
                }
            }
        }

        if (!mp4B64.isNullOrBlank()) {
            runCatching {
                val decoded = String(Base64.decode(mp4B64, Base64.DEFAULT)).trim()
                if (decoded.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(this.name, "${this.name} MP4", decoded, ExtractorLinkType.VIDEO) {
                            this.referer = activeUrl
                            this.quality = Qualities.P1080.value
                            this.headers = streamHeaders
                        }
                    )
                    extractedAny = true
                }
            }
        }

        if (!extractedAny) {
            super.getUrl(activeUrl, pageReferer, subtitleCallback, callback)
        }
    }
}

class VoeToExtractor : VoeExtractor() { override var mainUrl = "https://voe.to" }
class VoeCoExtractor : VoeExtractor() { override var mainUrl = "https://voe.co" }

// Pixeldrain
open class PixeldrainExtractor : ExtractorApi() {
    override var name = "Pixeldrain"
    override var mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!isExtractorAllowed("pixeldrain")) return
        val start = System.currentTimeMillis()
        val fileId = Regex("""pixeldrain\.[a-z]+/(?:u|api/file|l)/([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
        if (fileId == null) {
            recordExtractorHealth("pixeldrain", false, System.currentTimeMillis() - start)
            return
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = "https://pixeldrain.com/api/file/$fileId?download",
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://pixeldrain.com/"
                this.quality = Qualities.P1080.value
            }
        )
        recordExtractorHealth("pixeldrain", true, System.currentTimeMillis() - start)
    }
}

class PixeldrainDevExtractor : PixeldrainExtractor() { override var mainUrl = "https://pixeldrain.dev" }

// =========================================================================
// VegaMovies & HindMoviez Cloud / Direct Extractors
// =========================================================================

// 1. V-Cloud (vcloud.lol / vcloud.zip / vcloud.*)
open class VCloudExtractor : ExtractorApi() {
    override var name = "V-Cloud"
    override var mainUrl = "https://vcloud.lol"
    override val requiresReferer = false

    private val junkRegex = Regex(
        """(?i)vglist|t\.me|telegram|imdb\.com|google\.com/search|whatsapp|share|facebook|twitter|pinterest|report|hubcloud\.(foo|cx)|/admin|login|signup|\bidm\b|\bida\b|category|/tag/|/author/|/page/"""
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!isExtractorAllowed("vegamovies")) return
        val start = System.currentTimeMillis()
        var emittedAny = false

        val headers = mapOf(
            "User-Agent" to DEFAULT_MOBILE_UA,
            "Referer" to (referer ?: "https://nexdrive.beer/"),
            "Cookie" to "xla=s4t"
        )
        val first = runCatching { app.get(url, headers = headers, timeout = 25L).text }.getOrNull() ?: return
        val tokenRegex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]""")
        val plainRegex = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")

        var target = tokenRegex.find(first)?.groupValues?.get(1)
            ?.let { runCatching { base64Decode(base64Decode(it.trim())) }.getOrNull() }
            ?: plainRegex.find(first)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        var fromUrl = url

        if (target == null || !target.startsWith("http")) {
            val hop = Jsoup.parse(first).selectFirst("div.main h4 a")?.attr("href")?.trim() ?: return
            fromUrl = if (hop.startsWith("http")) hop else safeBaseHost(url, mainUrl) + hop
            val hopHtml = runCatching {
                app.get(fromUrl, headers = headers + mapOf("Referer" to url), timeout = 25L).text
            }.getOrNull() ?: return
            target = tokenRegex.find(hopHtml)?.groupValues?.get(1)
                ?.let { runCatching { base64Decode(base64Decode(it.trim())) }.getOrNull() }
                ?: plainRegex.find(hopHtml)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        }
        val resolvedTarget = target?.takeIf { it.startsWith("http") } ?: return

        val html = runCatching {
            app.get(resolvedTarget, headers = headers + mapOf("Referer" to fromUrl), timeout = 25L).text
        }.getOrNull() ?: return

        val seen = mutableSetOf<String>()
        for (a in Jsoup.parse(html).select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http") || junkRegex.containsMatchIn(href)) continue
            val low = a.text().lowercase()
            val h = href.lowercase()

            val (srvName, finalUrl) = when {
                low.contains("fslv2") || low.contains("fsl v2") || low.contains("fsl2") || h.contains(".r2.cloudflarestorage.com") ->
                    "FSLv2" to href
                low.contains("fsl") || h.contains(".r2.dev") ->
                    "FSL" to href
                low.contains("pixelserver") || low.contains("pixel server") || low.contains("pixeldrain") || h.contains("pixeldrain") -> {
                    val id = href.substringBefore("?").substringBefore("#").substringAfterLast("/")
                    "PixelDrain" to "${safeBaseHost(href, "https://pixeldrain.com")}/api/file/$id"
                }
                low.contains("buzzserver") -> "BuzzServer" to href
                low.contains("instant download") || low.contains("instant dl") -> "Instant Download" to href
                else -> continue
            }

            if (seen.add(finalUrl)) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "V-Cloud • $srvName",
                        url = finalUrl,
                        type = if (finalUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA)
                    }
                )
                emittedAny = true
            }
        }

        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") && it.contains("pixeldrain") }
            ?.let { pxlHref ->
                val id = pxlHref.substringBefore("?").substringBefore("#").substringAfterLast("/")
                val apiUrl = "${safeBaseHost(pxlHref, "https://pixeldrain.com")}/api/file/$id"
                if (seen.add(apiUrl)) {
                    callback.invoke(
                        newExtractorLink(this.name, "V-Cloud • PixelDrain", apiUrl, ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA)
                        }
                    )
                    emittedAny = true
                }
            }

        recordExtractorHealth("vegamovies", emittedAny, System.currentTimeMillis() - start)
    }
}

class VCloudZipExtractor : VCloudExtractor() { override var mainUrl = "https://vcloud.zip" }

// 2. HubCloud
open class HubCloudExtractor : ExtractorApi() {
    override var name = "HubCloud"
    override var mainUrl = "https://hubcloud.club"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!isExtractorAllowed("vegamovies")) return
        val start = System.currentTimeMillis()
        var emittedAny = false

        val res = runCatching {
            app.get(url, headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA), timeout = 20L)
        }.getOrNull() ?: return
        val doc = Jsoup.parse(res.text)
        val header = doc.selectFirst("div.card-header")?.text()?.trim().orEmpty()
        if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(header)) return

        val generate = doc.select("a.btn, a[download]")
            .firstOrNull { it.text().contains("generate", true) }
            ?.attr("href")?.trim() ?: return
        val genUrl = if (generate.startsWith("http")) generate else safeBaseHost(res.url, mainUrl) + generate
        val genRes = runCatching {
            app.get(genUrl, headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA, "Referer" to res.url), timeout = 25L)
        }.getOrNull() ?: return

        val seen = mutableSetOf<String>()
        for (a in Jsoup.parse(genRes.text).select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) continue
            val low = a.text().lowercase()
            val h = href.lowercase()

            val (srvName, finalUrl) = when {
                low.contains("fslv2") || h.contains(".r2.cloudflarestorage.com") -> "FSLv2" to href
                low.contains("fsl") || h.contains(".r2.dev") -> "FSL" to href
                low.contains("pixelserver") || low.contains("pixeldrain") || h.contains("pixeldrain") -> {
                    val id = href.substringBefore("?").substringBefore("#").substringAfterLast("/")
                    "PixelDrain" to "${safeBaseHost(href, "https://pixeldrain.com")}/api/file/$id"
                }
                low.contains("buzzserver") -> "BuzzServer" to href
                low.contains("10gbps") || h.contains("gpdl") -> "10Gbps" to href
                else -> continue
            }

            if (seen.add(finalUrl)) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "HubCloud • $srvName",
                        url = finalUrl,
                        type = if (finalUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA)
                    }
                )
                emittedAny = true
            }
        }

        recordExtractorHealth("vegamovies", emittedAny, System.currentTimeMillis() - start)
    }
}

class HubCloudDadExtractor : HubCloudExtractor() { override var mainUrl = "https://hubcloud.dad" }
class HubCloudInkExtractor : HubCloudExtractor() { override var mainUrl = "https://hubcloud.ink" }

// 3. VegaDrive (SkyDrop API)
open class VegaDriveExtractor : ExtractorApi() {
    override var name = "VegaDrive"
    override var mainUrl = "https://vegadrive.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!isExtractorAllowed("vegamovies")) return
        val start = System.currentTimeMillis()
        val page = runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA, "Referer" to (referer ?: "https://nexdrive.beer/")),
                timeout = 20L
            )
        }.getOrNull() ?: return

        val base = safeBaseHost(page.url, mainUrl)
        val token = url.substringAfter("/s/").substringBefore("?").substringBefore("#")
            .substringAfter("/d/").substringBefore("/")
        if (token.isBlank() || token == url) return

        for (attempt in 0 until 3) {
            val res = runCatching {
                app.get(
                    "$base/download-link/$token/skydrop",
                    headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA, "Referer" to "$base/s/$token"),
                    timeout = 15L
                )
            }.getOrNull() ?: return
            val parsed = runCatching { JSONObject(res.text) }.getOrNull()
            val link = parsed?.optString("download_url").orEmpty()
            if (res.code == 200 && link.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(this.name, "V-Drive Direct", link, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA)
                    }
                )
                recordExtractorHealth("vegamovies", true, System.currentTimeMillis() - start)
                return
            }
            if (res.code != 202) return
            delay((parsed?.optInt("retry_after_ms", 2500) ?: 2500).toLong().coerceAtMost(3000L))
        }
    }
}

// 4. FastDL
open class FastDLExtractor : ExtractorApi() {
    override var name = "FastDL"
    override var mainUrl = "https://fastdl.icu"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = runCatching {
            app.get(url, headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA), timeout = 20L)
        }.getOrNull() ?: return

        val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(res.text)?.groupValues?.get(1)
            ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(res.text)?.groupValues?.get(1)
            ?: return
        val carrier = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(reurl)?.groupValues?.get(1)?.let { wrapped ->
            safeB64Decode(wrapped)
        } ?: reurl
        val encoded = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1) ?: return
        val direct = URLDecoder.decode(encoded, "UTF-8")
        if (direct.startsWith("http")) {
            callback.invoke(
                newExtractorLink(this.name, "FastDL G-Direct", direct, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA)
                }
            )
        }
    }
}

// 5. Filepress / Filebee / FPGo / DotFlix
open class FilepressExtractor : ExtractorApi() {
    override var name = "Filepress"
    override var mainUrl = "https://filebee.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""/file/([a-f0-9]{16,40})""").find(url)?.groupValues?.get(1) ?: return
        val base = "https://filebee.xyz"

        suspend fun postTask(method: String, targetId: String): Any? {
            return runCatching {
                val endpoint = if (method == "indexDownlaod" && targetId != id) "$base/api/file/downlaod2/" else "$base/api/file/downlaod/"
                val res = app.post(
                    endpoint,
                    headers = mapOf(
                        "User-Agent" to DEFAULT_MOBILE_UA,
                        "Referer" to "$base/",
                        "Origin" to base,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                    json = JSONObject()
                        .put("captchaValue", JSONObject.NULL)
                        .put("id", targetId)
                        .put("method", method)
                        .toString(),
                    timeout = 25L
                )
                if (!res.isSuccessful) return@runCatching null
                val parsed = JSONObject(res.text)
                if (!parsed.optBoolean("status")) return@runCatching null
                parsed.opt("data")
            }.getOrNull()
        }

        val share = postTask("dotFlixDownlaod", id) as? String
        if (!share.isNullOrBlank() && share.startsWith("http")) {
            val dotDirect = runCatching {
                val text = app.get(
                    share,
                    headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA, "Referer" to "https://new3.dotflix.shop/"),
                    timeout = 20L
                ).text
                val code = Regex("""btoa\('([^']+)'\)""").find(text)?.groupValues?.get(1) ?: return@runCatching null
                val obfuscated = java.util.Base64.getEncoder().encodeToString(code.toByteArray()).reversed()
                val requestId = List(13) { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                val timestamp = System.currentTimeMillis().toString()
                val extractRes = app.post(
                    "https://dotflix.fit/api/extract-download",
                    headers = mapOf(
                        "User-Agent" to DEFAULT_MOBILE_UA,
                        "Referer" to "https://new3.dotflix.shop/",
                        "Origin" to "https://new3.dotflix.shop",
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "X-Request-ID" to requestId,
                        "X-Timestamp" to timestamp
                    ),
                    json = JSONObject()
                        .put("requestId", requestId)
                        .put("timestamp", timestamp)
                        .put("data", obfuscated)
                        .toString(),
                    timeout = 25L
                )
                if (!extractRes.isSuccessful) return@runCatching null
                val parsed = JSONObject(extractRes.text)
                if (!parsed.optBoolean("success")) null else parsed.optString("downloadUrl").takeIf { it.startsWith("http") }
            }.getOrNull()

            if (dotDirect != null) {
                callback.invoke(
                    newExtractorLink(this.name, "Filepress Direct", dotDirect, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA)
                    }
                )
                return
            }
        }

        val task = postTask("indexDownlaod", id) as? String
        if (!task.isNullOrBlank() && task.matches(Regex("[a-f0-9]{16,40}"))) {
            val direct = when (val finalData = postTask("indexDownlaod", task)) {
                is String -> finalData.takeIf { it.startsWith("http") }
                is JSONArray -> (0 until finalData.length())
                    .firstNotNullOfOrNull { idx -> finalData.optString(idx).takeIf { it.startsWith("http") } }
                else -> null
            }
            if (direct != null) {
                callback.invoke(
                    newExtractorLink(this.name, "Filepress Direct", direct, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA)
                    }
                )
            }
        }
    }
}

class FilebeeExtractor : FilepressExtractor() { override var mainUrl = "https://filebee.xyz" }
class FpGoExtractor : FilepressExtractor() { override var mainUrl = "https://fpgo.xyz" }

// 6. GoFile
open class GoFileExtractor : ExtractorApi() {
    override var name = "GoFile"
    override var mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = url.substringAfterLast("/").substringBefore("#").substringBefore("?")
        if (code.isBlank()) return
        val api = "https://api.gofile.io"
        val token = runCatching {
            JSONObject(app.post("$api/accounts", timeout = 15L).text)
                .getJSONObject("data").getString("token")
        }.getOrNull() ?: return

        val wt = runCatching {
            Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                app.get("$api/dist/js/global.js", timeout = 15L).text
            )?.groupValues?.get(1)
        }.getOrNull()

        val contentUrl = if (wt.isNullOrBlank()) "$api/contents/$code?wt=$token" else "$api/contents/$code?wt=$wt"
        val data = runCatching {
            JSONObject(
                app.get(contentUrl, headers = mapOf("Authorization" to "Bearer $token"), timeout = 20L).text
            ).getJSONObject("data")
        }.getOrNull() ?: return

        val children = data.optJSONObject("children") ?: return
        var best: String? = null
        var bestSize = -1L
        for (key in children.keys()) {
            val child = children.optJSONObject(key) ?: continue
            if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(child.optString("name"))) continue
            val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
            if (child.optLong("size", 0L) > bestSize) {
                bestSize = child.optLong("size", 0L)
                best = link
            }
        }
        if (best != null) {
            callback.invoke(
                newExtractorLink(this.name, "GoFile Direct", best, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA)
                }
            )
        }
    }
}

// 7. GPDL (10Gbps Redirects)
open class GpdlExtractor : ExtractorApi() {
    override var name = "10Gbps"
    override var mainUrl = "https://gpdl.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var cur = url
        runCatching {
            for (i in 0 until 5) {
                val res = app.get(cur, headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA), allowRedirects = false, timeout = 15L)
                val loc = res.headers["location"]
                if (loc.isNullOrBlank() || res.code !in 301..308) break
                cur = if (loc.startsWith("http")) loc else safeBaseHost(cur, mainUrl) + loc
            }
        }
        for (pair in cur.substringAfter("?", "").split("&")) {
            if (pair.substringBefore("=") != "link") continue
            val value = pair.substringAfter("=")
            val direct = if (value.contains("%")) URLDecoder.decode(value, "UTF-8") else value
            if (direct.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(this.name, "10Gbps Direct", direct, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA)
                    }
                )
                return
            }
        }
    }
}

// 8. GDShine (HindMoviez Mirror API)
open class GDShineExtractor : ExtractorApi() {
    override var name = "GDShine"
    override var mainUrl = "https://gdshine.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!isExtractorAllowed("hindmoviez")) return
        val start = System.currentTimeMillis()

        val shortId = url.substringAfter("/s/").substringBefore("?").substringBefore("#")
        if (shortId.isBlank() || shortId == url) return
        val fileId = runCatching {
            val text = app.get(
                "https://gdshine.org/api/files/s/$shortId",
                headers = mapOf("Referer" to "https://gdshine.org/", "User-Agent" to DEFAULT_MOBILE_UA),
                timeout = 15L
            ).text
            JSONObject(text).optJSONObject("data")?.optString("id")
        }.getOrNull() ?: return

        val direct = runCatching {
            val text = app.get(
                "https://gdshine.org/api/gp/mirror/$fileId",
                headers = mapOf("Referer" to "https://gdshine.org/", "User-Agent" to DEFAULT_MOBILE_UA),
                timeout = 15L
            ).text
            JSONObject(text).optJSONObject("data")?.optString("url")
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: return

        callback.invoke(
            newExtractorLink(this.name, "GDShine Direct", direct, ExtractorLinkType.VIDEO) {
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to DEFAULT_MOBILE_UA)
            }
        )
        recordExtractorHealth("hindmoviez", true, System.currentTimeMillis() - start)
    }
}

// =========================================================================
// VidPrime, Nitrox, NxStreamHub, Vibuxer & Existing JWP Extractors
// =========================================================================

class VidPrimeExtractor : BaseJWPExtractor() { override var name = "VidPrime"; override var mainUrl = "https://vidprime.top" }
class NitroxExtractor : BaseJWPExtractor() { override var name = "Nitrox"; override var mainUrl = "https://wts.itsnitrox.tech" }
class NxStreamHubExtractor : BaseJWPExtractor() { override var name = "NxStreamHub"; override var mainUrl = "https://proxy.nxstreamhub.workers.dev" }
class VibuxerExtractor : BaseJWPExtractor() { override var name = "Vibuxer"; override var mainUrl = "https://vibuxer.com" }

class LuluVidExtractor : BaseJWPExtractor() { override var name = "LuluStream"; override var mainUrl = "https://luluvid.com" }
class LuluStreamAltExtractor : BaseJWPExtractor() { override var name = "LuluStream"; override var mainUrl = "https://lulustream.com" }
class LuluVdoExtractor : BaseJWPExtractor() { override var name = "LuluStream"; override var mainUrl = "https://luluvdo.com" }

class VidaraExtractor : BaseJWPExtractor() { override var name = "Vidara"; override var mainUrl = "https://vidara.to" }
class VidaraAltExtractor : BaseJWPExtractor() { override var name = "Vidara"; override var mainUrl = "https://vidara.so" }

class RabbitstreamExtractor : BaseJWPExtractor() { override var name = "Rabbitstream"; override var mainUrl = "https://rabbitstream.net" }
class UpCloudExtractor : BaseJWPExtractor() { override var name = "UpCloud"; override var mainUrl = "https://upcloud.to" }
class MegaCloudExtractor : BaseJWPExtractor() { override var name = "MegaCloud"; override var mainUrl = "https://megacloud.tv" }
class MegaCloudClubExtractor : BaseJWPExtractor() { override var name = "MegaCloud"; override var mainUrl = "https://megacloud.club" }

class StreamWishExtractor : BaseJWPExtractor() { override var name = "StreamWish"; override var mainUrl = "https://streamwish.to" }
class FilemoonExtractor : BaseJWPExtractor() { override var name = "Filemoon"; override var mainUrl = "https://filemoon.sx" }
class EarnVidsExtractor : BaseJWPExtractor() { override var name = "EarnVids"; override var mainUrl = "https://earnvids.com" }
class EarnVidsProExtractor : BaseJWPExtractor() { override var name = "EarnVids"; override var mainUrl = "https://dingtezuni.com" }
class UpBoltExtractor : BaseJWPExtractor() { override var name = "UpBolt"; override var mainUrl = "https://upbolt.to" }
class UpBoltTvExtractor : BaseJWPExtractor() { override var name = "UpBolt"; override var mainUrl = "https://upbolt.tv" }
class AbyssExtractor : BaseJWPExtractor() { override var name = "Abyss"; override var mainUrl = "https://player.abyssplayer.com" }
class AbyssCdnExtractor : BaseJWPExtractor() { override var name = "Abyss"; override var mainUrl = "https://abysscdn.com" }
class ShortIcuExtractor : BaseJWPExtractor() { override var name = "Abyss"; override var mainUrl = "https://short.icu" }
class LPlayGoExtractor : BaseJWPExtractor() { override var name = "LPlayGo"; override var mainUrl = "https://lplaygo.com" }
class VidMolyExtractor : BaseJWPExtractor() { override var name = "VidMoly"; override var mainUrl = "https://vidmoly.me" }
class SwVidMolyExtractor : BaseJWPExtractor() { override var name = "VidMoly"; override var mainUrl = "https://sw.vidmoly.me" }
class VidMolyToExtractor : BaseJWPExtractor() { override var name = "VidMoly"; override var mainUrl = "https://vidmoly.to" }
