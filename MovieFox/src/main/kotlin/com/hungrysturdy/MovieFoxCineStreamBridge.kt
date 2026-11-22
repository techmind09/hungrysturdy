package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class CineStreamBridgePayload(
    val title: String = "",
    val imdbId: String = "",
    val tmdbId: String? = null,
    val isTv: Boolean = false,
    val season: Int = 1,
    val episode: Int = 1
)

object MovieFoxCineStreamBridge {

    fun availableServerKeys(): List<String> =
        listOf("cinestream") + MovieFoxCineStream.CATALOGS.map { it.id }

    fun enabledServerKeys(ctx: Context): List<String> {
        val order = MovieFoxSettingsDialog.getServerOrder(ctx)
        val keys = availableServerKeys()
        val ordered = order.filter { it in keys } + (keys - order.toSet())
        return ordered.filter { MovieFoxSettingsDialog.isServerEnabled(ctx, it) }
    }

    fun registerCineStreamExtractors(plugin: Plugin, extractors: List<ExtractorApi>) {
        extractors.distinctBy { it::class.java.name }.forEach { api ->
            runCatching { plugin.registerExtractorAPI(api) }
        }
    }

    suspend fun fetchServers(
        ctx: Context?,
        provider: MovieFoxProvider,
        payload: CineStreamBridgePayload,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        perServerTimeoutMs: Long = 15_000L
    ): Boolean {
        val activeKeys = if (ctx != null) {
            enabledServerKeys(ctx)
        } else {
            availableServerKeys()
        }
        if (activeKeys.isEmpty()) return false

        val timeoutSec = (perServerTimeoutMs / 1000L).coerceAtLeast(5L)
        val concurrency = (ctx?.let { MovieFoxSettingsDialog.getConcurrency(it) } ?: 15).coerceIn(2, 50)
        val gate = Semaphore(concurrency)
        var emitted = false
        val lock = Any()

        return supervisorScope {
            val jobs = activeKeys.map { _ ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        withTimeoutOrNull(perServerTimeoutMs) {
                            try {
                                val ok = MovieFoxCineStream.loadStreams(
                                    provider = provider,
                                    title = payload.title,
                                    imdbId = payload.imdbId,
                                    tmdbId = payload.tmdbId,
                                    isTv = payload.isTv,
                                    season = payload.season,
                                    episode = payload.episode,
                                    timeoutSec = timeoutSec,
                                    subtitleCallback = subtitleCallback,
                                    callback = { link ->
                                        synchronized(lock) { emitted = true }
                                        callback(link)
                                    }
                                )
                                if (ok) {
                                    synchronized(lock) { emitted = true }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
            emitted
        }
    }
}

