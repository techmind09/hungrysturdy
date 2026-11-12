package com.hungrysturdy

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

open class VidhideExtractor : ExtractorApi() {
    override var name = "Vidhide"
    override var mainUrl = "https://vidhide.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class Vidsp : VidhideExtractor() { override var mainUrl = "https://vidsp.lol" }
class VidHideplus : VidhideExtractor() { override var mainUrl = "https://vidhideplus.com" }
class VidHidedht : VidhideExtractor() { override var mainUrl = "https://dhtpre.com" }
class Vidhidehub : VidhideExtractor() { override var mainUrl = "https://vidhidehub.com" }
class Vidhidetoul : VidhideExtractor() { override var mainUrl = "https://toul.hair" }
class Xtapes : VidhideExtractor() { 
    override var mainUrl = "https://xtapes.porn"
    override var name = "Xtapes"
}

open class LuluStream : ExtractorApi() {
    override var name = "Lulustream"
    override var mainUrl = "https://lulustream.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class Luluvid : LuluStream() {
    override var name = "Lulustream"
    override var mainUrl = "https://luluvid.com"
}

class Filemoon : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "KDHindiDubbed"
    override val mainUrl = "https://momofile.shop"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Your KDHindiDubbed logic here
    }
}

object UniversalExtractors {
    suspend fun invoke(
        href: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = href.lowercase()

        // FIX: Made pushDirect a suspend function because callback.invoke is a suspend function in CS3
        suspend fun pushDirect(name: String, directUrl: String) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = directUrl,
                    type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = href
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        when {
            host.contains("vcloud") -> {}
            host.contains("vegadrive") -> {}
            host.contains("fastdl") -> {}
            host.contains("filebee") || host.contains("filepress") || host.contains("fpgo") -> {}
            host.contains("gofile") -> {}
            host.contains("gpdl") -> {}
            host.contains("hubcloud") -> {}
            else -> {
                if (href.startsWith("http")) {
                    loadExtractor(href, data, subtitleCallback, callback)
                }
            }
        }
    }
}
