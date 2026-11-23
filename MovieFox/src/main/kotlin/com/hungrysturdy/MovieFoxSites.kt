package com.hungrysturdy

import java.net.URI
import java.net.URLEncoder

data class MfSiteEntry(
    val key: String,
    val title: String,
    val baseUrl: String,
    val probeUrl: String,
    val mirrors: List<String> = emptyList(),
    val apiEndpoints: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val firebaseKeys: List<String> = emptyList(),
    val registryKeys: List<String> = emptyList(),
    val category: String = "Movies",
    val isHindi: Boolean = true
)

object MfRegistries {
    const val FIREBASE_RTDB_URL =
        "https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json"

    const val KAPERWAN_URLS_JSON =
        "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"

    const val PHISHER_DOMAINS_JSON =
        "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"

    const val TMDB_API_BASE = "https://api.themoviedb.org/3"
    const val TMDB_WEB_BASE = "https://www.themoviedb.org"
    const val TMDB_IMAGE_W500 = "https://image.tmdb.org/t/p/w500"
    const val CINEMETA_BASE = "https://v3-cinemeta.strem.io/meta"
    const val AIO_METADATA_BASE =
        "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7/meta"

    const val SKY_UNIVERSE_REPO =
        "https://raw.githubusercontent.com/akashdh11/sky-universe/refs/heads/main/mega_repo.json"
    const val CNC_UNIVERSE_REPO =
        "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Repository/main/repo.json"

    const val DEFAULT_STREMIO_MANIFEST =
        "https://apps.soluserv.es/stremio_catalog_plus/c.8e2ce47b1df7502fd7c8f45140d5eb89691d641a/manifest.json"
}

object MfApiEndpoints {
    const val MOVY_STREAM_API = "https://api.wecollege.net"
    const val SPORT_API_BFF = "https://h5-sport-api.aoneroom.com/wefeed-h5api-bff"
    const val SPORT_WEB_URL = "https://www.sportslive.wine/"
    const val HDHUB4U_TYPESENSE = "https://search.pingora.fyi/collections/post/documents/search"
    const val FILEBEE_API = "https://filebee.xyz/api"
    const val DOTFLIX_STORE_EXTRACT = "https://dotflix.store/api/extract-download"
    const val DOTFLIX_FIT_EXTRACT = "https://dotflix.fit/api/extract-download"
    const val GOFILE_API = "https://api.gofile.io"
    const val SIDEXFEE_BYPASS = "https://web.sidexfee.com"
    const val CINETV_HLS_WORKER = "https://silent-frost-f2dbv.nxstreamhub.workers.dev/hls"

    val MOVIEBOX_API_HOSTS = listOf(
        "https://h5-api.aoneroom.com",
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com"
    )

    val DRIVE_MIRRORS = listOf(
        "https://nexdrive.beer",
        "https://nexdrive.fit",
        "https://mobilejsr.best",
        "https://mobilejsr.rest",
        "https://one.vegadrive.me",
        "https://one.vegadrive.app",
        "https://vcloud.beer",
        "https://vcloud.fit",
        "https://hubcloud.ist",
        "https://fastdl.zip",
        "https://hubcdn.wiki",
        "https://filebee.xyz"
    )

    val ALLOWED_STREAM_AND_FILE_HOSTS = listOf(
        "pixeldrain", "fsl2", "fsl", "gdrive", "gdflix", "gdlink", "gdtot",
        "hubcloud", "vcloud", "mcloud", "nexdrive", "mobilejsr", "mdrive.cloud", "m-drive",
        "vegadrive", "fastdl", "fastdlserver", "hubcdn", "filebee", "filepress", "fpgo",
        "dotflix", "gofile", "gpdl", ".r2.dev", ".r2.cloudflarestorage.com",
        "buzzserver", "10gbps", "instantcloud", "streamtape", "stape", "strtape",
        "voe.sx", "nxstreamhub", "itsnitrox", "vibuxer", "wootly", "videostr",
        "streameeeeee", "megaplay", "vidwish", "vidtube", "modiplay", "vibeplayer",
        "vivibebe", "bibiemb", "iqsmartgames", "vidara.to", "bysetayico.com",
        "vidout.pages.dev", "vidsync.pro", "vidrift.net", "bingr.one", "filmu.in",
        "vidbolt.xyz", "nxsha.space", "hdstream4u.com", "hubstream.art", "playmogo.com",
        "otakuvid.online", "otakuhg.site", "minochinos.com", "zephyrix", "zephyrflick",
        "88player.top", "acek-cdn.com", "workers.dev", "googleusercontent.com", "drive.google.com"
    )
}

