package com.hungrysturdy

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.GDMirrorbot
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val dynamicUrlCache = ConcurrentHashMap<String, String>()

private fun mfBaseUrl(url: String): String = runCatching {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}"
}.getOrDefault(url)

private suspend fun mfLatestBaseUrl(baseUrl: String, source: String): String {
    dynamicUrlCache[source]?.let { return it }
    return runCatching {
        val raw = app.get(
            "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json",
            timeout = 8L
        ).text
        val resolved = JSONObject(raw).optString(source).trim()
        if (resolved.startsWith("http")) {
            dynamicUrlCache[source] = resolved.trimEnd('/')
            resolved.trimEnd('/')
        } else {
            baseUrl
        }
    }.getOrDefault(baseUrl)
}

private fun mfIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lower = str.lowercase()
    return when {
        lower.contains("8k") -> 4320
        lower.contains("4k") || lower.contains("uhd") -> 2160
        lower.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

private fun mfFormatBytes(bytes: Long): String = when {
    bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes.toDouble() / (1024 * 1024))
    else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
}

private suspend fun <A, B> Iterable<A>.mfSafeAmap(
    concurrency: Int = 7,
    f: suspend (A) -> B?
): List<B> = supervisorScope {
    val semaphore = Semaphore(concurrency)
    map { item ->
        async<B?>(Dispatchers.IO) {
            semaphore.withPermit {
                try {
                    f(item)
                } catch (e: CancellationException) {
                    if (!isActive) throw e
                    null
                } catch (e: Throwable) {
                    Log.e("mfSafeAmap", "Item failed: ${e.message}")
                    null
                }
            }
        }
    }.awaitAll().filterNotNull()
}

class Streameeeeee : Videostr() {
    override var name = "Streameeeeee"
    override var mainUrl = "https://streameeeeee.site"
}

open class Videostr : ExtractorApi() {
    override val name = "Videostr"
    override val mainUrl = "https://videostr.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mainHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "Connection" to "keep-alive",
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache"
        )

        val headers = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )

        val response = app.get(url, headers = headers)
        val document = response.document
        val htmlResponse = response.text

        val videoTag = document.selectFirst("[id$=\"-player\"]") ?: return
        val fileId = videoTag.attr("data-id")
        if (fileId.isEmpty()) return

        val tripleRegex = Regex("""\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b""", RegexOption.DOT_MATCHES_ALL)
        val singleRegex = Regex("""\b[a-zA-Z0-9]{48}\b""")

        val tripleMatch = tripleRegex.find(htmlResponse)
        val singleMatch = singleRegex.find(htmlResponse)

        val nonce = if (tripleMatch != null) {
            tripleMatch.groupValues[1] + tripleMatch.groupValues[2] + tripleMatch.groupValues[3]
        } else {
            singleMatch?.value ?: return
        }

        val apiUrl = "$mainUrl/embed-1/v3/e-1/getSources?id=$fileId&_k=$nonce"
        val jsonResponse = app.get(apiUrl, headers = headers).text

        val rootObj = JSONObject(jsonResponse)
        val sourcesArray = rootObj.optJSONArray("sources") ?: return
        if (sourcesArray.length() == 0) return

        val m3u8 = sourcesArray.optJSONObject(0)?.optString("file")?.trim().orEmpty()
        if (!m3u8.startsWith("http")) return

        generateM3u8(name, m3u8, mainUrl, headers = mainHeaders).forEach(callback)
    }
}

class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"
    private val browserLanguage = "en-GB"
    private val secret = "12af056dacea0b"
    private val userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return

        val defaultHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        val token = app.post("$mainApi/accounts", headers = defaultHeaders)
            .parsedSafe<AccountResponse>()?.data?.token ?: return

        val hashedToken = generateWebsiteToken(userAgent, token)

        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to userAgent,
            "Authorization" to "Bearer $token",
            "X-BL" to browserLanguage,
            "X-Website-Token" to hashedToken
        )

        val parsedResponse = app.get(
            "$mainApi/contents/$id?page=1&pageSize=100&sortField=name&sortDirection=1",
            headers = headers
        ).parsedSafe<GofileResponse>()

        val childrenMap = parsedResponse?.data?.children ?: return
        for ((_, file) in childrenMap) {
            val fileLink = file.link?.trim().orEmpty()
            if (!fileLink.startsWith("http") || file.type != "file") continue
            val fileName = file.name ?: ""
            val size = file.size ?: 0L
            val formattedSize = mfFormatBytes(size)
            callback.invoke(
                newExtractorLink(
                    "Gofile",
                    "[Gofile] $fileName [$formattedSize]",
                    fileLink,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = mfIndexQuality(fileName)
                    this.headers = mapOf("Cookie" to "accountToken=$token")
                }
            )
        }
    }

    private fun generateWebsiteToken(userAgent: String, accountToken: String): String {
        val timeSlot = System.currentTimeMillis() / 1000 / 14400
        val raw = "$userAgent::$browserLanguage::$accountToken::$timeSlot::$secret"
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class AccountResponse(val data: AccountData? = null)
    data class AccountData(val token: String? = null)
    data class GofileResponse(val data: GofileData? = null)
    data class GofileData(val children: Map<String, GofileFile>? = null)
    data class GofileFile(
        val type: String? = null,
        val name: String? = null,
        val link: String? = null,
        val size: Long? = 0L
    )
}

