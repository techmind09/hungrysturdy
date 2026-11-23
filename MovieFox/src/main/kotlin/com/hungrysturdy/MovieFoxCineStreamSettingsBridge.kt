package com.hungrysturdy

import android.content.Context
import android.net.Uri
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import java.util.concurrent.ConcurrentHashMap

object MovieFoxCineStreamSettingsBridge {

    const val CONCURRENCY_KEY = "ScrapeConcurrency"
    const val DOWNLOAD_ENABLE = "DownloadEnable"
    const val PROVIDER_CINESTREAM = "ProviderCineStream"
    const val PROVIDER_SIMKL = "ProviderSimkl"
    const val PROVIDER_TMDB = "ProviderTmdb"
    const val SHOWBOX_TOKEN_KEY = "showbox_ui_token"
    const val WYZIE_SUBS_KEY = "wyzie_subs_api_key"
    const val GRAMCINEMA_TOKEN_KEY = "gramcinema_bearer_token"
    const val STREMIO_ADDONS_KEY = "stremio_addons"
    const val NEW_PROVIDER_DEFAULT_ON = "new_provider_default_on"
    const val CF_BYPASS_ENABLED = "cloudflare_webview_bypass_enabled"

    private const val SEEN_PROVIDERS_KEY = "seen_providers"
    private const val PROVIDER_ORDER_KEY = "provider_order"
    private const val CF_COOKIE_KEY_PREFIX = "cf_cookie_"

    private val localCookieStore = ConcurrentHashMap<String, String>()

    enum class AddonType { HTTPS, TORRENT, DEBRID, SUBTITLE }

    data class StremioAddon(val name: String, val url: String, val type: AddonType)

    data class BypassDomain(val domain: String, val displayName: String)

    private val CLOUDFLARE_BYPASS_SITES = listOf(
        BypassDomain("anidao.to/search?q=hi", "AniDao")
    )

    private fun clearStoredKey(key: String) {
        runCatching { setKey<String?>(key, null) }
            .onFailure { runCatching { setKey(key, "") } }
    }

    fun getCloudflareBypassDomains(): List<BypassDomain> = CLOUDFLARE_BYPASS_SITES

    fun cloudflareBypassEnabled(): Boolean =
        runCatching { getKey<Boolean>(CF_BYPASS_ENABLED) }.getOrNull() ?: true

    fun getConcurrency(ctx: Context? = CloudStreamApp.context): Int {
        val fromKey = runCatching { getKey<Int>(CONCURRENCY_KEY) }.getOrNull()
        if (fromKey != null && fromKey > 0) return fromKey.coerceIn(2, 50)
        return ctx?.let { MovieFoxSettingsDialog.getConcurrency(it) } ?: 10
    }

    fun allowDownloadLinks(ctx: Context? = CloudStreamApp.context): Boolean {
        val fromKey = runCatching { getKey<Boolean>(DOWNLOAD_ENABLE) }.getOrNull()
        if (fromKey != null) return fromKey
        return ctx?.let { MovieFoxSettingsDialog.isDownloadOnly(it) } ?: false
    }

    private fun normalizeBypassDomain(domain: String): String =
        domain.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .substringBefore('?')
            .lowercase()
            .trim()

