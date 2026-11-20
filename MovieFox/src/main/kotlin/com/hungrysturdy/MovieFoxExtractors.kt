package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup

@CloudstreamPlugin
class MovieFoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieFoxProvider())
        registerExtractorAPI(MovieFoxPixelDrainExtractor())
        registerExtractorAPI(MovieFoxSpeedoStreamExtractor())
        registerExtractorAPI(MovieFoxVidHideExtractor())
        registerExtractorAPI(MovieFoxStreamWishExtractor())
        registerExtractorAPI(MovieFoxFileMoonExtractor())
        registerExtractorAPI(MovieFoxHubCloudExtractor())
        registerExtractorAPI(MovieFoxVCloudExtractor())

        openSettings = { ctx ->
            MovieFoxSettingsDialog.show(ctx)
        }
    }
}

class MovieFoxPixelDrainExtractor : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringBefore("?").substringBefore("#").substringAfterLast("/")
        if (id.isBlank()) return
        val host = if (url.contains("pixeldrain.dev", true)) "https://pixeldrain.dev" else "https://pixeldrain.com"
        val directUrl = "$host/api/file/$id"
        callback(
            newExtractorLink(
                source = name,
                name = "PixelDrain Direct",
                url = directUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$host/"
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$host/")
            }
        )
    }
}

class MovieFoxSpeedoStreamExtractor : ExtractorApi() {
    override val name = "SpeedoStream"
    override val mainUrl = "https://speedostream1.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = url.replace("\\/", "/").trim()
        val ref = referer ?: "$mainUrl/"
        val html = runCatching {
            app.get(
                clean,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to ref),
                timeout = 5L
            ).text
        }.getOrNull().orEmpty()
        if (html.isBlank()) return

        val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""sources\s*:\s*\[\s*\{?\s*file\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(html)?.value
            ?: return

        callback(
            newExtractorLink(
                source = name,
                name = "SpeedoStream HLS",
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = clean
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to clean)
            }
        )
    }
}

open class MovieFoxGenericPackedExtractor : ExtractorApi() {
    override val name = "GenericHLS"
    override val mainUrl = "https://vidhidevip.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: "$mainUrl/"
        val html = runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to ref),
                timeout = 5L
            ).text
        }.getOrNull().orEmpty()
        if (html.isBlank()) return

        val unpacked = getAndUnpack(html)
        val searchSpace = "$html\n$unpacked"

        val m3u8 = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(searchSpace)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'\s<>\\]+\.m3u8(?:\?[^"'\s<>\\]*)?""").find(searchSpace)?.value
            ?: return

        callback(
            newExtractorLink(
                source = name,
                name = "$name HLS",
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url)
            }
        )
    }
}

class MovieFoxVidHideExtractor : MovieFoxGenericPackedExtractor() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhidepro.com"
}

class MovieFoxStreamWishExtractor : MovieFoxGenericPackedExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
}

class MovieFoxFileMoonExtractor : MovieFoxGenericPackedExtractor() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
}

class MovieFoxHubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.ink"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT), timeout = 5L)
        }.getOrNull() ?: return
        val doc = Jsoup.parse(res.text)
        val genHref = doc.select("a.btn, a[download], a[href]")
            .firstOrNull { it.text().contains("generate", true) || it.attr("href").contains("hubcloud", true) }
            ?.attr("href")?.trim()

        val targetHtml = if (!genHref.isNullOrBlank()) {
            val fullGen = if (genHref.startsWith("http")) genHref else "${url.substringBefore("/", url)}/${genHref.trimStart('/')}"
            runCatching {
                app.get(fullGen, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to res.url), timeout = 5L).text
            }.getOrDefault(res.text)
        } else {
            res.text
        }

        val pageDoc = Jsoup.parse(targetHtml)
        pageDoc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            val label = a.text().trim()
            if (!href.startsWith("http")) return@forEach
            when {
                href.contains("pixeldrain", true) -> {
                    val id = href.substringBefore("?").substringAfterLast("/")
                    callback(
                        newExtractorLink("HubCloud", "HubCloud • PixelDrain", "https://pixeldrain.com/api/file/$id", ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    )
                }
                href.contains(".r2.dev", true) || href.contains(".r2.cloudflarestorage.com", true) || label.contains("FSL", true) -> {
                    val tag = if (href.contains("cloudflarestorage", true) || label.contains("FSL2", true)) "FSL2" else "FSL"
                    callback(
                        newExtractorLink("HubCloud", "HubCloud • $tag", href, ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    )
                }
            }
        }
    }
}

class MovieFoxVCloudExtractor : ExtractorApi() {
    override val name = "VCloud"
    override val mainUrl = "https://vcloud.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://nexdrive.beer/", "Cookie" to "xla=s4t"),
                timeout = 5L
            ).text
        }.getOrNull().orEmpty()
        if (html.isBlank()) return

        val encoded = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
        val decodedUrl = encoded?.let { runCatching { base64Decode(base64Decode(it.trim())) }.getOrNull() }
            ?: Regex("""var\s+url\s*=\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: return

        val finalHtml = runCatching {
            app.get(decodedUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url), timeout = 5L).text
        }.getOrDefault(html)

        Jsoup.parse(finalHtml).select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) return@forEach
            if (href.contains("pixeldrain", true)) {
                val id = href.substringBefore("?").substringAfterLast("/")
                callback(
                    newExtractorLink("VCloud", "VCloud • PixelDrain", "https://pixeldrain.com/api/file/$id", ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
            } else if (href.contains(".r2.dev", true) || href.contains(".r2.cloudflarestorage.com", true)) {
                callback(
                    newExtractorLink("VCloud", "VCloud • FSL", href, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
            }
        }
    }
}
