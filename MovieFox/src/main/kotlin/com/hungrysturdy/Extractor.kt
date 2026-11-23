package com.hungrysturdy

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

private fun extContext(): Context? =
    MovieFoxSettingsDialog.currentContext() ?: AcraApplication.context

private fun isExtractorAllowed(key: String): Boolean {
    val ctx = extContext() ?: return true
    return MovieFoxSettingsDialog.isExtractorEnabled(ctx, key)
}

private fun buildExtractorHeaders(
    url: String,
    referer: String?,
    origin: String? = null,
    extra: Map<String, String> = emptyMap()
): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    headers["User-Agent"] = MovieFoxSettingsDialog.CF_BYPASS_UA
    headers["Accept"] = "*/*"
    referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
    origin?.takeIf { it.isNotBlank() }?.let { headers["Origin"] = it }
    MovieFoxSettingsDialog.getCookieForDomain(extContext(), url)?.takeIf { it.isNotBlank() }?.let {
        headers["Cookie"] = it
    }
    headers.putAll(extra)
    return headers
}

private fun logExtractorError(source: String, url: String, err: Throwable, extra: String = "") {
    MovieFoxSettingsDialog.logSiteError(source, url, err, extra)
}

open class BaseJWPExtractor : ExtractorApi() {
    override var name = "BaseJWP"
    override var mainUrl = "https://example.com"
    override val requiresReferer = true

    protected open val prefKey: String = "basejwp"

    protected val defaultUserAgent: String
        get() = MovieFoxSettingsDialog.CF_BYPASS_UA

    protected fun getOrigin(targetUrl: String): String {
        return try {
            val uri = URI(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            mainUrl
        }
    }

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
        if (!isExtractorAllowed(prefKey) || !isExtractorAllowed("basejwp")) return

        val pageReferer = referer ?: "$mainUrl/"
        val embedOrigin = getOrigin(url)
        val emittedUrls = mutableSetOf<String>()

        try {
            val pageHeaders = buildExtractorHeaders(url = url, referer = pageReferer, origin = embedOrigin)
            val responseObj = app.get(url, referer = pageReferer, headers = pageHeaders)
            val response = responseObj.text

            val streamHeaders = buildExtractorHeaders(url = url, referer = url, origin = embedOrigin)

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
                    subtitleCallback.invoke(SubtitleFile(lang = "Hindi / English", url = subUrl))
                }
        } catch (e: Exception) {
            logExtractorError(this.name, url, e, "BaseJWP getUrl failed")
        }
    }
}

// ==========================================
// Google Drive (GDrive) Extractor
// ==========================================
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
        if (!isExtractorAllowed("gdrive")) return
        try {
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
        } catch (e: Exception) {
            logExtractorError(this.name, url, e, "GDrive getUrl failed")
        }
    }
}

