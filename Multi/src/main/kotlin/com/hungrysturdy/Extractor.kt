package com.hungrysturdy

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Calendar

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

    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        return it
    }

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
            val obj = JSONObject(rawJson)
            val mappedUrl = obj.optString(source, "")
            if (mappedUrl.isNotBlank()) return mappedUrl
        }
        baseUrl
    } catch (_: Exception) {
        baseUrl
    }
}

suspend fun emitSubtitle(
    subtitleCallback: (SubtitleFile) -> Unit,
    lang: String,
    url: String
) {
    if (url.isBlank()) return
    subtitleCallback.invoke(
        newSubtitleFile(
            lang = lang,
            url = url
        )
    )
}

class Filemoon : Filesim() {
    override val name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
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
                try {
                    URLDecoder.decode(location.substringAfter("url=").substringBefore("&"), "UTF-8")
                } catch (_: Exception) {
                    location.substringAfter("url=").substringBefore("&")
                }
            } else {
                location
            }
            loadExtractor(unwrapped, "", subtitleCallback, callback)
        }
    }
}

// =========================================================================
// 1. HubCloud & mCloud Extractors & Mirrors
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
        KatServerLogic.extractHubCloudServers(url, referer, subtitleCallback, callback)
    }
}

class HubCloudDrive : KatHubCloudExtractor() {
    override val mainUrl = "https://hubcloud.dad"
}

class HubCloudClub : KatHubCloudExtractor() {
    override val mainUrl = "https://hubcloud.club"
}

class HubCloudOne : KatHubCloudExtractor() {
    override val mainUrl = "https://hubcloud.one"
}

class GamerXYTExtractor : KatHubCloudExtractor() {
    override val name = "GamerXYT"
    override val mainUrl = "https://gamerxyt.com"
}

class KatMCloudExtractor : ExtractorApi() {
    override val name = "mCloud"
    override val mainUrl = "https://mcloud.best"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        KatServerLogic.extractMCloudServers(url, referer, subtitleCallback, callback)
    }
}

// =========================================================================
// 2. GDFlix Extractors & Mirrors
// =========================================================================
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
        KatServerLogic.extractGdFlixServers(name, url, referer, subtitleCallback, callback)
    }
}

class GDLink : KatGDFlixExtractor() {
    override var mainUrl = "https://gdlink.*"
}

class GDFlixApp : KatGDFlixExtractor() {
    override var mainUrl = "https://new.gdflix.*"
}

class GdFlix1 : KatGDFlixExtractor() {
    override var mainUrl = "https://new1.gdflix.*"
}

class GdFlix2 : KatGDFlixExtractor() {
    override var mainUrl = "https://*.gdflix.*"
}

class GDFlixNet : KatGDFlixExtractor() {
    override var mainUrl = "https://new15.gdflix.*"
}

class GDFlixCfd : KatGDFlixExtractor() {
    override var mainUrl = "https://gdflix.cfd"
}

class GDFlixdad : KatGDFlixExtractor() {
    override var mainUrl = "https://gdflix.dad"
}

class GDFlixNew9 : KatGDFlixExtractor() {
    override var mainUrl = "https://new9.gdflix.io"
}

class GDFlixNew10 : KatGDFlixExtractor() {
    override var mainUrl = "https://new10.gdflix.io"
}

// =========================================================================
// 3. VegaDrive & DOTFLIX Extractors
// =========================================================================
class KatVegaDriveExtractor : ExtractorApi() {
    override val name = "VegaDrive"
    override val mainUrl = "https://one.vegadrive.me"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        KatServerLogic.extractVegaDriveServers(url, referer, subtitleCallback, callback)
    }
}

class KatDotflixExtractor : ExtractorApi() {
    override val name = "DOTFLIX"
    override val mainUrl = "https://dt.dotflix.website"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        KatServerLogic.extractDotflixServers(url, referer, subtitleCallback, callback)
    }
}

// =========================================================================
// 4. Standalone Extractors
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
        KatServerLogic.extractGoFlixMirror(url, getQualityFromName(referer.orEmpty()), subtitleCallback, callback)
    }
}

