package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

internal fun NiceResponse.closeQuietly() {
    try {
        okhttpResponse.close()
    } catch (_: Exception) {
    }
}

object MovieFoxNet {

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

    const val MOBILE_UA: String = MovieFoxSettingsDialog.CF_BYPASS_UA

    private val cfKillers = ConcurrentHashMap<String, CloudflareKiller>()
    private val cfLocks = ConcurrentHashMap<String, Mutex>()
    private val verifiedUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val resolvedDomainCache = ConcurrentHashMap<String, String>()
    private val imageSegmentRegex = Regex("""(?i)\.(png|jpe?g|gif|webp|bmp|ico|svg)(?:[?#].*)?$""")

    fun hostOf(url: String): String = try {
        val normalized = if (url.contains("://")) url.trim() else "https://${url.trim()}"
        URI(normalized).host?.lowercase()?.removePrefix("www.").orEmpty()
    } catch (_: Exception) {
        ""
    }

    fun originOf(url: String): String = try {
        val normalized = if (url.contains("://")) url.trim() else "https://${url.trim()}"
        val uri = URI(normalized)
        val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "https"
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return url
        "$scheme://$host"
    } catch (_: Exception) {
        url
    }

    fun absolute(href: String, base: String): String {
        val clean = href.replace("\\/", "/").replace("&amp;", "&").trim()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("http://", ignoreCase = true) ||
                clean.startsWith("https://", ignoreCase = true) ||
                clean.startsWith("magnet:", ignoreCase = true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> originOf(base).trimEnd('/') + clean
            else -> base.trimEnd('/') + "/" + clean
        }
    }

    fun urlEncode(raw: String): String = try {
        URLEncoder.encode(raw, "UTF-8")
    } catch (_: Exception) {
        raw
    }

    private fun isJunkUrl(url: String): Boolean {
        val low = url.lowercase()
        return url.length < 10 ||
            low.contains("google.com") ||
            low.contains("gstatic.com") ||
            low.contains("gravatar.com") ||
            low.contains("cloudflare.com")
    }

