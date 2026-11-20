package com.hungrysturdy

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.USER_AGENT
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

open class CineStreamGenericExtractor(
    override val name: String,
    override val mainUrl: String,
    override val requiresReferer: Boolean = false
) : ExtractorApi() {

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace("\\/", "/").trim()
        if (cleanUrl.isBlank()) return

        if (Regex("""(?i)\.(m3u8|mpd|mp4|mkv|webm)(\?|$)""").containsMatchIn(cleanUrl)) {
            val isHls = cleanUrl.contains(".m3u8", true) || cleanUrl.contains(".mpd", true)
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name • ${if (isHls) "Stream" else "Direct"}",
                    url = cleanUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to (referer ?: "$mainUrl/")
                    )
                }
            )
            return
        }

        val html = runCatching {
            app.get(
                cleanUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to (referer ?: "$mainUrl/")
                ),
                timeout = 5L
            ).text
        }.getOrNull().orEmpty()
        if (html.isBlank()) return

        val searchBlob = "$html\n${runCatching { getAndUnpack(html) }.getOrDefault("")}"
        val foundUrls = LinkedHashSet<String>()

        Regex("""https?://[^\s"'<>\\)\]]+\.(?:m3u8|mpd|mp4|mkv|webm)(?:\?[^\s"'<>\\)\]]*)?""", RegexOption.IGNORE_CASE)
            .findAll(searchBlob)
            .forEach { foundUrls.add(it.value.replace("\\/", "/")) }

        Jsoup.parse(html, cleanUrl).select("video source[src], a[href]").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("href") }.trim()
            if (raw.startsWith("http") && Regex("""(?i)\.(m3u8|mpd|mp4|mkv|webm|r2\.dev|r2\.cloudflarestorage\.com|pixeldrain)""").containsMatchIn(raw)) {
                foundUrls.add(raw)
            }
        }

        foundUrls.forEach { mediaUrl ->
            val isHls = mediaUrl.contains(".m3u8", true) || mediaUrl.contains(".mpd", true)
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name • ${if (isHls) "HLS" else "MP4"}",
                    url = mediaUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = cleanUrl
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to cleanUrl
                    )
                }
            )
        }
    }
}

class HubCloud : CineStreamGenericExtractor("HubCloud", "https://hubcloud.ink")
class VCloud : CineStreamGenericExtractor("VCloud", "https://vcloud.lol")
class GDLink : CineStreamGenericExtractor("GDLink", "https://gdlink.dev")
class GDFlixApp : CineStreamGenericExtractor("GDFlixApp", "https://new.gdflix.dad")
class GdFlix1 : CineStreamGenericExtractor("GdFlix1", "https://gdflix.dad")
class GdFlix2 : CineStreamGenericExtractor("GdFlix2", "https://gdflix.net")
class GDFlixNet : CineStreamGenericExtractor("GDFlixNet", "https://gdflix.net")
class GDFlix : CineStreamGenericExtractor("GDFlix", "https://new.gdflix.dad")
class fastdlserver : CineStreamGenericExtractor("FastDLServer", "https://fastdl.icu")
class Hubdrive : CineStreamGenericExtractor("Hubdrive", "https://hubdrive.fit")
class Driveseed : CineStreamGenericExtractor("Driveseed", "https://driveseed.org")
class Driveleech : CineStreamGenericExtractor("Driveleech", "https://driveleech.org")
class Howblogs : CineStreamGenericExtractor("Howblogs", "https://howblogs.xyz")
class Wootly : CineStreamGenericExtractor("Wootly", "https://wootly.ch")
class Gofile : CineStreamGenericExtractor("Gofile", "https://gofile.io")
class Videostr : CineStreamGenericExtractor("Videostr", "https://videostr.net")
class Streameeeeee : CineStreamGenericExtractor("Streameeeeee", "https://streamtape.com")
class PpzjYoutube : CineStreamGenericExtractor("PpzjYoutube", "https://youtube.com")
class Asianload : CineStreamGenericExtractor("Asianload", "https://asianload.io")
class Embedload : CineStreamGenericExtractor("Embedload", "https://embedload.com")
class FlixCloud : CineStreamGenericExtractor("FlixCloud", "https://flixcloud.icu")
class VibePlayer : CineStreamGenericExtractor("VibePlayer", "https://vibeplayer.net")
class Bibiemb : CineStreamGenericExtractor("Bibiemb", "https://bibiemb.com")
class Vivibebe : CineStreamGenericExtractor("Vivibebe", "https://vivibebe.com")
class Playmogo : CineStreamGenericExtractor("Playmogo", "https://playmogo.com")
class Otakuvid : CineStreamGenericExtractor("Otakuvid", "https://otakuvid.com")
class Otakhug : CineStreamGenericExtractor("Otakhug", "https://otakhug.com")
class MegaPlay : CineStreamGenericExtractor("MegaPlay", "https://megaplay.buzz")
class Vidtube : CineStreamGenericExtractor("Vidtube", "https://vidtube.one")
class Vidwish : CineStreamGenericExtractor("Vidwish", "https://vidwish.live")
class Modiplay : CineStreamGenericExtractor("Modiplay", "https://modiplay.xyz")
class Iqsmartgames : CineStreamGenericExtractor("Iqsmartgames", "https://iqsmartgames.com")
class Minochinos : CineStreamGenericExtractor("Minochinos", "https://minochinos.com")
class Hblinks : CineStreamGenericExtractor("Hblinks", "https://hblinks.pro")
class HdStream4u : CineStreamGenericExtractor("HdStream4u", "https://hdstream4u.com")
class Hubstream : CineStreamGenericExtractor("Hubstream", "https://hubstream.art")
