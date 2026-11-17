package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

open class BaseJWPExtractor : ExtractorApi() {
    override var name = "BaseJWP"
    override var mainUrl = "https://example.com"
    override val requiresReferer = true

    private fun getOrigin(targetUrl: String): String {
        return try {
            val uri = URI(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            mainUrl
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val userAgent = "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        val pageReferer = referer ?: "$mainUrl/"
        val pageHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to pageReferer
        )

        val response = app.get(url, referer = pageReferer, headers = pageHeaders).text
        val unpacked = JsUnpacker(response).unpack() ?: response

        val combinedBuilder = StringBuilder("$response\n$unpacked")
        Regex("""["']([A-Za-z0-9+/=]{60,})["']""").findAll(response).forEach { match ->
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
            """["'](https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)["']""",
            RegexOption.IGNORE_CASE
        )
        val foundUrls = streamRegex.findAll(combinedHtml)
            .map { it.groupValues[1].replace("\\/", "/") }
            .distinct()
            .toList()

        val embedOrigin = getOrigin(url)
        val streamHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to url,
            "Origin" to embedOrigin
        )

        for (streamUrl in foundUrls) {
            if (streamUrl.contains("preview") || streamUrl.contains("thumb")) continue

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = streamUrl
                ) {
                    this.referer = url
                    this.quality = 0
                    this.headers = streamHeaders
                }
            )
        }

        val subRegex = Regex("""["'](https?://[^"'\s<>]+\.(?:vtt|srt)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
        subRegex.findAll(combinedHtml).map { it.groupValues[1].replace("\\/", "/") }.distinct().forEach { subUrl ->
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = "Hindi / English",
                    url = subUrl
                )
            )
        }
    }
}

// Dedicated StreamTape Extractor (Parses robotlink JS obfuscation across all mirror domains)
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
        val userAgent = "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        val normalizedUrl = url
            .replace(mainUrl, "https://streamtape.com", ignoreCase = true)
            .replace("/v/", "/e/", ignoreCase = true)
        val pageReferer = referer ?: "https://hindidrama.net/"

        val html = app.get(
            normalizedUrl,
            referer = pageReferer,
            headers = mapOf(
                "User-Agent" to userAgent,
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
            if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            } else if (finalUrl.startsWith("/")) {
                finalUrl = "https:/$finalUrl"
            }
            if (!finalUrl.contains("stream=1")) {
                finalUrl += if (finalUrl.contains("?")) "&stream=1" else "?stream=1"
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalUrl
                ) {
                    this.referer = normalizedUrl
                    this.quality = 0
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to normalizedUrl
                    )
                }
            )
        }
    }
}

class StreamTapeToExtractor : StreamTapeExtractor() {
    override var mainUrl = "https://streamtape.to"
}

class StreamTapeNetExtractor : StreamTapeExtractor() {
    override var mainUrl = "https://streamtape.net"
}

class StreamTapeXyzExtractor : StreamTapeExtractor() {
    override var mainUrl = "https://streamtape.xyz"
}

class ShaveTapeExtractor : StreamTapeExtractor() {
    override var mainUrl = "https://shavetape.cash"
}

class StrCloudExtractor : StreamTapeExtractor() {
    override var mainUrl = "https://strcloud.link"
}

class StrTapeTechExtractor : StreamTapeExtractor() {
    override var mainUrl = "https://strtape.tech"
}

class StapeFunExtractor : StreamTapeExtractor() {
    override var mainUrl = "https://stape.fun"
}

// LuluStream
class LuluVidExtractor : BaseJWPExtractor() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvid.com"
}

class LuluStreamAltExtractor : BaseJWPExtractor() {
    override var name = "LuluStream"
    override var mainUrl = "https://lulustream.com"
}

class LuluVdoExtractor : BaseJWPExtractor() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvdo.com"
}

// EarnVids
class EarnVidsExtractor : BaseJWPExtractor() {
    override var name = "EarnVids"
    override var mainUrl = "https://earnvids.com"
}

class EarnVidsProExtractor : BaseJWPExtractor() {
    override var name = "EarnVids"
    override var mainUrl = "https://dingtezuni.com"
}

// Vidara
class VidaraExtractor : BaseJWPExtractor() {
    override var name = "Vidara"
    override var mainUrl = "https://vidara.to"
}

class VidaraAltExtractor : BaseJWPExtractor() {
    override var name = "Vidara"
    override var mainUrl = "https://vidara.so"
}

// UpBolt
class UpBoltExtractor : BaseJWPExtractor() {
    override var name = "UpBolt"
    override var mainUrl = "https://upbolt.to"
}

class UpBoltTvExtractor : BaseJWPExtractor() {
    override var name = "UpBolt"
    override var mainUrl = "https://upbolt.tv"
}

// Abyss
class AbyssExtractor : BaseJWPExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://player.abyssplayer.com"
}

class AbyssCdnExtractor : BaseJWPExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://abysscdn.com"
}

class ShortIcuExtractor : BaseJWPExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://short.icu"
}

// LPlayGo
class LPlayGoExtractor : BaseJWPExtractor() {
    override var name = "LPlayGo"
    override var mainUrl = "https://lplaygo.com"
}

// VidMoly
class VidMolyExtractor : BaseJWPExtractor() {
    override var name = "VidMoly"
    override var mainUrl = "https://vidmoly.me"
}

class SwVidMolyExtractor : BaseJWPExtractor() {
    override var name = "VidMoly"
    override var mainUrl = "https://sw.vidmoly.me"
}

class VidMolyToExtractor : BaseJWPExtractor() {
    override var name = "VidMoly"
    override var mainUrl = "https://vidmoly.to"
}