class Wootly : ExtractorApi() {
    override var name = "Wootly"
    override var mainUrl = "https://www.wootly.ch"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframe = app.get(url).document.selectFirst("iframe")?.attr("src") ?: return

        val iframeHtml = app.post(
            iframe,
            headers = mapOf("Referer" to url),
            data = mapOf("qdfx" to "1")
        ).text

        val vd = Regex("""var\s+vd\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
        val tk = Regex("""tk\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
        if (vd.isNullOrBlank() || tk.isNullOrBlank()) return

        val streamUrl = app.get(
            "https://web.wootly.ch/grabm?t=$tk&id=$vd",
            headers = mapOf("Referer" to iframe)
        ).text.trim()

        if (streamUrl.isBlank() || !streamUrl.startsWith("http")) return

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url = streamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer ?: ""
            }
        )
    }
}

class GDLink : GDFlix() { override var mainUrl = "https://gdlink.*" }
class GDFlixApp : GDFlix() { override var mainUrl = "https://new.gdflix.*" }
class GdFlix1 : GDFlix() { override var mainUrl = "https://new1.gdflix.*" }
class GdFlix2 : GDFlix() { override var mainUrl = "https://*.gdflix.*" }
class GDFlixNet : GDFlix() { override var mainUrl = "https://new18.gdflix.*" }

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = mfBaseUrl(url)
        val latestBaseUrl = mfLatestBaseUrl(baseUrl, "gdflix")

        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ").orEmpty()
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ").orEmpty()
        val quality = mfIndexQuality(fileName)

        suspend fun myCallback(link: String, server: String = "") {
            if (!link.startsWith("http")) return
            callback.invoke(
                newExtractorLink(
                    "${name}${server}",
                    "${name}${server} ${fileName}[${fileSize}]",
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        document.select("div.text-center a").mfSafeAmap { anchor ->
            val text = anchor.select("a").text()
            val link = anchor.attr("href")

            when {
                text.contains("FSL V2") -> myCallback(link, "[FSL V2]")
                text.contains("DIRECT DL") -> myCallback(link, "[Direct]")
                text.contains("DIRECT SERVER") -> myCallback(link, "[Direct]")
                text.contains("CLOUD DOWNLOAD [R2]") -> myCallback(link, "[Cloud]")
                text.contains("GD Index") -> {
                    val cfLink = baseUrl + link
                    listOf(1, 2).mfSafeAmap { cfType ->
                        app.get("$cfLink?type=$cfType")
                            .document
                            .select("a.btn-success")
                            .mfSafeAmap { cfBtn -> myCallback(cfBtn.attr("href"), "[CF]") }
                    }
                }
                text.contains("FAST CLOUD") -> {
                    val dlink = app.get(baseUrl + link).document.select("div.card-body a").attr("href")
                    if (dlink.isNotBlank()) myCallback(dlink, "[FAST CLOUD]")
                }
                link.contains("pixeldra") -> {
                    val baseUrlLink = mfBaseUrl(link)
                    val finalURL = if (link.contains("download", true)) link
                    else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                    myCallback(finalURL, "[Pixeldrain]")
                }
                text.contains("GoFile") -> {
                    runCatching {
                        app.get(link).document.select(".row .row a").mfSafeAmap { gofileAnchor ->
                            val gLink = gofileAnchor.attr("href")
                            if (gLink.contains("gofile")) loadExtractor(gLink, "", subtitleCallback, callback)
                        }
                    }
                }
            }
        }
    }
}

open class fastdlserver : ExtractorApi() {
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
        if (!location.isNullOrBlank()) {
            loadExtractor(location, "", subtitleCallback, callback)
        }
    }
}

