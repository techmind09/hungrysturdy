package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLDecoder

open class HubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.mov"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = collectLinks(url, referer, subtitleCallback)
        for (link in links) {
            callback.invoke(link)
        }
    }

    internal suspend fun collectLinks(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val results = mutableListOf<ExtractorLink>()
        val seenUrls = linkedSetOf<String>()

        val pageUrl: String = resolveUrl(url, mainUrl)
        val response = app.get(pageUrl, referer = referer ?: mainUrl)
        val basePageUrl: String = response.url.ifBlank { pageUrl }
        val doc = response.document
        val html: String = response.text

        val titleText: String = doc.selectFirst(".card-header")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.title().trim().ifBlank { name }

        val episodeTag: String? = Regex("""\bS\d+E\d+\b""", RegexOption.IGNORE_CASE)
            .find(titleText)?.value?.uppercase()
        val labelPrefix: String = if (episodeTag != null) "$name $episodeTag" else name
        val baseQuality: Int = detectQualityFromText(titleText)

        suspend fun addDirectLink(serverLabel: String, rawMediaUrl: String, refUrl: String = basePageUrl) {
            val cleanUrl: String = unescapeUrl(rawMediaUrl).trim()
            if (cleanUrl.isBlank() || !seenUrls.add(cleanUrl)) return

            val linkType = if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            val resolvedQuality: Int = detectQualityFromText(cleanUrl).let { detected ->
                if (detected != Qualities.Unknown.value) detected else baseQuality
            }

            results.add(
                newExtractorLink(
                    source = name,
                    name = "$labelPrefix - $serverLabel",
                    url = cleanUrl,
                    type = linkType
                ) {
                    this.referer = refUrl
                    this.quality = resolvedQuality
                }
            )
        }

        // 1. Page anchors
        val anchors = doc.select("a[href]")
        for (el in anchors) {
            val hrefRaw: String = el.attr("href").trim()
            if (hrefRaw.isBlank() || hrefRaw.startsWith("#") || hrefRaw.startsWith("javascript:")) {
                continue
            }

            val fullHref: String = resolveUrl(hrefRaw, basePageUrl)
            val btnText: String = el.text().replace(Regex("""\s+"""), " ").trim()

            when {
                fullHref.contains("r2.cloudflarestorage.com", ignoreCase = true) ||
                    fullHref.contains(".mkv", ignoreCase = true) ||
                    fullHref.contains(".mp4", ignoreCase = true) ||
                    fullHref.contains(".m3u8", ignoreCase = true) -> {
                    val serverName: String = btnText.ifBlank { "Direct Server" }
                    addDirectLink(serverName, fullHref)
                }

                fullHref.contains("hbplay.pages.dev", ignoreCase = true) -> {
                    val decoded: String? = decodeBase64ParamU(fullHref)
                    if (!decoded.isNullOrBlank()) {
                        addDirectLink("Watch Online", decoded)
                    }
                }

                fullHref.contains("pixel.hubcloud", ignoreCase = true) ||
                    fullHref.contains("gpdl.hubcloud", ignoreCase = true) -> {
                    val serverName: String = btnText.ifBlank { "10Gbps Server" }
                    val subLinks = extractServerLinks(fullHref, basePageUrl, labelPrefix, serverName, baseQuality)
                    for (sub in subLinks) {
                        if (seenUrls.add(sub.url)) {
                            results.add(sub)
                        }
                    }
                }

                fullHref.contains("fuckingfast.net", ignoreCase = true) ||
                    fullHref.contains("pixeldrain.dev", ignoreCase = true) -> {
                    if (seenUrls.add(fullHref)) {
                        loadExtractor(fullHref, basePageUrl, subtitleCallback) { extLink ->
                            results.add(extLink)
                        }
                    }
                }
            }
        }

        // 2. Regional R2 mirror array `L=[...]`
        val lMatch = Regex("""L\s*=\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL)).find(html)
        val arrayBody: String = lMatch?.groupValues?.getOrNull(1).orEmpty()
        if (arrayBody.isNotBlank()) {
            val mirrorMatches = Regex("""https?://[^\s"']+r2\.cloudflarestorage\.com[^\s"']*""")
                .findAll(arrayBody)
                .map { it.value.trim() }
                .filter { it.isNotBlank() }
                .toList()

            mirrorMatches.forEachIndexed { index, mirrorUrl ->
                addDirectLink("T3 Mirror ${index + 1}", mirrorUrl)
            }
        }

        // 3. `var pxl = "..."` fallback
        val pxlMatch = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)
        val pxlRaw: String = pxlMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (pxlRaw.isNotBlank()) {
            val fixedPxl: String = resolveUrl(pxlRaw, basePageUrl)
            if (seenUrls.add(fixedPxl)) {
                loadExtractor(fixedPxl, basePageUrl, subtitleCallback) { extLink ->
                    results.add(extLink)
                }
            }
        }

        return results
    }

    private suspend fun extractServerLinks(
        serverUrl: String,
        refererUrl: String,
        labelPrefix: String,
        serverLabel: String,
        baseQuality: Int
    ): List<ExtractorLink> {
        val out = mutableListOf<ExtractorLink>()
        val doc = try {
            app.get(serverUrl, referer = refererUrl).document
        } catch (_: Exception) {
            return out
        }

        val candidates = mutableListOf<String>()
        for (el in doc.select("a[href], source[src], video[src]")) {
            val raw: String = if (el.tagName().equals("a", ignoreCase = true)) {
                el.attr("href").trim()
            } else {
                el.attr("src").trim()
            }
            if (raw.isNotBlank() && !raw.startsWith("#") && !raw.startsWith("javascript:")) {
                val fixed: String = resolveUrl(raw, serverUrl)
                if (!candidates.contains(fixed)) {
                    candidates.add(fixed)
                }
            }
        }

        for (link in candidates) {
            if (link.contains("r2.cloudflarestorage.com", ignoreCase = true) ||
                link.contains(".mkv", ignoreCase = true) ||
                link.contains(".mp4", ignoreCase = true) ||
                link.contains(".m3u8", ignoreCase = true)
            ) {
                val clean: String = unescapeUrl(link)
                val linkType = if (clean.contains(".m3u8", ignoreCase = true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
                val resolvedQuality: Int = detectQualityFromText(clean).let { q ->
                    if (q != Qualities.Unknown.value) q else baseQuality
                }
                out.add(
                    newExtractorLink(
                        source = name,
                        name = "$labelPrefix - $serverLabel",
                        url = clean,
                        type = linkType
                    ) {
                        this.referer = serverUrl
                        this.quality = resolvedQuality
                    }
                )
            }
        }

        return out
    }

    private fun resolveUrl(rawUrl: String, baseUrl: String): String {
        val trimmed: String = rawUrl.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> {
                val origin: String = Regex("""^(https?://[^/]+)""").find(baseUrl)?.groupValues?.getOrNull(1)
                    ?: mainUrl
                "$origin/${trimmed.trimStart('/')}"
            }
        }
    }

    private fun decodeBase64ParamU(url: String): String? {
        return try {
            val query: String = URI(url).rawQuery ?: return null
            val encodedPart: String = query.split("&")
                .firstOrNull { it.startsWith("u=", ignoreCase = true) }
                ?.substringAfter("=")
                ?: return null
            val decodedParam: String = URLDecoder.decode(encodedPart, "UTF-8")
            val bytes = Base64.decode(decodedParam, Base64.DEFAULT)
            val decodedStr: String = String(bytes, Charsets.UTF_8).trim()
            if (decodedStr.startsWith("http", ignoreCase = true)) decodedStr else null
        } catch (_: Exception) {
            null
        }
    }

    private fun unescapeUrl(input: String): String {
        return input.replace("\\u0026", "&").replace("&amp;", "&")
    }

    private fun detectQualityFromText(text: String): Int = when {
        text.contains("2160p", ignoreCase = true) || text.contains("4k", ignoreCase = true) -> Qualities.P2160.value
        text.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
        text.contains("720p", ignoreCase = true) -> Qualities.P720.value
        text.contains("480p", ignoreCase = true) -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }
}

