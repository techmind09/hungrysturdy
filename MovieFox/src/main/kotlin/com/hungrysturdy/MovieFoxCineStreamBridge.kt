package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.megix.AllLoadLinksData
import com.megix.CineStreamExtractors
import com.megix.ProviderRegistry
import com.megix.init as initCineStreamApis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

object MovieFoxCineStreamBridge {

    fun availableServerKeys(): List<String> =
        ProviderRegistry.builtInProviders.map { it.key }

    fun enabledServerKeys(ctx: Context): List<String> {
        val order = MovieFoxSettingsDialog.getServerOrder(ctx)
        val builtIn = ProviderRegistry.builtInProviders.map { it.key }
        val orderedBuiltIn = order.filter { it in builtIn } + (builtIn - order.toSet())
        return orderedBuiltIn.filter { MovieFoxSettingsDialog.isServerEnabled(ctx, it) }
    }

    fun registerCineStreamExtractors(plugin: Plugin, extractors: List<ExtractorApi>) {
        extractors.distinctBy { it::class.java.name }.forEach { api ->
            runCatching { plugin.registerExtractorAPI(api) }
        }
    }

    suspend fun fetchServers(
        ctx: Context?,
        payload: AllLoadLinksData,
        isAnime: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        perServerTimeoutMs: Long = 15_000L
    ): Boolean {
        runCatching { initCineStreamApis() }

        val activeKeys = if (ctx != null) {
            enabledServerKeys(ctx)
        } else {
            availableServerKeys()
        }
        if (activeKeys.isEmpty()) return false

        val concurrency = (ctx?.let { MovieFoxSettingsDialog.getConcurrency(it) } ?: 15).coerceIn(2, 50)
        val gate = Semaphore(concurrency)
        var emitted = false
        val lock = Any()

        val providersToRun = ProviderRegistry.builtInProviders.filter { it.key in activeKeys }

        supervisorScope {
            providersToRun.map { def ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        withTimeoutOrNull(perServerTimeoutMs) {
                            try {
                                val action = if (isAnime) def.executeAnime else def.executeStandard
                                action?.invoke(CineStreamExtractors, payload, subtitleCallback) { link ->
                                    synchronized(lock) {
                                        emitted = true
                                    }
                                    callback(link)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        return emitted
    }
}
