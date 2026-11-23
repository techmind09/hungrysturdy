version = 15
cloudstream {
    description = "MovieFox - Indian Hindi+English Movies&Web Series"
    authors = listOf("hungrysturdy")
    requiresResources = false

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    // You can find a list of available types here:
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf(
        "Movies&Series"
    )
    language = "hi"

    iconUrl = "https://raw.githubusercontent.com/techmind09/hungrysturdy/refs/heads/main/246481509.png"
}