class Linksmod : ExtractorApi() {
    override val name = "Linksmod"
    override var mainUrl = "https://linksmod.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url).document.select("div .view-well > a").mfSafeAmap { item ->
            loadExtractor(item.attr("href"), "", subtitleCallback, callback)
        }
    }
}

class HdStream4u : VidHidePro() { override var mainUrl = "https://hdstream4u.com" }
class Hubstream : VidStack() { override var mainUrl = "https://hubstream.art" }

open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url).document.select("div#primary p > a").mfSafeAmap { item ->
            loadExtractor(item.attr("href"), "", subtitleCallback, callback)
        }
    }
}

open class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val href = app.get(url).document.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
        if (href.isNotBlank()) loadExtractor(href, "", subtitleCallback, callback)
    }
}

class Driveseed : Driveleech() {
    override val name: String = "Driveseed"
    override val mainUrl: String = "https://driveseed.*"
}

open class Driveleech : ExtractorApi() {
    override val name: String = "Driveleech"
    override val mainUrl: String = "https://driveleech.*"
    override val requiresReferer = false

    private suspend fun CFType(url: String): List<String> {
        val downloadLinks = mutableListOf<String>()
        listOf("1", "2").forEach { t ->
            val document = app.get("$url?type=$t").document
            downloadLinks.addAll(document.select("a.btn-success").mapNotNull { it.attr("href") })
        }
        return downloadLinks
    }

    private suspend fun resumeCloudLink(baseUrl: String, url: String): String? {
        return app.get(baseUrl + url).document.selectFirst("a.btn-success")?.attr("href")
    }

    private suspend fun resumeBot(url: String): String {
        val resumeBotResponse = app.get(url)
        val resumeBotDoc = resumeBotResponse.document.toString()
        val ssid = resumeBotResponse.cookies["PHPSESSID"]
        val resumeBotToken = Regex("formData\\.append\\('token', '([a-f0-9]+)'\\)").find(resumeBotDoc)?.groups?.get(1)?.value
        val resumeBotPath = Regex("fetch\\('/download\\?id=([a-zA-Z0-9/+]+)'").find(resumeBotDoc)?.groups?.get(1)?.value
        val resumeBotBaseUrl = url.split("/download")[0]
        val jsonResponse = app.post(
            "$resumeBotBaseUrl/download?id=$resumeBotPath",
            data = mapOf("token" to "$resumeBotToken"),
            headers = mapOf("Accept" to "*/*", "Origin" to resumeBotBaseUrl, "Sec-Fetch-Site" to "same-origin"),
            cookies = mapOf("PHPSESSID" to "$ssid"),
            referer = url
        ).text
        return JSONObject(jsonResponse).getString("url")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = mfBaseUrl(url)
        val document = if (url.contains("r?key=")) {
            val temp = app.get(url).document.selectFirst("script")?.data()
                ?.substringAfter("replace(\"")?.substringBefore("\")").orEmpty()
            app.get(baseUrl + temp).document
        } else {
            app.get(url).document
        }

        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ")
        val quality = mfIndexQuality(fileName)

        suspend fun myCallback(link: String, server: String = "") {
            if (!link.startsWith("http")) return
            callback.invoke(
                newExtractorLink(
                    "${name}${server}",
                    "${name}${server} ${fileName}[${fileSize}]",
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        document.select("div.text-center > a").mfSafeAmap { element ->
            val text = element.text()
            val href = element.attr("href")
            when {
                text.contains("Cloud Download") -> myCallback(href, "[Cloud]")
                text.contains("Resume Worker Bot") -> runCatching { myCallback(resumeBot(href), "[ResumeBot]") }
                text.contains("Direct Links") -> runCatching {
                    CFType(baseUrl + href).forEach { myCallback(it, "[CF]") }
                }
                text.contains("Resume Cloud") -> runCatching {
                    resumeCloudLink(baseUrl, href)?.let { myCallback(it, "[ResumeCloud]") }
                }
                text.contains("gofile") -> loadExtractor(href, "", subtitleCallback, callback)
            }
        }
    }
}

class Howblogs : ExtractorApi() {
    override val name: String = "Howblogs"
    override val mainUrl: String = "https://howblogs.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url).document.select("div.center_it a").mfSafeAmap { item ->
            loadExtractor(item.attr("href"), referer, subtitleCallback, callback)
        }
    }
}

class VCloud : HubCloud() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.*"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    fun extractPxlUrl(html: String): String? =
        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

