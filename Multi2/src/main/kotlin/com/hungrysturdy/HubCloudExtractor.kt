package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

open class HubCloudExtractor : ExtractorAPI() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.mov"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = fixUrl(url)
        val response = app.get(pageUrl, referer = referer ?: mainUrl)
        val basePageUrl = response.url.ifBlank { pageUrl }
        val doc = response.document
        val html = response.text

        val titleText = doc.selectFirst(".card-header")
            ?.text()
            ?.trim()
            ?.ifBlank { doc.title().trim() }
            ?: name

        val episodeTag = Regex("""\bS\d+E\d+\b""", RegexOption.IGNORE_CASE)
            .find(titleText)?.value?.uppercase()
        val labelPrefix = if (episodeTag != null) "$name $episodeTag" else name
        val baseQuality = detectQualityFromText(titleText)
        val emittedUrls = linkedSetOf<String>()

        suspend fun emitDirect(serverLabel: String, mediaUrl: String, ref: String = basePageUrl) {
            val cleanUrl = unescapeJsString(mediaUrl).trim()
            if (cleanUrl.isBlank() || !emittedUrls.add(cleanUrl)) return

            val linkType = if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$labelPrefix - $serverLabel",
                    url = cleanUrl,
                    type = linkType
                ) {
                    this.referer = ref
                    this.quality = detectQualityFromText(cleanUrl).let {
                        if (it != Qualities.Unknown.value) it else baseQuality
                    }
                }
            )
        }

        doc.select("a[href]").forEach { el ->
            val href = el.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:")) return@forEach

            val fullHref = fixUrl(href)
            val btnText = el.text().replace(Regex("""\s+"""), " ").trim()

            when {
                fullHref.contains("r2.cloudflarestorage.com", ignoreCase = true) ||
                    fullHref.contains(".mkv", ignoreCase = true) ||
                    fullHref.contains(".mp4", ignoreCase = true) ||
                    fullHref.contains(".m3u8", ignoreCase = true) -> {
                    val serverName = btnText.ifBlank { "Direct Server" }
                    emitDirect(serverName, fullHref)
                }

                fullHref.contains("hbplay.pages.dev", ignoreCase = true) -> {
                    decodeBase64ParamU(fullHref)?.let { decoded ->
                        emitDirect("Watch Online", decoded)
                    }
                }

                fullHref.contains("pixel.hubcloud", ignoreCase = true) ||
                    fullHref.contains("gpdl.hubcloud", ignoreCase = true) -> {
                    val serverName = btnText.ifBlank { "10Gbps Server" }
                    extractFromHubCloudServer(fullHref, basePageUrl, serverName, ::emitDirect)
                }

                fullHref.contains("fuckingfast.net", ignoreCase = true) ||
                    fullHref.contains("pixeldrain.dev", ignoreCase = true) -> {
                    if (emittedUrls.add(fullHref)) {
                        loadExtractor(fullHref, basePageUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        Regex("""L\s*=\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { arrayBody ->
                Regex("""https?://[^\s"']+r2\.cloudflarestorage\.com[^\s"']*""")
                    .findAll(arrayBody)
                    .forEachIndexed { idx, match ->
                        emitDirect("T3 Mirror ${idx + 1}", match.value)
                    }
            }

        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { pxlUrl ->
                val fixedPxl = fixUrl(pxlUrl)
                if (emittedUrls.add(fixedPxl)) {
                    loadExtractor(fixedPxl, basePageUrl, subtitleCallback, callback)
                }
            }
    }

    private suspend fun extractFromHubCloudServer(
        serverUrl: String,
        refererUrl: String,
        serverLabel: String,
        emitDirect: suspend (label: String, mediaUrl: String, ref: String) -> Unit
    ) {
        val doc = try {
            app.get(serverUrl, referer = refererUrl).document
        } catch (_: Exception) {
            return
        }

        val foundLinks = doc.select("a[href], source[src], video[src]")
            .mapNotNull { el ->
                val raw = if (el.tagName() == "a") el.attr("href") else el.attr("src")
                raw.trim().takeIf {
                    it.isNotBlank() && !it.startsWith("#") && !it.startsWith("javascript:")
                }?.let { fixUrl(it) }
            }
            .distinct()

        for (link in foundLinks) {
            if (link.contains("r2.cloudflarestorage.com", ignoreCase = true) ||
                link.contains(".mkv", ignoreCase = true) ||
                link.contains(".mp4", ignoreCase = true) ||
                link.contains(".m3u8", ignoreCase = true)
            ) {
                emitDirect(serverLabel, link, serverUrl)
            }
        }
    }

    private fun decodeBase64ParamU(url: String): String? {
        return try {
            val query = URI(url).rawQuery ?: return null
            val encodedU = query.split("&")
                .firstOrNull { it.startsWith("u=", ignoreCase = true) }
                ?.substringAfter("=")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: return null

            val decodedBytes = Base64.decode(encodedU, Base64.DEFAULT)
            String(decodedBytes, Charsets.UTF_8).trim().takeIf { it.startsWith("http", ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    private fun unescapeJsString(input: String): String {
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
