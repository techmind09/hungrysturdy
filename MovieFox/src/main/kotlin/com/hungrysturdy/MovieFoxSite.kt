package com.hungrysturdy

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

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

internal object MovieFoxMoviesLinkBdSite {

    private const val HUB_URL = "https://movielinkbd.com"
    private const val DEFAULT_DOMAIN = "https://drjuea.movielinkbd.at"

    @Volatile
    private var workingDomain: String? = null

    @Volatile
    private var hubDomain: String? = null

    @Volatile
    private var hubCheckedAt = 0L

    private val mirrorRegex = Regex(
        "https?://([a-z0-9-]+\\.movielinkbd\\.(?:at|li|tv|app|net))(?=/)",
        RegexOption.IGNORE_CASE
    )

    private val keyRegex = Regex("var K=\"([a-f0-9]{24})\"")

    private fun decodeDl(payload: String, seedHex: String, rounds: Int): String? {
        return try {
            val raw = Base64.decode(payload, Base64.DEFAULT)
            var x = seedHex.toLong(16) and 0xFFFFFFFFL
            repeat(rounds) { x = (1103515245L * x + 12345L) and 0xFFFFFFFFL }
            for (i in raw.indices) {
                x = (1103515245L * x + 12345L) and 0xFFFFFFFFL
                raw[i] = ((x ushr 16).toInt() xor raw[i].toInt()).toByte()
            }
            String(raw, Charsets.UTF_8).trim().takeIf { it.startsWith("/file/") }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeGo(token: String, key: String): String? {
        return try {
            var b64 = token.replace("-", "+").replace("_", "/")
            val rem = b64.length % 4
            if (rem != 0) b64 += "=".repeat(4 - rem)
            val raw = Base64.decode(b64, Base64.DEFAULT)
            val out = StringBuilder(raw.size)
            for (i in raw.indices) {
                out.append(((raw[i].toInt() and 0xFF) xor key[i % key.length].code).toChar())
            }
            out.toString().trim().takeIf { it.startsWith("/go/") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun hubDomain(): String? {
        if (System.currentTimeMillis() - hubCheckedAt < 10 * 60_000L) return hubDomain
        hubCheckedAt = System.currentTimeMillis()
        val text = try {
            app.get(HUB_URL, headers = MovieFoxSiteNet.headers(), timeout = 15L).text
        } catch (_: Exception) {
            null
        } ?: return hubDomain
        val hosts = mirrorRegex.findAll(text)
            .map { it.groupValues[1].lowercase() }
            .filter { it != "movielinkbd.com" && it != "www.movielinkbd.com" }
            .distinct()
            .toList()
        val pick = hosts.firstOrNull { it.endsWith(".movielinkbd.at") }
            ?: hosts.firstOrNull { it.endsWith(".movielinkbd.li") }
            ?: hosts.firstOrNull()
        hubDomain = pick?.let { "https://$it" }
        return hubDomain
    }

    private suspend fun candidates(): List<String> {
        val list = mutableListOf<String>()
        workingDomain?.let { list.add(it) }
        hubDomain()?.let { list.add(it) }
        list.add(DEFAULT_DOMAIN)
        return list.distinct()
    }

    private data class BdPost(val title: String, val url: String)

    private class BdSession {
        private val cookies = ConcurrentHashMap<String, String>()

        fun absorb(res: NiceResponse) {
            runCatching { cookies.putAll(res.cookies) }
        }

        fun with(vararg extra: Pair<String, String>): Map<String, String> {
            val out = HashMap<String, String>(cookies)
            for ((k, v) in extra) out[k] = v
            return out
        }
    }

    private suspend fun searchPosts(domain: String, query: String, session: BdSession): List<BdPost> {
        val res = MovieFoxSiteNet.retry {
            try {
                app.get(
                    "$domain/search?q=${Uri.encode(query)}",
                    headers = MovieFoxSiteNet.headers(),
                    cookies = session.with(),
                    timeout = 15L
                )
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()
        session.absorb(res)
        val doc = runCatching { res.document }.getOrNull() ?: return emptyList()
        return runCatching {
            doc.select("div.movie-card").mapNotNull { card ->
                val a = card.selectFirst("a[href]") ?: return@mapNotNull null
                val href = a.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null
                val img = card.selectFirst("img")
                val title = img?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                    ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: a.text().trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                BdPost(title, MovieFoxSiteNet.absolute(href, domain))
            }.distinctBy { it.url }
        }.getOrDefault(emptyList())
    }

    private fun pickPost(posts: List<BdPost>, res: MovieFoxPlayData): BdPost? {
        val matched = posts.filter { MovieFoxSiteNet.titleMatchesFor(it.title, res) }
        if (matched.isEmpty()) return null
        if (res.season != null) {
            val seasonPosts = matched.filter { MovieFoxSiteNet.seasonsOf(it.title)?.contains(res.season) == true }
            if (seasonPosts.isNotEmpty()) {
                return seasonPosts.firstOrNull { MovieFoxSiteNet.yearMatches(it.title, res.matchYear) }
                    ?: seasonPosts.first()
            }
            return matched.firstOrNull { MovieFoxSiteNet.seasonsOf(it.title) == null }
        }
        return matched.firstOrNull { MovieFoxSiteNet.yearMatches(it.title, res.matchYear) }
    }

    private fun buttonLabel(a: Element): String {
        val bracket = Regex("\\[([^\\]]+)\\]").find(a.text())?.groupValues?.get(1)
        return bracket?.trim().orEmpty()
    }

    private fun decodeButton(a: Element): String? {
        val payload = a.attr("data-dl")
        val seed = a.attr("data-ds")
        val rounds = a.attr("data-dr").toIntOrNull() ?: return null
        if (payload.isBlank() || seed.isBlank()) return null
        return decodeDl(payload, seed, rounds)
    }

    private fun episodeCard(doc: Document, season: Int, episode: Int): Element? {
        return doc.select("div.ep-card").firstOrNull { card ->
            val cardSeason = card.attr("data-season-number").toIntOrNull()
            if (cardSeason != null && cardSeason != season) return@firstOrNull false
            if (card.attr("data-episode-number").toIntOrNull() == episode) return@firstOrNull true
            val start = card.attr("data-range-start").toIntOrNull()
            val end = card.attr("data-range-end").toIntOrNull()
            start != null && start == end && start == episode
        }
    }

    private fun episodeButtons(doc: Document, season: Int, episode: Int): List<Element> {
        val epTag = Regex("(?i)Episode\\s*:?\\s*0*$episode(?!\\d)")
        val seasonTag = Regex("(?i)\\bS0*$season\\b")
        val anySeason = Regex("(?i)\\bS\\d{1,2}\\b")
        for (h5 in doc.select("h5")) {
            val text = h5.text()
            if (!epTag.containsMatchIn(text)) continue
            if (anySeason.containsMatchIn(text) && !seasonTag.containsMatchIn(text)) continue
            val out = mutableListOf<Element>()
            var sib: Element? = h5.nextElementSibling()
            while (sib != null && sib.tagName() != "h5") {
                if (sib.tagName() == "a" && sib.hasAttr("data-dl")) out.add(sib)
                out.addAll(sib.select("a[data-dl][data-ds][data-dr]"))
                sib = sib.nextElementSibling()
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private data class BdDetail(
        val pageUrl: String,
        val files: List<BdFile>,
        val watchPath: String? = null
    )

    private data class BdFile(val info: String, val path: String)

    private suspend fun loadDetail(
        domain: String,
        postUrl: String,
        res: MovieFoxPlayData,
        session: BdSession
    ): BdDetail? {
        val response = try {
            app.get(
                postUrl,
                headers = MovieFoxSiteNet.headers("$domain/"),
                cookies = session.with(),
                timeout = 20L
            )
        } catch (_: Exception) {
            null
        } ?: return null
        session.absorb(response)
        val doc = response.document
        val pageUrl = response.url
        val watchPath = doc.selectFirst("iframe#mlbdHdFrame[src]")?.attr("src")?.trim()
            ?.takeIf { it.startsWith("/p/") }
        val buttons: List<Element> = if (res.season == null) {
            doc.select("a[data-dl][data-ds][data-dr]")
        } else {
            val episode = res.episode ?: return null
            val scoped = episodeButtons(doc, res.season, episode)
            scoped.ifEmpty {
                episodeCard(doc, res.season, episode)
                    ?.select("a[data-dl][data-ds][data-dr]")
                    ?: emptyList()
            }
        }
        val files = buttons.mapNotNull { a ->
            val path = decodeButton(a) ?: return@mapNotNull null
            val label = buttonLabel(a)
            val info = if (res.season != null && label.isNotBlank()) "Episode ${res.episode} $label" else label
            BdFile(info, path)
        }.distinctBy { it.path }
        return BdDetail(pageUrl, files, watchPath)
    }

    private class BdRow(val host: String, val path: String)

    private fun fileRows(doc: Document, key: String): List<BdRow> {
        val rows = mutableListOf<BdRow>()
        for (a in doc.select("a.dlh-arow")) {
            if (a.className().contains("dlh-promo")) continue
            val host = a.attr("data-host").trim().lowercase()
            if (host.endsWith("-promo")) continue
            val path = if (a.hasAttr("data-g")) {
                decodeGo(a.attr("data-g"), key) ?: continue
            } else {
                a.attr("href").trim().takeIf { it.startsWith("/") } ?: continue
            }
            val name = host.ifBlank {
                val text = a.text().lowercase()
                when {
                    text.contains("xcloud") -> "xcloud"
                    text.contains("instant") -> "instantcloud"
                    else -> ""
                }
            }
            rows.add(BdRow(name, path))
        }
        return rows.distinctBy { it.path }
    }

    private suspend fun goTarget(
        domain: String,
        path: String,
        session: BdSession,
        referer: String,
        key: String
    ): String? {
        return try {
            val res = app.get(
                MovieFoxSiteNet.absolute(path, domain),
                headers = MovieFoxSiteNet.headers(referer),
                cookies = session.with("mlbd_gk" to key),
                allowRedirects = false,
                timeout = 15L
            )
            session.absorb(res)
            val loc = res.headers["location"]?.trim().orEmpty()
            runCatching { res.okhttpResponse.close() }
            loc.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private val directMedia = Regex(
        """(?i)\.(mp4|mkv|webm|mov|m3u8)(?:\?|$)|\.r2\.cloudflarestorage\.com/|googleusercontent\.com/|\.r2\.dev/"""
    )

    private suspend fun emitDirect(
        url: String,
        server: String,
        info: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.startsWith("http")) return
        if (!MovieFoxSiteNet.alive(url, ExtractorLinkType.VIDEO)) return
        val qVal = quality ?: Qualities.Unknown.value
        val isHls = url.contains(".m3u8", true)
        val name = MovieFoxSiteLabels.buildLabel("movielinkbd", server, info)
        if (!MovieFoxSiteLabels.allowLink(CloudStreamApp.context, qVal, name, isHls)) return
        callback(
            newExtractorLink("[MovieFox • MovieLinkBD]", name, url, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.quality = qVal
            }
        )
    }

    private suspend fun emitFsl(
        url: String,
        info: String,
        quality: Int?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val target = MovieFoxSiteNet.resolveRedirectTarget(url, referer)
        if (target != null && !MovieFoxSiteNet.hostOf(target).contains("fsldownload")) {
            emitDirect(target, "FSL", info, quality, callback)
            return
        }
        val res = try {
            app.get(url, headers = MovieFoxSiteNet.headers(referer), timeout = 20L)
        } catch (_: Exception) {
            null
        } ?: return
        val doc = runCatching { res.document }.getOrNull()
        runCatching { res.okhttpResponse.close() }
        doc?.select("a[href]")?.forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && directMedia.containsMatchIn(href)) {
                emitDirect(href, "FSL", info, quality, callback)
                return
            }
        }
    }

    private suspend fun emitSubFile(
        domain: String,
        path: String,
        info: String,
        quality: Int?,
        referer: String,
        session: BdSession,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = try {
            app.get(
                MovieFoxSiteNet.absolute(path, domain),
                headers = MovieFoxSiteNet.headers(referer),
                cookies = session.with(),
                timeout = 20L
            )
        } catch (_: Exception) {
            null
        } ?: return
        session.absorb(res)
        val doc = runCatching { res.document }.getOrNull()
        val pageUrl = res.url
        runCatching { res.okhttpResponse.close() }
        doc ?: return

        for (a in doc.select("a.dlh-cta[href], div.fsl-action-area a[href], a[download][href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) continue
            when {
                href.contains("fsldownload") || MovieFoxSiteNet.hostOf(href).startsWith("fsl.") ->
                    emitFsl(href, info, quality, pageUrl, callback)
                directMedia.containsMatchIn(href) -> emitDirect(href, "Instant MP4", info, quality, callback)
                else -> loadExtractor(href, pageUrl, subtitleCallback) { link ->
                    if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, quality ?: link.quality, info, link.type == ExtractorLinkType.M3U8)) {
                        runBlocking { callback(link) }
                    }
                }
            }
        }
    }

    private suspend fun emitRow(
        domain: String,
        row: BdRow,
        info: String,
        quality: Int?,
        referer: String,
        key: String,
        session: BdSession,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            row.path.startsWith("/go/") -> {
                val target = goTarget(domain, row.path, session, referer, key) ?: return
                if (MovieFoxSiteNet.hostOf(target).contains("movielinkbd")) return
                if (row.host.contains("xcloud") || MovieFoxSiteNet.hostOf(target).contains("xcloud")) {
                    emitXCloud(target, info, quality, referer, callback)
                } else {
                    loadExtractor(target, referer, subtitleCallback) { link ->
                        if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, quality ?: link.quality, info, link.type == ExtractorLinkType.M3U8)) {
                            runBlocking { callback(link) }
                        }
                    }
                }
            }
            row.path.startsWith("/file/") -> {
                emitSubFile(domain, row.path, info, quality, referer, session, subtitleCallback, callback)
            }
        }
    }

    private suspend fun emitFile(
        domain: String,
        file: BdFile,
        pageUrl: String,
        session: BdSession,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileUrl = MovieFoxSiteNet.absolute(file.path, domain)
        val response = try {
            app.get(
                fileUrl,
                headers = MovieFoxSiteNet.headers(pageUrl),
                cookies = session.with(),
                timeout = 20L
            )
        } catch (_: Exception) {
            null
        } ?: return
        session.absorb(response)
        val text = response.text
        val key = keyRegex.find(text)?.groupValues?.get(1) ?: return
        val rows = fileRows(response.document, key)
        val quality = MovieFoxSiteNet.getIndexQuality(file.info)
        val semaphore = Semaphore(4)
        coroutineScope {
            rows.forEach { row ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        emitRow(domain, row, file.info, quality, fileUrl, key, session, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    private val xcloudGate = Mutex()
    private val mediaPattern = Regex("""(?i)\.(m3u8|mp4|mkv)(?:\?|$)""")

    private fun pd2Json(text: String): JSONObject? {
        val start = text.indexOf("window.__PD2")
        if (start < 0) return null
        var i = text.indexOf('{', start)
        if (i < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\' && inString) {
                escaped = true
            } else if (c == '"') {
                inString = !inString
            } else if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return runCatching {
                            JSONObject(text.substring(text.indexOf('{', start), i + 1))
                        }.getOrNull()
                    }
                }
            }
            i++
        }
        return null
    }

    private data class HlsVariant(val url: String, val height: Int)

    private fun parseVariants(master: String): List<HlsVariant> {
        val out = mutableListOf<HlsVariant>()
        var pending: Int? = null
        for (line in master.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                pending = Regex("""RESOLUTION=\d+x(\d+)""").find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
            } else if (!trimmed.startsWith("#") && trimmed.isNotEmpty() && pending != null) {
                out.add(HlsVariant(trimmed, pending))
                pending = null
            }
        }
        return out
    }

    private suspend fun emitWatch(
        domain: String,
        watchPath: String,
        res: MovieFoxPlayData,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val page = try {
                app.get(MovieFoxSiteNet.absolute(watchPath, domain), headers = MovieFoxSiteNet.headers("$domain/"), timeout = 15L).text
            } catch (_: Exception) {
                return
            }
            val config = pd2Json(page) ?: return
            var master = config.optString("master").takeIf { it.startsWith("http") }
            var label = "Watch"
            if (res.season != null) {
                val episode = res.episode ?: return
                val episodes = config.optJSONArray("episodes") ?: return
                var found: String? = null
                for (i in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(i) ?: continue
                    if (ep.optInt("season", -1) != res.season) continue
                    val from = ep.optInt("number", -1)
                    val to = ep.optInt("num_end", from)
                    if (episode in from..to) {
                        found = ep.optString("master").takeIf { it.startsWith("http") }
                        if (found != null) break
                    }
                }
                master = found ?: return
                label = "Episode $episode Watch"
            }
            if (master == null) return

            val streamHeaders = mapOf("Origin" to domain, "Referer" to "$domain/")
            val playlist = try {
                app.get(master, headers = MovieFoxSiteNet.headers() + streamHeaders, timeout = 15L).text
            } catch (_: Exception) {
                return
            }
            if (!playlist.trimStart().startsWith("#EXTM3U")) return
            val variants = parseVariants(playlist)
            val best = variants.maxOfOrNull { it.height } ?: Qualities.Unknown.value
            val name = MovieFoxSiteLabels.buildLabel("movielinkbd", label, "Multi Audio")
            if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, best, name, isHls = true)) {
                callback(
                    newExtractorLink("[MovieFox • MovieLinkBD]", name, master, ExtractorLinkType.M3U8) {
                        this.quality = best
                        this.headers = streamHeaders
                    }
                )
            }
            variants.sortedByDescending { it.height }.take(4).forEach { variant ->
                val vName = MovieFoxSiteLabels.buildLabel("movielinkbd", "$label ${variant.height}p", "")
                if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, variant.height, vName, isHls = true)) {
                    callback(
                        newExtractorLink("[MovieFox • MovieLinkBD]", vName, variant.url, ExtractorLinkType.M3U8) {
                            this.quality = variant.height
                            this.headers = streamHeaders
                        }
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun emitXCloud(
        url: String,
        info: String,
        quality: Int?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resolved = xcloudGate.withLock {
                val resolver = WebViewResolver(
                    interceptUrl = mediaPattern,
                    additionalUrls = listOf(mediaPattern),
                    script = """document.querySelector('a#download, .vd a.btn, .vd a')?.click();""",
                    useOkhttp = false,
                    timeout = 30_000L
                )
                try {
                    app.get(url, referer = referer, interceptor = resolver).url
                } catch (_: Exception) {
                    null
                }
            } ?: return
            if (!mediaPattern.containsMatchIn(resolved)) return
            val isHls = resolved.contains(".m3u8", true)
            val qVal = quality ?: Qualities.Unknown.value
            val name = MovieFoxSiteLabels.buildLabel("movielinkbd", "XCloud", info)
            if (!MovieFoxSiteLabels.allowLink(CloudStreamApp.context, qVal, name, isHls)) return
            callback(
                newExtractorLink(
                    "[MovieFox • MovieLinkBD]",
                    name,
                    resolved,
                    if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = qVal
                    this.headers = mapOf("Referer" to url)
                }
            )
        } catch (_: Exception) {}
    }

    suspend fun invoke(
        res: MovieFoxPlayData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (res.title.isNullOrBlank()) return

            var domain: String? = null
            var detail: BdDetail? = null
            val session = BdSession()
            outer@ for (candidate in candidates()) {
                for (query in MovieFoxSiteNet.searchQueries(res)) {
                    val found = searchPosts(candidate, query, session)
                    if (found.isEmpty()) continue
                    val pick = pickPost(found, res) ?: continue
                    val loaded = loadDetail(candidate, pick.url, res, session) ?: continue
                    if (loaded.files.isEmpty()) continue
                    domain = candidate
                    detail = loaded
                    break@outer
                }
            }
            if (domain == null || detail == null) return
            workingDomain = domain

            detail.watchPath?.let { watchPath ->
                emitWatch(domain, watchPath, res, callback)
            }

            val semaphore = Semaphore(4)
            coroutineScope {
                detail.files.take(8).forEach { file ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            emitFile(domain, file, detail.pageUrl, session, subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

internal object MovieFoxHiCineSite {
    private const val DEFAULT_API = "https://hicine.biz"

    @Volatile
    private var verifiedApi: String? = null

    private suspend fun servesApi(base: String): Boolean {
        return try {
            app.get("$base/hapi/home", headers = MovieFoxSiteNet.headers(referer = "$base/"), timeout = 8L)
                .text.trimStart().startsWith("{")
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun api(): String {
        verifiedApi?.let { return it }
        if (servesApi(DEFAULT_API)) {
            verifiedApi = DEFAULT_API
        }
        return verifiedApi ?: DEFAULT_API
    }

    private val SERVER_LABELS = mapOf(
        "fsl" to "FSL",
        "fsl2" to "FSLv2",
        "pixel" to "PixelDrain",
        "ten" to "10Gbps",
        "gdirect" to "G-Direct",
        "server1" to "Server 1",
        "gofile" to "Gofile"
    )

    private val OPTION_ORDER = listOf("pixel", "fsl2", "fsl", "server1", "ten", "gdirect")
    private val HOST_ORDER = listOf("vcloud", "gdirect", "vdrive")
    private val PAGE_HOSTS = setOf("transferit", "buzzheavier", "vikingfile")
    private val QUALITY_ORDER = listOf("2160p", "1080p", "720p", "480p", "1440p", "360p")

    private val dlGate = Semaphore(4)
    private val goPace = Mutex()
    private var goNotBefore = 0L
    private var goGapMs = 550L
    private const val GO_GAP_MIN = 450L
    private const val GO_GAP_MAX = 2400L
    private const val DEAD_STREAK_LIMIT = 8
    private const val RESOLVE_BUDGET_MS = 50_000L

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchItem(
        val id: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val kind: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchResponse(val items: List<SearchItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Group(
        val head: String? = null,
        val season: Int? = null,
        val quality: String? = null,
        val size: String? = null,
        val k: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TitleResponse(
        val title: String? = null,
        val kind: String? = null,
        val groups: List<Group>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Host(
        val id: String? = null,
        val name: String? = null,
        val k: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GroupItem(
        val ep: Int? = null,
        val hosts: List<Host>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GroupResponse(val items: List<GroupItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DlOption(
        val id: String? = null,
        val name: String? = null,
        val kind: String? = null,
        val k: String? = null,
        val ks: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DlResponse(val options: List<DlOption>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GoResponse(val url: String? = null)

    private class Slot(
        val group: Group,
        val hostKey: String,
        val options: List<DlOption>
    )

    private suspend fun fetchJson(url: String): String? {
        return try {
            val text = app.get(
                url,
                headers = MovieFoxSiteNet.headers(referer = MovieFoxSiteNet.baseUrlOf(url)),
                timeout = 12L
            ).text
            text.takeIf { it.trimStart().startsWith("{") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun search(query: String): List<SearchItem> {
        val text = fetchJson("${api()}/hapi/search?q=${URLEncoder.encode(query, "UTF-8")}&page=1")
            ?: return emptyList()
        return runCatching {
            parseJson<SearchResponse>(text).items.orEmpty().filter { !it.id.isNullOrBlank() }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchTitle(id: String): TitleResponse? {
        val text = fetchJson("${api()}/hapi/t?c=${URLEncoder.encode(id, "UTF-8")}") ?: return null
        return runCatching { parseJson<TitleResponse>(text) }.getOrNull()
    }

    private suspend fun fetchGroup(k: String): List<GroupItem> {
        val text = fetchJson("${api()}/hapi/g?k=${URLEncoder.encode(k, "UTF-8")}") ?: return emptyList()
        return runCatching { parseJson<GroupResponse>(text).items.orEmpty() }.getOrDefault(emptyList())
    }

    private suspend fun fetchDlOptions(k: String): List<DlOption> {
        val text = dlGate.withPermit {
            fetchJson("${api()}/hapi/dl?k=${URLEncoder.encode(k, "UTF-8")}")
        } ?: return emptyList()
        return runCatching { parseJson<DlResponse>(text).options.orEmpty() }.getOrDefault(emptyList())
    }

    private suspend fun <T> atGoPace(block: suspend () -> T): T = goPace.withLock {
        val wait = goNotBefore - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val out = block()
        goNotBefore = System.currentTimeMillis() + goGapMs
        out
    }

    private fun paceUp() {
        goGapMs = (goGapMs - 120L).coerceAtLeast(GO_GAP_MIN)
    }

    private fun paceDown() {
        goGapMs = (goGapMs + 350L).coerceAtMost(GO_GAP_MAX)
    }

    private suspend fun fetchGoUrl(key: String): String? {
        return atGoPace {
            val text = fetchJson("${api()}/hapi/go?k=${URLEncoder.encode(key, "UTF-8")}&m=u")
            val url = if (text == null) null else runCatching {
                parseJson<GoResponse>(text).url?.takeIf { it.startsWith("http") }
            }.getOrNull()
            if (url != null) paceUp() else paceDown()
            url
        }
    }

    private suspend fun resolveOption(option: DlOption): String? {
        val short = option.ks?.takeIf { it.isNotBlank() }
        val long = option.k?.takeIf { it.isNotBlank() }
        if (short != null) fetchGoUrl(short)?.let { return it }
        if (long != null && long != short) fetchGoUrl(long)?.let { return it }
        return null
    }

    private fun yearOk(item: SearchItem, want: Int?): Boolean {
        if (want == null || item.year == null) return true
        return kotlin.math.abs(item.year - want) <= 1
    }

    private fun pickTitle(hits: List<SearchItem>, res: MovieFoxPlayData): SearchItem? {
        val wantSeries = res.season != null
        val title = res.title ?: return null
        val matches = hits.filter { hit -> hit.title != null && MovieFoxSiteNet.titleMatches(hit.title, title) }
        val byKind = matches.filter { (it.kind == "series") == wantSeries }
        val pool = if (byKind.isNotEmpty()) byKind else matches
        return pool.firstOrNull { yearOk(it, res.matchYear) } ?: pool.firstOrNull()
    }

    private fun pickGroups(groups: List<Group>, res: MovieFoxPlayData): List<Group> {
        val withKey = groups.filter { !it.k.isNullOrBlank() }
        val pool = if (res.season != null) {
            withKey.filter { it.season == res.season }.ifEmpty { withKey }
        } else {
            withKey
        }
        return pool.sortedBy { g ->
            val idx = QUALITY_ORDER.indexOf(g.quality?.lowercase() ?: "")
            if (idx < 0) QUALITY_ORDER.size else idx
        }
    }

    private fun optionRank(id: String?): Int = OPTION_ORDER.indexOf(id).let { if (it < 0) OPTION_ORDER.size else it }
    private fun hostRank(id: String?): Int = HOST_ORDER.indexOf(id).let { if (it < 0) HOST_ORDER.size else it }

    private fun pixelDirect(pageUrl: String): String? {
        val id = pageUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')
        if (id.length !in 5..15) return null
        return "https://pixeldrain.dev/api/file/$id"
    }

    private suspend fun emitDirect(
        url: String,
        server: String,
        info: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!MovieFoxSiteNet.alive(url, ExtractorLinkType.VIDEO)) return
        val qVal = quality ?: MovieFoxSiteNet.getIndexQuality(info)
        val name = MovieFoxSiteLabels.buildLabel("hicine", server, info)
        if (!MovieFoxSiteLabels.allowLink(CloudStreamApp.context, qVal, name, isHls = false)) return
        callback(
            newExtractorLink("[MovieFox • HiCine]", name, url, ExtractorLinkType.VIDEO) {
                this.quality = qVal
            }
        )
    }

    private suspend fun emitResolved(
        option: DlOption,
        url: String,
        info: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = option.id ?: ""
        val server = SERVER_LABELS[id] ?: option.name?.takeIf { it.isNotBlank() } ?: id.ifBlank { "Server" }
        when {
            id == "pixel" || url.contains("pixeldrain.dev/u/") ->
                pixelDirect(url)?.let { emitDirect(it, server, info, quality, callback) }
            id == "gofile" || url.contains("gofile.io") ->
                loadExtractor(url, null, subtitleCallback) { link ->
                    if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, quality ?: link.quality, info, link.type == ExtractorLinkType.M3U8)) {
                        runBlocking { callback(link) }
                    }
                }
            url.contains("transfer.it") || url.contains("bzzhr.co") ||
                url.contains("buzzheavier") || url.contains("vikingfile") -> Unit
            else -> emitDirect(url, server, info, quality, callback)
        }
    }

    private suspend fun collectSlots(groups: List<Group>, season: Int?, episode: Int?): List<Slot> {
        return coroutineScope {
            groups.map { group ->
                async(Dispatchers.IO) {
                    val items = fetchGroup(group.k ?: return@async emptyList())
                    val scoped = if (season != null && episode != null) {
                        val exact = items.filter { it.ep == episode }
                        if (exact.isNotEmpty()) exact else items.filter { it.ep == null }
                    } else {
                        items.filter { it.ep == null }.ifEmpty { items.take(1) }
                    }
                    val item = scoped.firstOrNull() ?: return@async emptyList()
                    item.hosts.orEmpty()
                        .distinctBy { it.id }
                        .sortedBy { hostRank(it.id) }
                        .take(3)
                        .mapNotNull { host ->
                            val key = host.k?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val options = fetchDlOptions(key).filter {
                                it.id !in PAGE_HOSTS && (!it.k.isNullOrBlank() || !it.ks.isNullOrBlank())
                            }
                            if (options.isEmpty()) null else Slot(group, key, options)
                        }
                }
            }.flatMap { it.await() }
        }
    }

    private suspend fun resolveAndEmit(
        slots: List<Slot>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val targets = slots.flatMap { slot ->
            slot.options.sortedBy { optionRank(it.id) }.map { slot to it }
        }.sortedBy { (_, option) -> optionRank(option.id) }

        val deadline = System.currentTimeMillis() + RESOLVE_BUDGET_MS
        val failed = mutableListOf<Pair<Slot, DlOption>>()
        var deadStreak = 0

        coroutineScope {
            for ((slot, option) in targets) {
                if (deadStreak >= DEAD_STREAK_LIMIT || System.currentTimeMillis() > deadline) break
                val url = resolveOption(option)
                if (url == null) {
                    failed.add(slot to option)
                    deadStreak++
                    continue
                }
                deadStreak = 0
                val head = slot.group.head.orEmpty()
                val info = head.ifBlank { listOfNotNull(slot.group.quality, slot.group.size).joinToString(" ") }
                val quality = MovieFoxSiteNet.getIndexQuality(head)
                async(Dispatchers.IO) {
                    emitResolved(option, url, info, quality, subtitleCallback, callback)
                }
            }
        }

        if (failed.isEmpty() || System.currentTimeMillis() > deadline) return
        retryWithFreshKeys(failed, deadline, subtitleCallback, callback)
    }

    private suspend fun retryWithFreshKeys(
        failed: List<Pair<Slot, DlOption>>,
        deadline: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val wanted = failed.groupBy({ it.first }, { it.second.id })
        var misses = 0
        coroutineScope {
            for ((slot, ids) in wanted) {
                if (System.currentTimeMillis() > deadline || misses >= 3) break
                val fresh = fetchDlOptions(slot.hostKey).filter { it.id in ids }
                if (fresh.isEmpty()) continue
                for (option in fresh) {
                    if (System.currentTimeMillis() > deadline) return@coroutineScope
                    val url = resolveOption(option)
                    if (url == null) {
                        misses++
                    } else {
                        misses = 0
                        val head = slot.group.head.orEmpty()
                        val info = head.ifBlank { listOfNotNull(slot.group.quality, slot.group.size).joinToString(" ") }
                        val quality = MovieFoxSiteNet.getIndexQuality(head)
                        async(Dispatchers.IO) {
                            emitResolved(option, url, info, quality, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
    }

    suspend fun invoke(
        res: MovieFoxPlayData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val title = res.title ?: return
            val hits = search(title)
            if (hits.isEmpty()) return
            val picked = pickTitle(hits, res) ?: return
            val detail = fetchTitle(picked.id ?: return) ?: return
            val groups = pickGroups(detail.groups.orEmpty(), res)
            if (groups.isEmpty()) return

            val slots = collectSlots(groups, res.season, res.episode)
            if (slots.isEmpty()) return

            resolveAndEmit(slots, subtitleCallback, callback)
        } catch (_: Exception) {}
    }
}

internal object MovieFoxHindMoviezSite {
    private const val DEFAULT_DOMAIN = "https://v3.hindmovie.im"

    private suspend fun searchPosts(domain: String, query: String): List<Pair<String, String>> {
        val doc = MovieFoxSiteNet.retry {
            try {
                app.get(
                    "$domain/?s=${URLEncoder.encode(query, "UTF-8")}",
                    headers = MovieFoxSiteNet.headers(),
                    timeout = 15L
                ).document
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()
        return runCatching {
            doc.select("h2.entry-title > a[href]").mapNotNull { a ->
                val title = a.text().replace(Regex("\\s+"), " ").trim()
                val href = a.attr("href").trim()
                if (title.isBlank() || !href.startsWith("http")) null else title to href
            }
        }.getOrDefault(emptyList())
    }

    private fun pickPost(posts: List<Pair<String, String>>, res: MovieFoxPlayData): Pair<String, String>? {
        val matched = posts.filter { MovieFoxSiteNet.titleMatchesFor(it.first, res) }
        if (matched.isEmpty()) return null
        if (res.season != null) {
            val seasonPosts = matched.filter { MovieFoxSiteNet.seasonsOf(it.first)?.contains(res.season) == true }
            if (seasonPosts.isNotEmpty()) {
                return seasonPosts.firstOrNull { MovieFoxSiteNet.yearMatches(it.first, res.matchYear) } ?: seasonPosts.first()
            }
            return matched.firstOrNull { MovieFoxSiteNet.seasonsOf(it.first) == null }
        }
        return matched.firstOrNull { MovieFoxSiteNet.yearMatches(it.first, res.matchYear) }
    }

    private suspend fun fetchDoc(url: String, referer: String? = null): Document? {
        return try {
            app.get(url, headers = MovieFoxSiteNet.headers(referer), timeout = 20L).document
        } catch (_: Exception) {
            null
        }
    }

    private fun shareInfo(doc: Document): Pair<String, Int> {
        val name = doc.selectFirst("p:contains(Name:)")?.text()?.substringAfter("Name:")?.trim().orEmpty()
        val size = doc.selectFirst("p:contains(Size:)")?.text()?.substringAfter("Size:")?.trim().orEmpty()
        val info = listOf(name, size).filter { it.isNotBlank() }.joinToString(" ")
        return info to MovieFoxSiteNet.getIndexQuality(info)
    }

    private fun b64decode(value: String): String {
        var s = value.replace("-", "+").replace("_", "/")
        val rem = s.length % 4
        if (rem != 0) s += "=".repeat(4 - rem)
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun hpageTarget(href: String): String? {
        val outer = href.substringAfter("url=", "").substringBefore("&")
        if (outer.isBlank()) return null
        val layer1 = b64decode(outer)
        val inner = layer1.substringAfter("url=", "").substringBefore("&")
        if (inner.isBlank()) return null
        val target = b64decode(inner)
        return target.takeIf { it.startsWith("http") }
    }

    private suspend fun gdshineTarget(shortId: String): String? {
        if (shortId.isBlank()) return null
        val fileId = try {
            val text = app.get(
                "https://gdshine.org/api/files/s/$shortId",
                headers = MovieFoxSiteNet.headers("https://gdshine.org/"),
                timeout = 15L
            ).text
            JSONObject(text).optJSONObject("data")?.optString("id")
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            val text = app.get(
                "https://gdshine.org/api/gp/mirror/$fileId",
                headers = MovieFoxSiteNet.headers("https://gdshine.org/"),
                timeout = 15L
            ).text
            JSONObject(text).optJSONObject("data")?.optString("url")
        } catch (_: Exception) {
            null
        }?.takeIf { it.startsWith("http") }
    }

    private suspend fun emitLink(
        url: String,
        info: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!MovieFoxSiteNet.alive(url, ExtractorLinkType.VIDEO)) return
        val name = MovieFoxSiteLabels.buildLabel("hindmoviez", "", info)
        if (!MovieFoxSiteLabels.allowLink(CloudStreamApp.context, quality, name, isHls = false)) return
        callback(
            newExtractorLink("[MovieFox • HindMoviez]", name, url, ExtractorLinkType.VIDEO) {
                this.quality = quality
            }
        )
    }

    private suspend fun emitShare(shareHref: String, callback: (ExtractorLink) -> Unit) {
        if (!shareHref.contains("?id=")) return
        val res = try {
            app.get(shareHref, headers = MovieFoxSiteNet.headers(), timeout = 20L)
        } catch (_: Exception) {
            null
        } ?: return
        val doc = runCatching { res.document }.getOrNull() ?: return
        val (info, quality) = shareInfo(doc)
        if (info.isBlank()) return

        val semaphore = Semaphore(4)
        coroutineScope {
            for (a in doc.select("div.btn-group a.btn, a.btn")) {
                val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
                if (!href.startsWith("http")) continue
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        when {
                            a.classNames().contains("btn-info") ->
                                hpageTarget(href)?.let { emitLink(it, info, quality, callback) }
                            href.contains("gdshine.org/s/") ->
                                gdshineTarget(href.substringAfter("/s/").substringBefore("?"))
                                    ?.let { emitLink(it, info, quality, callback) }
                        }
                    }
                }
            }
        }
    }

    private fun episodeMatch(a: Element, res: MovieFoxPlayData): Boolean {
        if (res.episode == null) return false
        val ep = res.episode
        val fileName = Uri.decode(a.attr("href").substringAfter("?id=", ""))
        val sxxexx = Regex("(?i)S0*${res.season ?: 1}\\s*[-–]?\\s*E0*$ep(?!\\d)")
        val anyMarker = Regex("(?i)\\bS\\d{1,2}\\s*[-–]?\\s*E\\d{1,3}\\b")
        return when {
            anyMarker.containsMatchIn(fileName) -> sxxexx.containsMatchIn(fileName)
            else -> Regex("(?i)Episode\\s*:?\\s*0*$ep(?!\\d)").containsMatchIn(a.text())
        }
    }

    suspend fun invoke(
        res: MovieFoxPlayData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (res.title.isNullOrBlank()) return

            var chosen: Pair<String, String>? = null
            for (query in MovieFoxSiteNet.searchQueries(res)) {
                val found = searchPosts(DEFAULT_DOMAIN, query)
                if (found.isEmpty()) continue
                chosen = pickPost(found, res)
                if (chosen != null) break
            }
            if (chosen == null) return

            val postDoc = fetchDoc(chosen.second) ?: return
            val targets = postDoc.select("a.maxbutton").mapNotNull { a ->
                val href = a.attr("href").trim()
                if (!href.startsWith("http")) null else href
            }.distinct()
            if (targets.isEmpty()) return

            val semaphore = Semaphore(3)
            coroutineScope {
                targets.forEach { linkUrl ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val page = fetchDoc(linkUrl) ?: return@withPermit
                            if (res.season == null) {
                                page.select("a[href]").firstOrNull {
                                    it.attr("href").contains("?id=")
                                }?.attr("abs:href")?.trim()?.let {
                                    if (it.startsWith("http")) emitShare(it, callback)
                                }
                            } else {
                                page.select("a[href]").firstOrNull {
                                    it.attr("href").contains("?id=") && episodeMatch(it, res)
                                }?.attr("abs:href")?.trim()?.let {
                                    if (it.startsWith("http")) emitShare(it, callback)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

internal object MovieFoxDrivePages {

    private val junk = Regex(
        "gmpg\\.org|googleapis|googletagmanager|fonts\\.|schema\\.org|w3\\.org|" +
            "twitter\\.com|facebook\\.com|pinterest|whatsapp|telegram|t\\.me/|/tg/|catimages|tinyurl|bonuscaf|" +
            "winexch|a-ads|pixabay|i-poster|scene-source|vglist|vegamovies-apk|hdhub4u\\.download|hdhub4u\\.tv"
    )

    private fun isSelfLink(href: String, hosts: Set<String>): Boolean {
        return try {
            hosts.contains(URI(href).host?.lowercase())
        } catch (_: Exception) {
            false
        }
    }

    private fun episodeRegexes(episode: Int, season: Int?): List<Regex> {
        val s2 = (season ?: 1).toString().padStart(2, '0')
        return listOf(
            Regex("(?i)Episodes?\\s*:?\\s*0*$episode(?!\\d)"),
            Regex("(?i)\\b${s2}\\s*[xXeE]\\s*0*$episode(?!\\d)"),
            Regex("(?i)\\b${season ?: 1}\\s*[xX]\\s*$episode(?!\\d)")
        )
    }

    private fun headingLinks(doc: Document, regexes: List<Regex>): List<Pair<String, String>>? {
        val heads = doc.select("h2, h3, h4, h5").filter { el ->
            val text = el.text().replace('\u00A0', ' ')
            regexes.any { it.containsMatchIn(text) }
        }
        if (heads.isEmpty()) return null
        val links = mutableListOf<Pair<String, String>>()
        for (head in heads) {
            val label = head.text().replace(Regex("\\s+"), " ").trim()
            var sib = head.nextElementSibling()
            while (sib != null && sib.tagName() !in listOf("h2", "h3", "h4", "h5")) {
                sib.select("a[href]").forEach { a ->
                    links.add(a.attr("href").trim() to label)
                }
                sib = sib.nextElementSibling()
            }
        }
        return links.ifEmpty { null }
    }

    private suspend fun emitTarget(
        site: String,
        href: String,
        info: String,
        quality: Int?,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(href, referer, subtitleCallback) { link ->
            val qVal = quality ?: link.quality
            val name = MovieFoxSiteLabels.buildLabel(site, link.name, info)
            if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, qVal, name, link.type == ExtractorLinkType.M3U8)) {
                runBlocking { callback(link) }
            }
        }
    }

    suspend fun emit(
        site: String,
        driveUrl: String,
        episode: Int?,
        season: Int? = null,
        label: String = "",
        siteDomain: String? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fetchUrl = driveUrl.replace("mobilejsr.rest", "nexdrive.fit")
            val res = MovieFoxSiteNet.fetchDrivePage(fetchUrl, referer) ?: return
            val doc = res.document

            val pageTitle = doc.title().substringBefore(" – ").substringBefore(" - ").trim()
            if (pageTitle.contains("zip", true)) return
            val driveHost = runCatching { URI(res.url).host?.lowercase() }.getOrNull()
            val hosts = listOfNotNull(
                driveHost,
                siteDomain?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
            ).toSet()

            if (episode != null) {
                val links = headingLinks(doc, episodeRegexes(episode, season))
                if (links != null) {
                    val picked = links.mapNotNull { (href, headLabel) ->
                        if (href.startsWith("http") && !junk.containsMatchIn(href) && !isSelfLink(href, hosts)) {
                            href to headLabel
                        } else null
                    }.distinctBy { it.first }
                    coroutineScope {
                        picked.forEach { (href, headLabel) ->
                            async(Dispatchers.IO) {
                                val quality = listOf(headLabel, label).firstNotNullOfOrNull {
                                    MovieFoxSiteNet.getIndexQuality(it).takeIf { q -> q != Qualities.Unknown.value }
                                }
                                emitTarget(site, href, "Episode $episode", quality, fetchUrl, subtitleCallback, callback)
                            }
                        }
                    }
                    return
                }
                val markers = episodeRegexes(episode, season)
                val row = doc.select("a[href]").firstOrNull { a ->
                    val href = a.attr("href").trim()
                    href.startsWith("http") && !junk.containsMatchIn(href) &&
                        markers.any { it.containsMatchIn(a.text()) }
                }
                if (row != null) {
                    val href = row.attr("href").trim()
                    if (!isSelfLink(href, hosts)) {
                        emitTarget(site, href, "Episode $episode", null, fetchUrl, subtitleCallback, callback)
                        return
                    }
                }
                return
            }

            val external = doc.select("a[href]").mapNotNull { el ->
                val href = el.attr("href").trim()
                if (!href.startsWith("http")) null
                else if (junk.containsMatchIn(href) || isSelfLink(href, hosts)) null
                else href
            }.distinct()

            val fallbackTitle = if (label.isBlank()) {
                doc.title().substringBefore(" – ").substringBefore(" - ").trim()
            } else ""
            val effectiveLabel = label.ifBlank { fallbackTitle }
            val quality = if (effectiveLabel.isBlank()) null else MovieFoxSiteNet.getIndexQuality(effectiveLabel)

            coroutineScope {
                external.forEach { href ->
                    async(Dispatchers.IO) {
                        emitTarget(site, href, effectiveLabel, quality, fetchUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

internal object MovieFoxHdHub4uSite {
    private const val DEFAULT_DOMAIN = "https://new13.hdhub4u.cl"
    private const val DOMAIN_REGISTRY =
        "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"

    @Volatile
    private var registryDomain: String? = null

    @Volatile
    private var registryCheckedAt = 0L

    private suspend fun registryDomain(): String? {
        val now = System.currentTimeMillis()
        if (registryDomain == null && now - registryCheckedAt > 10 * 60 * 1000L) {
            registryCheckedAt = now
            registryDomain = try {
                val text = app.get(DOMAIN_REGISTRY, timeout = 10L).text
                JSONObject(text).optString("HDHUB4u").takeIf { it.startsWith("http") }
            } catch (_: Exception) {
                null
            }
        }
        return registryDomain
    }

    private suspend fun domain(): String = registryDomain() ?: DEFAULT_DOMAIN

    private data class HubPost(val title: String, val url: String, val imdbId: String)

    private suspend fun typesenseSearch(domain: String, query: String): List<HubPost> {
        return try {
            val url = "https://search.pingora.fyi/collections/post/documents/search" +
                "?q=${Uri.encode(query)}" +
                "&query_by=post_title,category&query_by_weights=4,2" +
                "&sort_by=sort_by_date:desc&limit=20&highlight_fields=none&use_cache=true&page=1"
            val text = MovieFoxSiteNet.retry {
                try {
                    app.get(
                        url,
                        headers = mapOf("User-Agent" to MovieFoxSiteNet.PLAY_UA, "Referer" to "$domain/"),
                        timeout = 15L
                    ).text
                } catch (_: Exception) {
                    null
                }
            } ?: return emptyList()
            val hits = JSONObject(text).optJSONArray("hits") ?: return emptyList()
            (0 until hits.length()).mapNotNull { i ->
                val d = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
                val postTitle = d.optString("post_title")
                val permalink = d.optString("permalink")
                if (postTitle.isBlank() || permalink.isBlank()) return@mapNotNull null
                val url2 = if (permalink.startsWith("http", true)) {
                    val path = runCatching { URI(permalink).path }.getOrNull()
                    if (path.isNullOrBlank()) permalink else domain + path
                } else {
                    domain + permalink
                }
                HubPost(postTitle, url2, d.optString("imdb_id"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun emitResolved(
        url: String,
        label: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.isBlank()) return
        val qVal = MovieFoxSiteNet.getIndexQuality(label)
        loadExtractor(url, referer, subtitleCallback) { link ->
            val name = MovieFoxSiteLabels.buildLabel("hdhub4u", link.name, label)
            if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, qVal, name, link.type == ExtractorLinkType.M3U8)) {
                runBlocking { callback(link) }
            }
        }
    }

    private suspend fun processPost(
        domain: String,
        postUrl: String,
        res: MovieFoxPlayData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val pageRes = app.get(postUrl, headers = MovieFoxSiteNet.headers("$domain/"), timeout = 20L)
            val doc = pageRes.document
            runCatching { pageRes.okhttpResponse.close() }
            if (res.season != null) {
                emitEpisodes(doc, domain, res, subtitleCallback, callback)
            } else {
                val semaphore = Semaphore(4)
                coroutineScope {
                    for (el in doc.select("h3 a:matches((?i)480|720|1080|2160|4K), h4 a:matches((?i)480|720|1080|2160|4K)")) {
                        val href = el.absUrl("href").ifBlank { el.attr("href") }
                        if (!href.startsWith("http")) continue
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                emitResolved(href, el.text().trim(), domain, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun emitEpisodes(
        doc: Document,
        domain: String,
        res: MovieFoxPlayData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val marker = Regex("(?i)episodes?\\s*[:\\-]?\\s*(\\d+)")
        val wanted = res.episode ?: return
        val head = doc.select("h3, h4").firstOrNull { el ->
            marker.find(el.text())?.groupValues?.get(1)?.toIntOrNull() == wanted
        } ?: return

        val containers = mutableListOf<Element>()
        containers.add(head)
        var sib = head.nextElementSibling()
        while (sib != null && sib.tagName() !in listOf("h2", "h3", "h4", "h5")) {
            containers.add(sib)
            sib = sib.nextElementSibling()
        }

        val semaphore = Semaphore(4)
        coroutineScope {
            containers.forEach { box ->
                val quality = MovieFoxSiteNet.getIndexQuality(box.text())
                for (a in box.select("a[href]")) {
                    val href = a.absUrl("href").ifBlank { a.attr("href") }
                    if (!href.startsWith("http")) continue
                    val label = a.text().trim().lowercase()
                    when {
                        label.contains("watch") -> {
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    emitResolved(href, "Episode $wanted Watch", domain, subtitleCallback, callback)
                                }
                            }
                        }
                        href.contains("search-recover") -> {
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    searchRecover(href, quality, wanted, subtitleCallback, callback)
                                }
                            }
                        }
                        else -> {
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    emitResolved(href, "Episode $wanted", domain, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun searchRecover(
        url: String,
        quality: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val base = MovieFoxSiteNet.baseUrlOf(url)
            val q = Uri.parse(url).getQueryParameter("q") ?: return
            val fromAc = Uri.parse(url).getQueryParameter("from_ac")
            val api = "$base/drive/search-recover.php?api=search" +
                "&q=${Uri.encode(q)}&page=1" +
                (fromAc?.let { "&from_ac=${Uri.encode(it)}" } ?: "")
            val text = MovieFoxSiteNet.retry {
                try {
                    val body = app.get(
                        api,
                        headers = MovieFoxSiteNet.headers("$base/").toMutableMap().apply {
                            put("Accept", "application/json")
                        },
                        timeout = 15L
                    ).text
                    if (JSONObject(body).optJSONArray("hits") != null) body else null
                } catch (_: Exception) {
                    null
                }
            } ?: return
            val hits = JSONObject(text).optJSONArray("hits") ?: return
            val packText = Regex("(?i)\\.(zip|rar|7z)$|\\b(batch|pack|complete)\\b")
            val driveLinks = (0 until hits.length()).mapNotNull { i ->
                val hit = hits.optJSONObject(i) ?: return@mapNotNull null
                val name = hit.optString("file_name")
                val link = hit.optString("url")
                if (link.isBlank() || packText.containsMatchIn(name)) null else link
            }.distinct().take(3)
            driveLinks.forEach { link ->
                loadExtractor(link, url, subtitleCallback) { extractorLink ->
                    val name = MovieFoxSiteLabels.buildLabel("hdhub4u", extractorLink.name, "Episode $episode")
                    if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, quality, name, extractorLink.type == ExtractorLinkType.M3U8)) {
                        runBlocking { callback(extractorLink) }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun invoke(
        res: MovieFoxPlayData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = domain()
            if (res.title.isNullOrBlank()) return

            var chosen: List<HubPost> = emptyList()
            for (query in MovieFoxSiteNet.searchQueries(res)) {
                val posts = typesenseSearch(domain, query)
                if (posts.isEmpty()) continue

                res.imdbId?.takeIf { it.isNotBlank() }?.let { id ->
                    posts.firstOrNull { it.imdbId.equals(id, ignoreCase = true) }?.let { hit ->
                        chosen = listOf(hit)
                    }
                }

                if (chosen.isEmpty()) {
                    val matched = posts.filter { MovieFoxSiteNet.titleMatchesFor(it.title, res) }
                    if (matched.isNotEmpty()) {
                        chosen = if (res.season != null) {
                            val seasonPosts = matched.filter {
                                MovieFoxSiteNet.seasonsOf(it.title)?.contains(res.season) == true
                            }
                            if (seasonPosts.isNotEmpty()) seasonPosts
                            else matched.filter { MovieFoxSiteNet.seasonsOf(it.title) == null }
                        } else {
                            matched.filter { MovieFoxSiteNet.yearMatches(it.title, res.matchYear) }
                        }.take(2)
                    }
                }
                if (chosen.isNotEmpty()) break
            }
            if (chosen.isEmpty()) return

            coroutineScope {
                chosen.forEach { post ->
                    async(Dispatchers.IO) {
                        processPost(domain, post.url, res, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

internal object MovieFoxFourKhdHubSite {
    private const val DEFAULT_DOMAIN = "https://4khdhub.one"

    private suspend fun emitLinks(
        hrefs: List<String>,
        label: String,
        domain: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val semaphore = Semaphore(6)
        val qVal = MovieFoxSiteNet.getIndexQuality(label)
        coroutineScope {
            hrefs.distinct().forEach { href ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (!href.startsWith("http")) return@withPermit
                        loadExtractor(href, domain, subtitleCallback) { link ->
                            val name = MovieFoxSiteLabels.buildLabel("4khdhub", link.name, label)
                            if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, qVal, name, link.type == ExtractorLinkType.M3U8)) {
                                runBlocking { callback(link) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun blockLabel(el: Element): String {
        val fileTitle = el.selectFirst(".episode-file-title")?.text()?.trim().orEmpty()
        if (fileTitle.isNotBlank()) return fileTitle
        val movieTitle = el.selectFirst("div.file-title")?.text()?.trim().orEmpty()
        if (movieTitle.isNotBlank()) return movieTitle
        val header = el.selectFirst("div.download-header, div.episode-header")?.text()?.trim().orEmpty()
        if (header.isNotBlank()) return header.replace(Regex("\\s+"), " ")
        return ""
    }

    suspend fun invoke(
        res: MovieFoxPlayData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = DEFAULT_DOMAIN
            if (res.title.isNullOrBlank()) return

            var matched: Element? = null
            for (query in MovieFoxSiteNet.searchQueries(res)) {
                val searchDoc = MovieFoxSiteNet.retry {
                    try {
                        app.get(
                            "$domain/?s=${Uri.encode(query)}",
                            headers = MovieFoxSiteNet.headers(),
                            timeout = 20L
                        ).document
                    } catch (_: Exception) {
                        null
                    }
                } ?: continue
                val elements = searchDoc.select("div.card-grid > a.movie-card")
                fun contentOf(el: Element): String = el.selectFirst("div.movie-card-content")?.text()?.lowercase() ?: ""

                matched = elements.firstOrNull { el ->
                    val content = contentOf(el)
                    MovieFoxSiteNet.titleMatchesFor(content, res) &&
                        MovieFoxSiteNet.yearMatches(content, res.year ?: res.matchYear)
                }
                if (matched != null) break
            }
            if (matched == null) return

            val href = matched.attr("href").trim()
            val detailUrl = if (href.startsWith("http")) href else domain + href
            val doc = app.get(detailUrl, headers = MovieFoxSiteNet.headers(domain), timeout = 20L).document

            if (res.season != null) {
                val seasonText = "S" + res.season.toString().padStart(2, '0')
                val epRegex = if (res.episode != null) {
                    Regex("(?i)${seasonText}\\s*[xXeE]\\s*0*${res.episode}(?!\\d)")
                } else {
                    Regex("(?i)\\b$seasonText(?!\\d)")
                }
                val items = doc.select("div.episode-download-item").filter { el ->
                    epRegex.containsMatchIn(el.text().replace('\u00A0', ' '))
                }
                val hrefs = items.flatMap { el ->
                    el.select("div.episode-links > a, a[href]").map { it.attr("href").trim() }
                }
                val labels = items.map { blockLabel(it) }
                val label = labels.firstOrNull { it.isNotBlank() }?.let { l ->
                    res.episode?.let { "S${res.season}E$it - $l" } ?: l
                } ?: "S${res.season}${res.episode?.let { "E$it" } ?: ""}"
                emitLinks(hrefs, label, domain, subtitleCallback, callback)
            } else {
                val items = doc.select("div.download-item")
                if (items.isNotEmpty()) {
                    items.forEach { el ->
                        val hrefs = el.select("a[href]").map { it.attr("href").trim() }
                        val label = blockLabel(el).ifBlank { doc.title() }
                        emitLinks(hrefs, label, domain, subtitleCallback, callback)
                    }
                } else {
                    val hrefs = doc.select("div.download-item a[href], a[href*=id=]").map { it.attr("href").trim() }
                    emitLinks(hrefs, doc.title(), domain, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }
}

internal object MovieFoxMovies4uSite {
    private const val DEFAULT_DOMAIN = "https://new6.movies4u.garden"
    private const val DOMAIN_REGISTRY =
        "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
    private val driveHostRegex = Regex("mdrive\\.cloud|nexdrive\\.fit|mobilejsr\\.rest|mdisk")

    @Volatile
    private var registryDomain: String? = null

    @Volatile
    private var registryCheckedAt = 0L

    private val junk = Regex(
        "winexch|a-ads|tinyurl|t\\.me/|/tg/|telegram|googleapis|googletagmanager|schema\\.org|w3\\.org"
    )

    private suspend fun registryDomain(): String? {
        val now = System.currentTimeMillis()
        if (registryDomain == null && now - registryCheckedAt > 10 * 60 * 1000L) {
            registryCheckedAt = now
            registryDomain = try {
                val text = app.get(DOMAIN_REGISTRY, timeout = 10L).text
                JSONObject(text).optString("movies4u").takeIf { it.startsWith("http") }
            } catch (_: Exception) {
                null
            }
        }
        return registryDomain
    }

    private suspend fun candidates(): List<String> = buildList {
        registryDomain()?.let { add(it) }
        add(DEFAULT_DOMAIN)
    }.distinct()

    private suspend fun searchPosts(domain: String, query: String): List<Pair<String, String>>? {
        val text = MovieFoxSiteNet.retry {
            try {
                app.get(
                    "$domain/lookup.php?q=${Uri.encode(query)}&page=1&per_page=30",
                    headers = MovieFoxSiteNet.headers(),
                    timeout = 15L
                ).text
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return try {
            val hits = JSONObject(text).optJSONArray("hits") ?: return emptyList()
            (0 until hits.length()).mapNotNull { i ->
                val hit = hits.optJSONObject(i) ?: return@mapNotNull null
                val postTitle = hit.optString("post_title")
                val permalink = hit.optString("permalink")
                if (postTitle.isBlank() || permalink.isBlank()) null
                else postTitle to MovieFoxSiteNet.absolute(permalink, domain)
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findPosts(title: String): List<Pair<String, String>> {
        for (domain in candidates()) {
            val posts = searchPosts(domain, title) ?: continue
            return posts.map { (postTitle, url) ->
                postTitle to url.replaceFirst(Regex("^[^/]*//[^/]+"), domain)
            }
        }
        return emptyList()
    }

    private suspend fun getDoc(url: String): Document? = try {
        app.get(url, headers = MovieFoxSiteNet.headers(MovieFoxSiteNet.baseUrlOf(url)), timeout = 20L).document
    } catch (_: Exception) {
        null
    }

    private fun qualityBlocks(doc: Document): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (block in doc.select("div.downloads-btns-div")) {
            var label = ""
            var sib = block.previousElementSibling()
            while (sib != null) {
                val text = sib.tagName().let { if (it == "h2" || it == "h3" || it == "h4") sib.text() else "" }
                if (text.isNotBlank()) {
                    label = text.replace(Regex("\\s+"), " ").trim()
                    break
                }
                sib = sib.previousElementSibling()
            }
            for (a in block.select("a[href]")) {
                val href = a.attr("href").trim()
                val text = a.text()
                if (!href.startsWith("http")) continue
                if (text.contains("Batch", true) || text.contains("Zip", true)) continue
                if (junk.containsMatchIn(href)) continue
                out.add(href to label)
            }
        }
        return out.distinctBy { it.first }
    }

    private suspend fun emitM4uLinks(
        linksUrl: String,
        label: String,
        episode: Int?,
        season: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = getDoc(linksUrl) ?: return
        val quality = if (label.isBlank()) null else MovieFoxSiteNet.getIndexQuality(label)

        val rows = mutableListOf<Pair<String, String>>()
        if (episode != null) {
            val epRegex = Regex("(?i)Episodes?\\s*:\\s*0*$episode(?!\\d)")
            for (h5 in doc.select("h5")) {
                if (!epRegex.containsMatchIn(h5.text())) continue
                var sib = h5.nextElementSibling()
                while (sib != null && sib.tagName() != "h5") {
                    for (a in sib.select("a[href]")) {
                        val href = a.attr("href").trim()
                        if (href.startsWith("http") && !junk.containsMatchIn(href)) {
                            rows.add(href to label)
                        }
                    }
                    sib = sib.nextElementSibling()
                }
            }
        }
        if (rows.isEmpty()) {
            for ((href, headLabel) in qualityBlocks(doc)) {
                rows.add(href to headLabel.ifBlank { label })
            }
        }
        if (rows.isEmpty()) {
            for (block in doc.select("div.downloads-btns-div")) {
                for (a in block.select("a[href]")) {
                    val href = a.attr("href").trim()
                    val text = a.text()
                    if (!href.startsWith("http")) continue
                    if (text.contains("Batch", true) || text.contains("Zip", true)) continue
                    if (junk.containsMatchIn(href)) continue
                    rows.add(href to label)
                }
            }
        }

        coroutineScope {
            rows.distinctBy { it.first }.forEach { (href, rowLabel) ->
                async(Dispatchers.IO) {
                    val rowQuality = if (rowLabel.isBlank()) quality else MovieFoxSiteNet.getIndexQuality(rowLabel)
                    emitSource(href, rowLabel, rowQuality, linksUrl, season, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun emitSource(
        url: String,
        label: String,
        quality: Int?,
        referer: String?,
        season: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = MovieFoxSiteNet.hostOf(url)
        when {
            host.contains("m4ulinks") ->
                emitM4uLinks(url, label, null, season, subtitleCallback, callback)
            driveHostRegex.containsMatchIn(url) ->
                MovieFoxDrivePages.emit("movies4u", url, null, season, label, null, referer, subtitleCallback, callback)
            else ->
                loadExtractor(url, referer, subtitleCallback) { link ->
                    val name = MovieFoxSiteLabels.buildLabel("movies4u", link.name, label)
                    if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, quality ?: link.quality, name, link.type == ExtractorLinkType.M3U8)) {
                        runBlocking { callback(link) }
                    }
                }
        }
    }

    suspend fun invoke(
        res: MovieFoxPlayData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (res.title.isNullOrBlank()) return
            var chosen: List<Pair<String, String>> = emptyList()

            for (query in MovieFoxSiteNet.searchQueries(res)) {
                val posts = findPosts(query)
                if (posts.isEmpty()) continue

                val matched = posts.filter { (postTitle, _) -> MovieFoxSiteNet.titleMatchesFor(postTitle, res) }
                if (matched.isEmpty()) continue
                chosen = if (res.season != null) {
                    val seasonPosts = matched.filter { (postTitle, _) ->
                        MovieFoxSiteNet.seasonsOf(postTitle)?.contains(res.season) == true
                    }
                    if (seasonPosts.isNotEmpty()) seasonPosts
                    else matched.filter { (postTitle, _) -> MovieFoxSiteNet.seasonsOf(postTitle) == null }
                } else {
                    matched.filter { (postTitle, _) -> MovieFoxSiteNet.yearMatches(postTitle, res.matchYear) }
                }.take(2)
                if (chosen.isNotEmpty()) break
            }
            if (chosen.isEmpty()) return

            chosen.forEach { (_, postUrl) ->
                try {
                    val doc = getDoc(postUrl) ?: return@forEach
                    if (res.season == null) {
                        val blocks = qualityBlocks(doc)
                        coroutineScope {
                            blocks.forEach { (href, label) ->
                                async(Dispatchers.IO) {
                                    emitSource(href, label, MovieFoxSiteNet.getIndexQuality(label), postUrl, null, subtitleCallback, callback)
                                }
                            }
                        }
                    } else {
                        val seasonRegex = Regex("(?i)Season\\s*${res.season}(?!\\d)")
                        val blocks = qualityBlocks(doc).filter { (_, label) ->
                            seasonRegex.containsMatchIn(label)
                        }.ifEmpty {
                            qualityBlocks(doc).filter { (_, label) -> MovieFoxSiteNet.seasonsOf(label) == null }
                        }
                        blocks.forEach { (href, label) ->
                            emitM4uLinks(href, label, res.episode, res.season, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}

internal object MovieFoxTmfSite {
    private const val DEFAULT_DOMAIN = "https://themoviesflix.com"

    private fun searchAnchors(doc: Document): List<Pair<String, String>> {
        return doc.select("article.latestpost a[id=featured-thumbnail]").mapNotNull { el ->
            val t = el.attr("title").trim().ifBlank { el.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            val href = el.attr("href").trim()
            if (t.isNotBlank() && href.startsWith("http")) t to href else null
        }
    }

    private val packRegex = Regex("""(?i)\b(zip|rar|7z|batch)\b""")

    private data class DriveGroup(val label: String, val redirectUrl: String)

    private fun downloadGroups(doc: Document): List<DriveGroup> {
        return doc.select("div.mfx-download-group").flatMap { div ->
            val label = div.selectFirst("h3")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (packRegex.containsMatchIn(label)) return@flatMap emptyList()
            div.select("a[href]").mapNotNull { a ->
                val text = a.text().trim()
                val href = a.attr("href").trim()
                when {
                    !href.startsWith("http") -> null
                    text.contains("Batch", true) || text.contains("Zip", true) -> null
                    packRegex.containsMatchIn(text) -> null
                    else -> DriveGroup(label, href)
                }
            }
        }.distinctBy { it.redirectUrl }
    }

    suspend fun invoke(
        res: MovieFoxPlayData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (res.title.isNullOrBlank()) return
            var target: Pair<String, String>? = null
            for (query in MovieFoxSiteNet.searchQueries(res)) {
                val searchDoc = runCatching {
                    app.get("$DEFAULT_DOMAIN/?s=${Uri.encode(query)}", headers = MovieFoxSiteNet.headers(), timeout = 15L).document
                }.getOrNull() ?: continue
                val anchors = searchAnchors(searchDoc)
                if (anchors.isEmpty()) continue

                val matched = anchors.filter { (t, _) -> MovieFoxSiteNet.titleMatchesFor(t, res) }
                if (matched.isEmpty()) continue
                target = matched.firstOrNull { (t, _) ->
                    if (res.season != null) MovieFoxSiteNet.seasonsOf(t)?.contains(res.season) == true
                    else MovieFoxSiteNet.yearMatches(t, res.matchYear)
                } ?: matched.firstOrNull { (t, _) ->
                    res.season != null && MovieFoxSiteNet.seasonsOf(t) == null
                }
                if (target != null) break
            }
            if (target == null) return

            val postDoc = runCatching {
                app.get(target.second, headers = MovieFoxSiteNet.headers(DEFAULT_DOMAIN), timeout = 20L).document
            }.getOrNull() ?: return

            val driveUrls = downloadGroups(postDoc).filter { (label, _) ->
                if (res.season == null) true
                else {
                    val seasonRegex = Regex("(?i)Season\\s*${res.season}(?!\\d)")
                    seasonRegex.containsMatchIn(label) || MovieFoxSiteNet.seasonsOf(label)?.contains(res.season) == true
                }
            }.map { it.redirectUrl }

            coroutineScope {
                driveUrls.forEach { driveUrl ->
                    async(Dispatchers.IO) {
                        MovieFoxDrivePages.emit("tmf", driveUrl, res.episode, res.season, "", DEFAULT_DOMAIN, target.second, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

internal object MovieFoxMultimoviesSite {
    private const val DEFAULT_DOMAIN = "https://m1.multimovies.courses"

    private data class MmCard(
        val title: String,
        val url: String,
        val year: Int?,
        val isTv: Boolean
    )

    private fun cardOf(el: Element, domain: String): MmCard? {
        val raw = el.selectFirst("[data-save-title]")?.attr("data-save-title") ?: return null
        val data = runCatching { JSONObject(raw.replace("&quot;", "\"")) }.getOrNull() ?: return null
        val url = data.optString("url")
        val title = data.optString("title")
        if (url.isBlank() || title.isBlank()) return null
        val isTv = data.optString("type") == "tv" || url.contains("/series/")
        return MmCard(title, MovieFoxSiteNet.absolute(url, domain), data.optInt("year", 0).takeIf { it > 0 }, isTv)
    }

    private suspend fun searchCards(domain: String, query: String): List<MmCard> = try {
        val doc = app.get("$domain/search?q=${Uri.encode(query)}", headers = MovieFoxSiteNet.headers(), timeout = 20L).document
        val seen = HashSet<String>()
        doc.select("article.poster-card").mapNotNull { cardOf(it, domain) }.filter { seen.add(it.url) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun extractBracedObject(html: String, marker: String): String {
        val start = html.indexOf(marker)
        if (start < 0) return ""
        val braceStart = html.indexOf('{', start)
        if (braceStart < 0) return ""
        var depth = 0
        var inString = false
        var escaped = false
        for (i in braceStart until html.length) {
            val ch = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                ch == '{' && !inString -> depth++
                ch == '}' && !inString -> {
                    depth--
                    if (depth == 0) return html.substring(braceStart, i + 1)
                }
            }
        }
        return ""
    }

    private data class WatchServer(val id: String, val name: String, val url: String)

    private fun parseWatchConfig(html: String): List<WatchServer> {
        val root = runCatching { JSONObject(extractBracedObject(html, "const watchConfig = ")) }.getOrNull()
            ?: return emptyList()
        val servers = root.optJSONArray("initialServers") ?: return emptyList()
        val out = mutableListOf<WatchServer>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val url = s.optString("url")
            if (url.isBlank()) continue
            out.add(WatchServer(s.optString("id"), s.optString("name"), url))
        }
        return out
    }

    private fun findEpisodeCard(doc: Document, domain: String, season: Int?, episode: Int?): String? {
        for (card in doc.select(".cinejoy-ep-card")) {
            val m = Regex("ep-item-(\\d+)-(\\d+)").find(card.attr("id")) ?: continue
            if (season != null && m.groupValues[1].toIntOrNull() != season) continue
            if (episode != null && m.groupValues[2].toIntOrNull() != episode) continue
            val href = card.selectFirst("a.cinejoy-ep-thumb-link")?.attr("href")?.trim().orEmpty()
            if (href.isNotBlank()) return MovieFoxSiteNet.absolute(href, domain)
        }
        return null
    }

    private suspend fun episodeHref(domain: String, seriesUrl: String, season: Int?, episode: Int?): String? {
        val base = seriesUrl.substringBefore('?')
        val first = runCatching { app.get(base, headers = MovieFoxSiteNet.headers(), timeout = 20L) }.getOrNull()
            ?: return null
        val renderedSeason = runCatching {
            JSONObject(extractBracedObject(first.text, "const watchConfig = ")).optInt("season", 1)
        }.getOrDefault(1)

        var doc = first.document
        if (season != null && season != renderedSeason) {
            doc = runCatching {
                app.get("$base?season=$season", headers = MovieFoxSiteNet.headers(), timeout = 20L).document
            }.getOrNull() ?: return null
        }
        findEpisodeCard(doc, domain, season, episode)?.let { return it }

        if (episode == null) return null
        val select = doc.selectFirst("select.episode-range-select") ?: return null
        val selected = select.selectFirst("option[selected]")?.attr("value")?.toIntOrNull()
        val target = select.select("option")
            .mapNotNull { it.attr("value").toIntOrNull() }
            .filter { it <= episode }
            .maxOrNull() ?: return null
        if (target == selected) return null
        val rangeDoc = runCatching {
            app.get("$base?season=${season ?: renderedSeason}&ep_range=$target", headers = MovieFoxSiteNet.headers(), timeout = 20L).document
        }.getOrNull() ?: return null
        return findEpisodeCard(rangeDoc, domain, season, episode)
    }

    private suspend fun resolvePost(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(pageUrl, headers = MovieFoxSiteNet.headers(), timeout = 20L)
            val servers = parseWatchConfig(res.text)
            if (servers.isEmpty()) return

            coroutineScope {
                servers.forEach { server ->
                    async(Dispatchers.IO) {
                        try {
                            loadExtractor(server.url, pageUrl, subtitleCallback) { link ->
                                val qVal = if (link.quality != Qualities.Unknown.value) link.quality else MovieFoxSiteNet.getIndexQuality(server.name)
                                val name = MovieFoxSiteLabels.buildLabel("multimovies", server.name, link.name)
                                if (MovieFoxSiteLabels.allowLink(CloudStreamApp.context, qVal, name, link.type == ExtractorLinkType.M3U8)) {
                                    runBlocking {
                                        callback(
                                            newExtractorLink("Multimovies", name, link.url, link.type) {
                                                this.quality = qVal
                                                this.referer = link.referer
                                                this.headers = link.headers
                                            }
                                        )
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun pickCard(cards: List<MmCard>, title: String, year: Int?): MmCard? {
        if (cards.isEmpty()) return null
        val norm = MovieFoxSiteNet.normalizeTitle(title)
        val exact = cards.filter { MovieFoxSiteNet.normalizeTitle(it.title) == norm }
        val pool = exact.ifEmpty { cards }
        return pool.firstOrNull { c ->
            c.year == null || year == null || kotlin.math.abs(c.year - year) <= 1
        }
    }

    suspend fun invoke(
        res: MovieFoxPlayData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = DEFAULT_DOMAIN
            val title = res.title ?: return
            var movie: MmCard? = null
            var show: MmCard? = null
            for (query in MovieFoxSiteNet.searchQueries(res)) {
                val cards = searchCards(domain, query)
                if (cards.isEmpty()) continue
                if (res.season == null) {
                    val matches = cards.filter { !it.isTv && MovieFoxSiteNet.titleMatchesFor(it.title, res) }
                    movie = pickCard(matches, title, res.year)
                } else {
                    val matches = cards.filter { it.isTv && MovieFoxSiteNet.titleMatchesFor(it.title, res) }
                    show = pickCard(matches, title, res.matchYear)
                }
                if (movie != null || show != null) break
            }
            if (res.season == null) {
                val target = movie ?: return
                resolvePost(target.url, subtitleCallback, callback)
            } else {
                val target = show ?: return
                val href = episodeHref(domain, target.url, res.season, res.episode) ?: return
                resolvePost(href, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }
}
