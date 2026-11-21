package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import java.net.URI

internal data class MovieFoxPlayData(
    val id: Int? = null,
    val title: String? = null,
    val imdbId: String? = null,
    val isMovie: Boolean = true,
    val season: Int? = null,
    val episode: Int? = null,
    val matchYear: Int? = null,
    val year: Int? = null
)

internal object MovieFoxSiteNet {
    const val PLAY_UA = USER_AGENT

    fun headers(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", PLAY_UA)
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (!referer.isNullOrBlank()) put("Referer", referer)
    }

    fun absolute(href: String, base: String): String {
        val t = href.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("//") -> "https:$t"
            else -> "${base.trimEnd('/')}/${t.trimStart('/')}"
        }
    }

    fun baseUrlOf(url: String): String {
        return runCatching {
            val u = URI(url)
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme}://${u.host}$port"
        }.getOrDefault("https://moviefox.pro")
    }

    fun hostOf(url: String): String =
        runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")

    fun normalizeTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.lowercase()
            .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .replace(Regex("""(?i)\b(season|part|s)\s*\d+\b"""), " ")
            .replace(
                Regex("""(?i)\b(480p|720p|1080p|2160p|4k|web-?dl|webrip|bluray|hdrip|hevc|x264|x265|hindi|dubbed|dual\s*audio)\b"""),
                " "
            )
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    fun titleMatches(candidate: String?, target: String?): Boolean {
        val cNorm = normalizeTitle(candidate)
        val tNorm = normalizeTitle(target)
        if (cNorm.isBlank() || tNorm.isBlank()) return false
        if (cNorm == tNorm || cNorm.contains(tNorm) || tNorm.contains(cNorm)) return true
        val tWords = tNorm.split(" ").filter { it.length > 1 }.toSet()
        val cWords = cNorm.split(" ").filter { it.length > 1 }.toSet()
        if (tWords.isEmpty() || cWords.isEmpty()) return false
        return (tWords.intersect(cWords).size.toDouble() / tWords.size.toDouble()) >= 0.7
    }

    fun titleMatchesFor(candidate: String?, data: MovieFoxPlayData): Boolean =
        titleMatches(candidate, data.title)

    fun seasonsOf(text: String?): Set<Int>? {
        if (text.isNullOrBlank()) return null
        val found = mutableSetOf<Int>()
        Regex("""(?i)\b(?:season|s)\s*0*(\d{1,2})\b""").findAll(text).forEach { m ->
            m.groupValues[1].toIntOrNull()?.let { found.add(it) }
        }
        return found.ifEmpty { null }
    }

    fun yearMatches(textOrYear: String, wantYear: Int?): Boolean {
        if (wantYear == null) return true
        val y = Regex("""\b(19|20)\d{2}\b""").find(textOrYear)?.value?.toIntOrNull()
            ?: textOrYear.take(4).toIntOrNull()
            ?: return true
        return kotlin.math.abs(y - wantYear) <= 1
    }

    fun getIndexQuality(info: String?): Int {
        val s = info?.uppercase().orEmpty()
        return when {
            s.contains("2160") || s.contains("4K") -> Qualities.P2160.value
            s.contains("1440") -> Qualities.P1440.value
            s.contains("1080") -> Qualities.P1080.value
            s.contains("720") -> Qualities.P720.value
            s.contains("480") -> Qualities.P480.value
            s.contains("360") -> Qualities.P360.value
            else -> Qualities.P1080.value
        }
    }

    suspend fun fetchDrivePage(url: String, referer: String? = null): NiceResponse? {
        return try {
            val res = app.get(url, headers = headers(referer), timeout = 20L)
            if (res.isSuccessful) res else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun alive(url: String, type: ExtractorLinkType = ExtractorLinkType.VIDEO): Boolean {
        return try {
            val res = app.get(url, headers = headers(), timeout = 12L)
            val ok = res.code in 200..299
            runCatching { res.okhttpResponse.close() }
            ok
        } catch (_: Exception) {
            false
        }
    }

    suspend fun resolveRedirectTarget(url: String, referer: String? = null): String? {
        return try {
            val res = app.get(url, headers = headers(referer), allowRedirects = false, timeout = 12L)
            val loc = res.headers["location"]?.trim().orEmpty()
            runCatching { res.okhttpResponse.close() }
            loc.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    fun searchQueries(data: MovieFoxPlayData): List<String> {
        val t = data.title?.trim().orEmpty()
        if (t.isBlank()) return emptyList()
        val y = data.matchYear ?: data.year
        return if (y != null && y > 1900) listOf("$t $y", t).distinct() else listOf(t)
    }

    suspend fun <T> retry(times: Int = 2, block: suspend () -> T?): T? {
        repeat(times) { attempt ->
            val res = block()
            if (res != null) return res
            if (attempt < times - 1) delay(250L * (attempt + 1))
        }
        return null
    }
}

internal object MovieFoxSiteLabels {
    fun siteName(site: String): String = when (site.lowercase()) {
        "play4u" -> "Play4u"
        "movy" -> "Movy"
        "netmirror", "netmirror_nf" -> "NetMirror NF"
        "netmirror_hs" -> "NetMirror HS"
        "netmirror_pv" -> "NetMirror PV"
        "movielinkbd" -> "MovieLinkBD"
        "hicine" -> "HiCine"
        "hindmoviez" -> "HindMoviez"
        "hdhub4u" -> "HDHub4u"
        "4khdhub" -> "4KHDHub"
        "movies4u" -> "Movies4u"
        "tmf" -> "TMF"
        "multimovies" -> "Multimovies"
        else -> site.replaceFirstChar { it.uppercase() }
    }

    fun buildLabel(site: String, server: String, info: String): String {
        val tag = siteName(site)
        val s = server.replace(Regex("(?i)^null$"), "").trim()
        val i = info.replace(Regex("""\s+"""), " ").trim()
        return when {
            s.isNotBlank() && i.isNotBlank() -> "$tag • $s ($i)"
            s.isNotBlank() -> "$tag • $s"
            i.isNotBlank() -> "$tag ($i)"
            else -> tag
        }.take(60)
    }

    fun allowLink(ctx: Context?, qualityValue: Int, label: String, isHls: Boolean): Boolean {
        if (ctx == null) return true
        if (MovieFoxSettingsDialog.isDownloadOnly(ctx) && isHls) return false
        val a4k = MovieFoxSettingsDialog.isQuality4kEnabled(ctx)
        val a1080 = MovieFoxSettingsDialog.isQuality1080Enabled(ctx)
        val a720 = MovieFoxSettingsDialog.isQuality720Enabled(ctx)
        val a480 = MovieFoxSettingsDialog.isQuality480Enabled(ctx)
        if (!(a4k || a1080 || a720 || a480)) return true
        val hasTag = Regex("""\b(2160|1440|1080|720|480|4k|uhd|fhd|hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(label)
        if (!hasTag) return true
        return when {
            qualityValue >= Qualities.P1440.value -> a4k
            qualityValue >= Qualities.P1080.value -> a1080
            qualityValue >= Qualities.P720.value -> a720
            else -> a480
        }
    }
}
