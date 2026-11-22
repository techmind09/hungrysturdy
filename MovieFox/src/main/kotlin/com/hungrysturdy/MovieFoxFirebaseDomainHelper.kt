package com.hungrysturdy

import com.lagradost.cloudstream3.app
import org.json.JSONObject

object MovieFoxFirebaseDomainHelper {

    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val REQUEST_TIMEOUT_SEC = 5L

    @Volatile
    private var domains: Map<String, String> = emptyMap()

    @Volatile
    private var lastLoadTime: Long = 0L

    @Volatile
    private var everLoadedSuccessfully: Boolean = false

    private fun cleanUrl(raw: Any?): String? {
        val str = when (raw) {
            is String -> raw
            is Number -> raw.toString()
            else -> null
        }?.trim()?.trim('"', '\'')?.trim()?.trimEnd('/')
        return str?.takeIf { it.isNotBlank() && it.startsWith("http", ignoreCase = true) }
    }

    private suspend fun load(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && everLoadedSuccessfully && (now - lastLoadTime) < CACHE_TTL_MS) {
            return
        }
        try {
            val responseText = app.get(
                MfRegistries.FIREBASE_RTDB_URL,
                timeout = REQUEST_TIMEOUT_SEC
            ).text
            val jsonObj = JSONObject(responseText)
            val parsed = LinkedHashMap<String, String>()
            val names = jsonObj.names()
            if (names != null) {
                for (i in 0 until names.length()) {
                    val k = names.optString(i).trim()
                    if (k.isEmpty()) continue
                    cleanUrl(jsonObj.opt(k))?.let { cleaned ->
                        parsed[k] = cleaned
                        parsed[k.lowercase()] = cleaned
                    }
                }
            }
            domains = parsed
            lastLoadTime = now
            everLoadedSuccessfully = true
        } catch (_: Exception) {
            lastLoadTime = now
        }
    }

    suspend fun getDomain(key: String, forceRefresh: Boolean = false): String? {
        load(force = forceRefresh)
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) return null

        val entry = MfSites.byKey(cleanKey)
        val candidates = buildList {
            add(cleanKey)
            add(cleanKey.lowercase())
            add("justplay_${cleanKey.lowercase()}")
            add("${cleanKey.lowercase()}_url")
            add("${cleanKey.lowercase()}_domain")
            entry?.firebaseKeys?.forEach { fk ->
                add(fk)
                add(fk.lowercase())
                add("${fk.lowercase()}_url")
                add("${fk.lowercase()}_domain")
            }
        }.distinct()

        for (candidate in candidates) {
            domains[candidate]?.let { return it }
        }
        return null
    }

    suspend fun getAllDomains(forceRefresh: Boolean = false): Map<String, String> {
        load(force = forceRefresh)
        return domains.toMap()
    }

    fun invalidate() {
        lastLoadTime = 0L
        everLoadedSuccessfully = false
    }
}
