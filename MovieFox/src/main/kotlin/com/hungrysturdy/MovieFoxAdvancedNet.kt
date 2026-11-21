package com.hungrysturdy

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.delay
import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

fun Closeable?.closeQuietly() {
    if (this == null) return
    try {
        close()
    } catch (_: Throwable) {
    }
}

object MovieFoxAdvancedNet {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val domainCookies = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val domainHeaders = ConcurrentHashMap<String, MutableMap<String, String>>()

    fun setCookie(hostOrUrl: String, key: String, value: String) {
        val host = extractHost(hostOrUrl) ?: return
        val map = domainCookies.computeIfAbsent(host) { ConcurrentHashMap() }
        map[key] = value
    }

    fun setCookiesFromHeader(hostOrUrl: String, rawCookieHeader: String?) {
        if (rawCookieHeader.isNullOrBlank()) return
        val host = extractHost(hostOrUrl) ?: return
        val map = domainCookies.computeIfAbsent(host) { ConcurrentHashMap() }
        rawCookieHeader.split(";")
            .map { it.trim() }
            .filter { it.contains("=") && !it.startsWith("Path=", true) && !it.startsWith("Domain=", true) && !it.startsWith("Expires=", true) && !it.startsWith("Max-Age=", true) && !it.startsWith("HttpOnly", true) && !it.startsWith("Secure", true) && !it.startsWith("SameSite", true) }
            .forEach { part ->
                val idx = part.indexOf("=")
                if (idx > 0) {
                    val k = part.substring(0, idx).trim()
                    val v = part.substring(idx + 1).trim()
                    if (k.isNotEmpty()) map[k] = v
                }
            }
    }

    fun setDomainHeader(hostOrUrl: String, key: String, value: String) {
        val host = extractHost(hostOrUrl) ?: return
        val map = domainHeaders.computeIfAbsent(host) { ConcurrentHashMap() }
        map[key] = value
    }

    fun clearHostState(hostOrUrl: String) {
        val host = extractHost(hostOrUrl) ?: return
        domainCookies.remove(host)
        domainHeaders.remove(host)
    }

    fun buildHeaders(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        headers["User-Agent"] = DEFAULT_USER_AGENT
        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        headers["Accept-Language"] = "en-US,en;q=0.9,hi;q=0.8"
        headers["Connection"] = "keep-alive"
        headers["Upgrade-Insecure-Requests"] = "1"
        headers["Sec-Fetch-Dest"] = "document"
        headers["Sec-Fetch-Mode"] = "navigate"
        headers["Sec-Fetch-Site"] = if (!referer.isNullOrBlank()) "same-origin" else "none"

        val effectiveReferer = referer ?: buildRootOrigin(url)
        if (!effectiveReferer.isNullOrBlank()) {
            headers["Referer"] = effectiveReferer
            buildRootOrigin(effectiveReferer)?.let { headers["Origin"] = it }
        }

        val host = extractHost(url)
        if (host != null) {
            domainHeaders[host]?.forEach { (k, v) -> headers[k] = v }
            val cookies = domainCookies[host]
            if (!cookies.isNullOrEmpty()) {
                val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                headers["Cookie"] = if (headers.containsKey("Cookie")) {
                    "${headers["Cookie"]}; $cookieString"
                } else {
                    cookieString
                }
            }
        }

        extraHeaders.forEach { (k, v) -> headers[k] = v }
        return headers
    }

    suspend fun get(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 20L,
        retries: Int = 2,
        retryDelayMs: Long = 450L
    ): NiceResponse? {
        var lastError: Throwable? = null
        val totalAttempts = retries.coerceAtLeast(0) + 1

        for (attempt in 1..totalAttempts) {
            try {
                val mergedHeaders = buildHeaders(url, referer, headers)
                val res = app.get(
                    url = url,
                    headers = mergedHeaders,
                    referer = referer,
                    timeout = timeoutSeconds
                )
                captureResponseCookies(url, res)
                if (res.code in 200..299 || res.code in 300..399) {
                    return res
                }
                if (res.code == 403 || res.code == 429 || res.code == 503) {
                    res.closeQuietly()
                    if (attempt < totalAttempts) {
                        delay(retryDelayMs * attempt)
                        continue
                    }
                    return null
                }
                res.closeQuietly()
            } catch (t: Throwable) {
                lastError = t
                if (attempt < totalAttempts) {
                    delay(retryDelayMs * attempt)
                }
            }
        }
        return null
    }

    suspend fun post(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        json: Any? = null,
        timeoutSeconds: Long = 20L,
        retries: Int = 1
    ): NiceResponse? {
        val totalAttempts = retries.coerceAtLeast(0) + 1
        for (attempt in 1..totalAttempts) {
            try {
                val mergedHeaders = buildHeaders(url, referer, headers).toMutableMap()
                if (json != null && !mergedHeaders.containsKey("Content-Type")) {
                    mergedHeaders["Content-Type"] = "application/json; charset=UTF-8"
                }
                val res = app.post(
                    url = url,
                    headers = mergedHeaders,
                    referer = referer,
                    data = data,
                    json = json,
                    timeout = timeoutSeconds
                )
                captureResponseCookies(url, res)
                if (res.code in 200..299 || res.code in 300..399) {
                    return res
                }
                res.closeQuietly()
            } catch (_: Throwable) {
                if (attempt < totalAttempts) {
                    delay(400L * attempt)
                }
            }
        }
        return null
    }

    suspend fun getTextWithMirrors(
        urls: List<String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 18L,
        validator: ((String, String) -> Boolean)? = null
    ): Pair<String, String>? {
        val uniqueUrls = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        for (candidate in uniqueUrls) {
            val res = get(
                url = candidate,
                referer = referer,
                headers = headers,
                timeoutSeconds = timeoutSeconds,
                retries = 1
            ) ?: continue

            val body = try {
                res.text
            } finally {
                res.closeQuietly()
            }

            if (body.isBlank()) continue
            if (isChallengePage(body)) continue
            if (validator != null && !validator(body, candidate)) continue

            return candidate to body
        }
        return null
    }

    fun isChallengePage(html: String): Boolean {
        val lower = html.lowercase()
        val markers = listOf(
            "checking your browser before accessing",
            "cf-browser-verification",
            "ddos-guard",
            "enable javascript and cookies to continue",
            "just a moment...",
            "attention required! | cloudflare"
        )
        return markers.any { lower.contains(it) } && html.length < 25_000
    }

    fun extractHost(urlOrHost: String): String? {
        return try {
            val normalized = if (urlOrHost.contains("://")) urlOrHost else "https://$urlOrHost"
            URI(normalized).host?.lowercase()?.removePrefix("www.")
        } catch (_: Throwable) {
            null
        }
    }

    fun buildRootOrigin(url: String): String? {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return null
            val portPart = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$portPart"
        } catch (_: Throwable) {
            null
        }
    }

    private fun captureResponseCookies(url: String, response: NiceResponse) {
        try {
            val setCookies = response.headers.values("Set-Cookie")
            setCookies.forEach { headerLine ->
                val cookiePair = headerLine.substringBefore(";").trim()
                val eqIdx = cookiePair.indexOf("=")
                if (eqIdx > 0) {
                    val name = cookiePair.substring(0, eqIdx).trim()
                    val value = cookiePair.substring(eqIdx + 1).trim()
                    if (name.isNotEmpty()) {
                        setCookie(url, name, value)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
}
