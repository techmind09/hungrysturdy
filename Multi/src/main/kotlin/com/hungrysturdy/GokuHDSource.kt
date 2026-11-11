package com.hungrysturdy

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

internal object GokuHDSources {

    private val deadHosts = Regex("""(?i)megaup|viking|vik1ng|telegram|t\.me""")

    private val buttonJunk = Regex(
        """(?i)vglist|t\.me|telegram|imdb\.com|google\.com/search|whatsapp|share|facebook|""" +
            """twitter|pinterest|report|hubcloud\.(foo|cx)|/admin|login|signup|\bidm\b|\bida\b|""" +
            """category|/tag/|/author/|/page/"""
    )

    fun isServerLink(href: String): Boolean {
        if (deadHosts.containsMatchIn(href)) return false
        val h = href.lowercase()
        return href.startsWith("http") && (
            h.contains("nexdrive") ||
                h.contains("vcloud") ||
                h.contains("vegadrive") ||
                h.contains("fastdl") ||
                h.contains("filebee") || h.contains("filepress") || h.contains("fpgo") ||
                h.contains("gofile") ||
                h.contains("hubcloud") ||
                h.contains("pixeldrain") ||
                h.contains("gpdl") ||
                h.contains(".r2.dev") || h.contains(".r2.cloudflarestorage.com") ||
                h.contains("buzzserver") || h.contains("10gbps")
            )
    }

    class DrivePage(
        val servers: List<Pair<String, String>>,
        val episodes: Map<Int, List<Pair<String, String>>>
    )

    private val pageCache = ConcurrentHashMap<String, DrivePage>()

    suspend fun fetchDrivePage(url: String): DrivePage? {
        pageCache[url]?.let { return it }
        val res = VegaNet.fetchWithCf(url, referer = "https://nexdrive.beer/") ?: return null
        val html = res.text
        if (Regex("""(?i)file (is )?deleted|not found""").containsMatchIn(html)) return null
        val page = parseDrivePage(html) ?: return null
        pageCache[url] = page
        return page
    }

    private fun parseDrivePage(html: String): DrivePage? {
        val doc = Jsoup.parse(html)
        val scope = doc.selectFirst(".entry, article, .post-inner") ?: doc

        val servers = mutableListOf<Pair<String, String>>()
        for (a in scope.select("a[href]")) {
            val href = a.attr("href").trim()
            val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                ?: a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank() || !isServerLink(href)) continue
            if (buttonJunk.containsMatchIn(href) || buttonJunk.containsMatchIn(label)) continue
            if (Regex("""(?i)batch|zip\b""").containsMatchIn(label)) continue
            servers.add(label to href)
        }