// ==========================================
// StreamTape (All Unique Mirrors)
// ==========================================
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
        if (!isExtractorAllowed("streamtape")) return
        try {
            val normalizedUrl = url
                .replace(mainUrl, "https://streamtape.com", ignoreCase = true)
                .replace("/v/", "/e/", ignoreCase = true)
            val pageReferer = referer ?: "https://hindidrama.net/"

            val html = app.get(
                normalizedUrl,
                referer = pageReferer,
                headers = buildExtractorHeaders(normalizedUrl, pageReferer)
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
                        this.headers = buildExtractorHeaders(finalUrl, normalizedUrl)
                    }
                )
            }
        } catch (e: Exception) {
            logExtractorError(this.name, url, e, "StreamTape getUrl failed")
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

// ==========================================
// Voe (All Unique Mirrors)
// ==========================================
open class VoeExtractor : BaseJWPExtractor() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val prefKey = "voe"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!isExtractorAllowed("voe")) return
        val pageReferer = referer ?: "$mainUrl/"
        try {
            val response = app.get(url, referer = pageReferer, headers = buildExtractorHeaders(url, pageReferer)).text
            val redirectUrl = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""")
                .find(response)?.groupValues?.get(1)
            val html = if (!redirectUrl.isNullOrBlank()) {
                app.get(redirectUrl, referer = url, headers = buildExtractorHeaders(redirectUrl, url)).text
            } else {
                response
            }

            val activeUrl = redirectUrl ?: url
            val streamHeaders = buildExtractorHeaders(url = activeUrl, referer = activeUrl, origin = getOrigin(activeUrl))

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
        } catch (e: Exception) {
            logExtractorError(this.name, url, e, "Voe getUrl failed")
        }
    }
}

class VoeToExtractor : VoeExtractor() { override var mainUrl = "https://voe.to" }
class VoeCoExtractor : VoeExtractor() { override var mainUrl = "https://voe.co" }

// ==========================================
// Pixeldrain (.com & .dev)
// ==========================================
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
        try {
            val fileId = Regex("""pixeldrain\.[a-z]+/(?:u|api/file|l)/([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1) ?: return

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
        } catch (e: Exception) {
            logExtractorError(this.name, url, e, "Pixeldrain getUrl failed")
        }
    }
}

class PixeldrainDevExtractor : PixeldrainExtractor() { override var mainUrl = "https://pixeldrain.dev" }

// ==========================================
// VidPrime, Nitrox, NxStreamHub, Vibuxer & Embeds
// ==========================================
class VidPrimeExtractor : BaseJWPExtractor() { override var name = "VidPrime"; override var mainUrl = "https://vidprime.top"; override val prefKey = "jwp_hosts" }
class NitroxExtractor : BaseJWPExtractor() { override var name = "Nitrox"; override var mainUrl = "https://wts.itsnitrox.tech"; override val prefKey = "itsnitrox" }
class NxStreamHubExtractor : BaseJWPExtractor() { override var name = "NxStreamHub"; override var mainUrl = "https://proxy.nxstreamhub.workers.dev"; override val prefKey = "nxstreamhub" }
class VibuxerExtractor : BaseJWPExtractor() { override var name = "Vibuxer"; override var mainUrl = "https://vibuxer.com"; override val prefKey = "vibuxer" }

