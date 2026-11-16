package com.hungrysturdy

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Referer" to (referer ?: "$mainUrl/")
        )

        val response = app.get(url, referer = referer ?: "$mainUrl/", headers = headers).text

        // Agar script packed (eval) hai toh unpack karein
        val unpacked = JsUnpacker(response).unpack() ?: response
        val combinedHtml = "$response\n$unpacked"

        // M3U8 ya MP4 links extract karein
        val streamRegex = Regex("""["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']""")
        val foundUrls = streamRegex.findAll(combinedHtml)
            .map { it.groupValues[1].replace("\\/", "/") }
            .distinct()
            .toList()

        for (streamUrl in foundUrls) {
            // Ads ya preview thumbnails filter karein
            if (streamUrl.contains("preview") || streamUrl.contains("thumb")) continue

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = streamUrl
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Referer" to url)
                }
            )
        }

        // Subtitles (.vtt / .srt) extract karein agar available hon
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

// 1. LuluStream (luluvid.com / lulustream.com)
class LuluVidExtractor : BaseJWPExtractor() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvid.com"
}

class LuluStreamAltExtractor : BaseJWPExtractor() {
    override var name = "LuluStream"
    override var mainUrl = "https://lulustream.com"
}

// 2. EarnVids / StreamWish mirrors
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
    override var mainUrl = "https://byse.sx"
}

class ByseAltExtractor : BaseJWPExtractor() {
    override var name = "Byse"
    override var mainUrl = "https://bysezejataos.com"
}

// 5. UpBolt
class UpBoltExtractor : BaseJWPExtractor() {
    override var name = "UpBolt"
    override var mainUrl = "https://upbolt.tv"
}

// 6. Abyss / Hydrax
class AbyssExtractor : BaseJWPExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://abysscdn.com"
}

class ShortIcuExtractor : BaseJWPExtractor() {
    override var name = "Abyss"
    override var mainUrl = "https://short.icu"
}