    fun extractDoubleAtob(html: String): String? =
        Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
            .find(html)?.groupValues?.get(1)?.let { base64Decode(base64Decode(it)) }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = mfBaseUrl(url)
        val latestBaseUrl = if (url.contains("hubcloud")) {
            mfLatestBaseUrl(baseUrl, "hubcloud")
        } else {
            mfLatestBaseUrl(baseUrl, "vcloud")
        }

        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val doc = app.get(newUrl).document
        var link = if (newUrl.contains("/video/")) {
            doc.selectFirst("div.vd > center > a")?.attr("href").orEmpty()
        } else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString().orEmpty()
            if (newUrl.contains("vcloud")) extractDoubleAtob(scriptTag).orEmpty()
            else Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1).orEmpty()
        }

        if (link.isBlank()) return
        if (!link.startsWith("https://")) link = baseUrl + link

        val document = app.get(link).document
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = mfIndexQuality(header)

        suspend fun myCallback(link: String, server: String = "") {
            if (!link.startsWith("http")) return
            callback.invoke(
                newExtractorLink(
                    "${name}${server}",
                    "${name}${server} ${header}[${size}]",
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        document.select("h2 a.btn").mfSafeAmap { btn ->
            val btnLink = btn.attr("href")
            val text = btn.text()
            when {
                text.contains("FSL Server") -> myCallback(btnLink, "[FSL Server]")
                text.contains("FSLv2") -> myCallback(btnLink, "[FSLv2 Server]")
                text.contains("Mega Server") -> myCallback(btnLink, "[Mega Server]")
                text.contains("Download File") -> myCallback(btnLink)
                btnLink.contains("pixeldra") -> {
                    val pixelLink = extractPxlUrl(document.toString()) ?: return@mfSafeAmap
                    val baseUrlLink = mfBaseUrl(pixelLink)
                    val finalURL = if (pixelLink.contains("download", true)) pixelLink
                    else "$baseUrlLink/api/file/${pixelLink.substringAfterLast("/")}?download"
                    myCallback(finalURL, "[Pixeldrain]")
                }
                text.contains("Buzz Server") -> {
                    val baseBuzzUrl = mfBaseUrl(btnLink)
                    val redirectUrl = app.get(btnLink).document.selectFirst(".download-btn")?.attr("href") ?: return@mfSafeAmap
                    myCallback(baseBuzzUrl + redirectUrl, "[Buzz Server]")
                }
                text.contains("Gofile") -> loadExtractor(btnLink, "", subtitleCallback, callback)
            }
        }
    }
}

class FlixCloud : ExtractorApi() {
    override val name = "FlixCloud"
    override val mainUrl = "https://flixcloud.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("Referer" to "$mainUrl/")
        val res = app.get(url, headers = headers).document
        val script = res.selectFirst("script:containsData(video_id)")?.data() ?: return

        val start = script.indexOf("data:{")
        if (start == -1) return
        val from = script.indexOf('{', start)

        var depth = 0
        var end = -1
        for (i in from until script.length) {
            when (script[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end == -1) return

        val rawData = script.substring(from, end + 1)
        val data = JSONObject(
            rawData.replace(Regex("""([{,]\s*)([A-Za-z0-9_]+)(\s*:)"""), "$1\"$2\"$3")
        )

        data.optJSONArray("subtitles")?.let { subtitles ->
            for (i in 0 until subtitles.length()) {
                subtitles.optJSONObject(i)?.run {
                    val subUrl = optString("url").trim()
                    if (subUrl.startsWith("http")) {
                        subtitleCallback.invoke(newSubtitleFile(optString("language", "English"), subUrl))
                    }
                }
            }
        }

        val stream = data.optString("stream").ifBlank { data.optString("file") }.ifBlank { data.optString("url") }.trim()
        if (!stream.startsWith("http")) return

        generateM3u8(
            name,
            stream,
            "$mainUrl/",
            headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl, "User-Agent" to USER_AGENT)
        ).forEach(callback)
    }
}

class Embedload : Asianload() {
    override val name = "Embedload"
    override val mainUrl = "https://embedload.cfd"
    override val requiresReferer = false
}

open class Asianload : ExtractorApi() {
    override val name = "Asianload"
    override val mainUrl = "https://asianload.cfd"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        val script = document.selectFirst("div#player + script")?.html() ?: return
        val unpacked = JsUnpacker(script).unpack() ?: return

        val base64Regex = Regex("""window\.atob\("([^"]+)"\)""")
        base64Regex.findAll(unpacked).forEach { match ->
            val decodedLink = base64Decode(match.groupValues[1])
            when {
                decodedLink.contains(".mp4") -> callback(
                    newExtractorLink("AsianLoad (mp4)", "AsianLoad", decodedLink, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                decodedLink.contains(".m3u8") -> callback(
                    newExtractorLink("AsianLoad (m3u8)", "AsianLoad", decodedLink, ExtractorLinkType.M3U8) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }
}

class Vivibebe : VibePlayer() { override val mainUrl = "https://vivibebe.site" }
class Bibiemb : VibePlayer() { override val mainUrl = "https://bibiemb.xyz" }

open class VibePlayer : ExtractorApi() {
    override val name = "VibePlayer"
    override val mainUrl = "https://vibeplayer.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer).text
        val videoUrl = Regex("""const\s+src\s*=\s*["']([^"']+)["']""")
            .find(response)?.groups?.get(1)?.value ?: return
        generateM3u8(name, videoUrl, "$mainUrl/").forEach(callback)
    }
}

class Playmogo : DoodLaExtractor() { override var mainUrl = "https://playmogo.com" }
class Otakuvid : VidHidePro() { override var mainUrl = "https://otakuvid.online" }
class Otakuhg : VidHidePro() { override var mainUrl = "https://otakuhg.site" }
class Minochinos : VidHidePro() { override var mainUrl = "https://minochinos.com" }

class Vidwish : MegaPlay() {
    override val name = "Vidwish"
    override val mainUrl = "https://vidwish.live"
}

class Vidtube : MegaPlay() {
    override val name = "Vidtube"
    override val mainUrl = "https://vidtube.site"
}

open class MegaPlay : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractMegaPlayUrl(url, referer, mainUrl, name, subtitleCallback, callback)
    }

    companion object {
        suspend fun extractMegaPlayUrl(
            url: String,
            referer: String?,
            host: String,
            serverName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val playbackHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "Origin" to host,
                "Referer" to "$host/"
            )
            val pageHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to (referer ?: "https://anikototv.to/")
            )

            val doc = app.get(url, headers = pageHeaders).document
            val playerEl = doc.selectFirst("#megaplay-player")
            val streamId = playerEl?.attr("data-id")
                ?: playerEl?.attr("data-realid")
                ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
                ?: return

            val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"
            val jsonText = runCatching {
                app.get("$host/stream/getSources?id=$streamId&type=$type", headers = mapOf("Referer" to url), referer = url).text
            }.getOrNull() ?: return

            val rootJson = runCatching { JSONObject(jsonText) }.getOrNull() ?: return
            val m3u8 = rootJson.optJSONObject("sources")?.optString("file")?.trim().orEmpty()
            if (!m3u8.startsWith("http")) return

            val generated = generateM3u8(serverName, m3u8, host, headers = playbackHeaders)
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback(
                    newExtractorLink(serverName, serverName, m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$host/"
                        this.headers = playbackHeaders
                    }
                )
            }

            rootJson.optJSONArray("tracks")?.let { tracks ->
                for (i in 0 until tracks.length()) {
                    val tr = tracks.optJSONObject(i) ?: continue
                    val kind = tr.optString("kind")
                    if (kind != "captions" && kind != "subtitles") continue
                    val file = tr.optString("file").trim()
                    if (!file.startsWith("http")) continue
                    subtitleCallback(newSubtitleFile(tr.optString("label", "Unknown"), file) { this.headers = playbackHeaders })
                }
            }
        }
    }
}

open class Modiplay : ExtractorApi() {
    override val name = "Modiplay"
    override val mainUrl = "https://rozgarlelo.modiplay.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val urls = Regex("""switchServer\(['"](https?:\/\/[^'"]+)""").findAll(doc.toString()).map { it.groupValues[1] }.toList()
        urls.mfSafeAmap { embed -> loadExtractor(embed, "", subtitleCallback, callback) }
    }
}

class Iqsmartgames : GDMirrorbot() {
    override var name = "Iqsmartgames"
    override var mainUrl = "https://streams.iqsmartgames.com"
    override var requiresReferer = true
}