class LuluVidExtractor : BaseJWPExtractor() { override var name = "LuluStream"; override var mainUrl = "https://luluvid.com"; override val prefKey = "jwp_embeds" }
class LuluStreamAltExtractor : BaseJWPExtractor() { override var name = "LuluStream"; override var mainUrl = "https://lulustream.com"; override val prefKey = "jwp_embeds" }
class LuluVdoExtractor : BaseJWPExtractor() { override var name = "LuluStream"; override var mainUrl = "https://luluvdo.com"; override val prefKey = "jwp_embeds" }
class VidaraExtractor : BaseJWPExtractor() { override var name = "Vidara"; override var mainUrl = "https://vidara.to"; override val prefKey = "jwp_embeds" }
class VidaraAltExtractor : BaseJWPExtractor() { override var name = "Vidara"; override var mainUrl = "https://vidara.so"; override val prefKey = "jwp_embeds" }
class RabbitstreamExtractor : BaseJWPExtractor() { override var name = "Rabbitstream"; override var mainUrl = "https://rabbitstream.net"; override val prefKey = "jwp_embeds" }
class UpCloudExtractor : BaseJWPExtractor() { override var name = "UpCloud"; override var mainUrl = "https://upcloud.to"; override val prefKey = "jwp_embeds" }
class MegaCloudExtractor : BaseJWPExtractor() { override var name = "MegaCloud"; override var mainUrl = "https://megacloud.tv"; override val prefKey = "jwp_embeds" }
class MegaCloudClubExtractor : BaseJWPExtractor() { override var name = "MegaCloud"; override var mainUrl = "https://megacloud.club"; override val prefKey = "jwp_embeds" }
class StreamWishExtractor : BaseJWPExtractor() { override var name = "StreamWish"; override var mainUrl = "https://streamwish.to"; override val prefKey = "jwp_embeds" }
class FilemoonExtractor : BaseJWPExtractor() { override var name = "Filemoon"; override var mainUrl = "https://filemoon.sx"; override val prefKey = "jwp_embeds" }
class EarnVidsExtractor : BaseJWPExtractor() { override var name = "EarnVids"; override var mainUrl = "https://earnvids.com"; override val prefKey = "jwp_embeds" }
class EarnVidsProExtractor : BaseJWPExtractor() { override var name = "EarnVids"; override var mainUrl = "https://dingtezuni.com"; override val prefKey = "jwp_embeds" }
class UpBoltExtractor : BaseJWPExtractor() { override var name = "UpBolt"; override var mainUrl = "https://upbolt.to"; override val prefKey = "jwp_embeds" }
class UpBoltTvExtractor : BaseJWPExtractor() { override var name = "UpBolt"; override var mainUrl = "https://upbolt.tv"; override val prefKey = "jwp_embeds" }
class AbyssExtractor : BaseJWPExtractor() { override var name = "Abyss"; override var mainUrl = "https://player.abyssplayer.com"; override val prefKey = "jwp_embeds" }
class AbyssCdnExtractor : BaseJWPExtractor() { override var name = "Abyss"; override var mainUrl = "https://abysscdn.com"; override val prefKey = "jwp_embeds" }
class ShortIcuExtractor : BaseJWPExtractor() { override var name = "Abyss"; override var mainUrl = "https://short.icu"; override val prefKey = "jwp_embeds" }
class LPlayGoExtractor : BaseJWPExtractor() { override var name = "LPlayGo"; override var mainUrl = "https://lplaygo.com"; override val prefKey = "jwp_embeds" }
class VidMolyExtractor : BaseJWPExtractor() { override var name = "VidMoly"; override var mainUrl = "https://vidmoly.me"; override val prefKey = "jwp_embeds" }
class SwVidMolyExtractor : BaseJWPExtractor() { override var name = "VidMoly"; override var mainUrl = "https://sw.vidmoly.me"; override val prefKey = "jwp_embeds" }
class VidMolyToExtractor : BaseJWPExtractor() { override var name = "VidMoly"; override var mainUrl = "https://vidmoly.to"; override val prefKey = "jwp_embeds" }

// =========================================================================
// HubCloud / GDFlix / FastDL / BuzzServer / HBPlay / GoFlix Extractors
// =========================================================================
private val katCfKiller by lazy { CloudflareKiller() }

private val katBrowserHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Cookie" to "xla=s4t"
)

private fun katGetBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) {
        url
    }
}

private fun katGetIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lowerStr = str.lowercase()
    return when {
        lowerStr.contains("8k") -> 4320
        lowerStr.contains("4k") -> 2160
        lowerStr.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

private suspend fun katGetLatestBaseUrl(baseUrl: String, source: String): String {
    return try {
        val rawJson = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").text.trim()
        if (rawJson.startsWith("{")) {
            val mappedUrl = JSONObject(rawJson).optString(source, "")
            if (mappedUrl.isNotBlank()) return mappedUrl
        }
        baseUrl
    } catch (_: Exception) {
        baseUrl
    }
}

open class KatFastDlServer : ExtractorApi() {
    override val name = "fastdlserver"
    override var mainUrl = "https://fastdlserver.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val location = app.get(url, allowRedirects = false).headers["location"]
        if (location != null) {
            val unwrapped = if (location.contains("url=")) {
                runCatching { URLDecoder.decode(location.substringAfter("url=").substringBefore("&"), "UTF-8") }
                    .getOrDefault(location.substringAfter("url=").substringBefore("&"))
            } else location
            loadExtractor(unwrapped, "", subtitleCallback, callback)
        }
    }
}

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
        if (!isExtractorAllowed("hubcloud")) return
        KatServerLogic.extractHubCloudServers(url, referer, callback)
    }
}