        val episodes = LinkedHashMap<Int, MutableList<Pair<String, String>>>()
        for (head in scope.select("h3, h4, h5, p:has(strong), p:has(b)")) {
            val text = head.text()
            val num = Regex("""(?i)ep(?:isode)?s?\s*:?\s*0*(\d{1,3})""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val links = mutableListOf<Pair<String, String>>()
            
            // Agar links usi <p> tag ke andar hain jisme Episode heading hai
            for (a in head.select("a[href]")) {
                val href = a.attr("href").trim()
                val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                    ?: a.text().replace(Regex("""\s+"""), " ").trim()
                if (label.isNotBlank() && isServerLink(href) && !buttonJunk.containsMatchIn(href) && !buttonJunk.containsMatchIn(label)) {
                    links.add(label to href)
                }
            }

            var sib = head.nextElementSibling()
            while (sib != null && !listOf("h3", "h4", "h5").contains(sib.tagName())) {
                // Agar next paragraph mein naya episode number hai toh break karein
                if (sib.tagName() == "p" && Regex("""(?i)ep(?:isode)?s?\s*:?\s*0*(\d{1,3})""").containsMatchIn(sib.text())) {
                    break
                }
                for (a in sib.select("a[href]")) {
                    val href = a.attr("href").trim()
                    val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                        ?: a.text().replace(Regex("""\s+"""), " ").trim()
                    if (label.isBlank() || !isServerLink(href)) continue
                    if (buttonJunk.containsMatchIn(href) || buttonJunk.containsMatchIn(label)) continue
                    links.add(label to href)
                }
                sib = sib.nextElementSibling()
            }
            if (links.isNotEmpty()) {
                val list = episodes.getOrPut(num) { mutableListOf() }
                list.addAll(links)
            }
        }
        val finalEpisodes = episodes.mapValues { it.value.distinctBy { pair -> pair.second } }
        return DrivePage(servers.distinctBy { it.second }, finalEpisodes)
    }

    class Server(val name: String, val url: String, val headers: Map<String, String> = emptyMap())

    suspend fun resolve(href: String, episodeNum: Int? = null): List<Server> {
        if (!href.startsWith("http") || deadHosts.containsMatchIn(href)) return emptyList()
        val host = hostOf(href)
        return try {
            when {
                host.contains("nexdrive") -> {
                    val page = fetchDrivePage(href) ?: return emptyList()
                    val out = mutableListOf<Server>()
                    val targets = if (episodeNum != null) {
                        page.episodes[episodeNum] ?: page.servers.takeIf { page.episodes.isEmpty() }
                    } else {
                        page.servers.ifEmpty { page.episodes.values.flatten() }
                    }
                    for ((_, link) in targets.orEmpty()) {
                        if (link.contains("nexdrive", true)) {
                            val nested = fetchDrivePage(link)
                            val innerTargets = if (episodeNum != null) {
                                nested?.episodes?.get(episodeNum) ?: nested?.servers
                            } else {
                                nested?.servers ?: nested?.episodes?.values?.flatten()
                            }
                            for ((_, innerLink) in innerTargets.orEmpty()) {
                                out.addAll(resolve(innerLink, episodeNum))
                            }
                        } else {
                            out.addAll(resolve(link, episodeNum))
                        }
                    }
                    out.distinctBy { it.url }
                }
                host.contains("vcloud") -> vcloudServers(href)
                host.contains("vegadrive") -> listOfNotNull(vegadriveSkydrop(href))
                host.contains("fastdl") -> fastdlDirect(href)?.let { listOf(Server("G-Direct", it)) } ?: emptyList()
                host.contains("filebee") || host.contains("filepress") || host.contains("fpgo") ->
                    filepressDirect(href)?.let { listOf(Server("Filepress", it)) } ?: emptyList()
                host.contains("gofile") -> gofileDirect(href)?.let { listOf(it) } ?: emptyList()
                host.contains("gpdl") -> gpdlDirect(href)?.let { listOf(Server("10Gbps", it)) } ?: emptyList()
                host.contains("hubcloud") -> hubcloudServers(href)
                else -> directServer(href)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hostOf(url: String): String = try {
        URI(url).host?.lowercase() ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun directServer(href: String): List<Server> {
        val h = href.lowercase()
        return when {
            h.contains(".r2.cloudflarestorage.com") -> listOf(Server("FSLv2", href))
            h.contains(".r2.dev") -> listOf(Server("FSL", href))
            h.contains("pixeldrain") -> listOf(Server("PixelDrain", pixelApi(href)))
            else -> emptyList()
        }
    }

    private fun pixelApi(href: String): String {
        val id = href.substringBefore("?").substringBefore("#").substringAfterLast("/")
        return "${VegaNet.originOf(href)}/api/file/$id"
    }

    private fun vcloudHeaders(referer: String?): Map<String, String> {
        val h = VegaNet.browserHeaders(referer).toMutableMap()
        h["Cookie"] = "xla=s4t"
        return h
    }

    private val vcloudTokenRegex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]""")
    private val plainUrlRegex = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")

    private suspend fun vcloudTarget(url: String): String? {
        val first = VegaNet.fetchWithCf(url, headers = vcloudHeaders(null), timeout = 25L) ?: return null
        val base = VegaNet.originOf(first.url)
        var target = vcloudTokenRegex.find(first.text)?.groupValues?.get(1)
            ?.let { VegaNet.decodeDoubleBase64(it) }
            ?: plainUrlRegex.find(first.text)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        if (target == null) {
            val hop = Jsoup.parse(first.text).selectFirst("div.main h4 a")?.attr("href")?.trim() ?: return null
            val hopUrl = if (hop.startsWith("http")) hop else base + hop
            val hopRes = VegaNet.fetchWithCf(hopUrl, headers = vcloudHeaders(base), timeout = 25L) ?: return null
            target = vcloudTokenRegex.find(hopRes.text)?.groupValues?.get(1)
                ?.let { VegaNet.decodeDoubleBase64(it) }
        }
        return target?.takeIf { it.startsWith("http") }
    }

    private fun serverNameOf(label: String, href: String): String? {
        val low = label.lowercase()
        val h = href.lowercase()
        return when {
            low.contains("fslv2") || low.contains("fsl v2") || low.contains("fsl2") -> "FSLv2"
            low.contains("fsl") -> "FSL"
            low.contains("pixelserver") || low.contains("pixel server") || low.contains("pixeldrain") -> "PixelDrain"
            low.contains("gofile") -> "GoFile"
            low.contains("10gbps") -> "10Gbps"
            low.contains("buzzserver") -> "BuzzServer"
            low.contains("instant download") || low.contains("instant dl") -> "Instant Download"
            low.contains("vegadrive") || low.contains("v-drive") || low.contains("skydrop") -> "V-Drive"
            low.contains("vcloud") || low.contains("v-cloud") -> "V-Cloud"
            low.contains("g-direct") || low.contains("gdirect") || low.contains("fastdl") -> "G-Direct"
            low.contains("filepress") || low.contains("filebee") -> "Filepress"
            h.contains(".r2.cloudflarestorage.com") -> "FSLv2"
            h.contains(".r2.dev") -> "FSL"
            h.contains("pixeldrain") -> "PixelDrain"
            h.contains("gpdl") -> "10Gbps"
            else -> null
        }
    }

    private suspend fun vcloudServers(url: String): List<Server> {
        val target = vcloudTarget(url) ?: return emptyList()
        val page = VegaNet.fetchWithCf(target, headers = vcloudHeaders(VegaNet.originOf(target)), timeout = 25L)
            ?: return emptyList()
        val html = page.text
        val doc = Jsoup.parse(html)
        val out = mutableListOf<Server>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) continue
            if (buttonJunk.containsMatchIn(href)) continue
            val label = a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank()) continue
            val name = serverNameOf(label, href) ?: continue
            addServer(out, name, href)
        }

        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") && it.contains("pixeldrain") }
            ?.let { addServer(out, "PixelDrain", it) }

        return out.distinctBy { it.url }
    }

    private suspend fun addServer(out: MutableList<Server>, name: String, href: String) {
        when (name) {
            "10Gbps" -> gpdlDirect(href)?.let { out.add(Server(name, it)) }
            "GoFile" -> gofileDirect(href)?.let { out.add(it) }
            "PixelDrain" -> out.add(Server(name, pixelApi(href)))
            else -> out.add(Server(name, href))
        }
    }

    private suspend fun hubcloudServers(url: String): List<Server> {
        val res = try {
            app.get(url, headers = VegaNet.browserHeaders(), timeout = 20L)
        } catch (_: Exception) {
            return emptyList()
        }
        val doc = Jsoup.parse(res.text)
        val header = doc.selectFirst("div.card-header")?.text()?.trim().orEmpty()
        if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(header)) return emptyList()
        val generate = doc.select("a.btn, a[download]")
            .firstOrNull { it.text().contains("generate", true) }
            ?.attr("href")?.trim() ?: return emptyList()
        val genUrl = if (generate.startsWith("http")) generate else VegaNet.originOf(res.url) + generate
        val genRes = try {
            app.get(genUrl, headers = VegaNet.browserHeaders(res.url), timeout = 25L)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<Server>()
        for (a in Jsoup.parse(genRes.text).select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) continue
            if (buttonJunk.containsMatchIn(href)) continue
            val label = a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank()) continue
            val name = serverNameOf(label, href) ?: continue
            addServer(out, name, href)
        }
        return out.distinctBy { it.url }
    }

    private suspend fun vegadriveSkydrop(url: String): Server? {
        val page = try {
            app.get(url, headers = VegaNet.browserHeaders("https://nexdrive.beer/"), timeout = 20L)
        } catch (_: Exception) {
            return null
        }
        val base = VegaNet.originOf(page.url)
        val token = url.substringAfter("/s/").substringBefore("?").substringBefore("#")
            .substringAfter("/d/").substringBefore("/")
        if (token.isBlank() || token == url) return null
        for (attempt in 0 until 3) {
            val res = try {
                app.get(
                    "$base/download-link/$token/skydrop",
                    headers = VegaNet.browserHeaders("$base/s/$token"),
                    timeout = 15L
                )
            } catch (_: Exception) {
                return null
            }
            val parsed = runCatching { JSONObject(res.text) }.getOrNull()
            val link = parsed?.optString("download_url").orEmpty()
            if (res.code == 200 && link.startsWith("http")) return Server("V-Drive", link)
            if (res.code != 202) return null
            delay((parsed?.optInt("retry_after_ms", 2500) ?: 2500).toLong().coerceAtMost(3000L))
        }
        return null
    }

    private suspend fun fastdlDirect(url: String): String? {
        return try {
            val res = app.get(url, headers = VegaNet.browserHeaders(), timeout = 20L)
            val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(res.text)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(res.text)?.groupValues?.get(1)
                ?: return null
            val carrier = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(reurl)?.groupValues?.get(1)?.let { wrapped ->
                val padded = if (wrapped.length % 4 > 0) {
                    wrapped + "=".repeat(4 - wrapped.length % 4)
                } else wrapped
                runCatching { base64Decode(padded) }.getOrNull()
            } ?: reurl
            val encoded = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1)
                ?: return null
            val direct = URLDecoder.decode(encoded, "UTF-8")
            if (direct.startsWith("http")) direct else null
        } catch (_: Exception) {
            null
        }
    }

    private val filePressId = Regex("""/file/([a-f0-9]{16,40})""")

    private suspend fun filepressDirect(url: String): String? {
        val id = filePressId.find(url)?.groupValues?.get(1) ?: return null
        val base = "https://filebee.xyz"

        val share = filepressTask(id, "dotFlixDownlaod", base)
        if (share != null && share.startsWith("http")) {
            resolveDotFlix(share)?.let { return it }
        }

        val task = filepressTask(id, "indexDownlaod", base)
        if (task != null && task.matches(Regex("[a-f0-9]{16,40}"))) {
            return filepressFinal(task, base)
        }
        return null
    }

    private suspend fun filepressTask(id: String, method: String, base: String): String? {
        return try {
            val res = app.post(
                "$base/api/file/downlaod/",
                headers = VegaNet.browserHeaders("$base/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("Origin", base)
                },
                json = JSONObject()
                    .put("captchaValue", "")
                    .put("id", id)
                    .put("method", method)
                    .toString(),
                timeout = 25L
            )
            if (!res.isSuccessful) return null
            val parsed = JSONObject(res.text)
            if (!parsed.optBoolean("status")) return null
            (parsed.opt("data") as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun filepressFinal(taskId: String, base: String): String? {
        return try {
            val res = app.post(
                "$base/api/file/downlaod2/",
                headers = VegaNet.browserHeaders("$base/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("Origin", base)
                },
                json = JSONObject()
                    .put("captchaValue", JSONObject.NULL)
                    .put("id", taskId)
                    .put("method", "indexDownlaod")
                    .toString(),
                timeout = 35L
            )
            if (!res.isSuccessful) return null
            val parsed = JSONObject(res.text)
            if (!parsed.optBoolean("status")) return null
            when (val data = parsed.opt("data")) {
                is String -> data.takeIf { it.startsWith("http") }
                is org.json.JSONArray -> (0 until data.length())
                    .firstNotNullOfOrNull { i -> data.optString(i).takeIf { it.startsWith("http") } }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveDotFlix(shareUrl: String): String? {
        return try {
            val text = app.get(
                shareUrl,
                headers = VegaNet.browserHeaders("https://new3.dotflix.shop/"),
                timeout = 20L
            ).text
            val code = Regex("""btoa\('([^']+)'\)""").find(text)?.groupValues?.get(1) ?: return null
            val obfuscated = java.util.Base64.getEncoder()
                .encodeToString(code.toByteArray()).reversed()
            val requestId = List(13) { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            val timestamp = System.currentTimeMillis().toString()
            val res = app.post(
                "https://dotflix.fit/api/extract-download",
                headers = VegaNet.browserHeaders("https://new3.dotflix.shop/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("X-Request-ID", requestId)
                    put("X-Timestamp", timestamp)
                    put("Origin", "https://new3.dotflix.shop")
                },
                json = JSONObject()
                    .put("requestId", requestId)
                    .put("timestamp", timestamp)
                    .put("data", obfuscated)
                    .toString(),
                timeout = 25L
            )
            if (!res.isSuccessful) return null
            val parsed = JSONObject(res.text)
            if (!parsed.optBoolean("success")) return null
            parsed.optString("downloadUrl").takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun gofileDirect(url: String): Server? {
        val code = url.substringAfterLast("/").substringBefore("#").substringBefore("?")
        if (code.isBlank()) return null
        return try {
            val api = "https://api.gofile.io"
            val token = JSONObject(app.post("$api/accounts", timeout = 15L).text)
                .getJSONObject("data").getString("token")
            val wt = runCatching {
                Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                    app.get("$api/dist/js/global.js", timeout = 15L).text
                )?.groupValues?.get(1)
            }.getOrNull()
            val contentUrl = if (wt.isNullOrBlank()) "$api/contents/$code?wt=$token" else "$api/contents/$code?wt=$wt"
            val data = JSONObject(
                app.get(contentUrl, headers = mapOf("Authorization" to "Bearer $token"), timeout = 20L).text
            ).getJSONObject("data")
            val children = data.optJSONObject("children") ?: return null
            var best: String? = null
            var bestSize = -1L
            for (key in children.keys()) {
                val child = children.optJSONObject(key) ?: continue
                if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(child.optString("name"))) continue
                val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
                if (child.optLong("size", 0L) > bestSize) {
                    bestSize = child.optLong("size", 0L)
                    best = link
                }
            }
            best?.let { Server("GoFile", it, mapOf("Cookie" to "accountToken=$token")) }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun gpdlDirect(url: String): String? {
        var cur = url
        try {
            for (i in 0 until 5) {
                val res = app.get(cur, headers = VegaNet.browserHeaders(), allowRedirects = false, timeout = 15L)
                val loc = res.headers["location"]
                if (loc.isNullOrBlank() || res.code !in 301..308) break
                cur = if (loc.startsWith("http")) loc else VegaNet.originOf(cur) + loc
            }
        } catch (_: Exception) {
            return null
        }
        for (pair in cur.substringAfter("?", "").split("&")) {
            if (pair.substringBefore("=") != "link") continue
            val value = pair.substringAfter("=")
            val direct = if (value.contains("%")) URLDecoder.decode(value, "UTF-8") else value
            return direct.takeIf { it.startsWith("http") }
        }
        return null
    }

    private val verified = ConcurrentHashMap.newKeySet<String>()

    fun resetSession() {
        verified.clear()
        pageCache.clear()
    }

    suspend fun alive(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        if (!url.startsWith("http")) return false
        if (url in verified) return true
        val ok = try {
            val h = VegaNet.browserHeaders().toMutableMap()
            h.putAll(headers)
            h["Range"] = "bytes=0-1023"
            app.get(url, headers = h, timeout = 10L).code in 200..399
        } catch (_: Exception) {
            false
        }
        if (ok) verified.add(url)
        return ok
    }
}
