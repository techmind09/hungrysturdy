package com.hungrysturdy

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import java.net.URI
import android.util.Base64

// ==========================================
// 1. Vidhide & Clones
// ==========================================
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

// ==========================================
// 2. Lulustream & Clones
// ==========================================
open class LuluStream : ExtractorApi() {
    override var name = "Lulustream"
    override var mainUrl = "https://lulustream.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class Luluvid : LuluStream() {
    // FIX 1: Changed 'val' to 'var' so it overrides properly
    override var name = "Lulustream"
    override var mainUrl = "https://luluvid.com"
}

// ==========================================
// 3. Filemoon
// ==========================================
class Filemoon : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

// ==========================================
// 4. KDHindiDubbed Extractor
// ==========================================
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
        // Aapne jo KDHindiDubbedExtractor ka code diya tha wo pura yaha aayega
    }
}

// ==========================================
// 5. Universal Extractor Router
// ==========================================
object UniversalExtractors {
    suspend fun invoke(
        href: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = href.lowercase()

        fun pushDirect(name: String, directUrl: String) {
            // FIX 3: ExtractorLink() is deprecated. Using newExtractorLink() instead.
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = directUrl,
                    referer = href,
                    quality = Qualities.Unknown.value,
                    isM3u8 = directUrl.contains(".m3u8")
                )
            )
        }

        when {
            host.contains("vcloud") -> {
                // vcloudServers(href).forEach { pushDirect(it.name, it.url) }
            }
            host.contains("vegadrive") -> {
                // vegadriveSkydrop(href)?.let { pushDirect("VegaDrive", it) }
            }
            host.contains("fastdl") -> {
                // fastdlDirect(href)?.let { pushDirect("G-Direct", it) }
            }
            host.contains("filebee") || host.contains("filepress") || host.contains("fpgo") -> {
                // filepressDirect(href)?.let { pushDirect("Filepress", it) }
            }
            host.contains("gofile") -> {
                // gofileDirect(href)?.let { pushDirect("Gofile", it) }
            }
            host.contains("gpdl") -> {
                // gpdlDirect(href)?.let { pushDirect("10Gbps", it) }
            }
            host.contains("hubcloud") -> {
                // hubcloudServers(href).forEach { pushDirect(it.name, it.url) }
            }
            else -> {
                if (href.startsWith("http")) {
                    loadExtractor(href, data, subtitleCallback, callback)
                }
            }
        }
    }
}