class HubCloudDrive : KatHubCloudExtractor() { override val mainUrl = "https://hubcloud.dad" }
class HubCloudClub : KatHubCloudExtractor() { override val mainUrl = "https://hubcloud.club" }
class HubCloudOne : KatHubCloudExtractor() { override val mainUrl = "https://hubcloud.one" }
class GamerXYTExtractor : KatHubCloudExtractor() { override val name = "GamerXYT"; override val mainUrl = "https://gamerxyt.com" }

open class KatGDFlixExtractor : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!isExtractorAllowed("gdflix")) return
        KatServerLogic.extractGdFlixServers(name, url, referer, subtitleCallback, callback)
    }
}

class GDLink : KatGDFlixExtractor() { override var mainUrl = "https://gdlink.*" }
class GDFlixApp : KatGDFlixExtractor() { override var mainUrl = "https://new.gdflix.*" }
class GdFlix1 : KatGDFlixExtractor() { override var mainUrl = "https://new1.gdflix.*" }
class GdFlix2 : KatGDFlixExtractor() { override var mainUrl = "https://*.gdflix.*" }
class GDFlixNet : KatGDFlixExtractor() { override var mainUrl = "https://new15.gdflix.*" }
class GDFlixCfd : KatGDFlixExtractor() { override var mainUrl = "https://gdflix.cfd" }
class GDFlixdad : KatGDFlixExtractor() { override var mainUrl = "https://gdflix.dad" }
class GDFlixNew9 : KatGDFlixExtractor() { override var mainUrl = "https://new9.gdflix.io" }
class GDFlixNew10 : KatGDFlixExtractor() { override var mainUrl = "https://new10.gdflix.io" }

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
        if (!isExtractorAllowed("pixeldrain")) return
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
        if (!isExtractorAllowed("buzz_hb_go")) return
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
        if (!isExtractorAllowed("buzz_hb_go")) return
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
        if (!isExtractorAllowed("buzz_hb_go")) return
        KatServerLogic.extractGoFlixMirror(url, getQualityFromName(referer.orEmpty()), subtitleCallback, callback)
    }
}

object KatServerLogic {

    private fun cleanUrl(raw: String): String {
        var cleaned = raw.replace("&amp;", "&").replace("\\/", "/").trim()
        if (cleaned.contains("fastdl-one.pages.dev") && cleaned.contains("url=")) {
            cleaned = cleaned.substringAfter("url=").substringBefore("&")
            runCatching { cleaned = URLDecoder.decode(cleaned, "UTF-8") }
        }
        return cleaned
    }

    private suspend fun parseJsonResponse(
        respText: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!respText.startsWith("{") && !respText.startsWith("[")) return false
        try {
            val uFieldMatch = Regex(""""(?:u|url|download_url|link)"\s*:\s*"(https?://[^"]+)"""").find(respText)
            if (uFieldMatch != null) {
                val foundUrl = cleanUrl(uFieldMatch.groupValues[1])
                when {
                    foundUrl.contains("pixeldrain") -> extractPixelDrain(foundUrl, quality, callback)
                    foundUrl.contains("hbplay.pages.dev") -> extractHbPlay(foundUrl, quality, callback)
                    foundUrl.contains("fuckingfast.net") -> extractFuckingFast(foundUrl, quality, callback)
                    else -> {
                        callback.invoke(
                            newExtractorLink("GDFlix", "GDFlix [JSON Direct]", foundUrl, ExtractorLinkType.VIDEO) {
                                this.referer = ""
                                this.quality = quality
                            }
                        )
                    }
                }
                return true
            }

            val urlMatches = Regex("https?://[^\\s\"'<>]+").findAll(respText)
            var extracted = false
            for (m in urlMatches) {
                val foundUrl = cleanUrl(m.value)
                if (foundUrl.startsWith("http") && !foundUrl.contains("gdflix") && !foundUrl.contains("cryptonewz.online") && !foundUrl.contains("t.me")) {
                    when {
                        foundUrl.contains("pixeldrain") -> { extractPixelDrain(foundUrl, quality, callback); extracted = true }
                        foundUrl.contains("fuckingfast") -> { extractFuckingFast(foundUrl, quality, callback); extracted = true }
                        else -> {
                            callback.invoke(
                                newExtractorLink("GDFlix", "GDFlix [Task Direct]", foundUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = ""
                                    this.quality = quality
                                }
                            )
                            extracted = true
                        }
                    }
                }
            }
            return extracted
        } catch (_: Exception) {
            return false
        }
    }

