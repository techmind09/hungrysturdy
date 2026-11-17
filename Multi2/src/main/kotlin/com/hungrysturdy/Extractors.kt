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

        val streamRegex = Regex("""["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']""")
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
                    this.httpHeaders = headers
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

// 1. LuluStream
class LuluVidExtractor : BaseJWPExtractor() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvid.com"
}

class LuluStreamAltExtractor : BaseJWPExtractor() {
    override var name = "LuluStream"
    override var mainUrl = "https://lulustream.com"
}

// 2. EarnVids
class EarnVidsExtractor : BaseJWPExtractor() {
    override var name = "EarnVids"
    override var mainUrl = "https://earnvids.com"
}

class EarnVidsProExtractor : BaseJWPExtractor() {
    override var name = "EarnVids"
    override var mainUrl = "https://dingtezuni.com"
}

// 3. Vidara
class VidaraExtractor : BaseJWPExtractor() {
    override var name = "Vidara"
    override var mainUrl = "https://vidara.to"
}

class VidaraAltExtractor : BaseJWPExtractor() {
    override var name = "Vidara"
    override var mainUrl = "https://vidara.so"
}

// 4. Byse
class ByseExtractor : BaseJWPExtractor() {
    override var name = "Byse"
    override var mainUrl = "https://bysebuho.com"
}

class ByseAltExtractor : BaseJWPExtractor() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
}

// 5. UpBolt
class UpBoltExtractor : BaseJWPExtractor() {
    override var name = "UpBolt"
    override var mainUrl = "https://upbolt.to"
}

// 6. Abyss
class AbyssExtractor : BaseJWPExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://player.abyssplayer.com"
}

class ShortIcuExtractor : BaseJWPExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://short.icu"
}

// 7. LPlayGo
class LPlayGoExtractor : BaseJWPExtractor() {
    override var name = "LPlayGo"
    override var mainUrl = "https://lplaygo.com"
}
