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

    fun getCloudflareBypassDomains(): List<BypassDomain> = CLOUDFLARE_BYPASS_SITES

    fun cloudflareBypassEnabled(): Boolean = getKey<Boolean>(CF_BYPASS_ENABLED) ?: true

    fun getConcurrency(ctx: Context? = CloudStreamApp.context): Int {
        val fromKey = getKey<Int>(CONCURRENCY_KEY)
        if (fromKey != null && fromKey > 0) return fromKey.coerceIn(2, 50)
        return ctx?.let { MovieFoxSettingsDialog.getConcurrency(it) } ?: 10
    }

    fun allowDownloadLinks(ctx: Context? = CloudStreamApp.context): Boolean {
        val fromKey = getKey<Boolean>(DOWNLOAD_ENABLE)
        if (fromKey != null) return fromKey
        return ctx?.let { MovieFoxSettingsDialog.isDownloadOnly(it) } ?: false
    }

    private fun normalizeBypassDomain(domain: String): String =
        domain.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .lowercase()
            .trim()

    fun hasCloudflareBypassForUrl(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase()?.trim().orEmpty()
        if (host.isBlank()) return false
        return CLOUDFLARE_BYPASS_SITES.any { site ->
            val norm = normalizeBypassDomain(site.domain)
            norm.isNotBlank() && (host == norm || host.endsWith(".$norm"))
        }
    }

    private fun cookieKeyForDomain(urlOrDomain: String): String {
        val domain = if (urlOrDomain.contains("://")) {
            Uri.parse(urlOrDomain).host?.lowercase()?.removePrefix("www.")
                ?: normalizeBypassDomain(urlOrDomain)
        } else {
            normalizeBypassDomain(urlOrDomain)
        }
        return "$CF_COOKIE_KEY_PREFIX$domain"
    }

    fun saveCookieForDomain(urlOrDomain: String, cookies: String) {
        val k = cookieKeyForDomain(urlOrDomain)
        val clean = cookies.trim()
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
        runCatching { setKey(k, null as String?) }
    }

    fun stremioAddonKey(name: String): String =
        "stremio_${name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"

    fun getStremioAddons(): MutableList<StremioAddon> {
        val raw = runCatching { getKey<String>(STREMIO_ADDONS_KEY) }.getOrNull() ?: return mutableListOf()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@mapNotNull null
            val type = runCatching { AddonType.valueOf(parts[2]) }.getOrDefault(AddonType.HTTPS)
            StremioAddon(name = parts[0], url = parts[1], type = type)
        }.toMutableList()
    }

    fun saveStremioAddons(addons: List<StremioAddon>) {
        if (addons.isEmpty()) {
            runCatching { setKey(STREMIO_ADDONS_KEY, null as String?) }
        } else {
            runCatching {
                setKey(STREMIO_ADDONS_KEY, addons.joinToString("\n") { "${it.name}|${it.url}|${it.type}" })
            }
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