    suspend fun extractHubCloudServers(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanedInputUrl = cleanUrl(url)
            val response = app.get(cleanedInputUrl, headers = katBrowserHeaders, referer = referer, interceptor = katCfKiller)
            val respText = response.text.trim()
            if (parseJsonResponse(respText, Qualities.Unknown.value, callback)) return

            val doc = response.document
            val currentUrl = response.url.ifEmpty { cleanedInputUrl }

            val headerText = "${doc.title()} ${doc.selectFirst("div.card-header, h5, h6")?.text().orEmpty()} ${referer.orEmpty()}"
            val detectedQuality = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(headerText)?.groupValues?.get(1)
                ?.let { getQualityFromName(it) } ?: Qualities.Unknown.value

            val gamerLinkRaw = if (currentUrl.contains("hubcloud.php")) {
                currentUrl
            } else {
                doc.selectFirst("a#download, a[href*='hubcloud.php']")?.attr("href")
                    ?: Regex("""(?:href|url)\s*=\s*['"](https?://[^'"]*hubcloud\.php[^'"]*)['"]""")
                        .find(respText)?.groupValues?.get(1)
            }

            val gamerDoc = if (!gamerLinkRaw.isNullOrBlank()) {
                val finalGamerUrl = cleanUrl(gamerLinkRaw)
                val gResp = app.get(finalGamerUrl, headers = katBrowserHeaders, referer = currentUrl, interceptor = katCfKiller)
                if (parseJsonResponse(gResp.text.trim(), detectedQuality, callback)) return
                gResp.document
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

            gamerDoc.select("a#fuckingfast, a[href*='fuckingfast.net'], a:contains(Buzz Server)").forEach { a ->
                val buzzUrl = cleanUrl(a.attr("href"))
                if (buzzUrl.startsWith("http")) {
                    extractFuckingFast(buzzUrl, detectedQuality, callback)
                }
            }
        } catch (_: Exception) {
        }
    }

    suspend fun extractGdFlixServers(
        sourceName: String = "GDFlix",
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            var baseUrl = katGetBaseUrl(cleanUrl(url))
            val latestBaseUrl = katGetLatestBaseUrl(baseUrl, "gdflix")

            var newUrl = cleanUrl(url)
            if (baseUrl != latestBaseUrl) {
                newUrl = newUrl.replace(baseUrl, latestBaseUrl)
                baseUrl = latestBaseUrl
            }

            val resp = app.get(newUrl, headers = katBrowserHeaders, referer = referer, interceptor = katCfKiller)
            val respText = resp.text.trim()
            if (parseJsonResponse(respText, Qualities.Unknown.value, callback)) return

            val document = resp.document
            val activeOrigin = katGetBaseUrl(resp.url.ifEmpty { newUrl })

            val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").trim()
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").trim()

            val detectedQuality = katGetIndexQuality(fileName).takeIf { it != Qualities.Unknown.value }
                ?: Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE)
                    .find("${document.title()} ${document.text()} ${referer.orEmpty()}")
                    ?.groupValues?.get(1)?.let { getQualityFromName(it) }
                ?: Qualities.Unknown.value