    fun hasCloudflareBypassForUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val host = runCatching { Uri.parse(withScheme).host?.lowercase()?.removePrefix("www.").orEmpty() }
            .getOrDefault("")
            .ifBlank { normalizeBypassDomain(trimmed) }
        if (host.isBlank()) return false
        return CLOUDFLARE_BYPASS_SITES.any { site ->
            val norm = normalizeBypassDomain(site.domain)
            norm.isNotBlank() && (host == norm || host.endsWith(".$norm"))
        }
    }

    private fun cookieKeyForDomain(urlOrDomain: String): String {
        val domain = if (urlOrDomain.contains("://")) {
            runCatching { Uri.parse(urlOrDomain).host?.lowercase()?.removePrefix("www.") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: normalizeBypassDomain(urlOrDomain)
        } else {
            normalizeBypassDomain(urlOrDomain)
        }
        return "$CF_COOKIE_KEY_PREFIX$domain"
    }

    fun saveCookieForDomain(urlOrDomain: String, cookies: String) {
        val k = cookieKeyForDomain(urlOrDomain)
        val clean = cookies.trim()
        if (k.isBlank() || clean.isBlank()) return
        localCookieStore[k] = clean
        runCatching { setKey(k, clean) }
    }

    fun getCookieForDomain(urlOrDomain: String): String? {
        val k = cookieKeyForDomain(urlOrDomain)
        localCookieStore[k]?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { getKey<String>(k) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun hasSavedCookieForDomain(urlOrDomain: String): Boolean =
        !getCookieForDomain(urlOrDomain).isNullOrBlank()

    fun removeCookieForDomain(urlOrDomain: String) {
        val k = cookieKeyForDomain(urlOrDomain)
        localCookieStore.remove(k)
        clearStoredKey(k)
    }

    fun stremioAddonKey(name: String): String =
        "stremio_${name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"

    fun getStremioAddons(): MutableList<StremioAddon> {
        val raw = runCatching { getKey<String>(STREMIO_ADDONS_KEY) }.getOrNull() ?: return mutableListOf()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val firstPipe = line.indexOf('|')
            val lastPipe = line.lastIndexOf('|')
            if (firstPipe <= 0 || lastPipe <= firstPipe) return@mapNotNull null
            val addonName = line.substring(0, firstPipe).trim()
            val addonUrl = line.substring(firstPipe + 1, lastPipe).trim()
            val typeStr = line.substring(lastPipe + 1).trim()
            if (addonName.isBlank() || addonUrl.isBlank()) return@mapNotNull null
            val type = runCatching { AddonType.valueOf(typeStr) }.getOrDefault(AddonType.HTTPS)
            StremioAddon(name = addonName, url = addonUrl, type = type)
        }.toMutableList()
    }

    fun saveStremioAddons(addons: List<StremioAddon>) {
        if (addons.isEmpty()) {
            clearStoredKey(STREMIO_ADDONS_KEY)
        } else {
            val serialized = addons
                .filter { it.name.isNotBlank() && it.url.isNotBlank() }
                .joinToString("\n") { "${it.name.trim()}|${it.url.trim()}|${it.type.name}" }
            runCatching { setKey(STREMIO_ADDONS_KEY, serialized) }
        }
        val validKeys = addons.map { stremioAddonKey(it.name) }.toSet()
        val current = runCatching { getKey<String>(PROVIDER_ORDER_KEY) }.getOrNull()
            ?.split(",")?.filter { it.isNotBlank() } ?: return
        val pruned = current.filter { !it.startsWith("stremio_") || it in validKeys }
        if (pruned.size != current.size) {
            runCatching { setKey(PROVIDER_ORDER_KEY, pruned.joinToString(",")) }
        }
    }

    fun getShowboxToken(): String? =
        runCatching { getKey<String>(SHOWBOX_TOKEN_KEY) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun getWyzieSubsKey(): String? =
        runCatching { getKey<String>(WYZIE_SUBS_KEY) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun getGramCinemaToken(): String? =
        runCatching { getKey<String>(GRAMCINEMA_TOKEN_KEY) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun isProviderEnabled(ctx: Context?, key: String): Boolean {
        val explicit = runCatching { getKey<Boolean>(key) }.getOrNull()
        if (explicit != null) return explicit
        if (ctx != null) return MovieFoxSettingsDialog.isServerEnabled(ctx, key)
        return true
    }

    fun getConfiguredOrder(ctx: Context?, baseKeys: List<String>): List<String> {
        val addonKeys = getStremioAddons().map { stremioAddonKey(it.name) }
        val allKnown = (baseKeys + addonKeys).distinct()
        val saved = runCatching { getKey<String>(PROVIDER_ORDER_KEY) }.getOrNull()
            ?.split(",")?.filter { it.isNotBlank() }
        if (saved != null && saved.isNotEmpty()) {
            val valid = saved.filter { it in allKnown }
            return valid + (allKnown - valid.toSet())
        }
        return ctx?.let { MovieFoxSettingsDialog.getServerOrder(it) } ?: allKnown
    }
}

