package com.hungrysturdy

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
