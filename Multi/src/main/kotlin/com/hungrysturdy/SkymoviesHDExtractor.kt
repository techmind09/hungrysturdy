package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.util.Calendar

data class ExtractedServer(
    val name: String,
    val url: String,
    val referer: String? = null,
    val isDirect: Boolean = false
)

class MirrorServerExtractor(
    private val mainUrl: String = "https://example.com"
) {

    /**
     * Entry point: Kisi bhi link (mCloud, VegaDrive, ya Dotflix) se servers extract karta hai
     */
    suspend fun extractAllServers(
        url: String,
        referer: String? = mainUrl,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val results = mutableListOf<ExtractedServer>()

        when {
            cleanUrl.contains("mcloud", ignoreCase = true) ||
                cleanUrl.contains("hubcloud", ignoreCase = true) ||
                cleanUrl.contains("pixeldrain", ignoreCase = true) -> {
                results.addAll(extractMCloudServers(cleanUrl, referer))
            }

            cleanUrl.contains("vegadrive", ignoreCase = true) -> {
                results.addAll(extractVegaDriveServers(cleanUrl, referer))
            }

            cleanUrl.contains("dotflix", ignoreCase = true) -> {
                results.addAll(extractDotflixServers(cleanUrl, referer))
            }

            else -> {
                // Fallback: HTML fetch karke detect karo ki kaunsa page hai
                val doc = app.get(cleanUrl, referer = referer).document
                val html = doc.html()
                when {
                    html.contains("FSL Server", true) || html.contains("data-minute-base-url", true) ->
                        results.addAll(parseMCloudDocument(html, cleanUrl))

                    html.contains("Vegadrop", true) || html.contains("/api/file/", true) ->
                        results.addAll(parseVegaDriveDocument(html, cleanUrl))

                    html.contains("/api/extract-download", true) || html.contains("sharingCode", true) ->
                        results.addAll(parseDotflixDocument(html, cleanUrl))
                }
            }
        }

        results.distinctBy { "${it.name}|${it.url}" }.forEach { server ->
            callback(
                newExtractorLink(
                    source = server.name,
                    name = server.name,
                    url = server.url,
                    type = if (server.isDirect) ExtractorLinkType.VIDEO else INFER_TYPE
                ) {
                    this.referer = server.referer ?: cleanUrl
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    // =========================================================================
    // 1. mCloud Extractor (FSL Server + Minute Token, PixelServer, 10Gbps Server)
    // =========================================================================
    suspend fun extractMCloudServers(url: String, referer: String? = null): List<ExtractedServer> {
        val res = app.get(url, referer = referer)
        return parseMCloudDocument(res.text, res.url)
    }

    private fun parseMCloudDocument(html: String, pageUrl: String): List<ExtractedServer> {
        val doc = Jsoup.parse(html, pageUrl)
        val servers = mutableListOf<ExtractedServer>()

        // FSL Server (data-minute-base-url me current minute ka '1' + MM append hota hai)
        val fslEl = doc.selectFirst("#fsl[data-minute-base-url], [data-minute-base-url]")
        val minuteBaseUrl = fslEl?.attr("data-minute-base-url")?.trim()
        if (!minuteBaseUrl.isNullOrEmpty()) {
            val minutes = Calendar.getInstance().get(Calendar.MINUTE)
            val minuteStr = minutes.toString().padStart(2, '0')
            val fslFinalUrl = minuteBaseUrl + "1" + minuteStr
            servers.add(
                ExtractedServer(
                    name = "mCloud - FSL Server",
                    url = fslFinalUrl,
                    referer = pageUrl,
                    isDirect = true
                )
            )
        }

        // Baaki saare <a> links scan karo (PixelServer, 10Gbps Server, etc.)
        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href").trim()
            if (href.isEmpty() || href.startsWith("javascript:", true) || href.startsWith("#")) continue

            val label = a.text().trim()
            val lowerHref = href.lowercase()
            val lowerLabel = label.lowercase()

            when {
                lowerHref.contains("pixeldrain.dev/u/") || lowerLabel.contains("pixelserver") -> {
                    servers.add(
                        ExtractedServer(
                            name = "mCloud - ${label.ifBlank { "PixelServer" }}",
                            url = href,
                            referer = pageUrl
                        )
                    )
                }

                lowerHref.contains("hubcloud") || lowerLabel.contains("10gbps") || lowerLabel.contains("server") -> {
                    if (!lowerHref.contains("t.me") && !lowerHref.contains("telegram")) {
                        servers.add(
                            ExtractedServer(
                                name = "mCloud - ${label.ifBlank { "10Gbps Server" }}",
                                url = href,
                                referer = pageUrl
                            )
                        )
                    }
                }
            }
        }

        return servers
    }

    // =========================================================================
    // 2. VegaDrive Extractor (Vegadrop, Transferit, Gofile, Buzzheavier, VikingFile + API Poll)
    // =========================================================================
    suspend fun extractVegaDriveServers(url: String, referer: String? = null): List<ExtractedServer> {
        val res = app.get(url, referer = referer)
        val servers = parseVegaDriveDocument(res.text, res.url).toMutableList()

        // Agar page par /api/file/<id> polling endpoint hai to usse bhi check karo
        val apiMatch = Regex("""/api/file/([A-Za-z0-9_-]+)""").find(res.text)
        if (apiMatch != null) {
            val fileId = apiMatch.groupValues[1]
            val origin = getOrigin(res.url)
            try {
                val apiJson = app.get("$origin/api/file/$fileId", referer = res.url).text
                val json = JSONObject(apiJson)
                val directUrl = json.optString("downloadUrl").ifBlank {
                    json.optString("url")
                }
                if (directUrl.isNotBlank() && directUrl != "null") {
                    servers.add(
                        ExtractedServer(
                            name = "VegaDrive - API Direct",
                            url = resolveUrl(origin, directUrl),
                            referer = res.url,
                            isDirect = true
                        )
                    )
                }
            } catch (_: Exception) {
                // Ignore polling error if file is still processing
            }
        }

        return servers
    }

    private fun parseVegaDriveDocument(html: String, pageUrl: String): List<ExtractedServer> {
        val doc = Jsoup.parse(html, pageUrl)
        val servers = mutableListOf<ExtractedServer>()

        val mirrorKeywords = listOf(
            "skydrop" to "Vegadrop 10Gbps Direct",
            "transferit" to "Transferit",
            "gofile" to "Gofile",
            "buzzheavier" to "Buzzheavier",
            "vikingfile" to "VikingFile"
        )

        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href").trim()
            if (href.isEmpty() || href.startsWith("javascript:", true) || href.startsWith("#")) continue

            val text = a.text().trim()
            val lowerHref = href.lowercase()
            val lowerText = text.lowercase()

            val matchedMirror = mirrorKeywords.firstOrNull { (key, _) ->
                lowerHref.contains("/$key") || lowerHref.contains(key) || lowerText.contains(key)
            }

            if (matchedMirror != null) {
                val niceName = text.ifBlank { matchedMirror.second }
                servers.add(
                    ExtractedServer(
                        name = "VegaDrive - $niceName",
                        url = href,
                        referer = pageUrl,
                        isDirect = matchedMirror.first == "skydrop"
                    )
                )
            } else if (lowerHref.contains("/d/")) {
                servers.add(
                    ExtractedServer(
                        name = "VegaDrive - ${text.ifBlank { "Direct Link" }}",
                        url = href,
                        referer = pageUrl
                    )
                )
            }
        }

        return servers
    }

    // =========================================================================
    // 3. DOTFLIX Extractor (POST /api/extract-download + sharingCode & proof)
    // =========================================================================
    suspend fun extractDotflixServers(url: String, referer: String? = null): List<ExtractedServer> {
        val res = app.get(url, referer = referer)
        return parseDotflixDocument(res.text, res.url)
    }

    private suspend fun parseDotflixDocument(html: String, pageUrl: String): List<ExtractedServer> {
        val doc = Jsoup.parse(html, pageUrl)
        val origin = getOrigin(pageUrl)
        val servers = mutableListOf<ExtractedServer>()

        // sharingCode aur optional proof extract karo
        val sharingCode = Regex("""sharingCode\s*[:=]\s*["']([a-fA-F0-9]+)["']""")
            .find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("input[name=sharingCode], [data-sharing-code]")
                ?.let { it.attr("value").ifBlank { it.attr("data-sharing-code") } }
            ?: ""

        val proof = Regex("""[?&]proof=([A-Za-z0-9_%-]+)""").find(pageUrl)?.groupValues?.get(1)
            ?: Regex("""proof\s*[:=]\s*["']([A-Za-z0-9_%-]+)["']""").find(html)?.groupValues?.get(1)

        if (sharingCode.isNotBlank()) {
            val endpoint = if (!proof.isNullOrBlank()) {
                "$origin/api/extract-download?proof=$proof"
            } else {
                "$origin/api/extract-download"
            }

            val payload = JSONObject().apply {
                put("sharingCode", sharingCode)
            }.toString()

            try {
                val apiResponse = app.post(
                    url = endpoint,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "Origin" to origin
                    ),
                    referer = pageUrl,
                    requestBody = payload.toRequestBody()
                ).text

                val json = JSONObject(apiResponse)
                val downloadUrl = json.optString("downloadUrl")
                    .ifBlank { json.optString("url") }
                    .ifBlank { json.optString("link") }

                if (downloadUrl.isNotBlank() && downloadUrl != "null") {
                    servers.add(
                        ExtractedServer(
                            name = "DOTFLIX - Direct Download",
                            url = resolveUrl(origin, downloadUrl),
                            referer = pageUrl,
                            isDirect = true
                        )
                    )
                }

                // Agar JSON me multiple mirrors/links array aaye to unhe bhi add karo
                val mirrorsArray = json.optJSONArray("mirrors") ?: json.optJSONArray("servers")
                if (mirrorsArray != null) {
                    for (i in 0 until mirrorsArray.length()) {
                        val item = mirrorsArray.optJSONObject(i)
                        val mUrl = item?.optString("url").orEmpty()
                        val mName = item?.optString("name").orEmpty().ifBlank { "Mirror ${i + 1}" }
                        if (mUrl.isNotBlank()) {
                            servers.add(
                                ExtractedServer(
                                    name = "DOTFLIX - $mName",
                                    url = resolveUrl(origin, mUrl),
                                    referer = pageUrl
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback niche handle hoga
            }
        }

        // Fallback share link if present
        doc.select("a[href*=/share/]").firstOrNull()?.let { a ->
            val shareHref = a.attr("abs:href").trim()
            if (shareHref.isNotBlank()) {
                servers.add(
                    ExtractedServer(
                        name = "DOTFLIX - Share Page",
                        url = shareHref,
                        referer = pageUrl
                    )
                )
            }
        }

        return servers
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private fun getOrigin(url: String): String {
        return try {
            val uri = URI(url)
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$portPart"
        } catch (_: Exception) {
            url.substringBefore("/", url)
        }
    }

    private fun resolveUrl(origin: String, rawUrl: String): String {
        return when {
            rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true) -> rawUrl
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("/") -> "$origin$rawUrl"
            else -> "$origin/$rawUrl"
        }
    }
}
