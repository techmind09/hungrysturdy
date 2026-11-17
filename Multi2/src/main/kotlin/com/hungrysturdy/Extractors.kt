package com.hungrysturdy

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink

open class BaseJWPExtractor : ExtractorApi() {
    override var name = "BaseJWP"
    override var mainUrl = "https://example.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractedReferer = referer ?: "$mainUrl/"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
            "Referer" to extractedReferer,
            "Origin" to extractedReferer.substringBeforeLast("/")
        )

        val response = app.get(url, referer = extractedReferer, headers = headers).text
        val unpacked = JsUnpacker(response).unpack() ?: response
        val combinedHtml = "$response\n$unpacked"

        val streamRegex = Regex(
            """["'](https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)["']""",
            RegexOption.IGNORE_CASE
        )
        val foundUrls = streamRegex.findAll(combinedHtml)
            .map { it.groupValues[1].replace("\\/", "/") }
            .distinct()
            .toList()

        for (streamUrl in foundUrls) {
            if (streamUrl.contains("preview") || streamUrl.contains("thumb")) continue

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = streamUrl
                ) {
                    this.referer = extractedReferer
                    this.quality = 0
                    this.headers = headers
                }
            )
        }

        val subRegex = Regex("""["'](https?://[^"'\s]+\.(?:vtt|srt)[^"'\s]*)["']""")
        subRegex.findAll(combinedHtml).map { it.groupValues[1] }.distinct().forEach { subUrl ->
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = "Hindi / English",
                    url = subUrl
                )
            )
        }
    }
}

class LuluVidExtractor : BaseJWPExtractor() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvid.com"
}

class LuluStreamAltExtractor : BaseJWPExtractor() {
    override var name = "LuluStream"
    override var mainUrl = "https://lulustream.com"
}

class EarnVidsExtractor : BaseJWPExtractor() {
    override var name = "EarnVids"
    override var mainUrl = "https://earnvids.com"
}

class EarnVidsProExtractor : BaseJWPExtractor() {
    override var name = "EarnVids"
    override var mainUrl = "https://dingtezuni.com"
}

class VidaraExtractor : BaseJWPExtractor() {
    override var name = "Vidara"
    override var mainUrl = "https://vidara.to"
}

class VidaraAltExtractor : BaseJWPExtractor() {
    override var name = "Vidara"
    override var mainUrl = "https://s1q2105.com"
}

class UpBoltExtractor : BaseJWPExtractor() {
    override var name = "UpBolt"
    override var mainUrl = "https://upbolt.to"
}

class AbyssExtractor : BaseJWPExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://player.abyssplayer.com"
}

class ShortIcuExtractor : BaseJWPExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://short.icu"
}

class LPlayGoExtractor : BaseJWPExtractor() {
    override var name = "LPlayGo"
    override var mainUrl = "https://lplaygo.com"
}

class VidMolyExtractor : BaseJWPExtractor() {
    override var name = "VidMoly"
    override var mainUrl = "https://vidmoly.me"
}

class StreamTapeExtractor : BaseJWPExtractor() {
    override var name = "StreamTape"
    override var mainUrl = "https://streamtape.com"
}

class SprintCDNExtractor : BaseJWPExtractor() {
    override var name = "SprintCDN"
    override var mainUrl = "https://edge1-moscow-sprintcdn.owphbf24.com"
}

class VMPXExtractor : BaseJWPExtractor() {
    override var name = "VMPX"
    override var mainUrl = "https://vmpx.online"
}
