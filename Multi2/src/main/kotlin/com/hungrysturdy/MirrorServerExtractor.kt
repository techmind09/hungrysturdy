package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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

    suspend fun extractAllServers(
        url: String,
        referer: String? = mainUrl,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace("&amp;", "&").trim()
        if (cleanUrl.isBlank()) return

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
                val res = app.get(cleanUrl, referer = referer)
                val html = res.text
                val activePage = if (res.url.isNotBlank()) res.url else cleanUrl
                when {
                    html.contains("FSL Server", true) || html.contains("data-minute-base-url", true) ->
                        results.addAll(parseMCloudDocument(html, activePage))

                    html.contains("Vegadrop", true) || html.contains("/api/file/", true) ->
                        results.addAll(parseVegaDriveDocument(html, activePage))

                    html.contains("/api/extract-download", true) || html.contains("sharingCode", true) ->
                        results.addAll(parseDotflixDocument(html, activePage))
                }
            }
        }

        results.distinctBy { "${it.name}|${it.url}" }.forEach { server ->
            val refUrl = server.referer ?: cleanUrl
            val resolvedUrl = resolveUrl(getOrigin(refUrl), server.url)
            val linkType = if (server.isDirect) ExtractorLinkType.VIDEO else INFER_TYPE
            callback.invoke(
                newExtractorLink(
                    source = server.name,
                    name = server.name,
                    url = resolvedUrl,
                    type = linkType
                ) {
                    this.referer = refUrl
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    suspend fun extractMCloudServers(url: String, referer: String? = null): List<ExtractedServer> {
        val res = app.get(url, referer = referer)
        val activePage = if (res.url.isNotBlank()) res.url else url
        return parseMCloudDocument(res.text, activePage)
    }

    private fun parseMCloudDocument(html: String, pageUrl: String): List<ExtractedServer> {
        val doc = Jsoup.parse(html, pageUrl)
        val servers = mutableListOf<ExtractedServer>()

        val fslEl = doc.selectFirst("#fsl[data-minute-base-url], [data-minute-base-url]")
        val minuteBaseUrl = fslEl?.attr("data-minute-base-url")?.trim() ?: ""
        if (minuteBaseUrl.isNotEmpty()) {
            val minutes = Calendar.getInstance().get(Calendar.MINUTE)
            val minuteStr = minutes.toString().padStart(2, '0')
            val fslFinalUrl = (minuteBaseUrl + "1" + minuteStr).replace("&amp;", "&").trim()
            servers.add(
                ExtractedServer(
                    name = "mCloud - FSL Server",
                    url = fslFinalUrl,
                    referer = pageUrl,
                    isDirect = true
                )
            )
        }

        for (a in doc.select("a[href]")) {
            val absHref = a.attr("abs:href")
            val rawHref = if (absHref.isNotBlank()) absHref else a.attr("href")
            val href = rawHref.replace("&amp;", "&").trim()
            if (href.isEmpty() || href.startsWith("javascript:", true) || href.startsWith("#")) continue

            val label = a.text().trim()
            val lowerHref = href.lowercase()
            val lowerLabel = label.lowercase()

            when {
                lowerHref.contains("pixeldrain") || lowerLabel.contains("pixelserver") -> {
                    val normalized = href
                        .replace("pixeldrain.dev/u/", "pixeldrain.dev/api/file/")
                        .replace("pixeldrain.com/u/", "pixeldrain.com/api/file/")
                    val finalPxl = if (normalized.contains("/api/file/") && !normalized.contains("?download")) {
                        "$normalized?download"
                    } else {
                        href
                    }
                    val pxlName = if (label.isNotBlank()) label else "PixelServer"
                    servers.add(
                        ExtractedServer(
                            name = "mCloud - $pxlName",
                            url = finalPxl,
                            referer = pageUrl
                        )
                    )
                }

                lowerHref.contains("hubcloud") || lowerLabel.contains("10gbps") || lowerLabel.contains("server") -> {
                    if (!lowerHref.contains("t.me") && !lowerHref.contains("telegram")) {
                        val srvName = if (label.isNotBlank()) label else "10Gbps Server"
                        servers.add(
                            ExtractedServer(
                                name = "mCloud - $srvName",
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

    suspend fun extractVegaDriveServers(url: String, referer: String? = null): List<ExtractedServer> {
        val res = app.get(url, referer = referer)
        val activeUrl = if (res.url.isNotBlank()) res.url else url
        val servers = parseVegaDriveDocument(res.text, activeUrl).toMutableList()

        val apiMatch = Regex("""/api/file/([A-Za-z0-9_-]+)""").find(res.text)
        if (apiMatch != null) {
            val fileId = apiMatch.groupValues[1]
            val origin = getOrigin(activeUrl)
            try {
                val apiJson = app.get("$origin/api/file/$fileId", referer = activeUrl).text.trim()
                if (apiJson.startsWith("{")) {
                    val json = JSONObject(apiJson)
                    val dlCandidate = json.optString("downloadUrl")
                    val urlCandidate = if (dlCandidate.isNotBlank()) dlCandidate else json.optString("url")
                    val linkCandidate = if (urlCandidate.isNotBlank()) urlCandidate else json.optString("link")
                    val directUrl = linkCandidate.replace("\\/", "/").trim()

                    if (directUrl.isNotBlank() && directUrl != "null") {
                        servers.add(
                            ExtractedServer(
                                name = "VegaDrive - API Direct",
                                url = resolveUrl(origin, directUrl),
                                referer = activeUrl,
                                isDirect = true
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        return servers
    }

    private fun parseVegaDriveDocument(html: String, pageUrl: String): List<ExtractedServer> {
        val doc = Jsoup.parse(html, pageUrl)
        val origin = getOrigin(pageUrl)
        val servers = mutableListOf<ExtractedServer>()

        val mirrorKeywords = listOf(
            "skydrop" to "Vegadrop 10Gbps Direct",
            "transferit" to "Transferit",
            "gofile" to "Gofile",
            "buzzheavier" to "Buzzheavier",
            "vikingfile" to "VikingFile"
        )

        for (a in doc.select("a[href]")) {
            val absHref = a.attr("abs:href")
            val rawHref = if (absHref.isNotBlank()) absHref else a.attr("href")
            val cleanedHref = rawHref.replace("&amp;", "&").trim()
            if (cleanedHref.isEmpty() || cleanedHref.startsWith("javascript:", true) || cleanedHref.startsWith("#")) continue

            val href = resolveUrl(origin, cleanedHref)
            if (href.contains("t.me") || href.contains("telegram")) continue

            val text = a.text().trim()
            val lowerHref = href.lowercase()
            val lowerText = text.lowercase()

            val matchedMirror = mirrorKeywords.firstOrNull { (key, _) ->
                lowerHref.contains("/$key") || lowerHref.contains(key) || lowerText.contains(key)
            }

            if (matchedMirror != null) {
                val niceName = if (text.isNotBlank()) text else matchedMirror.second
                servers.add(
                    ExtractedServer(
                        name = "VegaDrive - $niceName",
                        url = href,
                        referer = pageUrl,
                        isDirect = matchedMirror.first == "skydrop"
                    )
                )
            } else if (lowerHref.contains("/d/")) {
                val directName = if (text.isNotBlank()) text else "Direct Link"
                servers.add(
                    ExtractedServer(
                        name = "VegaDrive - $directName",
                        url = href,
                        referer = pageUrl
                    )
                )
            }
        }

        return servers
    }

    suspend fun extractDotflixServers(url: String, referer: String? = null): List<ExtractedServer> {
        val res = app.get(url, referer = referer)
        val activePage = if (res.url.isNotBlank()) res.url else url
        return parseDotflixDocument(res.text, activePage)
    }

    private suspend fun parseDotflixDocument(html: String, pageUrl: String): List<ExtractedServer> {
        val doc = Jsoup.parse(html, pageUrl)
        val origin = getOrigin(pageUrl)
        val servers = mutableListOf<ExtractedServer>()

        val regexCode = Regex("""sharingCode\s*[:=]\s*["']([a-fA-F0-9]+)["']""")
            .find(html)?.groupValues?.get(1)

        val domCodeEl = doc.selectFirst("input[name=sharingCode], [data-sharing-code]")
        val domCode = if (domCodeEl != null) {
            val attrVal = domCodeEl.attr("value")
            if (attrVal.isNotBlank()) attrVal else domCodeEl.attr("data-sharing-code")
        } else {
            ""
        }

        val sharingCode = if (!regexCode.isNullOrBlank()) regexCode else domCode

        val urlProof = Regex("""[?&]proof=([A-Za-z0-9_%-]+)""").find(pageUrl)?.groupValues?.get(1)
        val proof = if (!urlProof.isNullOrBlank()) {
            urlProof
        } else {
            Regex("""proof\s*[:=]\s*["']([A-Za-z0-9_%-]+)["']""").find(html)?.groupValues?.get(1)
        }

        if (sharingCode.isNotBlank()) {
            val endpoint = if (!proof.isNullOrBlank()) {
                "$origin/api/extract-download?proof=$proof"
            } else {
                "$origin/api/extract-download"
            }

            val payload = JSONObject().apply {
                put("sharingCode", sharingCode)
            }.toString()

            val requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            try {
                val apiResponse = app.post(
                    url = endpoint,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json, text/plain, */*",
                        "Origin" to origin
                    ),
                    referer = pageUrl,
                    requestBody = requestBody
                ).text.trim()

                if (apiResponse.startsWith("{")) {
                    val json = JSONObject(apiResponse)
                    val dlField = json.optString("downloadUrl")
                    val urlField = if (dlField.isNotBlank()) dlField else json.optString("url")
                    val linkField = if (urlField.isNotBlank()) urlField else json.optString("link")
                    val downloadUrl = linkField.replace("\\/", "/").trim()

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

                    val mirrorsArray = if (json.has("mirrors")) {
                        json.optJSONArray("mirrors")
                    } else {
                        json.optJSONArray("servers")
                    }

                    if (mirrorsArray != null) {
                        for (i in 0 until mirrorsArray.length()) {
                            val item = mirrorsArray.optJSONObject(i)
                            val rawMUrl = if (item != null) item.optString("url") else ""
                            val mUrl = rawMUrl.replace("\\/", "/").trim()
                            val rawMName = if (item != null) item.optString("name") else ""
                            val mName = if (rawMName.isNotBlank()) rawMName else "Mirror ${i + 1}"

                            if (mUrl.isNotBlank() && mUrl != "null") {
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
                }
            } catch (_: Exception) {
            }
        }

        doc.select("a[href*=/share/]").firstOrNull()?.let { a ->
            val absShare = a.attr("abs:href")
            val shareHref = (if (absShare.isNotBlank()) absShare else a.attr("href")).trim()
            if (shareHref.isNotBlank()) {
                servers.add(
                    ExtractedServer(
                        name = "DOTFLIX - Share Page",
                        url = resolveUrl(origin, shareHref),
                        referer = pageUrl
                    )
                )
            }
        }

        return servers
    }

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