    fun browserHeaders(
        referer: String? = null,
        userAgent: String = DESKTOP_UA,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["User-Agent"] = userAgent
        out["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        out["Accept-Language"] = "en-US,en;q=0.9,hi;q=0.8"
        out["sec-ch-ua"] = "\"Chromium\";v=\"154\", \"Not_A Brand\";v=\"24\""
        out["sec-ch-ua-mobile"] = if (userAgent.contains("Mobile", true) || userAgent.contains("Android", true)) "?1" else "?0"
        out["sec-ch-ua-platform"] = if (userAgent.contains("Android", true)) "\"Android\"" else "\"Windows\""
        out["Sec-Fetch-Dest"] = "document"
        out["Sec-Fetch-Mode"] = "navigate"
        out["Sec-Fetch-Site"] = if (referer != null) "same-origin" else "none"
        out["Sec-Fetch-User"] = "?1"
        out["Upgrade-Insecure-Requests"] = "1"
        if (referer != null) out["Referer"] = referer
        out.putAll(extra)
        return out
    }

    fun apiHeaders(
        referer: String? = null,
        origin: String? = null,
        userAgent: String = MOBILE_UA,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["User-Agent"] = userAgent
        out["Accept"] = "application/json, text/plain, */*"
        out["Accept-Language"] = "en-US,en;q=0.9,hi;q=0.8"
        if (referer != null) out["Referer"] = referer
        if (origin != null) out["Origin"] = origin.trimEnd('/')
        out.putAll(extra)
        return out
    }

    fun killerFor(url: String): CloudflareKiller =
        cfKillers.getOrPut(hostOf(url).ifBlank { url }) { CloudflareKiller() }

    private fun lockFor(url: String): Mutex =
        cfLocks.getOrPut(hostOf(url).ifBlank { url }) { Mutex() }

    fun isCfChallenge(res: NiceResponse): Boolean {
        if (res.code in listOf(403, 429, 503)) return true
        if (res.headers["cf-mitigated"].equals("challenge", ignoreCase = true)) return true
        val body = try {
            res.text.lowercase()
        } catch (_: Exception) {
            ""
        }
        return (body.contains("just a moment") && (body.contains("challenge-platform") || body.contains("cloudflare"))) ||
            body.contains("checking your browser") ||
            body.contains("checking if the site connection is secure") ||
            body.contains("cf-browser-verification")
    }

    suspend fun <T> retry(
        attempts: Int = 3,
        gapMs: Long = 650L,
        block: suspend (attempt: Int) -> T?
    ): T? {
        repeat(attempts.coerceAtLeast(1)) { idx ->
            try {
                block(idx)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (idx < attempts - 1) delay(gapMs)
        }
        return null
    }

    suspend fun fetchWithCf(
        url: String,
        referer: String? = null,
        headers: Map<String, String>? = null,
        timeoutSec: Long = 20L,
        solveTimeoutSec: Long = 45L
    ): NiceResponse? {
        val reqHeaders = headers ?: browserHeaders(referer)
        val plain = try {
            app.get(url, headers = reqHeaders, timeout = timeoutSec)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (plain != null && plain.code in 200..299 && !isCfChallenge(plain)) {
            return plain
        }
        plain?.closeQuietly()

        return lockFor(url).withLock {
            val killer = killerFor(url)
            try {
                val h = hostOf(url)
                if (h.isNotBlank()) killer.savedCookies.remove(h)
            } catch (_: Exception) {
            }
            val solved = try {
                app.get(url, headers = reqHeaders, interceptor = killer, timeout = solveTimeoutSec)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (solved != null && solved.code in 200..299 && !isCfChallenge(solved)) {
                solved
            } else {
                solved?.closeQuietly()
                null
            }
        }
    }

    suspend fun getText(
        url: String,
        referer: String? = null,
        headers: Map<String, String>? = null,
        timeoutSec: Long = 18L,
        useCfFallback: Boolean = true,
        sourceTag: String = "MFNet"
    ): String? {
        val reqHeaders = headers ?: browserHeaders(referer)
        val start = System.currentTimeMillis()
        val first = try {
            app.get(url, headers = reqHeaders, timeout = timeoutSec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MovieFoxSettingsDialog.logSiteError(sourceTag, url, e, "GET failed")
            null
        }
        if (first != null && first.code in 200..299 && !isCfChallenge(first)) {
            val ms = System.currentTimeMillis() - start
            val body = try {
                first.text
            } finally {
                first.closeQuietly()
            }
            MovieFoxSettingsDialog.reportServerStatus(sourceTag, "LIVE", true, first.code, ms, url)
            return body
        }
        val needsCf = first != null && isCfChallenge(first)
        val code = first?.code ?: 0
        first?.closeQuietly()

        if (needsCf && useCfFallback) {
            val solved = fetchWithCf(url, referer, reqHeaders, timeoutSec = timeoutSec)
            if (solved != null) {
                val ms = System.currentTimeMillis() - start
                val body = try {
                    solved.text
                } finally {
                    solved.closeQuietly()
                }
                MovieFoxSettingsDialog.reportServerStatus(sourceTag, "LIVE", true, solved.code, ms, url)
                return body
            }
            MovieFoxSettingsDialog.logSiteError(sourceTag, url, null, "CF BLOCKED HTTP $code")
            return null
        }

        if (code != 0) {
            MovieFoxSettingsDialog.logSiteError(sourceTag, url, null, "HTTP $code")
        }
        return null
    }

    suspend fun resolveRedirectTarget(
        url: String,
        referer: String? = null,
        maxHops: Int = 7,
        timeoutSec: Long = 12L
    ): String? {
        var current = url
        val seen = HashSet<String>()
        repeat(maxHops.coerceAtLeast(1)) {
            if (!seen.add(current)) return null
            val res = try {
                app.get(
                    current,
                    headers = browserHeaders(referer),
                    allowRedirects = false,
                    timeout = timeoutSec
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
            val loc = res.headers["location"]?.trim().orEmpty()
            res.closeQuietly()
            if (loc.isBlank()) {
                return current.takeIf { it.startsWith("http", ignoreCase = true) }
            }
            current = absolute(loc, current)
        }
        return current.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    suspend fun probeRange(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 12L
    ): Int? {
        return try {
            val h = browserHeaders(referer).toMutableMap()
            h.putAll(headers)
            h["Range"] = "bytes=0-1023"
            val res = app.get(url, headers = h, timeout = timeoutSec)
            val code = res.code
            res.closeQuietly()
            code
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun manifestAlive(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 10L
    ): Boolean {
        if (!url.startsWith("http", ignoreCase = true)) return false
        return try {
            val res = app.get(url, headers = headers, timeout = timeoutSec)
            if (res.code !in 200..299) {
                res.closeQuietly()
                return false
            }
            val text = try {
                res.text
            } finally {
                res.closeQuietly()
            }
            val trimmed = text.trimStart()
            if (trimmed.startsWith("#EXTM3U")) {
                val entries = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
                return entries.isNotEmpty() && entries.none { imageSegmentRegex.containsMatchIn(it) }
            }
            trimmed.contains("<MPD", ignoreCase = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun isAlive(
        url: String,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): Boolean {
        if (!url.startsWith("http", ignoreCase = true) || mfIsBrokenContainerUrl(url)) return false
        if (url in verifiedUrls) return true
        val merged = if (referer != null && headers.keys.none { it.equals("Referer", true) }) {
            headers + mapOf("Referer" to referer)
        } else {
            headers
        }
        val ok = try {
            when (type) {
                ExtractorLinkType.M3U8, ExtractorLinkType.DASH -> manifestAlive(url, merged)
                else -> probeRange(url, referer, merged)?.let { it in 200..399 } ?: false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (ok) {
            if (verifiedUrls.size > 800) verifiedUrls.clear()
            verifiedUrls.add(url)
        }
        return ok
    }

    fun decodeBase64Flexible(raw: String): String? {
        val trimmed = raw.trim().replace("-", "+").replace("_", "/")
        if (trimmed.isBlank()) return null
        val rem = trimmed.length % 4
        if (rem == 1) return null
        val padded = if (rem > 0) trimmed + "=".repeat(4 - rem) else trimmed
        return try {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            runCatching { base64Decode(padded) }.getOrNull()
        }
    }

    fun decodeDoubleBase64(encoded: String): String? {
        val once = decodeBase64Flexible(encoded) ?: return null
        return decodeBase64Flexible(once)
    }

    fun rot13(input: String): String = buildString(input.length) {
        for (c in input) {
            when (c) {
                in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
                in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
                else -> append(c)
            }
        }
    }

    fun normalizeTitle(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val folded = try {
            Normalizer.normalize(input, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        } catch (_: Exception) {
            input
        }
        return folded.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]"), "")
    }

    suspend fun resolveDomain(
        key: String,
        fallback: String = "",
        forceRefresh: Boolean = false
    ): String {
        val cacheKey = key.lowercase()
        if (!forceRefresh) {
            resolvedDomainCache[cacheKey]?.let { if (!isJunkUrl(it)) return it }
        }

        val entry = MfSites.byKey(key)
        val lookupKeys = buildList {
            add(key)
            add("justplay_$key")
            add("${key}_url")
            add("${key}_domain")
            entry?.firebaseKeys?.forEach { add(it) }
            entry?.registryKeys?.forEach { add(it) }
        }.distinct()

        // 1. Check Firebase RTDB
        try {
            val fbText = app.get(MfRegistries.FIREBASE_RTDB_URL, timeout = 5L).text
            val fbObj = runCatching { JSONObject(fbText) }.getOrNull()
            if (fbObj != null) {
                for (k in lookupKeys) {
                    val v = fbObj.opt(k)?.toString()?.trim()?.trim('"', '\'')?.trimEnd('/')
                    if (!v.isNullOrBlank() && v.startsWith("http", ignoreCase = true) && !isJunkUrl(v)) {
                        resolvedDomainCache[cacheKey] = v
                        return v
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        // 2. Check SaurabhKaperwan urls.json
        try {
            val uText = app.get(MfRegistries.KAPERWAN_URLS_JSON, timeout = 6L).text
            val uObj = runCatching { JSONObject(uText) }.getOrNull()
            if (uObj != null) {
                for (k in lookupKeys) {
                    val v = uObj.optString(k).trim().trim('"', '\'').trimEnd('/')
                    if (v.isNotBlank() && v.startsWith("http", ignoreCase = true) && !isJunkUrl(v)) {
                        resolvedDomainCache[cacheKey] = v
                        return v
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        // 3. Check phisher98 domains.json
        try {
            val pText = app.get(MfRegistries.PHISHER_DOMAINS_JSON, timeout = 6L).text
            val pObj = runCatching { JSONObject(pText) }.getOrNull()
            if (pObj != null) {
                for (k in lookupKeys) {
                    val v = pObj.optString(k).trim().trim('"', '\'').trimEnd('/')
                    if (v.isNotBlank() && v.startsWith("http", ignoreCase = true) && !isJunkUrl(v)) {
                        resolvedDomainCache[cacheKey] = v
                        return v
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        val def = fallback.trim().trimEnd('/').takeIf { it.isNotBlank() && !isJunkUrl(it) }
            ?: MfSites.candidatesFor(key).firstOrNull().orEmpty()
        if (def.isNotBlank()) resolvedDomainCache[cacheKey] = def
        return def
    }

    fun clearDomainCache() {
        resolvedDomainCache.clear()
        verifiedUrls.clear()
    }
}