object MfSites {

    val ALL: List<MfSiteEntry> = listOf(
        MfSiteEntry(
            key = "moviefox",
            title = "MovieFox Main",
            baseUrl = "https://moviefox.pro",
            probeUrl = "https://moviefox.pro/",
            apiEndpoints = listOf(
                "https://moviefox.pro/api/stream/purstream",
                "https://moviefox.pro/api/stream/castle",
                "https://moviefox.pro/api/stream/watchout"
            ),
            firebaseKeys = listOf("moviefox"),
            category = "Movies",
            isHindi = true
        ),
        MfSiteEntry(
            key = "purstream",
            title = "Purstream",
            baseUrl = "https://moviefox.pro",
            probeUrl = "https://moviefox.pro/api/stream/purstream?type=movie&id=550",
            apiEndpoints = listOf("https://moviefox.pro/api/stream/purstream"),
            category = "Movies",
            isHindi = true
        ),
        MfSiteEntry(
            key = "castle",
            title = "Castle",
            baseUrl = "https://moviefox.pro",
            probeUrl = "https://moviefox.pro/api/stream/castle?type=movie&id=550",
            apiEndpoints = listOf("https://moviefox.pro/api/stream/castle"),
            category = "Movies",
            isHindi = true
        ),
        MfSiteEntry(
            key = "watchout",
            title = "Watchout",
            baseUrl = "https://moviefox.pro",
            probeUrl = "https://moviefox.pro/api/stream/watchout?type=movie&id=550",
            apiEndpoints = listOf("https://moviefox.pro/api/stream/watchout"),
            category = "Movies",
            isHindi = false
        ),
        MfSiteEntry(
            key = "cinetv_api",
            title = "CineTV API",
            baseUrl = "https://cinetv.app",
            probeUrl = "https://cinetv.app/en",
            mirrors = listOf("https://cinetv.app/en", "https://cinetv.app"),
            apiEndpoints = listOf(
                "https://cinetv.app/api/stream/movie/{id}",
                "https://cinetv.app/api/stream/tv/{id}/{season}/{episode}",
                "https://cinetv.app/api/sources"
            ),
            category = "Movies",
            isHindi = true
        ),
        MfSiteEntry(
            key = "cinetv_hls",
            title = "CineTV HLS",
            baseUrl = "https://silent-frost-f2dbv.nxstreamhub.workers.dev",
            probeUrl = "https://silent-frost-f2dbv.nxstreamhub.workers.dev/hls/movie/550",
            apiEndpoints = listOf(
                "https://silent-frost-f2dbv.nxstreamhub.workers.dev/hls/movie/{id}",
                "https://silent-frost-f2dbv.nxstreamhub.workers.dev/hls/tv/{id}/{season}/{episode}"
            ),
            category = "Movies",
            isHindi = true
        ),
        MfSiteEntry(
            key = "vegamovies",
            title = "VegaMovies",
            baseUrl = "https://vegamovies.lease",
            probeUrl = "https://vegamovies.lease/",
            mirrors = listOf(
                "https://vegamovies.lease",
                "https://new1.vegamovies.lease",
                "https://vegamovies.wales",
                "https://rogmovies.autos",
                "https://rogmovies.im",
                "https://rogmovies.one",
                "https://rogmovies.asia",
                "https://rogmovies.vip"
            ),
            apiEndpoints = listOf("{base}/ts-search.php"),
            aliases = listOf("vegamovies.lease", "new1.vegamovies.lease", "vegamovies.wales"),
            firebaseKeys = listOf("vegamovies", "justplay_vegamovies"),
            registryKeys = listOf("vegamovies"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "rogmovies",
            title = "RogMovies",
            baseUrl = "https://rogmovies.autos",
            probeUrl = "https://rogmovies.vip/",
            mirrors = listOf(
                "https://rogmovies.autos",
                "https://rogmovies.im",
                "https://rogmovies.one",
                "https://rogmovies.asia",
                "https://rogmovies.vip"
            ),
            apiEndpoints = listOf("{base}/ts-search.php"),
            aliases = listOf("rogmovies.autos", "rogmovies.im", "rogmovies.one", "rogmovies.asia", "rogmovies.vip"),
            firebaseKeys = listOf("rogmovies", "justplay_rogmovies"),
            registryKeys = listOf("rogmovies"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "bollyflix",
            title = "Bollyflix",
            baseUrl = "https://bollyflix.frl",
            probeUrl = "https://new1.bollyflix.tours/",
            mirrors = listOf("https://new1.bollyflix.tours", "https://bollyflix.frl"),
            apiEndpoints = listOf("{base}/search/{query}/page/{page}/", MfApiEndpoints.SIDEXFEE_BYPASS),
            firebaseKeys = listOf("bollyflix", "justplay_bollyflix"),
            registryKeys = listOf("bollyflix"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "play4u",
            title = "Play4u",
            baseUrl = "https://play4u.org",
            probeUrl = "https://play4u.org/api/search.php?q=&page=1&per_page=24",
            mirrors = listOf("https://play4u.org"),
            apiEndpoints = listOf(
                "https://play4u.org/api/search.php",
                "https://play4u.org/watch/{imdbId}",
                "https://play4u.org/series/{imdbId}"
            ),
            firebaseKeys = listOf("play4u", "justplay_play4u"),
            registryKeys = listOf("play4u"),
            category = "Movies",
            isHindi = true
        ),
        MfSiteEntry(
            key = "hdhub4u",
            title = "HDHub4u",
            baseUrl = "https://new13.hdhub4u.cl",
            probeUrl = "https://new15.hdhub4u.cl/?utm=mn1",
            mirrors = listOf("https://new15.hdhub4u.cl", "https://new13.hdhub4u.cl"),
            apiEndpoints = listOf(
                MfApiEndpoints.HDHUB4U_TYPESENSE,
                "{base}/drive/search-recover.php",
                "{base}/?s={query}"
            ),
            firebaseKeys = listOf("hdhub4u", "justplay_hdhub4u"),
            registryKeys = listOf("HDHUB4u", "hdhub4u"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "dramaverse",
            title = "DramaVerse",
            baseUrl = "https://mydverse.com",
            probeUrl = "https://mydverse.com/",
            mirrors = listOf("https://mydverse.com"),
            apiEndpoints = listOf("https://mydverse.com/?s={query}"),
            category = "Series",
            isHindi = true
        ),
        MfSiteEntry(
            key = "gokuhd",
            title = "GokuHD",
            baseUrl = "https://gokuhd.nl",
            probeUrl = "https://gokuhd.nl/",
            mirrors = listOf("https://gokuhd.nl"),
            apiEndpoints = listOf("https://gokuhd.nl/search.php"),
            category = "Anime",
            isHindi = true
        ),
        MfSiteEntry(
            key = "animeworldindia",
            title = "Anime World India",
            baseUrl = "https://watchanimeworld.top",
            probeUrl = "https://watchanimeworld.top/",
            mirrors = listOf("https://watchanimeworld.top"),
            apiEndpoints = listOf(
                "{base}/page/{page}/?s={query}",
                "{base}/wp-admin/admin-ajax.php?action=action_select_season",
                "{playerBase}/player/index.php?data={videoId}&do=getVideo"
            ),
            firebaseKeys = listOf("animeworldindia"),
            category = "Anime",
            isHindi = true
        ),
        MfSiteEntry(
            key = "moviebox",
            title = "MovieBox / NetNaija API",
            baseUrl = "https://h5-api.aoneroom.com",
            probeUrl = "https://h5-api.aoneroom.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&page=1&perPage=1",
            mirrors = MfApiEndpoints.MOVIEBOX_API_HOSTS,
            apiEndpoints = listOf(
                "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list",
                "{host}/wefeed-mobile-bff/tab/ranking-list",
                "{host}/wefeed-mobile-bff/subject-api/list",
                "{host}/wefeed-mobile-bff/subject-api/search/v2",
                "{host}/wefeed-mobile-bff/subject-api/detail-rec",
                "{host}/wefeed-mobile-bff/subject-api/get",
                "{host}/wefeed-mobile-bff/subject-api/season-info",
                "{host}/wefeed-mobile-bff/subject-api/play-info",
                "{host}/wefeed-mobile-bff/subject-api/get-stream-captions",
                "{host}/wefeed-mobile-bff/subject-api/get-ext-captions",
                "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/trending",
                "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/search",
                "https://h5-api.aoneroom.com/wefeed-h5api-bff/detail",
                "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/play",
                "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/caption"
            ),
            firebaseKeys = listOf("netnaijabox"),
            category = "Movies",
            isHindi = true
        ),
        MfSiteEntry(
            key = "netnaija",
            title = "NetNaija",
            baseUrl = "https://netnaija.film",
            probeUrl = "https://netnaija.film/",
            mirrors = listOf(
                "https://netnaija.film",
                "https://www.thenetnaija.net",
                "https://123moviesfree.club",
                "https://movie-box.co",
                "https://movieboxonline.net",
                "https://movieboxhd.net",
                "https://sflix.film"
            ),
            apiEndpoints = listOf(
                "{base}/search?t={query}",
                "{domain}/wefeed-h5api-bff/subject/play"
            ),
            firebaseKeys = listOf("netnaija", "justplay_netnaija"),
            category = "Series",
            isHindi = false
        ),
        MfSiteEntry(
            key = "sportslive",
            title = "SportsLive",
            baseUrl = "https://www.sportslive.wine",
            probeUrl = "https://h5-sport-api.aoneroom.com/wefeed-h5api-bff/live/match-list-v5",
            mirrors = listOf("https://www.sportslive.wine"),
            apiEndpoints = listOf(
                "https://h5-sport-api.aoneroom.com/wefeed-h5api-bff/live/match-list-v5",
                "https://h5-sport-api.aoneroom.com/wefeed-h5api-bff/live/match-detail"
            ),
            category = "Movies",
            isHindi = false
        ),
        MfSiteEntry(
            key = "hicine",
            title = "HiCine",
            baseUrl = "https://hicine.biz",
            probeUrl = "https://hicine.biz/hapi/home",
            mirrors = listOf("https://hicine.biz", "https://hicine.lol"),
            apiEndpoints = listOf(
                "{base}/hapi/home",
                "{base}/hapi/browse",
                "{base}/hapi/search",
                "{base}/hapi/t",
                "{base}/hapi/g",
                "{base}/hapi/dl",
                "{base}/hapi/go"
            ),
            firebaseKeys = listOf("hicine", "justplay_hicine"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "hindmoviez",
            title = "HindMoviez",
            baseUrl = "https://v3.hindmovie.im",
            probeUrl = "https://v3.hindmovie.im/",
            mirrors = listOf("https://v3.hindmovie.im"),
            apiEndpoints = listOf("{base}/?s={query}"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "movielinkbd",
            title = "MovieLinkBD",
            baseUrl = "https://drjuea.movielinkbd.at",
            probeUrl = "https://drjuea.movielinkbd.at/",
            mirrors = listOf(
                "https://drjuea.movielinkbd.at",
                "https://movielinkbd.com"
            ),
            apiEndpoints = listOf(
                "{base}/search?q={query}",
                "{base}/go/{token}",
                "{base}/file/{id}"
            ),
            aliases = listOf("movielinkbd.com", "movielinkbd.at", "movielinkbd.li", "movielinkbd.tv", "movielinkbd.app", "movielinkbd.net"),
            firebaseKeys = listOf("movielinkbd", "justplay_movielinkbd"),
            category = "Movies",
            isHindi = false
        ),
        MfSiteEntry(
            key = "netmirror",
            title = "NetMirror",
            baseUrl = "https://netmirror.gold",
            probeUrl = "https://netmirror.gold/",
            mirrors = listOf(
                "https://netmirror.gold",
                "https://net52.cc",
                "https://net52.cc/mobile",
                "https://net52.cc/mobile/hs",
                "https://net52.cc/mobile/pv"
            ),
            apiEndpoints = listOf(
                "{base}/mobile/home?app=1",
                "https://userver.net52.cc/",
                "{base}/mobile/verify2.php",
                "{base}{ott}/search.php",
                "{base}{ott}/post.php",
                "{base}{ott}/episodes.php",
                "{base}{ott}/playlist.php"
            ),
            firebaseKeys = listOf("netmirror", "justplay_netmirror"),
            category = "Series",
            isHindi = true
        ),
        MfSiteEntry(
            key = "movy",
            title = "Movy",
            baseUrl = "https://www.movy.sx",
            probeUrl = "https://www.movy.sx/",
            mirrors = listOf("https://www.movy.sx"),
            apiEndpoints = listOf(
                "https://api.wecollege.net/seed",
                "https://api.wecollege.net/{city}/sources"
            ),
            firebaseKeys = listOf("movy", "justplay_movy"),
            category = "Movies",
            isHindi = true
        ),
        MfSiteEntry(
            key = "4khdhub",
            title = "4KHDHub",
            baseUrl = "https://4khdhub.one",
            probeUrl = "https://4khdhub.one/",
            mirrors = listOf("https://4khdhub.one"),
            apiEndpoints = listOf("{base}/?s={query}"),
            firebaseKeys = listOf("4khdhub", "justplay_4khdhub"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "movies4u",
            title = "Movies4u",
            baseUrl = "https://new6.movies4u.garden",
            probeUrl = "https://new8.movies4u.garden/",
            mirrors = listOf(
                "https://new8.movies4u.garden",
                "https://new6.movies4u.garden",
                "https://movies4u.vet"
            ),
            apiEndpoints = listOf("{base}/lookup.php"),
            firebaseKeys = listOf("movies4u", "justplay_movies4u"),
            registryKeys = listOf("movies4u"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "moviesmod",
            title = "Moviesmod",
            baseUrl = "https://moviesleech.rest",
            probeUrl = "https://moviesleech.rest/",
            mirrors = listOf("https://moviesleech.rest"),
            apiEndpoints = listOf("{base}/page/{page}", "{base}/search/{query}/page/{page}"),
            firebaseKeys = listOf("moviesmod", "justplay_moviesmod"),
            registryKeys = listOf("moviesmod"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "topmovies",
            title = "TopMovies",
            baseUrl = "https://topmovies.pet",
            probeUrl = "https://topmovies.pet/",
            mirrors = listOf("https://topmovies.pet"),
            apiEndpoints = listOf("{base}/page/{page}", "{base}/search/{query}/page/{page}"),
            firebaseKeys = listOf("topmovies", "justplay_topmovies"),
            registryKeys = listOf("topmovies"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "multimovies",
            title = "Multimovies",
            baseUrl = "https://m1.multimovies.courses",
            probeUrl = "https://m1.multimovies.courses/",
            mirrors = listOf("https://m1.multimovies.courses", "https://multimovies.garden"),
            apiEndpoints = listOf(
                "{base}/search?q={query}",
                "https://rozgarlelo.modiplay.xyz/proxy.php",
                "https://rozgarlelo.modiplay.xyz/api/v1/video",
                "https://vidara.to/api/stream",
                "https://bysetayico.com/api/videos/{code}/embed/details",
                "https://streams.iqsmartgames.com/mymovieapi",
                "https://streams.iqsmartgames.com/myseriesapi",
                "https://pro.iqsmartgames.com/embedhelper2.php",
                "https://api.bingr.one/stream",
                "https://embed.filmu.in/api/singularity-movie",
                "https://embed.filmu.in/api/singularity-tv",
                "https://vidbolt.xyz/api/resolve",
                "https://scraper.vidbolt.xyz/scrape",
                "https://nxsha.space/api/sources"
            ),
            firebaseKeys = listOf("multimovies", "justplay_multimovies"),
            category = "Anime",
            isHindi = true
        ),
        MfSiteEntry(
            key = "bollyhq",
            title = "BollyHQ",
            baseUrl = "https://bolly-hq.info",
            probeUrl = "https://bolly-hq.info/",
            mirrors = listOf("https://bolly-hq.info"),
            apiEndpoints = listOf("{base}/?s={query}"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "moviesdrive",
            title = "MoviesDrive",
            baseUrl = "https://new3.moviesdrive.cyou",
            probeUrl = "https://new4.moviesdrive.cyou/",
            mirrors = listOf("https://new4.moviesdrive.cyou", "https://new3.moviesdrive.cyou"),
            apiEndpoints = listOf("{base}/search.php"),
            firebaseKeys = listOf("moviesdrive", "justplay_moviesdrive"),
            registryKeys = listOf("moviesdrive"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "filmyfly",
            title = "FilmyFly",
            baseUrl = "https://filmyfly.kred",
            probeUrl = "https://filmyfly.kred/",
            mirrors = listOf("https://filmyfly.kred"),
            apiEndpoints = listOf("{base}/?s={query}"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "filmyking",
            title = "FilmyKing",
            baseUrl = "https://filmyking.co",
            probeUrl = "https://filmyking.co/",
            mirrors = listOf("https://filmyking.co"),
            apiEndpoints = listOf("{base}/?s={query}"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "myfilms",
            title = "MyFilms",
            baseUrl = "https://myfilms.pro",
            probeUrl = "https://myfilms.pro/",
            mirrors = listOf("https://myfilms.pro"),
            apiEndpoints = listOf("{base}/?s={query}"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "vipfilm",
            title = "VipFilm",
            baseUrl = "https://vipfilm.pro",
            probeUrl = "https://vipfilm.pro/",
            mirrors = listOf("https://vipfilm.pro"),
            apiEndpoints = listOf("{base}/?s={query}"),
            category = "Hindi",
            isHindi = true
        ),
        MfSiteEntry(
            key = "kmmovies",
            title = "KMmovies",
            baseUrl = "https://kmmovies.quest",
            probeUrl = "https://kmmovies.quest/",
            mirrors = listOf("https://kmmovies.quest"),
            apiEndpoints = listOf("{base}/?s={query}"),
            category = "Hindi",
            isHindi = true
        )
    )

    private val byKeyMap: Map<String, MfSiteEntry> = ALL.associateBy { it.key.lowercase() }

    private fun extractHost(raw: String): String = runCatching {
        val normalized = if (raw.contains("://")) raw.trim() else "https://${raw.trim()}"
        URI(normalized).host?.lowercase()?.removePrefix("www.").orEmpty()
    }.getOrDefault("")

    private fun ensureScheme(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun byKey(key: String): MfSiteEntry? = byKeyMap[key.lowercase()]

    fun siteOfUrl(url: String): MfSiteEntry? {
        val targetHost = extractHost(url)
        if (targetHost.isBlank()) return null
        return ALL.firstOrNull { entry ->
            val knownHosts = (listOf(entry.baseUrl) + entry.mirrors + entry.aliases)
                .map { extractHost(it) }
                .filter { it.isNotBlank() }
                .toSet()
            targetHost in knownHosts || knownHosts.any { h -> targetHost.endsWith(".$h") }
        }
    }

    fun candidatesFor(key: String): List<String> {
        val entry = byKey(key) ?: return emptyList()
        return (listOf(entry.baseUrl) + entry.mirrors)
            .map { ensureScheme(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun candidatesWithDynamic(key: String, dynamicFirst: String? = null): List<String> {
        val dynClean = dynamicFirst?.let { ensureScheme(it) }?.takeIf { it.isNotBlank() }
        return (listOfNotNull(dynClean) + candidatesFor(key)).distinct()
    }

    fun rewriteToBase(url: String, targetBase: String): String {
        val cleanTarget = ensureScheme(targetBase)
        if (!url.startsWith("http", ignoreCase = true) || cleanTarget.isBlank()) return url
        val entry = siteOfUrl(url) ?: return url
        val hostsToReplace = (listOf(entry.baseUrl) + entry.mirrors + entry.aliases)
            .map { extractHost(it) }
            .filter { it.isNotBlank() }
            .distinct()
        var out = url
        for (h in hostsToReplace) {
            out = out.replaceFirst(Regex("(?i)^https?://(?:www\\.)?${Regex.escape(h)}"), cleanTarget)
        }
        return out
    }

    fun resolveEndpoint(
        template: String,
        baseUrl: String,
        query: String = "",
        id: String = "",
        season: String = "1",
        episode: String = "1",
        page: Int = 1
    ): String {
        val cleanBase = ensureScheme(baseUrl)
        val cleanHost = extractHost(cleanBase)
        val encodedQuery = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        return template
            .replace("{base}", cleanBase)
            .replace("{domain}", cleanBase)
            .replace("{host}", cleanHost)
            .replace("{query}", encodedQuery)
            .replace("{id}", id)
            .replace("{imdbId}", id)
            .replace("{season}", season)
            .replace("{episode}", episode)
            .replace("{page}", page.toString())
    }

    fun isAllowedHost(nameOrUrl: String): Boolean {
        if (nameOrUrl.isBlank()) return false
        val low = nameOrUrl.lowercase()
        return MfApiEndpoints.ALLOWED_STREAM_AND_FILE_HOSTS.any { low.contains(it) }
    }
}
