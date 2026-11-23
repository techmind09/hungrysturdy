package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

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
        if (payload.title.isBlank() && payload.imdbId.isBlank() && payload.tmdbId.isNullOrBlank()) {
            return false
        }

        val timeoutSec = (perServerTimeoutMs / 1000L).coerceAtLeast(5L)
        val concurrency = (ctx?.let { MovieFoxSettingsDialog.getConcurrency(it) } ?: 15).coerceIn(2, 50)
        val gate = Semaphore(concurrency)
        val emitted = AtomicBoolean(false)
        val subLock = Any()
        val linkLock = Any()

        return supervisorScope {
            val sharedLoadJob = async(Dispatchers.IO) {
                gate.withPermit {
                    withTimeoutOrNull(perServerTimeoutMs) {
                        try {
                            val ok = MovieFoxCineStream.loadStreams(
                                provider = provider,
                                title = payload.title,
                                imdbId = payload.imdbId,
                                tmdbId = payload.tmdbId,
                                isTv = payload.isTv,
                                season = payload.season.coerceAtLeast(1),
                                episode = payload.episode.coerceAtLeast(1),
                                timeoutSec = timeoutSec,
                                subtitleCallback = { sub ->
                                    synchronized(subLock) { subtitleCallback(sub) }
                                },
                                callback = { link ->
                                    emitted.set(true)
                                    synchronized(linkLock) { callback(link) }
                                }
                            )
                            if (ok) emitted.set(true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            listOf(sharedLoadJob).awaitAll()
            emitted.get()
        }
    }
}