// =========================================================================
// Core Server Logic Object
// =========================================================================
object KatServerLogic {

    private fun cleanUrl(raw: String): String {
        var cleaned = raw.replace("&amp;", "&").replace("\\/", "/").trim()
        if (cleaned.contains("fastdl-one.pages.dev") && cleaned.contains("url=")) {
            cleaned = cleaned.substringAfter("url=").substringBefore("&")
            try {
                cleaned = URLDecoder.decode(cleaned, "UTF-8")
            } catch (_: Exception) {}
        }
        return cleaned
    }

    private fun detectQualityFromText(vararg texts: String?): Int {
        val combined = texts.filterNotNull().joinToString(" ")
        val indexQuality = katGetIndexQuality(combined)
        if (indexQuality != Qualities.Unknown.value) return indexQuality
        return Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.get(1)?.let { getQualityFromName(it) }
            ?: Qualities.Unknown.value
    }

    private suspend fun parseJsonResponse(
        respText: String,
        quality: Int,
        sourceTag: String = "GDFlix",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!respText.startsWith("{") && !respText.startsWith("[")) return false
        try {
            val uFieldMatch = Regex(""""(?:u|url|downloadUrl|download_url|link)"\s*:\s*"(https?://[^"]+)"""").find(respText)
            if (uFieldMatch != null) {
                val foundUrl = cleanUrl(uFieldMatch.groupValues[1])
                when {
                    foundUrl.contains("pixeldrain") -> extractPixelDrain(foundUrl, quality, callback)
                    foundUrl.contains("hbplay.pages.dev") -> extractHbPlay(foundUrl, quality, callback)
                    foundUrl.contains("fuckingfast.net") -> extractFuckingFast(foundUrl, quality, callback)
                    else -> {
                        callback.invoke(
                            newExtractorLink(sourceTag, "$sourceTag [JSON Direct]", foundUrl, ExtractorLinkType.VIDEO) {
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
                                newExtractorLink(sourceTag, "$sourceTag [Task Direct]", foundUrl, ExtractorLinkType.VIDEO) {
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

    suspend fun extractMCloudServers(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanedInputUrl = cleanUrl(url)
            val response = app.get(cleanedInputUrl, headers = katBrowserHeaders, referer = referer, interceptor = katCfKiller)
            val respText = response.text.trim()
            val doc = response.document
            val currentUrl = response.url.ifEmpty { cleanedInputUrl }
            val emitted = mutableSetOf<String>()

            val quality = detectQualityFromText(doc.title(), doc.select("h1, h2, h3, h4, h5, h6").text(), referer)

            if (parseJsonResponse(respText, quality, "mCloud", callback)) return

            val fslEl = doc.selectFirst("#fsl[data-minute-base-url], [data-minute-base-url]")
            val minuteBaseUrl = fslEl?.attr("data-minute-base-url")?.trim().orEmpty()
            if (minuteBaseUrl.isNotEmpty()) {
                val minutes = Calendar.getInstance().get(Calendar.MINUTE)
                val minuteStr = minutes.toString().padStart(2, '0')
                val fslFinalUrl = cleanUrl(minuteBaseUrl + "1" + minuteStr)
                if (fslFinalUrl.startsWith("http") && emitted.add(fslFinalUrl)) {
                    callback.invoke(
                        newExtractorLink("mCloud", "mCloud [FSL Server]", fslFinalUrl, ExtractorLinkType.VIDEO) {
                            this.referer = ""
                            this.quality = quality
                        }
                    )
                }
            }

            doc.select("a[href]").forEach { a ->
                val href = cleanUrl(a.attr("abs:href").ifBlank { a.attr("href") })
                if (href.isBlank() || !href.startsWith("http") || href.contains("t.me") || href.contains("telegram")) return@forEach

                val label = a.text().trim()
                val lowerHref = href.lowercase()
                val lowerLabel = label.lowercase()

                when {
                    lowerHref.contains("pixeldrain") || lowerLabel.contains("pixelserver") -> {
                        if (emitted.add(href)) extractPixelDrain(href, quality, callback)
                    }

                    lowerHref.contains("fuckingfast") || lowerLabel.contains("buzz") -> {
                        if (emitted.add(href)) extractFuckingFast(href, quality, callback)
                    }

                    lowerHref.contains("hbplay.pages.dev") -> {
                        if (emitted.add(href)) extractHbPlay(href, quality, callback)
                    }

                    lowerHref.contains("hubcloud") || lowerLabel.contains("10gbps") || lowerLabel.contains("server") -> {
                        if (emitted.add(href)) {
                            callback.invoke(
                                newExtractorLink("mCloud", "mCloud [${label.ifBlank { "10Gbps Server" }}]", href, ExtractorLinkType.VIDEO) {
                                    this.referer = currentUrl
                                    this.quality = quality
                                }
                            )
                        }
                    }

                    lowerHref.contains("gofile") -> {
                        if (emitted.add(href)) loadExtractor(href, currentUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("mCloud", e.toString())
        }
    }

    suspend fun extractVegaDriveServers(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanedInputUrl = cleanUrl(url)
            val response = app.get(cleanedInputUrl, headers = katBrowserHeaders, referer = referer, interceptor = katCfKiller)
            val respText = response.text.trim()
            val doc = response.document
            val currentUrl = response.url.ifEmpty { cleanedInputUrl }
            val origin = katGetBaseUrl(currentUrl)
            val emitted = mutableSetOf<String>()

            val quality = detectQualityFromText(doc.title(), doc.select("h1, h2, h3, h4, h5, h6").text(), referer)

            if (parseJsonResponse(respText, quality, "VegaDrive", callback)) return

            val apiMatch = Regex("""/api/file/([A-Za-z0-9_-]+)""").find(respText)
            if (apiMatch != null) {
                val fileId = apiMatch.groupValues[1]
                try {
                    val apiJson = app.get("$origin/api/file/$fileId", headers = katBrowserHeaders, referer = currentUrl, interceptor = katCfKiller).text.trim()
                    if (!parseJsonResponse(apiJson, quality, "VegaDrive", callback) && apiJson.startsWith("{")) {
                        val json = JSONObject(apiJson)
                        val directUrl = cleanUrl(
                            json.optString("downloadUrl")
                                .ifBlank { json.optString("url") }
                                .ifBlank { json.optString("link") }
                        )
                        if (directUrl.isNotBlank() && directUrl != "null" && emitted.add(directUrl)) {
                            val resolved = if (directUrl.startsWith("http")) directUrl else "$origin${if (directUrl.startsWith("/")) "" else "/"}$directUrl"
                            callback.invoke(
                                newExtractorLink("VegaDrive", "VegaDrive [API Direct]", resolved, ExtractorLinkType.VIDEO) {
                                    this.referer = currentUrl
                                    this.quality = quality
                                }
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            val mirrorLabels = listOf(
                "skydrop" to "Vegadrop 10Gbps Direct",
                "transferit" to "Transferit",
                "gofile" to "Gofile",
                "buzzheavier" to "Buzzheavier",
                "vikingfile" to "VikingFile"
            )

            doc.select("a[href]").forEach { a ->
                val rawHref = a.attr("abs:href").ifBlank { a.attr("href") }
                val href = cleanUrl(rawHref)
                if (href.isBlank() || href.startsWith("javascript:", true) || href.startsWith("#")) return@forEach

                val resolvedHref = if (href.startsWith("http")) {
                    href
                } else {
                    "$origin${if (href.startsWith("/")) "" else "/"}$href"
                }

                if (resolvedHref.contains("t.me") || resolvedHref.contains("telegram")) return@forEach

                val text = a.text().trim()
                val lowerHref = resolvedHref.lowercase()
                val lowerText = text.lowercase()

                val matchedMirror = mirrorLabels.firstOrNull { (key, _) ->
                    lowerHref.contains("/$key") || lowerHref.contains(key) || lowerText.contains(key)
                }

                if (matchedMirror != null && emitted.add(resolvedHref)) {
                    val serverTitle = text.ifBlank { matchedMirror.second }
                    when {
                        matchedMirror.first == "gofile" || lowerHref.contains("gofile.io") -> {
                            loadExtractor(resolvedHref, currentUrl, subtitleCallback, callback)
                        }
                        lowerHref.contains("pixeldrain") -> extractPixelDrain(resolvedHref, quality, callback)
                        lowerHref.contains("fuckingfast") -> extractFuckingFast(resolvedHref, quality, callback)
                        else -> {
                            callback.invoke(
                                newExtractorLink("VegaDrive", "VegaDrive [$serverTitle]", resolvedHref, ExtractorLinkType.VIDEO) {
                                    this.referer = currentUrl
                                    this.quality = quality
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("VegaDrive", e.toString())
        }
    }

    suspend fun extractDotflixServers(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanedInputUrl = cleanUrl(url)
            val response = app.get(cleanedInputUrl, headers = katBrowserHeaders, referer = referer, interceptor = katCfKiller)
            val respText = response.text.trim()
            val doc = response.document
            val currentUrl = response.url.ifEmpty { cleanedInputUrl }
            val origin = katGetBaseUrl(currentUrl)
            val emitted = mutableSetOf<String>()

            val quality = detectQualityFromText(doc.title(), doc.select("h1, h2, h3, h4, h5, h6").text(), referer)

            if (parseJsonResponse(respText, quality, "DOTFLIX", callback)) return

            val sharingCode = Regex("""sharingCode\s*[:=]\s*["']([a-fA-F0-9]+)["']""")
                .find(respText)?.groupValues?.get(1)
                ?: doc.selectFirst("input[name=sharingCode], [data-sharing-code]")
                    ?.let { it.attr("value").ifBlank { it.attr("data-sharing-code") } }
                ?: ""

            val proof = Regex("""[?&]proof=([A-Za-z0-9_%-]+)""").find(currentUrl)?.groupValues?.get(1)
                ?: Regex("""proof\s*[:=]\s*["']([A-Za-z0-9_%-]+)["']""").find(respText)?.groupValues?.get(1)

            if (sharingCode.isNotBlank()) {
                val endpoint = if (!proof.isNullOrBlank()) {
                    "$origin/api/extract-download?proof=$proof"
                } else {
                    "$origin/api/extract-download"
                }

                val jsonPayload = JSONObject().apply {
                    put("sharingCode", sharingCode)
                }.toString()

                val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val apiHeaders = katBrowserHeaders + mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/plain, */*",
                    "Origin" to origin
                )

                val apiResp = app.post(
                    url = endpoint,
                    headers = apiHeaders,
                    referer = currentUrl,
                    requestBody = requestBody,
                    interceptor = katCfKiller
                )
                val apiText = apiResp.text.trim()

                if (!parseJsonResponse(apiText, quality, "DOTFLIX", callback) && apiText.startsWith("{")) {
                    val obj = JSONObject(apiText)
                    val directUrl = cleanUrl(
                        obj.optString("downloadUrl")
                            .ifBlank { obj.optString("url") }
                            .ifBlank { obj.optString("link") }
                    )
                    if (directUrl.isNotBlank() && directUrl != "null" && emitted.add(directUrl)) {
                        val resolved = if (directUrl.startsWith("http")) directUrl else "$origin${if (directUrl.startsWith("/")) "" else "/"}$directUrl"
                        when {
                            resolved.contains("pixeldrain") -> extractPixelDrain(resolved, quality, callback)
                            resolved.contains("fuckingfast") -> extractFuckingFast(resolved, quality, callback)
                            resolved.contains("gofile") -> loadExtractor(resolved, currentUrl, subtitleCallback, callback)
                            else -> {
                                callback.invoke(
                                    newExtractorLink("DOTFLIX", "DOTFLIX [Direct]", resolved, ExtractorLinkType.VIDEO) {
                                        this.referer = currentUrl
                                        this.quality = quality
                                    }
                                )
                            }
                        }
                    }

                    val mirrorsArray = obj.optJSONArray("mirrors") ?: obj.optJSONArray("servers")
                    if (mirrorsArray != null) {
                        for (i in 0 until mirrorsArray.length()) {
                            val item = mirrorsArray.optJSONObject(i)
                            val rawMirrorUrl = cleanUrl(item?.optString("url").orEmpty())
                            if (rawMirrorUrl.isNotBlank() && rawMirrorUrl != "null" && emitted.add(rawMirrorUrl)) {
                                val resolvedMirror = if (rawMirrorUrl.startsWith("http")) rawMirrorUrl else "$origin${if (rawMirrorUrl.startsWith("/")) "" else "/"}$rawMirrorUrl"
                                val mirrorName = item?.optString("name").orEmpty().ifBlank { "Mirror ${i + 1}" }
                                callback.invoke(
                                    newExtractorLink("DOTFLIX", "DOTFLIX [$mirrorName]", resolvedMirror, ExtractorLinkType.VIDEO) {
                                        this.referer = currentUrl
                                        this.quality = quality
                                    }
                                )
                            }
                        }
                    }
                }
            }

            doc.select("a[href]").forEach { a ->
                val href = cleanUrl(a.attr("abs:href").ifBlank { a.attr("href") })
                if (href.isBlank() || href.contains("t.me") || href.contains("telegram")) return@forEach
                if ((href.contains("pixeldrain") || href.contains("fuckingfast") || href.contains("gofile")) && emitted.add(href)) {
                    when {
                        href.contains("pixeldrain") -> extractPixelDrain(href, quality, callback)
                        href.contains("fuckingfast") -> extractFuckingFast(href, quality, callback)
                        href.contains("gofile") -> loadExtractor(href, currentUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("DOTFLIX", e.toString())
        }
    }

    suspend fun extractHubCloudServers(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit = {},
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanedInputUrl = cleanUrl(url)
            val response = app.get(cleanedInputUrl, headers = katBrowserHeaders, referer = referer, interceptor = katCfKiller)
            val respText = response.text.trim()
            if (parseJsonResponse(respText, Qualities.Unknown.value, "HubCloud", callback)) return

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
                if (parseJsonResponse(gResp.text.trim(), detectedQuality, "HubCloud", callback)) return
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

            gamerDoc.select("a[href*='gofile.io']").forEach { a ->
                val goUrl = cleanUrl(a.attr("href"))
                if (goUrl.startsWith("http")) {
                    loadExtractor(goUrl, gamerRef, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            if (parseJsonResponse(respText, Qualities.Unknown.value, sourceName, callback)) return

            val document = resp.document
            val activeOrigin = katGetBaseUrl(resp.url.ifEmpty { newUrl })

            val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ").trim()
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ").trim()

            val detectedQuality = katGetIndexQuality(fileName).takeIf { it != Qualities.Unknown.value }
                ?: Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE)
                    .find("${document.title()} ${document.text()} ${referer.orEmpty()}")
                    ?.groupValues?.get(1)?.let { getQualityFromName(it) }
                ?: Qualities.Unknown.value

            val labelSuffix = if (fileName.isNotBlank() || fileSize.isNotBlank()) {
                " ${fileName}[${fileSize}]"
            } else {
                ""
            }

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

            document.select("div.text-center a").amap { anchor ->
                val text = anchor.select("a").text().ifEmpty { anchor.text() }.trim()
                val link = cleanUrl(anchor.attr("href"))
                if (link.isBlank()) return@amap

                val resolvedLink = if (link.startsWith("http")) {
                    link
                } else {
                    val root = if (activeOrigin.startsWith("http")) activeOrigin else baseUrl
                    "$root${if (link.startsWith("/")) "" else "/"}$link"
                }

                when {
                    text.contains("FSL V2", true) -> emitGdLink(resolvedLink, " [FSL V2]")
                    text.contains("DIRECT DL", true) -> emitGdLink(resolvedLink, " [Direct]")
                    text.contains("DIRECT SERVER", true) -> emitGdLink(resolvedLink, " [Direct]")
                    text.contains("CLOUD DOWNLOAD [R2]", true) -> emitGdLink(resolvedLink, " [Cloud]")
                    text.contains("GD Index", true) -> {
                        listOf(1, 2).amap { cfType ->
                            try {
                                val separator = if (resolvedLink.contains("?")) "&" else "?"
                                app.get(resolvedLink + "${separator}type=$cfType", headers = katBrowserHeaders, interceptor = katCfKiller)
                                    .document
                                    .select("a.btn-success")
                                    .amap { btn ->
                                        emitGdLink(btn.attr("href"), " [CF]")
                                    }
                            } catch (e: Exception) {
                                Log.d("GD Index", e.toString())
                            }
                        }
                    }
                    text.contains("FAST CLOUD", true) -> {
                        try {
                            val cloudResp = app.get(resolvedLink, headers = katBrowserHeaders, referer = newUrl, interceptor = katCfKiller)
                            val cloudRespText = cloudResp.text.trim()
                            if (parseJsonResponse(cloudRespText, detectedQuality, sourceName, callback)) {
                                return@amap
                            }
                            if (cloudRespText.contains("In Queue") || cloudRespText.contains("queue") || cloudRespText.contains("false")) {
                                extractGdFlixCloudSubpage(resolvedLink, newUrl, detectedQuality, callback)
                                return@amap
                            }
                            val dlink = cloudResp.document.select("div.card-body a").attr("href")
                            if (dlink.isNotBlank()) {
                                emitGdLink(dlink, " [FAST CLOUD]")
                            } else {
                                extractGdFlixCloudSubpage(resolvedLink, newUrl, detectedQuality, callback)
                            }
                        } catch (e: Exception) {
                            Log.d("FAST CLOUD", e.toString())
                        }
                    }
                    resolvedLink.contains("pixeldra", true) -> {
                        val baseUrlLink = katGetBaseUrl(resolvedLink)
                        val finalURL = if (resolvedLink.contains("download", true)) {
                            resolvedLink
                        } else {
                            "$baseUrlLink/api/file/${resolvedLink.substringAfterLast("/").substringBefore("?")}?download"
                        }
                        emitGdLink(finalURL, " [Pixeldrain]")
                    }
                    text.contains("Instant DL", true) -> {
                        try {
                            val instantLoc = app.get(resolvedLink, headers = katBrowserHeaders, allowRedirects = false)
                                .headers["location"]
                            val instantLink = if (!instantLoc.isNullOrBlank()) {
                                if (instantLoc.contains("url=")) {
                                    try {
                                        URLDecoder.decode(instantLoc.substringAfter("url=").substringBefore("&"), "UTF-8")
                                    } catch (_: Exception) {
                                        instantLoc.substringAfter("url=").substringBefore("&")
                                    }
                                } else {
                                    instantLoc
                                }
                            } else {
                                ""
                            }

                            if (instantLink.isNotBlank()) {
                                emitGdLink(instantLink, " [Instant Download]")
                            } else {
                                resolveInstantDl(resolvedLink, newUrl, detectedQuality, callback)
                            }
                        } catch (e: Exception) {
                            Log.d("Instant DL", e.toString())
                        }
                    }
                    text.contains("GoFile", true) -> {
                        try {
                            app.get(resolvedLink, headers = katBrowserHeaders, interceptor = katCfKiller).document
                                .select(".row .row a").amap { gofileAnchor ->
                                    val gLink = cleanUrl(gofileAnchor.attr("href"))
                                    if (gLink.contains("gofile", true)) {
                                        loadExtractor(gLink, "", subtitleCallback, callback)
                                    }
                                }
                        } catch (e: Exception) {
                            Log.d("Gofile", e.toString())
                        }
                    }
                    else -> {
                        Log.d("Error", "No Server matched: $text -> $resolvedLink")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun resolveInstantDl(
        instantUrl: String,
        refererUrl: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(instantUrl, headers = katBrowserHeaders, referer = refererUrl, interceptor = katCfKiller)
            val respText = resp.text.trim()
            if (parseJsonResponse(respText, quality, "GDFlix", callback)) return

            val finalPageUrl = cleanUrl(resp.url.ifEmpty { instantUrl })
            if (finalPageUrl.contains("googleusercontent.com") || finalPageUrl.contains("r2.cloudflarestorage.com") || finalPageUrl.contains("workers.dev")) {
                callback.invoke(
                    newExtractorLink("GDFlix", "GDFlix [Instant DL 10Gbps]", finalPageUrl, ExtractorLinkType.VIDEO) {
                        this.referer = ""
                        this.quality = quality
                    }
                )
                return
            }

            val doc = resp.document
            val html = resp.text

            val directGoogleUrl = doc.selectFirst("a[href*='googleusercontent.com'], a:contains(Download Here)")?.attr("href")
                ?: Regex("""https?://video-downloads\.googleusercontent\.com/[^\s"'<>\\]+""").find(html)?.value
                ?: Regex("""https?://[^\s"'<>\\]*googleusercontent\.com/[^\s"'<>\\]+""").find(html)?.value

            if (!directGoogleUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink("GDFlix", "GDFlix [Instant DL 10Gbps]", cleanUrl(directGoogleUrl), ExtractorLinkType.VIDEO) {
                        this.referer = ""
                        this.quality = quality
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun extractGdFlixCloudSubpage(
        cloudUrl: String,
        refererUrl: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            var resp = app.get(cloudUrl, headers = katBrowserHeaders, referer = refererUrl, interceptor = katCfKiller)
            var respText = resp.text.trim()

            if (parseJsonResponse(respText, quality, "GDFlix", callback)) return

            if (respText.contains("In Queue") || respText.contains("queue") || respText.contains("false") || respText.contains("challenge-platform")) {
                repeat(5) {
                    kotlinx.coroutines.delay(2000)
                    try {
                        resp = app.get(cloudUrl, headers = katBrowserHeaders, referer = refererUrl, interceptor = katCfKiller)
                        respText = resp.text.trim()
                        if (parseJsonResponse(respText, quality, "GDFlix", callback)) return
                    } catch (_: Exception) {}
                }
            }

            val doc = resp.document
            val html = resp.text
            val emitted = mutableSetOf<String>()

            doc.select("a[href*='workers.dev'], a:contains(CLOUD RESUME DOWNLOAD), a:contains(Download)").forEach { a ->
                val workersUrl = cleanUrl(a.attr("href"))
                if (workersUrl.startsWith("http") && !workersUrl.contains("gdflix") && emitted.add(workersUrl)) {
                    callback.invoke(
                        newExtractorLink("GDFlix", "GDFlix [Cloud Resume]", workersUrl, ExtractorLinkType.VIDEO) {
                            this.referer = ""
                            this.quality = quality
                        }
                    )
                }
            }

            if (emitted.isEmpty()) {
                Regex("""https?://[^\s"'<>\\]*(?:workers\.dev|cloudpaglu\.site|googleusercontent\.com)[^\s"'<>\\]*""").findAll(html).forEach { m ->
                    val wUrl = cleanUrl(m.value)
                    if (emitted.add(wUrl)) {
                        callback.invoke(
                            newExtractorLink("GDFlix", "GDFlix [Cloud CDN]", wUrl, ExtractorLinkType.VIDEO) {
                                this.referer = ""
                                this.quality = quality
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) {}
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
            try {
                val decodedBytes = try {
                    Base64.decode(encodedU, Base64.DEFAULT)
                } catch (_: Exception) {
                    Base64.decode(encodedU, Base64.URL_SAFE)
                }
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
            } catch (_: Exception) {}
        }
    }

    suspend fun extractFuckingFast(url: String, quality: Int, callback: (ExtractorLink) -> Unit) {
        try {
            val cleaned = cleanUrl(url)
            val responseText = app.get(cleaned, headers = katBrowserHeaders, interceptor = katCfKiller).text
            val directLink = Regex("""window\.open\(["'](https?://fuckingfast\.net/dl/[^"']+)["']\)""")
                .find(responseText)?.groupValues?.get(1)
                ?: Regex("""["'](https?://fuckingfast\.net/dl/[^"']+)["']""")
                    .find(responseText)?.groupValues?.get(1)

            if (!directLink.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink("BuzzServer", "HubCloud [Buzz Server]", cleanUrl(directLink), ExtractorLinkType.VIDEO) {
                        this.referer = cleaned
                        this.quality = quality
                    }
                )
            }
        } catch (_: Exception) {}
    }

    suspend fun extractGoFlixMirror(
        url: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
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
        } catch (_: Exception) {}
    }
}

