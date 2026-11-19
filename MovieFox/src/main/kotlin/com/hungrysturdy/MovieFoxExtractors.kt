package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class DirectMediaExtractor : ExtractorApi() {

    override val name = "DirectMedia"
    override val mainUrl = "https://example.com"
    override val requiresReferer = false

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

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

        val type = when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mp4") -> ExtractorLinkType.VIDEO
            else -> return
        }

        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*"
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = if (type == ExtractorLinkType.M3U8) {
                    "Direct HLS"
                } else {
                    "Direct MP4"
                },
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

    protected val defaultUserAgent =
        "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

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
                "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
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
    }
}

class PixeldrainDevExtractor : PixeldrainExtractor() { override var mainUrl = "https://pixeldrain.dev" }

// VidPrime, Nitrox, NxStreamHub, Vibuxer
class VidPrimeExtractor : BaseJWPExtractor() { override var name = "VidPrime"; override var mainUrl = "https://vidprime.top" }
class NitroxExtractor : BaseJWPExtractor() { override var name = "Nitrox"; override var mainUrl = "https://wts.itsnitrox.tech" }
class NxStreamHubExtractor : BaseJWPExtractor() { override var name = "NxStreamHub"; override var mainUrl = "https://proxy.nxstreamhub.workers.dev" }
class VibuxerExtractor : BaseJWPExtractor() { override var name = "Vibuxer"; override var mainUrl = "https://vibuxer.com" }

// LuluStream, Vidara, Rabbitstream, UpCloud, MegaCloud & Others
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