            val labelSuffix = if (fileName.isNotBlank() || fileSize.isNotBlank()) " ${fileName}[${fileSize}]" else ""

            suspend fun emitGdLink(rawLink: String, server: String = "") {
                val cleaned = cleanUrl(rawLink)
                if (cleaned.isBlank() || !cleaned.startsWith("http")) return
                callback.invoke(
                    newExtractorLink(
                        source = "$sourceName$server",
                        name = "$sourceName$server$labelSuffix",
                        url = cleaned,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = detectedQuality
                        this.referer = if (cleaned.contains("googleusercontent.com") || cleaned.contains("workers.dev")) "" else (resp.url.ifEmpty { newUrl })
                    }
                )
            }

            for (anchor in document.select("div.text-center a")) {
                val text = anchor.select("a").text().ifEmpty { anchor.text() }.trim()
                val link = cleanUrl(anchor.attr("href"))
                if (link.isBlank()) continue

                val resolvedLink = if (link.startsWith("http")) link else {
                    val root = if (activeOrigin.startsWith("http")) activeOrigin else baseUrl
                    "$root${if (link.startsWith("/")) "" else "/"}$link"
                }

                when {
                    text.contains("FSL V2", true) -> emitGdLink(resolvedLink, " [FSL V2]")
                    text.contains("DIRECT DL", true) || text.contains("DIRECT SERVER", true) -> emitGdLink(resolvedLink, " [Direct]")
                    text.contains("CLOUD DOWNLOAD [R2]", true) -> emitGdLink(resolvedLink, " [Cloud]")
                    text.contains("GD Index", true) -> {
                        for (cfType in listOf(1, 2)) {
                            runCatching {
                                val separator = if (resolvedLink.contains("?")) "&" else "?"
                                app.get(resolvedLink + "${separator}type=$cfType", headers = katBrowserHeaders, interceptor = katCfKiller)
                                    .document.select("a.btn-success").forEach { btn -> emitGdLink(btn.attr("href"), " [CF]") }
                            }
                        }
                    }
                    text.contains("FAST CLOUD", true) -> {
                        runCatching {
                            val cloudResp = app.get(resolvedLink, headers = katBrowserHeaders, referer = newUrl, interceptor = katCfKiller)
                            val cloudRespText = cloudResp.text.trim()
                            if (!parseJsonResponse(cloudRespText, detectedQuality, callback)) {
                                val dlink = cloudResp.document.select("div.card-body a").attr("href")
                                if (dlink.isNotBlank()) emitGdLink(dlink, " [FAST CLOUD]")
                                else extractGdFlixCloudSubpage(resolvedLink, newUrl, detectedQuality, callback)
                            }
                        }
                    }
                    resolvedLink.contains("pixeldra", true) -> {
                        val baseUrlLink = katGetBaseUrl(resolvedLink)
                        val finalURL = if (resolvedLink.contains("download", true)) resolvedLink
                        else "$baseUrlLink/api/file/${resolvedLink.substringAfterLast("/").substringBefore("?")}?download"
                        emitGdLink(finalURL, " [Pixeldrain]")
                    }
                    text.contains("Instant DL", true) -> {
                        runCatching {
                            val instantLoc = app.get(resolvedLink, headers = katBrowserHeaders, allowRedirects = false).headers["location"]
                            val instantLink = if (!instantLoc.isNullOrBlank()) {
                                if (instantLoc.contains("url=")) {
                                    runCatching { URLDecoder.decode(instantLoc.substringAfter("url=").substringBefore("&"), "UTF-8") }
                                        .getOrDefault(instantLoc.substringAfter("url=").substringBefore("&"))
                                } else instantLoc
                            } else ""
                            if (instantLink.isNotBlank()) emitGdLink(instantLink, " [Instant Download]")
                            else resolveInstantDl(resolvedLink, newUrl, detectedQuality, callback)
                        }
                    }
                    text.contains("GoFile", true) -> {
                        runCatching {
                            app.get(resolvedLink, headers = katBrowserHeaders, interceptor = katCfKiller).document
                                .select(".row .row a").forEach { gofileAnchor ->
                                    val gLink = cleanUrl(gofileAnchor.attr("href"))
                                    if (gLink.contains("gofile", true)) loadExtractor(gLink, "", subtitleCallback, callback)
                                }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun resolveInstantDl(instantUrl: String, refererUrl: String, quality: Int, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val resp = app.get(instantUrl, headers = katBrowserHeaders, referer = refererUrl, interceptor = katCfKiller)
            if (parseJsonResponse(resp.text.trim(), quality, callback)) return
            val finalPageUrl = cleanUrl(resp.url.ifEmpty { instantUrl })
            if (finalPageUrl.contains("googleusercontent.com") || finalPageUrl.contains("r2.cloudflarestorage.com") || finalPageUrl.contains("workers.dev")) {
                callback.invoke(newExtractorLink("GDFlix", "GDFlix [Instant DL 10Gbps]", finalPageUrl, ExtractorLinkType.VIDEO) {
                    this.referer = ""; this.quality = quality
                })
                return
            }
            val directGoogleUrl = resp.document.selectFirst("a[href*='googleusercontent.com'], a:contains(Download Here)")?.attr("href")
                ?: Regex("""https?://[^\s"'<>\\]*googleusercontent\.com/[^\s"'<>\\]+""").find(resp.text)?.value
            if (!directGoogleUrl.isNullOrBlank()) {
                callback.invoke(newExtractorLink("GDFlix", "GDFlix [Instant DL 10Gbps]", cleanUrl(directGoogleUrl), ExtractorLinkType.VIDEO) {
                    this.referer = ""; this.quality = quality
                })
            }
        }
    }

    private suspend fun extractGdFlixCloudSubpage(cloudUrl: String, refererUrl: String, quality: Int, callback: (ExtractorLink) -> Unit) {
        runCatching {
            var resp = app.get(cloudUrl, headers = katBrowserHeaders, referer = refererUrl, interceptor = katCfKiller)
            if (parseJsonResponse(resp.text.trim(), quality, callback)) return
            val emitted = mutableSetOf<String>()
            resp.document.select("a[href*='workers.dev'], a:contains(CLOUD RESUME DOWNLOAD), a:contains(Download)").forEach { a ->
                val workersUrl = cleanUrl(a.attr("href"))
                if (workersUrl.startsWith("http") && !workersUrl.contains("gdflix") && emitted.add(workersUrl)) {
                    callback.invoke(newExtractorLink("GDFlix", "GDFlix [Cloud Resume]", workersUrl, ExtractorLinkType.VIDEO) {
                        this.referer = ""; this.quality = quality
                    })
                }
            }
        }
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
            runCatching {
                val decodedBytes = runCatching { Base64.decode(encodedU, Base64.DEFAULT) }
                    .getOrElse { Base64.decode(encodedU, Base64.URL_SAFE) }
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
            }
        }
    }

    suspend fun extractFuckingFast(url: String, quality: Int, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val cleaned = cleanUrl(url)
            val responseText = app.get(cleaned, headers = katBrowserHeaders, interceptor = katCfKiller).text
            val directLink = Regex("""window\.open\(["'](https?://fuckingfast\.net/dl/[^"']+)["']\)""")
                .find(responseText)?.groupValues?.get(1)
                ?: Regex("""["'](https?://fuckingfast\.net/dl/[^"']+)["']""").find(responseText)?.groupValues?.get(1)
            if (!directLink.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink("BuzzServer", "HubCloud [Buzz Server]", cleanUrl(directLink), ExtractorLinkType.VIDEO) {
                        this.referer = cleaned
                        this.quality = quality
                    }
                )
            }
        }
    }

    suspend fun extractGoFlixMirror(
        url: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
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
        }
    }
}

